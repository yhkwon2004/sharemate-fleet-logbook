package ai.sharemate.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** Pure parts of the car shell: the header pill's line and tone, the log's filter labels, the settings form's columns. */
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

    @Test fun theTaskStackIsComposedOnlyWhileItCouldShowSomething() {
        val end = at(11, 4, 10)
        val auto = Trip(id("t"), at(11, 4, 9), end, 100.0, 110.0, purpose = TripPurpose.BUSINESS, purposeSource = EditSource.AUTO)
        val data = TripLogData(trips = listOf(auto))
        assertTrue("할 일이 있으면 항상", taskStackHasContent(listOf(ParkedTask(TaskKind.PURPOSE, 1)), TripLogData(), null, end))
        assertTrue("방금 끝난 자동 분류 운행은 카드와 목적 선택을 위해 유지", taskStackHasContent(emptyList(), data, auto.id, end + 60_000))
        assertFalse("유예 시간이 지나면 해제", taskStackHasContent(emptyList(), data, auto.id, end + 11 * 60_000))
        assertFalse("직접 분류한 운행은 카드가 없음", taskStackHasContent(emptyList(), data.copy(trips = listOf(auto.copy(purposeSource = EditSource.DRIVER))), auto.id, end + 60_000))
        assertFalse("자동 완성이 꺼져 있으면 카드가 없음", taskStackHasContent(emptyList(), data.copy(profile = FleetProfile(autopilot = false)), auto.id, end + 60_000))
        assertFalse("아무것도 없으면 비움", taskStackHasContent(emptyList(), data, null, end))
    }

    @Test fun logFilterLabelsCarryTheCountsAndShortenWhereTheRowIsNarrow() {
        assertEquals(listOf("전체 9", "점검 필요 7", "자동 반영 0"), logFilterLabels(9, 7, 0, wide = true))
        assertEquals(listOf("전체 9", "점검 필요 7", "자동 0"), logFilterLabels(9, 7, 0, wide = false))
    }

    @Test fun settingsFormsPairFieldsOnlyOnWideWindowsAtTheNormalFont() {
        assertEquals(2, settingsFieldColumns(carLayout(1680.dp), 1f))
        assertEquals("EXPANDED도 두 열", 2, settingsFieldColumns(carLayout(1200.dp), 1.15f))
        assertEquals("1.3배 글꼴은 항상 한 열", 1, settingsFieldColumns(carLayout(1680.dp), 1.3f))
        assertEquals("MEDIUM 이하는 한 열", 1, settingsFieldColumns(carLayout(1000.dp), 1f))
    }
}
