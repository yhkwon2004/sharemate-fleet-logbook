package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * 홈's answers ([ParkedStackAnswers]) as the attention queue reads them (2.13, [attentionQueue]): what the slot shows, and
 * when it is empty, so the home leaves the card out and the sky takes the room.
 */
class HomeCardsTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, 9, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private val end = at(10)
    private val auto = Trip(id("t"), at(9, 30), end, 100.0, 110.0, destination = "강남 오피스", purpose = TripPurpose.BUSINESS, purposeSource = EditSource.AUTO)
    private val data = TripLogData(trips = listOf(auto))

    /** The queue as 홈 builds it from [answers] at [now], with nothing else waiting but [tasks]. */
    private fun queue(answers: ParkedStackAnswers, tasks: List<ParkedTask> = emptyList(), now: Long = end + 60_000, source: TripLogData = data, last: String? = auto.id) =
        attentionQueue(tasks, SignalStatus.READY, answers.finishedId(source, last, now), answers.reopenedId(source, last), 0, closeReminder = false, chargeWaiting = false)

    @Test fun anEmptyQueueDrawsNoCardSoTheHomeLeavesItOut() {
        assertTrue(attentionQueue(emptyList(), SignalStatus.READY, null, null, 0, closeReminder = false, chargeWaiting = false).isEmpty())
        assertEquals("할 일이 있으면 그 카드", listOf(AttentionItem(AttentionKind.PURPOSE)),
            attentionQueue(listOf(ParkedTask(TaskKind.PURPOSE, 1)), SignalStatus.READY, null, null, 0, closeReminder = false, chargeWaiting = false))
    }

    @Test fun theFinishedTripCardIsShownForItsMomentAndGoneOnceAnsweredOrPast() {
        assertEquals("방금 끝난 자동 분류 운행", listOf(AttentionItem(AttentionKind.FINISHED, id = auto.id)), queue(ParkedStackAnswers()))
        assertTrue("확인하면 바로 빔", queue(ParkedStackAnswers(dismissed = auto.id)).isEmpty())
        assertTrue("2분이 지나면 빈 큐", queue(ParkedStackAnswers(), now = end + 5 * 60_000).isEmpty())
        assertEquals("사고가 기다리면 그쪽이 먼저, 방금 운행은 버리지 않고 뒤로", listOf(AttentionKind.INCIDENT, AttentionKind.FINISHED),
            queue(ParkedStackAnswers(), listOf(ParkedTask(TaskKind.INCIDENT, 1))).map { it.kind })
        assertEquals("카드·충전 질문 뒤로", listOf(AttentionKind.CARD_REVIEW, AttentionKind.CHARGE, AttentionKind.FINISHED),
            queue(ParkedStackAnswers(), listOf(ParkedTask(TaskKind.CARD_REVIEW, 2), ParkedTask(TaskKind.CHARGE, 1))).map { it.kind })
        assertEquals("서류 일은 방금 운행 뒤로", listOf(AttentionKind.FINISHED, AttentionKind.SETUP),
            queue(ParkedStackAnswers(), listOf(ParkedTask(TaskKind.SETUP, 1))).map { it.kind })
    }

    @Test fun aPurposeChangeOpenedFromTheCardStaysUntilAnsweredOrANewerTripFinishes() {
        val reopened = ParkedStackAnswers(dismissed = auto.id, reclassifyId = auto.id)
        assertEquals("카드의 2분이 지나도 열어 둔 질문은 남음", listOf(AttentionItem(AttentionKind.RECLASSIFY, id = auto.id)),
            queue(reopened, now = end + 30 * 60_000))
        assertEquals("열린 사고가 다시 연 질문보다 먼저", listOf(AttentionKind.INCIDENT, AttentionKind.RECLASSIFY),
            queue(reopened, listOf(ParkedTask(TaskKind.INCIDENT, 1))).map { it.kind })
        val newer = Trip(id("n"), at(11), at(11, 20), 110.0, 120.0, purpose = TripPurpose.BUSINESS, purposeSource = EditSource.AUTO)
        assertEquals("더 새 운행이 끝나면 그 운행의 카드로", listOf(AttentionItem(AttentionKind.FINISHED, id = newer.id)),
            queue(reopened, now = at(11, 21), source = data.copy(trips = listOf(auto, newer)), last = newer.id))
        assertTrue("기록 중인 운행은 묻지 않음", queue(reopened, source = data.copy(trips = listOf(auto.copy(endAt = null)))).isEmpty())
    }

    @Test fun theAnswersTheHomeHoldsDriveTheSameQueue() {
        val answers = ParkedStackAnswers()
        assertEquals(listOf(AttentionKind.FINISHED), queue(answers).map { it.kind })
        answers.answer(auto.id)
        assertTrue("확인", queue(answers).isEmpty())
        assertNull(answers.reclassifyId)
        answers.answer(auto.id, reclassify = true)
        assertEquals("다른 목적", listOf(AttentionItem(AttentionKind.RECLASSIFY, id = auto.id)), queue(answers))
        assertEquals("같은 운행을 두 번 확인해도 한 번만 기록", auto.id, answers.dismissed)
        answers.reclassifyId = null
        assertTrue("그대로 두기", queue(answers).isEmpty())
    }
}
