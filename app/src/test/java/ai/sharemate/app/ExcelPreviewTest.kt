package ai.sharemate.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcelPreviewTest {
    @Test fun previewCellsPrintNumbersTheWayTheSheetDoes() {
        assertEquals("12,406.4", previewCell(12_406.4))
        assertEquals("1,234.0", previewCell(1234f))
        assertEquals("55,000", previewCell(55_000L))
        assertEquals("7", previewCell(7))
        assertEquals("거래처", previewCell("거래처"))
        assertEquals("", previewCell(""))
    }

    @Test fun columnWidthsKeepDatesWideAndTheRemarksColumnWidest() {
        val columns = listOf("운행일자", "출발 일시", "출발지", "거리(km)", "비고")
        assertEquals(listOf(180.dp, 180.dp, 170.dp, 170.dp, 360.dp), excelColumnWidths(columns, compact = false))
        assertEquals("휴대폰은 좁은 열", listOf(180.dp, 180.dp, 136.dp, 136.dp, 260.dp), excelColumnWidths(columns, compact = true))
        assertEquals("마지막 열은 날짜여도 비고 폭", listOf(360.dp), excelColumnWidths(listOf("운행일자"), compact = false))
        assertTrue(excelColumnWidths(emptyList(), compact = false).isEmpty())
    }

    @Test fun fiveSheetTabsShareThePhoneCardButKeepTheirWidthOnTheCar() {
        assertEquals("72 dp × 5 + 4 dp × 4", 376.dp, sheetTabsNaturalWidth(5))
        assertTrue("휴대폰 카드(약 335 dp)에서는 나눠 씀", 335.dp < sheetTabsNaturalWidth(5))
        assertTrue("차량 760 dp 열에서는 자연 폭", 760.dp >= sheetTabsNaturalWidth(5))
        assertEquals(72.dp, sheetTabsNaturalWidth(1))
    }
}
