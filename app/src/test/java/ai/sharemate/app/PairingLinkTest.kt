package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * The pairing link of 2.15 (docs/design/DESIGN_2_15.md §6.1): what the car writes, what the phone accepts (strictly), the round
 * trip between them, the length cap, and the order in which the phone tries the addresses it carries (§6.3). The fix round
 * (§9.6): a code past its end gives no link and no countdown, and a scanned link pairs at once only when the camera took it
 * and every address it names is a car's local IPv4 one.
 */
class PairingLinkTest {
    private val key = "499efa1aa0f8445c3832ad7c3b911b20a0d9e04f3d66d03591d84b964802b21e"
    private val good = "sharemate://pair?v=1&e=10.0.2.15:9247&e=10.0.2.16:9247&c=455426&k=$key"

    private fun link(vararg params: String) = PAIRING_LINK_PREFIX + params.joinToString("&")

    @Test fun aGoodLinkGivesItsAddressesInOrderTheCodeAndTheKey() {
        val parsed = parsePairingLink(good)
        assertEquals(PairingLink(listOf("10.0.2.15:9247", "10.0.2.16:9247"), "455426", key), parsed)
        assertEquals("쉼표로 이은 주소", "10.0.2.15:9247,10.0.2.16:9247", parsed!!.endpointText)
    }

    @Test fun goodLinksInEveryAllowedShape() {
        assertEquals("주소 하나", listOf("192.168.0.12:9247"), parsePairingLink(link("v=1", "e=192.168.0.12:9247", "c=000000", "k=$key"))?.endpoints)
        assertEquals("주소 넷", 4, parsePairingLink(link("v=1", "e=1.1.1.1:1", "e=2.2.2.2:2", "e=3.3.3.3:3", "e=4.4.4.4:65535", "c=123456", "k=$key"))?.endpoints?.size)
        assertEquals("호스트 이름, 소문자로", listOf("car-7.fleet.local:9247"), parsePairingLink(link("v=1", "e=Car-7.Fleet.LOCAL:9247", "c=123456", "k=$key"))?.endpoints)
        assertEquals("순서는 상관없음", "123456", parsePairingLink(link("k=$key", "c=123456", "e=10.0.0.1:9247", "v=1"))?.code)
        assertEquals("대문자 키는 소문자로", key, parsePairingLink(link("v=1", "e=10.0.0.1:9247", "c=123456", "k=${key.uppercase()}"))?.fingerprint)
        assertEquals("같은 주소는 한 번", listOf("10.0.0.1:9247"), parsePairingLink(link("v=1", "e=10.0.0.1:9247", "e=10.0.0.1:9247", "c=123456", "k=$key"))?.endpoints)
        assertEquals("255.255.255.255도 IPv4", listOf("255.255.255.255:9247"), parsePairingLink(link("v=1", "e=255.255.255.255:9247", "c=123456", "k=$key"))?.endpoints)
    }

