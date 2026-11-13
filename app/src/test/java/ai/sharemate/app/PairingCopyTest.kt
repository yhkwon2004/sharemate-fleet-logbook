package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words of pairing by QR (2.15, docs/design/DESIGN_2_15.md §6): the car's lines in [VehicleCopy] under the one-line rule
 * (CarCopyRules), the column beside the QR ([pairingQrHeadline]), and the phone's lines in [PhoneQrCopy].
 */
class PairingCopyTest {
    /** A line set in titleSmall (19 sp) across a card gets the width of a 26-unit bodySmall (16 sp) line: 26 × 16 / 19 units. */
    private val titleSmallLineUnits = CARD_LINE_UNITS * 16 / 19

    /**
     * The 248-dp column beside the 232-dp QR where the steps stand beside it too (§9.6): a titleSmall unit is about 17.5 dp
     * (19 sp against the 14.7 dp of a 16-sp unit), so 13 units are about 228 dp, inside the column with room to spare.
     */
    private val qrSideColumnUnits = 13.0

    @Test fun theCarsPairingWordsAreOneLineEach() {
        with(VehicleCopy) {
            // 2.15 fix (§9.6): the QR's headline is one line in the column beside the tile, in either form of the card.
            assertEquals("휴대폰 앱으로 찍어요", PHONE_QR)
            assertOneLine(PHONE_QR, qrSideColumnUnits)
            // So are the two reasons the column gives most often in its place, so the column keeps its height at expiry.
            listOf(PHONE_EXPIRED, PHONE_ADDRESS_PENDING).forEach { assertOneLine(it, qrSideColumnUnits) }
            listOf(PHONE_QR, PHONE_MANUAL).forEach { assertOneLine(it, titleSmallLineUnits) }
            listOf(PHONE_NEW_CODE, PHONE_RENEW_CODE).forEach { assertOneLine(it, CARD_LINE_UNITS) }
            assertTrue("TalkBack가 QR이라고 들음", SPOKEN_PHONE_QR.contains("QR"))
            assertTrue("그림에 쓴 말과 같은 이름", SPOKEN_PHONE_QR.startsWith(PHONE_TITLE))
            assertFalse("조사 앞 공백 없음", PHONE_QR.contains("QR 을"))
            // The empty code's place is a mark, not words: no Hangul, and the same shape as "455 426".
            assertFalse(PHONE_CODE_NONE.any { it in '가'..'힣' })
            assertEquals(pairingDisplay("455426").length, PHONE_CODE_NONE.length)
        }
    }

