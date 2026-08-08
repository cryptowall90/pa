package com.pictureperfectx.app.ui.edit

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pictureperfectx.app.PicturePerfectApp
import com.pictureperfectx.app.capture.BitmapIO
import com.pictureperfectx.app.capture.ImageEditor
import com.pictureperfectx.app.capture.PhotoSaver
import com.pictureperfectx.app.data.PhotoEntity
import com.pictureperfectx.app.filter.Filter
import com.pictureperfectx.app.filter.FilterCatalog
import com.pictureperfectx.app.ui.camera.Adjustment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

data class EditUiState(
    val filters: List<Filter> = listOf(FilterCatalog.original),
    val selectedFilterId: String = Filter.ORIGINAL_ID,
    val intensity: Int = 100,
    val selectedAdjustment: Adjustment = Adjustment.Brightness,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val preview: Bitmap? = null,
    val isSaving: Boolean = false,
    val ready: Boolean = false,
    val savedMessage: String? = null,
) {
    val selectedFilter: Filter? get() = filters.firstOrNull { it.id == selectedFilterId }
    val intensityEnabled: Boolean get() = selectedFilter?.isOriginal == false
}

/**
 * Backs the editor: loads a source photo (a saved capture or a picked device image), renders a
 * debounced live preview with the chosen look + tone adjustments, and saves the result as a new
 * photo. All rendering is on-device via [ImageEditor].
 */
class EditViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as PicturePerfectApp).photoRepository
    private val renderDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private var sourceFull: Bitmap? = null   // capped full-res, used on save
    private var sourcePreview: Bitmap? = null // downscaled, used for the live preview
    private var renderJob: Job? = null

    private val _state = MutableStateFlow(EditUiState())
    val state: StateFlow<EditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val filters = withContext(Dispatchers.IO) { FilterCatalog.load(getApplication()) }
            _state.update { it.copy(filters = filters) }
        }
    }

    fun load(uri: Uri) {
        viewModelScope.launch {
            val (full, preview) = withContext(Dispatchers.IO) {
                val full = BitmapIO.load(getApplication(), uri, FULL_MAX_EDGE)
                val preview = full?.let { scaleToMaxEdge(it, PREVIEW_MAX_EDGE) }
                full to preview
            }
            sourceFull = full
            sourcePreview = preview
            // Reset controls for a clean edit (the ViewModel is reused across sessions).
            _state.update {
                it.copy(
                    ready = full != null,
                    preview = preview,
                    selectedFilterId = Filter.ORIGINAL_ID,
                    intensity = 100,
                    selectedAdjustment = Adjustment.Brightness,
                    brightness = 0,
                    contrast = 0,
                    saturation = 0,
                )
            }
            scheduleRender()
        }
    }

    fun onFilterSelected(filter: Filter) {
        _state.update { it.copy(selectedFilterId = filter.id) }
        scheduleRender()
    }

    fun onIntensityChanged(v: Int) {
        _state.update { it.copy(intensity = v) }
        scheduleRender()
    }

    fun onSelectAdjustment(a: Adjustment) = _state.update { it.copy(selectedAdjustment = a) }

    fun onBrightnessChanged(v: Int) {
        _state.update { it.copy(brightness = v) }
        scheduleRender()
    }

    fun onContrastChanged(v: Int) {
        _state.update { it.copy(contrast = v) }
        scheduleRender()
    }

    fun onSaturationChanged(v: Int) {
        _state.update { it.copy(saturation = v) }
        scheduleRender()
    }

    private fun scheduleRender() {
        val src = sourcePreview ?: return
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            delay(60) // debounce rapid slider changes
            val s = _state.value
            val out = withContext(renderDispatcher) {
                runCatching {
                    ImageEditor.render(
                        getApplication(), src, s.selectedFilter ?: FilterCatalog.original,
                        s.intensity, s.brightness, s.contrast, s.saturation,
                    )
                }.getOrNull()
            }
            if (out != null) _state.update { it.copy(preview = out) }
        }
    }

    fun save(onSaved: () -> Unit) {
        val src = sourceFull ?: return
        if (_state.value.isSaving) return
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val s = _state.value
                    val out = ImageEditor.render(
                        getApplication(), src, s.selectedFilter ?: FilterCatalog.original,
                        s.intensity, s.brightness, s.contrast, s.saturation,
                    )
                    val saved = PhotoSaver.save(getApplication(), out)
                    repository.record(
                        PhotoEntity(
                            uri = saved.uri.toString(),
                            displayName = saved.displayName,
                            filterId = s.selectedFilterId,
                            filterName = s.selectedFilter?.displayName ?: "Original",
                            lensFacing = "edit",
                            width = saved.width,
                            height = saved.height,
                        ),
                    )
                    if (!out.isRecycled) out.recycle()
                }.isSuccess
            }
            _state.update {
                it.copy(isSaving = false, savedMessage = if (ok) "Saved to Pictures/PicturePerfectX" else "Couldn't save")
            }
            if (ok) onSaved()
        }
    }

    fun consumeMessage() = _state.update { it.copy(savedMessage = null) }

    override fun onCleared() {
        renderJob?.cancel()
        renderDispatcher.close()
        super.onCleared()
    }

    private fun scaleToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }

    private companion object {
        const val FULL_MAX_EDGE = 2560
        const val PREVIEW_MAX_EDGE = 1280
    }
}
