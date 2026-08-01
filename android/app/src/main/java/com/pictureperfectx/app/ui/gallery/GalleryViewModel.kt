package com.pictureperfectx.app.ui.gallery

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pictureperfectx.app.PicturePerfectApp
import com.pictureperfectx.app.data.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs the in-app gallery from the local Room index. Reading, multi-select and deleting stay
 * entirely on-device (Room + MediaStore); no network is involved.
 */
class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as PicturePerfectApp).photoRepository

    val photos: StateFlow<List<PhotoEntity>> =
        repository.photos.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    /** Long-press: enter selection mode with this one photo selected. */
    fun startSelection(id: Long) = _selected.update { setOf(id) }

    /** Tap while selecting: add/remove this photo. */
    fun toggle(id: Long) = _selected.update { if (id in it) it - id else it + id }

    fun clearSelection() = _selected.update { emptySet() }

    /** Delete every currently selected photo (call after the user confirms). */
    fun deleteSelected() {
        val ids = _selected.value
        if (ids.isEmpty()) return
        val toDelete = photos.value.filter { it.id in ids }
        clearSelection()
        viewModelScope.launch { withContext(Dispatchers.IO) { toDelete.forEach { deleteOne(it) } } }
    }

    /** Delete a single photo (call after the user confirms). */
    fun deleteSingle(photo: PhotoEntity) {
        viewModelScope.launch { withContext(Dispatchers.IO) { deleteOne(photo) } }
    }

    private suspend fun deleteOne(photo: PhotoEntity) {
        // We own these files, so a direct MediaStore delete succeeds; ignore if already gone.
        runCatching {
            getApplication<Application>().contentResolver.delete(Uri.parse(photo.uri), null, null)
        }.onFailure { Log.w("GalleryViewModel", "MediaStore delete failed", it) }
        repository.remove(photo.id)
    }
}
