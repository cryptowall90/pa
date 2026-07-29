package com.pictureperfectx.app.ui.gallery

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pictureperfectx.app.PicturePerfectApp
import com.pictureperfectx.app.data.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs the in-app gallery from the local Room index. Reading and deleting stay entirely on-device
 * (Room + MediaStore); no network is involved.
 */
class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as PicturePerfectApp).photoRepository

    val photos: StateFlow<List<PhotoEntity>> =
        repository.photos.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(photo: PhotoEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // We own these files, so a direct MediaStore delete succeeds; ignore if already gone.
                runCatching {
                    getApplication<Application>().contentResolver.delete(Uri.parse(photo.uri), null, null)
                }.onFailure { Log.w("GalleryViewModel", "MediaStore delete failed", it) }
                repository.remove(photo.id)
            }
        }
    }
}
