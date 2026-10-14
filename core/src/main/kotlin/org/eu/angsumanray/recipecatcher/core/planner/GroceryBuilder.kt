package org.eu.angsumanray.recipecatcher.core.planner

import org.eu.angsumanray.recipecatcher.core.domain.FractionFormatter
import org.eu.angsumanray.recipecatcher.core.domain.QuantityScaler
import org.eu.angsumanray.recipecatcher.core.model.GroceryItem
import org.eu.angsumanray.recipecatcher.core.model.GrocerySource
import org.eu.angsumanray.recipecatcher.core.model.Ingredient
import org.eu.angsumanray.recipecatcher.core.model.InventoryItem
import org.eu.angsumanray.recipecatcher.core.model.InventoryStatus
import org.eu.angsumanray.recipecatcher.core.model.PlanEntry
import org.eu.angsumanray.recipecatcher.core.model.PlanEntryKind
import org.eu.angsumanray.recipecatcher.core.model.Recipe
import java.util.Locale

object GroceryCategories {
    const val VEGETABLES = "vegetables"
    const val FRUIT = "fruit"
    const val DAIRY = "dairy"
    const val MEAT_AND_FISH = "meat & fish"
    const val GRAINS_AND_FLOURS = "grains & flours"
    const val SPICES = "spices"
    const val PANTRY = "pantry"
    const val OTHER = "other"

    val ALL = listOf(
        VEGETABLES,
        FRUIT,
        DAIRY,
        MEAT_AND_FISH,
        GRAINS_AND_FLOURS,
        SPICES,
        PANTRY,
        OTHER
    )

    fun normalize(category: String?): String {
        if (category.isNullOrBlank()) return OTHER
        val clean = category.trim().lowercase(Locale.ROOT)
        return when {
            clean.contains("veg") -> VEGETABLES
            clean.contains("fruit") -> FRUIT
            clean.contains("dairy") || clean.contains("milk") || clean.contains("cheese") -> DAIRY
            clean.contains("meat") || clean.contains("fish") || clean.contains("poultry") || clean.contains("seafood") -> MEAT_AND_FISH
            clean.contains("grain") || clean.contains("flour") || clean.contains("rice") || clean.contains("pasta") || clean.contains("bread") -> GRAINS_AND_FLOURS
            clean.contains("spice") || clean.contains("seasoning") || clean.contains("herb") -> SPICES
            clean.contains("pantry") || clean.contains("oil") || clean.contains("sauce") || clean.contains("can") -> PANTRY
            ALL.any { it.equals(clean, ignoreCase = true) } -> clean
            else -> OTHER
        }
    }
}

/**
 * Aggregates needed ingredients from planned meals, normalizes quantities and units,
 * checks inventory, and merges with manual items and ticks.
 * Pure Kotlin JVM with zero Android dependencies.
 */
object GroceryBuilder {

    private sealed class UnitFamily {
        data class Weight(val totalGrams: Double) : UnitFamily()
        data class Volume(val totalMl: Double) : UnitFamily()
        data class ExactUnit(val unit: String, val totalQty: Double) : UnitFamily()
        object NoQuantity : UnitFamily()
    }

    private data class ParsedItem(
        val displayName: String,
        val baseName: String,
        val category: String,
        val unitFamily: UnitFamily
    )

