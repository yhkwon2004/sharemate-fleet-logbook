package ai.sharemate.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 홈 without scrolling (2.13, docs/design/DESIGN_2_13_SPEC.md §3): every card kind, with and without the pager and the demo
 * strip, fits the full Pleos window (1209 dp: the 84-dp header, the page slot's 28 dp of padding and the 1097-dp page); the
 * 지금 column fits beside it; the queue keeps the order the moment demands; and the driver's page follows the queue.
 */
class HomeFrameTest {
    @Test fun everyCardFitsTheFullWindowWithThePagerAndTheDemoStrip() {
        val kinds = AttentionKind.entries.map { it as AttentionKind? } + null
        kinds.forEach { kind ->
            listOf(false, true).forEach { pager ->
                listOf(false, true).forEach { demo ->
                    val frame = homeFrame(kind, pager, demo)
                    assertTrue("$kind pager=$pager demo=$demo: ${frame.total}", frame.total + HOME_HEADER_ROW + HOME_PAGE_PADDING <= HOME_WINDOW_FULL)
                    if (kind != null) assertTrue("$kind ${attentionCardMax(kind, pager)}", attentionCardMax(kind, pager) <= ATTENTION_CARD_CAP)
                }
            }
        }
        assertEquals("빈 홈", 384.dp, homeFrame(null, pager = false, demo = false).total)
        assertEquals("가장 긴 화면: 시연 중 사고 카드와 넘김", 981.dp, homeFrame(AttentionKind.INCIDENT, pager = true, demo = true).total)
        assertEquals("1209 − 84 − 28", 1097.dp, HOME_WINDOW_FULL - HOME_HEADER_ROW - HOME_PAGE_PADDING)
    }

    @Test fun theCardMaximaAreTheSpecsAnatomy() {
        val withPager = mapOf(AttentionKind.INCIDENT to 477, AttentionKind.PERMISSION to 242, AttentionKind.RECLASSIFY to 375, AttentionKind.PURPOSE to 375,
            AttentionKind.CARD_REVIEW to 287, AttentionKind.CHARGE to 414, AttentionKind.FINISHED to 264, AttentionKind.MATCHED to 264, AttentionKind.SETUP to 267,
            AttentionKind.PAIRING to 346, AttentionKind.MONTH_CLOSE to 264, AttentionKind.CARE to 242, AttentionKind.CLOSE_REMINDER to 219,
            AttentionKind.CHARGE_WAITING to 304)
        assertEquals(AttentionKind.entries.toSet(), withPager.keys)
        withPager.forEach { (kind, max) ->
            assertEquals("$kind", max.dp, attentionCardMax(kind, pager = true))
            assertEquals("$kind 넘김 없이 24 dp 작음", (max - 24).dp, attentionCardMax(kind, pager = false))
        }
        // The charge card is 390 dp; its money field's range error adds 24.
        assertEquals(390.dp, attentionCardMax(AttentionKind.CHARGE, pager = true) - ATTENTION_FIELD_ERROR)
    }

    @Test fun theNowColumnFitsBesideIt() {
        // 사용자 with a two-line question, 예약, 오늘 운행 and 이달 운행일지, three 16-dp gaps and 8 at the bottom.
        val column = 323.dp + 84.dp + 199.dp + 172.dp + 56.dp
        assertEquals(834.dp, column)
        assertTrue(column <= HOME_WINDOW_FULL - HOME_HEADER_ROW - HOME_PAGE_PADDING)
    }

    @Test fun theQueueKeepsTheOrderTheMomentDemands() {
        val tasks = listOf(ParkedTask(TaskKind.INCIDENT, 1), ParkedTask(TaskKind.CARD_REVIEW, 2), ParkedTask(TaskKind.CHARGE, 1), ParkedTask(TaskKind.PURPOSE, 3),
            ParkedTask(TaskKind.SETUP, 1), ParkedTask(TaskKind.PAIRING, 1), ParkedTask(TaskKind.MONTH_CLOSE, 1), ParkedTask(TaskKind.CARE, 2))
        val queue = attentionQueue(tasks, SignalStatus.DENIED, finishedId = "f", reclassifyId = "r", matched = 3, closeReminder = true, chargeWaiting = true)
        assertEquals(listOf(AttentionKind.INCIDENT, AttentionKind.PERMISSION, AttentionKind.RECLASSIFY, AttentionKind.CARD_REVIEW, AttentionKind.CHARGE,
            AttentionKind.FINISHED, AttentionKind.MATCHED, AttentionKind.PURPOSE, AttentionKind.SETUP, AttentionKind.PAIRING, AttentionKind.MONTH_CLOSE,
            AttentionKind.CARE, AttentionKind.CHARGE_WAITING), queue.map { it.kind })
        assertFalse("월 마감 카드 옆에 마감 안내는 없음", queue.any { it.kind == AttentionKind.CLOSE_REMINDER })
        assertEquals("개수는 할 일 목록 그대로", 2, queue.first { it.kind == AttentionKind.CARD_REVIEW }.count)
        assertEquals("카드 반영은 묶음의 건수", 3, queue.first { it.kind == AttentionKind.MATCHED }.count)
        assertEquals("운행 카드는 그 운행", "r", queue.first { it.kind == AttentionKind.RECLASSIFY }.id)
        val calm = attentionQueue(listOf(ParkedTask(TaskKind.CARE, 1)), SignalStatus.READY, null, null, 0, closeReminder = true, chargeWaiting = false)
        assertEquals("월 마감 할 일이 없으면 이달 안내", listOf(AttentionKind.CARE, AttentionKind.CLOSE_REMINDER), calm.map { it.kind })
        val finished = attentionQueue(listOf(ParkedTask(TaskKind.CARD_REVIEW, 1)), SignalStatus.READY, "f", null, 0, false, false)
        assertEquals("방금 운행은 카드 확인 뒤로", listOf(AttentionKind.CARD_REVIEW, AttentionKind.FINISHED), finished.map { it.kind })
        assertTrue("권한은 거부일 때만", attentionQueue(emptyList(), SignalStatus.ERROR, null, null, 0, false, false).isEmpty())
        assertTrue("빈 할 일은 건너뜀", attentionQueue(listOf(ParkedTask(TaskKind.PURPOSE, 0)), SignalStatus.READY, null, null, 0, false, false).isEmpty())
    }

    @Test fun theSharedKindsKeepTheTaskListsWords() {
        val shared = mapOf(AttentionKind.INCIDENT to TaskKind.INCIDENT, AttentionKind.CARD_REVIEW to TaskKind.CARD_REVIEW, AttentionKind.CHARGE to TaskKind.CHARGE,
            AttentionKind.PURPOSE to TaskKind.PURPOSE, AttentionKind.SETUP to TaskKind.SETUP, AttentionKind.PAIRING to TaskKind.PAIRING,
            AttentionKind.MONTH_CLOSE to TaskKind.MONTH_CLOSE, AttentionKind.CARE to TaskKind.CARE)
        shared.forEach { (kind, task) -> assertEquals("주행 화면의 마지막 줄과 같은 말", task.label, kind.label) }
        assertEquals(listOf("차량 권한", "목적 바꾸기", "방금 운행", "카드 반영", "월 마감", "충전 비용"),
            listOf(AttentionKind.PERMISSION, AttentionKind.RECLASSIFY, AttentionKind.FINISHED, AttentionKind.MATCHED, AttentionKind.CLOSE_REMINDER,
                AttentionKind.CHARGE_WAITING).map { it.label })
    }

    @Test fun theDriversPageHoldsUntilTheHeadChanges() {
        val incident = AttentionItem(AttentionKind.INCIDENT)
        val review = AttentionItem(AttentionKind.CARD_REVIEW, 2)
        val care = AttentionItem(AttentionKind.CARE)
        val queue = listOf(incident, review, care)
        assertEquals("넘기지 않았으면 맨 앞", 0, attentionIndex(queue, null))
        assertEquals("넘긴 자리", 2, attentionIndex(queue, AttentionPin(care, incident)))
        assertEquals("건수가 바뀌어도 같은 종류에 머묾", 1, attentionIndex(listOf(incident, review.copy(count = 1), care), AttentionPin(review, incident)))
        assertEquals("넘긴 항목이 사라지면 맨 앞", 0, attentionIndex(listOf(incident, review), AttentionPin(care, incident)))
        assertEquals("맨 앞이 바뀌면 맨 앞", 0, attentionIndex(listOf(review, care), AttentionPin(care, incident)))
        assertEquals("더 급한 일이 생겨도 맨 앞", 0, attentionIndex(listOf(AttentionItem(AttentionKind.PERMISSION), incident, review, care), AttentionPin(care, incident)))
        assertEquals(0, attentionIndex(emptyList(), AttentionPin(care, incident)))
        // 다른 목적 on the 방금 운행 card: the question for the same trip takes the pinned card's place, and the page follows it.
        val finished = AttentionItem(AttentionKind.FINISHED, id = "t")
        val before = listOf(incident, finished, care)
        val after = listOf(incident, AttentionItem(AttentionKind.RECLASSIFY, id = "t"), care)
        assertEquals(1, attentionIndex(before, AttentionPin(finished, incident)))
        assertEquals("방금 운행은 같은 운행의 목적 바꾸기로", 1, attentionIndex(after, AttentionPin(finished, incident)))
        assertEquals("다른 운행의 질문은 따라가지 않음", 0,
            attentionIndex(listOf(incident, AttentionItem(AttentionKind.RECLASSIFY, id = "u"), care), AttentionPin(finished, incident)))
    }

    @Test fun captionsSitBesideTheLabelFrom196Dp() {
        assertTrue("FULL의 204 dp", incidentCaptionBeside(204.dp, 1f))
        assertTrue(incidentCaptionBeside(196.dp, 1f))
        assertFalse(incidentCaptionBeside(195.dp, 1f))
        assertFalse("1.3배 글꼴은 255 dp부터", incidentCaptionBeside(254.dp, 1.3f))
        assertTrue(incidentCaptionBeside(255.dp, 1.3f))
        assertTrue("작은 글꼴은 1배로 셈", incidentCaptionBeside(196.dp, .85f))
    }

    @Test fun theBandIsWhatTheFollowUpBarRidesAbove() {
        assertEquals(200.dp, homeBandHeight(demo = false))
        assertEquals(296.dp, homeBandHeight(demo = true))
        // The sky keeps room for the bar (64 dp and 24 over the band) beside the tallest card.
        val sky = HOME_WINDOW_FULL - HOME_HEADER_ROW - HOME_PAGE_PADDING - homeFrame(AttentionKind.INCIDENT, pager = true, demo = true).total + HOME_SKY_MIN
        assertTrue("$sky", sky >= 88.dp)
    }
}
