package com.pictureperfectx.app.ui.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pictureperfectx.app.PicturePerfectApp
import com.pictureperfectx.app.data.PhotoEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the in-app gallery from the local Room index.
 *
 * Deleting here removes a photo from the **app's** gallery (its Room index row) only — the image
 * file stays on the device, so it remains in the phone's gallery. Fully on-device; no network.
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

    /** Remove every selected photo from the app gallery (files stay on the device). */
    fun deleteSelected() {
        val ids = _selected.value
        if (ids.isEmpty()) return
        clearSelection()
        viewModelScope.launch { ids.forEach { repository.remove(it) } }
    }

    /** Remove a single photo from the app gallery (file stays on the device). */
    fun deleteSingle(photo: PhotoEntity) {
        viewModelScope.launch { repository.remove(photo.id) }
    }
}
