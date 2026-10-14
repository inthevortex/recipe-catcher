package org.eu.angsumanray.recipecatcher.data.db

import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity

object StarterLists {
    val TAGS = listOf(
        "breakfast", "lunch", "dinner", "snack", "dessert", "drink",
        "chicken", "mutton", "fish", "seafood", "paneer", "indian",
        "bengali", "south-indian", "chinese", "italian", "continental",
        "quick (under 30 min)", "one-pot", "baking", "healthy", "party"
    )

    val DIET = listOf(
        "dairy", "gluten", "egg", "nuts", "peanuts", "soy", "sesame", "shellfish", "onion & garlic"
    )

    val EQUIPMENT = listOf(
        "kadai", "tawa", "frying pan", "saucepan", "pot", "pressure cooker", "air fryer",
        "OTG / oven", "microwave", "mixer-grinder", "blender", "hand mixer", "stand mixer",
        "food processor", "steamer", "grill pan", "baking tray", "cake tin", "idli stand"
    )

    val CONSUMABLES = listOf(
        "parchment paper", "butter paper", "aluminium foil", "cling film", "cellophane",
        "wrapping paper", "tissue / paper towels", "muslin cloth", "skewers", "toothpicks",
        "piping bag", "ziplock bags"
    )

    fun createInitialEntities(): List<TagEntity> {
        val list = mutableListOf<TagEntity>()
        var pos = 0
        TAGS.forEach { list.add(TagEntity(kind = TagEntity.KIND_TAG, name = it, position = pos++)) }
        pos = 0
        DIET.forEach { list.add(TagEntity(kind = TagEntity.KIND_DIET, name = it, position = pos++)) }
        pos = 0
        EQUIPMENT.forEach { list.add(TagEntity(kind = TagEntity.KIND_EQUIPMENT, name = it, position = pos++)) }
        pos = 0
        CONSUMABLES.forEach { list.add(TagEntity(kind = TagEntity.KIND_CONSUMABLE, name = it, position = pos++)) }
        return list
    }
}
