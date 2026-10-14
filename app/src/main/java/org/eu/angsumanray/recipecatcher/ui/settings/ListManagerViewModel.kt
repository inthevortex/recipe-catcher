package org.eu.angsumanray.recipecatcher.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import org.eu.angsumanray.recipecatcher.data.repo.TagRepository
import javax.inject.Inject

data class ListManagerUiState(
    val selectedKind: String = TagEntity.KIND_TAG,
    val tags: List<TagEntity> = emptyList(),
    val diet: List<TagEntity> = emptyList(),
    val equipment: List<TagEntity> = emptyList(),
    val consumables: List<TagEntity> = emptyList()
) {
    val currentItems: List<TagEntity>
        get() = when (selectedKind) {
            TagEntity.KIND_TAG -> tags
            TagEntity.KIND_DIET -> diet
            TagEntity.KIND_EQUIPMENT -> equipment
            TagEntity.KIND_CONSUMABLE -> consumables
            else -> emptyList()
        }
}

@HiltViewModel
class ListManagerViewModel @Inject constructor(
    private val tagRepository: TagRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ListManagerUiState())
    val uiState: StateFlow<ListManagerUiState> = _uiState.asStateFlow()

    init {
        observeTags()
    }

    private fun observeTags() {
        tagRepository.observeTagsByKind(TagEntity.KIND_TAG).onEach {
            _uiState.value = _uiState.value.copy(tags = it)
        }.launchIn(viewModelScope)

        tagRepository.observeTagsByKind(TagEntity.KIND_DIET).onEach {
            _uiState.value = _uiState.value.copy(diet = it)
        }.launchIn(viewModelScope)

        tagRepository.observeTagsByKind(TagEntity.KIND_EQUIPMENT).onEach {
            _uiState.value = _uiState.value.copy(equipment = it)
        }.launchIn(viewModelScope)

        tagRepository.observeTagsByKind(TagEntity.KIND_CONSUMABLE).onEach {
            _uiState.value = _uiState.value.copy(consumables = it)
        }.launchIn(viewModelScope)
    }

    fun selectKind(kind: String) {
        _uiState.value = _uiState.value.copy(selectedKind = kind)
    }

    fun addTag(name: String) {
        viewModelScope.launch {
            tagRepository.addTag(_uiState.value.selectedKind, name)
        }
    }

    fun renameTag(id: Long, newName: String) {
        viewModelScope.launch {
            tagRepository.renameTag(id, newName)
        }
    }

    fun deleteTag(id: Long) {
        viewModelScope.launch {
            tagRepository.deleteTag(id)
        }
    }

    fun mergeTags(sourceTagId: Long, targetTagId: Long) {
        viewModelScope.launch {
            tagRepository.mergeTags(sourceTagId, targetTagId)
        }
    }
}
