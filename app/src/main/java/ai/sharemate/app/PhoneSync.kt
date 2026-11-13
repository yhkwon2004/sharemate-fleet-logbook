package ai.sharemate.app

import android.content.Context
import android.util.AtomicFile
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

/**
 * Phone side of live sync. Keeps a long-poll open to the car, mirrors each new revision into the phone's local copy,
 * and sends edits back as commands. The car remains the only writer of the log file that matters.
 */
class PhoneSyncClient private constructor(context: Context) {
    private val accounts = AccountStore.get(context)
    private val repository = TripLogRepository.get(context)
    private val store = DeviceLink(context, "fleet-phone")
    // The company vehicles on this phone; the selected one is the car this client talks to (see PhoneFleet.kt).
    private val fleet = FleetStore.get(context)
    internal val fleetState: StateFlow<FleetState> get() = fleet.state
    // Approvals arrive whether or not the car is reachable, so they wait on disk until it is.
    private val queueFile = AtomicFile(File(context.applicationContext.noBackupFilesDir, "card-queue.json"))
    private val queueLock = Mutex()
    private val mutableQueued = MutableStateFlow(0)
    val queued: StateFlow<Int> = mutableQueued.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableLink = MutableStateFlow(PhoneLink())
    val link: StateFlow<PhoneLink> = mutableLink.asStateFlow()
    /** Bumps whenever a photo file lands on this phone, so thumbnails re-read the store. */
    val photoVersion = MutableStateFlow(0)
    private var job: Job? = null
    @Volatile private var config: LinkConfig? = runCatching { store.load() }.getOrNull()
    val paired get() = config != null

    init {
        scope.launch(Dispatchers.IO) { mutableQueued.value = readQueue().size }
        // A phone paired before 2.9 has one link and no fleet list: register that car so it shows up and stays selected.
        scope.launch { config?.let { c -> if (fleet.state.value.vehicles.none { it.id == c.fingerprint }) runCatching { fleet.mutate { upsertPaired(it, c.fingerprint, linkText(c), null, System.currentTimeMillis()) } } } }
    }

    private fun linkText(c: LinkConfig) = "${c.endpoint}|${c.token}|${c.fingerprint}"

    /**
     * Makes [id] the vehicle this phone shows and talks to: the current copy is kept for the vehicle it belongs to, the target's
     * last copy comes back at once (or an empty one with its registered plate), and the link restarts against that car.
     */
    suspend fun switchTo(id: String) {
        val current = fleet.state.value
        if (current.selectedId == id) return
        val target = current.vehicles.firstOrNull { it.id == id } ?: throw IllegalArgumentException("차량을 찾지 못했어요.")
        stop()
        current.selectedId?.let { fleet.saveCopy(it, repository.state.value.data) }
        fleet.mutate { it.copy(selectedId = id) }
        val copy = fleet.loadCopy(id) ?: TripLogData(profile = FleetProfile(companyName = target.company, plateNumber = target.plate, carModel = target.model))
        repository.importPhoneCopy(copy, live = true)
        config = target.link?.let { runCatching { parseLink(it) }.getOrNull() }
        withContext(Dispatchers.IO) { val link = target.link; if (link != null) store.save(link) else store.clear() }
        mutableLink.value = PhoneLink(message = if (config != null) "차량 연결 중" else "아직 연결하지 않은 차량")
        start()
    }

    /** Removes a vehicle from this phone; a paired car is told first. Selecting another vehicle (or none) follows. */
    suspend fun removeVehicle(id: String) {
        val state = fleet.state.value
        val vehicle = state.vehicles.firstOrNull { it.id == id } ?: return
        if (state.selectedId == id && config != null) unpair()
        else vehicle.link?.let { link -> runCatching { store.request(parseLink(link), "/v2/unpair", JSONObject()) } }
        fleet.deleteCopy(id)
        val next = fleet.mutate { s -> s.copy(vehicles = s.vehicles.filterNot { it.id == id }, selectedId = if (s.selectedId == id) null else s.selectedId) }
        if (next.selectedId == null) {
            next.vehicles.firstOrNull()?.let { switchTo(it.id) } ?: repository.importPhoneCopy(TripLogData())
        }
    }

