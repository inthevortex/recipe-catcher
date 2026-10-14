package org.eu.angsumanray.recipecatcher.core.domain

import org.eu.angsumanray.recipecatcher.core.model.Ingredient
import org.junit.Assert.assertEquals
import org.junit.Test

class FractionFormatterTest {

    @Test
    fun toFractionString_formatsStandardFractions() {
        assertEquals("½", FractionFormatter.toFractionString(0.5))
        assertEquals("¼", FractionFormatter.toFractionString(0.25))
        assertEquals("⅓", FractionFormatter.toFractionString(0.333))
        assertEquals("⅔", FractionFormatter.toFractionString(0.667))
        assertEquals("¾", FractionFormatter.toFractionString(0.75))
        assertEquals("⅛", FractionFormatter.toFractionString(0.125))
        assertEquals("⅜", FractionFormatter.toFractionString(0.375))
        assertEquals("⅝", FractionFormatter.toFractionString(0.625))
        assertEquals("⅞", FractionFormatter.toFractionString(0.875))
    }

    @Test
    fun toFractionString_formatsMixedNumbers() {
        assertEquals("1 ½", FractionFormatter.toFractionString(1.5))
        assertEquals("2 ¼", FractionFormatter.toFractionString(2.25))
        assertEquals("1 ⅓", FractionFormatter.toFractionString(1.333))
        assertEquals("3 ⅔", FractionFormatter.toFractionString(3.667))
        assertEquals("2", FractionFormatter.toFractionString(2.0))
        assertEquals("4", FractionFormatter.toFractionString(4.0))
    }

    @Test
    fun formatSingle_formatsFractionUnits() {
        val tspHalf = FractionFormatter.formatSingle(0.5, "tsp")
        assertEquals("½", tspHalf.valueText)
        assertEquals("tsp", tspHalf.unitText)
        assertEquals("½ tsp", tspHalf.displayText)

        val tbspQuarter = FractionFormatter.formatSingle(0.25, "tbsp")
        assertEquals("¼ tbsp", tbspQuarter.displayText)

        val piecesThird = FractionFormatter.formatSingle(0.333, "pieces")
        assertEquals("⅓ pieces", piecesThird.displayText)

        val cloveWhole = FractionFormatter.formatSingle(3.0, "cloves")
        assertEquals("3 cloves", cloveWhole.displayText)
    }

    @Test
    fun formatSingle_formatsGramsCorrectly() {
        // Under 100g -> rounded to whole number
        assertEquals("49 g", FractionFormatter.formatSingle(48.7, "g").displayText)
        assertEquals("12 g", FractionFormatter.formatSingle(12.2, "g").displayText)

        // 100g to 999g -> rounded to nearest 5
        assertEquals("125 g", FractionFormatter.formatSingle(123.0, "g").displayText)
        assertEquals("100 g", FractionFormatter.formatSingle(102.0, "g").displayText)
        assertEquals("125 g", FractionFormatter.formatSingle(127.0, "g").displayText)
        assertEquals("130 g", FractionFormatter.formatSingle(128.0, "g").displayText)
        assertEquals("255 g", FractionFormatter.formatSingle(254.0, "g").displayText)

        // 1000g and above -> switches to kg with 1 decimal
        assertEquals("1.0 kg", FractionFormatter.formatSingle(1000.0, "g").displayText)
        assertEquals("1.3 kg", FractionFormatter.formatSingle(1250.0, "g").displayText)
        assertEquals("1.5 kg", FractionFormatter.formatSingle(1500.0, "g").displayText)
        assertEquals("2.0 kg", FractionFormatter.formatSingle(2000.0, "g").displayText)
    }

    @Test
    fun formatSingle_formatsMillilitersCorrectly() {
        // Under 100ml -> rounded to whole number
        assertEquals("15 ml", FractionFormatter.formatSingle(15.2, "ml").displayText)

        // 100ml to 999ml -> rounded to nearest 5
        assertEquals("250 ml", FractionFormatter.formatSingle(248.0, "ml").displayText)
        assertEquals("175 ml", FractionFormatter.formatSingle(174.0, "ml").displayText)

        // 1000ml and above -> switches to l with 1 decimal
        assertEquals("1.0 l", FractionFormatter.formatSingle(1000.0, "ml").displayText)
        assertEquals("1.8 l", FractionFormatter.formatSingle(1750.0, "ml").displayText)
        assertEquals("2.5 l", FractionFormatter.formatSingle(2500.0, "ml").displayText)
    }

    @Test
    fun format_formatsRangesCorrectly() {
        assertEquals("2–3 cloves", FractionFormatter.format(2.0, 3.0, "cloves"))
        assertEquals("100–150 g", FractionFormatter.format(100.0, 150.0, "g"))
        assertEquals("1.0–1.5 kg", FractionFormatter.format(1000.0, 1500.0, "g"))
        assertEquals("½–1 tsp", FractionFormatter.format(0.5, 1.0, "tsp"))
        assertEquals("2–4", FractionFormatter.format(2.0, 4.0, null))
        assertEquals("", FractionFormatter.format(null, null, "g"))
    }

    @Test
    fun formatIngredient_formatsFullDisplayLine() {
        val item1 = Ingredient(
            name = "chicken thighs",
            baseName = "chicken",
            category = "meat & fish",
            quantity = 1500.0,
            unit = "g",
            note = "boneless"
        )
        assertEquals("1.5 kg chicken thighs (boneless)", FractionFormatter.formatIngredient(item1))

        val item2 = Ingredient(
            name = "garlic",
            baseName = "garlic",
            category = "vegetables",
            quantity = 2.0,
            quantityMax = 3.0,
            unit = "cloves"
        )
        assertEquals("2–3 cloves garlic", FractionFormatter.formatIngredient(item2))

        val item3 = Ingredient(
            name = "salt",
            baseName = "salt",
            category = "spices",
            quantity = null,
            note = "to taste"
        )
        assertEquals("salt (to taste)", FractionFormatter.formatIngredient(item3))
    }
}
