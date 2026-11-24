package app.familygem.geni.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Prepares photos for Geni: scaled down, upright and without the metadata (such as GPS) of the source file. */
object PhotoScaler {

    private const val QUALITY = 85

    /**
     * The bytes to upload for [file]: always decoded, scaled down to [maxSide] on the longer side if needed,
     * turned upright by its EXIF orientation and encoded as a fresh JPEG, so no source metadata (EXIF, XMP, GPS)
     * is carried over. The file itself is not touched.
     * @throws IOException if the file cannot be decoded or encoded, so the source bytes are never uploaded.
     */
    suspend fun scaled(file: File, maxSide: Int = 2048): ByteArray = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) throw IOException("Not an image: ${file.name}")
        val longer = maxOf(width, height)
        val orientation = try {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        var sample = 1
        while (longer / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Cannot decode: ${file.name}")
        val factor = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled = if (factor < 1f) Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * factor).toInt()),
            maxOf(1, (decoded.height * factor).toInt()), true).also { if (it !== decoded) decoded.recycle() } else decoded
        val bitmap = upright(scaled, orientation)
        try {
            ByteArrayOutputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) throw IOException("Cannot encode: ${file.name}")
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** The bitmap turned or flipped as its EXIF [orientation] says; the given one is recycled when a new one is made. */
    private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }
}
