package ai.sharemate.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The pairing QR of 2.15 (docs/design/DESIGN_2_15.md §6.2–§6.3) on the JVM: what the car encodes ([qrMatrix]) and how it lays
 * the modules on its tile, read back by what the phone runs on a picture ([decodeQr], [decodeQrPicture]): at several scales,
 * inverted, and set in a larger noisy frame.
 */
class QrCodeTest {
    private val key = "499efa1aa0f8445c3832ad7c3b911b20a0d9e04f3d66d03591d84b964802b21e"
    private val twoAddresses = pairingLinkText(listOf("10.0.2.15:9247", "10.0.2.16:9247"), "455426", key)!!
    private val fourLongest = pairingLinkText(List(4) { "255.255.255.25$it:65535" }, "999999", key)!!

    private val white = 0xFFFFFFFF.toInt()
    private val dark = 0xFF0B1220.toInt()

    /** [matrix] as ARGB pixels: [scale] pixels a module inside a [quiet]-module white margin, or the other way round. */
    private fun render(matrix: QrMatrix, scale: Int, quiet: Int = QR_QUIET_MODULES, inverted: Boolean = false): Triple<IntArray, Int, Int> {
        val side = (matrix.size + 2 * quiet) * scale
        val pixels = IntArray(side * side) { index ->
            val x = index % side / scale - quiet
            val y = index / side / scale - quiet
            val on = x in 0 until matrix.size && y in 0 until matrix.size && matrix[x, y]
            if (on != inverted) dark else white
        }
        return Triple(pixels, side, side)
    }

    /** The car's own tile, [sidePx] square: white, the modules where [qrModulePlacement] and [qrRuns] put them. */
    private fun tile(matrix: QrMatrix, sidePx: Int): IntArray {
        val pixels = IntArray(sidePx * sidePx) { white }
        val (module, origin) = qrModulePlacement(matrix.size, sidePx.toFloat())
        qrRuns(matrix).forEach { (y, x, length) ->
            for (py in origin + y * module until origin + (y + 1) * module)
                for (px in origin + x * module until origin + (x + length) * module) pixels[py * sidePx + px] = dark
        }
        return pixels
    }

    @Test fun theLinkEncodesAtErrorCorrectionMWithoutAMargin() {
        val matrix = qrMatrix(twoAddresses)!!
        assertEquals("버전 n은 17 + 4n 모듈", 0, (matrix.size - 17) % 4)
        // No margin: the finder pattern's dark corner sits on the matrix's own corner.
        assertTrue(matrix[0, 0] && matrix[matrix.size - 1, 0] && matrix[0, matrix.size - 1])
        assertNull("빈 글", qrMatrix(""))
        assertNull("QR에 다 안 들어감", qrMatrix("x".repeat(5_000)))
        assertEquals("run의 모듈 수 = 어두운 모듈 수", (0 until matrix.size * matrix.size).count { matrix[it % matrix.size, it / matrix.size] },
            qrRuns(matrix).sumOf { it.third })
    }

    @Test fun aModuleIsFourDpOrMoreWithTheQuietZone() {
        val usual = qrMatrix(twoAddresses)!!
        assertEquals("주소 둘이면 232dp", QR_MIN_SIDE, qrSide(usual.size))
        assertTrue("232dp에 모듈 4dp 이상", QR_MIN_SIDE.value / (usual.size + 2 * QR_QUIET_MODULES) >= 4f)
        val longest = qrMatrix(fourLongest)!!
        val side = qrSide(longest.size)
        assertTrue("긴 링크는 타일이 커짐: $side", side >= ((longest.size + 2 * QR_QUIET_MODULES) * 4).dp)
        // On the tile, whole-pixel modules with at least the quiet zone around them, centred.
        listOf(232f, 348f, 464f, 301.6f).forEach { px ->
            val (module, origin) = qrModulePlacement(usual.size, px)
            assertTrue("모듈은 정수 픽셀 ≥ 1", module >= 1)
            assertTrue("여백 ≥ 4모듈 at $px", origin >= QR_QUIET_MODULES * module)
            assertTrue("오른쪽 여백도", px - (origin + usual.size * module) >= QR_QUIET_MODULES * module - 1)
        }
    }

    @Test fun theCodeReadsBackAtSeveralScales() {
        listOf(twoAddresses, fourLongest).forEach { text ->
            val matrix = qrMatrix(text)!!
            listOf(2, 3, 4, 7).forEach { scale ->
                val (pixels, w, h) = render(matrix, scale)
                assertEquals("×$scale", text, decodeQr(pixels, w, h))
            }
        }
    }

    @Test fun theCarsOwnTileReadsBack() {
        val matrix = qrMatrix(twoAddresses)!!
        // 232 dp at the densities a car or a screenshot of it may have.
        listOf(232, 348, 464).forEach { side -> assertEquals("$side px", twoAddresses, decodeQr(tile(matrix, side), side, side)) }
    }

    @Test fun anInvertedCodeReadsBack() {
        val matrix = qrMatrix(twoAddresses)!!
        val (pixels, w, h) = render(matrix, 4, inverted = true)
        assertEquals(twoAddresses, decodeQr(pixels, w, h))
    }

    @Test fun aCodeInALargerNoisyFrameReadsBack() {
        val matrix = qrMatrix(twoAddresses)!!
        val (code, side, _) = render(matrix, 4)
        val random = Random(2_15)
        val width = side * 3
        val height = side * 2 + 37
        // Grey blotches of 6 px, none as dark as a module or as light as the tile.
        val blotch = IntArray((width / 6 + 1) * (height / 6 + 1)) { 70 + random.nextInt(110) }
        val frame = IntArray(width * height) { index ->
            val grey = blotch[(index / width / 6) * (width / 6 + 1) + (index % width / 6)]
            (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
        }
        val left = side + 23
        val top = side / 2 + 11
        for (y in 0 until side) System.arraycopy(code, y * side, frame, (top + y) * width + left, side)
        assertEquals("전체 그림", twoAddresses, decodeQr(frame, width, height))
        assertEquals("가운데 자르기까지", twoAddresses, decodeQrPicture(frame, width, height))
    }

    @Test fun aPictureWithoutACodeGivesNothing() {
        val random = Random(7)
        val noise = IntArray(320 * 240) { (0xFF shl 24) or (random.nextInt(256) * 0x010101) }
        assertNull(decodeQrPicture(noise, 320, 240))
        assertNull("빈 그림", decodeQr(IntArray(0), 0, 0))
        assertNull("픽셀이 모자람", decodeQr(IntArray(10), 10, 10 + 1))
        val (other, w, h) = render(qrMatrix("https://example.com")!!, 4)
        val read = decodeQr(other, w, h)
        assertEquals("다른 앱의 QR도 글은 읽힘", "https://example.com", read)
        assertNull("…그러나 연결 링크는 아님", parsePairingLink(read!!))
    }

    @Test fun aPictureIsLoadedAtSixteenHundredPixelsOnItsLongEdgeAtMost() {
        assertEquals(1_600 to 1_200, qrPictureSize(4_000, 3_000))
        assertEquals("세로 사진", 400 to 1_600, qrPictureSize(1_200, 4_800))
        assertEquals("작은 그림은 그대로", 1_000 to 800, qrPictureSize(1_000, 800))
        assertEquals("딱 1600", 1_600 to 900, qrPictureSize(1_600, 900))
        assertEquals("아주 긴 그림도 1픽셀 이상", 1_600 to 1, qrPictureSize(100_000, 10))
    }
}
