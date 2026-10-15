package org.eu.angsumanray.recipecatcher.data.repo

import androidx.room.withTransaction
import org.eu.angsumanray.recipecatcher.core.llm.AppliedRecipeChange
import org.eu.angsumanray.recipecatcher.core.llm.LibraryEditProposal
import org.eu.angsumanray.recipecatcher.core.llm.RemovedTagLink
import org.eu.angsumanray.recipecatcher.core.llm.TagRef
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeTagEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Applies library changes proposed in chat, and undoes exactly what an apply changed. */
@Singleton
class LibraryEditor @Inject constructor(
    private val database: RecipeDatabase
) {
    private val recipeDao = database.recipeDao()
    private val tagDao = database.tagDao()
    private val recipeTagDao = database.recipeTagDao()

    /**
     * Applies [proposal] to every recipe except [skippedRecipeIds] in one transaction. Tags named in
     * additions are created when missing; changes a recipe already has are skipped. The returned
     * proposal records what changed, for [undo].
     */
    suspend fun apply(proposal: LibraryEditProposal, skippedRecipeIds: Set<Long> = emptySet()): LibraryEditProposal =
        database.withTransaction {
            val createdTagIds = mutableListOf<Long>()

            suspend fun resolve(ref: TagRef, create: Boolean): Long? {
                val sameKind = tagDao.getByKind(ref.kind)
                sameKind.firstOrNull { it.name.equals(ref.name, ignoreCase = true) }?.let { return it.id }
                if (!create) return null
                val id = tagDao.insert(
                    TagEntity(kind = ref.kind, name = ref.name, position = (sameKind.maxOfOrNull { it.position } ?: 0) + 1)
                )
                createdTagIds += id
                return id
            }

            val applied = proposal.edits.filter { it.recipeId !in skippedRecipeIds }.mapNotNull { edit ->
                val recipe = recipeDao.getById(edit.recipeId) ?: return@mapNotNull null
                val links = recipeTagDao.getLinksForRecipe(recipe.id).associate { it.tagId to it.source }

                val added = edit.add.mapNotNull { resolve(it, create = true) }.distinct().filter { it !in links }
                if (added.isNotEmpty()) {
                    recipeTagDao.insertAll(added.map { RecipeTagEntity(recipe.id, it, RecipeTagEntity.SOURCE_USER) })
                }
                val removed = edit.remove.mapNotNull { resolve(it, create = false) }.distinct()
                    .mapNotNull { tagId -> links[tagId]?.let { RemovedTagLink(tagId, it) } }
                removed.forEach { recipeTagDao.delete(recipe.id, it.tagId) }

                val fields = buildList {
                    if (edit.dietType != null && edit.dietType != recipe.dietType) add(LibraryEditProposal.FIELD_DIET_TYPE)
                    if (edit.complexity != null && edit.complexity != recipe.complexity) add(LibraryEditProposal.FIELD_COMPLEXITY)
                    if (edit.spiceLevel != null && edit.spiceLevel != recipe.spiceLevel) add(LibraryEditProposal.FIELD_SPICE_LEVEL)
                    if (edit.makeAhead != null && edit.makeAhead != recipe.makeAhead) add(LibraryEditProposal.FIELD_MAKE_AHEAD)
                    if (edit.freezerFriendly != null && edit.freezerFriendly != recipe.freezerFriendly) add(LibraryEditProposal.FIELD_FREEZER_FRIENDLY)
                }
                if (fields.isNotEmpty()) {
                    recipeDao.update(
                        recipe.copy(
                            dietType = edit.dietType ?: recipe.dietType,
                            complexity = edit.complexity ?: recipe.complexity,
                            spiceLevel = edit.spiceLevel ?: recipe.spiceLevel,
                            makeAhead = edit.makeAhead ?: recipe.makeAhead,
                            freezerFriendly = edit.freezerFriendly ?: recipe.freezerFriendly,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }

                if (added.isEmpty() && removed.isEmpty() && fields.isEmpty()) return@mapNotNull null
                AppliedRecipeChange(
                    recipeId = recipe.id,
                    addedTagIds = added,
                    removedLinks = removed,
                    previousDietType = recipe.dietType,
                    previousComplexity = recipe.complexity,
                    previousSpiceLevel = recipe.spiceLevel,
                    previousMakeAhead = recipe.makeAhead,
                    previousFreezerFriendly = recipe.freezerFriendly,
                    fieldsChanged = fields
                )
            }

            proposal.copy(
                status = LibraryEditProposal.STATUS_APPLIED,
                applied = applied,
                createdTagIds = createdTagIds
            )
        }

    /**
     * Reverts what [apply] recorded in [proposal]: added tags come off, removed ones go back with
     * their original source, and changed fields return to their old values. Tags the apply created
     * are deleted once no recipe uses them.
     */
    suspend fun undo(proposal: LibraryEditProposal): LibraryEditProposal = database.withTransaction {
        for (change in proposal.applied) {
            change.addedTagIds.forEach { recipeTagDao.delete(change.recipeId, it) }
            if (change.removedLinks.isNotEmpty()) {
                recipeTagDao.insertAll(change.removedLinks.map { RecipeTagEntity(change.recipeId, it.tagId, it.source) })
            }
            if (change.fieldsChanged.isNotEmpty()) {
                val recipe = recipeDao.getById(change.recipeId) ?: continue
                val f = change.fieldsChanged
                recipeDao.update(
                    recipe.copy(
                        dietType = if (LibraryEditProposal.FIELD_DIET_TYPE in f) change.previousDietType else recipe.dietType,
                        complexity = if (LibraryEditProposal.FIELD_COMPLEXITY in f) change.previousComplexity else recipe.complexity,
                        spiceLevel = if (LibraryEditProposal.FIELD_SPICE_LEVEL in f) change.previousSpiceLevel else recipe.spiceLevel,
                        makeAhead = if (LibraryEditProposal.FIELD_MAKE_AHEAD in f) change.previousMakeAhead ?: recipe.makeAhead else recipe.makeAhead,
                        freezerFriendly = if (LibraryEditProposal.FIELD_FREEZER_FRIENDLY in f) change.previousFreezerFriendly ?: recipe.freezerFriendly else recipe.freezerFriendly,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        proposal.createdTagIds
            .filter { recipeTagDao.getRecipeIdsForTag(it).isEmpty() }
            .forEach { tagDao.deleteById(it) }
        proposal.copy(status = LibraryEditProposal.STATUS_UNDONE, applied = emptyList(), createdTagIds = emptyList())
    }
}
