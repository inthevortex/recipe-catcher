package org.eu.angsumanray.recipecatcher.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: RecipeDatabase,
    private val settingsStore: SettingsStore
) {

    private val json = Json { prettyPrint = true }

    val safetyBackupDir: File
        get() = File(context.filesDir, "safety_backup").apply { if (!exists()) mkdirs() }

    val safetyDbFile: File
        get() = File(safetyBackupDir, "recipes_safety.db")

    /**
     * Executes backup writing into the given SAF [destinationUri].
     */
    suspend fun backupToUri(destinationUri: Uri): BackupManifest = withContext(Dispatchers.IO) {
        val outputStream = context.contentResolver.openOutputStream(destinationUri)
            ?: throw IllegalStateException("Could not open destination Uri for writing: $destinationUri")
        outputStream.use { out ->
            backupToStream(out)
        }
    }

    /**
     * Backs up database and images into the provided [outputStream] in ZIP format.
     */
    suspend fun backupToStream(outputStream: OutputStream): BackupManifest = withContext(Dispatchers.IO) {
        val tempBackupDir = File(context.cacheDir, "backup").apply { if (!exists()) mkdirs() }
        val tempDb = File(tempBackupDir, "recipes.db")
        if (tempDb.exists()) tempDb.delete()

        // 1. Consistent snapshot of SQLite DB via VACUUM INTO
        database.openHelper.writableDatabase.execSQL("VACUUM INTO '${tempDb.absolutePath}'")

        try {
            // 2. Hash DB file with SHA-256
            val dbSha256 = computeSha256(tempDb)

            // 3. Count recipes in database
            val recipeCount = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM recipe").use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }

            // 4. Gather images from filesDir/images/
            val imagesDir = File(context.filesDir, "images")
            val imageFilesWithRelPath = mutableListOf<Pair<File, String>>()
            if (imagesDir.exists()) {
                imagesDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relPath = file.relativeTo(imagesDir).path.replace('\\', '/')
                    imageFilesWithRelPath.add(file to relPath)
                }
            }

            // 5. Create manifest
            val appVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
            } catch (_: Exception) {
                "1.0.0"
            }
            val nowIso = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

            val manifest = BackupManifest(
                format = "recipe-catcher-backup",
                formatVersion = 1,
                dbSchemaVersion = 1,
                appVersion = appVersion,
                createdAt = nowIso,
                recipeCount = recipeCount,
                imageCount = imageFilesWithRelPath.size,
                dbSha256 = dbSha256
            )
            val manifestJsonBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)

            // 6. Stream into ZipOutputStream
            ZipOutputStream(outputStream).use { zipOut ->
                // Write manifest.json (DEFLATED)
                val manifestEntry = ZipEntry("manifest.json")
                manifestEntry.method = ZipEntry.DEFLATED
                zipOut.putNextEntry(manifestEntry)
                zipOut.write(manifestJsonBytes)
                zipOut.closeEntry()

                // Write db/recipes.db (DEFLATED)
                val dbEntry = ZipEntry("db/recipes.db")
                dbEntry.method = ZipEntry.DEFLATED
                zipOut.putNextEntry(dbEntry)
                tempDb.inputStream().use { it.copyTo(zipOut) }
                zipOut.closeEntry()

                // Write images using STORED
                for ((imgFile, relPath) in imageFilesWithRelPath) {
                    val entryName = "images/$relPath"
                    val imgEntry = ZipEntry(entryName)
                    imgEntry.method = ZipEntry.STORED
                    val fileLength = imgFile.length()
                    imgEntry.size = fileLength
                    imgEntry.compressedSize = fileLength
                    imgEntry.crc = computeCrc32(imgFile)
                    zipOut.putNextEntry(imgEntry)
                    imgFile.inputStream().use { it.copyTo(zipOut) }
                    zipOut.closeEntry()
                }
            }

            // 7. Update settings
            settingsStore.setLastBackupAt(nowIso)
            settingsStore.resetNewRecipesSinceBackup()

            manifest
        } finally {
            if (tempDb.exists()) tempDb.delete()
        }
    }

    /**
     * Creates an automatic safety backup in app-private storage before database migration or restore.
     */
    suspend fun createSafetyBackup(): File = withContext(Dispatchers.IO) {
        val target = safetyDbFile
        if (target.exists()) target.delete()

        try {
            database.openHelper.writableDatabase.execSQL("VACUUM INTO '${target.absolutePath}'")
        } catch (_: Exception) {
            // Fallback: if database is closed or vacuum fails, copy live database file directly
            val liveDb = context.getDatabasePath(RecipeDatabase.DATABASE_NAME)
            if (liveDb.exists()) {
                liveDb.copyTo(target, overwrite = true)
            }
        }
        target
    }

    companion object {
        fun computeSha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            FileInputStream(file).use { input ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun computeCrc32(file: File): Long {
            val crc = CRC32()
            val buffer = ByteArray(8192)
            FileInputStream(file).use { input ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    crc.update(buffer, 0, read)
                }
            }
            return crc.value
        }

        fun generateSuggestedFileName(): String {
            val now = OffsetDateTime.now()
            val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
            return "recipes-${now.format(formatter)}.zip"
        }
    }
}
