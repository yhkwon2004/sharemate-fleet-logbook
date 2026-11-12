package ai.sharemate.app

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.floor
import kotlin.math.max

/*
 * The QR the car shows for pairing (2.15, docs/design/DESIGN_2_15.md §6.2): the pairing link (PairingLink.kt) encoded by
 * ZXing and drawn on the card. The one place on the car with literal colours: a camera has to read it by day and by night, so
 * it is always dark modules on a white tile, never the scene's colours.
 */

/** The tile under the QR: plain white in both scenes (§6.2). */
internal val QR_LIGHT = Color(0xFFFFFFFF)

/** The modules: a near-black navy (§6.2), in both scenes; over 18:1 on the white tile. */
internal val QR_DARK = Color(0xFF0B1220)

/** The quiet zone around the modules, in modules: the four the QR standard asks for. */
internal const val QR_QUIET_MODULES = 4

/** The smallest module the car draws (§6.2): 4 dp, so a phone a metre away still resolves it. */
internal val QR_MIN_MODULE = 4.dp

/** The QR tile's smallest side on the car (§6.2). */
internal val QR_MIN_SIDE = 232.dp

/** A QR's modules, [size] × [size], row by row; true is a dark module. */
internal class QrMatrix(val size: Int, private val dark: BooleanArray) {
    init { require(size > 0 && dark.size == size * size) }
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
}

/**
 * [text] as a QR code (pure; ZXing's QRCodeWriter, error correction M, no margin: the quiet zone is the tile's business), or
 * null when it cannot be encoded (empty, or past what a QR holds).
 */
internal fun qrMatrix(text: String): QrMatrix? {
    if (text.isEmpty()) return null
    val bits = try {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
    } catch (_: WriterException) { return null } catch (_: IllegalArgumentException) { return null }
    val size = bits.width
    return QrMatrix(size, BooleanArray(size * size) { bits.get(it % size, it / size) })
}

/**
 * The tile's side for a QR of [modules] modules a side (pure): [minSide], or more where that would make a module smaller
 * than [QR_MIN_MODULE] with the quiet zone around it (a link with four long addresses).
 */
internal fun qrSide(modules: Int, minSide: Dp = QR_MIN_SIDE): Dp = max(minSide.value, ((modules + 2 * QR_QUIET_MODULES) * QR_MIN_MODULE.value)).dp

/**
 * Where the modules go on a tile [sidePx] pixels square (pure): the module size in whole pixels, so neighbouring modules
 * meet without seams, and the start of the first module, centred. The quiet zone is what is left around them, never less
 * than [QR_QUIET_MODULES] modules. A module is one pixel at the least.
 */
internal fun qrModulePlacement(modules: Int, sidePx: Float): Pair<Int, Int> {
    val module = floor(sidePx / (modules + 2 * QR_QUIET_MODULES)).toInt().coerceAtLeast(1)
    val origin = floor((sidePx - modules * module) / 2f).toInt().coerceAtLeast(0)
    return module to origin
}

/** The dark modules of each row as runs (pure): (row, first column, length), so a row draws as a few rectangles. */
internal fun qrRuns(matrix: QrMatrix): List<Triple<Int, Int, Int>> = buildList {
    for (y in 0 until matrix.size) {
        var x = 0
        while (x < matrix.size) {
            if (!matrix[x, y]) { x++; continue }
            val start = x
            while (x < matrix.size && matrix[x, y]) x++
            add(Triple(y, start, x - start))
        }
    }
}

/**
 * The QR of [text] on its white tile (§6.2): corner 16, the quiet zone of four modules, modules in [QR_DARK] snapped to whole
 * pixels, [size] at least ([qrSide] grows it where a module would fall under 4 dp). The matrix is encoded once per text
 * (`remember(text)`), and the path once per text and tile size: a card that recomposes every second for its countdown never
 * rebuilds it. One image for TalkBack, "휴대폰 연결 QR". A text that cannot be encoded leaves the white tile empty; the
 * caller only passes links that parse ([pairingLinkText]).
 */
@Composable internal fun QrCode(text: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(text) { qrMatrix(text) }
    val runs = remember(matrix) { matrix?.let(::qrRuns).orEmpty() }
    val side = qrSide(matrix?.size ?: 0, size)
    val draw = remember(matrix, runs) {
        Modifier.drawWithCache {
            val corner = CornerRadius(16.dp.toPx())
            val path = Path()
            if (matrix != null) {
                val (module, origin) = qrModulePlacement(matrix.size, this.size.minDimension)
                runs.forEach { (y, x, length) ->
                    val left = (origin + x * module).toFloat()
                    val top = (origin + y * module).toFloat()
                    path.addRect(Rect(left, top, left + length * module, top + module))
                }
            }
            onDrawBehind {
                drawRoundRect(QR_LIGHT, cornerRadius = corner)
                drawPath(path, QR_DARK)
            }
        }
    }
    Spacer(modifier.size(side).semantics { contentDescription = VehicleCopy.SPOKEN_PHONE_QR; role = Role.Image }.then(draw))
}
