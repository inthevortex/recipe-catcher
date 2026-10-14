package org.eu.angsumanray.recipecatcher.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "import_item",
    indices = [
        Index(value = ["source_key"], unique = true),
        Index(value = ["status", "created_at"])
    ]
)
data class ImportItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    val collection: String? = null,
    val status: String = STATUS_QUEUED,
    @ColumnInfo(name = "recipe_id")
    val recipeId: Long? = null,
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_DONE = "done"
        const val STATUS_SKIPPED = "skipped"
        const val STATUS_FAILED = "failed"
    }
}
