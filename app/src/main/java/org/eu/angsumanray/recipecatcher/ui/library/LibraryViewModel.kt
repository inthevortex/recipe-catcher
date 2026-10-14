package org.eu.angsumanray.recipecatcher.ui.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.capture.CaptureWorker
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val recipeRepository: RecipeRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val savedRecipes: StateFlow<List<RecipeEntity>> = recipeRepository.observeSavedRecipes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingRecipes: StateFlow<List<RecipeEntity>> = recipeRepository.observePendingRecipes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun retryCapture(recipe: RecipeEntity) {
        viewModelScope.launch {
            val nextStatus = if (recipe.captionRaw.isNullOrBlank()) {
                RecipeEntity.STATUS_PENDING_FETCH
            } else {
                RecipeEntity.STATUS_PENDING_EXTRACT
            }
            recipeRepository.updateRecipeStatus(recipe.id, nextStatus, null)
            CaptureWorker.enqueue(context, recipe.id)
        }
    }

    fun deleteRecipe(recipeId: Long) {
        viewModelScope.launch {
            recipeRepository.deleteRecipe(recipeId)
        }
    }
}
