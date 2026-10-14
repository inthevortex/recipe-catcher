package org.eu.angsumanray.recipecatcher.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.eu.angsumanray.recipecatcher.MainActivity
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

data class BackupInspection(
    val manifest: BackupManifest,
    val tempDir: File,
    val tempDbFile: File,
    val restoredRecipeCount: Int,
    val currentRecipeCount: Int
)

@Singleton
class RestoreManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: RecipeDatabase,
    private val backupManager: BackupManager
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Inspects and validates a backup ZIP from [sourceUri] without modifying live data.
     */
    suspend fun inspectBackup(sourceUri: Uri): BackupInspection = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(sourceUri)
            ?: throw IllegalArgumentException("Could not open input Uri: $sourceUri")
        inputStream.use { stream ->
            inspectBackupStream(stream)
        }
    }

    /**
     * Validates and inspects a backup ZIP stream into a temporary directory.
     */
    suspend fun inspectBackupStream(inputStream: InputStream): BackupInspection = withContext(Dispatchers.IO) {
        val restoreTempDir = File(context.cacheDir, "restore_temp_${System.currentTimeMillis()}").apply { mkdirs() }

        try {
            // 1. Unzip and check for zip-slip
            unzipSafe(inputStream, restoreTempDir)

            // 2. Check manifest.json
            val manifestFile = File(restoreTempDir, "manifest.json")
            if (!manifestFile.exists()) {
                throw IllegalStateException("Invalid backup: missing manifest.json")
            }

            val manifest = try {
                json.decodeFromString<BackupManifest>(manifestFile.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                throw IllegalStateException("Invalid backup: corrupted manifest.json", e)
            }

            if (manifest.format != "recipe-catcher-backup") {
                throw IllegalStateException("Unsupported backup format: ${manifest.format}")
            }
            if (manifest.formatVersion > 1) {
                throw IllegalStateException("Unsupported backup format version: ${manifest.formatVersion}")
            }
            if (manifest.dbSchemaVersion > 1) {
                throw IllegalStateException("Database schema version ${manifest.dbSchemaVersion} is newer than supported version 1")
            }

            // 3. Check database file and SHA-256
            val tempDb = File(restoreTempDir, "db/recipes.db")
            if (!tempDb.exists()) {
                throw IllegalStateException("Invalid backup: missing db/recipes.db")
            }

            val computedSha256 = BackupManager.computeSha256(tempDb)
            if (!computedSha256.equals(manifest.dbSha256, ignoreCase = true)) {
                throw IllegalStateException("Database integrity failure: SHA-256 hash mismatch")
            }

            // 4. Run PRAGMA integrity_check read-only on the temp database
            val readOnlyDb = SQLiteDatabase.openDatabase(
                tempDb.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            val integrityOk = readOnlyDb.use { db ->
                val cursor = db.rawQuery("PRAGMA integrity_check", null)
                val status = cursor.use {
                    if (it.moveToFirst()) it.getString(0) else "failed"
                }
                status.equals("ok", ignoreCase = true)
            }
            if (!integrityOk) {
                throw IllegalStateException("Database PRAGMA integrity_check failed")
            }

            val restoredRecipeCount = SQLiteDatabase.openDatabase(
                tempDb.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                val cursor = db.rawQuery("SELECT COUNT(*) FROM recipe", null)
                cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
            }

            // 5. Query current live recipe count
            val currentRecipeCount = try {
                database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM recipe").use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
            } catch (_: Exception) {
                0
            }

            BackupInspection(
                manifest = manifest,
                tempDir = restoreTempDir,
                tempDbFile = tempDb,
                restoredRecipeCount = restoredRecipeCount,
                currentRecipeCount = currentRecipeCount
            )
        } catch (e: Exception) {
            restoreTempDir.deleteRecursively()
            throw e
        }
    }

    /**
     * Executes the restore after confirmation. Creates a safety backup, closes Room,
     * swaps DB and image files, and restarts the process.
     */
    suspend fun performRestore(
        inspection: BackupInspection,
        restartProcess: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val safetyDir = backupManager.safetyBackupDir
        val safetyImagesBackup = File(safetyDir, "images_pre_restore")
        if (safetyImagesBackup.exists()) safetyImagesBackup.deleteRecursively()

        val imagesDir = File(context.filesDir, "images")
        val liveDb = context.getDatabasePath(RecipeDatabase.DATABASE_NAME)

        // 1. Write automatic safety backup before touching live data
        backupManager.createSafetyBackup()
        if (imagesDir.exists()) {
            imagesDir.copyRecursively(safetyImagesBackup, overwrite = true)
        }

        try {
            // 2. Close Room database
            database.close()

            // 3. Swap database file and delete stale WAL / SHM
            val walFile = File(liveDb.parentFile, "${liveDb.name}-wal")
            val shmFile = File(liveDb.parentFile, "${liveDb.name}-shm")
            if (walFile.exists()) walFile.delete()
            if (shmFile.exists()) shmFile.delete()

            inspection.tempDbFile.copyTo(liveDb, overwrite = true)

            // 4. Swap images directory
            if (imagesDir.exists()) imagesDir.deleteRecursively()
            imagesDir.mkdirs()

            val extractedImagesDir = File(inspection.tempDir, "images")
            if (extractedImagesDir.exists()) {
                extractedImagesDir.copyRecursively(imagesDir, overwrite = true)
            }

            // Clean up restore temp dir
            inspection.tempDir.deleteRecursively()

            // 5. Restart process
            if (restartProcess) {
                restartApp()
            }
        } catch (e: Exception) {
            // Rollback from safety backup
            if (backupManager.safetyDbFile.exists()) {
                backupManager.safetyDbFile.copyTo(liveDb, overwrite = true)
            }
            if (safetyImagesBackup.exists()) {
                if (imagesDir.exists()) imagesDir.deleteRecursively()
                safetyImagesBackup.copyRecursively(imagesDir, overwrite = true)
            }
            inspection.tempDir.deleteRecursively()
            throw e
        }
    }

    fun restartApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        exitProcess(0)
    }

    private fun unzipSafe(inputStream: InputStream, destinationDir: File) {
        ZipInputStream(inputStream).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                val name = entry.name

                // Zip-slip protection
                if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                    throw SecurityException("Zip-slip security exception: rejected entry $name")
                }

                val outFile = File(destinationDir, name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        zipIn.copyTo(out)
                    }
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
    }
}
