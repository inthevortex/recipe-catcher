package org.eu.angsumanray.recipecatcher.ui.edit

import org.eu.angsumanray.recipecatcher.ui.edit.EditRecipeViewModel.Companion.formatQuantityText
import org.eu.angsumanray.recipecatcher.ui.edit.EditRecipeViewModel.Companion.parseQuantityText
import org.junit.Assert.assertEquals
import org.junit.Test

class EditRecipeQuantityTextTest {

    @Test
    fun `parses single values including partial and comma input`() {
        assertEquals(1.0 to null, parseQuantityText("1"))
        assertEquals(1.0 to null, parseQuantityText("1."))
        assertEquals(1.5 to null, parseQuantityText("1.5"))
        assertEquals(1.5 to null, parseQuantityText("1,5"))
        assertEquals(null to null, parseQuantityText(""))
        assertEquals(null to null, parseQuantityText("abc"))
    }

    @Test
    fun `parses ranges with hyphen or en dash`() {
        assertEquals(2.0 to 3.0, parseQuantityText("2-3"))
        assertEquals(2.0 to 3.0, parseQuantityText("2 – 3"))
        assertEquals(2.0 to null, parseQuantityText("2-"))
        assertEquals(5.0 to null, parseQuantityText("5-3"))
    }

    @Test
    fun `formats without trailing zero decimals`() {
        assertEquals("2", formatQuantityText(2.0, null))
        assertEquals("1.5", formatQuantityText(1.5, null))
        assertEquals("2–3", formatQuantityText(2.0, 3.0))
        assertEquals("", formatQuantityText(null, null))
    }
}
