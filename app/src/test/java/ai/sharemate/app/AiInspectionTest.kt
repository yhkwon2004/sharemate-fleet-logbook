package ai.sharemate.app

import org.junit.Assert.*
import org.junit.Test

class AiInspectionTest {
    private val finding = AiDamageFinding("SCRATCH", "문 표면의 선 모양 흔적을 확인해 주세요.", .1f, .2f, .6f, .7f)
    private val report = AiDamageReport(InspectionStage.AFTER, EvidenceView.FRONT, "a".repeat(64), "b".repeat(64),
        ComparisonStatus.REVIEW, "긁힘 의심 흔적을 직접 확인해 주세요.", listOf(finding), "gpt-4.1-mini", 1000)

    private fun rejected(value: AiDamageReport) = assertTrue(runCatching { validateAiReport(value) }.exceptionOrNull() is IllegalArgumentException)

    @Test fun validReviewAndEmptyResultsAreAcceptedWithoutDamageFreeVerdict() {
        validateAiReport(report)
        listOf(ComparisonStatus.RETAKE, ComparisonStatus.NO_CANDIDATE).forEach {
            validateAiReport(report.copy(status = it, findings = emptyList()))
        }
        validateAiReport(report.copy(stage = InspectionStage.BEFORE, referenceSha256 = null))
    }

    @Test fun nonFiniteOutOfBoundsAndEmptyBoxesAreRejected() {
        listOf(finding.copy(left = Float.NaN), finding.copy(top = Float.POSITIVE_INFINITY),
            finding.copy(right = Float.NEGATIVE_INFINITY), finding.copy(left = -.1f), finding.copy(bottom = 1.1f),
            finding.copy(left = finding.right), finding.copy(top = finding.bottom), finding.copy(right = .01f)).forEach {
            rejected(report.copy(findings = listOf(it)))
        }
    }

    @Test fun reviewRequiresFindingsAndOtherStatusesCannotHideFindings() {
        rejected(report.copy(findings = emptyList()))
        rejected(report.copy(status = ComparisonStatus.RETAKE))
        rejected(report.copy(status = ComparisonStatus.NO_CANDIDATE))
        rejected(report.copy(findings = List(13) { finding }))
    }

    @Test fun canonicalPhotoDigestsAndReferenceStageAreRequired() {
        listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), "../photo.jpg").forEach {
            rejected(report.copy(sha256 = it))
            rejected(report.copy(referenceSha256 = it))
        }
        rejected(report.copy(stage = InspectionStage.BEFORE))
        rejected(report.copy(view = EvidenceView.WALKAROUND))
    }

    @Test fun unboundedOrEmptyDescriptionsAndUnknownKindsAreRejected() {
        rejected(report.copy(summary = " "))
        rejected(report.copy(summary = "가".repeat(1001)))
        rejected(report.copy(model = ""))
        rejected(report.copy(analyzedAt = 0))
        rejected(report.copy(findings = listOf(finding.copy(kind = "CONFIRMED_DAMAGE"))))
        rejected(report.copy(findings = listOf(finding.copy(description = "가".repeat(251)))))
    }
}
