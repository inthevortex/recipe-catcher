package org.eu.angsumanray.recipecatcher.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class ImageStorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    val imagesDir: File
        get() = File(context.filesDir, "images").apply {
            if (!exists()) mkdirs()
        }

    val cookedImagesDir: File
        get() = File(imagesDir, "cooked").apply {
            if (!exists()) mkdirs()
        }

    fun getFile(relativePath: String): File {
        return File(context.filesDir, relativePath)
    }

    fun getRecipeImageFile(recipeId: Long): File {
        return File(imagesDir, "$recipeId.jpg")
    }

    fun getCookedImageFile(filename: String): File {
        return File(cookedImagesDir, filename)
    }

    suspend fun saveImageBytes(recipeId: Long, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val file = getRecipeImageFile(recipeId)
        FileOutputStream(file).use { it.write(bytes) }
        "images/$recipeId.jpg"
    }

    suspend fun downloadAndSave(recipeId: Long, imageUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(imageUrl)
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                // Write to a temp file first so a dropped connection never leaves a truncated image.
                val file = getRecipeImageFile(recipeId)
                val tmp = File(file.parentFile, "${file.name}.part")
                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    return@withContext null
                }
            }
            "images/$recipeId.jpg"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resizes and saves a photo from a content URI to filesDir/images/cooked/<id>.jpg
     * Max long edge is capped at 1600 px, compressed to JPEG 85%.
     */
    suspend fun saveCookedImageFromUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val rawBytes = inputStream.use { it.readBytes() }
            saveCookedImageBytes(rawBytes)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveCookedImageBytes(bytes: ByteArray, id: String = UUID.randomUUID().toString()): String = withContext(Dispatchers.IO) {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

        val origWidth = options.outWidth
        val origHeight = options.outHeight

        val maxDim = 1600
        val longEdge = max(origWidth, origHeight)

        val targetBitmap = if (longEdge > maxDim && origWidth > 0 && origHeight > 0) {
            // Compute inSampleSize
            var sampleSize = 1
            while ((longEdge / (sampleSize * 2)) >= maxDim) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val sampledBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            if (sampledBitmap != null && max(sampledBitmap.width, sampledBitmap.height) > maxDim) {
                val scale = maxDim.toFloat() / max(sampledBitmap.width, sampledBitmap.height)
                val newW = (sampledBitmap.width * scale).toInt()
                val newH = (sampledBitmap.height * scale).toInt()
                val scaled = Bitmap.createScaledBitmap(sampledBitmap, newW, newH, true)
                if (scaled != sampledBitmap) {
                    sampledBitmap.recycle()
                }
                scaled
            } else {
                sampledBitmap
            }
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }

        val filename = "$id.jpg"
        val targetFile = File(cookedImagesDir, filename)

        if (targetBitmap != null) {
            FileOutputStream(targetFile).use { fos ->
                targetBitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
            }
            targetBitmap.recycle()
        } else {
            // Fallback: write raw bytes directly
            FileOutputStream(targetFile).use { it.write(bytes) }
        }

        "images/cooked/$filename"
    }

    /**
     * Creates a temporary file in cacheDir/camera/ and returns its content Uri via FileProvider.
     */
    fun createTempCameraUri(): Pair<Uri, File> {
        val cameraDir = File(context.cacheDir, "camera").apply {
            if (!exists()) mkdirs()
        }
        val file = File(cameraDir, "camera_${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Pair(uri, file)
    }

    fun deleteImage(relativePath: String): Boolean {
        return try {
            val file = getFile(relativePath)
            if (file.exists()) file.delete() else true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Scans filesDir/images/ and filesDir/images/cooked/ and returns relative paths of all image files.
     */
    fun scanAllImageFiles(): List<String> {
        val results = mutableListOf<String>()
        val baseDir = imagesDir
        if (!baseDir.exists()) return emptyList()

        val files = baseDir.listFiles() ?: return emptyList()
        for (file in files) {
            if (file.isFile && (file.extension == "jpg" || file.extension == "jpeg" || file.extension == "png")) {
                results.add("images/${file.name}")
            } else if (file.isDirectory && file.name == "cooked") {
                val cookedFiles = file.listFiles() ?: emptyArray()
                for (cookedFile in cookedFiles) {
                    if (cookedFile.isFile && (cookedFile.extension == "jpg" || cookedFile.extension == "jpeg" || cookedFile.extension == "png")) {
                        results.add("images/cooked/${cookedFile.name}")
                    }
                }
            }
        }
        return results
    }
}
