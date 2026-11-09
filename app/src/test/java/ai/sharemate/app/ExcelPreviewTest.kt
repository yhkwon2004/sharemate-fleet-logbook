package ai.sharemate.app

import androidx.compose.ui.unit.Dp
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

    @Test fun aPageHoldsTheRowsThatFitTheTablesOwnHeightOrAFixedPageWhereThePageScrolls() {
        // The header takes 66 dp (two label lines in 12-dp padding and the card's edges); a row 72 + 1 on the car, 64 + 1 compact.
        assertEquals("표가 받은 높이에 맞춰 담음", 5, previewRowsPerPage(452.dp, compact = false))
        assertEquals("머리글과 한 행이 딱 맞는 높이", 1, previewRowsPerPage(139.dp, compact = false))
        assertEquals("한 행도 안 들어가도 한 행은 보임", 1, previewRowsPerPage(40.dp, compact = false))
        assertEquals("아무리 높아도 최대 8행", PREVIEW_MAX_ROWS, previewRowsPerPage(2_000.dp, compact = false))
        assertEquals("좁은 차량 창은 64 dp 행", 6, previewRowsPerPage(460.dp, compact = true))
        assertEquals("큰 글꼴은 두 줄 행이 높아져 덜 담음", 4, previewRowsPerPage(452.dp, compact = false, fontScale = 1.3f))
        assertEquals("스크롤되는 휴대폰 카드는 고정 3행", 3, previewRowsPerPage(null, compact = true))
        assertEquals(6, previewRowsPerPage(null, compact = false))
        assertEquals("높이가 무한이면 고정 개수", 3, previewRowsPerPage(Dp.Infinity, compact = true))
        assertEquals(6, previewRowsPerPage(Dp.Infinity, compact = false))
    }

    @Test fun thePagerAndTheHintTakeTheirShareOfTheTablesRoom() {
        assertEquals("간격 12 + 버튼 56 + 간격 12 + 안내 한 줄 16", 96f, previewPagerHeight(56.dp).value, .01f)
        assertEquals("휴대폰 48 dp 버튼", 88f, previewPagerHeight(48.dp).value, .01f)
        assertEquals("큰 글꼴은 안내 줄만 커짐", 100.8f, previewPagerHeight(56.dp, 1.3f).value, .01f)
    }
}
