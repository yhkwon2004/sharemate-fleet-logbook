package ai.sharemate.app

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

data class TripLogData(
    val profile: FleetProfile = FleetProfile(),
    val drivers: List<Driver> = emptyList(),
    val currentDriverId: String? = null,
    val trips: List<Trip> = emptyList(),
    val open: Trip? = null,
)

enum class SignalStatus(val label: String) {
    STARTING("차량 연결 중"), READY("자동 기록 대기"), RECORDING("운행 기록 중"), DEMO("시연 데이터"),
    DENIED("차량 정보 권한 필요"), UNSUPPORTED("차량 데이터 미지원"), ERROR("차량 연결 확인 필요"),
}

data class TripLogState(
    val loaded: Boolean = false,
    val loadFailed: Boolean = false,
    val data: TripLogData = TripLogData(),
    val live: DriveSample? = null,
    val signal: SignalStatus = SignalStatus.STARTING,
    val vehicleModel: String? = null,
    val lastFinishedId: String? = null,
    val error: String? = null,
)

/** Single owner of the trip log, shared by the recording service, the car screen, and Gleo voice actions. */
class TripLogRepository private constructor(context: Context) {
    private val store = TripStore(context)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(TripLogState())
    val state: StateFlow<TripLogState> = mutableState.asStateFlow()
    private var persistedOdometer = 0.0
    private var persistedAt = 0L

    suspend fun ensureLoaded(): TripLogData = mutex.withLock { loadLocked() }

    private suspend fun loadLocked(): TripLogData {
        if (state.value.loaded) return state.value.data
        return try {
            val data = withContext(Dispatchers.IO) { store.load() }
            mutableState.update { it.copy(loaded = true, loadFailed = false, data = data) }
            data
        } catch (error: Exception) {
            // A damaged file is kept on disk so it can be inspected; the app starts from an empty log.
            mutableState.update { it.copy(loaded = true, loadFailed = true, error = "저장된 운행일지를 읽지 못했어요. 기존 파일은 보존했습니다.") }
            state.value.data
        }
    }

    private suspend fun save(data: TripLogData) {
        if (state.value.loadFailed) throw IOException("손상된 운행일지를 덮어쓰지 않습니다.")
        withContext(Dispatchers.IO) { store.save(data) }
        mutableState.update { it.copy(data = data) }
    }

    fun setSignal(signal: SignalStatus, model: String? = state.value.vehicleModel) {
        mutableState.update { it.copy(signal = signal, vehicleModel = model) }
    }

    fun clearError() = mutableState.update { it.copy(error = null) }

    fun recordingFailed() = mutableState.update {
        it.copy(signal = SignalStatus.ERROR, error = it.error ?: "운행 기록을 저장하지 못했어요. 저장 공간과 앱 상태를 확인해 주세요. 기존 기록은 보존했습니다.")
    }

    suspend fun ingest(sample: DriveSample) = mutex.withLock {
        val data = loadLocked()
        mutableState.update { it.copy(live = sample) }
        val stored = if (data.profile.storeAddresses) sample else sample.copy(address = null, destination = null)
        var next = data
        var finishedId: String? = null
        advanceTrip(data.open, stored, data.currentDriverId) { UUID.randomUUID().toString() }.forEach { change ->
            next = when (change) {
                is TripChange.Started -> next.copy(open = change.trip)
                is TripChange.Progress -> next.copy(open = change.trip)
                is TripChange.Finished -> {
                    require(next.trips.size < MAX_TRIPS) { "운행기록이 20,000건에 도달했어요. 기록을 내보낸 뒤 저장 공간을 정리해 주세요." }
                    val history = withHistory(change.trip, if (change.trip.recovered) "앱 중단 후 마지막 수신 시각으로 종료" else "기어 P 전환으로 운행 종료", sample.at)
                    finishedId = history.id
                    next.copy(open = null, trips = next.trips + history)
                }
                is TripChange.Discarded -> next.copy(open = null)
            }
        }
        val open = next.open
        // Checkpoint a recording trip every minute or kilometre so an interruption loses little.
        val checkpoint = open != null && data.open != null && (sample.at - persistedAt >= 60_000 || open.endOdometerKm - persistedOdometer >= 1.0)
        if (next.open?.id != data.open?.id || next.trips.size != data.trips.size || checkpoint) {
            save(next)
            persistedAt = sample.at
            persistedOdometer = open?.endOdometerKm ?: 0.0
        } else mutableState.update { it.copy(data = next) }
        if (finishedId != null) mutableState.update { it.copy(lastFinishedId = finishedId) }
    }

    private suspend fun mutate(transform: (TripLogData) -> TripLogData) = mutex.withLock {
        val data = loadLocked()
        val next = transform(data)
        if (next != data) save(next)
    }

