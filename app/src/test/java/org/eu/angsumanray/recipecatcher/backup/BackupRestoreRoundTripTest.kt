package org.eu.angsumanray.recipecatcher.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.StarterLists
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeTagEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import org.eu.angsumanray.recipecatcher.data.settings.SecretStore
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreRoundTripTest {

    private lateinit var context: Context
    private lateinit var database: RecipeDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var backupManager: BackupManager
    private lateinit var restoreManager: RestoreManager

    private lateinit var liveDbFile: File
    private lateinit var imagesDir: File
    private lateinit var cookedImagesDir: File

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        liveDbFile = context.getDatabasePath(RecipeDatabase.DATABASE_NAME)
        liveDbFile.parentFile?.mkdirs()

        // Create a real disk-backed Room database in the app database directory so VACUUM INTO and file swap work cleanly
        database = Room.databaseBuilder(context, RecipeDatabase::class.java, RecipeDatabase.DATABASE_NAME)
            .allowMainThreadQueries()
            .build()

        database.tagDao().insertAll(StarterLists.createInitialEntities())

        val secretStore = SecretStore(context)
        settingsStore = SettingsStore(context, secretStore)

        backupManager = BackupManager(context, database, settingsStore)
        restoreManager = RestoreManager(context, database, backupManager)

        imagesDir = File(context.filesDir, "images").apply { mkdirs() }
        cookedImagesDir = File(imagesDir, "cooked").apply { mkdirs() }
    }

    @After
    fun teardown() {
        try {
            database.close()
        } catch (_: Exception) {}
        liveDbFile.delete()
        imagesDir.deleteRecursively()
        File(context.filesDir, "safety_backup").deleteRecursively()
        File(context.cacheDir, "backup").deleteRecursively()
    }

    @Test
    fun `backup and restore round trip preserves 50 recipes and images exactly`() = runBlocking {
        val recipeDao = database.recipeDao()
        val ingredientDao = database.ingredientDao()
        val stepDao = database.stepDao()
        val recipeTagDao = database.recipeTagDao()
        val tagDao = database.tagDao()

        val allTags = tagDao.getAll()
        val starterTagId = allTags.first().id

        // 1. Seed 50 complete recipes
        val seededRecipeIds = mutableListOf<Long>()
        val createdImageBytes = mutableMapOf<String, ByteArray>()

        for (i in 1..50) {
            val now = System.currentTimeMillis() - (50 - i) * 60_000L
            val recipe = RecipeEntity(
                sourceType = if (i % 3 == 0) "youtube" else if (i % 2 == 0) "web" else "instagram",
                sourceKey = "key_recipe_$i",
                sourceUrl = "https://example.com/recipe/$i",
                title = "Gourmet Dish Number $i",
                description = "Description for recipe $i with rich culinary details",
                servings = (i % 6) + 1,
                prepMin = 10 + i,
                cookMin = 20 + i,
                totalMin = 30 + (2 * i),
                cuisine = if (i % 2 == 0) "Italian" else "Indian",
                complexity = if (i % 3 == 0) "hard" else "medium",
                spiceLevel = "medium",
                dietType = "vegetarian",
                status = RecipeEntity.STATUS_SAVED,
                savedAt = now,
                createdAt = now,
                updatedAt = now
            )
            val recipeId = recipeDao.insertIfAbsent(recipe)
            seededRecipeIds.add(recipeId)

            // Ingredients
            val ing1 = IngredientEntity(
                recipeId = recipeId,
                position = 0,
                name = "Ingredient A$i",
                baseName = "ing_a",
                quantity = i.toDouble(),
                unit = "g",
                category = "pantry",
                rawText = "$i g Ingredient A$i"
            )
            val ing2 = IngredientEntity(
                recipeId = recipeId,
                position = 1,
                name = "Ingredient B$i",
                baseName = "ing_b",
                quantity = (i * 2).toDouble(),
                unit = "ml",
                category = "dairy",
                rawText = "${i * 2} ml Ingredient B$i"
            )
            ingredientDao.insertAll(listOf(ing1, ing2))

            // Steps
            val s1 = StepEntity(recipeId = recipeId, position = 0, text = "Chop ingredients for recipe $i")
            val s2 = StepEntity(recipeId = recipeId, position = 1, text = "Simmer pot $i for 20 minutes", timerSeconds = 1200)
            stepDao.insertAll(listOf(s1, s2))

            // Tags
            recipeTagDao.insertAll(listOf(RecipeTagEntity(recipeId = recipeId, tagId = starterTagId, source = "ai")))

            // Cover image for 30 recipes
            if (i <= 30) {
                val imgFile = File(imagesDir, "$recipeId.jpg")
                val bytes = "JPEG_DATA_RECIPE_$recipeId".toByteArray(Charsets.UTF_8)
                imgFile.writeBytes(bytes)
                createdImageBytes["$recipeId.jpg"] = bytes
                recipeDao.updateImagePath(recipeId, "images/$recipeId.jpg")
            }

            // Cooked photo for 10 recipes
            if (i <= 10) {
                val cookedFile = File(cookedImagesDir, "cooked_$recipeId.jpg")
                val bytes = "COOKED_PHOTO_$recipeId".toByteArray(Charsets.UTF_8)
                cookedFile.writeBytes(bytes)
                createdImageBytes["cooked/cooked_$recipeId.jpg"] = bytes
            }
        }

        assertEquals(50, recipeDao.count())

        // 2. Perform Backup to ZIP stream
        val zipByteStream = ByteArrayOutputStream()
        val manifest = backupManager.backupToStream(zipByteStream)

        assertEquals("recipe-catcher-backup", manifest.format)
        assertEquals(1, manifest.formatVersion)
        assertEquals(50, manifest.recipeCount)
        assertEquals(40, manifest.imageCount) // 30 cover images + 10 cooked images
        assertTrue("Manifest has non-blank SHA256", manifest.dbSha256.isNotBlank())

        val backupBytes = zipByteStream.toByteArray()
        assertTrue("Zip archive was written", backupBytes.isNotEmpty())

        // 3. Inspect backup before restore
        val inspection = restoreManager.inspectBackupStream(ByteArrayInputStream(backupBytes))
        assertEquals(50, inspection.restoredRecipeCount)
        assertEquals(50, inspection.currentRecipeCount)
        assertEquals(manifest.dbSha256, inspection.manifest.dbSha256)

        // 4. Wipe live database and images completely
        database.close()
        liveDbFile.delete()
        imagesDir.deleteRecursively()

        // 5. Perform Restore
        restoreManager.performRestore(inspection, restartProcess = false)

        // 6. Deep verify restored database
        val reopenedDb = Room.databaseBuilder(context, RecipeDatabase::class.java, RecipeDatabase.DATABASE_NAME)
            .allowMainThreadQueries()
            .build()

        try {
            val restoredRecipeDao = reopenedDb.recipeDao()
            val restoredIngDao = reopenedDb.ingredientDao()
            val restoredStepDao = reopenedDb.stepDao()
            val restoredTagDao = reopenedDb.recipeTagDao()

            assertEquals(50, restoredRecipeDao.count())

            for (id in seededRecipeIds) {
                val r = restoredRecipeDao.getById(id)
                assertNotNull("Recipe $id exists", r)
                checkNotNull(r)

                val i = id.toInt()
                assertEquals("Gourmet Dish Number $i", r.title)
                assertEquals("Description for recipe $i with rich culinary details", r.description)
                assertEquals((i % 6) + 1, r.servings)
                assertEquals("RecipeEntity.STATUS_SAVED", RecipeEntity.STATUS_SAVED, r.status)

                val ings = restoredIngDao.getByRecipeId(id)
                assertEquals("2 ingredients for recipe $id", 2, ings.size)
                assertEquals("Ingredient A$i", ings[0].name)
                assertEquals("Ingredient B$i", ings[1].name)

                val steps = restoredStepDao.getByRecipeId(id)
                assertEquals("2 steps for recipe $id", 2, steps.size)
                assertEquals("Chop ingredients for recipe $i", steps[0].text)
                assertEquals(1200, steps[1].timerSeconds)

                val tags = restoredTagDao.getTagsForRecipe(id)
                assertEquals(1, tags.size)
                assertEquals(starterTagId, tags[0].id)
            }

            // 7. Deep verify restored image files
            for ((relPath, originalBytes) in createdImageBytes) {
                val restoredFile = File(imagesDir, relPath)
                assertTrue("Image $relPath was restored", restoredFile.exists())
                assertEquals(
                    "Image $relPath content matches byte-for-byte",
                    String(originalBytes),
                    restoredFile.readText(Charsets.UTF_8)
                )
            }
        } finally {
            reopenedDb.close()
        }
    }
}
