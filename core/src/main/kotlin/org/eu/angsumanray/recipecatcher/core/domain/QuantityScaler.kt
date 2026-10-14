package org.eu.angsumanray.recipecatcher.core.domain

import org.eu.angsumanray.recipecatcher.core.model.Ingredient

object QuantityScaler {

    /**
     * Calculates the scaling factor from base servings to target servings.
     * Returns 1.0 if either value is invalid (<= 0).
     */
    fun calculateFactor(baseServings: Int?, targetServings: Int?): Double {
        if (baseServings == null || baseServings <= 0 || targetServings == null || targetServings <= 0) {
            return 1.0
        }
        return targetServings.toDouble() / baseServings.toDouble()
    }

    /**
     * Scales a single quantity value by the given factor.
     * If quantity is null, returns null.
     */
    fun scale(quantity: Double?, factor: Double): Double? {
        if (quantity == null) return null
        val safeFactor = if (factor > 0.0) factor else 1.0
        return quantity * safeFactor
    }

    /**
     * Scales a quantity and optional range max by the given factor.
     */
    fun scaleRange(quantity: Double?, quantityMax: Double?, factor: Double): Pair<Double?, Double?> {
        val safeFactor = if (factor > 0.0) factor else 1.0
        val scaledMin = quantity?.let { it * safeFactor }
        val scaledMax = quantityMax?.let { if (it > 0.0) it * safeFactor else null }
        return Pair(scaledMin, scaledMax)
    }

    /**
     * Scales an ingredient's quantity and quantityMax using baseServings and targetServings.
     * Non-numeric text / notes stay intact.
     */
    fun scale(ingredient: Ingredient, baseServings: Int?, targetServings: Int?): Ingredient {
        val factor = calculateFactor(baseServings, targetServings)
        return scale(ingredient, factor)
    }

    /**
     * Scales an ingredient's quantity and quantityMax by a direct multiplier factor.
     */
    fun scale(ingredient: Ingredient, factor: Double): Ingredient {
        if (factor <= 0.0 || factor == 1.0) {
            return ingredient
        }
        val (scaledMin, scaledMax) = scaleRange(ingredient.quantity, ingredient.quantityMax, factor)
        return ingredient.copy(
            quantity = scaledMin,
            quantityMax = scaledMax
        )
    }

    /**
     * Scales a list of ingredients.
     */
    fun scaleIngredients(
        ingredients: List<Ingredient>,
        baseServings: Int?,
        targetServings: Int?
    ): List<Ingredient> {
        val factor = calculateFactor(baseServings, targetServings)
        if (factor == 1.0) return ingredients
        return ingredients.map { scale(it, factor) }
    }
}
