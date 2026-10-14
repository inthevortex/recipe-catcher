package org.eu.angsumanray.recipecatcher.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

@Entity(tableName = "recipe_fts")
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
data class RecipeFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Long,
    val title: String,
    val ingredients: String,
    val steps: String,
    val caption: String,
    val author: String,
    val notes: String
)
