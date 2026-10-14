package org.eu.angsumanray.recipecatcher.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BackupManifest(
    val format: String = "recipe-catcher-backup",
    @SerialName("format_version") val formatVersion: Int = 1,
    @SerialName("db_schema_version") val dbSchemaVersion: Int = 1,
    @SerialName("app_version") val appVersion: String = "1.0.0",
    @SerialName("created_at") val createdAt: String,
    @SerialName("recipe_count") val recipeCount: Int,
    @SerialName("image_count") val imageCount: Int,
    @SerialName("db_sha256") val dbSha256: String
)
