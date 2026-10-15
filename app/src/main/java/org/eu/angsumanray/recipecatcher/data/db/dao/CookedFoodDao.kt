package org.eu.angsumanray.recipecatcher.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.eu.angsumanray.recipecatcher.data.db.entity.CookedFoodItemEntity

@Dao
interface CookedFoodDao {

    @Query("SELECT * FROM cooked_food_item ORDER BY date_cooked DESC, id DESC")
    fun getCookedFoodItemsFlow(): Flow<List<CookedFoodItemEntity>>

    @Query("SELECT * FROM cooked_food_item ORDER BY date_cooked DESC, id DESC")
    suspend fun getAllCookedFoodItems(): List<CookedFoodItemEntity>

    @Query("SELECT * FROM cooked_food_item WHERE id = :id LIMIT 1")
    suspend fun getCookedFoodItemById(id: Long): CookedFoodItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: CookedFoodItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CookedFoodItemEntity>): List<Long>

    @Update
    suspend fun update(item: CookedFoodItemEntity)

    @Query("UPDATE cooked_food_item SET portions = :portions, updated_at = :updatedAt WHERE id = :id")
    suspend fun updatePortions(id: Long, portions: Int, updatedAt: Long = System.currentTimeMillis())

    @Delete
    suspend fun delete(item: CookedFoodItemEntity)

    @Query("DELETE FROM cooked_food_item WHERE id = :id")
    suspend fun deleteById(id: Long)
}