    @Test fun anythingThatIsNotThatLinkIsRefused() {
        val bad = mapOf(
            "빈 글" to "",
            "다른 앱의 주소" to "https://example.com/pair?v=1&e=10.0.0.1:9247&c=123456&k=$key",
            "다른 스킴" to "sharemat://pair?v=1&e=10.0.0.1:9247&c=123456&k=$key",
            "대문자 스킴" to "SHAREMATE://pair?v=1&e=10.0.0.1:9247&c=123456&k=$key",
            "다른 호스트" to "sharemate://pairing?v=1&e=10.0.0.1:9247&c=123456&k=$key",
            "경로가 붙음" to "sharemate://pair/?v=1&e=10.0.0.1:9247&c=123456&k=$key",
            "앞에 공백" to " $good",
            "뒤에 줄바꿈" to "$good\n",
            "매개변수 없음" to PAIRING_LINK_PREFIX,
            "버전 없음" to link("e=10.0.0.1:9247", "c=123456", "k=$key"),
            "버전 2" to link("v=2", "e=10.0.0.1:9247", "c=123456", "k=$key"),
            "버전 두 번" to link("v=1", "v=1", "e=10.0.0.1:9247", "c=123456", "k=$key"),
            "주소 없음" to link("v=1", "c=123456", "k=$key"),
            "주소 다섯" to link("v=1", "e=1.1.1.1:1", "e=2.2.2.2:2", "e=3.3.3.3:3", "e=4.4.4.4:4", "e=5.5.5.5:5", "c=123456", "k=$key"),
            "번호 없음" to link("v=1", "e=10.0.0.1:9247", "k=$key"),
            "번호 다섯 자리" to link("v=1", "e=10.0.0.1:9247", "c=12345", "k=$key"),
            "번호 일곱 자리" to link("v=1", "e=10.0.0.1:9247", "c=1234567", "k=$key"),
            "번호에 글자" to link("v=1", "e=10.0.0.1:9247", "c=12345a", "k=$key"),
            "번호 두 번" to link("v=1", "e=10.0.0.1:9247", "c=123456", "c=123456", "k=$key"),
            "키 없음" to link("v=1", "e=10.0.0.1:9247", "c=123456"),
            "키 63자리" to link("v=1", "e=10.0.0.1:9247", "c=123456", "k=${key.drop(1)}"),
            "키 65자리" to link("v=1", "e=10.0.0.1:9247", "c=123456", "k=${key}0"),
            "키에 16진수 아닌 글자" to link("v=1", "e=10.0.0.1:9247", "c=123456", "k=${key.dropLast(1)}g"),
            "키 두 번" to link("v=1", "e=10.0.0.1:9247", "c=123456", "k=$key", "k=$key"),
            "모르는 매개변수" to link("v=1", "e=10.0.0.1:9247", "c=123456", "k=$key", "x=1"),
            "빈 칸" to link("v=1", "e=10.0.0.1:9247", "", "c=123456", "k=$key"),
            "끝의 &" to link("v=1", "e=10.0.0.1:9247", "c=123456", "k=$key") + "&",
            "= 없는 매개변수" to link("v", "e=10.0.0.1:9247", "c=123456", "k=$key"),
            "이름 없는 값" to link("v=1", "=10.0.0.1:9247", "c=123456", "k=$key"),
            "프래그먼트" to "$good#top",
            "퍼센트 인코딩" to link("v=1", "e=10.0.0.1%3A9247", "c=123456", "k=$key"),
        )
        bad.forEach { (why, text) -> assertNull(why, parsePairingLink(text)) }
    }

    @Test fun everyKindOfBadAddressIsRefused() {
        val bad = mapOf(
            "포트 없음" to "10.0.0.1", "빈 포트" to "10.0.0.1:", "빈 호스트" to ":9247", "포트 0" to "10.0.0.1:0",
            "포트 65536" to "10.0.0.1:65536", "포트 앞의 0" to "10.0.0.1:09247", "포트에 글자" to "10.0.0.1:92a7",
            "음수 포트" to "10.0.0.1:-1", "IPv6" to "[::1]:9247", "콜론 둘" to "a:b:9247", "밑줄" to "car_7:9247",
            "공백" to "car 7:9247", "범위 밖 IPv4" to "999.1.1.1:9247", "세 칸 IPv4" to "10.0.1:9247", "앞의 0 IPv4" to "10.0.0.01:9247",
            "빈 라벨" to "car..local:9247", "점으로 끝남" to "car.:9247", "하이픈으로 시작" to "-car:9247", "하이픈으로 끝남" to "car-:9247",
            "64자 라벨" to "${"a".repeat(64)}:9247", "사용자 정보" to "user@10.0.0.1:9247", "스킴 포함" to "https://10.0.0.1:9247",
        )
        bad.forEach { (why, endpoint) ->
            assertNull(why, pairingEndpoint(endpoint))
            assertNull("$why (링크 안에서)", parsePairingLink(link("v=1", "e=$endpoint", "c=123456", "k=$key")))
        }
        assertEquals("63자 라벨은 됨", "${"a".repeat(63)}:9247", pairingEndpoint("${"a".repeat(63)}:9247"))
        assertEquals("localhost", "localhost:9247", pairingEndpoint("localhost:9247"))
    }