    /**
     * Builds a list of [GroceryItem] for a given set of planned [entries] and known [recipes].
     *
     * @param entries Cook and Prep entries in the target date range.
     * @param recipes Map of recipe ID to Recipe domain model.
     * @param inventory Current inventory items.
     * @param existingGroceryItems Existing grocery items for this week (to preserve ticks and manual items).
     * @param weekStart Optional ISO-8601 date string representing week start ("YYYY-MM-DD").
     */
    fun buildFromPlan(
        entries: List<PlanEntry>,
        recipes: Map<Long, Recipe>,
        inventory: List<InventoryItem> = emptyList(),
        existingGroceryItems: List<GroceryItem> = emptyList(),
        weekStart: String? = null
    ): List<GroceryItem> {
        // 1. Filter entries to cook and prep that have recipes
        val validEntries = entries.filter { entry ->
            (entry.kind == PlanEntryKind.COOK.value || entry.kind == PlanEntryKind.PREP.value) &&
                entry.recipeId != null && recipes.containsKey(entry.recipeId)
        }

        // 2. Aggregate ingredients across all valid entries
        // Group by base_name (lowercased)
        val aggregatedByBaseName = mutableMapOf<String, MutableList<Pair<Double?, String?>>>()
        val displayNameMap = mutableMapOf<String, String>()
        val categoryMap = mutableMapOf<String, String>()

        for (entry in validEntries) {
            val recipe = recipes[entry.recipeId] ?: continue
            val baseServings = (recipe.servings ?: 1).coerceAtLeast(1)
            val factor = entry.portions.toDouble() / baseServings.toDouble()

            for (ingredient in recipe.ingredients) {
                val baseName = ingredient.baseName.ifBlank { ingredient.name }
                    .trim()
                    .lowercase(Locale.ROOT)
                if (baseName.isBlank()) continue

                if (!displayNameMap.containsKey(baseName)) {
                    val rawName = ingredient.baseName.ifBlank { ingredient.name }.trim()
                    displayNameMap[baseName] = rawName.replaceFirstChar { it.uppercase() }
                }

                if (!categoryMap.containsKey(baseName)) {
                    categoryMap[baseName] = GroceryCategories.normalize(ingredient.category)
                }

                val scaledQty = QuantityScaler.scale(ingredient.quantity, factor)
                val unit = ingredient.unit?.trim()?.lowercase(Locale.ROOT)

                aggregatedByBaseName.getOrPut(baseName) { mutableListOf() }.add(Pair(scaledQty, unit))
            }
        }

        // 3. Map inventory by base_name
        val inventoryMap = inventory.associateBy { it.baseName.trim().lowercase(Locale.ROOT) }

        // 4. Map existing items to preserve checked status and manual items
        val existingMap = existingGroceryItems.associateBy {
            (it.baseName ?: it.name).trim().lowercase(Locale.ROOT)
        }
        val manualItems = existingGroceryItems.filter { it.source == GrocerySource.MANUAL.value }

        // 5. Build grocery items from plan
        val resultList = mutableListOf<GroceryItem>()

        for ((baseName, qtyList) in aggregatedByBaseName) {
            val invItem = inventoryMap[baseName]
            val isLow = invItem?.status == InventoryStatus.LOW.value

            // If item is in stock, skip it
            if (invItem?.status == InventoryStatus.IN_STOCK.value) {
                continue
            }

            val displayName = displayNameMap[baseName] ?: baseName.replaceFirstChar { it.uppercase() }
            val category = categoryMap[baseName] ?: GroceryCategories.OTHER
            val combinedQuantity = combineQuantities(qtyList)
            val quantityText = if (isLow) {
                if (combinedQuantity.isNotBlank()) "$combinedQuantity (running low)" else "running low"
            } else {
                combinedQuantity.ifBlank { null }
            }

            val wasChecked = existingMap[baseName]?.checked ?: false

            resultList.add(
                GroceryItem(
                    id = existingMap[baseName]?.id ?: 0L,
                    name = displayName,
                    baseName = baseName,
                    category = category,
                    quantityText = quantityText,
                    source = GrocerySource.PLAN.value,
                    weekStart = weekStart,
                    checked = wasChecked,
                    position = resultList.size
                )
            )
        }

        // 6. Add inventory items marked "out" that are not yet on the list
        for (invItem in inventory) {
            if (invItem.status == InventoryStatus.OUT.value) {
                val baseKey = invItem.baseName.trim().lowercase(Locale.ROOT)
                val alreadyPresent = resultList.any { it.baseName?.equals(baseKey, ignoreCase = true) == true }
                if (!alreadyPresent) {
                    val wasChecked = existingMap[baseKey]?.checked ?: false
                    resultList.add(
                        GroceryItem(
                            id = existingMap[baseKey]?.id ?: 0L,
                            name = invItem.name.ifBlank { invItem.baseName },
                            baseName = baseKey,
                            category = GroceryCategories.normalize(invItem.category),
                            quantityText = null,
                            source = GrocerySource.INVENTORY.value,
                            weekStart = weekStart,
                            checked = wasChecked,
                            position = resultList.size
                        )
                    )
                }
            }
        }

        // 7. Merge preserved manual items that aren't already included
        for (manual in manualItems) {
            val baseKey = (manual.baseName ?: manual.name).trim().lowercase(Locale.ROOT)
            val alreadyPresent = resultList.any {
                (it.baseName ?: it.name).trim().lowercase(Locale.ROOT) == baseKey
            }
            if (!alreadyPresent) {
                resultList.add(manual)
            }
        }

        // 8. Sort by category (standard order), then name
        val categoryOrder = GroceryCategories.ALL.withIndex().associate { it.value to it.index }
        return resultList.sortedWith(
            compareBy<GroceryItem> { categoryOrder[it.category] ?: 999 }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        ).mapIndexed { index, item -> item.copy(position = index) }
    }

