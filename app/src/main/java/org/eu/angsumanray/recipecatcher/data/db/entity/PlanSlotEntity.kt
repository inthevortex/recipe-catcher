package org.eu.angsumanray.recipecatcher.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime

@Entity(
    tableName = "plan_slot",
    indices = [
        Index(value = ["date"])
    ]
)
data class PlanSlotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: LocalDate,
    val name: String,
    val time: LocalTime? = null,
    val position: Int = 0
)