    @Test fun whatTheCarWritesThePhoneReadsBack() {
        val endpoints = listOf("10.0.2.15:9247", "192.168.43.1:9247", "car.local:9247")
        val text = pairingLinkText(endpoints, "012345", key)!!
        assertEquals(PairingLink(endpoints, "012345", key), parsePairingLink(text))
        assertTrue("car's own scheme", text.startsWith("sharemate://pair?v=1&e="))
        // The car keeps its order, writes four at most and leaves out what the phone would refuse.
        val many = listOf("bad", "1.1.1.1:1", "2.2.2.2:2", "1.1.1.1:1", "3.3.3.3:3", "4.4.4.4:4", "5.5.5.5:5")
        assertEquals(listOf("1.1.1.1:1", "2.2.2.2:2", "3.3.3.3:3", "4.4.4.4:4"), parsePairingLink(pairingLinkText(many, "123456", key)!!)!!.endpoints)
        assertEquals("키는 소문자로", key, parsePairingLink(pairingLinkText(endpoints, "123456", key.uppercase())!!)!!.fingerprint)
    }

    @Test fun theCarWritesNoLinkThePhoneWouldRefuse() {
        assertNull("주소 없음", pairingLinkText(emptyList(), "123456", key))
        assertNull("쓸 수 있는 주소 없음", pairingLinkText(listOf("[::1]:9247"), "123456", key))
        assertNull("번호 없음", pairingLinkText(listOf("10.0.0.1:9247"), "", key))
        assertNull("번호 다섯 자리", pairingLinkText(listOf("10.0.0.1:9247"), "12345", key))
        assertNull("키 없음", pairingLinkText(listOf("10.0.0.1:9247"), "123456", ""))
        val now = 1_790_000_000_000L
        val info = SyncInfo(running = true, port = 9247, addresses = listOf("10.0.2.15", "10.0.2.16"), pairingCode = "455426",
            pairingExpiresAt = now + SYNC_PAIR_TTL, fingerprint = key)
        assertEquals(good, syncPairingLinkText(info, now))
        assertNull("번호 없음(사용됨·닫힘)", syncPairingLinkText(info.copy(pairingCode = null), now))
        assertNull("주소 확인 중", syncPairingLinkText(info.copy(addresses = emptyList()), now))
        assertNull("키를 아직 모름", syncPairingLinkText(info.copy(fingerprint = ""), now))
    }

    // ───────────────────────── an expired code is no code (§9.6) ─────────────────────────

    @Test fun aCodeIsLiveOnlyBeforeItsEnd() {
        val now = 1_790_000_000_000L
        val info = SyncInfo(running = true, port = 9247, addresses = listOf("10.0.2.15", "10.0.2.16"), pairingCode = "455426",
            pairingExpiresAt = now + SYNC_PAIR_TTL, fingerprint = key)
        // Live: the whole five minutes, up to the last millisecond.
        assertEquals("455426", livePairingCode(info, now))
        assertEquals(good, syncPairingLinkText(info, now + SYNC_PAIR_TTL - 1))
        assertEquals("4:59", pairingRemaining(info, now + 1_000))
        assertEquals("0:00", pairingRemaining(info, now + SYNC_PAIR_TTL - 1))
        // Expired: the server has not published yet, so the field still holds the code, and nothing on screen may use it.
        val end = now + SYNC_PAIR_TTL
        listOf(end, end + 1, end + 60_000).forEach { at ->
            assertNull("만료 후 번호 없음 ($at)", livePairingCode(info, at))
            assertNull("만료 후 QR 없음 ($at)", syncPairingLinkText(info, at))
            assertNull("만료 후 \"0:00 남음\" 없음 ($at)", pairingRemaining(info, at))
        }
        // Missing: used, or closed after too many wrong tries.
        val missing = info.copy(pairingCode = null)
        assertNull(livePairingCode(missing, now))
        assertNull(syncPairingLinkText(missing, now))
        assertNull(pairingRemaining(missing, now))
        // A state that never had a code (the server not started) has no expiry either.
        assertNull(syncPairingLinkText(SyncInfo(pairingCode = "455426"), now))
    }

