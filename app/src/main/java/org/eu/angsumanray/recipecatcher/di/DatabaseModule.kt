package org.eu.angsumanray.recipecatcher.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
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
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideRecipeDatabase(@ApplicationContext context: Context): RecipeDatabase {
        val dbFile = context.getDatabasePath(RecipeDatabase.DATABASE_NAME)
        if (dbFile.exists()) {
            try {
                val currentVersion = android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.path,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                ).use { it.version }

                if (currentVersion in 1 until RecipeDatabase.DATABASE_VERSION) {
                    val safetyDir = java.io.File(context.filesDir, "safety_backup").apply { if (!exists()) mkdirs() }
                    val safetyFile = java.io.File(safetyDir, "recipes_pre_migration_v$currentVersion.db")
                    dbFile.copyTo(safetyFile, overwrite = true)
                }
            } catch (e: Exception) {
                android.util.Log.w("DatabaseModule", "Pre-migration safety check failed: ${e.message}")
            }
        }

        return Room.databaseBuilder(
            context,
            RecipeDatabase::class.java,
            RecipeDatabase.DATABASE_NAME
        )
            .addCallback(RecipeDatabase.starterSeedCallback)
            .fallbackToDestructiveMigration(false)
            .build()
    }

    @Provides
    fun provideRecipeDao(database: RecipeDatabase): RecipeDao = database.recipeDao()

    @Provides
    fun provideIngredientDao(database: RecipeDatabase): IngredientDao = database.ingredientDao()

    @Provides
    fun provideStepDao(database: RecipeDatabase): StepDao = database.stepDao()

    @Provides
    fun provideTagDao(database: RecipeDatabase): TagDao = database.tagDao()

    @Provides
    fun provideRecipeTagDao(database: RecipeDatabase): RecipeTagDao = database.recipeTagDao()

    @Provides
    fun provideRecipeFtsDao(database: RecipeDatabase): RecipeFtsDao = database.recipeFtsDao()

    @Provides
    fun provideCookLogDao(database: RecipeDatabase): CookLogDao = database.cookLogDao()

    @Provides
    fun provideImportItemDao(database: RecipeDatabase): ImportItemDao = database.importItemDao()

    @Provides
    fun provideLlmUsageDao(database: RecipeDatabase): LlmUsageDao = database.llmUsageDao()

    @Provides
    fun providePlannerDao(database: RecipeDatabase): PlannerDao = database.plannerDao()

    @Provides
    fun provideListsDao(database: RecipeDatabase): ListsDao = database.listsDao()

    @Provides
    fun provideChatDao(database: RecipeDatabase): ChatDao = database.chatDao()
}
