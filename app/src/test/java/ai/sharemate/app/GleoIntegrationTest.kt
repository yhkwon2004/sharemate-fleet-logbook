package ai.sharemate.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

class GleoIntegrationTest {
    @Test fun moneyRequiresWholeWonAndKnownExpenseType() {
        assertEquals(3600L, gleoExpenseAmount(JSONObject().put("amount", 3600)))
        assertEquals(10_000_000L, gleoExpenseAmount(JSONObject().put("amount", 10_000_000.0)))
        listOf(0, -1, .9, 3600.5, 10_000_001, "3600", JSONObject.NULL).forEach { amount ->
            assertNull(gleoExpenseAmount(JSONObject().put("amount", amount)))
        }
        assertNull(gleoExpense("invalid"))
    }

    @Test fun malformedJsonCannotFallBackToDifferentMutationExtras() {
        assertEquals(0, parseActionParam("not json", mapOf("purpose" to "business")).length())
        assertEquals(0, parseActionParam("{} trailing", emptyMap()).length())
        assertEquals(0, parseActionParam("x".repeat(8193), emptyMap()).length())
        val note = "거래처 \\ 자료 \"확인\""
        val encoded = JSONObject().put("purpose", "business").put("note", note).toString()
        assertEquals(note, parseActionParam(JSONObject.quote(encoded), emptyMap()).getString("note"))
        assertEquals(note, parseActionParam("\"$encoded\"", emptyMap()).getString("note"))
        assertEquals(0, parseActionParam(null, mapOf("unknown" to "ignored", "note" to "x".repeat(301))).length())
        assertEquals(0, parseActionParam(null, mapOf("amount" to Double.NaN)).length())
    }

    @Test fun summaryOnlyIncludesSelectedModeAndExposesNoNamesOrAddresses() {
        val date = LocalDate.of(2026, 10, 28)
        val at = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val demo = Trip("demo", at, at + 3_600_000, 100.0, 120.0, startAddress = "개인주소",
            destination = "거래처비밀", driverId = "d", purpose = TripPurpose.BUSINESS,
            expenses = listOf(TripExpense(ExpenseType.FUEL, 15000, at, EditSource.VOICE)), demo = true)
        val real = demo.copy(id = "real", demo = false, endOdometerKm = 200.0)
        val data = TripLogData(FleetProfile(demoMode = true, reimbursePerKmWon = 200),
            listOf(Driver("d", "개인부서", "직원실명")), "d", listOf(demo, real), real.copy(endAt = null))
        val before = TripLogCodec.encode(data).toList()
        val reply = gleoDataSummaryReply(data, "this_month", date, ZoneOffset.UTC)
        val result = JSONArray(reply.data).getJSONObject(0)
        assertEquals(20.0, result.getDouble("totalKm"), .0)
        assertEquals(15000, result.getLong("fuelExpenseWon"))
        assertEquals(15000, result.getLong("expenseWon"))
        assertEquals(4000, result.getLong("estimatedReimbursementWon"))
        assertEquals(1, result.getInt("driverCount"))
        assertFalse(result.getBoolean("recording"))
        assertTrue(result.getBoolean("demo"))
        listOf("개인주소", "거래처비밀", "개인부서", "직원실명").forEach { assertFalse(replyJson(reply).toString().contains(it)) }
        assertEquals(before, TripLogCodec.encode(data).toList())
        assertEquals("asking", gleoDataSummaryReply(data, "last_decade", date, ZoneOffset.UTC).status)
    }

    @Test fun officialSchemaKeepsThreeActionsAndMatchesAppVersion() {
        val schema = JSONObject(File("src/main/assets/schema.json").readText())
        val actions = schema.getJSONArray("actionCalls")
        assertEquals(3, actions.length())
        assertEquals(setOf(GLEO_SET_PURPOSE, GLEO_ADD_EXPENSE, GLEO_SUMMARY), (0 until actions.length()).map {
            val function = actions.getJSONObject(it).getJSONObject("function")
            assertEquals("service", function.getString("component"))
            function.getString("name")
        }.toSet())
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("versionName = \"${schema.getString("version")}\""))
    }

    @Test fun summaryWithholdsMileagePaymentEstimateForUnassignedEmployees() {
        val date = LocalDate.of(2026, 10, 28)
        val at = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val trip = Trip("pending", at, at + 3_600_000, 100.0, 120.0, purpose = TripPurpose.BUSINESS)
        val data = TripLogData(profile = FleetProfile(reimbursePerKmWon = 200), trips = listOf(trip))
        val result = JSONArray(gleoDataSummaryReply(data, "today", date, ZoneOffset.UTC).data).getJSONObject(0)
        assertEquals(20.0, result.getDouble("businessKm"), .0)
        assertEquals(0, result.getLong("estimatedReimbursementWon"))
        assertEquals(1, result.getInt("unassignedDriverTrips"))
        assertEquals(0, result.getInt("driverCount"))
    }
}