    /**
     * 2.15.1 (docs/design/DESIGN_2_15.md §10): TalkBack hears the countdown tag in minutes and seconds, never "4:59" as a time
     * of day, from the same count as the tag, and has nothing to say exactly where the tag is gone.
     */
    @Test fun theCountdownIsHeardInMinutesAndSeconds() {
        val now = 1_790_000_000_000L
        val info = SyncInfo(running = true, port = 9247, addresses = listOf("10.0.2.15"), pairingCode = "455426", pairingExpiresAt = now + SYNC_PAIR_TTL,
            fingerprint = key)
        assertEquals("4:59", pairingRemaining(info, now + 1_000))
        assertEquals("4분 59초 남음", pairingRemainingSpoken(info, now + 1_000))
        assertEquals("정각의 분은 초 없이", "5분 남음", pairingRemainingSpoken(info, now))
        assertEquals("1분 남음", pairingRemainingSpoken(info, now + 240_000))
        assertEquals("1분 아래는 초만", "59초 남음", pairingRemainingSpoken(info, now + 241_000))
        assertEquals("0:00", pairingRemaining(info, now + SYNC_PAIR_TTL - 1))
        assertEquals("0초 남음", pairingRemainingSpoken(info, now + SYNC_PAIR_TTL - 1))
        assertEquals("1분 1초 남음", VehicleCopy.spokenPhoneRemaining(61))
        // Null exactly where the tag is: at expiry, after it, and without a code.
        listOf(now + SYNC_PAIR_TTL, now + SYNC_PAIR_TTL + 60_000).forEach { at -> assertNull("만료 후 ($at)", pairingRemainingSpoken(info, at)) }
        assertNull(pairingRemainingSpoken(info.copy(pairingCode = null), now))
        (0..SYNC_PAIR_TTL + 2_000 step 997).forEach { offset ->
            val at = now + offset
            assertEquals("$offset", pairingRemaining(info, at) == null, pairingRemainingSpoken(info, at) == null)
            pairingRemainingSpoken(info, at)?.let { spoken ->
                assertTrue(spoken, spoken.endsWith(" 남음") && !spoken.contains(':'))
                // The spoken form says the tag's own minutes and seconds.
                val (m, s) = pairingRemaining(info, at)!!.split(':').map { it.toInt() }
                assertEquals(VehicleCopy.spokenPhoneRemaining(m * 60L + s), spoken)
            }
        }
    }

    // ───────────────────────── how far a scanned link is trusted (§9.6) ─────────────────────────

    private fun linkTo(vararg endpoints: String) = PairingLink(endpoints.toList(), "455426", key)

    @Test fun onlyALinkWhoseAddressesAreAllACarsLocalOnesIsLocal() {
        val local = mapOf(
            "에뮬레이터의 두 주소" to linkTo("10.0.2.15:9247", "10.0.2.16:9247"),
            "10/8 끝" to linkTo("10.255.255.255:9247"),
            "172.16/12 처음" to linkTo("172.16.0.1:9247"),
            "172.16/12 끝" to linkTo("172.31.255.254:9247"),
            "핫스팟" to linkTo("192.168.43.1:9247"),
            "링크 로컬" to linkTo("169.254.12.7:9247"),
            "사설 넷" to linkTo("10.0.0.1:1", "172.20.1.1:2", "192.168.0.12:3", "169.254.0.1:4"),
        )
        local.forEach { (why, link) -> assertTrue(why, pairingLinkIsLocal(link)) }
        // What a real car writes, read back by the phone: always local.
        assertTrue("차가 쓴 링크", pairingLinkIsLocal(parsePairingLink(good)!!))
    }

    @Test fun aWaitingLinkShowsTheAddressThatIsNotLocal() {
        // A local decoy first and a foreign host behind it: the line must name where the code could really go (§9.6).
        assertEquals("203.0.113.9:443", pairingLinkShownEndpoint(linkTo("10.255.255.1:9247", "203.0.113.9:443")))
        assertEquals("car-7.fleet.local:9247", pairingLinkShownEndpoint(linkTo("192.168.0.12:9247", "10.0.0.2:9247", "car-7.fleet.local:9247")))
        assertEquals("공인이 먼저면 그 주소", "8.8.8.8:9247", pairingLinkShownEndpoint(linkTo("8.8.8.8:9247", "192.168.0.12:9247")))
        // An all-local link from the picker names its first address.
        assertEquals("10.0.2.15:9247", pairingLinkShownEndpoint(linkTo("10.0.2.15:9247", "10.0.2.16:9247")))
        assertEquals("10.0.2.15:9247", pairingLinkShownEndpoint(parsePairingLink(good)!!))
    }

