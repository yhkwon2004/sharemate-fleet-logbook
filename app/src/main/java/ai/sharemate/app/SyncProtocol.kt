package ai.sharemate.app

import org.json.JSONObject
import java.security.SecureRandom

/*
 * Car-to-phone live sync. The vehicle hosts the server and owns the data; the phone is a client.
 *
 *   POST /pair        {code}                      -> {token, fingerprint, carId}   six-digit code shown on the car screen
 *   GET  /v2/state?since=<rev>&data=<dataRev>&wait=1 -> {revision, dataRevision, data?, live, signal, serverTime}  long-poll up to 20 s
 *                                                    `data` is present only when the saved log moved past <dataRev>
 *   POST /v2/command  {type, ...}                 -> {ok, revision}                 edits applied through the repository
 *   POST /v2/unpair                               -> {ok}
 *
 * Every /v2 call carries "Authorization: Bearer <token>". Tokens are random 256-bit values issued per phone at pairing.
 */

internal const val SYNC_PORT = 9247
internal const val SYNC_PAIR_TTL = 5 * 60_000L
internal const val SYNC_MAX_BODY = 64 * 1024
internal const val SYNC_LONG_POLL_MILLIS = 20_000L
internal const val SYNC_MAX_PAIR_FAILURES = 5

private val random = SecureRandom()

internal fun newPairingCode(): String = "%06d".format(random.nextInt(1_000_000))
internal fun newToken(): String = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
internal fun validToken(value: String?) = value != null && value.matches(Regex("[a-f0-9]{64}"))

/** Edits a phone may send; each maps to one repository call so validation stays in one place. */
sealed interface SyncCommand {
    data class Classify(val tripId: String, val purpose: TripPurpose) : SyncCommand
    data class Note(val tripId: String, val note: String) : SyncCommand
    data class AddExpense(val tripId: String, val type: ExpenseType, val amountWon: Long) : SyncCommand
    data class RemoveExpense(val tripId: String, val index: Int) : SyncCommand
    data class SetTripDriver(val tripId: String, val driverId: String) : SyncCommand
    data class ResolveCharge(val amountWon: Long?) : SyncCommand
    data class ReportIncident(val kind: IncidentKind, val note: String) : SyncCommand
    data class UpdateIncident(val id: String, val note: String?, val status: IncidentStatus?, val kind: IncidentKind?) : SyncCommand
    data class RemovePhoto(val incidentId: String, val photoId: String) : SyncCommand
}

/*
 * Photos are binary, so they bypass the command channel:
 *   POST /v2/photo   {incidentId, photoId, jpeg: base64}   -> {ok, revision}   phone-generated UUID, JPEG ≤ 1.5 MB
 *   GET  /v2/photo?id=<photoId>                             -> image/jpeg      for a phone that joined after the photo was taken
 */
internal const val SYNC_MAX_PHOTO_BODY = 2_200_000

private fun JSONObject.id(key: String): String = getString(key).also { require(validUuid(it)) { "식별자가 올바르지 않아요." } }

/** Strict parser: unknown types, missing fields, and out-of-range values are rejected before anything touches the log. */
internal fun parseSyncCommand(json: JSONObject): SyncCommand = when (val type = json.optString("type")) {
    "classify" -> SyncCommand.Classify(json.id("tripId"), TripPurpose.valueOf(json.getString("purpose")))
    "note" -> SyncCommand.Note(json.id("tripId"), json.getString("note").also { require(it.length <= 300) })
    "addExpense" -> SyncCommand.AddExpense(json.id("tripId"), ExpenseType.valueOf(json.getString("expenseType")),
        json.getLong("amountWon").also { require(it in 1..10_000_000) })
    "removeExpense" -> SyncCommand.RemoveExpense(json.id("tripId"), json.getInt("index").also { require(it in 0..29) })
    "setTripDriver" -> SyncCommand.SetTripDriver(json.id("tripId"), json.id("driverId"))
    "resolveCharge" -> SyncCommand.ResolveCharge(if (json.isNull("amountWon")) null else json.getLong("amountWon").also { require(it in 1..10_000_000) })
    "reportIncident" -> SyncCommand.ReportIncident(IncidentKind.valueOf(json.getString("kind")), json.optString("note").also { require(it.length <= 500) })
    "updateIncident" -> SyncCommand.UpdateIncident(json.id("id"), if (json.isNull("note")) null else json.getString("note").also { require(it.length <= 500) },
        if (json.isNull("status")) null else IncidentStatus.valueOf(json.getString("status")),
        if (json.isNull("kind")) null else IncidentKind.valueOf(json.getString("kind")))
    "removePhoto" -> SyncCommand.RemovePhoto(json.id("incidentId"), json.id("photoId"))
    else -> throw IllegalArgumentException("Unknown command: $type")
}

