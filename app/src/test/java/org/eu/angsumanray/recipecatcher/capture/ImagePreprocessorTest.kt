package org.eu.angsumanray.recipecatcher.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.eu.angsumanray.recipecatcher.data.image.ImageStorageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImagePreprocessorTest {

    private lateinit var context: Context
    private lateinit var imageStorageManager: ImageStorageManager
    private lateinit var imagePreprocessor: ImagePreprocessor

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        imageStorageManager = ImageStorageManager(context, OkHttpClient())
        imagePreprocessor = ImagePreprocessor(context, imageStorageManager)
    }

    private fun createTestJpegBytes(
        width: Int,
        height: Int,
        orientation: Int = ExifInterface.ORIENTATION_NORMAL
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.BLUE }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bos)
        bitmap.recycle()
        val rawBytes = bos.toByteArray()

        if (orientation == ExifInterface.ORIENTATION_NORMAL) {
            return rawBytes
        }

        val tempFile = File.createTempFile("test_exif", ".jpg", context.cacheDir)
        tempFile.writeBytes(rawBytes)
        val exif = ExifInterface(tempFile.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        exif.saveAttributes()

        val output = tempFile.readBytes()
        tempFile.delete()
        return output
    }

    @Test
    fun `processBytes preserves dimensions for images within MAX_LONG_EDGE`() = runBlocking {
        val raw = createTestJpegBytes(800, 600)
        val processed = imagePreprocessor.processBytes(raw)

        assertNotNull(processed)
        assertEquals(800, processed!!.width)
        assertEquals(600, processed.height)
        assertTrue(processed.base64.isNotBlank())
        assertTrue(processed.base64.length <= ImagePreprocessor.MAX_BASE64_BYTES)
    }

    @Test
    fun `processBytes downscales image exceeding MAX_LONG_EDGE while preserving aspect ratio`() = runBlocking {
        val raw = createTestJpegBytes(3000, 2000)
        val processed = imagePreprocessor.processBytes(raw)

        assertNotNull(processed)
        assertTrue(processed!!.width <= ImagePreprocessor.MAX_LONG_EDGE)
        assertTrue(processed.height <= ImagePreprocessor.MAX_LONG_EDGE)
        assertEquals(ImagePreprocessor.MAX_LONG_EDGE, processed.width)
        val expectedHeight = (2000f * ImagePreprocessor.MAX_LONG_EDGE / 3000f).toInt()
        assertEquals(expectedHeight, processed.height)
        assertTrue(processed.base64.length <= ImagePreprocessor.MAX_BASE64_BYTES)
    }

    @Test
    fun `processBytes applies EXIF rotation for 90 degree orientation tag`() = runBlocking {
        // Landscape 800x600 rotated 90 degrees becomes portrait 600x800
        val raw = createTestJpegBytes(800, 600, ExifInterface.ORIENTATION_ROTATE_90)
        val processed = imagePreprocessor.processBytes(raw)

        assertNotNull(processed)
        assertEquals(600, processed!!.width)
        assertEquals(800, processed.height)
    }

    @Test
    fun `processBytes applies EXIF rotation for 270 degree orientation tag`() = runBlocking {
        val raw = createTestJpegBytes(800, 600, ExifInterface.ORIENTATION_ROTATE_270)
        val processed = imagePreprocessor.processBytes(raw)

        assertNotNull(processed)
        assertEquals(600, processed!!.width)
        assertEquals(800, processed.height)
    }

    @Test
    fun `processFile and saveAsRecipeCover save cover image to filesDir images`() = runBlocking {
        val raw = createTestJpegBytes(500, 500)
        val file = File(context.cacheDir, "sample.jpg")
        file.writeBytes(raw)

        val processed = imagePreprocessor.processFile(file)
        assertNotNull(processed)

        val coverPath = imagePreprocessor.saveAsRecipeCover(processed!!, 42L)
        assertEquals("images/42.jpg", coverPath)

        val savedFile = imageStorageManager.getFile(coverPath)
        assertTrue(savedFile.exists())
        assertEquals(processed.jpegBytes.size.toLong(), savedFile.length())
        file.delete()
        Unit
    }

    @Test
    fun `processBytes returns null for empty input`() = runBlocking {
        assertNull(imagePreprocessor.processBytes(ByteArray(0)))
    }

    @Test
    fun `base64Length matches encoded payload size`() = runBlocking {
        val processed = imagePreprocessor.processBytes(createTestJpegBytes(640, 480))
        assertNotNull(processed)
        assertEquals(ImagePreprocessor.base64Length(processed!!.jpegBytes.size), processed.base64.length)
    }
}