    @Test fun aPublicAddressAHostNameOrLoopbackIsNotLocal() {
        val notLocal = mapOf(
            "공인 주소" to linkTo("203.0.113.9:443"),
            "사설과 공인이 섞임" to linkTo("10.0.2.15:9247", "203.0.113.9:443"),
            "공인이 먼저" to linkTo("8.8.8.8:9247", "192.168.0.12:9247"),
            "172.32.x" to linkTo("172.32.0.1:9247"),
            "172.15.x" to linkTo("172.15.255.255:9247"),
            "192.169.x" to linkTo("192.169.0.1:9247"),
            "169.253.x" to linkTo("169.253.0.1:9247"),
            "11.x" to linkTo("11.0.0.1:9247"),
            "호스트 이름" to linkTo("car-7.fleet.local:9247"),
            "사설처럼 보이는 이름" to linkTo("10.0.2.15.nip.io:9247"),
            "localhost" to linkTo("localhost:9247"),
            "루프백은 차가 아님" to linkTo("127.0.0.1:9247"),
            "16진수 루프백" to linkTo("0x7f.0.0.1:9247"),
            "0.0.0.0" to linkTo("0.0.0.0:9247"),
            "세 칸" to linkTo("10.0.1:9247"),
            "범위 밖" to linkTo("10.0.0.256:9247"),
            "앞의 0" to linkTo("010.0.0.1:9247"),
            "전각 숫자" to linkTo("１0.0.0.1:9247"),
            "주소 없음" to PairingLink(emptyList(), "455426", key),
        )
        notLocal.forEach { (why, link) -> assertFalse(why, pairingLinkIsLocal(link)) }
        // Through the parser: a public address and a host name are good links, and still not local.
        assertFalse(pairingLinkIsLocal(parsePairingLink(link("v=1", "e=203.0.113.9:443", "c=123456", "k=$key"))!!))
        assertFalse(pairingLinkIsLocal(parsePairingLink(link("v=1", "e=localhost:9247", "c=123456", "k=$key"))!!))
    }

    @Test fun onlyTheCamerasPictureOfALocalLinkPairsAtOnce() {
        assertTrue("카메라 + 사설 주소: 바로 연결", scannedLinkPairsAtOnce(fromCamera = true, local = true))
        assertFalse("카메라 + 다른 주소: 확인 후", scannedLinkPairsAtOnce(fromCamera = true, local = false))
        assertFalse("사진 + 사설 주소: 확인 후", scannedLinkPairsAtOnce(fromCamera = false, local = true))
        assertFalse("사진 + 다른 주소: 확인 후", scannedLinkPairsAtOnce(fromCamera = false, local = false))
    }

    @Test fun aLinkIsFiveHundredTwelveCharactersAtMost() {
        // Two different host names padded so the whole link is exactly the cap; one character more is refused, and never written.
        fun hostOf(length: Int, letter: Char) = (0 until length).joinToString("") { if (it % 50 == 49) "." else "$letter" }
            .let { if (it.endsWith(".")) it.dropLast(1) + letter else it }
        val fixed = link("v=1", "e=:9247", "e=:9247", "c=123456", "k=$key").length
        val room = PAIRING_LINK_MAX_LENGTH - fixed
        val first = hostOf(room / 2, 'a')
        val second = hostOf(room - room / 2, 'b')
        val longest = link("v=1", "e=$first:9247", "e=$second:9247", "c=123456", "k=$key")
        assertEquals(PAIRING_LINK_MAX_LENGTH, longest.length)
        assertNotNull("512자는 됨", parsePairingLink(longest))
        val over = link("v=1", "e=${first}a:9247", "e=$second:9247", "c=123456", "k=$key")
        assertEquals(PAIRING_LINK_MAX_LENGTH + 1, over.length)
        assertNull("513자는 안 됨", parsePairingLink(over))
        assertNull("차가 쓰지도 않음", pairingLinkText(listOf("${first}a:9247", "$second:9247"), "123456", key))
        assertEquals(longest, pairingLinkText(listOf("$first:9247", "$second:9247"), "123456", key))
    }

