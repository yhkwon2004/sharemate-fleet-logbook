package ai.sharemate.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FleetCareUiTest {
    private val today = LocalDate.of(2026, 11, 1)
    private val care = VehicleCare(inspectionDue = "2026-11-20", insuranceDue = "2026-10-25", leaseEnd = "2027-06-30", serviceIntervalKm = 10_000, lastServiceKm = 42_000.0)
    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    @Test fun onlySoonAndOverdueDeadlinesBecomeHomeCardsAndSnoozingHidesExactlyOne() {
        val due = careDueAlerts(care, 51_300.0, today, emptySet())
        assertEquals("리스는 여유가 있어 카드가 되지 않음", listOf(CareKind.INSURANCE, CareKind.INSPECTION, CareKind.SERVICE), due.map { it.kind })
        val first = due.first()
        val snoozed = withCareSnooze(emptySet(), careSnoozeKey(first, care), now)
        val remaining = careDueAlerts(care, 51_300.0, today, activeCareSnoozes(snoozed, now))
        assertEquals(listOf(CareKind.INSPECTION, CareKind.SERVICE), remaining.map { it.kind })
        assertEquals("7일이 지나면 다시 물어봄", 3, careDueAlerts(care, 51_300.0, today, activeCareSnoozes(snoozed, now + CARE_SNOOZE_DAYS * day + 1)).size)
    }

    @Test fun aSnoozeIsTiedToTheDeadlineAndItsUrgency() {
        val soon = careAlerts(care, 51_300.0, today).first { it.kind == CareKind.INSPECTION }
        val overdue = careAlerts(care, 51_300.0, LocalDate.of(2026, 11, 25)).first { it.kind == CareKind.INSPECTION }
        assertNotEquals("곧 만기에서 기한 지남으로 넘어가면 다시 물어봄", careSnoozeKey(soon, care), careSnoozeKey(overdue, care))
        val moved = care.copy(inspectionDue = "2027-11-20")
        val later = careAlerts(moved, 51_300.0, today).first { it.kind == CareKind.INSPECTION }
        assertNotEquals("날짜를 바꾸면 새 기한", careSnoozeKey(soon, care), careSnoozeKey(later, moved))
        val service = careAlerts(care, 51_300.0, today).first { it.kind == CareKind.SERVICE }
        val afterService = care.copy(lastServiceKm = 51_300.0)
        assertTrue("정비 후에는 주기가 새로 시작됨", careAlerts(afterService, 51_300.0, today).none { it.kind == CareKind.SERVICE && it.level != CareLevel.INFO })
        assertTrue(careSnoozeKey(service, care).startsWith("SERVICE:42000.0+10000"))
    }

    @Test fun storedSnoozesExpireAndReplaceThemselves() {
        val raw = setOf("A:x:SOON=${now + day}", "B:y:SOON=${now - 1}", "broken", "=5", "C:z:SOON=notanumber")
        assertEquals(setOf("A:x:SOON"), activeCareSnoozes(raw, now))
        val renewed = withCareSnooze(raw, "A:x:SOON", now)
        assertEquals("만료된 항목은 버리고 같은 키는 하나만", 1, renewed.size)
        assertEquals(setOf("A:x:SOON"), activeCareSnoozes(renewed, now + 6 * day))
        assertTrue(activeCareSnoozes(renewed, now + 8 * day).isEmpty())
    }

    @Test fun serviceProgressIsTheOdometerBetweenServices() {
        assertEquals(0.93f, serviceProgress(care, 51_300.0)!!, 0.001f)
        assertEquals(1f, serviceProgress(care, 60_000.0)!!, 0f)
        assertEquals(0f, serviceProgress(care, 41_000.0)!!, 0f)
        assertNull(serviceProgress(care, null))
        assertNull(serviceProgress(care.copy(serviceIntervalKm = null), 51_300.0))
        assertNull(serviceProgress(care.copy(lastServiceKm = null), 51_300.0))
    }

    @Test fun dateFieldsFormatWhileTypingAndExplainMistakesInline() {
        assertEquals("2026", typeIsoDate("2026"))
        assertEquals("2026-11", typeIsoDate("202611"))
        assertEquals("2026-11-24", typeIsoDate("20261124"))
        assertEquals("2026-11-24", typeIsoDate("2026.11.24"))
        assertEquals("2026-11-24", typeIsoDate("2026/11/24abc"))
        assertEquals("2026-11-2", typeIsoDate("2026-11-2"))
        assertNull(careDateError(""))
        assertNull(careDateError("2026-11-24"))
        assertEquals("2026-11-24처럼 연-월-일로 입력해 주세요", careDateError("2026-11"))
        assertEquals("2026-11-24처럼 연-월-일로 입력해 주세요", careDateError("2026-13-01"))
        assertEquals("D-23 · 11월 24일", careDateHint("2026-11-24", today))
        assertEquals("오늘", careDateHint("2026-11-01", today))
        assertEquals("7일 지남", careDateHint("2026-10-25", today))
        assertNull(careDateHint("11/24", today))
        assertNull(careIntervalError("")); assertNull(careIntervalError("10000"))
        assertEquals("500~100,000 km 사이로 입력해 주세요", careIntervalError("100"))
        assertEquals("숫자만 입력해 주세요", careIntervalError("만"))
        assertNull(careKmError("51300")); assertEquals("계기판 값을 확인해 주세요", careKmError("99999999"))
    }

    @Test fun theOdometerUsedForCareIsTheLiveOneOnlyWhileFresh() {
        val now = 100_000L
        val live = DriveSample(now - 30_000, GearState.PARK, 51_300.0)
        val state = TripLogState(live = live, signal = SignalStatus.READY, data = TripLogData(vehicle = VehicleCard(odometerKm = 51_000.0)))
        assertEquals(51_300.0 to true, careOdometer(state, now))
        assertEquals("오래된 신호는 마지막 수신값으로", 51_000.0 to false, careOdometer(state, now + 20_000))
        assertEquals(null to false, careOdometer(state.copy(live = null, data = TripLogData()), now))
    }

    @Test fun theOdometerLineNamesTheValueTheButtonWouldPutIn() {
        assertEquals("계기판 값은 차량 신호를 받으면 넣을 수 있어요", careOdometerLine(null, false))
        assertEquals("지금 계기판 51,300 km", careOdometerLine(51_300.0, true))
        assertEquals("마지막 수신 계기판 51,300 km", careOdometerLine(51_300.0, false))
        assertEquals("반올림해 km 단위", "지금 계기판 51,301 km", careOdometerLine(51_300.6, true))
    }

    @Test fun doneButtonsSayWhatTheyWrite() {
        CareKind.values().forEach { kind ->
            assertTrue(careDoneLabel(kind).endsWith("완료"))
            val (line, spoken) = careTaskNote(kind)
            assertTrue(line, line.startsWith("완료하면"))
            assertOneLine(line, HOME_CARD_LINE_UNITS)
            assertTrue("읽어 주는 말이 더 김: $kind", spoken.length > line.length)
            assertTrue("2.12의 안내를 그대로 읽어 줌", spoken.contains("완료를 누르면"))
        }
        assertEquals("완료하면 검사 기록을 남기고 만료일을 지워요", careTaskNote(CareKind.INSPECTION).first)
        assertTrue(careTaskNote(CareKind.INSPECTION).second.contains("과태료"))
    }

    @Test fun aSnoozeCanBeTakenBack() {
        val now = 1_000_000L
        val key = "INSPECTION:2026-11-20:SOON"
        val other = "INSURANCE:2026-11-05:OVERDUE"
        val raw = withCareSnooze(withCareSnooze(emptySet(), key, now), other, now)
        assertEquals(setOf(key, other), activeCareSnoozes(raw, now))
        val back = withoutCareSnooze(raw, key, now)
        assertEquals("실행 취소: 그 기한만 다시 알림", setOf(other), activeCareSnoozes(back, now))
        assertEquals("만료된 항목도 함께 정리", emptySet<String>(), withoutCareSnooze(raw, key, now + (CARE_SNOOZE_DAYS + 1) * 86_400_000L))
        assertEquals("없는 키는 그대로", activeCareSnoozes(raw, now), activeCareSnoozes(withoutCareSnooze(raw, "SERVICE:x", now), now))
    }

    @Test fun settingsFieldsSitInTwoColumnsOnlyOnAWideWindowAtANormalFontScale() {
        assertEquals(2, careFieldColumns(carLayout(1680.dp), 1f))
        assertEquals(2, careFieldColumns(carLayout(1200.dp), 1.15f))
        assertEquals("1.3배 글자에서는 항상 한 열", 1, careFieldColumns(carLayout(1680.dp), 1.3f))
        assertEquals("MEDIUM 이하는 한 열", 1, careFieldColumns(carLayout(1000.dp), 1f))
        assertEquals(1, careFieldColumns(carLayout(700.dp), 1f))
        assertEquals("태블릿 폭의 휴대폰도 두 열", 2, careFieldColumns(phoneLayout(900.dp), 1f))
        assertEquals(1, careFieldColumns(phoneLayout(400.dp), 1f))
    }
}
