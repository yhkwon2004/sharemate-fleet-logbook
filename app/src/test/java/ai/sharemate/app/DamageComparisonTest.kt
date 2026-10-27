package ai.sharemate.app

import org.junit.Assert.*
import org.junit.Test

class DamageComparisonTest {
    private val width = 128
    private val height = 128
    private fun panel(): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        110 + (x * 13 + y * 7 + x * y % 11) % 45
    }
    private fun compare(before: IntArray, after: IntArray) = DamageComparison.compare(before, after, width, height)

    @Test fun unchangedPhotoNeverClaimsDamageFree() {
        val image = panel()
        val result = compare(image, image.copyOf())
        assertEquals(ComparisonStatus.NO_CANDIDATE, result.status)
        assertTrue(result.boxes.isEmpty())
        assertTrue(result.message.contains("흠집이 없다는 뜻은 아니"))
    }

    @Test fun smallTranslationAndModerateExposureChangeAreAligned() {
        val image = panel()
        val moved = IntArray(image.size) { index ->
            val x = (index % width - 3).coerceIn(0, width - 1)
            val y = (index / width + 2).coerceIn(0, height - 1)
            image[y * width + x] + 18
        }
        assertEquals(ComparisonStatus.NO_CANDIDATE, compare(image, moved).status)
    }

    @Test fun addedLineProducesBoundedCandidateAtItsLocation() {
        val before = panel()
        val after = before.copyOf()
        for (x in 40..85) for (y in 59..60) after[y * width + x] = 220
        val result = compare(before, after)
        assertEquals(ComparisonStatus.REVIEW, result.status)
        assertEquals(1, result.boxes.size)
        val box = result.boxes.single()
        assertTrue(box.left <= 40f / width && box.right >= 86f / width)
        assertTrue(box.top <= 59f / height && box.bottom >= 61f / height)
        assertTrue(listOf(box.left, box.top, box.right, box.bottom, box.score).all { it in 0f..1f })
    }

    @Test fun darknessGlareAndFeaturelessImagesRequestRetake() {
        val image = panel()
        listOf(IntArray(image.size) { 5 }, IntArray(image.size) { 253 }, IntArray(image.size) { 130 }).forEach {
            assertEquals(ComparisonStatus.RETAKE, compare(image, it).status)
        }
    }

    @Test fun blurredImageRequestsRetakeInsteadOfReportingDamage() {
        val image = panel()
        val blurred = IntArray(image.size) { index ->
            val x = index % width
            val y = index / width
            var total = 0
            for (dy in -3..3) for (dx in -3..3) total += image[(y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)]
            total / 49
        }
        assertEquals(ComparisonStatus.RETAKE, compare(image, blurred).status)
    }

    @Test fun DifferentSceneAndRotatedPerspectiveRequestRetake() {
        val image = panel()
        val other = IntArray(image.size) { i -> 100 + (i * 31 + i / width * 19) % 65 }
        assertEquals(ComparisonStatus.RETAKE, compare(image, other).status)
        val skewed = IntArray(image.size) { index ->
            val y = index / width
            val x = (index % width + (y - height / 2) / 10).coerceIn(0, width - 1)
            image[y * width + x]
        }
        assertEquals(ComparisonStatus.RETAKE, compare(image, skewed).status)
    }

    @Test fun inputSizeAndPixelRangesAreValidatedBeforeAllocations() {
        assertTrue(runCatching { DamageComparison.compare(IntArray(0), IntArray(0), Int.MAX_VALUE, Int.MAX_VALUE) }.isFailure)
        assertTrue(runCatching { DamageComparison.compare(IntArray(64), IntArray(64), 128, 128) }.isFailure)
        val invalid = panel().apply { this[0] = 256 }
        assertTrue(runCatching { compare(invalid, panel()) }.isFailure)
    }
}
