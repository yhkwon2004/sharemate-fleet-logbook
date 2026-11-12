package ai.sharemate.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words of the car's overlays (docs/design/DESIGN_2_13_SPEC.md §8, §10): every line one car line within its budget, no
 * final period, each spoken form longer than its line; the follow-up bar's messages within the bar; the bar in the other
 * scene; and the answer a classification gives.
 */
class OverlayCopyTest {
    @Test fun dialogLinesFitTheDialog() {
        listOf(OverlayCopy.SAVED_LINE, OverlayCopy.NO_MORE_PERMISSIONS).forEach { assertOneLine(it, DIALOG_LINE_UNITS) }
        // Titles are short: at most 8 syllables, never a sentence.
        listOf(OverlayCopy.ERROR_TITLE, OverlayCopy.SAVED_TITLE, OverlayCopy.PICKER_NOW_TITLE, OverlayCopy.PICKER_TRIP_TITLE, OverlayCopy.TUTORIAL_TITLE,
            OverlayCopy.TUTORIAL_DRIVER, OverlayCopy.TUTORIAL_VOICE).forEach { title ->
            assertTrue("$title: 8음절 이하", title.count { it in '가'..'힣' } <= 8)
            assertFalse(title.endsWith("."))
        }
    }

    @Test fun pickerSheetAndWalkthroughLinesFitACard() {
        val lines = listOf(OverlayCopy.PICKER_RECORDING, OverlayCopy.PICKER_NEXT, OverlayCopy.PICKER_TRIP_NOTE, OverlayCopy.PICKER_EMPTY,
            OverlayCopy.PICKER_NO_MATCH, OverlayCopy.ASSISTANT_ASK_LINE, OverlayCopy.ASSISTANT_HOW, OverlayCopy.assistantConfirm(cost = true),
            OverlayCopy.assistantConfirm(cost = false), OverlayCopy.ASSISTANT_UNDONE, OverlayCopy.TUTORIAL_DATA, OverlayCopy.TUTORIAL_DRIVER_LINE,
            OverlayCopy.TUTORIAL_RECORDING_LINE, OverlayCopy.TUTORIAL_PURPOSE_LINE, OverlayCopy.TUTORIAL_EXPENSE_NOTE, OverlayCopy.TUTORIAL_VOICE_NOTE,
            OverlayCopy.tutorialRate(200, 2_480)) + OverlayCopy.TUTORIAL_STORY
        lines.forEach { assertOneLine(it, CARD_LINE_UNITS) }
        assertEquals("km당 200원 · 정산 참고 2,480원", OverlayCopy.tutorialRate(200, 2_480))
        assertEquals("조사가 맞음 (2.12는 추가을)", "맞으면 추가를 눌러 주세요 · 바로 되돌릴 수 있어요", OverlayCopy.assistantConfirm(cost = true))
        assertEquals("맞으면 기록을 눌러 주세요 · 바로 되돌릴 수 있어요", OverlayCopy.assistantConfirm(cost = false))
        // What an ⓘ row unfolds may wrap (it is the long form), but it is still written without a final period.
        val more = OverlayCopy.ASSISTANT_HOW_MORE + OverlayCopy.TUTORIAL_STORY_MORE.flatten() + OverlayCopy.TUTORIAL_EXPENSE_MORE + OverlayCopy.TUTORIAL_VOICE_MORE
        more.forEach { assertFalse("마침표로 끝나지 않음: $it", it.trimEnd().endsWith(".")) }
        assertEquals("여섯 단계", 6, OverlayCopy.TUTORIAL_STORY.size)
        assertEquals(6, OverlayCopy.TUTORIAL_STORY_MORE.size)
        // No car copy writes a rate as 원/km or a sum with ₩.
        (lines + more).forEach { assertFalse(it, it.contains("원/km") || it.contains('₩')) }
    }

