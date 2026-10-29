package ai.sharemate.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/** Incident photos, one JPEG per id, in app-private storage that is never backed up (they may show plates and faces). */
internal class PhotoStore(context: Context) {
    private val dir = File(context.applicationContext.noBackupFilesDir, "incident-photos")

    fun file(id: String): File = File(dir, "$id.jpg")
    fun has(id: String) = file(id).exists()
    fun load(id: String): ByteArray? = file(id).takeIf { it.exists() }?.readBytes()

    fun save(id: String, bytes: ByteArray) {
        require(validUuid(id)) { "사진 식별자가 올바르지 않아요." }
        dir.mkdirs()
        val temp = File(dir, "$id.tmp")
        temp.writeBytes(bytes)
        val target = file(id)
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "사진을 저장하지 못했어요." }
    }

    fun delete(id: String) { file(id).delete() }
}

/** Re-encodes a capture to a bounded JPEG. Re-encoding also drops EXIF, so no GPS or device metadata leaves the phone. */
internal fun shrinkJpeg(source: File, longest: Int = 1600, quality: Int = 82): ByteArray {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
        val scale = minOf(1.0, longest.toDouble() / maxOf(info.size.width, info.size.height))
        decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    try {
        var q = quality
        while (true) {
            val out = ByteArrayOutputStream()
            require(bitmap.compress(Bitmap.CompressFormat.JPEG, q, out)) { "사진을 변환하지 못했어요." }
            if (out.size() <= MAX_PHOTO_BYTES || q <= 40) return out.toByteArray()
            q -= 14
        }
    } finally { bitmap.recycle() }
}

/** A small bitmap for list thumbnails; decoded at a fraction of the size so a dozen photos stay cheap. */
internal fun thumbnail(file: File, edge: Int = 320): Bitmap? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}
