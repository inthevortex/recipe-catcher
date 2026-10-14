package org.eu.angsumanray.recipecatcher.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.dao.CookLogDao
import org.eu.angsumanray.recipecatcher.data.db.dao.PlannerDao
import org.eu.angsumanray.recipecatcher.data.db.dao.RecipeDao
import org.eu.angsumanray.recipecatcher.data.db.entity.CookLogEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanEntryEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanSlotEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlannerRepository @Inject constructor(
    private val database: RecipeDatabase,
    private val plannerDao: PlannerDao,
    private val cookLogDao: CookLogDao,
    private val recipeDao: RecipeDao
) {

    fun observeSlotsForDate(date: LocalDate): Flow<List<PlanSlotEntity>> =
        plannerDao.observeSlotsForDate(date)

    suspend fun getSlotsForDate(date: LocalDate): List<PlanSlotEntity> =
        plannerDao.getSlotsForDate(date)

    fun observeSlotsBetween(startDate: LocalDate, endDate: LocalDate): Flow<List<PlanSlotEntity>> =
        plannerDao.observeSlotsBetween(startDate, endDate)

    suspend fun getSlotsBetween(startDate: LocalDate, endDate: LocalDate): List<PlanSlotEntity> =
        plannerDao.getSlotsBetween(startDate, endDate)

    suspend fun getSlotById(id: Long): PlanSlotEntity? =
        plannerDao.getSlotById(id)

    suspend fun ensureDefaultSlotsForDate(date: LocalDate): List<PlanSlotEntity> {
        val existing = plannerDao.getSlotsForDate(date)
        if (existing.isNotEmpty()) return existing

        val defaults = listOf(
            PlanSlotEntity(date = date, name = "Breakfast", time = LocalTime.of(8, 30), position = 0),
            PlanSlotEntity(date = date, name = "Lunch", time = LocalTime.of(13, 0), position = 1),
            PlanSlotEntity(date = date, name = "Dinner", time = LocalTime.of(19, 30), position = 2)
        )
        database.withTransaction {
            plannerDao.insertSlots(defaults)
        }
        return plannerDao.getSlotsForDate(date)
    }

    suspend fun ensureDefaultSlotsForWeek(startDate: LocalDate, daysCount: Int = 7): List<PlanSlotEntity> {
        for (i in 0 until daysCount) {
            ensureDefaultSlotsForDate(startDate.plusDays(i.toLong()))
        }
        return plannerDao.getSlotsBetween(startDate, startDate.plusDays((daysCount - 1).toLong()))
    }

    suspend fun insertSlot(slot: PlanSlotEntity): Long =
        plannerDao.insertSlot(slot)

    suspend fun updateSlot(slot: PlanSlotEntity) =
        plannerDao.updateSlot(slot)

    suspend fun deleteSlot(slot: PlanSlotEntity) {
        database.withTransaction {
            plannerDao.deleteEntriesForSlot(slot.id)
            plannerDao.deleteSlot(slot)
        }
    }

    fun observeEntriesForSlot(slotId: Long): Flow<List<PlanEntryEntity>> =
        plannerDao.observeEntriesForSlot(slotId)

    suspend fun getEntriesForSlot(slotId: Long): List<PlanEntryEntity> =
        plannerDao.getEntriesForSlot(slotId)

    fun observeEntriesForDate(date: LocalDate): Flow<List<PlanEntryEntity>> =
        plannerDao.observeEntriesForDate(date)

    suspend fun getEntriesForDate(date: LocalDate): List<PlanEntryEntity> =
        plannerDao.getEntriesForDate(date)

    fun observeEntriesBetween(startDate: LocalDate, endDate: LocalDate): Flow<List<PlanEntryEntity>> =
        plannerDao.observeEntriesBetween(startDate, endDate)

    suspend fun getEntriesBetween(startDate: LocalDate, endDate: LocalDate): List<PlanEntryEntity> =
        plannerDao.getEntriesBetween(startDate, endDate)

    suspend fun getEntryById(id: Long): PlanEntryEntity? =
        plannerDao.getEntryById(id)

    suspend fun insertEntry(entry: PlanEntryEntity): Long =
        plannerDao.insertEntry(entry)

    suspend fun updateEntry(entry: PlanEntryEntity) =
        plannerDao.updateEntry(entry)

    suspend fun deleteEntry(entry: PlanEntryEntity) =
        plannerDao.deleteEntry(entry)

    suspend fun moveEntry(entryId: Long, newSlotId: Long, newPosition: Int) =
        plannerDao.moveEntry(entryId, newSlotId, newPosition)

    suspend fun addCookEntry(
        slotId: Long,
        recipeId: Long,
        portions: Int,
        portionsEatenNow: Int = 1
    ): Long {
        val existing = plannerDao.getEntriesForSlot(slotId)
        val entry = PlanEntryEntity(
            slotId = slotId,
            kind = PlanEntryEntity.KIND_COOK,
            recipeId = recipeId,
            portions = portions.coerceAtLeast(1),
            portionsEatenNow = portionsEatenNow.coerceAtLeast(1),
            position = existing.size
        )
        return plannerDao.insertEntry(entry)
    }

    suspend fun addPrepEntry(
        slotId: Long,
        recipeId: Long? = null,
        text: String? = null,
        portions: Int = 1,
        prepNightBefore: Boolean = false
    ): Long {
        val existing = plannerDao.getEntriesForSlot(slotId)
        val entry = PlanEntryEntity(
            slotId = slotId,
            kind = PlanEntryEntity.KIND_PREP,
            recipeId = recipeId,
            text = text,
            portions = portions.coerceAtLeast(1),
            prepNightBefore = prepNightBefore,
            position = existing.size
        )
        return plannerDao.insertEntry(entry)
    }

    suspend fun addLeftoverEntry(
        slotId: Long,
        fromEntryId: Long,
        portions: Int = 1
    ): Long {
        val existing = plannerDao.getEntriesForSlot(slotId)
        val fromEntry = plannerDao.getEntryById(fromEntryId)
        val entry = PlanEntryEntity(
            slotId = slotId,
            kind = PlanEntryEntity.KIND_LEFTOVER,
            recipeId = fromEntry?.recipeId,
            text = fromEntry?.text,
            portions = portions.coerceAtLeast(1),
            fromEntryId = fromEntryId,
            position = existing.size
        )
        return plannerDao.insertEntry(entry)
    }

    suspend fun copyPreviousWeekLayout(targetWeekStart: LocalDate): Boolean {
        val prevWeekStart = targetWeekStart.minusWeeks(1)
        val prevWeekEnd = prevWeekStart.plusDays(6)
        val prevSlots = plannerDao.getSlotsBetween(prevWeekStart, prevWeekEnd)
        if (prevSlots.isEmpty()) return false

        database.withTransaction {
            val targetWeekEnd = targetWeekStart.plusDays(6)
            val currentTargetSlots = plannerDao.getSlotsBetween(targetWeekStart, targetWeekEnd)
            for (slot in currentTargetSlots) {
                val entries = plannerDao.getEntriesForSlot(slot.id)
                if (entries.isEmpty()) {
                    plannerDao.deleteSlot(slot)
                }
            }

            // Copy slots from previous week for each day
            for (offset in 0L..6L) {
                val prevDate = prevWeekStart.plusDays(offset)
                val newDate = targetWeekStart.plusDays(offset)
                val daySlots = prevSlots.filter { it.date == prevDate }
                val newSlots = daySlots.map { slot ->
                    PlanSlotEntity(
                        date = newDate,
                        name = slot.name,
                        time = slot.time,
                        position = slot.position
                    )
                }
                if (newSlots.isNotEmpty()) {
                    plannerDao.insertSlots(newSlots)
                }
            }
        }
        return true
    }

    suspend fun toggleEntryDone(
        entryId: Long,
        photoPath: String? = null,
        note: String? = null
    ) {
        val entry = plannerDao.getEntryById(entryId) ?: return
        if (entry.doneAt != null) {
            plannerDao.setEntryDone(entryId, null)
        } else {
            val now = System.currentTimeMillis()
            plannerDao.setEntryDone(entryId, now)
            if (entry.kind == PlanEntryEntity.KIND_COOK && entry.recipeId != null) {
                cookLogDao.insert(
                    CookLogEntity(
                        recipeId = entry.recipeId,
                        cookedOn = LocalDate.now(),
                        note = note,
                        photoPath = photoPath
                    )
                )
            }
        }
    }

    suspend fun getRecipe(recipeId: Long): RecipeEntity? =
        recipeDao.getById(recipeId)

    suspend fun getAllRecipes(): List<RecipeEntity> =
        recipeDao.getAllSaved()
}
