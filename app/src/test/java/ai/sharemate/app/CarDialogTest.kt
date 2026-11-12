package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A car dialog's lines (docs/design/DESIGN_2_13_SPEC.md §1.1): one sentence per line, no final period. */
class CarDialogTest {
    @Test fun aMessageBecomesOneLinePerSentenceWithoutTheFinalPeriod() {
        assertEquals(listOf("저장하지 못했어요", "다시 시도해 주세요"), dialogLines("저장하지 못했어요. 다시 시도해 주세요."))
        assertEquals("숫자 속 마침표는 그대로", listOf("JPEG 사진만 첨부할 수 있어요 (최대 1.5MB)"), dialogLines("JPEG 사진만 첨부할 수 있어요 (최대 1.5MB)."))
        assertEquals(listOf("입력 내용을 확인해 주세요"), dialogLines("입력 내용을 확인해 주세요."))
        assertEquals("물음표는 남김", listOf("지울까요?", "되돌릴 수 없어요"), dialogLines("지울까요? 되돌릴 수 없어요."))
        assertEquals("줄바꿈도 문장 경계", listOf("파일 선택기가 없어요", "/storage/x.xlsx"), dialogLines("파일 선택기가 없어요.\n/storage/x.xlsx"))
        assertEquals("마침표 없는 문장은 그대로", listOf("주행 상태가 바뀌어 파일 선택을 중단했어요", "정차 후 다시 저장해 주세요"),
            dialogLines("주행 상태가 바뀌어 파일 선택을 중단했어요. 정차 후 다시 저장해 주세요."))
        assertTrue(dialogLines("").isEmpty())
        assertTrue("공백뿐이면 비어 있음", dialogLines("  \n ").isEmpty())
    }

    @Test fun theShellsOwnMessagesFitTheDialogLine() {
        // The messages the shell writes itself (LogbookApp's act and export); the repository's stay as written.
        listOf("입력 내용을 확인해 주세요.", "저장하지 못했어요. 다시 시도해 주세요.", "주행 상태가 바뀌어 파일 선택을 중단했어요. 정차 후 다시 저장해 주세요.")
            .flatMap(::dialogLines).forEach { assertOneLine(it, DIALOG_LINE_UNITS) }
    }
}
