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
    suspend fun insertSlots(slots: List<PlanSlotEntity>): List<Long>

    @Update
    suspend fun updateSlot(slot: PlanSlotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: PlanEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<PlanEntryEntity>): List<Long>

    @Update
    suspend fun updateEntry(entry: PlanEntryEntity)

    @Delete
    suspend fun deleteSlot(slot: PlanSlotEntity)

    @Delete
    suspend fun deleteEntry(entry: PlanEntryEntity)

    @Query("SELECT * FROM plan_slot WHERE id = :id LIMIT 1")
    suspend fun getSlotById(id: Long): PlanSlotEntity?

    @Query("SELECT * FROM plan_entry WHERE id = :id LIMIT 1")
    suspend fun getEntryById(id: Long): PlanEntryEntity?

    @Query("SELECT * FROM plan_slot WHERE date = :date ORDER BY position ASC, time ASC")
    suspend fun getSlotsForDate(date: LocalDate): List<PlanSlotEntity>

    @Query("SELECT * FROM plan_slot WHERE date = :date ORDER BY position ASC, time ASC")
    fun observeSlotsForDate(date: LocalDate): Flow<List<PlanSlotEntity>>

    @Query("SELECT * FROM plan_slot WHERE date >= :startDate AND date <= :endDate ORDER BY date ASC, position ASC, time ASC")
    suspend fun getSlotsBetween(startDate: LocalDate, endDate: LocalDate): List<PlanSlotEntity>

    @Query("SELECT * FROM plan_slot WHERE date >= :startDate AND date <= :endDate ORDER BY date ASC, position ASC, time ASC")
    fun observeSlotsBetween(startDate: LocalDate, endDate: LocalDate): Flow<List<PlanSlotEntity>>

    @Query("SELECT * FROM plan_slot ORDER BY date ASC, position ASC")
    fun observeAllSlots(): Flow<List<PlanSlotEntity>>

    @Query("SELECT * FROM plan_entry WHERE slot_id = :slotId ORDER BY position ASC")
    suspend fun getEntriesForSlot(slotId: Long): List<PlanEntryEntity>

    @Query("SELECT * FROM plan_entry WHERE slot_id = :slotId ORDER BY position ASC")
    fun observeEntriesForSlot(slotId: Long): Flow<List<PlanEntryEntity>>

    @Query("SELECT * FROM plan_entry WHERE slot_id IN (:slotIds) ORDER BY position ASC")
    suspend fun getEntriesForSlots(slotIds: List<Long>): List<PlanEntryEntity>

    @Query("SELECT * FROM plan_entry WHERE slot_id IN (:slotIds) ORDER BY position ASC")
    fun observeEntriesForSlots(slotIds: List<Long>): Flow<List<PlanEntryEntity>>

    @Query("SELECT pe.* FROM plan_entry pe INNER JOIN plan_slot ps ON pe.slot_id = ps.id WHERE ps.date = :date ORDER BY ps.position ASC, pe.position ASC")
    suspend fun getEntriesForDate(date: LocalDate): List<PlanEntryEntity>

    @Query("SELECT pe.* FROM plan_entry pe INNER JOIN plan_slot ps ON pe.slot_id = ps.id WHERE ps.date = :date ORDER BY ps.position ASC, pe.position ASC")
    fun observeEntriesForDate(date: LocalDate): Flow<List<PlanEntryEntity>>

    @Query("SELECT pe.* FROM plan_entry pe INNER JOIN plan_slot ps ON pe.slot_id = ps.id WHERE ps.date >= :startDate AND ps.date <= :endDate ORDER BY ps.date ASC, ps.position ASC, pe.position ASC")
    suspend fun getEntriesBetween(startDate: LocalDate, endDate: LocalDate): List<PlanEntryEntity>

    @Query("SELECT pe.* FROM plan_entry pe INNER JOIN plan_slot ps ON pe.slot_id = ps.id WHERE ps.date >= :startDate AND ps.date <= :endDate ORDER BY ps.date ASC, ps.position ASC, pe.position ASC")
    fun observeEntriesBetween(startDate: LocalDate, endDate: LocalDate): Flow<List<PlanEntryEntity>>

    @Query("SELECT * FROM plan_entry ORDER BY position ASC")
    suspend fun getAllEntries(): List<PlanEntryEntity>

    @Query("SELECT * FROM plan_entry ORDER BY position ASC")
    fun observeAllEntries(): Flow<List<PlanEntryEntity>>

    @Query("SELECT * FROM plan_entry WHERE recipe_id = :recipeId")
    suspend fun getEntriesForRecipe(recipeId: Long): List<PlanEntryEntity>

    @Query("UPDATE plan_entry SET done_at = :doneAt WHERE id = :id")
    suspend fun setEntryDone(id: Long, doneAt: Long?)

    @Query("UPDATE plan_entry SET slot_id = :newSlotId, position = :newPosition WHERE id = :entryId")
    suspend fun moveEntry(entryId: Long, newSlotId: Long, newPosition: Int)

    @Query("DELETE FROM plan_entry WHERE slot_id = :slotId")
    suspend fun deleteEntriesForSlot(slotId: Long)
}
