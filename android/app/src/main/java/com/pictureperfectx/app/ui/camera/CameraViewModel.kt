package com.pictureperfectx.app.ui.camera

import android.app.Application
import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pictureperfectx.app.PicturePerfectApp
import com.pictureperfectx.app.camera.CameraController
import com.pictureperfectx.app.capture.PhotoSaver
import com.pictureperfectx.app.data.PhotoEntity
import com.pictureperfectx.app.filter.Filter
import com.pictureperfectx.app.filter.FilterCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    val controller = CameraController(app)

    private val repository = (app as PicturePerfectApp).photoRepository

    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private val _events = Channel<CameraEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        controller.applyFilter(FilterCatalog.original)
        // Load the 100-LUT pack off the main thread, then publish it to the UI.
        viewModelScope.launch {
            val filters = withContext(Dispatchers.IO) { FilterCatalog.load(getApplication()) }
            _state.update { it.copy(filters = filters) }
        }
    }

    fun onFilterSelected(filter: Filter) {
        controller.applyFilter(filter)
        controller.setIntensity(_state.value.intensity)
        _state.update { it.copy(selectedFilterId = filter.id) }
    }

    fun onIntensityChanged(percent: Int) {
        controller.setIntensity(percent)
        _state.update { it.copy(intensity = percent) }
    }

    fun onToggleLens() {
        controller.toggleLens()
        _state.update { it.copy(isFrontFacing = controller.lensFacing == CameraSelector.LENS_FACING_FRONT) }
    }

    fun onCycleFlash() {
        val mode = controller.cycleFlash()
        _state.update { it.copy(flashMode = mode) }
    }

    // ---- Manual controls -----------------------------------------------------------------------

    fun onToggleAdjustments() {
        val range = controller.exposureRange()
        _state.update {
            val opening = !it.showAdjustments
            // Default the selection to an available adjustment.
            val selected = if (it.selectedAdjustment == Adjustment.Exposure && range.upper <= range.lower) {
                Adjustment.Brightness
            } else {
                it.selectedAdjustment
            }
            it.copy(
                showAdjustments = opening,
                selectedAdjustment = selected,
                exposureMin = range.lower,
                exposureMax = range.upper,
            )
        }
    }

    fun onToggleFilters() {
        _state.update { it.copy(showFilters = !it.showFilters) }
    }

    fun onSelectAdjustment(adjustment: Adjustment) {
        _state.update { it.copy(selectedAdjustment = adjustment) }
    }

    fun onBrightnessChanged(v: Int) {
        controller.setBrightness(v)
        _state.update { it.copy(brightness = v) }
    }

    fun onContrastChanged(v: Int) {
        controller.setContrast(v)
        _state.update { it.copy(contrast = v) }
    }

    fun onSaturationChanged(v: Int) {
        controller.setSaturation(v)
        _state.update { it.copy(saturation = v) }
    }

    fun onExposureChanged(index: Int) {
        controller.setExposureIndex(index)
        _state.update { it.copy(exposure = index) }
    }

    /** Tap-to-focus at a metering point built by the PreviewView. */
    fun onFocus(point: androidx.camera.core.MeteringPoint) = controller.focusAndMeter(point)

    /** Pinch-to-zoom: multiply current zoom by the gesture's scale factor. */
    fun onZoom(factor: Float) = controller.scaleZoom(factor)

    fun onCapture() {
        if (_state.value.isSaving) return
        _state.update { it.copy(isSaving = true) }
        controller.capture(
            onResult = { bitmap, filter -> persist(bitmap, filter) },
            onFailure = { throwable ->
                _state.update { it.copy(isSaving = false) }
                emit(CameraEvent.Error(throwable.message ?: "Capture failed"))
            },
        )
    }

    private fun persist(bitmap: Bitmap, filter: Filter) {
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val result = PhotoSaver.save(getApplication(), bitmap)
                    repository.record(
                        PhotoEntity(
                            uri = result.uri.toString(),
                            displayName = result.displayName,
                            filterId = filter.id,
                            filterName = filter.displayName,
                            lensFacing = if (controller.lensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back",
                            width = result.width,
                            height = result.height,
                        ),
                    )
                    result
                }
                _state.update { it.copy(isSaving = false, lastSavedThumbUri = saved.uri.toString()) }
                emit(CameraEvent.Saved("Saved to your gallery"))
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false) }
                emit(CameraEvent.Error(e.message ?: "Couldn't save photo"))
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun emit(event: CameraEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    override fun onCleared() {
        controller.release()
        super.onCleared()
    }
}