    /**
     * 2.15.1 (docs/design/DESIGN_2_15.md §10): 홈's pairing column. Its headline is one headlineSmall line across the column in
     * every state (pairingQrHeadline's words, or 휴대폰 연결을 준비하고 있어요 while the server is not running); the line at the
     * action row's start is the spec's words and one line wherever the column stands beside the QR, beside one button; the
     * three labelled lines keep the 차량 page's names; and the countdown is heard in minutes and seconds.
     */
    @Test fun theHomeColumnsWordsAreOneLineEach() {
        with(VehicleCopy) {
            assertEquals("같은 Wi-Fi나 핫스팟에서 찍어요", PHONE_QR_HOW)
            assertOneLine(PHONE_QR_HOW, PAIRING_COLUMN_LINE_UNITS)
            assertFalse("조사 앞 공백 없음", PHONE_QR_HOW.contains("Fi 나"))
            // Without a QR on the tile the line says what brings one back, in the same slot beside the button.
            assertEquals(PHONE_QR_HOW, pairingQrHint(qr = true, code = true))
            assertEquals("새 번호를 만들면 QR이 다시 나와요", pairingQrHint(qr = false, code = false))
            assertEquals("Wi-Fi나 핫스팟에 연결되면 QR이 나와요", pairingQrHint(qr = false, code = true))
            listOf(PHONE_QR_AGAIN, PHONE_QR_NETWORK).forEach { assertOneLine(it, PAIRING_COLUMN_LINE_UNITS) }
            assertFalse("QR이 없을 때 찍으라고 하지 않음", listOf(PHONE_QR_AGAIN, PHONE_QR_NETWORK).any { it.contains("찍어요") })
            listOf(PHONE_QR, PHONE_EXPIRED, PHONE_ANOTHER, PHONE_ADDRESS_PENDING, HomeCopy.PAIRING_PREPARING).forEach { assertOneLine(it, PAIRING_HEADLINE_UNITS) }
            // The three names, in the phone's order, as the 차량 page's steps name them; each fits the 72-dp name column
            // (labelMedium, 16 sp: 4.5 units are about 66 dp, so 연결 번호 keeps a gap before its digits).
            listOf(PHONE_ADDRESS, PHONE_CODE, PHONE_KEY).forEach { assertTrue(it, oneLineUnits(it) <= 4.5) }
            listOf(PHONE_NEW_CODE, PHONE_RENEW_CODE).forEach { assertOneLine(it, CARD_LINE_UNITS) }
            assertEquals("4분 59초 남음", spokenPhoneRemaining(299))
            assertEquals("5분 남음", spokenPhoneRemaining(300))
            assertEquals("59초 남음", spokenPhoneRemaining(59))
            assertEquals("0초 남음", spokenPhoneRemaining(0))
            listOf(0L, 59L, 60L, 61L, 299L, 300L).map(VehicleCopy::spokenPhoneRemaining).forEach {
                assertFalse("시각처럼 들리지 않음: $it", it.contains(':'))
                assertFalse("마침표 없음: $it", it.endsWith('.'))
            }
        }
    }

    /**
     * 2.15.1 fix: where the column beside 홈's QR is narrowest ([PAIRING_COLUMN_MIN], some 11.7 headlineSmall units at any font,
     * since the least grows with the font), the headline wraps to two lines at the most and is never cut. At FULL it stays one.
     */
    @Test fun theHeadlineWrapsInTheNarrowestColumnBesideTheQrAndIsNeverCut() {
        assertEquals(2, PAIRING_HEADLINE_LINES)
        val headlines = listOf(VehicleCopy.PHONE_QR, VehicleCopy.PHONE_EXPIRED, VehicleCopy.PHONE_ANOTHER, VehicleCopy.PHONE_ADDRESS_PENDING,
            HomeCopy.PAIRING_PREPARING)
        headlines.forEach { assertTrue("$it: ${phraseLines(it, PAIRING_HEADLINE_NARROW_UNITS)}줄", phraseLines(it, PAIRING_HEADLINE_NARROW_UNITS) <= PAIRING_HEADLINE_LINES) }
        headlines.forEach { assertEquals("FULL에서는 한 줄: $it", 1, phraseLines(it, PAIRING_HEADLINE_UNITS)) }
        // The two forms that need the second line there (14 and 13.75 units against some 11.7), and the two that do not.
        assertEquals(2, phraseLines(VehicleCopy.PHONE_ANOTHER, PAIRING_HEADLINE_NARROW_UNITS))
        assertEquals(2, phraseLines(HomeCopy.PAIRING_PREPARING, PAIRING_HEADLINE_NARROW_UNITS))
        assertEquals(1, phraseLines(VehicleCopy.PHONE_QR, PAIRING_HEADLINE_NARROW_UNITS))
        assertEquals(1, phraseLines(VehicleCopy.PHONE_EXPIRED, PAIRING_HEADLINE_NARROW_UNITS))
        // The counting itself: a break at each space that would overflow, a phrase wider than the line alone on its own.
        assertEquals(1, phraseLines("가나 다라", 4.25))
        assertEquals(2, phraseLines("가나 다라", 4.0))
        assertEquals(2, phraseLines("가나다라마 바", 3.0))
    }

