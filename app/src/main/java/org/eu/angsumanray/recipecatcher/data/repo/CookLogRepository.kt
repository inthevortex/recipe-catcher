package org.eu.angsumanray.recipecatcher.data.repo

import kotlinx.coroutines.flow.Flow
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.CookLogEntity
import org.eu.angsumanray.recipecatcher.data.image.ImageStorageManager
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CookLogRepository @Inject constructor(
    private val database: RecipeDatabase,
    private val imageStorageManager: ImageStorageManager
) {
    private val cookLogDao = database.cookLogDao()

    fun observeCookLogs(recipeId: Long): Flow<List<CookLogEntity>> =
        cookLogDao.observeByRecipeId(recipeId)

    suspend fun getCookLogs(recipeId: Long): List<CookLogEntity> =
        cookLogDao.getByRecipeId(recipeId)

    suspend fun getTimesCooked(recipeId: Long): Int =
        cookLogDao.getTimesCooked(recipeId)

    suspend fun getLastCookedDate(recipeId: Long): String? =
        cookLogDao.getLastCookedDate(recipeId)

    suspend fun addCookLog(
        recipeId: Long,
        cookedOn: LocalDate = LocalDate.now(),
        note: String? = null,
        photoPath: String? = null
    ): Long {
        val entry = CookLogEntity(
            recipeId = recipeId,
            cookedOn = cookedOn,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            photoPath = photoPath
        )
        return cookLogDao.insert(entry)
    }

    suspend fun addCookLog(
        recipeId: Long,
        cookedOnIso: String,
        note: String? = null,
        photoPath: String? = null
    ): Long {
        val parsed = try {
            LocalDate.parse(cookedOnIso)
        } catch (e: Exception) {
            LocalDate.now()
        }
        return addCookLog(recipeId, parsed, note, photoPath)
    }

    suspend fun deleteCookLog(entry: CookLogEntity) {
        entry.photoPath?.let { imageStorageManager.deleteImage(it) }
        cookLogDao.delete(entry)
    }

    suspend fun deleteCookLogById(id: Long) {
        val entry = cookLogDao.getById(id) ?: return
        deleteCookLog(entry)
    }
}
