package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The words of 월간·Excel and 차량 on the car (2.12): the copy built from data (the settlement card, the tax card's last line,
 * the export dialog) and the one-line rule for every static line of [ReportCopy] and [VehicleCopy].
 */
class ReportCopyTest {
    /** A line set in titleSmall (19 sp) gets the width of a 26-unit bodySmall (16 sp) line: 26 × 16 / 19 units. */
    private val titleSmallLineUnits = CARD_LINE_UNITS * 16 / 19

    @Test fun theSettlementCardNamesItsRateAndThePeriodItSums() {
        assertEquals(SettlementCopy("km당 300원", "이번 달 정산 참고 합계", "이번 달 업무용 운행이 없어요"), settlementCopy(300, "이번 달"))
        assertEquals("월간·Excel은 화면의 기간", SettlementCopy("km당 1,200원", "정산 참고 합계", "이 기간 업무용 운행이 없어요"), settlementCopy(1_200, null))
        assertNull("단가가 없으면 태그도 없음", settlementCopy(0, null).tag)
        listOf(settlementCopy(300, "이번 달"), settlementCopy(1_200, null)).forEach { assertOneLine(it.empty, CARD_LINE_UNITS) }
    }

    @Test fun theTaxCardSaysHowTheTwoAmountsCompareInOneLine() {
        assertEquals("두 계산의 차이 1,200,000원", taxDifferenceLine(1_200_000, .9))
        assertEquals("비율 50% 적용액이 더 작아요", taxDifferenceLine(-300_000, .5))
        assertEquals("두 계산액이 같아요", taxDifferenceLine(0, .8))
        listOf(taxDifferenceLine(123_456_789, .99), taxDifferenceLine(-1, .05), taxDifferenceLine(0, 1.0)).forEach { assertOneLine(it, titleSmallLineUnits) }
    }

    @Test fun theExportDialogListsOnlyWhatTheFileWillMiss() {
        val unclassified = "미분류 12.0 km는 업무 거리에서 빠져요"
        val driverless = "사용자 미지정 3건은 ④란이 비어요"
        assertEquals(listOf(unclassified), exportConfirmLines(12.0, 0))
        assertEquals(listOf(driverless), exportConfirmLines(0.0, 3))
        assertEquals("미분류가 먼저", listOf(unclassified, driverless), exportConfirmLines(12.0, 3))
        assertEquals("천 단위 구분", "미분류 1,234.5 km는 업무 거리에서 빠져요", exportConfirmLines(1_234.5, 0).single())
        // 2.13: they are the lines of a CarDialog, one sentence each at its 600-dp width.
        exportConfirmLines(12_345.6, 999).forEach { assertOneLine(it, DIALOG_LINE_UNITS) }
        assertOneLine(ReportCopy.CONFIRM_TITLE, DIALOG_LINE_UNITS)
    }

    @Test fun theTaxCardBlockersAndBasisFitTheirPlaces() {
        val today = LocalDate.of(2026, 11, 9)
        listOfNotNull(taxCardBlocker(null, null, 2026, today), taxCardBlocker(15_000_000, null, 2025, today), taxCardBlocker(15_000_000, null, 2026, today))
            .forEach { assertOneLine(it, titleSmallLineUnits) }
        listOf(taxBasis(2026, today), taxBasis(2025, today)).forEach { assertOneLine(it, CARD_LINE_UNITS) }
    }

