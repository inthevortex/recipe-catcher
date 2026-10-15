package org.eu.angsumanray.recipecatcher.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import android.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eu.angsumanray.recipecatcher.data.image.ImageStorageManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

data class ProcessedImage(
    val base64: String,
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProcessedImage
        if (base64 != other.base64) return false
        if (!jpegBytes.contentEquals(other.jpegBytes)) return false
        if (width != other.width) return false
        if (height != other.height) return false

        return true
    }

    override fun hashCode(): Int {
        var result = base64.hashCode()
        result = 31 * result + jpegBytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

@Singleton
class ImagePreprocessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageStorageManager: ImageStorageManager
) {
    companion object {
        const val MAX_LONG_EDGE = 1568
        const val MAX_BYTE_SIZE = 4_500_000 // 4.5 MB safety margin under Claude's 5 MB limit
        const val INITIAL_JPEG_QUALITY = 85
    }

    suspend fun processUri(uri: Uri): ProcessedImage? = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext null
            processBytes(bytes)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun processFile(file: File): ProcessedImage? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext null
            processBytes(file.readBytes())
        } catch (_: Exception) {
            null
        }
    }

    suspend fun processBytes(rawBytes: ByteArray): ProcessedImage? = withContext(Dispatchers.IO) {
        if (rawBytes.isEmpty()) return@withContext null

        try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOptions)

            val rawWidth = boundsOptions.outWidth
            val rawHeight = boundsOptions.outHeight
            if (rawWidth <= 0 || rawHeight <= 0) return@withContext null

            val longestEdge = max(rawWidth, rawHeight)
            var sampleSize = 1
            while ((longestEdge / (sampleSize * 2)) >= MAX_LONG_EDGE) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decodedBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
                ?: return@withContext null

            val orientedBitmap = applyExifOrientation(decodedBitmap, rawBytes)
            val currentLongEdge = max(orientedBitmap.width, orientedBitmap.height)

            val scaledBitmap = if (currentLongEdge > MAX_LONG_EDGE) {
                val scale = MAX_LONG_EDGE.toFloat() / currentLongEdge
                val newWidth = (orientedBitmap.width * scale).toInt().coerceAtLeast(1)
                val newHeight = (orientedBitmap.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(orientedBitmap, newWidth, newHeight, true)
                if (scaled != orientedBitmap && orientedBitmap != decodedBitmap) {
                    orientedBitmap.recycle()
                }
                scaled
            } else {
                orientedBitmap
            }

            // Compress to JPEG ensuring < 4.5 MB
            var quality = INITIAL_JPEG_QUALITY
            var compressedBytes: ByteArray
            while (true) {
                val bos = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                compressedBytes = bos.toByteArray()
                if (compressedBytes.size <= MAX_BYTE_SIZE || quality <= 30) {
                    break
                }
                quality -= 15
            }

            val finalWidth = scaledBitmap.width
            val finalHeight = scaledBitmap.height

            if (scaledBitmap != decodedBitmap) {
                scaledBitmap.recycle()
            }
            decodedBitmap.recycle()

            val base64 = Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
            ProcessedImage(
                base64 = base64,
                jpegBytes = compressedBytes,
                width = finalWidth,
                height = finalHeight
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun applyExifOrientation(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val exif = try {
            ExifInterface(ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            return bitmap
        }

        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val (degrees, flipped) = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f to false
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f to false
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f to false
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0f to true
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180f to true
            ExifInterface.ORIENTATION_TRANSPOSE -> 90f to true
            ExifInterface.ORIENTATION_TRANSVERSE -> 270f to true
            else -> return bitmap
        }

        val matrix = Matrix().apply {
            postRotate(degrees)
            if (flipped) postScale(-1f, 1f)
        }

        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    suspend fun saveAsRecipeCover(processed: ProcessedImage, recipeId: Long): String {
        return imageStorageManager.saveImageBytes(recipeId, processed.jpegBytes)
    }
}
