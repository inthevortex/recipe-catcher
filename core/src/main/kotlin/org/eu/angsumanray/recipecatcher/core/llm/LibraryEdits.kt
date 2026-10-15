package org.eu.angsumanray.recipecatcher.core.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A tag of one kind, by name; the app resolves it to a stored tag and creates it when new. */
@Serializable
data class TagRef(
    val kind: String,
    val name: String
)

/** Changes chat proposes for one recipe. Null fields are left as they are. */
@Serializable
data class RecipeEdit(
    @SerialName("recipe_id") val recipeId: Long,
    val add: List<TagRef> = emptyList(),
    val remove: List<TagRef> = emptyList(),
    @SerialName("diet_type") val dietType: String? = null,
    val complexity: String? = null,
    @SerialName("spice_level") val spiceLevel: String? = null,
    @SerialName("make_ahead") val makeAhead: Boolean? = null,
    @SerialName("freezer_friendly") val freezerFriendly: Boolean? = null,
    val reason: String = ""
) {
    val isEmpty: Boolean
        get() = add.isEmpty() && remove.isEmpty() && dietType == null && complexity == null &&
            spiceLevel == null && makeAhead == null && freezerFriendly == null
}

/** What applying one [RecipeEdit] actually changed, kept so it can be undone exactly. */
@Serializable
data class AppliedRecipeChange(
    @SerialName("recipe_id") val recipeId: Long,
    @SerialName("added_tag_ids") val addedTagIds: List<Long> = emptyList(),
    /** Removed links as tag id and the link's source, so undo restores them as they were. */
    @SerialName("removed_links") val removedLinks: List<RemovedTagLink> = emptyList(),
    @SerialName("previous_diet_type") val previousDietType: String? = null,
    @SerialName("previous_complexity") val previousComplexity: String? = null,
    @SerialName("previous_spice_level") val previousSpiceLevel: String? = null,
    @SerialName("previous_make_ahead") val previousMakeAhead: Boolean? = null,
    @SerialName("previous_freezer_friendly") val previousFreezerFriendly: Boolean? = null,
    @SerialName("fields_changed") val fieldsChanged: List<String> = emptyList()
)

@Serializable
data class RemovedTagLink(
    @SerialName("tag_id") val tagId: Long,
    val source: String
)

/** Library changes proposed in chat; nothing is written until the user applies them. */
@Serializable
data class LibraryEditProposal(
    val summary: String,
    val edits: List<RecipeEdit>,
    val status: String = STATUS_PROPOSED,
    val applied: List<AppliedRecipeChange> = emptyList(),
    @SerialName("created_tag_ids") val createdTagIds: List<Long> = emptyList()
) {
    companion object {
        const val STATUS_PROPOSED = "proposed"
        const val STATUS_APPLIED = "applied"
        const val STATUS_DISMISSED = "dismissed"
        const val STATUS_UNDONE = "undone"

        const val KIND_TAG = "tag"
        const val KIND_CONTAINS = "diet"
        const val KIND_EQUIPMENT = "equipment"
        const val KIND_CONSUMABLE = "consumable"
        val TAG_KINDS = setOf(KIND_TAG, KIND_CONTAINS, KIND_EQUIPMENT, KIND_CONSUMABLE)

        const val FIELD_DIET_TYPE = "diet_type"
        const val FIELD_COMPLEXITY = "complexity"
        const val FIELD_SPICE_LEVEL = "spice_level"
        const val FIELD_MAKE_AHEAD = "make_ahead"
        const val FIELD_FREEZER_FRIENDLY = "freezer_friendly"

        /** The model's own words for kinds, mapped to the stored ones. */
        private val KIND_ALIASES = mapOf(
            "tags" to KIND_TAG,
            "contains" to KIND_CONTAINS,
            "allergen" to KIND_CONTAINS,
            "allergens" to KIND_CONTAINS,
            "diet" to KIND_CONTAINS,
            "consumables" to KIND_CONSUMABLE
        )

        fun normaliseKind(kind: String): String? {
            val lower = kind.trim().lowercase()
            return lower.takeIf { it in TAG_KINDS } ?: KIND_ALIASES[lower]
        }

        /**
         * Keeps only edits for recipes in [validIds], with known tag kinds and enum values; drops
         * tags added and removed in the same edit, duplicate recipes and edits left empty.
         */
        fun validated(summary: String, edits: List<RecipeEdit>, validIds: Set<Long>): LibraryEditProposal {
            fun clean(tags: List<TagRef>) = tags.mapNotNull { ref ->
                val kind = normaliseKind(ref.kind) ?: return@mapNotNull null
                val name = ref.name.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                TagRef(kind, name)
            }.distinctBy { it.kind to it.name.lowercase() }

            val cleaned = edits
                .filter { it.recipeId in validIds }
                .distinctBy { it.recipeId }
                .map { edit ->
                    val add = clean(edit.add)
                    val remove = clean(edit.remove)
                    val conflicting = add.map { it.kind to it.name.lowercase() }
                        .intersect(remove.map { it.kind to it.name.lowercase() }.toSet())
                    edit.copy(
                        add = add.filterNot { (it.kind to it.name.lowercase()) in conflicting },
                        remove = remove.filterNot { (it.kind to it.name.lowercase()) in conflicting },
                        dietType = edit.dietType?.lowercase()?.takeIf { it in ClaudeExtractor.DIET_TYPE_VALUES },
                        complexity = edit.complexity?.lowercase()?.takeIf { it in ClaudeExtractor.COMPLEXITY_VALUES },
                        spiceLevel = edit.spiceLevel?.lowercase()?.takeIf { it in ClaudeExtractor.SPICE_LEVEL_VALUES },
                        reason = edit.reason.trim()
                    )
                }
                .filterNot { it.isEmpty }
            return LibraryEditProposal(summary = summary.trim(), edits = cleaned)
        }
    }
}
