package org.eu.angsumanray.recipecatcher.core.domain

import org.eu.angsumanray.recipecatcher.core.model.Ingredient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuantityScalerTest {

    @Test
    fun calculateFactor_computesCorrectMultiplier() {
        assertEquals(2.0, QuantityScaler.calculateFactor(4, 8), 0.001)
        assertEquals(0.5, QuantityScaler.calculateFactor(4, 2), 0.001)
        assertEquals(1.5, QuantityScaler.calculateFactor(2, 3), 0.001)
        assertEquals(1.0, QuantityScaler.calculateFactor(4, 4), 0.001)
    }

    @Test
    fun calculateFactor_handlesInvalidServings() {
        assertEquals(1.0, QuantityScaler.calculateFactor(null, 4), 0.001)
        assertEquals(1.0, QuantityScaler.calculateFactor(4, null), 0.001)
        assertEquals(1.0, QuantityScaler.calculateFactor(0, 4), 0.001)
        assertEquals(1.0, QuantityScaler.calculateFactor(4, -1), 0.001)
    }

    @Test
    fun scale_scalesSingleValues() {
        assertEquals(5.0, QuantityScaler.scale(2.5, 2.0)!!, 0.001)
        assertEquals(1.0, QuantityScaler.scale(2.0, 0.5)!!, 0.001)
        assertNull(QuantityScaler.scale(null, 2.0))
    }

    @Test
    fun scaleRange_scalesBothEnds() {
        val (min, max) = QuantityScaler.scaleRange(2.0, 3.0, 2.0)
        assertEquals(4.0, min!!, 0.001)
        assertEquals(6.0, max!!, 0.001)
    }

    @Test
    fun scaleRange_handlesNullOrZeroMax() {
        val (min1, max1) = QuantityScaler.scaleRange(2.0, null, 2.0)
        assertEquals(4.0, min1!!, 0.001)
        assertNull(max1)

        val (min2, max2) = QuantityScaler.scaleRange(2.0, 0.0, 2.0)
        assertEquals(4.0, min2!!, 0.001)
        assertNull(max2)
    }

    @Test
    fun scale_scalesIngredientModelCorrectly() {
        val original = Ingredient(
            id = 1L,
            recipeId = 10L,
            position = 0,
            quantity = 2.0,
            quantityMax = 3.0,
            unit = "cloves",
            name = "garlic",
            baseName = "garlic",
            category = "vegetables",
            note = "minced",
            rawText = "2-3 cloves garlic, minced"
        )

        val scaled = QuantityScaler.scale(original, baseServings = 2, targetServings = 4)
        assertEquals(4.0, scaled.quantity!!, 0.001)
        assertEquals(6.0, scaled.quantityMax!!, 0.001)
        assertEquals("cloves", scaled.unit)
        assertEquals("garlic", scaled.name)
        assertEquals("minced", scaled.note)
        assertEquals("2-3 cloves garlic, minced", scaled.rawText)
    }

    @Test
    fun scaleIngredients_scalesWholeList() {
        val ingredients = listOf(
            Ingredient(name = "onion", baseName = "onion", category = "vegetables", quantity = 1.0),
            Ingredient(name = "salt", baseName = "salt", category = "spices", quantity = null, note = "to taste")
        )

        val scaled = QuantityScaler.scaleIngredients(ingredients, baseServings = 2, targetServings = 6)
        assertEquals(3.0, scaled[0].quantity!!, 0.001)
        assertNull(scaled[1].quantity)
        assertEquals("to taste", scaled[1].note)
    }
}
