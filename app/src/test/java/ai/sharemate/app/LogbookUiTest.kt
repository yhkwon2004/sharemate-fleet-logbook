package ai.sharemate.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** Pure parts of the car shell: the header pill's line and tone, the log's filter labels and empty state, the settings form's columns. */
class LogbookUiTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(month: Int, day: Int, hour: Int) = LocalDateTime.of(2026, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()

    @Test fun statusPillPutsFailuresFirstThenWhatTheCarIsDoing() {
        val ready = TripLogState(loaded = true, signal = SignalStatus.READY)
        assertEquals("자동 기록 대기" to PillTone.QUIET, statusPillInfo(ready, fresh = true, status = "수신 중"))
        assertEquals("저장 실패가 가장 먼저", "기록 저장 확인 필요" to PillTone.NEGATIVE, statusPillInfo(ready.copy(error = "disk"), fresh = true, status = "수신 중"))
        assertEquals("기록 저장 확인 필요" to PillTone.NEGATIVE, statusPillInfo(ready.copy(loadFailed = true), fresh = true, status = "수신 중"))
        assertEquals(SignalStatus.DENIED.label to PillTone.NEGATIVE, statusPillInfo(ready.copy(signal = SignalStatus.DENIED), fresh = true, status = "수신 중"))
        assertEquals("연결 중은 조용한 톤", SignalStatus.STARTING.label to PillTone.QUIET, statusPillInfo(ready.copy(signal = SignalStatus.STARTING), fresh = false, status = "확인 필요"))
        assertEquals("오래된 신호는 대기로 읽히지 않음", "마지막 수신 5분 전" to PillTone.NEGATIVE, statusPillInfo(ready, fresh = false, status = "마지막 수신 5분 전"))
        val recording = ready.copy(signal = SignalStatus.RECORDING, data = TripLogData(open = Trip(id("o"), at(11, 4, 9), null, 100.0, 103.2)))
        assertEquals("기록 중 · 3.2 km" to PillTone.POSITIVE, statusPillInfo(recording, fresh = true, status = "수신 중"))
        assertEquals("기록 중이면 신호가 오래돼도 기록 중", "기록 중 · 3.2 km" to PillTone.POSITIVE, statusPillInfo(recording, fresh = false, status = "확인 필요"))
        assertEquals("시연 데이터" to PillTone.DEMO, statusPillInfo(ready.copy(signal = SignalStatus.DEMO), fresh = true, status = "수신 중"))
    }

    @Test fun theExcelPreviewFillsThePaneUnderTheViewTabsAndNeverDropsUnder480() {
        // FULL car pane, about 950 dp: less the pinned save bar (72 + 16) and the view tabs with the range line (66 + 16 + 16 + 16).
        assertEquals(748f, excelPreviewHeight(950.dp).value, .01f)
        assertEquals("큰 글꼴은 범위 줄이 커진 만큼만 줄어듦", 743.2f, excelPreviewHeight(950.dp, 1.3f).value, .01f)
        assertEquals("짧은 창은 480에서 멈추고 창이 더 스크롤", EXCEL_PREVIEW_MIN_HEIGHT, excelPreviewHeight(400.dp))
        assertEquals(480f, EXCEL_PREVIEW_MIN_HEIGHT.value, .01f)
        assertEquals("큰 글꼴의 최소 높이는 글꼴만큼 커짐", 624f, excelPreviewHeight(400.dp, 1.3f).value, .01f)
        assertEquals("높이가 정해지지 않으면 최소값", EXCEL_PREVIEW_MIN_HEIGHT, excelPreviewHeight(Dp.Infinity))
        assertEquals("저장 바 72 + 위 간격 16", 88f, SAVE_BAR_RESERVE.value, .01f)
    }

    @Test fun logFilterLabelsCarryTheCountsAndShortenWhereTheRowIsNarrow() {
        assertEquals(listOf("전체 9", "점검 필요 7", "자동 반영 0"), logFilterLabels(9, 7, 0, wide = true))
        assertEquals(listOf("전체 9", "점검 필요 7", "자동 0"), logFilterLabels(9, 7, 0, wide = false))
    }

    @Test fun anEmptyLogSaysWhatTheMonthIsMissingAndOffersClearingOnlyWhenSomethingNarrowsIt() {
        val today = LocalDate.of(2026, 11, 9)
        val november = YearMonth.of(2026, 11)
        val plain = logEmptyCopy(0, november, 0, null, today)
        assertEquals("11월 운행 기록이 없어요", plain.title); assertNull(plain.detail)
        assertFalse("전체·전체 직원이면 필터 해제가 없음", plain.clearable)
        val employee = logEmptyCopy(0, november, 0, "영업 · 권", today)
        assertEquals("11월 영업 · 권 님의 운행 기록이 없어요", employee.title); assertTrue(employee.clearable)
        val checked = logEmptyCopy(1, november, 4, null, today)
        assertEquals("11월 점검할 운행이 없어요", checked.title); assertEquals("계기판 누적거리가 끊김 없이 이어져요", checked.detail)
        assertTrue(checked.clearable)
        assertEquals("운행이 없으면 점검 필요도 운행이 없다고 말함", "11월 운행 기록이 없어요", logEmptyCopy(1, november, 0, null, today).title)
        assertEquals("11월 카드 결제가 자동으로 들어간 운행이 없어요", logEmptyCopy(2, november, 3, null, today).title)
        assertEquals("지난해 달은 연도를 붙임", "2025년 12월 운행 기록이 없어요", logEmptyCopy(0, YearMonth.of(2025, 12), 0, null, today).title)
    }

    @Test fun settingsFormsPairFieldsOnlyOnWideWindowsAtTheNormalFont() {
        assertEquals(2, settingsFieldColumns(carLayout(1680.dp), 1f))
        assertEquals("EXPANDED도 두 열", 2, settingsFieldColumns(carLayout(1200.dp), 1.15f))
        assertEquals("1.3배 글꼴은 항상 한 열", 1, settingsFieldColumns(carLayout(1680.dp), 1.3f))
        assertEquals("MEDIUM 이하는 한 열", 1, settingsFieldColumns(carLayout(1000.dp), 1f))
    }
}
