package org.eu.angsumanray.recipecatcher.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.CookLogEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.image.ImageStorageManager
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageCleanupWorkerTest {

    private lateinit var context: Context
    private lateinit var database: RecipeDatabase
    private lateinit var imageStorageManager: ImageStorageManager
    private lateinit var repository: RecipeRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        imageStorageManager = ImageStorageManager(context, OkHttpClient())
        repository = RecipeRepository(database, imageStorageManager)
    }

    @After
    fun tearDown() {
        database.close()
        // Clean up test image directory
        imageStorageManager.imagesDir.deleteRecursively()
    }

    @Test
    fun `cleanOrphanedImagesWithStats purges unreferenced files and preserves active files`() = runBlocking {
        val imagesDir = imageStorageManager.imagesDir
        val cookedDir = imageStorageManager.cookedImagesDir

        // 1. Active recipe image
        val activeRecipeFile = File(imagesDir, "10.jpg")
        activeRecipeFile.writeBytes(ByteArray(1024) { 1 }) // 1 KB
        database.recipeDao().insert(
            RecipeEntity(
                id = 10L,
                sourceType = "web",
                sourceKey = "key_10",
                title = "Active Recipe",
                imagePath = "images/10.jpg"
            )
        )

        // 2. Active cook log photo
        val activeCookFile = File(cookedDir, "photo_1.jpg")
        activeCookFile.writeBytes(ByteArray(2048) { 2 }) // 2 KB
        database.cookLogDao().insert(
            CookLogEntity(
                id = 1L,
                recipeId = 10L,
                cookedOn = LocalDate.now(),
                photoPath = "images/cooked/photo_1.jpg"
            )
        )

        // 3. Orphaned file in imagesDir
        val orphanFile1 = File(imagesDir, "orphan_99.jpg")
        orphanFile1.writeBytes(ByteArray(3000) { 3 }) // 3000 bytes
        val oldTimestamp = System.currentTimeMillis() - 48L * 60 * 60 * 1000 // 48h ago
        orphanFile1.setLastModified(oldTimestamp)

        // 4. Orphaned file in cookedDir
        val orphanFile2 = File(cookedDir, "orphan_cooked.jpg")
        orphanFile2.writeBytes(ByteArray(5000) { 4 }) // 5000 bytes
        orphanFile2.setLastModified(oldTimestamp)

        // 5. Recent file within grace period (not in DB, but recent)
        val recentFile = File(imagesDir, "recent_capture.jpg")
        recentFile.writeBytes(ByteArray(1500) { 5 })
        recentFile.setLastModified(System.currentTimeMillis())

        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000 // 24h grace cutoff

        val result = repository.cleanOrphanedImagesWithStats(modifiedBefore = cutoff)

        // Asserts
        assertEquals(2, result.deletedCount)
        assertEquals(8000L, result.bytesFreed) // 3000 + 5000

        // Assert orphaned files are deleted
        assertFalse("Orphan 1 should be deleted", orphanFile1.exists())
        assertFalse("Orphan 2 should be deleted", orphanFile2.exists())

        // Assert active and recent files are strictly preserved
        assertTrue("Active recipe image must be preserved", activeRecipeFile.exists())
        assertTrue("Active cook photo must be preserved", activeCookFile.exists())
        assertTrue("Recent file within grace period must be preserved", recentFile.exists())
    }
}
