package ai.sharemate.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class LogbookExportTest {
    private fun fixture(): TripLogData {
        val driver = Driver("a0000000-0000-4000-8000-000000000001", "시연 부서", "시연 운전자")
        val at = Instant.parse("2026-10-27T01:00:00Z").toEpochMilli()
        val trips = listOf(TripPurpose.COMMUTE, TripPurpose.BUSINESS, TripPurpose.PERSONAL).mapIndexed { index, purpose ->
            val startKm = listOf(12000.0, 12008.0, 12020.0)[index]
            val endKm = listOf(12008.0, 12020.0, 12025.0)[index]
            Trip("b0000000-0000-4000-8000-00000000000${index + 1}", at + index * 3_600_000,
                at + index * 3_600_000 + 1_800_000, startKm, endKm, "서울역", "판교역", "시연 목적지",
                driver.id, purpose, EditSource.DRIVER, note = if (index == 0) "=1+1 & <확인>" else "시연 데이터", demo = true)
        }
        return TripLogData(FleetProfile(companyName = "SHAREMATE 시연 법인", plateNumber = "시연 00가0000", demoMode = true), listOf(driver), driver.id, trips)
    }

    @Test fun portableCopyRoundTripsAllClosedTripsAndDoesNotInventOpenTrip() {
        val original = fixture()
        assertEquals(original, TripLogCodec.decode(TripLogCodec.encode(original)))
        val json = JSONObject(TripLogCodec.encode(original).toString(Charsets.UTF_8))
        json.getJSONArray("trips").getJSONObject(0).put("endOdometerKm", -1)
        assertThrows(IllegalArgumentException::class.java) { TripLogCodec.decode(json.toString().toByteArray()) }
        val duplicate = original.copy(trips = original.trips + original.trips.first())
        assertThrows(IllegalArgumentException::class.java) { TripLogCodec.decode(TripLogCodec.encode(duplicate)) }
        assertThrows(IllegalArgumentException::class.java) { TripLogCodec.decode(ByteArray(LOGBOOK_MAX_BYTES + 1)) }
    }

    @Test fun excelIsAnOoxmlWorkbookWithNumericDistancesAndInertUserText() {
        val data = fixture(); val from = LocalDate.of(2026, 10, 1); val to = LocalDate.of(2026, 10, 31)
        val bytes = logbookXlsx(data.profile, from, to, logbookRows(data.trips, data.drivers, ZoneId.of("Asia/Seoul")), logbookTotals(data.trips))
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
        }
        assertTrue(entries.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/styles.xml", "xl/worksheets/sheet1.xml")))
        entries.values.forEach { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(it)) }
        val sheet = entries.getValue("xl/worksheets/sheet1.xml").toString(Charsets.UTF_8)
        assertTrue(sheet.contains("<c r=\"F6\" s=\"3\"><v>8.0</v></c>"))
        assertTrue(sheet.contains("=1+1 &amp; &lt;확인&gt;")); assertFalse(sheet.contains("<f>"))
        assertTrue(sheet.contains("80.0%")); assertTrue(sheet.contains("state=\"frozen\""))
        val demo = File("build/outputs/logbook-demo").apply { mkdirs() }
        File(demo, "SHAREMATE-demo.smlog").writeBytes(TripLogCodec.encode(data))
        File(demo, "SHAREMATE-demo.xlsx").writeBytes(bytes)
    }

    @Test fun employeeSummaryKeepsActualFuelSeparateFromAllowanceAndWithholdsUnassignedDriver() {
        val data = fixture()
        val closed = data.trips.mapIndexed { index, trip -> if (index == 1) trip.copy(expenses = listOf(
            TripExpense(ExpenseType.FUEL, 60_000, trip.startAt, EditSource.DRIVER),
            TripExpense(ExpenseType.TOLL, 3_600, trip.startAt, EditSource.VOICE))) else trip }
        val unassigned = closed.last().copy(id = "missing", startOdometerKm = 12025.0, endOdometerKm = 12029.0,
            driverId = null, purpose = TripPurpose.BUSINESS)
        val open = closed.first().copy(id = "open", endAt = null, endOdometerKm = 12100.0)
        val summary = employeeUsage(closed + unassigned + open, data.drivers, 150)
        assertEquals(2, summary.size)
        val employee = summary.first { it.driver != null }
        assertEquals(25.0, employee.totals.totalKm, 0.0)
        assertEquals(20.0, employee.totals.businessKm, 0.0)
        assertEquals(63_600L, employee.totals.expenseWon)
        assertEquals(60_000L, employee.fuelWon)
        assertEquals(3_000L, employee.reimbursementWon)
        assertEquals(0L, summary.first { it.driver == null }.reimbursementWon)
        assertThrows(IllegalArgumentException::class.java) { employeeUsage(closed, data.drivers, -1) }
    }

    @Test fun evidenceWorkbookIncludesEmployeeExpensesAndIssuesWithoutMixingModesOrOpenTrips() {
        val data = fixture()
        val selected = data.trips.mapIndexed { index, trip -> if (index == 1) trip.copy(expenses = listOf(
            TripExpense(ExpenseType.FUEL, 60_000, trip.startAt, EditSource.DRIVER),
            TripExpense(ExpenseType.TOLL, 3_600, trip.startAt + 1, EditSource.VOICE))) else trip }
        val open = selected.first().copy(id = "open", endAt = null)
        val real = selected.first().copy(id = "real", demo = false, endOdometerKm = 99999.0)
        val outside = selected.first().copy(id = "previous", startAt = Instant.parse("2026-09-30T00:00:00Z").toEpochMilli())
        val profile = data.profile.copy(reimbursePerKmWon = 150)
        val bytes = logbookXlsx(profile, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), selected + open + real + outside, data.drivers, ZoneId.of("Asia/Seoul"))
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        entries.values.forEach { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(it.toByteArray())) }
        val workbook = entries.getValue("xl/workbook.xml")
        listOf("운행기록부", "직원별 현황", "경비원장", "확인사항").forEach { assertTrue(workbook.contains("name=\"$it\"")) }
        assertTrue(entries.getValue("xl/worksheets/sheet1.xml").contains("<v>25.0</v>"))
        assertFalse(entries.getValue("xl/worksheets/sheet1.xml").contains("99999"))
        val sheet = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(entries.getValue("xl/worksheets/sheet1.xml").toByteArray()))
        val recordRows = sheet.getElementsByTagName("row")
        val expenseRecord = (0 until recordRows.length).map { recordRows.item(it) as org.w3c.dom.Element }.first { it.getAttribute("r") == "7" }
        assertTrue(expenseRecord.getAttribute("ht").toInt() >= 36)
        val employees = entries.getValue("xl/worksheets/sheet2.xml")
        assertTrue(employees.contains("<c r=\"F6\" s=\"4\"><v>60000</v></c>"))
        assertTrue(employees.contains("<c r=\"I6\" s=\"4\"><v>3000</v></c>"))
        val expenses = entries.getValue("xl/worksheets/sheet3.xml")
        assertTrue(expenses.contains("주유·충전") && expenses.contains("통행료") && expenses.contains("영수증 별도 확인·보관"))
        val checks = entries.getValue("xl/worksheets/sheet4.xml")
        assertTrue(checks.contains("사업자등록번호 항목을 입력해 주세요."))
        assertTrue(checks.contains("시연 데이터"))
        assertFalse(entries.values.any { it.contains("<f>") })
        File("build/outputs/logbook-demo/SHAREMATE-evidence.xlsx").apply { requireNotNull(parentFile).mkdirs(); writeBytes(bytes) }
    }

    @Test fun previewRowsMatchEveryExportedSheetIncludingExpensesAndMissingDriverChecks() {
        val original = fixture()
        val trips = original.trips.mapIndexed { index, trip -> when (index) {
            0 -> trip.copy(expenses = listOf(TripExpense(ExpenseType.FUEL, 55_000, trip.startAt, EditSource.DRIVER)))
            1 -> trip.copy(driverId = null, purpose = TripPurpose.UNCLASSIFIED)
            else -> trip
        } }
        val ignored = listOf(trips.first().copy(id = "open", endAt = null), trips.first().copy(id = "real", demo = false),
            trips.first().copy(id = "outside", startAt = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()))
        val from = LocalDate.of(2026, 10, 1); val to = LocalDate.of(2026, 10, 31); val zone = ZoneId.of("Asia/Seoul")
        val sheets = logbookSheets(original.profile, from, to, trips + ignored, original.drivers, zone)
        assertEquals(listOf("운행기록부", "직원별 현황", "경비원장", "확인사항"), sheets.map { it.name })
        assertEquals(3, sheets[0].rows.size)
        assertEquals(55_000L, sheets[2].rows.single()[5])
        assertTrue(sheets[3].rows.any { it[0] == IssueType.NO_DRIVER.label })
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(logbookXlsx(original.profile, from, to, trips + ignored, original.drivers, zone))).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
        }
        sheets.forEachIndexed { sheetIndex, sheet ->
            val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(ByteArrayInputStream(entries.getValue("xl/worksheets/sheet${sheetIndex + 1}.xml")))
            val nodes = xml.getElementsByTagName("row")
            val byNumber = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }.associateBy { it.getAttribute("r").toInt() }
            (listOf(sheet.columns) + sheet.rows).forEachIndexed { rowIndex, row ->
                val cells = byNumber.getValue(rowIndex + 5).getElementsByTagName("c")
                assertEquals(row.size, cells.length)
                row.forEachIndexed { column, value -> assertEquals(value.toString(), cells.item(column).textContent) }
            }
            assertEquals(0, xml.getElementsByTagName("f").length)
        }
    }
}
