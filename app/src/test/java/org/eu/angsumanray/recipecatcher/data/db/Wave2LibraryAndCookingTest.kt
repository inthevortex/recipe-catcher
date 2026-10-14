package org.eu.angsumanray.recipecatcher.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.eu.angsumanray.recipecatcher.data.db.entity.CookLogEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeTagEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import org.eu.angsumanray.recipecatcher.data.image.ImageStorageManager
import org.eu.angsumanray.recipecatcher.data.repo.CookLogRepository
import org.eu.angsumanray.recipecatcher.data.repo.RecipeFilter
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import org.eu.angsumanray.recipecatcher.data.repo.RecipeSortOrder
import org.eu.angsumanray.recipecatcher.data.repo.TagRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class Wave2LibraryAndCookingTest {

    private lateinit var database: RecipeDatabase
    private lateinit var repository: RecipeRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var cookLogRepository: CookLogRepository
    private lateinit var imageStorageManager: ImageStorageManager
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java)
            .addCallback(RecipeDatabase.starterSeedCallback)
            .allowMainThreadQueries()
            .build()
        imageStorageManager = ImageStorageManager(context, okhttp3.OkHttpClient())
        repository = RecipeRepository(database, imageStorageManager)
        tagRepository = TagRepository(database)
        cookLogRepository = CookLogRepository(database, imageStorageManager)
    }

    @After
    fun teardown() {
        database.close()
    }

    private suspend fun insertRecipeHelper(
        title: String,
        sourceKey: String,
        cuisine: String? = null,
        complexity: String? = "easy",
        spiceLevel: String? = "medium",
        dietType: String? = "vegetarian",
        kcal: Int? = 400,
        totalMin: Int? = 30,
        makeAhead: Boolean = false,
        freezerFriendly: Boolean = false,
        isFavourite: Boolean = false,
        reviewed: Boolean = true,
        savedAt: Long = System.currentTimeMillis(),
        tagNames: List<String> = emptyList(),
        dietTagNames: List<String> = emptyList(),
        equipmentNames: List<String> = emptyList()
    ): Long {
        val recipe = RecipeEntity(
            sourceType = "manual",
            sourceKey = sourceKey,
            title = title,
            cuisine = cuisine,
            complexity = complexity,
            complexityReason = "Test reason",
            spiceLevel = spiceLevel,
            dietType = dietType,
            kcalPerServing = kcal,
            totalMin = totalMin,
            makeAhead = makeAhead,
            freezerFriendly = freezerFriendly,
            isFavourite = isFavourite,
            reviewed = reviewed,
            status = RecipeEntity.STATUS_SAVED,
            savedAt = savedAt,
            createdAt = savedAt
        )

        val allTags = tagRepository.getAllTags()
        val tagIdMap = allTags.associate { (it.name.lowercase() to it.kind) to it.id }

        val tagIds = mutableListOf<Long>()
        tagNames.forEach { name ->
            tagIdMap[name.lowercase() to TagEntity.KIND_TAG]?.let { tagIds.add(it) }
        }
        dietTagNames.forEach { name ->
            tagIdMap[name.lowercase() to TagEntity.KIND_DIET]?.let { tagIds.add(it) }
        }
        equipmentNames.forEach { name ->
            tagIdMap[name.lowercase() to TagEntity.KIND_EQUIPMENT]?.let { tagIds.add(it) }
        }

        val ingredients = listOf(
            IngredientEntity(
                recipeId = 0,
                position = 0,
                name = "main ingredient",
                baseName = "main ingredient",
                category = "pantry",
                rawText = "1 cup main ingredient"
            )
        )
        val steps = listOf(
            StepEntity(recipeId = 0, position = 0, text = "Step instruction", timerSeconds = 300)
        )

        return repository.saveFullRecipe(recipe, ingredients, steps, tagIds)
    }

    @Test
    fun `fts search finds recipe by prefix match preserving unicode words`() = runBlocking {
        insertRecipeHelper(title = "Paneer Butter Masala", sourceKey = "PBM1")
        insertRecipeHelper(title = "Chicken Biryani", sourceKey = "CB1")

        // Search "pan" -> matches "Paneer"
        val results = repository.search("pan")
        assertEquals(1, results.size)
        assertEquals("Paneer Butter Masala", results.first().title)

        // Multiple words: "pan but"
        val results2 = repository.search("pan but")
        assertEquals(1, results2.size)

        // Non-matching term
        val empty = repository.search("tofu")
        assertEquals(0, empty.size)
    }

    @Test
    fun `filter by diet type and tags and without tags`() = runBlocking {
        // Recipe 1: Vegetarian, contains dairy tag, tagged "indian"
        val id1 = insertRecipeHelper(
            title = "Paneer Tikka",
            sourceKey = "PT1",
            dietType = "vegetarian",
            tagNames = listOf("indian", "paneer"),
            dietTagNames = listOf("dairy")
        )
        // Recipe 2: Vegan, no dairy, tagged "indian"
        val id2 = insertRecipeHelper(
            title = "Chana Masala",
            sourceKey = "CM1",
            dietType = "vegan",
            tagNames = listOf("indian"),
            dietTagNames = emptyList()
        )
        // Recipe 3: Non-veg, contains dairy, tagged "continental"
        val id3 = insertRecipeHelper(
            title = "Chicken Alfredo",
            sourceKey = "CA1",
            dietType = "non-veg",
            tagNames = listOf("continental", "chicken"),
            dietTagNames = listOf("dairy")
        )

        val dairyTagId = tagRepository.getTagsByKind(TagEntity.KIND_DIET).first { it.name == "dairy" }.id
        val indianTagId = tagRepository.getTagsByKind(TagEntity.KIND_TAG).first { it.name == "indian" }.id

        // Filter: dietType = "vegan"
        val veganList = repository.queryFilteredRecipes(RecipeFilter(dietType = "vegan"))
        assertEquals(1, veganList.size)
        assertEquals("Chana Masala", veganList.first().title)

        // Filter: without dairy
        val withoutDairyList = repository.queryFilteredRecipes(RecipeFilter(withoutDietTagIds = setOf(dairyTagId)))
        assertEquals(1, withoutDairyList.size)
        assertEquals("Chana Masala", withoutDairyList.first().title)

        // Filter: tag = "indian" AND without dairy
        val indianNoDairy = repository.queryFilteredRecipes(
            RecipeFilter(
                tagIds = setOf(indianTagId),
                withoutDietTagIds = setOf(dairyTagId)
            )
        )
        assertEquals(1, indianNoDairy.size)
        assertEquals("Chana Masala", indianNoDairy.first().title)
    }

    @Test
    fun `filter by favourites, never cooked, complexity, equipment, spice, flags, and toReview`() = runBlocking {
        val airFryerTagId = tagRepository.getTagsByKind(TagEntity.KIND_EQUIPMENT).first { it.name == "air fryer" }.id

        val id1 = insertRecipeHelper(
            title = "Air Fryer Samosa",
            sourceKey = "AFS1",
            complexity = "easy",
            spiceLevel = "hot",
            isFavourite = true,
            makeAhead = true,
            freezerFriendly = true,
            reviewed = false, // To review
            equipmentNames = listOf("air fryer")
        )
        val id2 = insertRecipeHelper(
            title = "Slow Cooker Stew",
            sourceKey = "SCS1",
            complexity = "hard",
            spiceLevel = "mild",
            isFavourite = false,
            makeAhead = false,
            freezerFriendly = false,
            reviewed = true
        )

        // Cook recipe 2
        cookLogRepository.addCookLog(id2, LocalDate.of(2026, 10, 1))

        // Filter favourites
        val favs = repository.queryFilteredRecipes(RecipeFilter(isFavourite = true))
        assertEquals(1, favs.size)
        assertEquals("Air Fryer Samosa", favs.first().title)

        // Filter never cooked (only recipe 1 should appear)
        val neverCooked = repository.queryFilteredRecipes(RecipeFilter(neverCooked = true))
        assertEquals(1, neverCooked.size)
        assertEquals("Air Fryer Samosa", neverCooked.first().title)

        // Filter complexity = hard
        val hard = repository.queryFilteredRecipes(RecipeFilter(complexity = "hard"))
        assertEquals(1, hard.size)
        assertEquals("Slow Cooker Stew", hard.first().title)

        // Filter equipment = air fryer
        val airFryer = repository.queryFilteredRecipes(RecipeFilter(equipmentTagIds = setOf(airFryerTagId)))
        assertEquals(1, airFryer.size)
        assertEquals("Air Fryer Samosa", airFryer.first().title)

        // Filter spice = hot
        val hot = repository.queryFilteredRecipes(RecipeFilter(spiceLevel = "hot"))
        assertEquals(1, hot.size)
        assertEquals("Air Fryer Samosa", hot.first().title)

        // Filter make-ahead and freezer-friendly
        val makeAhead = repository.queryFilteredRecipes(RecipeFilter(makeAhead = true, freezerFriendly = true))
        assertEquals(1, makeAhead.size)
        assertEquals("Air Fryer Samosa", makeAhead.first().title)

        // Filter to review
        val toReview = repository.queryFilteredRecipes(RecipeFilter(toReview = true))
        assertEquals(1, toReview.size)
        assertEquals("Air Fryer Samosa", toReview.first().title)
    }

    @Test
    fun `sorting by title, time, calories, and cooked dates`() = runBlocking {
        val t0 = 1000000L
        val idA = insertRecipeHelper(title = "Apple Pie", sourceKey = "A1", totalMin = 60, kcal = 500, savedAt = t0 + 100)
        val idB = insertRecipeHelper(title = "Banana Bread", sourceKey = "B1", totalMin = 20, kcal = 300, savedAt = t0 + 200)
        val idC = insertRecipeHelper(title = "Cherry Tart", sourceKey = "C1", totalMin = 45, kcal = 400, savedAt = t0 + 300)

        // Log cooking:
        // idA cooked on Oct 10
        cookLogRepository.addCookLog(idA, LocalDate.of(2026, 10, 10))
        // idB cooked on Oct 14 (most recently)
        cookLogRepository.addCookLog(idB, LocalDate.of(2026, 10, 14))
        // idC never cooked

        // Sort Title A-Z
        val titleSorted = repository.queryFilteredRecipes(RecipeFilter(sortOrder = RecipeSortOrder.TITLE_A_Z))
        assertEquals(listOf("Apple Pie", "Banana Bread", "Cherry Tart"), titleSorted.map { it.title })

        // Sort Total Time
        val timeSorted = repository.queryFilteredRecipes(RecipeFilter(sortOrder = RecipeSortOrder.TOTAL_TIME))
        assertEquals(listOf("Banana Bread", "Cherry Tart", "Apple Pie"), timeSorted.map { it.title })

        // Sort Calories
        val calSorted = repository.queryFilteredRecipes(RecipeFilter(sortOrder = RecipeSortOrder.CALORIES))
        assertEquals(listOf("Banana Bread", "Cherry Tart", "Apple Pie"), calSorted.map { it.title })

        // Sort Recently Cooked (Banana Bread [Oct 14] -> Apple Pie [Oct 10] -> Cherry Tart [never])
        val recentSorted = repository.queryFilteredRecipes(RecipeFilter(sortOrder = RecipeSortOrder.RECENTLY_COOKED))
        assertEquals(listOf("Banana Bread", "Apple Pie", "Cherry Tart"), recentSorted.map { it.title })

        // Sort Longest Since Cooked (Apple Pie [Oct 10] -> Banana Bread [Oct 14] -> Cherry Tart [never])
        val longestSorted = repository.queryFilteredRecipes(RecipeFilter(sortOrder = RecipeSortOrder.LONGEST_SINCE_COOKED))
        assertEquals(listOf("Apple Pie", "Banana Bread", "Cherry Tart"), longestSorted.map { it.title })
    }

    @Test
    fun `tag repository rename, delete, and merge rewrites links`() = runBlocking {
        val tagIdA = tagRepository.addTag(TagEntity.KIND_TAG, "TagAlpha")
        val tagIdB = tagRepository.addTag(TagEntity.KIND_TAG, "TagBeta")

        val r1 = insertRecipeHelper(title = "R1", sourceKey = "R1", tagNames = listOf("TagAlpha"))
        val r2 = insertRecipeHelper(title = "R2", sourceKey = "R2", tagNames = listOf("TagAlpha", "TagBeta"))

        // Check initial links
        val r1TagsBefore = repository.observeTags(r1).first()
        assertTrue(r1TagsBefore.any { it.name == "TagAlpha" })

        // Rename TagAlpha -> TagAlphaRenamed
        tagRepository.renameTag(tagIdA, "TagAlphaRenamed")
        val r1TagsAfterRename = repository.observeTags(r1).first()
        assertTrue(r1TagsAfterRename.any { it.name == "TagAlphaRenamed" })

        // Merge TagAlphaRenamed into TagBeta
        tagRepository.mergeTags(tagIdA, tagIdB)

        // TagAlphaRenamed should be deleted
        assertNull(database.tagDao().getById(tagIdA))

        // Both R1 and R2 should now link to TagBeta
        val r1TagsAfterMerge = repository.observeTags(r1).first()
        assertTrue(r1TagsAfterMerge.any { it.id == tagIdB })

        val r2TagsAfterMerge = repository.observeTags(r2).first()
        assertEquals(1, r2TagsAfterMerge.count { it.id == tagIdB }) // No duplicate link created

        // Delete TagBeta
        tagRepository.deleteTag(tagIdB)
        assertNull(database.tagDao().getById(tagIdB))
        val r1TagsFinal = repository.observeTags(r1).first()
        assertFalse(r1TagsFinal.any { it.id == tagIdB })
    }

    @Test
    fun `personal touches update and cook log persistence`() = runBlocking {
        val recipeId = insertRecipeHelper(title = "Curry", sourceKey = "C_TEST")

        // Favourite toggle
        repository.toggleFavourite(recipeId, true)
        assertTrue(repository.getRecipe(recipeId)!!.isFavourite)

        // Personal note
        repository.updatePersonalNote(recipeId, "Extra coriander leaves")
        assertEquals("Extra coriander leaves", repository.getRecipe(recipeId)!!.personalNote)

        // Search finds personal note via FTS
        val noteSearchResults = repository.search("coriander")
        assertEquals(1, noteSearchResults.size)
        assertEquals("Curry", noteSearchResults.first().title)

        // Cook log
        cookLogRepository.addCookLog(recipeId, LocalDate.of(2026, 10, 15), "Delicious", "images/cooked/sample.jpg")
        assertEquals(1, cookLogRepository.getTimesCooked(recipeId))
        assertEquals("2026-10-15", cookLogRepository.getLastCookedDate(recipeId))

        // Cover photo update
        repository.updateCoverPhotoPath(recipeId, "images/cooked/sample.jpg")
        assertEquals("images/cooked/sample.jpg", repository.getRecipe(recipeId)!!.coverPhotoPath)
    }

    @Test
    fun `deleting recipe cleans up its image files and cook photos`() = runBlocking {
        val recipeId = insertRecipeHelper(title = "Pie", sourceKey = "PIE1")

        // Create dummy source image and cook photo
        val sourceImgPath = imageStorageManager.saveImageBytes(recipeId, byteArrayOf(1, 2, 3))
        repository.updateImagePath(recipeId, sourceImgPath)

        val cookedImgPath = imageStorageManager.saveCookedImageBytes(byteArrayOf(4, 5, 6))
        cookLogRepository.addCookLog(recipeId, LocalDate.now(), photoPath = cookedImgPath)

        val sourceFile = File(context.filesDir, sourceImgPath)
        val cookedFile = File(context.filesDir, cookedImgPath)
        assertTrue(sourceFile.exists())
        assertTrue(cookedFile.exists())

        // Delete recipe
        repository.deleteRecipe(recipeId)

        // Check DB rows deleted
        assertNull(repository.getRecipe(recipeId))
        assertEquals(0, cookLogRepository.getCookLogs(recipeId).size)

        // Check image files deleted
        assertFalse(sourceFile.exists())
        assertFalse(cookedFile.exists())
    }

    @Test
    fun `cleanOrphanedImages removes unreferenced files on disk`() = runBlocking {
        val recipeId = insertRecipeHelper(title = "Soup", sourceKey = "SOUP1")
        val validSourcePath = imageStorageManager.saveImageBytes(recipeId, byteArrayOf(10, 20))
        repository.updateImagePath(recipeId, validSourcePath)

        // Create an unreferenced file in images/
        val orphan1 = File(imageStorageManager.imagesDir, "orphan_99999.jpg")
        orphan1.writeBytes(byteArrayOf(99))
        assertTrue(orphan1.exists())

        // Create an unreferenced file in images/cooked/
        val orphan2 = File(imageStorageManager.cookedImagesDir, "orphan_cooked.jpg")
        orphan2.writeBytes(byteArrayOf(88))
        assertTrue(orphan2.exists())

        val old = System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000
        orphan1.setLastModified(old)
        orphan2.setLastModified(old)

        // A fresh file may belong to a capture or cook photo whose row isn't written yet.
        val fresh = File(imageStorageManager.cookedImagesDir, "fresh_unsaved.jpg")
        fresh.writeBytes(byteArrayOf(77))

        val deleted = repository.cleanOrphanedImages()
        assertTrue(deleted >= 2)
        assertTrue(fresh.exists())
        assertFalse(orphan1.exists())
        assertFalse(orphan2.exists())

        // Valid referenced file must stay intact!
        assertTrue(File(context.filesDir, validSourcePath).exists())
    }

    @Test
    fun `renaming a tag onto an existing name merges instead of failing`() = runBlocking {
        val tagIdA = tagRepository.addTag(TagEntity.KIND_EQUIPMENT, "Air fryer oven")
        val tagIdB = tagRepository.addTag(TagEntity.KIND_EQUIPMENT, "OTG")
        val r1 = insertRecipeHelper(title = "R1", sourceKey = "RN1", equipmentNames = listOf("Air fryer oven"))
        val r2 = insertRecipeHelper(title = "R2", sourceKey = "RN2", equipmentNames = listOf("Air fryer oven", "OTG"))

        tagRepository.renameTag(tagIdA, "otg")

        assertNull(database.tagDao().getById(tagIdA))
        assertEquals(listOf(tagIdB), repository.getTags(r1).map { it.id })
        assertEquals(listOf(tagIdB), repository.getTags(r2).map { it.id })

        // A case-only rename of the same entry is a plain rename.
        tagRepository.renameTag(tagIdB, "Otg")
        assertEquals("Otg", database.tagDao().getById(tagIdB)!!.name)
    }

    @Test
    fun `deleting a cook log removes its photo and clears it as cover`() = runBlocking {
        val recipeId = insertRecipeHelper(title = "Dal", sourceKey = "DAL1")
        val photo = imageStorageManager.saveCookedImageBytes(byteArrayOf(1, 2))
        val logId = cookLogRepository.addCookLog(recipeId, LocalDate.now(), photoPath = photo)
        repository.updateCoverPhotoPath(recipeId, photo)

        cookLogRepository.deleteCookLogById(logId)

        assertEquals(0, cookLogRepository.getTimesCooked(recipeId))
        assertNull(repository.getRecipe(recipeId)!!.coverPhotoPath)
        assertFalse(File(context.filesDir, photo).exists())
    }

    @Test
    fun `fts query keeps uppercase operator words as search terms`() = runBlocking {
        assertEquals("salt* and* pepper*", RecipeRepository.sanitizeFtsQuery("Salt AND pepper"))
        insertRecipeHelper(title = "Not just dal", sourceKey = "NOT1")
        assertEquals(1, repository.search("NOT").size)
    }

    @Test
    fun `re-extraction keeps personal touches set meanwhile and user tags`() = runBlocking {
        val recipeId = insertRecipeHelper(title = "Old title", sourceKey = "REX1")
        val userTag = tagRepository.addTag(TagEntity.KIND_TAG, "Sunday")
        val aiTag = tagRepository.addTag(TagEntity.KIND_TAG, "Weeknight")
        database.recipeTagDao().insertAll(listOf(RecipeTagEntity(recipeId, userTag, RecipeTagEntity.SOURCE_USER)))
        cookLogRepository.addCookLog(recipeId, LocalDate.of(2026, 10, 1))

        // Personal touches change after the extraction snapshot was taken.
        repository.toggleFavourite(recipeId, true)
        repository.updatePersonalNote(recipeId, "Use ghee")
        repository.updateCoverPhotoPath(recipeId, "images/cooked/cover.jpg")

        val saved = repository.saveReExtraction(
            recipeId = recipeId,
            ingredients = listOf(IngredientEntity(recipeId = recipeId, position = 0, name = "rice", baseName = "rice", category = "grains", rawText = "1 cup rice")),
            steps = listOf(StepEntity(recipeId = recipeId, position = 0, text = "Boil")),
            aiTagIds = listOf(aiTag),
            usage = org.eu.angsumanray.recipecatcher.data.db.entity.LlmUsageEntity(purpose = "extraction", model = "m", inputTokens = 10, outputTokens = 5, cacheReadTokens = 0)
        ) { it.copy(title = "New title", isFavourite = false, personalNote = null, coverPhotoPath = null) }

        assertTrue(saved)
        val recipe = repository.getRecipe(recipeId)!!
        assertEquals("New title", recipe.title)
        assertTrue(recipe.isFavourite)
        assertEquals("Use ghee", recipe.personalNote)
        assertEquals("images/cooked/cover.jpg", recipe.coverPhotoPath)
        assertEquals(setOf(userTag, aiTag), repository.getTags(recipeId).map { it.id }.toSet())
        assertEquals(1, cookLogRepository.getTimesCooked(recipeId))
        assertEquals(listOf("rice"), repository.getIngredients(recipeId).map { it.name })
        assertEquals(1, database.llmUsageDao().getAll().size)
        assertEquals(1, repository.search("ghee").size)
    }
}