    @Test fun everySpokenFormSaysMoreThanItsLine() {
        listOf(OverlayCopy.PICKER_TRIP_NOTE to OverlayCopy.SPOKEN_PICKER_TRIP_NOTE,
            OverlayCopy.TUTORIAL_DATA to OverlayCopy.SPOKEN_TUTORIAL_DATA,
            OverlayCopy.TUTORIAL_STORY[4] to OverlayCopy.SPOKEN_TUTORIAL_OVERVIEW,
            OverlayCopy.assistantConfirm(cost = true) to OverlayCopy.spokenAssistantConfirm(cost = true),
            OverlayCopy.assistantConfirm(cost = false) to OverlayCopy.spokenAssistantConfirm(cost = false),
            OverlayCopy.tutorialStep(3) to OverlayCopy.spokenTutorialStep(3),
            "닫기" to OverlayCopy.SPOKEN_TUTORIAL_CLOSE,
        ).forEach { (line, spoken) -> assertTrue("$spoken > $line", spoken.length > line.length) }
        assertEquals("3 / 6", OverlayCopy.tutorialStep(3))
        assertEquals("6단계 중 3단계", OverlayCopy.spokenTutorialStep(3))
        // A pill's spoken form always contains its label.
        assertTrue(OverlayCopy.SPOKEN_TUTORIAL_CLOSE.contains("닫기"))
    }

    @Test fun followUpsFitTheBar() {
        val kim = Driver("kj", "Sales", "Kim Jiwoo")
        val lee = Driver("lm", "Finance", "Lee Minho")
        listOf(driverPickMessage(kim, lee, forTrip = true), driverPickMessage(kim, null, forTrip = true), driverPickMessage(kim, lee, forTrip = false),
            driverPickMessage(kim, null, forTrip = false)).forEach { assertOneLine(it!!, FOLLOW_UP_UNITS) }
        TripPurpose.entries.filter { it != TripPurpose.UNCLASSIFIED }.forEach { purpose ->
            for (others in listOf(0, 1, 3, 12, 99)) {
                val item = classifyFollowUp(purpose, others, {}, {})
                assertOneLine(item.message, if (item.second != null) FOLLOW_UP_TWO_UNITS else FOLLOW_UP_UNITS)
            }
            assertOneLine(classifyManyFollowUp(purpose, 99) {}.message, FOLLOW_UP_UNITS)
        }
        assertOneLine(OverlayCopy.ASSISTANT_CHANGED, FOLLOW_UP_UNITS)
        // The app's own estimate is the copy rules' one, so the assistant's fallback agrees with these budgets.
        listOf("운행 비서: 방금 운행을 업무로 기록했어요", "km당 200원 · 정산 참고 2,480원", "Kim Jiwoo").forEach { assertEquals(oneLineUnits(it), copyUnits(it), 1e-9) }
        assertTrue(followUpFits("가".repeat(32), actions = 1))
        assertFalse(followUpFits("가".repeat(33), actions = 1))
        assertTrue(followUpFits("가".repeat(24), actions = 2))
        assertFalse(followUpFits("가".repeat(25), actions = 2))
    }

    @Test fun theBarWearsTheOtherScene() {
        assertSame("낮에는 밤의 남색 카드", NightSceneColors, followUpScene(isDay = true))
        assertSame("밤에는 낮의 흰 카드", DaySceneColors, followUpScene(isDay = false))
    }

    @Test fun aPurposeIsAlwaysUndoableAndTheRestOfTheDayIsOneTapAway() {
        val alone = classifyFollowUp(TripPurpose.BUSINESS, 0, {}, {})
        assertEquals("일반 업무로 기록했어요", alone.message)
        assertEquals("실행 취소", alone.action)
        assertNotNull(alone.perform)
        assertNull("다른 운행이 없으면 하나만", alone.second)

        var undone = false
        var applied = false
        val three = classifyFollowUp(TripPurpose.COMMUTE, 3, undo = { undone = true }, sameForOthers = { applied = true })
        assertEquals("출·퇴근으로 기록 · 같은 날 미분류 3건", three.message)
        assertEquals("되돌리기는 늘 있음", "실행 취소", three.action)
        assertEquals("3건도 같게", three.second?.label)
        runBlocking { three.perform!!.invoke() }
        assertTrue(undone && !applied)
        runBlocking { three.second!!.perform() }
        assertTrue(applied)

        var reverted = false
        val bulk = classifyManyFollowUp(TripPurpose.BUSINESS, 3) { reverted = true }
        assertEquals("3건을 일반 업무로 기록했어요", bulk.message)
        assertEquals("실행 취소", bulk.action)
        assertNull(bulk.second)
        runBlocking { bulk.perform!!.invoke() }
        assertTrue(reverted)
    }
}
