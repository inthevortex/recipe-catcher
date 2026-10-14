package org.eu.angsumanray.recipecatcher.data.repo

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

enum class RecipeSortOrder(val displayName: String) {
    NEWEST_SAVED("Newest saved"),
    TITLE_A_Z("Title A–Z"),
    TOTAL_TIME("Total time"),
    CALORIES("Calories"),
    RECENTLY_COOKED("Recently cooked"),
    LONGEST_SINCE_COOKED("Longest since cooked")
}

data class RecipeFilter(
    val query: String = "",
    val tagIds: Set<Long> = emptySet(),
    val dietType: String? = null,
    val withoutDietTagIds: Set<Long> = emptySet(),
    val isFavourite: Boolean? = null,
    val neverCooked: Boolean = false,
    val complexity: String? = null,
    val equipmentTagIds: Set<Long> = emptySet(),
    val spiceLevel: String? = null,
    val makeAhead: Boolean? = null,
    val freezerFriendly: Boolean? = null,
    val toReview: Boolean? = null,
    val sortOrder: RecipeSortOrder = RecipeSortOrder.NEWEST_SAVED
) {
    val isActive: Boolean
        get() = query.isNotBlank() ||
            tagIds.isNotEmpty() ||
            !dietType.isNullOrBlank() ||
            withoutDietTagIds.isNotEmpty() ||
            isFavourite != null ||
            neverCooked ||
            !complexity.isNullOrBlank() ||
            equipmentTagIds.isNotEmpty() ||
            !spiceLevel.isNullOrBlank() ||
            makeAhead != null ||
            freezerFriendly != null ||
            toReview != null ||
            sortOrder != RecipeSortOrder.NEWEST_SAVED
}

object RecipeFilterQueryBuilder {
    fun buildQuery(filter: RecipeFilter): SupportSQLiteQuery {
        val sanitizedQuery = RecipeRepository.sanitizeFtsQuery(filter.query)
        val useFts = sanitizedQuery.isNotBlank()

        val sql = StringBuilder()
        val args = mutableListOf<Any>()

        if (useFts) {
            sql.append("SELECT DISTINCT recipe.* FROM recipe ")
            sql.append("JOIN recipe_fts ON recipe.id = recipe_fts.rowid ")
            sql.append("WHERE recipe.status = 'saved' ")
            sql.append("AND recipe_fts MATCH ? ")
            args.add(sanitizedQuery)
        } else {
            sql.append("SELECT DISTINCT recipe.* FROM recipe ")
            sql.append("WHERE recipe.status = 'saved' ")
        }

        if (!filter.dietType.isNullOrBlank()) {
            sql.append("AND LOWER(recipe.diet_type) = LOWER(?) ")
            args.add(filter.dietType.trim())
        }

        if (filter.isFavourite == true) {
            sql.append("AND recipe.is_favourite = 1 ")
        }

        if (!filter.complexity.isNullOrBlank()) {
            sql.append("AND LOWER(recipe.complexity) = LOWER(?) ")
            args.add(filter.complexity.trim())
        }

        if (!filter.spiceLevel.isNullOrBlank()) {
            sql.append("AND LOWER(recipe.spice_level) = LOWER(?) ")
            args.add(filter.spiceLevel.trim())
        }

        if (filter.makeAhead == true) {
            sql.append("AND recipe.make_ahead = 1 ")
        }

        if (filter.freezerFriendly == true) {
            sql.append("AND recipe.freezer_friendly = 1 ")
        }

        if (filter.toReview == true) {
            sql.append("AND recipe.reviewed = 0 ")
        }

        if (filter.neverCooked) {
            sql.append("AND NOT EXISTS (SELECT 1 FROM cook_log WHERE cook_log.recipe_id = recipe.id) ")
        }

        for (tagId in filter.tagIds) {
            sql.append("AND EXISTS (SELECT 1 FROM recipe_tag WHERE recipe_tag.recipe_id = recipe.id AND recipe_tag.tag_id = ?) ")
            args.add(tagId)
        }

        for (withoutTagId in filter.withoutDietTagIds) {
            sql.append("AND NOT EXISTS (SELECT 1 FROM recipe_tag WHERE recipe_tag.recipe_id = recipe.id AND recipe_tag.tag_id = ?) ")
            args.add(withoutTagId)
        }

        for (equipmentTagId in filter.equipmentTagIds) {
            sql.append("AND EXISTS (SELECT 1 FROM recipe_tag WHERE recipe_tag.recipe_id = recipe.id AND recipe_tag.tag_id = ?) ")
            args.add(equipmentTagId)
        }

        when (filter.sortOrder) {
            RecipeSortOrder.NEWEST_SAVED -> {
                sql.append("ORDER BY COALESCE(recipe.saved_at, recipe.created_at) DESC, recipe.created_at DESC")
            }
            RecipeSortOrder.TITLE_A_Z -> {
                sql.append("ORDER BY recipe.title COLLATE NOCASE ASC")
            }
            RecipeSortOrder.TOTAL_TIME -> {
                sql.append("ORDER BY (CASE WHEN recipe.total_min IS NULL OR recipe.total_min <= 0 THEN 999999 ELSE recipe.total_min END) ASC, recipe.title COLLATE NOCASE ASC")
            }
            RecipeSortOrder.CALORIES -> {
                sql.append("ORDER BY (CASE WHEN recipe.kcal_per_serving IS NULL OR recipe.kcal_per_serving <= 0 THEN 999999 ELSE recipe.kcal_per_serving END) ASC, recipe.title COLLATE NOCASE ASC")
            }
            RecipeSortOrder.RECENTLY_COOKED -> {
                sql.append("ORDER BY (CASE WHEN (SELECT MAX(cook_log.cooked_on) FROM cook_log WHERE cook_log.recipe_id = recipe.id) IS NOT NULL THEN 0 ELSE 1 END) ASC, (SELECT MAX(cook_log.cooked_on) FROM cook_log WHERE cook_log.recipe_id = recipe.id) DESC, recipe.saved_at DESC")
            }
            RecipeSortOrder.LONGEST_SINCE_COOKED -> {
                sql.append("ORDER BY (CASE WHEN (SELECT MAX(cook_log.cooked_on) FROM cook_log WHERE cook_log.recipe_id = recipe.id) IS NOT NULL THEN 0 ELSE 1 END) ASC, (SELECT MAX(cook_log.cooked_on) FROM cook_log WHERE cook_log.recipe_id = recipe.id) ASC, recipe.saved_at DESC")
            }
        }

        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }
}
