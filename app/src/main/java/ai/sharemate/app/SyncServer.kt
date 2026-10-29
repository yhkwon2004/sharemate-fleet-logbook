package ai.sharemate.app

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/** Paired phone tokens, kept beside the log and never backed up. */
private class PairingStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "phone-pairings.json"))
    fun load(): List<String> = runCatching {
        if (!file.baseFile.exists()) emptyList() else JSONArray(file.readFully().toString(Charsets.UTF_8)).let { a -> (0 until a.length()).map { a.getString(it) } }
    }.getOrDefault(emptyList()).filter(::validToken)
    fun save(tokens: List<String>) {
        val stream = file.startWrite()
        try { stream.write(JSONArray(tokens).toString().toByteArray()); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}

/**
 * HTTPS server that runs inside the recording service while the car is on. The key lives in the Android keystore, so the
 * self-signed certificate is stable across restarts and the phone pins its fingerprint at pairing.
 */
class SyncServer(context: Context, private val repository: TripLogRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pairings = PairingStore(context.applicationContext)
    private val keyFile = File(context.applicationContext.noBackupFilesDir, "sync-key.der")
    private val certFile = File(context.applicationContext.noBackupFilesDir, "sync-cert.der")
    @Volatile private var tokens = pairings.load()
    @Volatile private var pairingCode: String? = null
    @Volatile private var pairingExpiresAt = 0L
    @Volatile private var pairFailures = 0
    @Volatile private var lastPhoneSeenAt = 0L
    private var socket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var fingerprint = ""

    fun start() {
        if (acceptJob?.isActive == true) return
        active = this
        acceptJob = scope.launch {
            try {
                val server = openServerSocket()
                socket = server
                refreshPairingCode()
                publish()
                while (isActive) {
                    val client = try { server.accept() } catch (e: IOException) { if (isActive) continue else break }
                    launch { client.use { handle(it) } }
                }
            } catch (e: Exception) {
                repository.setSync(SyncInfo(error = "휴대폰 연결 서버를 시작하지 못했어요: ${e.javaClass.simpleName}${e.message?.take(90)?.let { " · $it" } ?: ""}"))
            }
        }
    }

    fun stop() {
        if (active === this) active = null
        acceptJob?.cancel(); acceptJob = null
        runCatching { socket?.close() }; socket = null
        scope.cancel()
        repository.setSync(SyncInfo())
    }

    fun refreshPairingCode() {
        pairingCode = newPairingCode()
        pairingExpiresAt = System.currentTimeMillis() + SYNC_PAIR_TTL
        pairFailures = 0
        publish()
    }

    fun forgetPhones() {
        tokens = emptyList()
        runCatching { pairings.save(tokens) }
        publish()
    }

    private fun publish() {
        repository.setSync(SyncInfo(running = socket?.isClosed == false, port = SYNC_PORT, addresses = localAddresses(),
            pairingCode = pairingCode?.takeIf { System.currentTimeMillis() < pairingExpiresAt }, pairingExpiresAt = pairingExpiresAt,
            pairedPhones = tokens.size, lastPhoneSeenAt = lastPhoneSeenAt))
    }

    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filter { it is java.net.Inet4Address && !it.isLoopbackAddress }.map { it.hostAddress ?: "" }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun openServerSocket(): ServerSocket {
        // The Pleos emulator's KeyMint refuses the raw-digest ECDSA operation TLS needs (INCOMPATIBLE_DIGEST), so the
        // identity is a software key kept in app-private, non-backed-up storage instead of the hardware keystore.
        val identity = TlsIdentity.load(keyFile, certFile)
        fingerprint = identity.fingerprint
        val keyStore = KeyStore.getInstance("PKCS12").apply { load(null); setKeyEntry(KEY_ALIAS, identity.privateKey, CharArray(0), arrayOf(identity.certificate)) }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, CharArray(0)) }.keyManagers
        val ssl = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
        return (ssl.serverSocketFactory.createServerSocket(SYNC_PORT, 8) as SSLServerSocket).apply { soTimeout = 0 }
    }

    private class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray)

    private fun readRequest(socket: Socket): Request? {
        socket.soTimeout = 10_000
        val input = BufferedInputStream(socket.getInputStream())
        fun line(): String? {
            val out = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (out.isEmpty()) null else out.toString()
                if (c == '\n'.code) return out.toString().trimEnd('\r')
                out.append(c.toChar())
                if (out.length > 8_192) throw IOException("Header too long")
            }
        }
        val start = line() ?: return null
        val parts = start.split(' ')
        if (parts.size < 2) throw IOException("Bad request line")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val h = line() ?: break
            if (h.isEmpty()) break
            val idx = h.indexOf(':')
            if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
            if (headers.size > 64) throw IOException("Too many headers")
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > SYNC_MAX_BODY) throw IOException("Body too large")
        val body = ByteArray(length)
        var read = 0
        while (read < length) { val n = input.read(body, read, length - read); if (n < 0) throw IOException("Truncated body"); read += n }
        return Request(parts[0], path, query, headers, body)
    }

    private fun respond(socket: Socket, status: Int, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 429 -> "Too Many Requests"; else -> "Error" }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
    }

    private suspend fun handle(socket: Socket) {
        val request = try { readRequest(socket) ?: return } catch (_: SocketTimeoutException) { return } catch (_: IOException) { return }
        try {
            val token = request.headers["authorization"]?.removePrefix("Bearer ")?.trim()
            when {
                request.method == "POST" && request.path == "/pair" -> pair(socket, request)
                !validToken(token) || token !in tokens -> respond(socket, 401, JSONObject().put("error", "unauthorized"))
                request.method == "GET" && request.path == "/v2/state" -> {
                    lastPhoneSeenAt = System.currentTimeMillis()
                    publish()
                    val since = request.query["since"]?.toLongOrNull() ?: -1L
                    val dataSince = request.query["data"]?.toLongOrNull() ?: -1L
                    if (request.query["wait"] == "1" && repository.state.value.revision == since)
                        withTimeoutOrNull(SYNC_LONG_POLL_MILLIS) { repository.state.first { it.revision != since } }
                    respond(socket, 200, encodeSyncState(repository.state.value, dataSince, System.currentTimeMillis()))
                }
                request.method == "POST" && request.path == "/v2/command" -> {
                    lastPhoneSeenAt = System.currentTimeMillis()
                    publish()
                    val command = parseSyncCommand(JSONObject(request.body.toString(Charsets.UTF_8)))
                    try {
                        repository.applyCommand(command)
                        respond(socket, 200, JSONObject().put("ok", true).put("revision", repository.state.value.revision))
                    } catch (e: IllegalArgumentException) {
                        respond(socket, 400, JSONObject().put("error", "rejected").put("message", e.message ?: ""))
                    }
                }
                request.method == "POST" && request.path == "/v2/unpair" -> {
                    tokens = tokens.filterNot { it == token }
                    runCatching { pairings.save(tokens) }
                    publish()
                    respond(socket, 200, JSONObject().put("ok", true))
                }
                else -> respond(socket, 404, JSONObject().put("error", "not_found"))
            }
        } catch (e: IllegalArgumentException) {
            runCatching { respond(socket, 400, JSONObject().put("error", "invalid").put("message", e.message ?: "")) }
        } catch (e: Exception) {
            runCatching { respond(socket, 400, JSONObject().put("error", "invalid")) }
        }
    }

    private fun pair(socket: Socket, request: Request) {
        val code = runCatching { JSONObject(request.body.toString(Charsets.UTF_8)).optString("code") }.getOrDefault("")
        val current = pairingCode
        val now = System.currentTimeMillis()
        if (current == null || now >= pairingExpiresAt || pairFailures >= SYNC_MAX_PAIR_FAILURES) {
            respond(socket, 401, JSONObject().put("error", "pairing_closed")); return
        }
        if (!MessageDigest.isEqual(code.toByteArray(), current.toByteArray())) {
            pairFailures++
            if (pairFailures >= SYNC_MAX_PAIR_FAILURES) { pairingCode = null; publish() }
            respond(socket, 401, JSONObject().put("error", "wrong_code")); return
        }
        val token = newToken()
        tokens = (tokens + token).takeLast(10)
        pairings.save(tokens)
        pairingCode = null
        lastPhoneSeenAt = now
        publish()
        respond(socket, 200, JSONObject().put("token", token).put("fingerprint", fingerprint).put("carId", fingerprint.take(12)))
    }

    companion object {
        private const val KEY_ALIAS = "sharemate-sync-tls-v2"
        /** The server owned by the running service, so the car screen can issue a new pairing code. */
        @Volatile var active: SyncServer? = null
            private set
    }
}
