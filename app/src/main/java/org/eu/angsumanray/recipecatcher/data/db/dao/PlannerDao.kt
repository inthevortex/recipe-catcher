package org.eu.angsumanray.recipecatcher.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanEntryEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanSlotEntity
import java.time.LocalDate

@Dao
interface PlannerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSlot(slot: PlanSlotEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: PlanEntryEntity): Long

    @Update
    suspend fun updateEntry(entry: PlanEntryEntity)

    @Delete
    suspend fun deleteSlot(slot: PlanSlotEntity)

    @Delete
    suspend fun deleteEntry(entry: PlanEntryEntity)

    @Query("SELECT * FROM plan_slot WHERE date = :date ORDER BY position ASC, time ASC")
    suspend fun getSlotsForDate(date: LocalDate): List<PlanSlotEntity>

    @Query("SELECT * FROM plan_slot WHERE date = :date ORDER BY position ASC, time ASC")
    fun observeSlotsForDate(date: LocalDate): Flow<List<PlanSlotEntity>>

    @Query("SELECT * FROM plan_entry WHERE slot_id = :slotId ORDER BY position ASC")
    suspend fun getEntriesForSlot(slotId: Long): List<PlanEntryEntity>

    @Query("SELECT * FROM plan_entry WHERE slot_id = :slotId ORDER BY position ASC")
    fun observeEntriesForSlot(slotId: Long): Flow<List<PlanEntryEntity>>

    @Query("SELECT * FROM plan_entry WHERE recipe_id = :recipeId")
    suspend fun getEntriesForRecipe(recipeId: Long): List<PlanEntryEntity>
}