    @Test fun theColumnBesideTheQrSaysWhatToDoOrWhyThereIsNone() {
        assertEquals(VehicleCopy.PHONE_QR, pairingQrHeadline("sharemate://pair?…", "455426", 0))
        assertEquals("번호가 없고 연결된 휴대폰이 없음", VehicleCopy.PHONE_EXPIRED, pairingQrHeadline(null, null, 0))
        assertEquals("번호가 없고 휴대폰이 이미 있음", VehicleCopy.PHONE_ANOTHER, pairingQrHeadline(null, null, 2))
        assertEquals("번호는 있으나 주소를 아직 모름", VehicleCopy.PHONE_ADDRESS_PENDING, pairingQrHeadline(null, "455426", 0))
    }

    @Test fun atExpiryTheHeadlineSaysTheCodeRanOutAndThePillOffersANewOne() {
        // §9.6: the column reads the live code, as PhonePairing does; the server has not published yet (the field still holds it).
        val now = 1_790_000_000_000L
        val key = "499efa1aa0f8445c3832ad7c3b911b20a0d9e04f3d66d03591d84b964802b21e"
        val info = SyncInfo(running = true, port = 9247, addresses = listOf("10.0.2.15"), pairingCode = "455426", pairingExpiresAt = now + SYNC_PAIR_TTL,
            fingerprint = key)
        fun headline(at: Long) = pairingQrHeadline(syncPairingLinkText(info, at), livePairingCode(info, at), info.pairedPhones)
        assertEquals("5분 안에는 QR", VehicleCopy.PHONE_QR, headline(now + SYNC_PAIR_TTL - 1))
        assertEquals("만료되면 만료됐다고", VehicleCopy.PHONE_EXPIRED, headline(now + SYNC_PAIR_TTL))
        assertEquals("다른 휴대폰이 있으면 더 연결", VehicleCopy.PHONE_ANOTHER,
            pairingQrHeadline(syncPairingLinkText(info, now + SYNC_PAIR_TTL), livePairingCode(info, now + SYNC_PAIR_TTL), 1))
        // The pill's label follows the same live code: 새 연결 번호 once it ran out.
        val pill = { at: Long -> if (livePairingCode(info, at) == null) VehicleCopy.PHONE_NEW_CODE else VehicleCopy.PHONE_RENEW_CODE }
        assertEquals(VehicleCopy.PHONE_RENEW_CODE, pill(now))
        assertEquals(VehicleCopy.PHONE_NEW_CODE, pill(now + SYNC_PAIR_TTL))
    }

    @Test fun thePhonesQrWordsAreShortAndEndWithoutAPeriod() {
        with(PhoneQrCopy) {
            listOf(SCAN, READING, PICK, MANUAL, UNREADABLE, NOT_OURS, NO_CAMERA, NO_PICKER, confirm("203.0.113.9:443")).forEach {
                assertFalse("한 줄: $it", it.contains('\n'))
                assertFalse("마침표 없음: $it", it.trimEnd().endsWith('.'))
            }
            // The spec's own words (§6.3, §9.6).
            assertEquals("QR을 읽지 못했어요 · 화면에 QR이 다 나오게 다시 찍어 주세요", UNREADABLE)
            assertEquals("SHAREMATE 연결 QR이 아니에요", NOT_OURS)
            assertEquals("주소를 확인하고 차량과 연결을 눌러 주세요 · 203.0.113.9:443", confirm("203.0.113.9:443"))
            listOf(SCAN, PICK, MANUAL).forEach { assertTrue("버튼 이름은 짧게: $it", oneLineUnits(it) <= 10.0) }
        }
    }

    @Test fun theWaitingLineNamesTheFirstAddressOfTheLink() {
        // §9.6: a link that does not pair at once shows where the code would go first, the first of its addresses.
        val link = parsePairingLink("sharemate://pair?v=1&e=203.0.113.9:443&e=10.0.2.15:9247&c=455426&k=${"a".repeat(64)}")!!
        assertFalse(pairingLinkIsLocal(link))
        assertTrue(PhoneQrCopy.confirm(link.endpoints.first()).endsWith("· 203.0.113.9:443"))
        // It names the button the person presses, in the button's own words.
        assertTrue(PhoneQrCopy.confirm("x").contains("차량과 연결"))
    }
}