    private fun TripLogData.editTrip(id: String, edit: (Trip) -> Trip): TripLogData = when {
        open?.id == id -> copy(open = edit(open))
        trips.any { it.id == id } -> copy(trips = trips.map { if (it.id == id) edit(it) else it })
        else -> throw IllegalArgumentException("운행 기록을 찾지 못했어요.")
    }

    suspend fun classify(id: String, purpose: TripPurpose, source: EditSource) = mutate { data ->
        data.editTrip(id) { trip ->
            if (trip.purpose == purpose && trip.purposeSource == source) trip
            else withHistory(trip.copy(purpose = purpose, purposeSource = source), "목적 ${trip.purpose.label} → ${purpose.label} (${source.label})", System.currentTimeMillis())
        }
    }

    suspend fun setNote(id: String, note: String, source: EditSource = EditSource.DRIVER) = mutate { data ->
        require(note.length <= 300) { "메모는 300자 이하로 입력해 주세요." }
        data.editTrip(id) { trip -> if (trip.note == note) trip else withHistory(trip.copy(note = note), "비고 수정 (${source.label})", System.currentTimeMillis()) }
    }

    suspend fun setTripDriver(id: String, driverId: String) = mutate { data ->
        val driver = data.drivers.firstOrNull { it.id == driverId } ?: throw IllegalArgumentException("사용자를 다시 선택해 주세요.")
        data.editTrip(id) { trip -> if (trip.driverId == driverId) trip else withHistory(trip.copy(driverId = driverId), "사용자 ${driver.label}", System.currentTimeMillis()) }
    }

    suspend fun addExpense(id: String, type: ExpenseType, amountWon: Long, source: EditSource) = mutate { data ->
        require(amountWon in 1..10_000_000) { "금액은 1원~1,000만 원으로 입력해 주세요." }
        val now = System.currentTimeMillis()
        data.editTrip(id) { trip ->
            require(trip.expenses.size < 30) { "한 운행에 비용은 30건까지 기록할 수 있어요." }
            withHistory(trip.copy(expenses = trip.expenses + TripExpense(type, amountWon, now, source)), "${type.label} ${formatWon(amountWon)} 추가 (${source.label})", now)
        }
    }

    suspend fun removeExpense(id: String, index: Int) = mutate { data ->
        data.editTrip(id) { trip ->
            val item = trip.expenses.getOrNull(index) ?: return@editTrip trip
            withHistory(trip.copy(expenses = trip.expenses.filterIndexed { i, _ -> i != index }), "${item.type.label} ${formatWon(item.amountWon)} 삭제", System.currentTimeMillis())
        }
    }

    suspend fun deleteTrip(id: String) = mutate { data ->
        require(data.open?.id != id) { "기록 중인 운행은 삭제할 수 없어요." }
        data.copy(trips = data.trips.filterNot { it.id == id })
    }

    suspend fun fillGap(previousId: String, nextId: String) = mutate { data ->
        require(data.trips.size < MAX_TRIPS) { "운행기록이 20,000건에 도달했어요. 먼저 기록을 내보내 주세요." }
        require(auditTrips(data.trips).any { it.type == IssueType.ODOMETER_GAP && it.previousTripId == previousId && it.tripId == nextId }) {
            "앞뒤 운행 기록이 변경됐어요. 누락 구간을 다시 확인해 주세요."
        }
        val previous = data.trips.first { it.id == previousId }
        val next = data.trips.first { it.id == nextId }
        require(next.startOdometerKm - previous.endOdometerKm > ODOMETER_TOLERANCE_KM) { "보완할 누락 구간이 없어요." }
        data.copy(trips = (data.trips + gapTrip(previous, next, UUID.randomUUID().toString(), System.currentTimeMillis()))
            .sortedWith(compareBy({ it.startAt }, { it.startOdometerKm })))
    }

    suspend fun saveDriver(driver: Driver) = mutate { data ->
        require(driver.name.isNotBlank() && driver.name.length <= 30 && driver.department.length <= 30) { "부서·성명은 30자 이하로 입력해 주세요." }
        require(data.drivers.size < 50 || data.drivers.any { it.id == driver.id }) { "사용자는 50명까지 등록할 수 있어요." }
        val drivers = if (data.drivers.any { it.id == driver.id }) data.drivers.map { if (it.id == driver.id) driver else it } else data.drivers + driver
        data.copy(drivers = drivers, currentDriverId = data.currentDriverId ?: driver.id)
    }

    suspend fun removeDriver(id: String) = mutate { data ->
        // The logbook's ④ user column must stay filled, so users with trips can be renamed but not removed.
        require(data.trips.none { it.driverId == id } && data.open?.driverId != id) { "운행 기록이 있는 사용자는 삭제할 수 없어요. 부서·성명을 수정해 주세요." }
        data.copy(drivers = data.drivers.filterNot { it.id == id }, currentDriverId = data.currentDriverId.takeIf { it != id })
    }

