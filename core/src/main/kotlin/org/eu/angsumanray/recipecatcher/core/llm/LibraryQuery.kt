package org.eu.angsumanray.recipecatcher.core.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One saved recipe as chat's library tools see it. */
data class LibraryRecipe(
    val id: Long,
    val title: String,
    val ingredients: List<String> = emptyList(),
    val tags: List<TagRef> = emptyList(),
    val dietType: String? = null,
    val complexity: String? = null,
    val spiceLevel: String? = null,
    val totalMinutes: Int? = null
)

/** Criteria for `find_recipes`. Every criterion given must hold; list criteria are case-insensitive. */
@Serializable
data class RecipeQuery(
    /** At least one ingredient contains one of these words. */
    @SerialName("ingredients_any") val ingredientsAny: List<String> = emptyList(),
    /** No ingredient contains any of these words. */
    @SerialName("ingredients_none") val ingredientsNone: List<String> = emptyList(),
    /** Title, ingredients or tags contain this text. */
    val text: String? = null,
    @SerialName("has_tags") val hasTags: List<TagRef> = emptyList(),
    @SerialName("lacks_tags") val lacksTags: List<TagRef> = emptyList(),
    /** Diet type is one of these; "unknown" matches recipes without one. */
    @SerialName("diet_types") val dietTypes: List<String> = emptyList(),
    val complexity: String? = null,
    @SerialName("spice_level") val spiceLevel: String? = null,
    @SerialName("max_total_minutes") val maxTotalMinutes: Int? = null,
    val offset: Int = 0
)

/** Runs [RecipeQuery]s over a library snapshot and formats the answers for Claude. */
object LibraryQueryEngine {

    const val PAGE_SIZE = 300

    data class Match(val recipe: LibraryRecipe, val matchedIngredients: List<String>)

    fun find(query: RecipeQuery, recipes: List<LibraryRecipe>): List<Match> {
        val any = query.ingredientsAny.normalised()
        val none = query.ingredientsNone.normalised()
        val text = query.text?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val diets = query.dietTypes.normalised().toSet()

        return recipes.mapNotNull { recipe ->
            val ingredients = recipe.ingredients.map { it.lowercase() }
            val matched = if (any.isEmpty()) {
                emptyList()
            } else {
                recipe.ingredients.filterIndexed { i, _ -> any.any { ingredients[i].contains(it) } }
                    .ifEmpty { return@mapNotNull null }
            }
            if (none.isNotEmpty() && ingredients.any { ing -> none.any { ing.contains(it) } }) return@mapNotNull null
            if (text != null) {
                val haystack = (listOf(recipe.title) + recipe.ingredients + recipe.tags.map { it.name }).joinToString("\n").lowercase()
                if (!haystack.contains(text)) return@mapNotNull null
            }
            if (query.hasTags.any { !recipe.hasTag(it) }) return@mapNotNull null
            if (query.lacksTags.any { recipe.hasTag(it) }) return@mapNotNull null
            if (diets.isNotEmpty() && (recipe.dietType?.lowercase() ?: "unknown") !in diets) return@mapNotNull null
            if (query.complexity != null && !query.complexity.equals(recipe.complexity, ignoreCase = true)) return@mapNotNull null
            if (query.spiceLevel != null && !query.spiceLevel.equals(recipe.spiceLevel, ignoreCase = true)) return@mapNotNull null
            if (query.maxTotalMinutes != null && (recipe.totalMinutes == null || recipe.totalMinutes > query.maxTotalMinutes)) return@mapNotNull null
            Match(recipe, matched)
        }
    }

    /** One page of [matches] as text: a count line, then "id | title | diet | matched ingredients" lines. */
    fun describe(matches: List<Match>, offset: Int): String {
        val start = offset.coerceIn(0, matches.size)
        val page = matches.drop(start).take(PAGE_SIZE)
        return buildString {
            append("${matches.size} recipes match")
            if (matches.isNotEmpty()) append("; showing ${start + 1}-${start + page.size}")
            if (start + page.size < matches.size) append("; call again with offset ${start + page.size} for more")
            append(".\n")
            page.forEach { m ->
                append(m.recipe.id).append(" | ").append(m.recipe.title).append(" | ").append(m.recipe.dietType ?: "unknown")
                if (m.matchedIngredients.isNotEmpty()) append(" | matched: ").append(m.matchedIngredients.joinToString(", "))
                append('\n')
            }
        }.trimEnd()
    }

    private fun LibraryRecipe.hasTag(ref: TagRef): Boolean {
        val kind = LibraryEditProposal.normaliseKind(ref.kind)
        return tags.any { (kind == null || it.kind == kind) && it.name.equals(ref.name.trim(), ignoreCase = true) }
    }

    private fun List<String>.normalised() = map { it.trim().lowercase() }.filter { it.isNotEmpty() }
}
