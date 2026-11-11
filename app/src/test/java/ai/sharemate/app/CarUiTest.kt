package ai.sharemate.app

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pure parts of the 2.12 car components: how many lines a supporting line may take, when a segment's or a tab's label turns
 * white under the gliding accent, the header clock's date, time and spoken form, and the one-line copy rule's units.
 */
class CarUiTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0, zone: ZoneId = seoul) =
        LocalDateTime.of(year, month, day, hour, minute, second).atZone(zone).toInstant().toEpochMilli()

    @Test fun aCarLineIsOneLineAtTheNormalFontAndTwoAboveIt() {
        assertEquals("작은 글꼴도 한 줄", 1, carLineMaxLines(.85f))
        assertEquals(1, carLineMaxLines(1f))
        assertEquals("1.05×까지 한 줄", 1, carLineMaxLines(1.05f))
        assertEquals("큰 글꼴은 두 줄로 단어를 잃지 않음", 2, carLineMaxLines(1.15f))
        assertEquals(2, carLineMaxLines(1.3f))
    }

    @Test fun aLabelTurnsWhiteOnceTheGlideCoversHalfOfIt() {
        val item = Rect(100f, 0f, 200f, 56f)
        assertTrue("제자리의 선택", glideCovers(item, item))
        assertTrue("정확히 절반", glideCovers(Rect(150f, 0f, 250f, 56f), item))
        assertTrue("왼쪽에서 정확히 절반", glideCovers(Rect(0f, 0f, 150f, 56f), item))
        assertFalse("49 %는 아직", glideCovers(Rect(151f, 0f, 251f, 56f), item))
        assertFalse("왼쪽에서 49 %", glideCovers(Rect(0f, 0f, 149f, 56f), item))
        assertFalse("넓은 표시가 지나가도 겹친 폭으로", glideCovers(Rect(160f, 0f, 400f, 56f), item))
        assertFalse("표시가 아직 없으면", glideCovers(null, item))
        assertFalse("항목을 아직 재지 않았으면", glideCovers(item, null))
    }

    @Test fun theHeaderClockShowsTheDateAndTimeAndSpeaksThemInFull() {
        assertEquals(ClockText("11월 9일 (월)", "22:20", "11월 9일 월요일 22시 20분"), headerClock(at(2026, 11, 9, 22, 20, 41), seoul))
        val morning = headerClock(at(2026, 11, 9, 9, 5), seoul)
        assertEquals("시는 두 자리", "09:05", morning.time)
        assertEquals("읽을 때는 0 없이", "11월 9일 월요일 9시 5분", morning.spoken)
        assertEquals("정각은 분을 읽지 않음", ClockText("1월 1일 (금)", "00:00", "1월 1일 금요일 0시"), headerClock(at(2027, 1, 1, 0, 0, 30), seoul))
        val lastSecond = headerClock(at(2026, 11, 9, 23, 59, 59), seoul)
        val midnight = headerClock(at(2026, 11, 9, 23, 59, 59) + 1_000, seoul)
        assertEquals(ClockText("11월 9일 (월)", "23:59", "11월 9일 월요일 23시 59분"), lastSecond)
        assertEquals("자정에 날짜가 넘어감", ClockText("11월 10일 (화)", "00:00", "11월 10일 화요일 0시"), midnight)
        val instant = at(2026, 11, 9, 22, 20)
        assertEquals("같은 순간, UTC", "13:20", headerClock(instant, ZoneOffset.UTC).time)
        assertEquals("같은 순간, 서울", "22:20", headerClock(instant, seoul).time)
    }

    @Test fun oneLineUnitsWeighHangulSpacesAndEverythingElse() {
        assertEquals(1.8, oneLineUnits("가 a"), 1e-9)
        assertEquals("숫자와 가운뎃점은 .55", 2.75, oneLineUnits("10·28"), 1e-9)
        // The 예약 tile's empty line keeps inside the 지금 column.
        assertEquals(10.75, oneLineUnits("현재 잡힌 예약이 없어요"), 1e-9)
        assertOneLine("현재 잡힌 예약이 없어요", NOW_LINE_UNITS)
    }
}
