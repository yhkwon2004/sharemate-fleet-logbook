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
import java.time.ZoneId
import java.util.UUID

data class TripLogData(
    val profile: FleetProfile = FleetProfile(),
    val drivers: List<Driver> = emptyList(),
    val currentDriverId: String? = null,
    val trips: List<Trip> = emptyList(),
    val open: Trip? = null,
    val vehicle: VehicleCard = VehicleCard(),
    val incidents: List<Incident> = emptyList(),
    val pendingCharge: ChargeSession? = null,
    // Evidence and card records for automatic cost matching; see CardMatch.kt.
    val stops: List<ChargeSession> = emptyList(),
    val charges: List<CardCharge> = emptyList(),
)

/** Live-sync status published by the car's sync server for the vehicle screens. */
data class SyncInfo(
    val running: Boolean = false,
    val port: Int = 0,
    val addresses: List<String> = emptyList(),
    val pairingCode: String? = null,
    val pairingExpiresAt: Long = 0,
    val pairedPhones: Int = 0,
    val lastPhoneSeenAt: Long = 0,
    val error: String? = null,
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
    // Monotonic change counter the phone polls against; bumps on every data or live change.
    val revision: Long = 0,
    // Bumps only when the saved log changes, so the phone re-imports the file no more often than needed.
    val dataRevision: Long = 0,
    val sync: SyncInfo = SyncInfo(),
)