internal fun encodeSyncCommand(command: SyncCommand): JSONObject = when (command) {
    is SyncCommand.Classify -> JSONObject().put("type", "classify").put("tripId", command.tripId).put("purpose", command.purpose.name)
    is SyncCommand.Note -> JSONObject().put("type", "note").put("tripId", command.tripId).put("note", command.note)
    is SyncCommand.AddExpense -> JSONObject().put("type", "addExpense").put("tripId", command.tripId).put("expenseType", command.type.name).put("amountWon", command.amountWon)
    is SyncCommand.RemoveExpense -> JSONObject().put("type", "removeExpense").put("tripId", command.tripId).put("index", command.index)
    is SyncCommand.SetTripDriver -> JSONObject().put("type", "setTripDriver").put("tripId", command.tripId).put("driverId", command.driverId)
    is SyncCommand.ResolveCharge -> JSONObject().put("type", "resolveCharge").put("amountWon", command.amountWon ?: JSONObject.NULL)
    is SyncCommand.ReportIncident -> JSONObject().put("type", "reportIncident").put("kind", command.kind.name).put("note", command.note)
    is SyncCommand.UpdateIncident -> JSONObject().put("type", "updateIncident").put("id", command.id).put("note", command.note ?: JSONObject.NULL)
        .put("status", command.status?.name ?: JSONObject.NULL).put("kind", command.kind?.name ?: JSONObject.NULL)
    is SyncCommand.RemovePhoto -> JSONObject().put("type", "removePhoto").put("incidentId", command.incidentId).put("photoId", command.photoId)
}

/** What the phone sees of the car: the whole log only when the saved log moved, live signals every time. */
internal fun encodeSyncState(state: TripLogState, dataSince: Long, now: Long): JSONObject {
    val changed = state.dataRevision != dataSince
    val live = state.live
    return JSONObject()
        .put("revision", state.revision).put("dataRevision", state.dataRevision).put("changed", changed).put("serverTime", now)
        .put("signal", state.signal.name).put("vehicleModel", state.vehicleModel ?: JSONObject.NULL)
        .put("live", live?.let { JSONObject().put("at", it.at).put("gear", it.gear.name).put("odometerKm", it.odometerKm ?: JSONObject.NULL)
            .put("charging", it.charging ?: JSONObject.NULL).put("socPercent", it.socPercent ?: JSONObject.NULL).put("demo", it.demo) } ?: JSONObject.NULL)
        .apply { if (changed) put("data", JSONObject(TripLogCodec.encode(state.data).toString(Charsets.UTF_8))) }
}

/** The phone's picture of the car between polls; `data` is the last full copy it received. */
data class PhoneLink(
    val connected: Boolean = false,
    val revision: Long = -1,
    val lastSeenAt: Long = 0,
    val signal: SignalStatus? = null,
    val live: DriveSample? = null,
    val message: String = "차량 연결 전",
)

internal fun decodeLive(json: JSONObject?): DriveSample? = json?.let {
    DriveSample(it.getLong("at"), runCatching { GearState.valueOf(it.getString("gear")) }.getOrDefault(GearState.UNKNOWN),
        if (it.isNull("odometerKm")) null else it.getDouble("odometerKm"), demo = it.optBoolean("demo"),
        charging = if (it.isNull("charging")) null else it.getBoolean("charging"), socPercent = if (it.isNull("socPercent")) null else it.getInt("socPercent"))
}
