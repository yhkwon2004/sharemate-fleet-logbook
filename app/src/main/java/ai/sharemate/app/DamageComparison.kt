package ai.sharemate.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class ComparisonStatus { RETAKE, REVIEW, NO_CANDIDATE }

data class ComparisonBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val score: Float)

data class ComparisonResult(val status: ComparisonStatus, val message: String, val boxes: List<ComparisonBox> = emptyList())

/** 기기 내 사진 비교 · 실험 기능. score is normalized pixel-change strength, never damage probability. */
object DamageComparison {
    fun compare(before: IntArray, after: IntArray, width: Int, height: Int): ComparisonResult {
        require(width in 64..640 && height in 64..640 && before.size == width * height && after.size == before.size) {
            "같은 크기의 64~640 픽셀 사진 두 장이 필요합니다."
        }
        require(before.all { it in 0..255 } && after.all { it in 0..255 }) { "사진 밝기 값이 올바르지 않습니다." }
        val a = quality(before, width, height)
        val b = quality(after, width, height)
        if (a.mean !in 30.0..225.0 || b.mean !in 30.0..225.0 || a.clipped > .22 || b.clipped > .22) {
            return retake("사진이 너무 어둡거나 빛이 강해요. 그늘에서 같은 부위를 다시 촬영해 주세요.")
        }
        if (a.detail < 4 || b.detail < 4 || a.deviation < 8 || b.deviation < 8 ||
            min(a.detail, b.detail) / max(a.detail, b.detail) < .45) {
            return retake("사진이 흐리거나 비교할 특징이 부족해요. 초점을 맞추고 차체 경계가 보이도록 다시 촬영해 주세요.")
        }
        val scale = a.deviation / b.deviation
        if (abs(a.mean - b.mean) > 40 || scale !in .7..1.4) {
            return retake("두 사진의 조명이 많이 달라요. 같은 장소와 밝기에서 다시 촬영해 주세요.")
        }
        val normalized = DoubleArray(after.size) { (after[it] - b.mean) * scale + a.mean }
        val shift = 6
        val margin = shift + 3
        fun cost(dx: Int, dy: Int, left: Int, top: Int, right: Int, bottom: Int): Double {
            var total = 0.0
            var count = 0
            for (y in top until bottom step 3) for (x in left until right step 3) {
                total += min(40.0, abs(before[y * width + x] - normalized[(y + dy) * width + x + dx]))
                count++
            }
            return total / count
        }
        var dx = 0
        var dy = 0
        var best = Double.MAX_VALUE
        for (y in -shift..shift) for (x in -shift..shift) {
            val value = cost(x, y, margin, margin, width - margin, height - margin) + (abs(x) + abs(y)) * .001
            if (value < best) { best = value; dx = x; dy = y }
        }
        if (abs(dx) == shift || abs(dy) == shift || best > 10) {
            return retake("구도나 촬영 거리가 달라 비교하기 어려워요. 이전 사진에 맞춰 같은 부위를 다시 촬영해 주세요.")
        }
        // shortcut: translation-only alignment; perspective/rotation needs calibrated matching or a validated model.
        for (row in 0..1) for (column in 0..1) {
            val left = if (column == 0) margin else width / 2
            val right = if (column == 0) width / 2 else width - margin
            val top = if (row == 0) margin else height / 2
            val bottom = if (row == 0) height / 2 else height - margin
            val aligned = cost(dx, dy, left, top, right, bottom)
            var localBest = aligned
            var localDistance = 0
            for (y in -2..2) for (x in -2..2) {
                val local = cost(dx + x, dy + y, left, top, right, bottom)
                if (local < localBest) { localBest = local; localDistance = max(abs(x), abs(y)) }
            }
            if (localDistance == 2 && aligned > 3 && localBest < aligned * .65) {
                return retake("촬영 각도가 달라 보여요. 카메라를 이전 사진과 같은 위치에 두고 다시 촬영해 주세요.")
            }
        }
        val mask = BooleanArray(before.size)
        val differences = FloatArray(before.size)
        var changed = 0
        var compared = 0
        for (y in margin until height - margin) for (x in margin until width - margin) {
            val difference = abs(before[y * width + x] - normalized[(y + dy) * width + x + dx]).toFloat()
            val index = (y + dy) * width + x + dx
            differences[index] = difference
            if (difference > max(22.0, best * 4)) { mask[index] = true; changed++ }
            compared++
        }
        if (changed > compared * .08) return retake("변화 영역이 너무 넓어요. 반사광·배경·구도를 확인하고 다시 촬영해 주세요.")
        val queue = IntArray(before.size)
        val boxes = mutableListOf<ComparisonBox>()
        for (start in mask.indices) {
            if (!mask[start]) continue
            var head = 0
            var tail = 1
            queue[0] = start
            mask[start] = false
            var left = start % width
            var right = left
            var top = start / width
            var bottom = top
            var strength = 0f
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
                strength += differences[index]
                for (ny in max(0, y - 1)..min(height - 1, y + 1)) for (nx in max(0, x - 1)..min(width - 1, x + 1)) {
                    val neighbor = ny * width + nx
                    if (mask[neighbor]) { mask[neighbor] = false; queue[tail++] = neighbor }
                }
            }
            if (tail < 5 || max(right - left, bottom - top) < 7) continue
            if ((right - left + 1) * (bottom - top + 1) > compared * .12 || boxes.size >= 12) {
                return retake("변화가 넓게 퍼져 있어요. 반사광이나 촬영 각도를 줄이고 같은 부위를 다시 촬영해 주세요.")
            }
            boxes += ComparisonBox(
                max(0, left - 2).toFloat() / width, max(0, top - 2).toFloat() / height,
                min(width, right + 3).toFloat() / width, min(height, bottom + 3).toFloat() / height,
                (strength / tail / 255f).coerceIn(0f, 1f),
            )
        }
        return if (boxes.isEmpty()) ComparisonResult(ComparisonStatus.NO_CANDIDATE,
            "뚜렷한 변화 후보를 찾지 못했어요. 흠집이 없다는 뜻은 아니며, 가장자리와 미세 흠집은 직접 확인해 주세요.")
        else ComparisonResult(ComparisonStatus.REVIEW,
            "이전 사진과 달라진 영역 ${boxes.size}곳을 표시했어요. 반사광일 수도 있으니 원본을 보고 직접 확인해 주세요.", boxes)
    }

    private data class Quality(val mean: Double, val deviation: Double, val detail: Double, val clipped: Double)

    private fun quality(pixels: IntArray, width: Int, height: Int): Quality {
        val mean = pixels.average()
        var variance = 0.0
        var clipped = 0
        var detail = 0.0
        for (value in pixels) { variance += (value - mean) * (value - mean); if (value <= 8 || value >= 247) clipped++ }
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            detail += abs(4 * pixels[i] - pixels[i - 1] - pixels[i + 1] - pixels[i - width] - pixels[i + width])
        }
        return Quality(mean, sqrt(variance / pixels.size), detail / ((width - 2) * (height - 2)), clipped.toDouble() / pixels.size)
    }

    private fun retake(message: String) = ComparisonResult(ComparisonStatus.RETAKE, message)
}
