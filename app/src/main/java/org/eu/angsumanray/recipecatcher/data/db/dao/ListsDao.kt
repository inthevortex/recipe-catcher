package org.eu.angsumanray.recipecatcher.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.eu.angsumanray.recipecatcher.data.db.entity.GroceryItemEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.InventoryItemEntity
import java.time.LocalDate

@Dao
interface ListsDao {

    // Inventory
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInventoryItem(item: InventoryItemEntity): Long

    @Query("SELECT * FROM inventory_item ORDER BY category ASC, name ASC")
    suspend fun getAllInventoryItems(): List<InventoryItemEntity>

    @Query("SELECT * FROM inventory_item ORDER BY category ASC, name ASC")
    fun observeAllInventoryItems(): Flow<List<InventoryItemEntity>>

    @Query("SELECT * FROM inventory_item WHERE base_name = :baseName LIMIT 1")
    suspend fun getInventoryItemByBaseName(baseName: String): InventoryItemEntity?

    @Query("UPDATE inventory_item SET status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateInventoryStatus(id: Long, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM inventory_item WHERE id = :id")
    suspend fun deleteInventoryItemById(id: Long)

    @Delete
    suspend fun deleteInventoryItem(item: InventoryItemEntity)

    // Grocery
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroceryItem(item: GroceryItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllGroceryItems(items: List<GroceryItemEntity>)

    @Update
    suspend fun updateGroceryItem(item: GroceryItemEntity)

    @Delete
    suspend fun deleteGroceryItem(item: GroceryItemEntity)

    @Query("DELETE FROM grocery_item WHERE id = :id")
    suspend fun deleteGroceryItemById(id: Long)

    @Query("SELECT * FROM grocery_item WHERE week_start = :weekStart OR week_start IS NULL ORDER BY checked ASC, category ASC, position ASC")
    fun observeGroceryItems(weekStart: LocalDate?): Flow<List<GroceryItemEntity>>

    @Query("SELECT * FROM grocery_item WHERE week_start = :weekStart OR week_start IS NULL ORDER BY checked ASC, category ASC, position ASC")
    suspend fun getGroceryItems(weekStart: LocalDate?): List<GroceryItemEntity>

    @Query("SELECT * FROM grocery_item WHERE id = :id LIMIT 1")
    suspend fun getGroceryItemById(id: Long): GroceryItemEntity?

    @Query("SELECT * FROM grocery_item")
    suspend fun getAllGroceryItems(): List<GroceryItemEntity>

    @Query("SELECT * FROM inventory_item WHERE id = :id LIMIT 1")
    suspend fun getInventoryItemById(id: Long): InventoryItemEntity?

    @Query("UPDATE grocery_item SET checked = :checked WHERE id = :id")
    suspend fun setGroceryItemChecked(id: Long, checked: Boolean)

    @Query("DELETE FROM grocery_item WHERE checked = 1 AND (week_start = :weekStart OR week_start IS NULL)")
    suspend fun clearCheckedGroceryItems(weekStart: LocalDate?)

    @Query("DELETE FROM grocery_item WHERE source = 'plan' AND (week_start = :weekStart OR week_start IS NULL)")
    suspend fun clearPlanGroceryItems(weekStart: LocalDate?)
}
