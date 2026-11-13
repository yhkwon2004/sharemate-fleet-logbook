package ai.sharemate.app

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/*
 * The phone reads the car's pairing QR from a picture (2.15, docs/design/DESIGN_2_15.md §6.3): a photo the system camera took
 * into the app's cache, or one the system photo picker hands over. No camera permission and no preview of our own: the
 * camera and the picker are the system's. The decoding is pure JVM (ZXing) and unit-tested on rendered codes (QrCodeTest);
 * the Android part only loads the picture's pixels.
 */

/** The phone's words for pairing by QR (§6.3), in the phone's own register beside the connect card's sentences. */
internal object PhoneQrCopy {
    /** The card's first, filled button (with a camera mark). */
    const val SCAN = "QR 찍어서 연결"
    /** The same button while the picture is decoded. */
    const val READING = "QR 읽는 중…"
    /** The text button under it: the system photo picker. */
    const val PICK = "사진에서 QR 읽기"
    /** The heading over the three fields typed by hand, as before 2.15. */
    const val MANUAL = "직접 입력"
    /** No QR in the picture (or a picture that could not be opened). */
    const val UNREADABLE = "QR을 읽지 못했어요 · 화면에 QR이 다 나오게 다시 찍어 주세요"
    /** A QR, but not a SHAREMATE pairing link ([parsePairingLink] refused it). */
    const val NOT_OURS = "SHAREMATE 연결 QR이 아니에요"
    /** The camera app could not be opened; the same words as the incident photo's. */
    const val NO_CAMERA = "카메라를 열 수 없어요 · 카메라 앱이 있는지 확인해 주세요"
    /** The photo picker could not be opened. */
    const val NO_PICKER = "사진을 고를 수 없어요 · 직접 입력해 주세요"

    /**
     * A link the phone does not pair at once (2.15 fix, docs/design/DESIGN_2_15.md §9.6: a picture from the picker, or an
     * address that is not a car's local one): the fields are filled, and this one line, not an error, names the first address
     * the code would go to and asks for 차량과 연결, as typing does.
     */
    fun confirm(endpoint: String): String = "주소를 확인하고 차량과 연결을 눌러 주세요 · $endpoint"
}

/** The longest edge a picture is loaded at before decoding (§6.3): enough for a QR across part of a photo, light on memory. */
internal const val QR_PICTURE_MAX_EDGE = 1_600

/** The centre crops tried after the whole picture, as a fraction of each side (§6.3): a QR in the middle of a busy photo. */
internal val QR_CENTRE_CROPS = listOf(.7f, .5f)

private val QR_HINTS = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

/**
 * The text of the QR code in [pixels] (ARGB, row by row, [width] × [height]), or null (pure JVM). ZXing's QRCodeReader with
 * TRY_HARDER over the HybridBinarizer, then the GlobalHistogramBinarizer (a flat, evenly lit picture), each on the picture as
 * it is and inverted (light modules on a dark ground, as some screens and night themes show a code).
 */
internal fun decodeQr(pixels: IntArray, width: Int, height: Int): String? {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return null
    return decodeQr(RGBLuminanceSource(width, height, pixels))
}

private fun decodeQr(source: LuminanceSource): String? {
    for (picture in listOf(source, source.invert())) {
        for (binarizer in listOf(HybridBinarizer(picture), GlobalHistogramBinarizer(picture))) {
            try { return QRCodeReader().decode(BinaryBitmap(binarizer), QR_HINTS).text } catch (_: ReaderException) { }
        }
    }
    return null
}

/**
 * [decodeQr] over a whole picture, then over its centre crops ([QR_CENTRE_CROPS]) where the whole did not give a code (pure
 * JVM): a code that fills a small part of a photo full of other edges often reads once the edges around it are cut away.
 */
internal fun decodeQrPicture(pixels: IntArray, width: Int, height: Int): String? {
    decodeQr(pixels, width, height)?.let { return it }
    if (width <= 0 || height <= 0 || pixels.size < width * height) return null
    val source = RGBLuminanceSource(width, height, pixels)
    for (fraction in QR_CENTRE_CROPS) {
        val w = (width * fraction).toInt()
        val h = (height * fraction).toInt()
        // A version-1 code is 21 modules: a crop smaller than that holds no code at one pixel a module.
        if (w < 21 || h < 21) continue
        decodeQr(source.crop((width - w) / 2, (height - h) / 2, w, h))?.let { return it }
    }
    return null
}

/** The size a [width] × [height] picture is loaded at (pure): its own, or scaled down so the long edge is [maxEdge]. */
internal fun qrPictureSize(width: Int, height: Int, maxEdge: Int = QR_PICTURE_MAX_EDGE): Pair<Int, Int> {
    val long = maxOf(width, height)
    if (long <= maxEdge || long <= 0) return width to height
    val scale = maxEdge.toDouble() / long
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

/**
 * Where the camera puts the picture to scan: one file in the app's cache, under the `qr-scan` cache path the evidence
 * FileProvider shares with the camera app (res/xml/evidence_paths.xml). The card deletes it once read.
 */
internal fun qrScanFile(context: Context): File = File(File(context.cacheDir, "qr-scan").apply { mkdirs() }, "pairing-scan.jpg")

/** The QR text in the picture at [file] (the camera's), or null; loaded and decoded off the main thread. */
internal suspend fun readQrPicture(file: File): String? = readQrPicture { ImageDecoder.createSource(file) }

/** The QR text in the picture at [uri] (the photo picker's), or null; loaded and decoded off the main thread. */
internal suspend fun readQrPicture(context: Context, uri: Uri): String? = readQrPicture { ImageDecoder.createSource(context.contentResolver, uri) }

/**
 * Loads the picture at most [QR_PICTURE_MAX_EDGE] on its long edge (ImageDecoder scales while it decodes, so a 50-MP photo
 * never sits in memory whole, and turns it upright), as a software bitmap whose pixels can be read, then decodes it
 * ([decodeQrPicture]) on the default dispatcher. A picture that cannot be opened is null, like one without a code.
 */
private suspend fun readQrPicture(source: () -> ImageDecoder.Source): String? {
    val loaded = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = ImageDecoder.decodeBitmap(source()) { decoder, info, _ ->
                val (w, h) = qrPictureSize(info.size.width, info.size.height)
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                Triple(pixels, bitmap.width, bitmap.height)
            } finally { bitmap.recycle() }
        }.getOrNull()
    } ?: return null
    return withContext(Dispatchers.Default) { decodeQrPicture(loaded.first, loaded.second, loaded.third) }
}
