package org.eu.angsumanray.recipecatcher.data.repo

import org.eu.angsumanray.recipecatcher.core.llm.RecipeIndexEntry
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Builds the one-line-per-recipe index that chat and Suggest send to Claude. */
@Singleton
class RecipeIndexBuilder @Inject constructor(
    private val database: RecipeDatabase
) {
    private val recipeDao = database.recipeDao()
    private val ingredientDao = database.ingredientDao()
    private val recipeTagDao = database.recipeTagDao()
    private val cookLogDao = database.cookLogDao()
    private val recipeFtsDao = database.recipeFtsDao()

    /** Saved recipes, newest first, so ties in the chat pre-filter favour recent saves. */
    suspend fun build(recipes: List<RecipeEntity>? = null): List<RecipeIndexEntry> =
        (recipes ?: recipeDao.getAllSaved()).map { recipe ->
            val tags = recipeTagDao.getTagsForRecipe(recipe.id)
            fun names(kind: String) = tags.filter { it.kind == kind }.map { it.name }

            RecipeIndexEntry(
                id = recipe.id,
                title = recipe.title.orEmpty(),
                tags = names(TagEntity.KIND_TAG),
                mainIngredients = mainIngredients(ingredientDao.getByRecipeId(recipe.id)),
                totalMinutes = recipe.totalMin,
                complexity = recipe.complexity,
                spiceLevel = recipe.spiceLevel,
                dietType = recipe.dietType,
                contains = names(TagEntity.KIND_DIET),
                kcalPerServing = recipe.kcalPerServing,
                makeAhead = recipe.makeAhead,
                freezerFriendly = recipe.freezerFriendly,
                equipment = names(TagEntity.KIND_EQUIPMENT),
                favourite = recipe.isFavourite,
                lastCookedDate = cookLogDao.getLastCookedDate(recipe.id)
            )
        }

    /** For each saved recipe, how many of [terms] it matches in full-text search. */
    suspend fun ftsHits(terms: List<String>): Map<Long, Int> {
        val hits = mutableMapOf<Long, Int>()
        for (term in terms) {
            val query = RecipeRepository.sanitizeFtsQuery(term)
            if (query.isBlank()) continue
            recipeFtsDao.search(query).forEach { hits[it.id] = (hits[it.id] ?: 0) + 1 }
        }
        return hits
    }

    companion object {
        private const val MAX_MAIN_INGREDIENTS = 8
        private val MINOR_CATEGORIES = setOf("spices", "pantry", "other")

        /** Base names, main produce and proteins before spices and pantry staples. */
        fun mainIngredients(ingredients: List<IngredientEntity>): List<String> = ingredients
            .sortedBy { if (it.category in MINOR_CATEGORIES) 1 else 0 }
            .map { it.baseName.ifBlank { it.name }.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(MAX_MAIN_INGREDIENTS)
    }
}
