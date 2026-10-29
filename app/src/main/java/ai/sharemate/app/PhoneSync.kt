package ai.sharemate.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Phone side of live sync. Keeps a long-poll open to the car, mirrors each new revision into the phone's local copy,
 * and sends edits back as commands. The car remains the only writer of the log file that matters.
 */
class PhoneSyncClient private constructor(context: Context) {
    private val repository = TripLogRepository.get(context)
    private val store = DeviceLink(context, "fleet-phone")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableLink = MutableStateFlow(PhoneLink())
    val link: StateFlow<PhoneLink> = mutableLink.asStateFlow()
    private var job: Job? = null
    @Volatile private var config: LinkConfig? = runCatching { store.load() }.getOrNull()
    val paired get() = config != null

    /** Pairs with the car at [endpoint] using the six-digit code on the vehicle screen; the certificate seen then is pinned. */
    suspend fun pair(endpoint: String, code: String) {
        val base = endpoint.trim().trimEnd('/').let { if (it.startsWith("https://")) it else "https://$it" }
        require(URI(base).host?.isNotBlank() == true) { "차량 주소를 확인해 주세요." }
        require(code.matches(Regex("\\d{6}"))) { "6자리 연결 번호를 입력해 주세요." }
        var seen: String? = null
        // Trust-on-first-use, gated by the six-digit code the driver reads off the car screen: this manager only records
        // the presented certificate, and pairing succeeds only if the car's signed reply names that same fingerprint.
        // Every later request goes through DeviceLink, which pins exactly that certificate.
        @Suppress("CustomX509TrustManager")
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw java.security.cert.CertificateException() }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                require(chain.isNotEmpty()); chain[0].checkValidity()
                seen = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
            }
        }
        val response = withContext(Dispatchers.IO) {
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
            val connection = URI("$base/pair").toURL().openConnection() as HttpsURLConnection
            try {
                connection.sslSocketFactory = ssl.socketFactory
                connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                connection.connectTimeout = 5_000; connection.readTimeout = 5_000
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val status = try {
                    connection.outputStream.use { it.write(JSONObject().put("code", code).toString().toByteArray()) }
                    connection.responseCode
                } catch (e: java.io.IOException) {
                    throw IllegalArgumentException("차량에 닿지 않아요. 주소와 포트, 같은 Wi-Fi·핫스팟인지 확인해 주세요. (${e.javaClass.simpleName})")
                }
                val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "{}"
                val json = JSONObject(text)
                require(status == 200) { when (json.optString("error")) {
                    "wrong_code" -> "연결 번호가 달라요. 차량 화면의 번호를 다시 확인해 주세요."
                    "pairing_closed" -> "연결 번호가 만료됐어요. 차량에서 새 번호를 만들어 주세요."
                    else -> "차량이 연결을 거절했어요 ($status)."
                } }
                json
            } finally { connection.disconnect() }
        }
        val fingerprint = response.getString("fingerprint")
        // The certificate the car presented must be the one it claims; otherwise someone sat between us during pairing.
        require(seen != null && MessageDigest.isEqual(seen!!.toByteArray(), fingerprint.toByteArray())) { "차량 인증서를 확인하지 못했어요. 다시 연결해 주세요." }
        val text = "$base|${response.getString("token")}|$fingerprint"
        withContext(Dispatchers.IO) { store.save(text) }
        config = parseLink(text)
        mutableLink.value = PhoneLink(message = "차량 연결됨 · 동기화 중")
        start()
    }

    suspend fun unpair() {
        val current = config
        if (current != null) runCatching { store.request(current, "/v2/unpair", JSONObject()) }
        withContext(Dispatchers.IO) { store.clear() }
        config = null
        stop()
        mutableLink.value = PhoneLink(message = "차량 연결 해제됨")
    }

    fun start() {
        val current = config ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            var since = -1L
            var dataSince = -1L
            var failures = 0
            while (isActive) {
                try {
                    val remote = store.request(current, "/v2/state?since=$since&data=$dataSince&wait=1", timeoutMillis = SYNC_LONG_POLL_MILLIS + 8_000)
                    val revision = remote.getLong("revision")
                    if (remote.optBoolean("changed")) {
                        val data = TripLogCodec.decode(remote.getJSONObject("data").toString().toByteArray(Charsets.UTF_8))
                        // The car's trip in progress rides along so the phone can show it; it is never closed on the phone.
                        repository.importPhoneCopy(data, live = true)
                        dataSince = remote.getLong("dataRevision")
                    }
                    since = revision
                    failures = 0
                    mutableLink.update { it.copy(connected = true, revision = revision, lastSeenAt = System.currentTimeMillis(),
                        signal = runCatching { SignalStatus.valueOf(remote.getString("signal")) }.getOrNull(),
                        live = decodeLive(remote.optJSONObject("live")), message = "차량 연결됨") }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    failures++
                    mutableLink.update { it.copy(connected = false, message = if (failures < 3) "차량 응답 대기 중" else "차량과 연결되지 않아요 · 같은 네트워크인지 확인") }
                    delay(if (failures < 3) 2_000 else 6_000)
                }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** Sends one edit to the car and waits for the next revision so the phone screen updates right away. */
    suspend fun send(command: SyncCommand) {
        val current = config ?: throw IllegalArgumentException("차량과 먼저 연결해 주세요.")
        val result = store.request(current, "/v2/command", encodeSyncCommand(command))
        require(result.optBoolean("ok")) { result.optString("message").ifBlank { "차량이 변경을 받지 않았어요." } }
    }

    companion object {
        @Volatile private var instance: PhoneSyncClient? = null
        fun get(context: Context): PhoneSyncClient = instance ?: synchronized(this) { instance ?: PhoneSyncClient(context).also { instance = it } }
    }
}
