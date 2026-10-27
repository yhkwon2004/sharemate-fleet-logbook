package ai.sharemate.app

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class LinkConfig(val endpoint: String, val token: String, val fingerprint: String)
data class LinkStatus(val configured: Boolean = false, val message: String = "휴대폰 연결 전", val remote: JSONObject? = null, val updatedAt: Long = 0, val current: Boolean = false)

internal fun parseLink(text: String): LinkConfig {
    val parts = text.trim().split('|')
    require(parts.size == 3) { "연결 정보 전체를 입력해 주세요." }
    val url = URI(parts[0])
    require(url.scheme == "https" && !url.host.isNullOrBlank() && url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null && url.path in listOf("", "/")) { "HTTPS 서버 주소를 확인해 주세요." }
    require(parts[1].matches(Regex("[a-fA-F0-9]{64}")) && parts[2].matches(Regex("[a-fA-F0-9]{64}"))) { "연결 토큰과 인증서 지문을 확인해 주세요." }
    return LinkConfig(parts[0].trimEnd('/'), parts[1], parts[2].lowercase())
}

internal class DeviceLink(context: Context, role: String) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "link-$role.txt"))
    fun load(): LinkConfig? = if (!file.baseFile.exists()) null else parseLink(file.readFully().toString(Charsets.UTF_8))
    fun save(text: String) {
        parseLink(text)
        val stream = file.startWrite()
        try { stream.write(text.trim().toByteArray()); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun clear() = file.delete()

    suspend fun request(config: LinkConfig, path: String, payload: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw java.security.cert.CertificateException() }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                require(chain.isNotEmpty())
                chain[0].checkValidity()
                val pin = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(pin.toByteArray(), config.fingerprint.toByteArray())) throw java.security.cert.CertificateException("Certificate pin mismatch")
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
        val connection = URI(config.endpoint + path).toURL().openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory = ssl.socketFactory
            // The exact certificate is explicitly paired; never trust arbitrary self-signed certificates.
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { host, _ -> host == URI(config.endpoint).host }
            connection.connectTimeout = 4_000; connection.readTimeout = if (path == "/v1/analyze") 75_000 else 4_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer ${config.token}")
            if (payload != null) {
                val bytes = payload.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= if (path == "/v1/analyze") 4_200_000 else 262_144)
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream).use { stream ->
                requireNotNull(stream) { "서버 응답을 확인하지 못했습니다." }
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                while (true) { val count = stream.read(buffer); if (count < 0) break; require(output.size() + count <= 524_288); output.write(buffer, 0, count) }
                output.toByteArray()
            }
            val result = JSONObject(bytes.toString(Charsets.UTF_8))
            require(status in 200..299) { when (result.optString("error")) {
                "ai_not_configured" -> "서버에 AI 키가 연결되지 않았습니다. 촬영 원본은 안전하게 보관됩니다."
                "ai_auth_failed" -> "서버의 AI 키 또는 이용 권한을 확인해 주세요."
                "ai_rate_limited" -> "AI 이용 한도에 도달했습니다. 잠시 후 직접 다시 시도해 주세요."
                "ai_unavailable", "ai_invalid_response", "ai_incomplete", "ai_refused" -> "AI 결과를 확인하지 못했습니다. 원본을 직접 확인하거나 다시 요청해 주세요."
                "ai_invalid_image", "ai_invalid_request" -> "분석할 사진을 확인하지 못했습니다. 원본을 확인하고 다시 촬영해 주세요."
                "ai_busy" -> "다른 AI 판독이 진행 중입니다. 완료 후 다시 요청해 주세요."
                "session_mismatch", "evidence_mismatch", "pairing_closed" -> "현재 차량 이용과 촬영 기록의 연결을 다시 확인해 주세요."
                else -> "연결 응답을 확인해 주세요 ($status)."
            } }
            result
        } finally { connection.disconnect() }
    }
}

internal fun telemetry(state: AppState): JSONObject {
    val session = state.session
    val now = System.currentTimeMillis()
    val cost = session?.let { rentalCost(it, state.snapshot, now) }
    fun <T> Reading<T>.current(): Any = if (isFresh(now)) value as Any else JSONObject.NULL
    return JSONObject().apply {
        put("sessionId", session?.id ?: JSONObject.NULL); put("source", state.sourceMode.name); put("sentAt", now)
        put("returnAt", session?.returnAt ?: JSONObject.NULL); put("returnPlace", session?.returnPlace ?: "")
        put("soc", state.snapshot.soc.current()); put("charging", state.snapshot.charging.current())
        put("odometerKm", state.snapshot.odometerKm.current())
        put("distanceKm", session?.let { rentalDistance(it, state.snapshot, now) } ?: JSONObject.NULL)
        put("startedAt", session?.startedAt?.takeIf { it > 0 } ?: JSONObject.NULL)
        // The relay still validates the 1.4.0 per-minute tariff shape, and the phone shows only costWon.
        put("rates", JSONObject.NULL)
        put("costWon", cost?.totalWon ?: JSONObject.NULL); put("costAt", if (cost?.totalWon != null) now else JSONObject.NULL)
    }
}
