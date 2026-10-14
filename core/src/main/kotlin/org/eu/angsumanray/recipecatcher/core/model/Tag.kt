package org.eu.angsumanray.recipecatcher.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TagKind(val value: String) {
    TAG("tag"),
    DIET("diet"),
    EQUIPMENT("equipment"),
    CONSUMABLE("consumable");

    companion object {
        fun from(value: String): TagKind {
            return entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: TAG
        }
    }
}

@Serializable
data class Tag(
    val id: Long = 0L,
    val kind: String,
    val name: String,
    val position: Int = 0
)
