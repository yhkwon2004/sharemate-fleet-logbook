package ai.sharemate.app

import org.junit.Assert.*
import org.junit.Test

class InspectionTest {
    private fun evidence(stage: InspectionStage) = EvidenceView.entries.mapIndexed { index, view ->
        EvidenceItem("00000000-0000-0000-0000-00000000000$index.${if (view == EvidenceView.WALKAROUND) "mp4" else "jpg"}", stage, view, 100 + index.toLong(), 100, "a".repeat(64))
    }

    @Test fun fourPhotosCannotFinishWithoutVideoAndManualReview() {
        val photos = InspectionRecord(evidence = evidence(InspectionStage.BEFORE).dropLast(1))
        assertFalse(photos.canComplete(InspectionStage.BEFORE, true))
        val full = photos.copy(evidence = evidence(InspectionStage.BEFORE))
        assertFalse(full.canComplete(InspectionStage.BEFORE, false))
        assertTrue(full.canComplete(InspectionStage.BEFORE, true))
        assertFalse(full.copy(beforeComplete = true).canComplete(InspectionStage.BEFORE, true))
    }

    @Test fun afterRequiresCompletedBeforeAndCannotCompleteTwice() {
        val record = InspectionRecord(evidence = evidence(InspectionStage.AFTER))
        assertFalse(record.canComplete(InspectionStage.AFTER, true))
        assertTrue(record.copy(beforeComplete = true).canComplete(InspectionStage.AFTER, true))
        assertFalse(record.copy(beforeComplete = true, afterComplete = true).canComplete(InspectionStage.AFTER, true))
    }

    @Test fun retakesDoNotCountAsAnotherSideAndLatestOriginalIsSelected() {
        val original = evidence(InspectionStage.BEFORE).first()
        val retake = original.copy(name = "00000000-0000-0000-0000-000000000099.jpg", capturedAt = 999)
        val record = InspectionRecord(evidence = listOf(original, retake))
        assertEquals(1, record.capturedCount(InspectionStage.BEFORE))
        assertEquals(retake, record.latest(InspectionStage.BEFORE, EvidenceView.FRONT))
        assertNull(record.latest(InspectionStage.AFTER, EvidenceView.FRONT))
    }

    @Test fun recordIdentifiersRejectPathsAndNoncanonicalUuidForms() {
        assertTrue(validUuid("00000000-0000-0000-0000-000000000001"))
        assertFalse(validUuid("../../../outside"))
        assertFalse(validUuid("1-1-1-1-1"))
    }
}