    /**
     * The certificate pin comes from the vehicle screen, before any pairing secret is transmitted. 2.15
     * (docs/design/DESIGN_2_15.md §6.3): [endpoint] may hold several addresses separated by commas, as a scanned pairing link
     * carries them ([PairingLink.endpointText]). They are tried in order ([pairInOrder]): one that cannot be reached moves on
     * to the next; a wrong code or a certificate that is not the pinned one stops at once, so the code is never sent to a
     * second address after the car has answered.
     */
    suspend fun pair(endpoint: String, code: String, vehicleFingerprint: String) {
        require(accounts.account.value != null) { "먼저 로그인해 주세요." }
        val bases = pairingEndpointList(endpoint).map { typed -> typed.trimEnd('/').let { if (it.startsWith("https://")) it else "https://$it" } }
        require(bases.isNotEmpty() && bases.all { runCatching { URI(it).host }.getOrNull()?.isNotBlank() == true }) { "차량 주소를 확인해 주세요." }
        require(code.matches(Regex("\\d{6}"))) { "6자리 연결 번호를 입력해 주세요." }
        val fingerprint = pairingFingerprint(vehicleFingerprint)
        // Every address is checked before the first is dialled, so a malformed later one never surfaces mid-way.
        val configs = bases.map { parseLink("$it|${"0".repeat(64)}|$fingerprint").copy(token = "") }.distinctBy { it.endpoint }
        val (base, response) = try {
            pairInOrder(configs) { pairingConfig -> pairingConfig.endpoint to store.request(pairingConfig, "/pair", JSONObject().put("code", code)) }
        } catch (e: javax.net.ssl.SSLException) { throw IllegalArgumentException("차량 인증키를 확인하지 못했어요. 차량 화면의 인증키와 날짜 설정을 확인해 주세요.", e) }
        require(response.getString("fingerprint") == fingerprint) { "차량 인증키가 달라요. 차량 화면에서 다시 확인해 주세요." }
        val text = "$base|${response.getString("token")}|$fingerprint"
        withContext(Dispatchers.IO) { store.save(text) }
        config = parseLink(text)
        // The new car joins the fleet and becomes the selected one; the previous car keeps its copy for when it is picked again.
        stop()
        val previous = fleet.state.value.selectedId
        if (previous != null && previous != fingerprint) fleet.saveCopy(previous, repository.state.value.data)
        val cached = fleet.loadCopy(fingerprint)
        if (previous != fingerprint) repository.importPhoneCopy(cached ?: TripLogData(), live = true)
        fleet.mutate { upsertPaired(it, fingerprint, text, cached, System.currentTimeMillis()) }
        mutableLink.value = PhoneLink(message = "차량 연결됨 · 동기화 중")
        start()
    }

    suspend fun unpair() {
        val current = config
        if (current != null) runCatching { store.request(current, "/v2/unpair", JSONObject()) }
        withContext(Dispatchers.IO) { store.clear() }
        // The vehicle stays on the list as registered but not connected; removing it is a separate choice.
        if (current != null) fleet.mutate { s -> s.copy(vehicles = s.vehicles.map { if (it.id == current.fingerprint) it.copy(link = null) else it }) }
        config = null
        stop()
        mutableLink.value = PhoneLink(message = "차량 연결 해제됨")
    }