    /** The chosen user applies to new trips and to a trip already being recorded. */
    suspend fun selectDriver(id: String) = mutate { data ->
        require(data.drivers.any { it.id == id }) { "사용자를 다시 선택해 주세요." }
        data.copy(currentDriverId = id, open = data.open?.copy(driverId = id))
    }

    suspend fun updateProfile(profile: FleetProfile) = mutate { data ->
        validateProfile(profile)
        data.copy(profile = profile)
    }

    suspend fun importPhoneCopy(data: TripLogData) = mutex.withLock {
        require(data.open == null) { "기록 중인 운행은 가져올 수 없어요." }
        loadLocked()
        save(data)
    }

    /** Trip targeted by voice: the one being recorded, else the most recent trip within the last 12 hours. */
    suspend fun voiceTarget(): Trip? = mutex.withLock {
        val data = loadLocked()
        val now = System.currentTimeMillis()
        data.open?.takeIf { it.demo == data.profile.demoMode } ?: data.trips.filter {
            it.demo == data.profile.demoMode && now - (it.endAt ?: it.updatedAt) in 0..12 * 3_600_000L
        }.maxByOrNull { it.endAt ?: it.updatedAt }
    }

    companion object {
        internal const val MAX_TRIPS = 20_000
        @Volatile private var instance: TripLogRepository? = null
        fun get(context: Context): TripLogRepository =
            instance ?: synchronized(this) { instance ?: TripLogRepository(context.applicationContext).also { instance = it } }
    }
}

internal val BUSINESS_NUMBER = Regex("^\\d{3}-\\d{2}-\\d{5}$")

internal fun validateProfile(profile: FleetProfile) {
    require(profile.companyName.length <= 60 && profile.carModel.length <= 60 && profile.plateNumber.length <= 20) { "입력 길이를 확인해 주세요." }
    require(profile.businessNumber.isEmpty() || BUSINESS_NUMBER.matches(profile.businessNumber)) { "사업자등록번호는 000-00-00000 형식으로 입력해 주세요." }
    require(profile.annualCostWon == null || profile.annualCostWon in 0..10_000_000_000L) { "연간 차량 비용을 확인해 주세요." }
    require(profile.reimbursePerKmWon in 0..100_000) { "km당 정산 단가는 0~100,000원으로 입력해 주세요." }
}

/** Trip log stored on the vehicle only, excluded from backup like the rest of the app data. */
private class TripStore(context: Context) {
    private val base = File(context.noBackupFilesDir, "trip-log.json")
    private val file = AtomicFile(base)

    fun load(): TripLogData {
        if (!base.exists() && !File(base.path + ".bak").exists()) return TripLogData()
        val bytes = file.openRead().use { stream ->
            require(stream.channel.size() <= LOGBOOK_MAX_BYTES.toLong()) { "운행일지 파일이 너무 큽니다." }
            stream.readBytes()
        }
        return TripLogCodec.decode(bytes)
    }

    fun save(data: TripLogData) {
        val bytes = TripLogCodec.encode(data)
        require(bytes.size <= LOGBOOK_MAX_BYTES) { "운행일지 저장 용량을 초과했어요. 기존 기록은 보존했습니다." }
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}

internal const val LOGBOOK_MAX_BYTES = 8 * 1024 * 1024

internal object TripLogCodec {
    fun decode(bytes: ByteArray): TripLogData {
        require(bytes.size <= LOGBOOK_MAX_BYTES)
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("version") == 1)
        val p = json.getJSONObject("profile")
        val profile = FleetProfile(p.getString("companyName"), p.getString("businessNumber"), p.getString("carModel"), p.getString("plateNumber"),
            if (p.isNull("annualCostWon")) null else p.getLong("annualCostWon"), p.getLong("reimbursePerKmWon"), p.getBoolean("storeAddresses"), p.getBoolean("demoMode"))
        validateProfile(profile)
        val drivers = json.getJSONArray("drivers").objects().map { Driver(it.getString("id"), it.getString("department"), it.getString("name")) }
        val trips = json.getJSONArray("trips").objects().map(::readTrip)
        val data = TripLogData(profile, drivers, json.optString("currentDriverId").takeIf { it.isNotEmpty() && drivers.any { d -> d.id == it } },
            trips, if (json.isNull("open")) null else readTrip(json.getJSONObject("open")))
        validate(data)
        return data
    }

