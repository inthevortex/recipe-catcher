package org.eu.angsumanray.recipecatcher.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.eu.angsumanray.recipecatcher.core.model.GroceryItem
import org.eu.angsumanray.recipecatcher.core.model.GrocerySource
import org.eu.angsumanray.recipecatcher.core.model.Ingredient
import org.eu.angsumanray.recipecatcher.core.model.InventoryItem
import org.eu.angsumanray.recipecatcher.core.model.InventoryStatus
import org.eu.angsumanray.recipecatcher.core.model.PlanEntry
import org.eu.angsumanray.recipecatcher.core.model.Recipe
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.core.planner.GroceryBuilder
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.dao.IngredientDao
import org.eu.angsumanray.recipecatcher.data.db.dao.ListsDao
import org.eu.angsumanray.recipecatcher.data.db.dao.PlannerDao
import org.eu.angsumanray.recipecatcher.data.db.dao.RecipeDao
import org.eu.angsumanray.recipecatcher.data.db.entity.GroceryItemEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.InventoryItemEntity
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ListsRepository @Inject constructor(
    private val database: RecipeDatabase,
    private val listsDao: ListsDao,
    private val plannerDao: PlannerDao,
    private val recipeDao: RecipeDao,
    private val ingredientDao: IngredientDao
) {

    // --- Grocery ---

    fun observeGroceryItems(weekStart: LocalDate? = null): Flow<List<GroceryItemEntity>> =
        listsDao.observeGroceryItems(weekStart)

    suspend fun getGroceryItems(weekStart: LocalDate? = null): List<GroceryItemEntity> =
        listsDao.getGroceryItems(weekStart)

    suspend fun addManualGroceryItem(
        name: String,
        category: String,
        quantityText: String? = null,
        weekStart: LocalDate? = null
    ): Long {
        val baseName = name.trim().lowercase(Locale.ROOT)
        val entity = GroceryItemEntity(
            name = name.trim(),
            baseName = baseName,
            category = category.trim().lowercase(Locale.ROOT),
            quantityText = quantityText?.trim()?.ifEmpty { null },
            source = GroceryItemEntity.SOURCE_MANUAL,
            weekStart = weekStart,
            checked = false
        )
        return listsDao.insertGroceryItem(entity)
    }

    suspend fun toggleGroceryItemChecked(id: Long, checked: Boolean) {
        database.withTransaction {
            listsDao.setGroceryItemChecked(id, checked)
            if (checked) {
                val item = listsDao.getGroceryItemById(id)
                if (item != null) {
                    val baseKey = (item.baseName ?: item.name).trim().lowercase(Locale.ROOT)
                    val existingInv = listsDao.getInventoryItemByBaseName(baseKey)
                    val invEntity = InventoryItemEntity(
                        id = existingInv?.id ?: 0L,
                        name = item.name,
                        baseName = baseKey,
                        category = item.category,
                        status = InventoryItemEntity.STATUS_IN_STOCK,
                        updatedAt = System.currentTimeMillis()
                    )
                    listsDao.upsertInventoryItem(invEntity)
                }
            }
        }
    }

    suspend fun deleteGroceryItem(id: Long) =
        listsDao.deleteGroceryItemById(id)

    suspend fun clearCheckedGroceryItems(weekStart: LocalDate? = null) =
        listsDao.clearCheckedGroceryItems(weekStart)

    suspend fun buildFromPlan(weekStart: LocalDate) {
        val weekEnd = weekStart.plusDays(6)
        val planEntriesEntities = plannerDao.getEntriesBetween(weekStart, weekEnd)

        // Map PlanEntryEntities to core PlanEntry domain models
        val coreEntries = planEntriesEntities.map { entity ->
            PlanEntry(
                id = entity.id,
                slotId = entity.slotId,
                kind = entity.kind,
                recipeId = entity.recipeId,
                text = entity.text,
                portions = entity.portions,
                portionsEatenNow = entity.portionsEatenNow,
                fromEntryId = entity.fromEntryId,
                prepNightBefore = entity.prepNightBefore,
                doneAt = entity.doneAt,
                position = entity.position
            )
        }

        // Fetch recipes and ingredients
        val recipeIds = planEntriesEntities.mapNotNull { it.recipeId }.distinct()
        val recipeMap = mutableMapOf<Long, Recipe>()
        for (id in recipeIds) {
            val rEntity = recipeDao.getById(id) ?: continue
            val ingEntities = ingredientDao.getByRecipeId(id)
            val coreIngredients = ingEntities.map { ing ->
                Ingredient(
                    id = ing.id,
                    recipeId = ing.recipeId,
                    position = ing.position,
                    groupName = ing.groupName,
                    quantity = ing.quantity,
                    quantityMax = ing.quantityMax,
                    unit = ing.unit,
                    name = ing.name,
                    baseName = ing.baseName,
                    category = ing.category,
                    note = ing.note,
                    rawText = ing.rawText
                )
            }
            recipeMap[id] = Recipe(
                id = rEntity.id,
                sourceType = SourceType.from(rEntity.sourceType),
                sourceKey = rEntity.sourceKey,
                title = rEntity.title,
                servings = rEntity.servings,
                ingredients = coreIngredients
            )
        }

        // Fetch existing inventory and map to domain
        val inventoryEntities = listsDao.getAllInventoryItems()
        val coreInventory = inventoryEntities.map {
            InventoryItem(
                id = it.id,
                name = it.name,
                baseName = it.baseName,
                category = it.category,
                status = it.status,
                updatedAt = it.updatedAt
            )
        }

        // Fetch existing grocery items for this week
        val existingGroceryEntities = listsDao.getGroceryItems(weekStart)
        val coreExistingGrocery = existingGroceryEntities.map {
            GroceryItem(
                id = it.id,
                name = it.name,
                baseName = it.baseName,
                category = it.category,
                quantityText = it.quantityText,
                source = it.source,
                weekStart = it.weekStart?.toString(),
                checked = it.checked,
                position = it.position
            )
        }

        // Run core GroceryBuilder
        val builtItems = GroceryBuilder.buildFromPlan(
            entries = coreEntries,
            recipes = recipeMap,
            inventory = coreInventory,
            existingGroceryItems = coreExistingGrocery,
            weekStart = weekStart.toString()
        )

        // Persist built items in a transaction
        database.withTransaction {
            listsDao.clearPlanGroceryItems(weekStart)
            val entitiesToInsert = builtItems.filter { it.source != GrocerySource.MANUAL.value }.map { core ->
                GroceryItemEntity(
                    id = if (coreExistingGrocery.any { it.id == core.id && core.id > 0L }) core.id else 0L,
                    name = core.name,
                    baseName = core.baseName,
                    category = core.category,
                    quantityText = core.quantityText,
                    source = core.source,
                    weekStart = weekStart,
                    checked = core.checked,
                    position = core.position
                )
            }
            listsDao.insertAllGroceryItems(entitiesToInsert)
        }
    }

    // --- Inventory ---

    fun observeInventoryItems(): Flow<List<InventoryItemEntity>> =
        listsDao.observeAllInventoryItems()

    suspend fun getAllInventoryItems(): List<InventoryItemEntity> =
        listsDao.getAllInventoryItems()

    suspend fun addInventoryItem(
        name: String,
        baseName: String = name.trim().lowercase(Locale.ROOT),
        category: String,
        status: String = InventoryItemEntity.STATUS_IN_STOCK
    ): Long {
        val cleanBase = baseName.trim().lowercase(Locale.ROOT)
        val existing = listsDao.getInventoryItemByBaseName(cleanBase)
        val entity = InventoryItemEntity(
            id = existing?.id ?: 0L,
            name = name.trim(),
            baseName = cleanBase,
            category = category.trim().lowercase(Locale.ROOT),
            status = status,
            updatedAt = System.currentTimeMillis()
        )
        return listsDao.upsertInventoryItem(entity)
    }

    suspend fun setInventoryStatus(id: Long, status: String) {
        database.withTransaction {
            listsDao.updateInventoryStatus(id, status)
            if (status == InventoryItemEntity.STATUS_OUT) {
                // When marked OUT, add to grocery list if no unbought line for it exists in any week
                val item = listsDao.getInventoryItemById(id)
                if (item != null) {
                    val alreadyOnList = listsDao.getAllGroceryItems().any {
                        !it.checked && (it.baseName ?: it.name).equals(item.baseName, ignoreCase = true)
                    }
                    if (!alreadyOnList) {
                        listsDao.insertGroceryItem(
                            GroceryItemEntity(
                                name = item.name,
                                baseName = item.baseName,
                                category = item.category,
                                source = GroceryItemEntity.SOURCE_INVENTORY,
                                checked = false
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun applyPantryReconciliation(
        items: List<org.eu.angsumanray.recipecatcher.core.planner.PantryReconciliationItem>
    ) {
        database.withTransaction {
            for (item in items) {
                val newStatus = when (item.action) {
                    org.eu.angsumanray.recipecatcher.core.planner.PantryAction.IN_STOCK -> InventoryItemEntity.STATUS_IN_STOCK
                    org.eu.angsumanray.recipecatcher.core.planner.PantryAction.LOW -> InventoryItemEntity.STATUS_LOW
                    org.eu.angsumanray.recipecatcher.core.planner.PantryAction.OUT -> InventoryItemEntity.STATUS_OUT
                    org.eu.angsumanray.recipecatcher.core.planner.PantryAction.DONT_CHANGE -> continue
                }

                val existing = item.matchedInventoryItem?.id?.takeIf { it > 0 }
                    ?: listsDao.getInventoryItemByBaseName(item.baseName)?.id

                if (existing != null) {
                    setInventoryStatus(existing, newStatus)
                } else {
                    val entity = InventoryItemEntity(
                        name = item.ingredientName,
                        baseName = item.baseName,
                        category = item.category.ifBlank { "other" },
                        status = newStatus,
                        updatedAt = System.currentTimeMillis()
                    )
                    val newId = listsDao.upsertInventoryItem(entity)
                    if (newStatus == InventoryItemEntity.STATUS_OUT) {
                        setInventoryStatus(newId, newStatus)
                    }
                }
            }
        }
    }

    suspend fun deleteInventoryItem(id: Long) =
        listsDao.deleteInventoryItemById(id)

    // --- Cooked Food / Leftovers ---

    fun observeCookedFoodItems(): Flow<List<org.eu.angsumanray.recipecatcher.data.db.entity.CookedFoodItemEntity>> =
        database.cookedFoodDao().getCookedFoodItemsFlow()

    suspend fun getAllCookedFoodItems(): List<org.eu.angsumanray.recipecatcher.data.db.entity.CookedFoodItemEntity> =
        database.cookedFoodDao().getAllCookedFoodItems()

    suspend fun addCookedFoodItem(
        name: String,
        portions: Int = 1,
        dateCooked: String = LocalDate.now().toString(),
        notes: String? = null,
        recipeId: Long? = null
    ): Long {
        val entity = org.eu.angsumanray.recipecatcher.data.db.entity.CookedFoodItemEntity(
            name = name.trim(),
            portions = portions.coerceAtLeast(1),
            dateCooked = dateCooked,
            notes = notes?.trim()?.ifEmpty { null },
            recipeId = recipeId,
            updatedAt = System.currentTimeMillis()
        )
        return database.cookedFoodDao().insert(entity)
    }

    suspend fun updateCookedFoodPortions(id: Long, portions: Int) {
        if (portions <= 0) {
            database.cookedFoodDao().deleteById(id)
        } else {
            database.cookedFoodDao().updatePortions(id, portions)
        }
    }

    suspend fun deleteCookedFoodItem(id: Long) =
        database.cookedFoodDao().deleteById(id)

    suspend fun upsertScannedInventoryItems(
        items: List<org.eu.angsumanray.recipecatcher.core.llm.ScannedGroceryItem>,
        defaultStatus: String = InventoryItemEntity.STATUS_IN_STOCK
    ): Int {
        var count = 0
        database.withTransaction {
            for (item in items) {
                if (item.name.isBlank()) continue
                addInventoryItem(
                    name = item.name.trim(),
                    category = item.category.trim().ifBlank { "pantry" },
                    status = defaultStatus
                )
                count++
            }
        }
        return count
    }
}
