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
}
