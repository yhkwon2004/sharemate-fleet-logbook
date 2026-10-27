package ai.sharemate.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutableState.asStateFlow()
    private val store = SessionStore(application)
    private val sessionLock = Mutex()
    private var source: VehicleSource = PleosVehicleSource(application)
    private var sourceJob: Job? = null
    private var clockJob: Job? = null
    private var foreground = false
    private val deviceLink = DeviceLink(application, "vehicle")
    private val mutableLink = MutableStateFlow(LinkStatus())
    val link: StateFlow<LinkStatus> = mutableLink.asStateFlow()
    private var linkJob: Job? = null

    fun configureLink(text: String) = action {
        withContext(Dispatchers.IO) { deviceLink.save(text) }
        mutableLink.value = LinkStatus(true, "연결 확인 중")
        if (foreground) startLink()
    }

    fun disconnectPhone() = action {
        linkJob?.cancel()
        val config = withContext(Dispatchers.IO) { deviceLink.load() }
        if (config != null) deviceLink.request(config, "/v1/telemetry", telemetry(state.value.copy(session = null)))
        withContext(Dispatchers.IO) { deviceLink.clear() }
        mutableLink.value = LinkStatus()
    }

    private fun startLink() {
        linkJob?.cancel()
        linkJob = viewModelScope.launch {
            while (foreground) {
                try {
                    val configured = sessionLock.withLock {
                    val config = withContext(Dispatchers.IO) { deviceLink.load() } ?: return@withLock false
                    // A restore decision must not erase an in-progress paired rental.
                    if (!state.value.restoring && state.value.pendingSession == null && !state.value.restoreError) {
                        deviceLink.request(config, "/v1/telemetry", telemetry(state.value))
                        val remote = deviceLink.request(config, "/v1/state")
                        mutableLink.value = LinkStatus(true, "연결 서버 접속됨 · 휴대폰 기록은 아래에서 확인", remote, System.currentTimeMillis())
                    }
                    true
                    }
                    if (!configured) break
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { mutableLink.update { it.copy(configured = true, message = "연결 확인 필요 · 마지막 수신 정보", remote = null) } }
                delay(3_000)
            }
        }
    }

    private val restoreJob = viewModelScope.launch {
        try {
            val saved = withContext(Dispatchers.IO) { store.load() }
            mutableState.update { it.copy(pendingSession = saved, restoring = false) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            mutableState.update {
                it.copy(restoring = false, restoreError = true, error = "저장된 이용 기록을 읽지 못했습니다. 이전 이용 기록을 삭제한 뒤 시작해 주세요.")
            }
        }
    }

    fun startSession(returnAt: Long, returnPlace: String, purpose: Purpose, family: Boolean, pet: Boolean) = action {
        require(!state.value.restoreError && state.value.pendingSession == null) { "이전 이용을 이어서 사용하거나 이전 이용 기록을 삭제해 주세요." }
        require(state.value.session == null) { "진행 중인 이용을 먼저 종료해 주세요." }
        require(returnAt > System.currentTimeMillis()) { "반납 시각은 현재보다 나중으로 설정해 주세요." }
        val now = System.currentTimeMillis()
        save(RentalSession(UUID.randomUUID().toString(), returnAt, returnPlace.trim(), purpose, family, pet,
            startedAt = now, startingOdometerKm = state.value.snapshot.odometerKm.takeIf { it.isFresh(now) }?.value?.toDouble(), odometerSource = state.value.sourceMode))
    }

    fun updateSession(edit: (RentalSession) -> RentalSession) = changeSession { current -> applySessionEdit(current, edit) }

    fun captureStartingDistance() = changeSession { current ->
        require(current.phase == Phase.CHECK_IN && current.startingOdometerKm == null) { "거리 기준은 출발 전 한 번만 설정할 수 있습니다." }
        val reading = state.value.snapshot.odometerKm
        require(reading.isFresh(System.currentTimeMillis())) { "최신 누적 주행거리를 먼저 확인해 주세요." }
        current.copy(startingOdometerKm = reading.value!!.toDouble(), odometerSource = state.value.sourceMode)
    }

    fun setCheck(key: String, checked: Boolean) = changeSession { session ->
        require(key.isNotBlank() && key.length <= 80) { "확인 항목이 올바르지 않습니다." }
        session.copy(checks = if (checked) session.checks + key else session.checks - key)
    }

    fun completeCheckIn() = action {
        val session = requireNotNull(state.value.session)
        require(session.phase == Phase.CHECK_IN) { "현재 이용 단계에서 실행할 수 없습니다." }
        require(session.checks.containsAll(pickupChecks)) { "인수 확인 항목을 모두 직접 확인해 주세요." }
        requirePhoneInspection(session, "beforeComplete", "탑승 전")
        save(session.copy(phase = Phase.ACTIVE))
    }

    fun beginReturn() = changeSession { session ->
        require(session.phase == Phase.ACTIVE) { "이용을 시작한 뒤 반납 확인을 진행해 주세요." }
        session.copy(phase = Phase.RETURN)
    }

    fun endSession() = action {
        val session = requireNotNull(state.value.session) { "진행 중인 이용이 없습니다." }
        require(session.phase == Phase.RETURN && session.checks.containsAll(returnChecks)) { "반납 확인 항목을 모두 직접 확인해 주세요." }
        requirePhoneInspection(session, "afterComplete", "이용 후")
        clearSession()
    }

    private suspend fun requirePhoneInspection(session: RentalSession, field: String, label: String) {
        val config = withContext(Dispatchers.IO) { deviceLink.load() } ?: return
        val remote = deviceLink.request(config, "/v1/state")
        val report = remote.optJSONObject("inspection")
        require(report?.optString("sessionId") == session.id && report.optBoolean(field)) {
            "연결된 휴대폰에서 $label 네 방향 사진·둘레 영상을 촬영하고 기록을 마무리해 주세요."
        }
    }

    fun resumeSession() = action {
        val pending = requireNotNull(state.value.pendingSession) { "이어서 사용할 이용 기록이 없습니다." }
        // A restarted app always reconnects to the real source and needs a new destination confirmation.
        save(pending.copy(boundDestinationId = null, startingOdometerKm = pending.startingOdometerKm.takeIf { pending.odometerSource == state.value.sourceMode }))
        mutableState.update { it.copy(pendingSession = null) }
    }

    fun discardPendingSession() = action {
        require(state.value.session == null) { "진행 중인 이용은 반납 확인 후 종료해 주세요." }
        clearSession()
    }

    fun bindCurrentDestination(expectedDestinationId: String) = changeSession { session ->
        bindReturnDestination(session, state.value.route, expectedDestinationId, System.currentTimeMillis())
    }

    fun setSourceMode(mode: SourceMode) = action {
        if (mode == state.value.sourceMode) return@action
        state.value.session?.let { save(it.copy(boundDestinationId = null, startingOdometerKm = null)) }
        state.value.pendingSession?.let { pending ->
            val cleared = pending.copy(boundDestinationId = null)
            withContext(Dispatchers.IO) { store.save(cleared) }
            mutableState.update { it.copy(pendingSession = cleared) }
        }
        sourceJob?.cancel()
        sourceJob = null
        source.close()
        source = if (mode == SourceMode.PLEOS) PleosVehicleSource(getApplication()) else DemoVehicleSource()
        mutableState.update { it.copy(sourceMode = mode, snapshot = VehicleSnapshot(), route = null, error = null) }
        if (foreground) startSource()
    }

    fun setScenario(scenario: DemoScenario) = action {
        val demo = source as? DemoVehicleSource ?: return@action
        state.value.session?.let { save(it.copy(boundDestinationId = null, startingOdometerKm = null)) }
        mutableState.update { it.copy(snapshot = VehicleSnapshot(), route = null) }
        demo.setScenario(scenario)
    }

    fun clearError() { mutableState.update { it.copy(error = null) } }

    fun setForeground(visible: Boolean) {
        if (foreground == visible) return
        foreground = visible
        sourceJob?.cancel()
        sourceJob = null
        clockJob?.cancel()
        clockJob = null
        if (visible) {
            startSource()
            startLink()
            clockJob = viewModelScope.launch {
                while (true) {
                    mutableState.update { it.copy(now = System.currentTimeMillis()) }
                    delay(1_000)
                }
            }
        } else {
            linkJob?.cancel(); linkJob = null
            runCatching { source.stop() }.onFailure { showError("차량 정보 연결을 중지하지 못했습니다.") }
        }
    }

    private fun startSource() {
        val activeSource = source
        sourceJob = viewModelScope.launch {
            // Fresh input can be newer than the one-second display clock; publish them together.
            launch { activeSource.snapshot.collect { value -> if (source === activeSource) mutableState.update { it.copy(snapshot = value, now = System.currentTimeMillis()) } } }
            launch { activeSource.route.collect { value -> if (source === activeSource) mutableState.update { it.copy(route = value, now = System.currentTimeMillis()) } } }
        }
        try {
            activeSource.start()
        } catch (_: Exception) {
            showError("차량 정보 연결을 시작하지 못했습니다. 데이터 연결 상태를 확인해 주세요.")
        }
    }

    private fun changeSession(transform: (RentalSession) -> RentalSession) = action {
        val current = requireNotNull(state.value.session) { "진행 중인 이용이 없습니다." }
        save(transform(current))
    }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            restoreJob.join()
            sessionLock.withLock {
                try {
                    block()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IllegalArgumentException) {
                    showError(error.message ?: "입력 내용을 확인해 주세요.")
                } catch (_: Exception) {
                    showError("변경 결과를 확인하지 못했습니다. 다시 시도해 주세요.")
                }
            }
        }
    }

    private suspend fun save(session: RentalSession) {
        validateSession(session)
        withContext(Dispatchers.IO) { store.save(session) }
        mutableState.update { it.copy(session = session, error = null) }
    }

    private suspend fun clearSession() {
        val config = withContext(Dispatchers.IO) { deviceLink.load() }
        if (config != null) {
            // Do not claim that paired rental data was erased when the relay is offline.
            deviceLink.request(config, "/v1/telemetry", telemetry(state.value.copy(session = null)))
        }
        withContext(Dispatchers.IO) { store.clear() }
        linkJob?.cancel(); linkJob = null
        withContext(Dispatchers.IO) { deviceLink.clear() }
        mutableLink.value = LinkStatus()
        mutableState.update { it.copy(session = null, pendingSession = null, restoreError = false, error = null) }
    }

    private fun showError(message: String) { mutableState.update { it.copy(error = message) } }

    override fun onCleared() {
        runCatching { source.close() }
        super.onCleared()
    }
}
