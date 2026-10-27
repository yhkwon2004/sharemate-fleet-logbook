package ai.sharemate.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal class SessionStore(context: Context) {
    private val base = File(context.noBackupFilesDir, "rental-session.json")
    private val file = AtomicFile(base)

    fun load(): RentalSession? {
        if (!base.exists() && !File(base.path + ".bak").exists()) return null
        val bytes = file.openRead().use { stream ->
            require(stream.channel.size() <= 32_768) { "저장된 이용 기록이 너무 큽니다." }
            stream.readBytes()
        }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.integer("version") == 1L) { "지원하지 않는 이용 기록입니다." }
        val checks = json.getJSONArray("checks")
        require(checks.length() <= 100) { "확인 항목이 너무 많습니다." }
        val session = RentalSession(
            id = json.string("id"),
            returnAt = json.integer("returnAt"),
            returnPlace = json.string("returnPlace"),
            purpose = Purpose.valueOf(json.string("purpose")),
            family = json.boolean("family"),
            pet = json.boolean("pet"),
            targetSoc = json.integer("targetSoc").also { require(it in 0L..100L) }.toInt(),
            timerEnd = if (json.isNull("timerEnd")) null else json.integer("timerEnd"),
            destinationNote = json.string("destinationNote"),
            parkingNote = json.string("parkingNote"),
            checks = (0 until checks.length()).map { checks.get(it).also { value -> require(value is String) } as String }.toSet(),
            boundDestinationId = if (json.isNull("boundDestinationId")) null else json.string("boundDestinationId"),
            phase = Phase.valueOf(json.string("phase")),
            startedAt = if (json.has("startedAt")) json.integer("startedAt") else 0,
            startingOdometerKm = if (json.isNull("startingOdometerKm")) null else json.getDouble("startingOdometerKm"),
            odometerSource = if (json.has("odometerSource")) SourceMode.valueOf(json.string("odometerSource")) else SourceMode.PLEOS,
            rates = if (json.isNull("rates")) null else json.getJSONObject("rates").let { rates ->
                // 1.4.0 and earlier stored per-minute tariffs; those cannot be converted, so the user re-enters the fare.
                if (rates.has("perMinuteWon")) null
                else RentalRates(rates.integer("rentalWon"), rates.integer("insuranceWon"), rates.integer("perKmWon"), rates.integer("latePer10MinWon"))
            },
        )
        validateSession(session)
        return session
    }

    fun save(session: RentalSession) {
        validateSession(session)
        val json = JSONObject().apply {
            put("version", 1)
            put("id", session.id)
            put("returnAt", session.returnAt)
            put("returnPlace", session.returnPlace)
            put("purpose", session.purpose.name)
            put("family", session.family)
            put("pet", session.pet)
            put("targetSoc", session.targetSoc)
            put("timerEnd", session.timerEnd ?: JSONObject.NULL)
            put("destinationNote", session.destinationNote)
            put("parkingNote", session.parkingNote)
            put("checks", JSONArray(session.checks.sorted()))
            put("boundDestinationId", session.boundDestinationId ?: JSONObject.NULL)
            put("phase", session.phase.name)
            put("startedAt", session.startedAt)
            put("startingOdometerKm", session.startingOdometerKm ?: JSONObject.NULL)
            put("odometerSource", session.odometerSource.name)
            put("rates", session.rates?.let { JSONObject().put("rentalWon", it.rentalWon).put("insuranceWon", it.insuranceWon).put("perKmWon", it.perKmWon).put("latePer10MinWon", it.latePer10MinWon) } ?: JSONObject.NULL)
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 32_768) { "이용 기록이 너무 큽니다." }
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
            if (!file.readFully().contentEquals(bytes)) throw IOException("이용 기록 저장을 확인하지 못했습니다.")
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun clear() {
        file.delete()
        if (listOf(base, File(base.path + ".bak"), File(base.path + ".new")).any { it.exists() }) {
            throw IOException("이용 기록을 삭제하지 못했습니다.")
        }
    }
}

private fun JSONObject.string(key: String): String = get(key).let { require(it is String); it }
private fun JSONObject.boolean(key: String): Boolean = get(key).let { require(it is Boolean); it }
private fun JSONObject.integer(key: String): Long = when (val value = get(key)) {
    is Int -> value.toLong()
    is Long -> value
    else -> throw IllegalArgumentException("이용 기록의 숫자 형식이 올바르지 않습니다.")
}
