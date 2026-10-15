package org.eu.angsumanray.recipecatcher.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.eu.angsumanray.recipecatcher.core.model.PlanEntry
import org.eu.angsumanray.recipecatcher.core.model.PlanSlot
import org.eu.angsumanray.recipecatcher.core.planner.AvailableLeftover
import org.eu.angsumanray.recipecatcher.core.planner.PortionLedger
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.dao.CookLogDao
import org.eu.angsumanray.recipecatcher.data.db.dao.PlannerDao
import org.eu.angsumanray.recipecatcher.data.db.dao.RecipeDao
import org.eu.angsumanray.recipecatcher.data.db.entity.CookLogEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanEntryEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanSlotEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlannerRepository @Inject constructor(
    private val database: RecipeDatabase,
    private val plannerDao: PlannerDao,
    private val cookLogDao: CookLogDao,
    private val recipeDao: RecipeDao,
    private val settingsStore: SettingsStore? = null
) {

    private fun parseLocalTimeOrDefault(raw: String?, fallback: LocalTime): LocalTime {
        if (raw.isNullOrBlank()) return fallback
        return try {
            val trimmed = raw.trim()
            val parts = trimmed.split(":")
            if (parts.size >= 2) {
                LocalTime.of(parts[0].toInt(), parts[1].toInt())
            } else {
                LocalTime.parse(trimmed)
            }
        } catch (_: Exception) {
            fallback
        }
    }

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

        val breakfastTime = settingsStore?.let {
            parseLocalTimeOrDefault(it.breakfastTimeFlow.first(), LocalTime.of(8, 30))
        } ?: LocalTime.of(8, 30)

        val lunchTime = settingsStore?.let {
            parseLocalTimeOrDefault(it.lunchTimeFlow.first(), LocalTime.of(13, 0))
        } ?: LocalTime.of(13, 0)

        val dinnerTime = settingsStore?.let {
            parseLocalTimeOrDefault(it.dinnerTimeFlow.first(), LocalTime.of(19, 30))
        } ?: LocalTime.of(19, 30)

        val defaults = listOf(
            PlanSlotEntity(date = date, name = "Breakfast", time = breakfastTime, position = 0),
            PlanSlotEntity(date = date, name = "Lunch", time = lunchTime, position = 1),
            PlanSlotEntity(date = date, name = "Dinner", time = dinnerTime, position = 2)
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

    /**
     * Deletes [slot] and its entries. Leftover entries elsewhere that draw on its cook entries are
     * removed when [removeLeftovers] is set; otherwise they stay with their link cleared.
     */
    suspend fun deleteSlot(slot: PlanSlotEntity, removeLeftovers: Boolean = false) {
        database.withTransaction {
            if (removeLeftovers) {
                val cookIds = plannerDao.getEntriesForSlot(slot.id)
                    .filter { it.kind == PlanEntryEntity.KIND_COOK }
                    .map { it.id }
                if (cookIds.isNotEmpty()) plannerDao.deleteLeftoversOf(cookIds)
            }
            plannerDao.deleteEntriesForSlot(slot.id)
            plannerDao.deleteSlot(slot)
        }
    }

    /** Leftover entries in other slots that would lose their source if [slot] were deleted. */
    suspend fun countLeftoversDependingOnSlot(slot: PlanSlotEntity): Int {
        val cookIds = plannerDao.getEntriesForSlot(slot.id)
            .filter { it.kind == PlanEntryEntity.KIND_COOK }
            .map { it.id }
        if (cookIds.isEmpty()) return 0
        return plannerDao.getLeftoversOf(cookIds).count { it.slotId != slot.id }
    }

    suspend fun countLeftoversDependingOn(entry: PlanEntryEntity): Int =
        if (entry.kind == PlanEntryEntity.KIND_COOK) plannerDao.getLeftoversOf(listOf(entry.id)).size else 0

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

    /**
     * Deletes [entry]. For a cook entry, its leftover entries are removed when [removeLeftovers] is set;
     * otherwise they stay with their link cleared.
     */
    suspend fun deleteEntry(entry: PlanEntryEntity, removeLeftovers: Boolean = false) {
        database.withTransaction {
            if (removeLeftovers && entry.kind == PlanEntryEntity.KIND_COOK) {
                plannerDao.deleteLeftoversOf(listOf(entry.id))
            }
            plannerDao.deleteEntry(entry)
        }
    }

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
            portionsEatenNow = portionsEatenNow.coerceIn(0, portions.coerceAtLeast(1)),
            position = existing.size
        )
        return plannerDao.insertEntry(entry)
    }

    /**
     * Adds accepted Suggest picks as cook entries in one transaction, given as (slot ID, recipe ID,
     * portions). With [skipOccupiedSlots], a slot that gained an entry since the suggestions were
     * made is left alone. Returns how many entries were added.
     */
    suspend fun addSuggestedCookEntries(
        picks: List<Triple<Long, Long, Int>>,
        skipOccupiedSlots: Boolean
    ): Int = database.withTransaction {
        var added = 0
        for ((slotId, recipeId, portions) in picks) {
            if (plannerDao.getSlotById(slotId) == null || recipeDao.getById(recipeId) == null) continue
            if (skipOccupiedSlots && plannerDao.getEntriesForSlot(slotId).isNotEmpty()) continue
            addCookEntry(slotId = slotId, recipeId = recipeId, portions = portions)
            added++
        }
        added
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

    /**
     * Plans [portions] of a leftover. A negative [fromEntryId] names a cooked-food item (see
     * [availableLeftovers]); its portions are taken from the item, which is removed once used up.
     */
    suspend fun addLeftoverEntry(
        slotId: Long,
        fromEntryId: Long,
        portions: Int = 1
    ): Long {
        if (fromEntryId < 0) return addCookedFoodLeftover(slotId, -fromEntryId, portions)

        val existing = plannerDao.getEntriesForSlot(slotId)
        val source = resolveCookSource(fromEntryId)
        requireNotNull(source) { "Leftovers must come from a cook entry" }
        val entry = PlanEntryEntity(
            slotId = slotId,
            kind = PlanEntryEntity.KIND_LEFTOVER,
            recipeId = source.recipeId,
            text = source.text,
            portions = portions.coerceAtLeast(1),
            fromEntryId = source.id,
            position = existing.size
        )
        return plannerDao.insertEntry(entry)
    }

    private suspend fun addCookedFoodLeftover(slotId: Long, cookedFoodId: Long, portions: Int): Long =
        database.withTransaction {
            val cookedFoodDao = database.cookedFoodDao()
            val cooked = requireNotNull(cookedFoodDao.getCookedFoodItemById(cookedFoodId)) {
                "Cooked food $cookedFoodId no longer exists"
            }
            require(cooked.portions > 0) { "No portions of ${cooked.name} left" }
            val taken = portions.coerceIn(1, cooked.portions)
            val entry = PlanEntryEntity(
                slotId = slotId,
                kind = PlanEntryEntity.KIND_LEFTOVER,
                recipeId = cooked.recipeId,
                text = cooked.name,
                portions = taken,
                fromEntryId = null,
                position = plannerDao.getEntriesForSlot(slotId).size
            )
            val left = cooked.portions - taken
            if (left > 0) cookedFoodDao.updatePortions(cookedFoodId, left) else cookedFoodDao.deleteById(cookedFoodId)
            plannerDao.insertEntry(entry)
        }

    /** Follows leftover links from [entryId] back to the cook entry that made the food. */
    private suspend fun resolveCookSource(entryId: Long): PlanEntryEntity? {
        val chain = mutableMapOf<Long, PlanEntryEntity>()
        var nextId: Long? = entryId
        while (nextId != null && nextId !in chain) {
            val entry = plannerDao.getEntryById(nextId) ?: break
            chain[entry.id] = entry
            nextId = if (entry.kind == PlanEntryEntity.KIND_COOK) null else entry.fromEntryId
        }
        val start = chain[entryId] ?: return null
        val source = PortionLedger.resolveCookSource(start.toCore(), chain.mapValues { it.value.toCore() })
        return source?.let { chain[it.id] }
    }

    /**
     * Leftovers that can be planned on [date]: cook entries from the [lookbackDays] before it (and the
     * day itself) with portions still unclaimed, counting leftover entries planned in any week.
     */
    suspend fun availableLeftovers(date: LocalDate, lookbackDays: Long = 7): List<AvailableLeftover> {
        val slots = plannerDao.getSlotsBetween(date.minusDays(lookbackDays), date)
        val planLeftovers = if (slots.isNotEmpty()) {
            val cooks = plannerDao.getEntriesForSlots(slots.map { it.id })
                .filter { it.kind == PlanEntryEntity.KIND_COOK }
            if (cooks.isNotEmpty()) {
                val leftovers = plannerDao.getLeftoversOf(cooks.map { it.id })
                PortionLedger.availableLeftovers(
                    targetDate = date.toString(),
                    allSlots = slots.map { it.toCore() },
                    allEntries = (cooks + leftovers).map { it.toCore() }
                )
            } else emptyList()
        } else emptyList()

        // Cooked-food items carry a negated ID and their dish name in slotName.
        val cookedFoodLeftovers = database.cookedFoodDao().getAllCookedFoodItems()
            .filter { it.portions > 0 && it.dateCooked <= date.toString() }
            .map { cooked ->
                AvailableLeftover(
                    cookEntryId = -cooked.id,
                    recipeId = cooked.recipeId,
                    slotId = 0L,
                    slotDate = cooked.dateCooked,
                    slotName = cooked.name,
                    initialPortions = cooked.portions,
                    remainingPortions = cooked.portions
                )
            }

        return planLeftovers + cookedFoodLeftovers
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

            // Slots that still hold food were kept; don't add a second slot with the same name.
            val keptNames = plannerDao.getSlotsBetween(targetWeekStart, targetWeekEnd)
                .groupBy({ it.date }, { it.name.trim().lowercase() })

            for (offset in 0L..6L) {
                val prevDate = prevWeekStart.plusDays(offset)
                val newDate = targetWeekStart.plusDays(offset)
                val taken = keptNames[newDate].orEmpty()
                val daySlots = prevSlots.filter { it.date == prevDate && it.name.trim().lowercase() !in taken }
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
        setEntryDone(entry, done = entry.doneAt == null, photoPath = photoPath, note = note)
    }

    /** Marks [entryId] done without toggling, so a repeated tap or a stale notification can't undo it. */
    suspend fun markEntryDone(
        entryId: Long,
        cookedOn: LocalDate = LocalDate.now(),
        photoPath: String? = null,
        note: String? = null
    ) {
        val entry = plannerDao.getEntryById(entryId) ?: return
        if (entry.doneAt == null) setEntryDone(entry, done = true, photoPath = photoPath, note = note, cookedOn = cookedOn)
    }

    /**
     * Ticking a cook entry writes the cooked log; unticking removes the log row that tick wrote, so
     * toggling back and forth doesn't inflate the recipe's cook count.
     */
    private suspend fun setEntryDone(
        entry: PlanEntryEntity,
        done: Boolean,
        photoPath: String? = null,
        note: String? = null,
        cookedOn: LocalDate = LocalDate.now()
    ) {
        database.withTransaction {
            val logsCook = entry.kind == PlanEntryEntity.KIND_COOK && entry.recipeId != null
            if (done) {
                plannerDao.setEntryDone(entry.id, System.currentTimeMillis())
                if (logsCook) {
                    cookLogDao.insert(
                        CookLogEntity(
                            recipeId = entry.recipeId!!,
                            cookedOn = cookedOn,
                            note = note,
                            photoPath = photoPath
                        )
                    )
                }
            } else {
                plannerDao.setEntryDone(entry.id, null)
                val doneAt = entry.doneAt
                if (logsCook && doneAt != null) {
                    // Only a log dated the day of the tick is removed; one backdated from the sheet stays.
                    val doneOn = Instant.ofEpochMilli(doneAt).atZone(ZoneId.systemDefault()).toLocalDate()
                    cookLogDao.deleteLatestForRecipeOn(entry.recipeId!!, doneOn)
                }
            }
        }
    }

    suspend fun getRecipe(recipeId: Long): RecipeEntity? =
        recipeDao.getById(recipeId)

    suspend fun getAllRecipes(): List<RecipeEntity> =
        recipeDao.getAllSaved()
}

private fun PlanSlotEntity.toCore() = PlanSlot(
    id = id,
    date = date.toString(),
    name = name,
    time = time?.toString(),
    position = position
)

private fun PlanEntryEntity.toCore() = PlanEntry(
    id = id,
    slotId = slotId,
    kind = kind,
    recipeId = recipeId,
    text = text,
    portions = portions,
    portionsEatenNow = portionsEatenNow,
    fromEntryId = fromEntryId,
    prepNightBefore = prepNightBefore,
    doneAt = doneAt,
    position = position
)
