package org.eu.angsumanray.recipecatcher.data.image

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageStorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val imagesDir: File
        get() = File(context.filesDir, "images").apply {
            if (!exists()) mkdirs()
        }

    fun getFile(relativePath: String): File {
        return File(context.filesDir, relativePath)
    }

    fun getRecipeImageFile(recipeId: Long): File {
        return File(imagesDir, "$recipeId.jpg")
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

    fun deleteImage(relativePath: String): Boolean {
        return try {
            val file = getFile(relativePath)
            if (file.exists()) file.delete() else true
        } catch (e: Exception) {
            false
        }
    }
}
