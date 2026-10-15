package org.eu.angsumanray.recipecatcher.core.planner

import org.eu.angsumanray.recipecatcher.core.model.Ingredient
import org.eu.angsumanray.recipecatcher.core.model.InventoryItem
import org.eu.angsumanray.recipecatcher.core.model.InventoryStatus
import java.util.Locale

/** Action chosen by the user for an ingredient during pantry reconciliation. */
enum class PantryAction {
    DONT_CHANGE,
    IN_STOCK,
    LOW,
    OUT
}

/** Represents an ingredient row in the interactive pantry reconciliation sheet. */
data class PantryReconciliationItem(
    val ingredientName: String,
    val baseName: String,
    val category: String,
    val matchedInventoryItem: InventoryItem? = null,
    val initialStatus: InventoryStatus? = null,
    val action: PantryAction = PantryAction.DONT_CHANGE
)

object PantryReconciler {

    /**
     * Matches recipe ingredients against the current inventory by base name.
     * Deduplicates multiple occurrences of the same base name in a recipe.
     */
    fun matchIngredients(
        ingredients: List<Ingredient>,
        inventory: List<InventoryItem>
    ): List<PantryReconciliationItem> {
        val invByBase = inventory.associateBy { it.baseName.trim().lowercase(Locale.ROOT) }
        val seen = mutableSetOf<String>()
        val result = mutableListOf<PantryReconciliationItem>()

        for (ing in ingredients) {
            val base = ing.baseName.trim().lowercase(Locale.ROOT)
            if (base.isEmpty() || !seen.add(base)) continue

            val matched = invByBase[base]
            val initialStatus = matched?.let { InventoryStatus.from(it.status) }
            result.add(
                PantryReconciliationItem(
                    ingredientName = ing.name.trim(),
                    baseName = base,
                    category = ing.category.trim().lowercase(Locale.ROOT),
                    matchedInventoryItem = matched,
                    initialStatus = initialStatus,
                    action = PantryAction.DONT_CHANGE
                )
            )
        }
        return result
    }
}
