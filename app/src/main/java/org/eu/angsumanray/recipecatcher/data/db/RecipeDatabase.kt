package org.eu.angsumanray.recipecatcher.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import org.eu.angsumanray.recipecatcher.data.db.dao.ChatDao
import org.eu.angsumanray.recipecatcher.data.db.dao.CookLogDao
import org.eu.angsumanray.recipecatcher.data.db.dao.ImportItemDao
import org.eu.angsumanray.recipecatcher.data.db.dao.IngredientDao
import org.eu.angsumanray.recipecatcher.data.db.dao.ListsDao
import org.eu.angsumanray.recipecatcher.data.db.dao.LlmUsageDao
import org.eu.angsumanray.recipecatcher.data.db.dao.PlannerDao
import org.eu.angsumanray.recipecatcher.data.db.dao.RecipeDao
import org.eu.angsumanray.recipecatcher.data.db.dao.RecipeFtsDao
import org.eu.angsumanray.recipecatcher.data.db.dao.RecipeTagDao
import org.eu.angsumanray.recipecatcher.data.db.dao.StepDao
import org.eu.angsumanray.recipecatcher.data.db.dao.TagDao
import org.eu.angsumanray.recipecatcher.data.db.entity.ChatMessageEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.ChatSessionEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.CookLogEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.GroceryItemEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.ImportItemEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.InventoryItemEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.LlmUsageEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanEntryEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanSlotEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeFtsEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeTagEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity

@Database(
    entities = [
        RecipeEntity::class,
        IngredientEntity::class,
        StepEntity::class,
        TagEntity::class,
        RecipeTagEntity::class,
        CookLogEntity::class,
        ImportItemEntity::class,
        LlmUsageEntity::class,
        PlanSlotEntity::class,
        PlanEntryEntity::class,
        InventoryItemEntity::class,
        GroceryItemEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        RecipeFtsEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class RecipeDatabase : RoomDatabase() {

    abstract fun recipeDao(): RecipeDao
    abstract fun ingredientDao(): IngredientDao
    abstract fun stepDao(): StepDao
    abstract fun tagDao(): TagDao
    abstract fun recipeTagDao(): RecipeTagDao
    abstract fun recipeFtsDao(): RecipeFtsDao
    abstract fun cookLogDao(): CookLogDao
    abstract fun importItemDao(): ImportItemDao
    abstract fun llmUsageDao(): LlmUsageDao
    abstract fun plannerDao(): PlannerDao
    abstract fun listsDao(): ListsDao
    abstract fun chatDao(): ChatDao

    companion object {
        const val DATABASE_NAME = "recipes.db"
        const val DATABASE_VERSION = 1

        /**
         * Seeds the starter lists inside the database-creation transaction, so nothing can read
         * the tag table before it is filled.
         */
        val starterSeedCallback: Callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                StarterLists.createInitialEntities().forEach { tag ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO tag (kind, name, position) VALUES (?, ?, ?)",
                        arrayOf<Any>(tag.kind, tag.name, tag.position)
                    )
                }
            }
        }
    }
}
