package org.eu.angsumanray.recipecatcher.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    @Test
    fun recipeModel_serializationRoundTrip() {
        val ingredient = Ingredient(
            id = 1L,
            recipeId = 100L,
            position = 0,
            quantity = 2.0,
            quantityMax = 3.0,
            unit = "tbsp",
            name = "butter",
            baseName = "butter",
            category = "dairy"
        )
        val step = Step(
            id = 1L,
            recipeId = 100L,
            position = 0,
            text = "Melt butter in kadai",
            timerSeconds = 120
        )
        val tag = Tag(
            id = 1L,
            kind = TagKind.TAG.value,
            name = "indian"
        )
        val recipe = Recipe(
            id = 100L,
            sourceType = SourceType.INSTAGRAM,
            sourceKey = "ABC123",
            sourceUrl = "https://www.instagram.com/p/ABC123/",
            authorHandle = "chefindia",
            title = "Paneer Butter Masala",
            servings = 4,
            prepMin = 10,
            cookMin = 25,
            totalMin = 35,
            cuisine = "Indian",
            complexity = "medium",
            spiceLevel = "medium",
            dietType = "vegetarian",
            ingredients = listOf(ingredient),
            steps = listOf(step),
            tags = listOf(tag)
        )

        val encoded = json.encodeToString(recipe)
        val decoded = json.decodeFromString<Recipe>(encoded)

        assertEquals(recipe.id, decoded.id)
        assertEquals(recipe.title, decoded.title)
        assertEquals(SourceType.INSTAGRAM, decoded.sourceType)
        assertEquals(1, decoded.ingredients.size)
        assertEquals("butter", decoded.ingredients[0].name)
        assertEquals(1, decoded.steps.size)
        assertEquals(120, decoded.steps[0].timerSeconds)
        assertEquals(1, decoded.tags.size)
        assertEquals("indian", decoded.tags[0].name)
    }

    @Test
    fun cookLogEntry_serializationRoundTrip() {
        val entry = CookLogEntry(
            id = 5L,
            recipeId = 100L,
            cookedOn = "2026-10-15",
            note = "Turned out delicious",
            photoPath = "cooked/100_photo.jpg"
        )
        val encoded = json.encodeToString(entry)
        val decoded = json.decodeFromString<CookLogEntry>(encoded)
        assertEquals(entry, decoded)
    }

    @Test
    fun planSlotAndEntry_serializationRoundTrip() {
        val slot = PlanSlot(
            id = 12L,
            date = "2026-10-15",
            name = "Dinner",
            time = "20:00",
            position = 1
        )
        val entry = PlanEntry(
            id = 20L,
            slotId = 12L,
            kind = PlanEntryKind.COOK.value,
            recipeId = 100L,
            portions = 4,
            portionsEatenNow = 2
        )

        val decodedSlot = json.decodeFromString<PlanSlot>(json.encodeToString(slot))
        val decodedEntry = json.decodeFromString<PlanEntry>(json.encodeToString(entry))

        assertEquals(slot, decodedSlot)
        assertEquals(entry, decodedEntry)
    }

    @Test
    fun inventoryAndGroceryItem_serializationRoundTrip() {
        val inv = InventoryItem(
            id = 1L,
            name = "Red Onions",
            baseName = "onion",
            category = "vegetables",
            status = InventoryStatus.IN_STOCK.value
        )
        val grocery = GroceryItem(
            id = 2L,
            name = "Onions",
            baseName = "onion",
            category = "vegetables",
            quantityText = "1 kg",
            source = GrocerySource.PLAN.value,
            checked = false
        )

        val decodedInv = json.decodeFromString<InventoryItem>(json.encodeToString(inv))
        val decodedGrocery = json.decodeFromString<GroceryItem>(json.encodeToString(grocery))

        assertEquals(inv.name, decodedInv.name)
        assertEquals(grocery.quantityText, decodedGrocery.quantityText)
    }

    @Test
    fun chatMessage_serializationRoundTrip() {
        val msg = ChatMessage(
            id = 1L,
            sessionId = 10L,
            role = "user",
            text = "What can I cook with paneer?",
            includeWeb = true
        )
        val decoded = json.decodeFromString<ChatMessage>(json.encodeToString(msg))
        assertEquals(msg.text, decoded.text)
        assertTrue(decoded.includeWeb)
    }

    @Test
    fun enumHelpers_parseCorrectly() {
        assertEquals(SourceType.INSTAGRAM, SourceType.from("instagram"))
        assertEquals(SourceType.YOUTUBE, SourceType.from("youtube"))
        assertEquals(SourceType.WEB, SourceType.from("web"))
        assertEquals(SourceType.MANUAL, SourceType.from("unknown_type"))

        assertEquals(TagKind.DIET, TagKind.from("diet"))
        assertEquals(RecipeStatus.SAVED, RecipeStatus.from("saved"))
        assertEquals(PlanEntryKind.LEFTOVER, PlanEntryKind.from("leftover"))
        assertEquals(InventoryStatus.LOW, InventoryStatus.from("low"))
        assertEquals(GrocerySource.PLAN, GrocerySource.from("plan"))
    }
}