    // ───────────────────────── the order of the addresses (§6.3) ─────────────────────────

    @Test fun theTypedOrScannedAddressesAreTriedInTheirOrder() {
        assertEquals(listOf("10.0.2.15:9247", "10.0.2.16:9247"), pairingEndpointList("10.0.2.15:9247,10.0.2.16:9247"))
        assertEquals("공백·빈 칸·반복 정리", listOf("a:1", "b:2"), pairingEndpointList(" a:1 , ,b:2,a:1, "))
        assertEquals("하나만 입력", listOf("192.168.0.12:9247"), pairingEndpointList("192.168.0.12:9247"))
        assertTrue(pairingEndpointList("  ").isEmpty())
    }

    @Test fun onlyAPlaceThatCannotBeReachedLetsTheNextBeTried() {
        listOf(ConnectException("refused"), SocketTimeoutException("timeout"), UnknownHostException("x"), NoRouteToHostException("no route"),
            IOException("reset"), SSLException("Connection reset during handshake"))
            .forEach { assertEquals(it.toString(), PairAttemptOutcome.NEXT, pairAttemptOutcome(it)) }
        val pinMismatch = SSLHandshakeException("handshake").apply { initCause(CertificateException("Certificate pin mismatch")) }
        listOf(pinMismatch, IllegalArgumentException("연결 번호가 달라요. 차량 화면의 번호를 다시 확인해 주세요."),
            IllegalArgumentException("차량 인증키가 달라요."), CancellationException(), IllegalStateException())
            .forEach { assertEquals(it.toString(), PairAttemptOutcome.STOP, pairAttemptOutcome(it)) }
    }

    @Test fun anUnreachableAddressMovesOnAndTheFirstThatAnswersWins() {
        val tried = mutableListOf<String>()
        val result = pairInOrder(listOf("a", "b", "c")) { endpoint ->
            tried += endpoint
            if (endpoint == "a") throw ConnectException("refused") else "paired at $endpoint"
        }
        assertEquals("paired at b", result)
        assertEquals("c는 시도하지 않음", listOf("a", "b"), tried)
    }

    @Test fun aWrongCodeOrKeyStopsAtOnceAndTheCodeGoesNowhereElse() {
        val wrongCode = IllegalArgumentException("연결 번호가 달라요. 차량 화면의 번호를 다시 확인해 주세요.")
        val tried = mutableListOf<String>()
        val thrown = runCatching { pairInOrder(listOf("a", "b")) { endpoint -> tried += endpoint; throw wrongCode } }.exceptionOrNull()
        assertSame(wrongCode, thrown)
        assertEquals("두 번째 주소에 번호를 보내지 않음", listOf("a"), tried)

        val wrongKey = SSLHandshakeException("handshake").apply { initCause(CertificateException("Certificate pin mismatch")) }
        tried.clear()
        val keyThrown = runCatching {
            pairInOrder(listOf("a", "b", "c")) { endpoint -> tried += endpoint; if (endpoint == "a") throw SocketTimeoutException() else throw wrongKey }
        }.exceptionOrNull()
        assertSame(wrongKey, keyThrown)
        assertEquals(listOf("a", "b"), tried)
    }

    @Test fun whenNothingAnswersTheBestHintIsThrown() {
        val last = ConnectException("refused")
        assertSame("마지막 실패", last, runCatching { pairInOrder(listOf("a", "b")) { if (it == "a") throw SocketTimeoutException() else throw last } }.exceptionOrNull())
        val tls = SSLException("not TLS")
        assertSame("TLS 실패가 있으면 그것(인증키 안내)", tls,
            runCatching { pairInOrder(listOf("a", "b", "c")) { when (it) { "b" -> throw tls; else -> throw ConnectException() } } }.exceptionOrNull())
        try { pairInOrder(emptyList<String>()) { it }; fail("빈 목록") } catch (e: IllegalArgumentException) { assertEquals("차량 주소를 확인해 주세요.", e.message) }
    }
}