/** Single owner of the trip log, shared by the recording service, the car screen, and Gleo voice actions. */
class TripLogRepository private constructor(context: Context) {
    private val store = TripStore(context)
    internal val photos = PhotoStore(context)
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
        mutableState.update { it.copy(data = data, revision = it.revision + 1, dataRevision = it.dataRevision + 1) }
    }

    fun setSignal(signal: SignalStatus, model: String? = state.value.vehicleModel) {
        mutableState.update { it.copy(signal = signal, vehicleModel = model, revision = it.revision + 1) }
    }

    fun setSync(info: SyncInfo) = mutableState.update { it.copy(sync = info) }

    private var chargingSince: Long? = null
    private var chargingSocFrom: Int? = null
    private var settledAt = 0L
    private val zone: ZoneId get() = ZoneId.systemDefault()

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
                    val closed = withHistory(change.trip, if (change.trip.recovered) "앱 중단 후 마지막 수신 시각으로 종료" else "기어 P 전환으로 운행 종료", sample.at)
                    // A place the driver has labelled the same way every time needs no question.
                    val purpose = if (next.profile.autopilot) autoPurpose(closed, next.trips) else null
                    val history = if (purpose == null) closed else withHistory(closed.copy(purpose = purpose, purposeSource = EditSource.AUTO),
                        "목적 ${purpose.label} (같은 도착지 기록 기준 ${EditSource.AUTO.label})", sample.at)
                    finishedId = history.id
                    next.copy(open = null, trips = next.trips + history)
                }
                is TripChange.Discarded -> next.copy(open = null)
            }
        }
        // Charging stops become an expense prompt; the car knows when and how much battery, the driver adds the amount.
        val wasCharging = chargingSince != null
        if (sample.charging == true && chargingSince == null) { chargingSince = sample.at; chargingSocFrom = sample.socPercent }
        val observed = chargeSessionEnded(chargingSince, chargingSocFrom, sample, wasCharging)
        if (sample.charging == false) { chargingSince = null; chargingSocFrom = null }
        val card = next.vehicle
        val detected = observed ?: if (chargingSince == null && next.open == null && !sample.demo) refillAcrossGap(card, sample) else null
        val session = detected?.let { stop ->
            val place = (stored.destination ?: stored.address ?: next.trips.lastOrNull()?.let { it.destination ?: it.endAddress })?.take(120)
            // A signal-less charge shows up in steps; steps close together are one stop.
            next.stops.lastOrNull { it.demo == stop.demo && it.fuel == stop.fuel && it.chargeId == null && stop.startAt - it.endAt <= 600_000 }
                ?.copy(endAt = stop.endAt, socTo = stop.socTo, odometerKm = stop.odometerKm) ?: stop.copy(id = UUID.randomUUID().toString(), place = place)
        }
        if (session != null) next = next.copy(stops = (next.stops.filterNot { it.id == session.id } + session).takeLast(MAX_ENERGY_STOPS),
            pendingCharge = next.pendingCharge?.takeIf { it.id != session.id } ?: session)
        // The vehicle card refreshes from live signals so the logbook header always names the real car.
        val model = state.value.vehicleModel?.takeIf { it.isNotBlank() && !sample.demo }
        val refreshed = card.copy(model = model ?: card.model, vin = sample.vin ?: card.vin, odometerKm = sample.odometerKm ?: card.odometerKm,
            socPercent = sample.socPercent ?: card.socPercent, fuelPercent = sample.fuelPercent ?: card.fuelPercent, capturedAt = sample.at)
        val cardChanged = refreshed.model != card.model || refreshed.vin != card.vin
        // Parking refreshes the baseline too, so the next start compares against the level the car actually stopped with.
        if (!sample.demo && (cardChanged || session != null || finishedId != null || sample.at - card.capturedAt >= 300_000)) next = next.copy(vehicle = refreshed)
        // New evidence can resolve a card line that was waiting; the timer retires lines that never found a home.
        val settle = finishedId != null || session != null || (next.charges.any { it.state == ChargeState.WAITING } && sample.at - settledAt >= 60_000)
        val beforeSettle = next
        if (settle) { next = settleCharges(next, sample.at, zone); settledAt = sample.at }
        val settled = next != beforeSettle
        val open = next.open
        // Checkpoint a recording trip every minute or kilometre so an interruption loses little.
        val checkpoint = open != null && data.open != null && (sample.at - persistedAt >= 60_000 || open.endOdometerKm - persistedOdometer >= 1.0)
        if (next.open?.id != data.open?.id || next.trips.size != data.trips.size || checkpoint || session != null || cardChanged || settled) {
            save(next)
            persistedAt = sample.at
            persistedOdometer = open?.endOdometerKm ?: 0.0
        } else mutableState.update { it.copy(data = next, revision = it.revision + 1) }
        if (finishedId != null) mutableState.update { it.copy(lastFinishedId = finishedId) }
    }

    /** Records the charge as a fuel/charging expense on the most recent trip, or just clears the prompt. */
    suspend fun resolveCharge(amountWon: Long?, source: EditSource) = mutate { data ->
        val session = data.pendingCharge ?: return@mutate data
        var next = data.copy(pendingCharge = null)
        if (amountWon != null) {
            require(amountWon in 1..10_000_000) { "금액은 1원~1,000만 원으로 입력해 주세요." }
            val target = data.trips.filter { it.demo == session.demo && it.startAt <= session.endAt }.maxByOrNull { it.startAt }
                ?: throw IllegalArgumentException("충전 비용을 연결할 운행이 아직 없어요. 운행 후 상세에서 등록해 주세요.")
            val now = System.currentTimeMillis()
            next = next.editTrip(target.id) { trip ->
                require(trip.expenses.size < 30) { "한 운행에 비용은 30건까지 기록할 수 있어요." }
                withHistory(trip.copy(expenses = trip.expenses + TripExpense(ExpenseType.FUEL, amountWon, now, source)),
                    "충전 ${formatWon(amountWon)} 추가 · 차량 감지 ${session.socFrom ?: "?"}→${session.socTo ?: "?"}% (${source.label})", now)
            }
        }
        next
    }

    /** One tap at the scene: the car fills time, odometer, place, trip and driver; the note can follow later from either device. */
    suspend fun reportIncident(kind: IncidentKind, note: String = "", source: EditSource = EditSource.DRIVER): Incident {
        var created: Incident? = null
        mutate { data ->
            require(note.length <= 500) { "메모는 500자 이하로 입력해 주세요." }
            require(data.incidents.size < 500) { "특이사항 기록이 500건에 도달했어요." }
            val live = state.value.live
            val now = System.currentTimeMillis()
            val demo = data.profile.demoMode
            val trip = data.open?.takeIf { it.demo == demo } ?: data.trips.filter { it.demo == demo }.maxByOrNull { it.startAt }
            val incident = Incident(UUID.randomUUID().toString(), now, kind, live?.odometerKm ?: data.vehicle.odometerKm,
                live?.address?.takeIf { data.profile.storeAddresses }, trip?.id, data.currentDriverId ?: trip?.driverId, note.trim(), demo = demo)
            created = incident
            data.copy(incidents = data.incidents + incident)
        }
        return created!!
    }

    suspend fun updateIncident(id: String, note: String? = null, status: IncidentStatus? = null, kind: IncidentKind? = null) = mutate { data ->
        require(note == null || note.length <= 500) { "메모는 500자 이하로 입력해 주세요." }
        require(data.incidents.any { it.id == id }) { "특이사항 기록을 찾지 못했어요." }
        data.copy(incidents = data.incidents.map { item ->
            if (item.id != id) item
            else item.copy(note = note?.trim() ?: item.note, status = status ?: item.status, kind = kind ?: item.kind, updatedAt = System.currentTimeMillis())
        })
    }

    /** Card records from the phone. Runs inside the save so an approval and the trip it lands on are written together. */
    suspend fun ingestCardEvents(events: List<CardApproval>) = mutate { data ->
        val now = System.currentTimeMillis()
        settleCharges(mergeCardEvents(data, events, now, zone), now, zone)
    }

    suspend fun confirmCardCharge(id: String, tripId: String) = mutate { data -> confirmCharge(data, id, tripId, System.currentTimeMillis()) }
    suspend fun excludeCardCharge(id: String) = mutate { data -> excludeCharge(data, id, System.currentTimeMillis()) }

    suspend fun deleteIncident(id: String) {
        val orphaned = state.value.data.incidents.firstOrNull { it.id == id }?.photos.orEmpty()
        mutate { data -> data.copy(incidents = data.incidents.filterNot { it.id == id }) }
        withContext(Dispatchers.IO) { orphaned.forEach { photos.delete(it.id) } }
    }

    /** Stores the JPEG first, then records it; a crash between the two leaves an unreferenced file, never a dangling entry. */
    suspend fun attachPhoto(incidentId: String, photoId: String, jpeg: ByteArray, source: EditSource): IncidentPhoto {
        require(validUuid(photoId)) { "사진 식별자가 올바르지 않아요." }
        require(looksLikeJpeg(jpeg)) { "JPEG 사진만 첨부할 수 있어요 (최대 1.5MB)." }
        val photo = IncidentPhoto(photoId, System.currentTimeMillis(), source, jpeg.size)
        withContext(Dispatchers.IO) { photos.save(photoId, jpeg) }
        try {
            mutate { data ->
                val incident = data.incidents.firstOrNull { it.id == incidentId } ?: throw IllegalArgumentException("특이사항 기록을 찾지 못했어요.")
                require(incident.photos.size < MAX_INCIDENT_PHOTOS) { "사진은 기록당 ${MAX_INCIDENT_PHOTOS}장까지 첨부할 수 있어요." }
                require(data.incidents.none { i -> i.photos.any { it.id == photoId } }) { "이미 첨부된 사진이에요." }
                data.copy(incidents = data.incidents.map { if (it.id == incidentId) it.copy(photos = it.photos + photo, updatedAt = photo.at) else it })
            }
        } catch (e: Exception) { withContext(Dispatchers.IO) { photos.delete(photoId) }; throw e }
        return photo
    }

    suspend fun removePhoto(incidentId: String, photoId: String) {
        mutate { data ->
            require(data.incidents.any { it.id == incidentId }) { "특이사항 기록을 찾지 못했어요." }
            data.copy(incidents = data.incidents.map { if (it.id == incidentId) it.copy(photos = it.photos.filterNot { p -> p.id == photoId }, updatedAt = System.currentTimeMillis()) else it })
        }
        withContext(Dispatchers.IO) { photos.delete(photoId) }
    }

    /** Applies an edit sent by a paired phone; every change is attributed to the phone in the trip history. */
    suspend fun applyCommand(command: SyncCommand) {
        when (command) {
            is SyncCommand.Classify -> classify(command.tripId, command.purpose, EditSource.PHONE)
            is SyncCommand.Note -> setNote(command.tripId, command.note, EditSource.PHONE)
            is SyncCommand.AddExpense -> addExpense(command.tripId, command.type, command.amountWon, EditSource.PHONE)
            is SyncCommand.RemoveExpense -> removeExpense(command.tripId, command.index)
            is SyncCommand.SetTripDriver -> setTripDriver(command.tripId, command.driverId)
            is SyncCommand.ResolveCharge -> resolveCharge(command.amountWon, EditSource.PHONE)
            is SyncCommand.ReportIncident -> reportIncident(command.kind, command.note, EditSource.PHONE)
            is SyncCommand.UpdateIncident -> updateIncident(command.id, command.note, command.status, command.kind)
            is SyncCommand.RemovePhoto -> removePhoto(command.incidentId, command.photoId)
            is SyncCommand.CardEvents -> ingestCardEvents(command.events)
            is SyncCommand.ConfirmCharge -> confirmCardCharge(command.id, command.tripId)
            is SyncCommand.ExcludeCharge -> excludeCardCharge(command.id)
        }
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

    suspend fun classify(id: String, purpose: TripPurpose, source: EditSource, appendNote: String = "") = mutate { data ->
        require(appendNote.length <= 200) { "추가 메모는 200자 이하로 입력해 주세요." }
        data.editTrip(id) { trip ->
            val note = if (appendNote.isBlank()) trip.note else (trip.note + " " + appendNote.trim()).trim()
            require(note.length <= 300) { "메모는 300자 이하로 입력해 주세요." }
            if (trip.purpose == purpose && trip.purposeSource == source && trip.note == note) trip
            else withHistory(trip.copy(purpose = purpose, purposeSource = source, note = note),
                "목적 ${trip.purpose.label} → ${purpose.label} (${source.label})" + if (trip.note != note) " · 비고 추가" else "", System.currentTimeMillis())
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
        val trip = (data.trips + listOfNotNull(data.open)).firstOrNull { it.id == id }
        // Deleting a matched cost is the driver saying it does not belong here, so it must not match back in.
        val linked = trip?.expenses?.getOrNull(index)?.cardChargeId?.takeIf { charge -> data.charges.any { it.id == charge } }
        if (linked != null) return@mutate excludeCharge(data, linked, System.currentTimeMillis())
        data.editTrip(id) { current ->
            val item = current.expenses.getOrNull(index) ?: return@editTrip current
            withHistory(current.copy(expenses = current.expenses.filterIndexed { i, _ -> i != index }), "${item.type.label} ${formatWon(item.amountWon)} 삭제", System.currentTimeMillis())
        }
    }

    /** Applies one purpose to several closed trips in a single save, e.g. "the other N unclassified trips too". */
    suspend fun classifyMany(ids: Collection<String>, purpose: TripPurpose, source: EditSource) = mutate { data ->
        val targets = ids.toSet()
        val now = System.currentTimeMillis()
        data.copy(trips = data.trips.map { trip ->
            if (trip.id !in targets || trip.open || trip.purpose == purpose) trip
            else withHistory(trip.copy(purpose = purpose, purposeSource = source), "목적 ${trip.purpose.label} → ${purpose.label} (${source.label} · 일괄)", now)
        })
    }

    suspend fun deleteTrip(id: String) = mutate { data ->
        require(data.open?.id != id) { "기록 중인 운행은 삭제할 수 없어요." }
        data.copy(trips = data.trips.filterNot { it.id == id })
    }

    /** Puts back a trip removed by [deleteTrip] (undo); a trip with the same id is never duplicated. */
    suspend fun restoreTrip(trip: Trip) = mutate { data ->
        require(!trip.open) { "기록 중인 운행은 되돌릴 수 없어요." }
        if (data.trips.any { it.id == trip.id }) data
        else {
            require(data.trips.size < MAX_TRIPS) { "운행기록이 20,000건에 도달했어요. 먼저 기록을 내보내 주세요." }
            data.copy(trips = (data.trips + withHistory(trip, "삭제 취소", System.currentTimeMillis())).sortedWith(compareBy({ it.startAt }, { it.startOdometerKm })))
        }
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

    /** A copy from the car: a shared file never carries a trip in progress, a live sync keeps it so the phone can show it. */
    suspend fun importPhoneCopy(data: TripLogData, live: Boolean = false) = mutex.withLock {
        require(live || data.open == null) { "기록 중인 운행은 가져올 수 없어요." }
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
    require(profile.cards.size <= 10 && profile.cards.all { it.matches(Regex("[0-9]{4}")) }) { "카드 끝 4자리를 쉼표로 구분해 입력해 주세요." }
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
            if (p.isNull("annualCostWon")) null else p.getLong("annualCostWon"), p.getLong("reimbursePerKmWon"), p.getBoolean("storeAddresses"), p.getBoolean("demoMode"),
            p.optBoolean("phoneSync", false), p.optBoolean("autopilot", true),
            p.optJSONArray("cards")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty())
        validateProfile(profile)
        val drivers = json.getJSONArray("drivers").objects().map { Driver(it.getString("id"), it.getString("department"), it.getString("name")) }
        val trips = json.getJSONArray("trips").objects().map(::readTrip)
        // Fields added after 2.3 are optional so older files still load.
        val vehicle = json.optJSONObject("vehicle")?.let { v ->
            VehicleCard(v.text("model"), v.text("vin"), if (v.isNull("odometerKm")) null else v.getDouble("odometerKm"),
                if (v.isNull("socPercent")) null else v.getInt("socPercent"), v.optLong("capturedAt"), if (v.isNull("fuelPercent")) null else v.getInt("fuelPercent"))
        } ?: VehicleCard()
        val incidents = json.optJSONArray("incidents")?.objects()?.map { i ->
            Incident(i.getString("id"), i.getLong("at"), IncidentKind.valueOf(i.getString("kind")), if (i.isNull("odometerKm")) null else i.getDouble("odometerKm"),
                i.text("address"), i.text("tripId"), i.text("driverId"), i.getString("note"), IncidentStatus.valueOf(i.getString("status")), i.getLong("updatedAt"), i.optBoolean("demo"),
                i.optJSONArray("photos")?.objects()?.map { p -> IncidentPhoto(p.getString("id"), p.getLong("at"), EditSource.valueOf(p.getString("source")), p.getInt("bytes")) }.orEmpty())
        }.orEmpty()
        val charge = json.optJSONObject("pendingCharge")?.let(::readStop)
        val stops = json.optJSONArray("stops")?.objects()?.map(::readStop).orEmpty()
        val charges = json.optJSONArray("charges")?.objects()?.map { c ->
            CardCharge(c.getString("id"), c.getLong("at"), c.getBoolean("timeKnown"), c.getLong("amountWon"), c.getString("merchant"), c.text("last4"),
                CardSource.valueOf(c.getString("source")), c.text("category")?.let(CostCategory::valueOf), ChargeState.valueOf(c.getString("state")),
                c.text("tripId"), c.text("stopId"), c.optInt("score"), c.optString("reason"), c.text("statementId"), c.optBoolean("demo"))
        }.orEmpty()
        val data = TripLogData(profile, drivers, json.optString("currentDriverId").takeIf { it.isNotEmpty() && drivers.any { d -> d.id == it } },
            trips, if (json.isNull("open")) null else readTrip(json.getJSONObject("open")), vehicle, incidents, charge, stops, charges)
        validate(data)
        return data
    }

    fun encode(data: TripLogData): ByteArray {
        val json = JSONObject()
            .put("version", 1)
            .put("profile", JSONObject().put("companyName", data.profile.companyName).put("businessNumber", data.profile.businessNumber)
                .put("carModel", data.profile.carModel).put("plateNumber", data.profile.plateNumber)
                .put("annualCostWon", data.profile.annualCostWon ?: JSONObject.NULL).put("reimbursePerKmWon", data.profile.reimbursePerKmWon)
                .put("storeAddresses", data.profile.storeAddresses).put("demoMode", data.profile.demoMode).put("phoneSync", data.profile.phoneSync)
                .put("autopilot", data.profile.autopilot).put("cards", JSONArray(data.profile.cards)))
            .put("drivers", JSONArray(data.drivers.map { JSONObject().put("id", it.id).put("department", it.department).put("name", it.name) }))
            .put("currentDriverId", data.currentDriverId ?: "")
            .put("trips", JSONArray(data.trips.map(::writeTrip)))
            .put("open", data.open?.let(::writeTrip) ?: JSONObject.NULL)
            .put("vehicle", JSONObject().put("model", data.vehicle.model ?: JSONObject.NULL).put("vin", data.vehicle.vin ?: JSONObject.NULL)
                .put("odometerKm", data.vehicle.odometerKm ?: JSONObject.NULL).put("socPercent", data.vehicle.socPercent ?: JSONObject.NULL).put("capturedAt", data.vehicle.capturedAt)
                .put("fuelPercent", data.vehicle.fuelPercent ?: JSONObject.NULL))
            .put("incidents", JSONArray(data.incidents.map { i -> JSONObject().put("id", i.id).put("at", i.at).put("kind", i.kind.name)
                .put("odometerKm", i.odometerKm ?: JSONObject.NULL).put("address", i.address ?: JSONObject.NULL).put("tripId", i.tripId ?: JSONObject.NULL)
                .put("driverId", i.driverId ?: JSONObject.NULL).put("note", i.note).put("status", i.status.name).put("updatedAt", i.updatedAt).put("demo", i.demo)
                .put("photos", JSONArray(i.photos.map { p -> JSONObject().put("id", p.id).put("at", p.at).put("source", p.source.name).put("bytes", p.bytes) })) }))
            .put("pendingCharge", data.pendingCharge?.let(::writeStop) ?: JSONObject.NULL)
            .put("stops", JSONArray(data.stops.map(::writeStop)))
            .put("charges", JSONArray(data.charges.map { c -> JSONObject().put("id", c.id).put("at", c.at).put("timeKnown", c.timeKnown).put("amountWon", c.amountWon)
                .put("merchant", c.merchant).put("last4", c.last4 ?: JSONObject.NULL).put("source", c.source.name).put("category", c.category?.name ?: JSONObject.NULL)
                .put("state", c.state.name).put("tripId", c.tripId ?: JSONObject.NULL).put("stopId", c.stopId ?: JSONObject.NULL).put("score", c.score)
                .put("reason", c.reason).put("statementId", c.statementId ?: JSONObject.NULL).put("demo", c.demo) }))
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun readStop(c: JSONObject) = ChargeSession(c.getLong("startAt"), c.getLong("endAt"), if (c.isNull("socFrom")) null else c.getInt("socFrom"),
        if (c.isNull("socTo")) null else c.getInt("socTo"), if (c.isNull("odometerKm")) null else c.getDouble("odometerKm"), c.optBoolean("demo"),
        c.optString("id"), c.optBoolean("fuel"), c.text("place"), c.text("chargeId"))

    private fun writeStop(c: ChargeSession) = JSONObject().put("startAt", c.startAt).put("endAt", c.endAt).put("socFrom", c.socFrom ?: JSONObject.NULL)
        .put("socTo", c.socTo ?: JSONObject.NULL).put("odometerKm", c.odometerKm ?: JSONObject.NULL).put("demo", c.demo)
        .put("id", c.id).put("fuel", c.fuel).put("place", c.place ?: JSONObject.NULL).put("chargeId", c.chargeId ?: JSONObject.NULL)

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.text(key: String) = if (!has(key) || isNull(key)) null else getString(key)

    private fun readTrip(o: JSONObject) = Trip(
        id = o.getString("id"), startAt = o.getLong("startAt"), endAt = if (o.isNull("endAt")) null else o.getLong("endAt"),
        startOdometerKm = o.getDouble("startOdometerKm"), endOdometerKm = o.getDouble("endOdometerKm"),
        startAddress = o.text("startAddress"), endAddress = o.text("endAddress"), destination = o.text("destination"), driverId = o.text("driverId"),
        purpose = TripPurpose.valueOf(o.getString("purpose")), purposeSource = o.text("purposeSource")?.let(EditSource::valueOf),
        note = o.getString("note"),
        expenses = o.getJSONArray("expenses").objects().map { TripExpense(ExpenseType.valueOf(it.getString("type")), it.getLong("amountWon"), it.getLong("at"), EditSource.valueOf(it.getString("source")), it.text("cardChargeId"), it.text("merchant")) },
        origin = TripOrigin.valueOf(o.getString("origin")), recovered = o.getBoolean("recovered"), demo = o.getBoolean("demo"),
        history = o.getJSONArray("history").let { a -> (0 until a.length()).map { a.getString(it) } }, updatedAt = o.getLong("updatedAt"),
        tollFareWon = if (o.isNull("tollFareWon")) null else o.getLong("tollFareWon"),
        tollPasses = o.optJSONArray("tollPasses")?.objects()?.map { TollPass(it.getLong("at"), it.text("place")) }.orEmpty(),
    ).also { require(it.endOdometerKm >= it.startOdometerKm && it.startAt > 0) }

    private fun writeTrip(t: Trip) = JSONObject()
        .put("id", t.id).put("startAt", t.startAt).put("endAt", t.endAt ?: JSONObject.NULL)
        .put("startOdometerKm", t.startOdometerKm).put("endOdometerKm", t.endOdometerKm)
        .put("startAddress", t.startAddress ?: JSONObject.NULL).put("endAddress", t.endAddress ?: JSONObject.NULL)
        .put("destination", t.destination ?: JSONObject.NULL).put("driverId", t.driverId ?: JSONObject.NULL)
        .put("purpose", t.purpose.name).put("purposeSource", t.purposeSource?.name ?: JSONObject.NULL).put("note", t.note)
        .put("expenses", JSONArray(t.expenses.map { JSONObject().put("type", it.type.name).put("amountWon", it.amountWon).put("at", it.at).put("source", it.source.name)
            .put("cardChargeId", it.cardChargeId ?: JSONObject.NULL).put("merchant", it.merchant ?: JSONObject.NULL) }))
        .put("origin", t.origin.name).put("recovered", t.recovered).put("demo", t.demo)
        .put("history", JSONArray(t.history)).put("updatedAt", t.updatedAt).put("tollFareWon", t.tollFareWon ?: JSONObject.NULL)
        .put("tollPasses", JSONArray(t.tollPasses.map { JSONObject().put("at", it.at).put("place", it.place ?: JSONObject.NULL) }))

    private fun validate(data: TripLogData) {
        require(data.incidents.size <= 500 && data.incidents.all { validUuid(it.id) && it.at > 0 && it.note.length <= 500 &&
            it.photos.size <= MAX_INCIDENT_PHOTOS && it.photos.all { p -> validUuid(p.id) && p.bytes in 1..MAX_PHOTO_BYTES } })
        require(data.incidents.flatMap { it.photos }.map { it.id }.let { it.size == it.toSet().size })
        require(data.vehicle.model.orEmpty().length <= 60 && data.vehicle.vin.orEmpty().length <= 40)
        require(data.stops.size <= MAX_ENERGY_STOPS && data.stops.all { validUuid(it.id) && it.endAt >= it.startAt && it.place.orEmpty().length <= 120 })
        require(data.charges.size <= MAX_CARD_CHARGES && data.charges.map { it.id }.distinct().size == data.charges.size)
        require(data.charges.all { validUuid(it.id) && it.at > 0 && it.amountWon in 1..10_000_000 && it.merchant.length <= 60 && it.reason.length <= 200 })
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
