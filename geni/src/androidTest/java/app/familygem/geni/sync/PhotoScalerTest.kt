package app.familygem.geni.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhotoScalerTest {

    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "photoscaler").apply { mkdirs() }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun jpeg(name: String, width: Int, height: Int): File = File(dir, name).also { file ->
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
    }

    @Test
    fun bigPhotoIsScaledDown() = runTest {
        val bytes = PhotoScaler.scaled(jpeg("big.jpg", 4000, 3000))
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(decoded)
        assertTrue(maxOf(decoded.width, decoded.height) <= 2048)
        assertEquals(2048, maxOf(decoded.width, decoded.height))
    }

    @Test
    fun smallJpegLosesGpsButOriginalKeepsIt() = runTest {
        val file = jpeg("small.jpg", 100, 100)
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "50/1,30/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "30/1,15/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            saveAttributes()
        }
        assertNotNull(ExifInterface(file.path).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        val original = file.readBytes()

        val bytes = PhotoScaler.scaled(file)

        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(100, decoded.width)
        assertEquals(100, decoded.height)
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        assertFalse(exif.getLatLong(FloatArray(2)))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        assertArrayEquals(original, file.readBytes())
        assertNotNull(ExifInterface(file.path).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
    }

    @Test
    fun exifOrientationIsAppliedEvenToSmallJpeg() = runTest {
        val file = jpeg("rotated.jpg", 200, 100)
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val bytes = PhotoScaler.scaled(file)
        assertFalse(bytes.contentEquals(file.readBytes()))
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(100, decoded.width)
        assertEquals(200, decoded.height)
    }
}
