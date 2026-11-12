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

    @Test fun theCarsPairingWordsAreOneLineEach() {
        with(VehicleCopy) {
            // Beside the QR the prompt may take two lines; stacked under it (a narrow card) it is one card line.
            listOf(PHONE_QR, PHONE_MANUAL).forEach { assertOneLine(it, titleSmallLineUnits) }
            listOf(PHONE_NEW_CODE, PHONE_RENEW_CODE).forEach { assertOneLine(it, CARD_LINE_UNITS) }
            assertTrue("TalkBack가 QR이라고 들음", SPOKEN_PHONE_QR.contains("QR"))
            assertTrue("그림에 쓴 말과 같은 이름", SPOKEN_PHONE_QR.startsWith(PHONE_TITLE))
            assertFalse("조사 앞 공백 없음", PHONE_QR.contains("QR 을"))
        }
    }

    @Test fun theColumnBesideTheQrSaysWhatToDoOrWhyThereIsNone() {
        assertEquals(VehicleCopy.PHONE_QR, pairingQrHeadline("sharemate://pair?…", "455426", 0))
        assertEquals("번호가 없고 연결된 휴대폰이 없음", VehicleCopy.PHONE_EXPIRED, pairingQrHeadline(null, null, 0))
        assertEquals("번호가 없고 휴대폰이 이미 있음", VehicleCopy.PHONE_ANOTHER, pairingQrHeadline(null, null, 2))
        assertEquals("번호는 있으나 주소를 아직 모름", VehicleCopy.PHONE_ADDRESS_PENDING, pairingQrHeadline(null, "455426", 0))
    }

    @Test fun thePhonesQrWordsAreShortAndEndWithoutAPeriod() {
        with(PhoneQrCopy) {
            listOf(SCAN, READING, PICK, MANUAL, UNREADABLE, NOT_OURS, NO_CAMERA, NO_PICKER).forEach {
                assertFalse("한 줄: $it", it.contains('\n'))
                assertFalse("마침표 없음: $it", it.trimEnd().endsWith('.'))
            }
            // The spec's own words (§6.3).
            assertEquals("QR을 읽지 못했어요 · 화면에 QR이 다 나오게 다시 찍어 주세요", UNREADABLE)
            assertEquals("SHAREMATE 연결 QR이 아니에요", NOT_OURS)
            listOf(SCAN, PICK, MANUAL).forEach { assertTrue("버튼 이름은 짧게: $it", oneLineUnits(it) <= 10.0) }
        }
    }
}
