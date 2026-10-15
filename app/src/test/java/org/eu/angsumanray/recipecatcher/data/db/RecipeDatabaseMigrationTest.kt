package org.eu.angsumanray.recipecatcher.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeDatabaseMigrationTest {

    private lateinit var context: Context
    private val dbName = "migration-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /** Creates the database exactly as the exported version-1 schema describes it. */
    private fun createVersion1Database() {
        val schema = Json.parseToJsonElement(
            File("schemas/org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase/1.json").readText()
        ).jsonObject["database"]!!.jsonObject

        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }, null)
        db.use {
            schema["entities"]!!.jsonArray.forEach { entity ->
                val obj = entity.jsonObject
                val table = obj["tableName"]!!.jsonPrimitive.content
                it.execSQL(obj["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                obj["indices"]?.jsonArray?.forEach { index ->
                    it.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema["setupQueries"]!!.jsonArray.forEach { query -> it.execSQL(query.jsonPrimitive.content) }
            it.execSQL("INSERT INTO recipe (id, source_type, source_key, make_ahead, freezer_friendly, is_favourite, reviewed, status, created_at, updated_at) " +
                    "VALUES (1, 'web', 'web:x', 0, 0, 0, 1, 'saved', 0, 0)")
            it.version = 1
        }
    }

    @Test
    fun `migration 1 to 2 adds cooked food table that matches the schema`() = runBlocking {
        createVersion1Database()

        // Room validates the migrated schema against version 2 on open and throws on any mismatch.
        val database = Room.databaseBuilder(context, RecipeDatabase::class.java, dbName)
            .addMigrations(RecipeDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.cookedFoodDao()
            val id = dao.insert(
                org.eu.angsumanray.recipecatcher.data.db.entity.CookedFoodItemEntity(
                    name = "Dal",
                    recipeId = 1L,
                    portions = 3,
                    dateCooked = "2026-10-15"
                )
            )
            assertEquals(3, dao.getCookedFoodItemById(id)?.portions)

            database.openHelper.writableDatabase.execSQL("DELETE FROM recipe WHERE id = 1")
            assertNull(dao.getCookedFoodItemById(id)?.recipeId)
        } finally {
            database.close()
        }
    }
}
