package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** The home task stack's pure choice of card: what it shows, and whether the home composes it at all. */
class HomeCardsTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, 9, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private val end = at(10)
    private val auto = Trip(id("t"), at(9, 30), end, 100.0, 110.0, destination = "강남 오피스", purpose = TripPurpose.BUSINESS, purposeSource = EditSource.AUTO)
    private val data = TripLogData(trips = listOf(auto))

    @Test fun anEmptyStackDrawsNoCardSoTheHomeLeavesItOut() {
        val key = parkedStackKey(emptyList(), data, null, end, emptySet(), null)
        assertEquals(StackKey(null, null, null), key)
        assertFalse(key.showsCard)
        assertTrue("할 일이 있으면 그 카드", parkedStackKey(listOf(ParkedTask(TaskKind.PURPOSE, 1)), TripLogData(), null, end, emptySet(), null).showsCard)
    }

    @Test fun theFinishedTripCardIsShownForItsMomentAndGoneOnceAnsweredOrPast() {
        assertEquals("방금 끝난 자동 분류 운행", StackKey(null, auto.id, null), parkedStackKey(emptyList(), data, auto.id, end + 60_000, emptySet(), null))
        assertFalse("확인하면 바로 비고, 남은 시간 동안 빈 간격을 두지 않음",
            parkedStackKey(emptyList(), data, auto.id, end + 60_000, setOf(auto.id), null).showsCard)
        assertFalse("2분이 지나면 빈 스택", parkedStackKey(emptyList(), data, auto.id, end + 5 * 60_000, emptySet(), null).showsCard)
        assertEquals("사고가 기다리면 그쪽이 먼저", StackKey(TaskKind.INCIDENT, null, null),
            parkedStackKey(listOf(ParkedTask(TaskKind.INCIDENT, 1)), data, auto.id, end + 60_000, emptySet(), null))
        assertEquals("서류 일은 방금 운행 뒤로", StackKey(null, auto.id, null),
            parkedStackKey(listOf(ParkedTask(TaskKind.SETUP, 1)), data, auto.id, end + 60_000, emptySet(), null))
    }

    @Test fun aPurposeChangeOpenedFromTheCardStaysUntilAnsweredOrANewerTripFinishes() {
        val key = parkedStackKey(emptyList(), data, auto.id, end + 30 * 60_000, setOf(auto.id), auto.id)
        assertEquals("카드의 2분이 지나도 열어 둔 질문은 남음", StackKey(null, null, auto.id), key)
        assertTrue(key.showsCard)
        val newer = Trip(id("n"), at(11), at(11, 20), 110.0, 120.0, purpose = TripPurpose.BUSINESS, purposeSource = EditSource.AUTO)
        assertEquals("더 새 운행이 끝나면 그 운행의 카드로", StackKey(null, newer.id, null),
            parkedStackKey(emptyList(), data.copy(trips = listOf(auto, newer)), newer.id, at(11, 21), setOf(auto.id), auto.id))
        assertFalse("기록 중인 운행은 묻지 않음",
            parkedStackKey(emptyList(), data.copy(trips = listOf(auto.copy(endAt = null))), auto.id, end + 60_000, setOf(auto.id), auto.id).showsCard)
    }

    @Test fun theAnswersTheHomeHoldsDriveTheSameKey() {
        val answers = ParkedStackAnswers()
        assertEquals(StackKey(null, auto.id, null), answers.key(emptyList(), data, auto.id, end + 60_000))
        answers.answer(auto.id)
        assertFalse("확인", answers.key(emptyList(), data, auto.id, end + 60_000).showsCard)
        assertNull(answers.reclassifyId)
        answers.answer(auto.id, reclassify = true)
        assertEquals("다른 목적", StackKey(null, null, auto.id), answers.key(emptyList(), data, auto.id, end + 60_000))
        assertEquals("같은 운행을 두 번 확인해도 한 번만 기록", auto.id, answers.dismissed)
        answers.reclassifyId = null
        assertFalse("그대로 두기", answers.key(emptyList(), data, auto.id, end + 60_000).showsCard)
    }
}
