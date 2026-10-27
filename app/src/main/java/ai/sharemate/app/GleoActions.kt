package ai.sharemate.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

// Function names must match assets/schema.json and the receiver's intent filters.
internal const val GLEO_SET_PURPOSE = "SET_TRIP_PURPOSE"
internal const val GLEO_ADD_EXPENSE = "ADD_TRIP_EXPENSE"
internal const val GLEO_SUMMARY = "GET_DRIVING_LOG_SUMMARY"
internal const val GLEO_RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

data class GleoReply(val functionName: String, val status: String, val message: String, val data: String? = null)

/**
 * Gleo documents both a single ACTION_PARAM JSON extra (sometimes wrapped in extra quotes) and one extra per parameter.
 * Both are accepted so the integration keeps working whichever form the platform sends.
 */
internal fun parseActionParam(raw: String?, extras: Map<String, Any?>): JSONObject {
    var text = raw?.trim().orEmpty()
    if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) text = text.substring(1, text.length - 1).replace("\\\"", "\"")
    val parsed = runCatching { if (text.isNotEmpty()) JSONObject(text) else null }.getOrNull()
    if (parsed != null) return parsed
    return JSONObject().apply { extras.forEach { (key, value) -> if (key != "ACTION_PARAM" && value != null) put(key, value) } }
}

internal fun gleoPurpose(value: String?): TripPurpose? = when (value?.trim()?.lowercase(Locale.ROOT)) {
    "commute" -> TripPurpose.COMMUTE
    "business" -> TripPurpose.BUSINESS
    "personal" -> TripPurpose.PERSONAL
    else -> null
}

internal fun gleoExpense(value: String?): ExpenseType? = when (value?.trim()?.lowercase(Locale.ROOT)) {
    "fuel" -> ExpenseType.FUEL
    "toll" -> ExpenseType.TOLL
    "parking" -> ExpenseType.PARKING
    "other" -> ExpenseType.OTHER
    else -> null
}

internal fun gleoPeriod(value: String?, today: LocalDate): Pair<LocalDate, LocalDate>? = when (value?.trim()?.lowercase(Locale.ROOT)) {
    "today" -> today to today
    "this_month", null, "" -> today.withDayOfMonth(1) to today
    "this_year" -> today.withDayOfYear(1) to today
    else -> null
}

/** Voice summary: totals only, never addresses or names, because the callback is a broadcast. */
internal fun gleoSummaryReply(period: String, totals: LogbookTotals, unclassified: Int, recording: Trip?): GleoReply {
    val ratio = totals.ratio?.let { String.format(Locale.US, "%.0f%%", it * 100) } ?: "no data"
    val parts = mutableListOf("For $period, ${String.format(Locale.US, "%.1f", totals.totalKm)} km driven in ${totals.trips} trips, " +
        "${String.format(Locale.US, "%.1f", totals.businessKm)} km for business ($ratio).")
    if (unclassified > 0) parts += "$unclassified trips still need a purpose."
    if (recording != null) parts += "A trip is being recorded now."
    val data = JSONArray().put(JSONObject()
        .put("period", period).put("totalKm", totals.totalKm).put("businessKm", totals.businessKm)
        .put("businessRatio", totals.ratio ?: JSONObject.NULL).put("trips", totals.trips).put("unclassifiedTrips", unclassified)
        .put("recording", recording != null)).toString()
    return GleoReply(GLEO_SUMMARY, "success", parts.joinToString(" "), data)
}

internal fun replyJson(reply: GleoReply): JSONObject = JSONObject().apply {
    put("functionName", reply.functionName)
    put("status", reply.status)
    put("message", reply.message)
    reply.data?.let { put("data", it) }
}

/** Receives Gleo function calls (component "service") and answers within Gleo's 7-second window. */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(GLEO_SET_PURPOSE, GLEO_ADD_EXPENSE, GLEO_SUMMARY)) return
        val extras = intent.extras?.let { bundle -> bundle.keySet().associateWith { key -> @Suppress("DEPRECATION") bundle.get(key) } }.orEmpty()
        val params = parseActionParam(intent.getStringExtra("ACTION_PARAM"), extras)
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            val reply = try {
                withTimeout(5_000) { handle(app, action, params) }
            } catch (_: Exception) {
                GleoReply(action, "fail", "The driving log could not be updated right now.")
            }
            sendReply(app, reply)
            pending.finish()
        }
    }

    private suspend fun handle(context: Context, action: String, params: JSONObject): GleoReply {
        val repository = TripLogRepository.get(context)
        return when (action) {
            GLEO_SET_PURPOSE -> {
                val purpose = gleoPurpose(params.optString("purpose", ""))
                    ?: return GleoReply(action, "asking", "Should this trip be logged as commute, business, or personal?")
                val target = repository.voiceTarget() ?: return GleoReply(action, "fail", "There is no recent trip to classify.")
                val note = params.optString("note", "").trim().take(200)
                val combinedNote = (target.note + " " + note).trim()
                if (note.isNotEmpty() && combinedNote.length > 300) return GleoReply(action, "asking", "Please shorten the note to 300 characters.")
                repository.classify(target.id, purpose, EditSource.VOICE)
                if (note.isNotEmpty()) repository.setNote(target.id, combinedNote, EditSource.VOICE)
                GleoReply(action, "success", "The ${if (target.open) "current" else "last"} trip is logged as ${purpose.name.lowercase(Locale.ROOT)}.",
                    JSONArray().put(JSONObject().put("purpose", purpose.name.lowercase(Locale.ROOT)).put("recording", target.open)).toString())
            }
            GLEO_ADD_EXPENSE -> {
                val type = gleoExpense(params.optString("expenseType", "")) ?: ExpenseType.OTHER
                val amount = params.optDouble("amount", Double.NaN)
                if (!amount.isFinite() || amount <= 0 || amount > 10_000_000) return GleoReply(action, "asking", "How much was the expense in won?")
                val target = repository.voiceTarget() ?: return GleoReply(action, "fail", "There is no recent trip to attach the expense to.")
                repository.addExpense(target.id, type, amount.toLong(), EditSource.VOICE)
                GleoReply(action, "success", "Added a ${type.name.lowercase(Locale.ROOT)} expense of ${amount.toLong()} won to the trip.",
                    JSONArray().put(JSONObject().put("expenseType", type.name.lowercase(Locale.ROOT)).put("amountWon", amount.toLong())).toString())
            }
            else -> {
                val label = params.optString("period", "this_month").ifBlank { "this_month" }
                val zone = ZoneId.systemDefault()
                val (from, to) = gleoPeriod(label, LocalDate.now(zone))
                    ?: return GleoReply(action, "asking", "Do you want today, this month, or this year?")
                val data = repository.ensureLoaded()
                val trips = tripsIn(data.trips.filter { it.demo == data.profile.demoMode }, from, to, zone)
                gleoSummaryReply(label.replace('_', ' '), logbookTotals(trips), trips.count { it.purpose == TripPurpose.UNCLASSIFIED }, data.open)
            }
        }
    }

    private fun sendReply(context: Context, reply: GleoReply) {
        runCatching {
            context.sendBroadcast(Intent(GLEO_RESPONSE_ACTION)
                .putExtra("packageName", context.packageName)
                .putExtra("response", replyJson(reply).toString()))
        }
    }

    private companion object { val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) }
}