    /**
     * Combines quantities of the same ingredient across different units/recipes.
     */
    internal fun combineQuantities(items: List<Pair<Double?, String?>>): String {
        var totalGrams = 0.0
        var hasGrams = false

        var totalMl = 0.0
        var hasMl = false

        val exactUnitTotals = mutableMapOf<String, Double>()
        var noQtyCount = 0

        for ((qty, rawUnit) in items) {
            if (qty == null || qty <= 0.0) {
                noQtyCount++
                continue
            }

            val unit = rawUnit?.trim()?.lowercase(Locale.ROOT) ?: ""

            when {
                // Grams and Kilograms
                unit == "g" || unit == "gram" || unit == "grams" -> {
                    totalGrams += qty
                    hasGrams = true
                }
                unit == "kg" || unit == "kilogram" || unit == "kilograms" -> {
                    totalGrams += qty * 1000.0
                    hasGrams = true
                }
                // Milliliters and Liters
                unit == "ml" || unit == "milliliter" || unit == "milliliters" -> {
                    totalMl += qty
                    hasMl = true
                }
                unit == "l" || unit == "liter" || unit == "liters" || unit == "litre" || unit == "litres" -> {
                    totalMl += qty * 1000.0
                    hasMl = true
                }
                // Exact matching units
                unit.isNotBlank() -> {
                    val normalizedUnit = when (unit) {
                        "teaspoon", "teaspoons" -> "tsp"
                        "tablespoon", "tablespoons" -> "tbsp"
                        "pieces", "piece", "pcs" -> "pc"
                        "cloves" -> "clove"
                        "pinches" -> "pinch"
                        else -> unit
                    }
                    exactUnitTotals[normalizedUnit] = (exactUnitTotals[normalizedUnit] ?: 0.0) + qty
                }
                else -> {
                    // Number without unit, e.g. "3 eggs"
                    exactUnitTotals[""] = (exactUnitTotals[""] ?: 0.0) + qty
                }
            }
        }

        val parts = mutableListOf<String>()

        if (hasGrams) {
            val formatted = FractionFormatter.formatSingle(totalGrams, "g")
            parts.add(formatted.displayText)
        }

        if (hasMl) {
            val formatted = FractionFormatter.formatSingle(totalMl, "ml")
            parts.add(formatted.displayText)
        }

        for ((unit, total) in exactUnitTotals) {
            val formatted = FractionFormatter.formatSingle(total, unit.ifBlank { null })
            parts.add(formatted.displayText)
        }

        return parts.joinToString(" + ")
    }
}