    fun encode(data: TripLogData): ByteArray {
        val json = JSONObject()
            .put("version", 1)
            .put("profile", JSONObject().put("companyName", data.profile.companyName).put("businessNumber", data.profile.businessNumber)
                .put("carModel", data.profile.carModel).put("plateNumber", data.profile.plateNumber)
                .put("annualCostWon", data.profile.annualCostWon ?: JSONObject.NULL).put("reimbursePerKmWon", data.profile.reimbursePerKmWon)
                .put("storeAddresses", data.profile.storeAddresses).put("demoMode", data.profile.demoMode))
            .put("drivers", JSONArray(data.drivers.map { JSONObject().put("id", it.id).put("department", it.department).put("name", it.name) }))
            .put("currentDriverId", data.currentDriverId ?: "")
            .put("trips", JSONArray(data.trips.map(::writeTrip)))
            .put("open", data.open?.let(::writeTrip) ?: JSONObject.NULL)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.text(key: String) = if (isNull(key)) null else getString(key)

    private fun readTrip(o: JSONObject) = Trip(
        id = o.getString("id"), startAt = o.getLong("startAt"), endAt = if (o.isNull("endAt")) null else o.getLong("endAt"),
        startOdometerKm = o.getDouble("startOdometerKm"), endOdometerKm = o.getDouble("endOdometerKm"),
        startAddress = o.text("startAddress"), endAddress = o.text("endAddress"), destination = o.text("destination"), driverId = o.text("driverId"),
        purpose = TripPurpose.valueOf(o.getString("purpose")), purposeSource = o.text("purposeSource")?.let(EditSource::valueOf),
        note = o.getString("note"),
        expenses = o.getJSONArray("expenses").objects().map { TripExpense(ExpenseType.valueOf(it.getString("type")), it.getLong("amountWon"), it.getLong("at"), EditSource.valueOf(it.getString("source"))) },
        origin = TripOrigin.valueOf(o.getString("origin")), recovered = o.getBoolean("recovered"), demo = o.getBoolean("demo"),
        history = o.getJSONArray("history").let { a -> (0 until a.length()).map { a.getString(it) } }, updatedAt = o.getLong("updatedAt"),
    ).also { require(it.endOdometerKm >= it.startOdometerKm && it.startAt > 0) }

    private fun writeTrip(t: Trip) = JSONObject()
        .put("id", t.id).put("startAt", t.startAt).put("endAt", t.endAt ?: JSONObject.NULL)
        .put("startOdometerKm", t.startOdometerKm).put("endOdometerKm", t.endOdometerKm)
        .put("startAddress", t.startAddress ?: JSONObject.NULL).put("endAddress", t.endAddress ?: JSONObject.NULL)
        .put("destination", t.destination ?: JSONObject.NULL).put("driverId", t.driverId ?: JSONObject.NULL)
        .put("purpose", t.purpose.name).put("purposeSource", t.purposeSource?.name ?: JSONObject.NULL).put("note", t.note)
        .put("expenses", JSONArray(t.expenses.map { JSONObject().put("type", it.type.name).put("amountWon", it.amountWon).put("at", it.at).put("source", it.source.name) }))
        .put("origin", t.origin.name).put("recovered", t.recovered).put("demo", t.demo)
        .put("history", JSONArray(t.history)).put("updatedAt", t.updatedAt)

    private fun validate(data: TripLogData) {
        require(data.drivers.size <= 50 && data.drivers.map { it.id }.distinct().size == data.drivers.size)
        data.drivers.forEach { require(validUuid(it.id) && it.name.isNotBlank() && it.name.length <= 30 && it.department.length <= 30) }
        require(data.trips.size <= TripLogRepository.MAX_TRIPS && data.trips.none { it.open } && (data.open == null || data.open.open))
        val trips = data.trips + listOfNotNull(data.open)
        require(trips.map { it.id }.distinct().size == trips.size)
        trips.forEach { t ->
            require(validUuid(t.id) && t.startAt in 1..253_402_300_799_999 && t.updatedAt in t.startAt..253_402_300_799_999)
            require(t.endAt == null || t.endAt in t.startAt..253_402_300_799_999)
            require(t.startOdometerKm.isFinite() && t.endOdometerKm.isFinite() && t.startOdometerKm in 0.0..10_000_000.0 && t.endOdometerKm in t.startOdometerKm..10_000_000.0)
            require(t.driverId == null || data.drivers.any { it.id == t.driverId })
            require(t.note.length <= 300 && (t.startAddress?.length ?: 0) <= 120 && (t.endAddress?.length ?: 0) <= 120 && (t.destination?.length ?: 0) <= 80)
            require(t.history.size <= 30 && t.history.all { it.length <= 500 } && t.expenses.size <= 30)
            t.expenses.forEach { require(it.amountWon in 1..10_000_000 && it.at in 0..253_402_300_799_999) }
        }
    }
}
