package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of the car's one money style (docs/design/DESIGN_2_13_SPEC.md §5): grouping, the money field's cursor
 * mapping and its filter, amounts read back in 만 and 억, the field's state against its range, and what an amount draws.
 */
class CarMoneyTest {
    @Test fun digitsGroupInThreesFromTheEnd() {
        assertEquals("", groupDigits(""))
        assertEquals("150", groupDigits("150"))
        assertEquals("1,500", groupDigits("1500"))
        assertEquals("32,000,000", groupDigits("32000000"))
        assertEquals("열한 자리", "12,345,678,901", groupDigits("12345678901"))
    }

    @Test fun theCursorAlwaysRestsAfterADigitAndMapsBothWays() {
        for (n in 0..11) {
            val shown = groupDigits("7".repeat(n))
            var last = 0
            for (raw in 0..n) {
                val at = wonShownOffset(n, raw)
                assertTrue("n=$n raw=$raw → $at: 표시 문자열 안", at in 0..shown.length)
                assertTrue("n=$n raw=$raw: 줄지 않음", at >= last)
                assertEquals("n=$n raw=$raw: 왕복", raw, wonRawOffset(n, at))
                if (raw > 0) assertTrue("n=$n raw=$raw: 숫자 뒤", shown[at - 1].isDigit())
                last = at
            }
            // Every shown offset, a comma's included, maps back inside the raw digits.
            for (at in 0..shown.length) assertTrue("n=$n shown=$at", wonRawOffset(n, at) in 0..n)
        }
        // 32,000,000: the cursor after "32" stays before the comma; after the third digit it skips it.
        assertEquals(2, wonShownOffset(8, 2))
        assertEquals(4, wonShownOffset(8, 3))
        assertEquals(10, wonShownOffset(8, 8))
        assertEquals("쉼표 뒤 커서는 앞 숫자에", 2, wonRawOffset(8, 3))
        assertEquals(5, wonRawOffset(8, 7))
    }

    @Test fun theFieldKeepsDigitsOnlyAndIgnoresAnEditPastItsLength() {
        assertEquals("붙여 넣은 금액", "4500", moneyDigits("4,500원", 8))
        assertEquals("32000000", moneyDigits("32,000,000원", 8))
        assertEquals("앞의 0은 지움", "7", moneyDigits("007", 8))
        assertEquals("0만 있으면 0", "0", moneyDigits("000", 8))
        assertEquals("비우기", "", moneyDigits("", 8))
        assertNull("아홉 자리는 무시", moneyDigits("123456789", 8))
        assertEquals("자릿수는 0을 지운 뒤에 셈", "1", moneyDigits("000000001", 8))
    }

    @Test fun largeAmountsReadIn만And억() {
        assertEquals("4,500원", wonReading(4_500))
        assertEquals("1만 5,000원", wonReading(15_000))
        assertEquals("10만 원", wonReading(100_000))
        assertEquals("1,000만 원", wonReading(10_000_000))
        assertEquals("3,200만 원", wonReading(32_000_000))
        assertEquals("1억 2,000만 원", wonReading(120_000_000))
        assertEquals("100억 원", wonReading(10_000_000_000))
        assertEquals("만 없이 억과 원", "1억 5,000원", wonReading(100_005_000))
        assertEquals("0원", wonReading(0))
        assertEquals("1,000만 원까지 입력할 수 있어요", wonLimit(10_000_000))
        assertEquals("10만 원까지 입력할 수 있어요", wonLimit(100_000))
        assertEquals("100억 원까지 입력할 수 있어요", wonLimit(10_000_000_000))
    }

    @Test fun theFieldsStateFollowsItsRange() {
        val blank = moneyInput("", min = 1, max = 10_000_000, hint = "1원~1,000만 원")
        assertFalse("최소 1이면 빈 칸은 아직", blank.ok)
        assertNull("빈 칸은 오류가 아님", blank.error)
        assertNull(blank.won)
        assertEquals("1원~1,000만 원", blank.helper)
        val over = moneyInput("20000000", min = 1, max = 10_000_000, hint = "1원~1,000만 원")
        assertFalse(over.ok)
        assertEquals("1,000만 원까지 입력할 수 있어요", over.error)
        assertEquals("오류가 도움말 자리에", over.error, over.helper)
        assertTrue("최소 0이면 빈 칸도 됨", moneyInput("", min = 0, max = 10_000_000, hint = "보증·무상이면 비워 두세요").ok)
        val zero = moneyInput("0", min = 1, max = 10_000_000, hint = "1원~1,000만 원")
        assertFalse("범위 아래는 아직", zero.ok)
        assertNull("범위 아래는 도움말이 범위를 말함", zero.error)
        val fine = moneyInput("4500", min = 1, max = 10_000_000, hint = "1원~1,000만 원")
        assertTrue(fine.ok)
        assertEquals(4_500L, fine.won)
        assertEquals("읽어 주기 없으면 도움말", "1원~1,000만 원", fine.helper)
        assertEquals("3,200만 원", moneyInput("32000000", min = 0, max = 10_000_000_000, hint = SettingsCopy.COST_HINT, reading = true).helper)
        assertEquals("1만 아래는 읽어 주지 않음", SettingsCopy.COST_HINT, moneyInput("9000", 0, 10_000_000_000, SettingsCopy.COST_HINT, reading = true).helper)
        assertEquals("열두 자리도 범위 밖", "100억 원까지 입력할 수 있어요", moneyInput("999999999999", 0, 10_000_000_000, "").error)
    }

    @Test fun anAmountDrawsItsDigitsItsUnitAndWhatIsSpoken() {
        val amount = moneyText(45_000, "—")
        assertEquals("45,000", amount.digits)
        assertEquals("원", amount.unit)
        assertEquals("45,000원", amount.spoken)
        assertFalse(amount.quiet)
        val zero = moneyText(0, "—")
        assertTrue("0원은 흐리게", zero.zero)
        assertTrue(zero.quiet)
        assertEquals("0", zero.digits)
        assertEquals("0원", zero.spoken)
        val missing = moneyText(null, "—")
        assertTrue(missing.missing)
        assertTrue(missing.quiet)
        assertEquals("—", missing.digits)
        assertEquals("", missing.unit)
        assertEquals("금액 없음", missing.spoken)
        assertEquals("말로 된 표시는 그대로 읽음", "금액 없음", moneyText(null, "금액 없음").spoken)
    }
}
