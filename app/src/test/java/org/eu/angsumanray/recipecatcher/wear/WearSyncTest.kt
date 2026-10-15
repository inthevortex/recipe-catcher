package org.eu.angsumanray.recipecatcher.wear

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.eu.angsumanray.recipecatcher.capture.CommentFetcher
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanEntryEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanSlotEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.repo.PlannerRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WearSyncTest {

    private lateinit var context: Context
    private lateinit var database: RecipeDatabase
    private lateinit var plannerRepository: PlannerRepository
    private lateinit var wearSync: WearSync
    private val today = LocalDate.of(2026, 10, 15)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        plannerRepository = PlannerRepository(database, database.plannerDao(), database.cookLogDao(), database.recipeDao())
        wearSync = WearSync(context, database.plannerDao(), plannerRepository)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun buildSnapshot_listsTodaysEntriesWithTitlesSlotsAndDoneState() = runBlocking {
        val recipeId = database.recipeDao().insert(
            RecipeEntity(sourceType = "web", sourceKey = "k1", title = "Dal Tadka", status = RecipeEntity.STATUS_SAVED)
        )
        val dinner = database.plannerDao().insertSlot(PlanSlotEntity(date = today, name = "Dinner", time = LocalTime.of(20, 0), position = 2))
        val lunch = database.plannerDao().insertSlot(PlanSlotEntity(date = today, name = "Lunch", position = 1))
        val tomorrow = database.plannerDao().insertSlot(PlanSlotEntity(date = today.plusDays(1), name = "Lunch"))
        val dalId = database.plannerDao().insertEntry(PlanEntryEntity(slotId = dinner, kind = PlanEntryEntity.KIND_COOK, recipeId = recipeId))
        database.plannerDao().insertEntry(PlanEntryEntity(slotId = lunch, kind = PlanEntryEntity.KIND_PREP, text = "Soak chana", doneAt = 5L))
        database.plannerDao().insertEntry(PlanEntryEntity(slotId = tomorrow, kind = PlanEntryEntity.KIND_COOK, text = "Not today"))

        val snapshot = wearSync.buildSnapshot(today, now = 99L)

        assertEquals("2026-10-15", snapshot.dateIso)
        assertEquals(99L, snapshot.timestamp)
        assertEquals(listOf("Soak chana", "Dal Tadka"), snapshot.items.map { it.title })
        assertTrue(snapshot.items[0].done)
        val dal = snapshot.items[1]
        assertEquals(dalId, dal.id)
        assertEquals("Dinner", dal.slot)
        assertEquals("20:00", dal.time)
        assertEquals(PlanEntryEntity.KIND_COOK, dal.kind)
    }

    @Test
    fun parseEntryId_acceptsOnlyPositiveIds() {
        assertEquals(12L, WearMessageListenerService.parseEntryId("12".toByteArray()))
        assertEquals(12L, WearMessageListenerService.parseEntryId(" 12\n".toByteArray()))
        assertNull(WearMessageListenerService.parseEntryId("abc".toByteArray()))
        assertNull(WearMessageListenerService.parseEntryId("-3".toByteArray()))
        assertNull(WearMessageListenerService.parseEntryId(ByteArray(0)))
        assertNull(WearMessageListenerService.parseEntryId(null))
    }

    @Test
    fun commentFetcher_postUrlFromSharedText() {
        assertEquals(
            "https://www.instagram.com/p/ABC123/",
            CommentFetcher.postUrlFrom("Look at this! https://www.instagram.com/reel/ABC123/?igsh=xyz")
        )
        assertNull(CommentFetcher.postUrlFrom("https://example.com/recipe"))
        assertNull(CommentFetcher.postUrlFrom("no link"))
    }
}
