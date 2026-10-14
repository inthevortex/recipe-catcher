package org.eu.angsumanray.recipecatcher.core.domain

import org.eu.angsumanray.recipecatcher.core.model.Ingredient
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object FractionFormatter {

    data class FormattedQuantity(
        val valueText: String,
        val unitText: String?
    ) {
        val displayText: String
            get() = if (unitText.isNullOrBlank()) valueText else "$valueText $unitText"
    }

    private val FRACTIONS = listOf(
        Pair(0.125, "⅛"),
        Pair(0.25, "¼"),
        Pair(0.333, "⅓"),
        Pair(0.375, "⅜"),
        Pair(0.5, "½"),
        Pair(0.625, "⅝"),
        Pair(0.667, "⅔"),
        Pair(0.75, "¾"),
        Pair(0.875, "⅞")
    )

    /**
     * Determines if a unit is fraction-based (tsp, tbsp, pieces/cloves, or no unit).
     */
    fun isFractionUnit(unit: String?): Boolean {
        if (unit == null || unit.isBlank()) return true
        val normalized = unit.trim().lowercase(Locale.ROOT)
        return when (normalized) {
            "tsp", "teaspoon", "teaspoons",
            "tbsp", "tablespoon", "tablespoons",
            "pc", "pcs", "piece", "pieces",
            "clove", "cloves", "slice", "slices",
            "pinch", "pinches" -> true
            else -> false
        }
    }

    /**
     * Determines if a unit is metric weight (g / gram).
     */
    fun isGramUnit(unit: String?): Boolean {
        if (unit == null) return false
        val normalized = unit.trim().lowercase(Locale.ROOT)
        return normalized == "g" || normalized == "gram" || normalized == "grams"
    }

    /**
     * Determines if a unit is metric volume (ml / milliliter).
     */
    fun isMlUnit(unit: String?): Boolean {
        if (unit == null) return false
        val normalized = unit.trim().lowercase(Locale.ROOT)
        return normalized == "ml" || normalized == "milliliter" || normalized == "milliliters"
    }

    /**
     * Formats a single numeric value according to its unit:
     * - tsp, tbsp, pieces: fractions (0.5 -> ½, 0.25 -> ¼, 0.333 -> ⅓, 1.5 -> 1 ½)
     * - g / ml:
     *     - >= 1000: converts to kg or l with one decimal (1250 g -> 1.3 kg, 1000 g -> 1.0 kg)
     *     - >= 100: rounded to nearest 5 (123 g -> 125 g)
     *     - < 100: rounded to whole number (48.7 g -> 49 g)
     * - others: clean decimal without trailing zeroes
     */
    fun formatSingle(value: Double, unit: String?): FormattedQuantity {
        val trimmedUnit = unit?.trim()

        if (isGramUnit(trimmedUnit)) {
            return formatMetric(value, baseUnit = "g", largeUnit = "kg")
        }

        if (isMlUnit(trimmedUnit)) {
            return formatMetric(value, baseUnit = "ml", largeUnit = "l")
        }

        if (isFractionUnit(trimmedUnit)) {
            val fractionStr = toFractionString(value)
            return FormattedQuantity(fractionStr, trimmedUnit)
        }

        // Generic units: clean decimal
        val genericStr = if (abs(value - value.roundToLong()) < 0.001) {
            value.roundToLong().toString()
        } else {
            String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }
        return FormattedQuantity(genericStr, trimmedUnit)
    }

    private fun formatMetric(value: Double, baseUnit: String, largeUnit: String): FormattedQuantity {
        if (value >= 1000.0) {
            val converted = value / 1000.0
            val text = String.format(Locale.US, "%.1f", converted)
            return FormattedQuantity(text, largeUnit)
        }

        val roundedVal: Long = if (value >= 100.0) {
            val nearestFive = (Math.round(value / 5.0) * 5)
            if (nearestFive >= 1000) {
                // If rounding pushed it to 1000, convert to large unit
                return FormattedQuantity("1.0", largeUnit)
            }
            nearestFive
        } else {
            Math.round(value)
        }

        return FormattedQuantity(roundedVal.toString(), baseUnit)
    }

    /**
     * Converts a numeric value to a fraction string.
     * Examples: 0.5 -> "½", 0.25 -> "¼", 0.333 -> "⅓", 1.5 -> "1 ½", 2.0 -> "2".
     */
    fun toFractionString(value: Double): String {
        val roundedWhole = value.roundToLong()
        if (abs(value - roundedWhole) < 0.03) {
            return roundedWhole.toString()
        }

        val wholePart = value.toLong()
        val fractionalPart = value - wholePart

        // Check if fractional part is close to 1
        if (fractionalPart >= 0.97) {
            return (wholePart + 1).toString()
        }

        var matchedFraction: String? = null
        for ((target, symbol) in FRACTIONS) {
            if (abs(fractionalPart - target) < 0.04) {
                matchedFraction = symbol
                break
            }
        }

        return if (matchedFraction != null) {
            if (wholePart == 0L) matchedFraction else "$wholePart $matchedFraction"
        } else {
            // Fallback to 1 or 2 decimals
            String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }
    }

    /**
     * Formats a quantity and optional range max.
     * e.g. 2, 3, "cloves" -> "2–3 cloves"
     *      100.0, 150.0, "g" -> "100–150 g"
     *      0.5, null, "tsp" -> "½ tsp"
     */
    fun format(quantity: Double?, quantityMax: Double? = null, unit: String? = null): String {
        if (quantity == null) return ""
        val formattedMin = formatSingle(quantity, unit)

        if (quantityMax != null && quantityMax > quantity) {
            val formattedMax = formatSingle(quantityMax, unit)
            return if (formattedMin.unitText == formattedMax.unitText) {
                if (formattedMin.unitText.isNullOrBlank()) {
                    "${formattedMin.valueText}–${formattedMax.valueText}"
                } else {
                    "${formattedMin.valueText}–${formattedMax.valueText} ${formattedMin.unitText}"
                }
            } else {
                "${formattedMin.displayText}–${formattedMax.displayText}"
            }
        }

        return formattedMin.displayText
    }

    /**
     * Formats an Ingredient into a display text: quantity, unit, name, note.
     */
    fun formatIngredient(ingredient: Ingredient): String {
        val qtyText = format(ingredient.quantity, ingredient.quantityMax, ingredient.unit)
        val nameWithNote = if (!ingredient.note.isNullOrBlank()) {
            "${ingredient.name} (${ingredient.note})"
        } else {
            ingredient.name
        }

        return if (qtyText.isBlank()) {
            nameWithNote
        } else {
            "$qtyText $nameWithNote"
        }
    }
}
