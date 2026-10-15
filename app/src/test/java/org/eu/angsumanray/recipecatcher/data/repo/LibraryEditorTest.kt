package org.eu.angsumanray.recipecatcher.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.eu.angsumanray.recipecatcher.core.llm.LibraryEditProposal
import org.eu.angsumanray.recipecatcher.core.llm.RecipeEdit
import org.eu.angsumanray.recipecatcher.core.llm.TagRef
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeTagEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryEditorTest {

    private lateinit var database: RecipeDatabase
    private lateinit var editor: LibraryEditor
    private var paneerId = 0L
    private var chickenId = 0L
    private var dairyTagId = 0L
    private var quickTagId = 0L

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        editor = LibraryEditor(database)

        val recipeDao = database.recipeDao()
        paneerId = recipeDao.insert(RecipeEntity(sourceType = "manual", sourceKey = "p", title = "Palak Paneer", status = RecipeEntity.STATUS_SAVED, dietType = "vegan"))
        chickenId = recipeDao.insert(RecipeEntity(sourceType = "manual", sourceKey = "c", title = "Chicken Curry", status = RecipeEntity.STATUS_SAVED))
        dairyTagId = database.tagDao().insert(TagEntity(kind = TagEntity.KIND_DIET, name = "dairy", position = 1))
        quickTagId = database.tagDao().insert(TagEntity(kind = TagEntity.KIND_TAG, name = "quick", position = 1))
        database.recipeTagDao().insertAll(listOf(RecipeTagEntity(paneerId, quickTagId, RecipeTagEntity.SOURCE_AI)))
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun tagNames(recipeId: Long) = runBlocking {
        database.recipeTagDao().getTagsForRecipe(recipeId).map { "${it.kind}:${it.name}" }.toSet()
    }

    @Test
    fun applyThenUndoRestoresTagsFieldsAndRemovesCreatedTags() = runBlocking {
        val proposal = LibraryEditProposal(
            summary = "Fix paneer",
            edits = listOf(
                RecipeEdit(
                    recipeId = paneerId,
                    add = listOf(TagRef("diet", "Dairy"), TagRef("tag", "comfort food")),
                    remove = listOf(TagRef("tag", "quick")),
                    dietType = "vegetarian"
                ),
                RecipeEdit(recipeId = chickenId, add = listOf(TagRef("tag", "comfort food")))
            )
        )

        val applied = editor.apply(proposal, skippedRecipeIds = setOf(chickenId))

        assertEquals(LibraryEditProposal.STATUS_APPLIED, applied.status)
        assertEquals(setOf("diet:dairy", "tag:comfort food"), tagNames(paneerId))
        assertTrue(tagNames(chickenId).isEmpty())
        assertEquals("vegetarian", database.recipeDao().getById(paneerId)!!.dietType)
        assertEquals(1, applied.applied.size)
        assertEquals(1, applied.createdTagIds.size)

        val undone = editor.undo(applied)

        assertEquals(LibraryEditProposal.STATUS_UNDONE, undone.status)
        assertEquals(setOf("tag:quick"), tagNames(paneerId))
        assertEquals("vegan", database.recipeDao().getById(paneerId)!!.dietType)
        assertNull(database.tagDao().getByNameAndKind("comfort food", TagEntity.KIND_TAG))
        assertEquals(RecipeTagEntity.SOURCE_AI, database.recipeTagDao().getLinksForRecipe(paneerId).single().source)
    }

    @Test
    fun applySkipsChangesAlreadyInPlace() = runBlocking {
        val proposal = LibraryEditProposal(
            summary = "Tag quick",
            edits = listOf(
                RecipeEdit(recipeId = paneerId, add = listOf(TagRef("tag", "quick")), dietType = "vegan"),
                RecipeEdit(recipeId = chickenId, remove = listOf(TagRef("tag", "missing tag")))
            )
        )

        val applied = editor.apply(proposal)

        assertTrue(applied.applied.isEmpty())
        assertTrue(applied.createdTagIds.isEmpty())
        assertFalse(database.tagDao().getAll().any { it.name == "missing tag" })
    }
}
