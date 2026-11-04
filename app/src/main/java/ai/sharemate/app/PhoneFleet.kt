package ai.sharemate.app

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/*
 * The company's vehicles on this phone (2.9). A vehicle is registered by pairing with its car (live link, reservations,
 * remote check) or by typing its details (listed, not yet connected). One vehicle is selected at a time; the phone keeps
 * a cached copy of each paired car's log so switching shows the last known state at once while the link reconnects.
 */

data class FleetVehicle(
    /** The car's TLS certificate fingerprint for a paired car (stable across re-pairing), a random id for a typed one. */
    val id: String,
    val plate: String,
    val model: String = "",
    val company: String = "",
    /** "endpoint|token|fingerprint" (see [parseLink]); null for a vehicle registered by hand or unpaired. */
    val link: String? = null,
    val addedAt: Long = 0,
    val lastSeenAt: Long = 0,
) {
    val paired get() = link != null
    val title get() = plate.ifBlank { "차량번호 미등록" }
}

internal data class FleetState(val vehicles: List<FleetVehicle> = emptyList(), val selectedId: String? = null) {
    val selected get() = vehicles.firstOrNull { it.id == selectedId }
}

internal const val MAX_FLEET_VEHICLES = 30
private val PLATE = Regex("^[0-9가-힣A-Za-z ]{2,12}$")

/** Validates a typed vehicle; the sentence is what the form shows under the field. */
internal fun validateFleetVehicle(plate: String, model: String, company: String) {
    require(PLATE.matches(plate.trim())) { "차량번호를 확인해 주세요. 예: 12가 3456" }
    require(model.length <= 40 && company.length <= 60) { "차종은 40자, 회사명은 60자까지예요." }
}

internal fun encodeFleet(state: FleetState): String = JSONObject().put("selected", state.selectedId ?: JSONObject.NULL)
    .put("vehicles", JSONArray(state.vehicles.map { v -> JSONObject().put("id", v.id).put("plate", v.plate).put("model", v.model).put("company", v.company)
        .put("link", v.link ?: JSONObject.NULL).put("addedAt", v.addedAt).put("lastSeenAt", v.lastSeenAt) })).toString()

internal fun decodeFleet(text: String): FleetState {
    val json = JSONObject(text)
    val array = json.optJSONArray("vehicles") ?: JSONArray()
    val vehicles = (0 until array.length()).map { array.getJSONObject(it) }.map { v ->
        FleetVehicle(v.getString("id"), v.optString("plate"), v.optString("model"), v.optString("company"),
            if (v.isNull("link")) null else v.getString("link").takeIf { runCatching { parseLink(it) }.isSuccess }, v.optLong("addedAt"), v.optLong("lastSeenAt"))
    }.distinctBy { it.id }.take(MAX_FLEET_VEHICLES)
    val selected = if (json.isNull("selected")) null else json.getString("selected")
    return FleetState(vehicles, selected?.takeIf { id -> vehicles.any { it.id == id } } ?: vehicles.firstOrNull()?.id)
}

/** Adds or refreshes a paired car by its fingerprint; keeps the registration date and fills names the car reported. */
internal fun upsertPaired(state: FleetState, fingerprint: String, link: String, data: TripLogData?, now: Long): FleetState {
    val existing = state.vehicles.firstOrNull { it.id == fingerprint }
    val plate = data?.profile?.plateNumber?.takeIf { it.isNotBlank() } ?: existing?.plate.orEmpty()
    val model = (data?.vehicle?.model ?: data?.profile?.carModel)?.takeIf { it.isNotBlank() } ?: existing?.model.orEmpty()
    val company = data?.profile?.companyName?.takeIf { it.isNotBlank() } ?: existing?.company.orEmpty()
    val vehicle = FleetVehicle(fingerprint, plate, model, company, link, existing?.addedAt ?: now, now)
    require(existing != null || state.vehicles.size < MAX_FLEET_VEHICLES) { "차량은 ${MAX_FLEET_VEHICLES}대까지 등록할 수 있어요." }
    return FleetState(if (existing == null) state.vehicles + vehicle else state.vehicles.map { if (it.id == fingerprint) vehicle else it }, fingerprint)
}

/** What the car reported most recently, so the list shows real names; only for the vehicle the copy belongs to. */
internal fun refreshFromCopy(state: FleetState, id: String, data: TripLogData, now: Long): FleetState =
    state.copy(vehicles = state.vehicles.map { v ->
        if (v.id != id) v else v.copy(plate = data.profile.plateNumber.ifBlank { v.plate }, model = (data.vehicle.model ?: data.profile.carModel).ifBlank { v.model },
            company = data.profile.companyName.ifBlank { v.company }, lastSeenAt = now)
    })

class FleetStore private constructor(context: Context) {
    private val dir = File(context.applicationContext.noBackupFilesDir, "fleet").apply { mkdirs() }
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "fleet.json"))
    private val lock = Mutex()
    private val mutable = MutableStateFlow(runCatching { if (file.baseFile.exists()) decodeFleet(file.readFully().toString(Charsets.UTF_8)) else FleetState() }.getOrDefault(FleetState()))
    internal val state: StateFlow<FleetState> = mutable.asStateFlow()

    internal suspend fun mutate(transform: (FleetState) -> FleetState): FleetState = lock.withLock {
        val next = transform(mutable.value)
        if (next != mutable.value) withContext(Dispatchers.IO) {
            val stream = file.startWrite()
            try { stream.write(encodeFleet(next).toByteArray(Charsets.UTF_8)); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
        }
        mutable.value = next
        next
    }

    /** Registers a vehicle by hand (not yet connected); it becomes the selected one. */
    suspend fun addManual(plate: String, model: String, company: String, now: Long = System.currentTimeMillis()): FleetVehicle {
        validateFleetVehicle(plate, model, company)
        val vehicle = FleetVehicle(UUID.randomUUID().toString(), plate.trim(), model.trim(), company.trim(), null, now, 0)
        mutate { s ->
            require(s.vehicles.size < MAX_FLEET_VEHICLES) { "차량은 ${MAX_FLEET_VEHICLES}대까지 등록할 수 있어요." }
            require(s.vehicles.none { it.plate.replace(" ", "") == vehicle.plate.replace(" ", "") }) { "이미 등록된 차량번호예요." }
            FleetState(s.vehicles + vehicle, s.selectedId ?: vehicle.id)
        }
        return vehicle
    }

    internal fun cacheFile(id: String) = File(dir, "${id.filter { it.isLetterOrDigit() || it == '-' }}.json")

    internal suspend fun saveCopy(id: String, data: TripLogData) = withContext(Dispatchers.IO) {
        runCatching { AtomicFile(cacheFile(id)).let { f -> val s = f.startWrite(); try { s.write(TripLogCodec.encode(data)); f.finishWrite(s) } catch (e: Exception) { f.failWrite(s); throw e } } }
    }

    internal suspend fun loadCopy(id: String): TripLogData? = withContext(Dispatchers.IO) {
        runCatching { cacheFile(id).takeIf { it.exists() }?.readBytes()?.let(TripLogCodec::decode) }.getOrNull()
    }

    internal suspend fun deleteCopy(id: String) = withContext(Dispatchers.IO) { cacheFile(id).delete() }

    companion object {
        @Volatile private var instance: FleetStore? = null
        fun get(context: Context): FleetStore = instance ?: synchronized(this) { instance ?: FleetStore(context).also { instance = it } }
    }
}