    fun start() {
        if (accounts.account.value == null) return
        val current = config ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            var since = -1L
            var dataSince = -1L
            var failures = 0
            while (isActive && accounts.account.value != null) {
                try {
                    val remote = store.request(current, "/v2/state?since=$since&data=$dataSince&wait=1", timeoutMillis = SYNC_LONG_POLL_MILLIS + 8_000)
                    val revision = remote.getLong("revision")
                    if (remote.optBoolean("changed")) {
                        val data = withContext(Dispatchers.Default) { TripLogCodec.decode(remote.getJSONObject("data").toString().toByteArray(Charsets.UTF_8)) }
                        // The car's trip in progress rides along so the phone can show it; it is never closed on the phone.
                        val before = repository.state.value.data.incidents.flatMap { it.photos }.map { it.id }.toSet()
                        repository.importPhoneCopy(data, live = true)
                        dataSince = remote.getLong("dataRevision")
                        // Keep the fleet list's names current, but write the list only when something it shows changed or once a minute.
                        val now = System.currentTimeMillis()
                        fleet.state.value.vehicles.firstOrNull { it.id == current.fingerprint }?.let { v ->
                            val plate = data.profile.plateNumber.ifBlank { v.plate }; val company = data.profile.companyName.ifBlank { v.company }
                            if (plate != v.plate || company != v.company || now - v.lastSeenAt > 60_000) runCatching { fleet.mutate { refreshFromCopy(it, current.fingerprint, data, now) } }
                        }
                        // A photo the car no longer lists has no business staying on the phone.
                        val kept = data.incidents.flatMap { it.photos }.map { it.id }.toSet()
                        withContext(Dispatchers.IO) { (before - kept).forEach { repository.photos.delete(it) } }
                    }
                    since = revision
                    failures = 0
                    if (mutableQueued.value > 0) flushCards()
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

    fun stop() { job?.cancel(); job = null; mutableLink.update { it.copy(connected = false, live = null) } }

    /** Pull-to-refresh: one immediate state fetch, independent of the long-poll loop, so the gesture ends with a real answer. */
    suspend fun refreshNow(): Boolean {
        if (accounts.account.value == null) return false
        val current = config ?: return false
        return try {
            val remote = store.request(current, "/v2/state?since=-1&data=-1&wait=0", timeoutMillis = 8_000)
            if (remote.optBoolean("changed")) repository.importPhoneCopy(withContext(Dispatchers.Default) {
                TripLogCodec.decode(remote.getJSONObject("data").toString().toByteArray(Charsets.UTF_8))
            }, live = true)
            mutableLink.update { it.copy(connected = true, revision = remote.getLong("revision"), lastSeenAt = System.currentTimeMillis(),
                signal = runCatching { SignalStatus.valueOf(remote.getString("signal")) }.getOrNull(), live = decodeLive(remote.optJSONObject("live")), message = "차량 연결됨") }
            if (mutableQueued.value > 0) flushCards()
            true
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            mutableLink.update { it.copy(connected = false, message = "차량과 연결되지 않아요 · 같은 네트워크인지 확인") }
            false
        }
    }

    private fun readQueue(): List<CardApproval> = runCatching {
        if (!queueFile.baseFile.exists()) emptyList() else parseApprovals(JSONArray(queueFile.readFully().toString(Charsets.UTF_8)))
    }.getOrDefault(emptyList())

    private fun writeQueue(events: List<CardApproval>) {
        val stream = queueFile.startWrite()
        try { stream.write(encodeApprovals(events).toString().toByteArray(Charsets.UTF_8)); queueFile.finishWrite(stream) }
        catch (e: Exception) { queueFile.failWrite(stream); throw e }
        mutableQueued.value = events.size
    }

    /** Takes card records from any source and hands them to the car as soon as it can be reached. */
    suspend fun enqueue(events: List<CardApproval>) {
        if (events.isEmpty() || accounts.account.value == null) return
        queueLock.withLock {
            withContext(Dispatchers.IO) { writeQueue((readQueue() + events).distinctBy { it.id }.takeLast(CARD_QUEUE_LIMIT)) }
        }
        flushCards()
    }

    /** Sends the queue in order; whatever the car did not accept stays queued for the next attempt. */
    suspend fun flushCards() {
        if (accounts.account.value == null) return
        val current = config ?: return
        queueLock.withLock {
            var pending = withContext(Dispatchers.IO) { readQueue() }
            mutableQueued.value = pending.size
            while (pending.isNotEmpty() && accounts.account.value != null) {
                val batch = pending.take(SYNC_MAX_CARD_EVENTS)
                val sent = runCatching { store.request(current, "/v2/command", encodeSyncCommand(SyncCommand.CardEvents(batch))).optBoolean("ok") }.getOrDefault(false)
                if (!sent) break
                pending = pending.drop(batch.size)
                withContext(Dispatchers.IO) { writeQueue(pending) }
            }
        }
    }

    /** Uploads a JPEG the phone already stored under [photoId]; the car records it and the entry comes back through the mirror. */
    suspend fun uploadPhoto(incidentId: String, photoId: String, jpeg: ByteArray) {
        require(accounts.account.value != null) { "먼저 로그인해 주세요." }
        val current = config ?: throw IllegalArgumentException("차량과 먼저 연결해 주세요.")
        val payload = JSONObject().put("incidentId", incidentId).put("photoId", photoId).put("jpeg", java.util.Base64.getEncoder().encodeToString(jpeg))
        val result = store.request(current, "/v2/photo", payload, timeoutMillis = 20_000)
        require(result.optBoolean("ok")) { result.optString("message").ifBlank { "차량이 사진을 받지 않았어요." } }
    }

    /** Pulls a photo another device attached, so every paired phone can see the whole report. */
    suspend fun fetchPhoto(photoId: String): ByteArray? {
        if (accounts.account.value == null) return null
        val current = config ?: return null
        return runCatching { store.download(current, "/v2/photo?id=$photoId") }.getOrNull()?.takeIf(::looksLikeJpeg)
            ?.also { bytes -> withContext(Dispatchers.IO) { repository.photos.save(photoId, bytes) }; photoVersion.update { it + 1 } }
    }

    /** Sends one edit to the car and waits for the next revision so the phone screen updates right away. */
    suspend fun send(command: SyncCommand) {
        require(accounts.account.value != null) { "먼저 로그인해 주세요." }
        val current = config ?: throw IllegalArgumentException("차량과 먼저 연결해 주세요.")
        val result = store.request(current, "/v2/command", encodeSyncCommand(command))
        require(result.optBoolean("ok")) { result.optString("message").ifBlank { "차량이 변경을 받지 않았어요." } }
    }

    companion object {
        private const val CARD_QUEUE_LIMIT = 500
        @Volatile private var instance: PhoneSyncClient? = null
        fun get(context: Context): PhoneSyncClient = instance ?: synchronized(this) { instance ?: PhoneSyncClient(context).also { instance = it } }
    }
}