    @Test fun theRangeTagSaysTheDatesInTheSteppersWords() {
        val today = LocalDate.of(2026, 11, 12)
        assertEquals("한 달", "10월 1일 ~ 31일 · 9건", reportRangeLabel(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), 9, today))
        assertEquals("올해는 오늘까지", "1월 1일 ~ 11월 12일 · 54건", reportRangeLabel(LocalDate.of(2026, 1, 1), today, 54, today))
        assertEquals("이번 달은 오늘까지", "11월 1일 ~ 12일 · 0건", reportRangeLabel(LocalDate.of(2026, 11, 1), today, 0, today))
        assertEquals("다른 해는 연도부터", "2025년 10월 1일 ~ 31일 · 9건", reportRangeLabel(LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 31), 9, today))
        assertEquals("2025년 1월 1일 ~ 12월 31일 · 120건", reportRangeLabel(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), 120, today))
        assertEquals("하루면 날짜 한 번", "11월 1일 · 2건", reportRangeLabel(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1), 2, today))
        // A tag beside another (시연 데이터) on the narrowest page: the longest the stepper reaches still fits a card line.
        listOf(reportRangeLabel(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), 20_000, today), ReportCopy.RANGE_DEMO).forEach { assertOneLine(it, CARD_LINE_UNITS) }
    }

    @Test fun theExpenseListLeavesTypesWithoutExpensesOut() {
        val at = 1_790_000_000_000L
        fun trip(seed: String, vararg expenses: Pair<ExpenseType, Long>) = Trip(seed, at, at + 600_000, 100.0, 110.0,
            expenses = expenses.map { (type, won) -> TripExpense(type, won, at, EditSource.DRIVER) })
        val trips = listOf(trip("a", ExpenseType.PARKING to 4_500, ExpenseType.FUEL to 45_000), trip("b", ExpenseType.PARKING to 3_600), trip("c"))
        assertEquals("종류 순서, 0원인 종류는 빠짐", listOf(ExpenseType.FUEL to 45_000L, ExpenseType.PARKING to 8_100L), expenseTypeSums(trips))
        assertTrue("경비가 없으면 행도 없음", expenseTypeSums(listOf(trip("d"))).isEmpty())
    }

    @Test fun noCarCopyWritesTheWonSignOrAPerKmRateTheOldWay() {
        // The one money style (DESIGN_2_13_SPEC §5): an amount is drawn, never written with ₩, and a rate reads "km당 150원".
        listOf(ReportCopy, VehicleCopy, SettingsCopy).flatMap { copy ->
            copy.javaClass.declaredFields.filter { it.type == String::class.java || List::class.java.isAssignableFrom(it.type) }.flatMap { field ->
                field.isAccessible = true
                when (val value = field.get(copy)) { is String -> listOf(value); is List<*> -> value.filterIsInstance<String>(); else -> emptyList() }
            }
        }.also { assertTrue("상수를 읽음", it.size > 40) }.forEach { line ->
            assertFalse("₩ 없음: $line", line.contains('₩'))
            assertFalse("원/km 없음: $line", line.contains("원/km"))
        }
        assertEquals("km당 150원", settlementCopy(150, null).tag)
    }

    @Test fun everyLineOfTheReportPageIsOneLine() {
        with(ReportCopy) {
            listOf(CLOSE_NOTHING, CLOSE_WAITING, USAGE_EMPTY, EXPENSE_EMPTY, PREVIEW_FAILED_LINE, PREVIEW_EMPTY_LINE, SAVE_BAR_LINE, AUDIT_LINE)
                .forEach { assertOneLine(it, CARD_LINE_UNITS) }
            listOf(CLOSE_NOTE, USAGE_NOTE, EXPENSE_NOTE, SETTLEMENT_NOTE, PREVIEW_NOTE, AUDIT_NOTE, TAX_CAP_NOTE, TAX_NOTE).forEach { assertOneLine(it, INFO_LINE_UNITS) }
            listOf(SETTLEMENT_ASK, PREVIEW_FAILED).forEach { assertOneLine(it, titleSmallLineUnits) }
            listOf(RATIO_FORMULA, PREVIEW_DEMO, NOTHING_TO_SAVE, TAX_COST, TAX_WITH_LOG, TAX_WITHOUT_LOG, EXPENSE_TOTAL, RANGE_DEMO, CONFIRM_TITLE, CONFIRM_SAVE, CONFIRM_TIDY)
                .forEach { assertOneLine(it, CARD_LINE_UNITS) }
            // The one word for a trip without its person (2.13), the issue's own label.
            assertTrue(SETTLEMENT_NOTE.startsWith(IssueType.NO_DRIVER.label))
            // What an ⓘ row unfolds may wrap, but it is still written without a final period.
            (listOf(CLOSE_NOTE_MORE) + EXPENSE_NOTE_MORE + SETTLEMENT_NOTE_MORE + PREVIEW_NOTE_MORE + AUDIT_NOTE_MORE + TAX_CAP_MORE + TAX_NOTE_MORE)
                .forEach { assertFalse(it, it.trimEnd().endsWith(".")) }
            assertShortTitles(RATIO_TITLE, USAGE_TITLE, EXPENSE_TITLE, SETTLEMENT_TITLE, AUDIT_TITLE, TAX_TITLE)
        }
    }

    @Test fun everyLineOfTheVehiclePageIsOneLine() {
        with(VehicleCopy) {
            listOf(CARE_LINE, MAINTENANCE_EMPTY, MAINTENANCE_MORE, MAINTENANCE_OIL, PHONE_HOW, PHONE_KEY, PHONE_ADDRESS, INCIDENTS_EMPTY)
                .forEach { assertOneLine(it, CARD_LINE_UNITS) }
            listOf(FOOTER, FOOTER_DEMO, PHONE_NOTE).forEach { assertOneLine(it, INFO_LINE_UNITS) }
            listOf(CARE_EMPTY, CARE_WAITING, RESERVATIONS_EMPTY, NO_TRIP_YET, PHONE_ANOTHER, PHONE_EXPIRED, PHONE_CODE, MAINTENANCE_KIND, PHONE_ADDRESS_PENDING)
                .forEach { assertOneLine(it, titleSmallLineUnits) }
            listOf(NO_PLATE, ODOMETER, GEAR, CHECK, SOURCE, SOURCE_DEMO, VIN, BATTERY_UNKNOWN).forEach { assertOneLine(it, CARD_LINE_UNITS) }
            PHONE_NOTE_MORE.forEach { assertFalse(it, it.trimEnd().endsWith(".")) }
            assertShortTitles(DETAILS_TITLE, CARE_TITLE, MAINTENANCE_TITLE, PHONE_TITLE, RESERVATIONS_TITLE, INCIDENTS_TITLE)
        }
        // The spoken forms say at least what the row shows.
        assertTrue(VehicleCopy.SPOKEN_ODOMETER.startsWith(VehicleCopy.ODOMETER))
        assertTrue(VehicleCopy.SPOKEN_GEAR.endsWith(VehicleCopy.GEAR))
        assertTrue(VehicleCopy.SPOKEN_PHONE_KEY.contains(VehicleCopy.PHONE_KEY))
        assertTrue(VehicleCopy.SPOKEN_PHONE_ADDRESS.contains(VehicleCopy.PHONE_ADDRESS))
    }

    /** Titles keep to eight syllables (spec §9): a card title is a name, never a sentence. */
    private fun assertShortTitles(vararg titles: String) = titles.forEach { title ->
        assertTrue("$title: 8음절 이하", title.count { it in '가'..'힣' } <= 8)
        assertOneLine(title, CARD_LINE_UNITS)
    }
}
