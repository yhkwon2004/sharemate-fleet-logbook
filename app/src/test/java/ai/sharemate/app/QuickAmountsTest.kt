package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quick amounts under 운행 비용's amount field (2.15, docs/design/DESIGN_2_15.md §5.1): which recent amounts a type
 * offers, what an adding chip leaves in the field, and the chips' words.
 */
class QuickAmountsTest {
    private val day = 1_790_000_000_000L
    private val hour = 3_600_000L

    private fun trip(seed: String, start: Long, vararg expenses: TripExpense) =
        Trip(seed, start, start + hour, 100.0, 110.0, expenses = expenses.toList())

    private fun cost(type: ExpenseType, won: Long, at: Long) = TripExpense(type, won, at, EditSource.DRIVER)

    @Test fun recentAmountsAreTheTypesNewestDistinctAmounts() {
        val trips = listOf(
            trip("a", day, cost(ExpenseType.PARKING, 3_000, day + 1), cost(ExpenseType.FUEL, 45_000, day + 2)),
            trip("b", day + 2 * hour, cost(ExpenseType.PARKING, 4_500, day + 3 * hour), cost(ExpenseType.TOLL, 3_600, day + 3 * hour)),
            trip("c", day + 4 * hour, cost(ExpenseType.PARKING, 4_500, day + 5 * hour)),
        )
        assertEquals("최근 것부터, 같은 금액은 한 번", listOf(4_500L, 3_000L), recentAmounts(trips, ExpenseType.PARKING))
        assertEquals("다른 종류는 보지 않음", listOf(45_000L), recentAmounts(trips, ExpenseType.FUEL))
        assertEquals(listOf(3_600L), recentAmounts(trips, ExpenseType.TOLL))
        assertTrue("없는 종류", recentAmounts(trips, ExpenseType.MAINTENANCE).isEmpty())
        assertTrue("빈 기록", recentAmounts(emptyList(), ExpenseType.PARKING).isEmpty())
    }

    @Test fun recentAmountsKeepToTheLimitAndToWhatTheFieldHolds() {
        val trips = listOf(trip("a", day, cost(ExpenseType.OTHER, 1_000, day + 1), cost(ExpenseType.OTHER, 2_000, day + 2), cost(ExpenseType.OTHER, 3_000, day + 3)))
        assertEquals("기본 두 개", listOf(3_000L, 2_000L), recentAmounts(trips, ExpenseType.OTHER))
        assertEquals(listOf(3_000L, 2_000L, 1_000L), recentAmounts(trips, ExpenseType.OTHER, limit = 5))
        assertEquals(listOf(3_000L), recentAmounts(trips, ExpenseType.OTHER, limit = 1))
        assertTrue(recentAmounts(trips, ExpenseType.OTHER, limit = 0).isEmpty())
        val odd = listOf(trip("b", day, cost(ExpenseType.OTHER, 0, day + 9), cost(ExpenseType.OTHER, EXPENSE_LIMIT_WON + 1, day + 8),
            cost(ExpenseType.OTHER, EXPENSE_LIMIT_WON, day + 7)))
        assertEquals("칸에 넣을 수 없는 금액은 빠짐", listOf(EXPENSE_LIMIT_WON), recentAmounts(odd, ExpenseType.OTHER))
    }

    @Test fun recentAmountsBreakATieByTheLaterTrip() {
        val at = day + 9 * hour
        val trips = listOf(trip("early", day, cost(ExpenseType.PARKING, 2_000, at)), trip("late", day + hour, cost(ExpenseType.PARKING, 5_000, at)))
        assertEquals(listOf(5_000L, 2_000L), recentAmounts(trips, ExpenseType.PARKING))
        assertEquals("목록 순서와 상관없음", listOf(5_000L, 2_000L), recentAmounts(trips.reversed(), ExpenseType.PARKING))
    }

    @Test fun anAddingChipAddsToTheFieldAndStopsAtItsLimit() {
        assertEquals("빈 칸은 0", "1000", quickAmount("", 1_000, EXPENSE_LIMIT_WON))
        assertEquals("0", "5000", quickAmount("0", 5_000, EXPENSE_LIMIT_WON))
        assertEquals("더하기", "9500", quickAmount("4500", 5_000, EXPENSE_LIMIT_WON))
        assertEquals("한도에서 멈춤", "10000000", quickAmount("9995000", 10_000, EXPENSE_LIMIT_WON))
        assertEquals("한도 그대로", "10000000", quickAmount("10000000", 1_000, EXPENSE_LIMIT_WON))
        assertEquals("넘은 값은 한도로", "10000000", quickAmount("99999999", 1_000, EXPENSE_LIMIT_WON))
        assertEquals("Long을 넘는 자릿수", "10000000", quickAmount("9".repeat(30), 1_000, EXPENSE_LIMIT_WON))
        assertEquals("구분 기호는 무시", "5500", quickAmount("4,500원", 1_000, EXPENSE_LIMIT_WON))
        assertEquals("앞의 0", "1007", quickAmount("007", 1_000, EXPENSE_LIMIT_WON))
        assertEquals("빼지 않음", "4500", quickAmount("4500", -1_000, EXPENSE_LIMIT_WON))
        assertEquals("다른 한도", "20000", quickAmount("15000", 10_000, 20_000))
        // What a chip leaves is always an amount the field accepts.
        listOf("", "4500", "9995000", "99999999").forEach { start ->
            QUICK_ADD_STEPS.forEach { add -> assertTrue("$start + $add", moneyInput(quickAmount(start, add, EXPENSE_LIMIT_WON), 1, EXPENSE_LIMIT_WON, "").ok) }
        }
    }

    @Test fun theChipsSayTheirAmountsAsTheChargeCardDoes() {
        assertEquals(listOf(1_000L, 5_000L, 10_000L), QUICK_ADD_STEPS)
        assertEquals(listOf("+1,000", "+5,000", "+10,000"), QUICK_ADD_STEPS.map(::quickAddText))
        assertEquals("1,000원 더하기", quickAddSpoken(1_000))
        assertEquals("10,000원 더하기", quickAddSpoken(10_000))
        // The spoken form says the figure the chip shows.
        QUICK_ADD_STEPS.forEach { add -> assertTrue(quickAddSpoken(add).contains(quickAddText(add).removePrefix("+"))) }
        // The field's limit is the one its hint names and the repository keeps.
        assertTrue(LogCopy.AMOUNT_HINT.endsWith(wonReading(EXPENSE_LIMIT_WON)))
        assertTrue(moneyInput(EXPENSE_LIMIT_WON.toString(), 1, EXPENSE_LIMIT_WON, "").ok)
    }

    @Test fun theChipsWordsFitAChip() {
        // A chip is one labelLarge line; the longest recent amount is the field's limit.
        (QUICK_ADD_STEPS.map(::quickAddText) + formatWon(EXPENSE_LIMIT_WON)).forEach { assertOneLine(it, 8.0) }
        listOf(LogCopy.QUICK_SET_ACTION, LogCopy.QUICK_ADD_ACTION).forEach { assertOneLine(it, CARD_LINE_UNITS) }
        QUICK_ADD_STEPS.forEach { assertOneLine(quickAddSpoken(it), CARD_LINE_UNITS) }
    }
}
