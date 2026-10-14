package org.eu.angsumanray.recipecatcher.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SourceType(val value: String) {
    @SerialName("instagram")
    INSTAGRAM("instagram"),

    @SerialName("web")
    WEB("web"),

    @SerialName("youtube")
    YOUTUBE("youtube"),

    @SerialName("manual")
    MANUAL("manual");

    companion object {
        fun from(value: String): SourceType {
            return entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: MANUAL
        }
    }
}
