package org.eu.angsumanray.recipecatcher.core.domain

import org.eu.angsumanray.recipecatcher.core.model.Recipe

/**
 * Formats a [Recipe] into clean, readable plain text suitable for sharing via
 * Intent.ACTION_SEND (text/plain) without markdown clutter (*, **, #).
 */
object PlainTextRecipeFormatter {

    fun format(recipe: Recipe): String {
        return buildString {
            val title = recipe.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Untitled Recipe"
            appendLine(title)
            appendLine("=".repeat(title.length.coerceAtLeast(8)))
            appendLine()

            recipe.description?.trim()?.takeIf { it.isNotEmpty() }?.let { desc ->
                appendLine(desc)
                appendLine()
            }

            val meta = mutableListOf<String>()
            recipe.servings?.let { meta.add("Servings: $it") }
            recipe.prepMin?.let { meta.add("Prep: ${it}m") }
            recipe.cookMin?.let { meta.add("Cook: ${it}m") }
            val total = recipe.totalMin ?: ((recipe.prepMin ?: 0) + (recipe.cookMin ?: 0)).takeIf { it > 0 }
            if (total != null && (recipe.prepMin == null || recipe.cookMin == null || total != (recipe.prepMin ?: 0) + (recipe.cookMin ?: 0))) {
                meta.add("Total: ${total}m")
            }
            recipe.cuisine?.trim()?.takeIf { it.isNotEmpty() }?.let { meta.add("Cuisine: $it") }

            if (meta.isNotEmpty()) {
                appendLine(meta.joinToString(" | "))
                appendLine()
            }

            if (recipe.ingredients.isNotEmpty()) {
                appendLine("Ingredients:")
                val hasGroups = recipe.ingredients.any { !it.groupName.isNullOrBlank() }
                if (hasGroups) {
                    val grouped = recipe.ingredients.groupBy { it.groupName?.trim()?.ifEmpty { null } }
                    for ((group, items) in grouped) {
                        if (group != null) {
                            appendLine("  $group:")
                        }
                        for (item in items) {
                            val indent = if (group != null) "    - " else "  - "
                            appendLine("$indent${FractionFormatter.formatIngredient(item)}")
                        }
                    }
                } else {
                    for (item in recipe.ingredients) {
                        appendLine("  - ${FractionFormatter.formatIngredient(item)}")
                    }
                }
                appendLine()
            }

            if (recipe.steps.isNotEmpty()) {
                appendLine("Instructions:")
                recipe.steps.sortedBy { it.position }.forEachIndexed { index, step ->
                    appendLine("  ${index + 1}. ${step.text.trim()}")
                }
                appendLine()
            }

            recipe.personalNote?.trim()?.takeIf { it.isNotEmpty() }?.let { note ->
                appendLine("Notes:")
                appendLine("  $note")
                appendLine()
            }

            recipe.sourceUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { url ->
                appendLine("Source: $url")
            }
        }.trimEnd()
    }
}
