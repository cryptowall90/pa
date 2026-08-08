package com.pictureperfectx.app.ui.camera

import android.app.Application
import androidx.camera.core.CameraSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pictureperfectx.app.PicturePerfectApp
import com.pictureperfectx.app.camera.CameraController
import com.pictureperfectx.app.camera.CaptureResult
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
    private val prefs = (app as PicturePerfectApp).userPrefs

    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private val _events = Channel<CameraEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // Format support is only known once a lens is bound, and changes when the lens does.
        controller.onFormatsChanged = { formats, active ->
            _state.update {
                it.copy(
                    availableFormats = formats,
                    captureFormat = active,
                    // A fallback to JPEG makes the RAW caveat irrelevant.
                    showRawFilterNotice = it.showRawFilterNotice && !active.appliesLook,
                )
            }
        }
        // A refused format used to freeze the preview, then only flashed a snackbar that was easy
        // to miss. It now sits on screen until the user acknowledges it.
        controller.onBindError = { message -> _state.update { it.copy(bindMessage = message) } }
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

    /** Cycles through the formats this lens can actually deliver. */
    fun onCycleCaptureFormat() {
        controller.setCaptureFormat(controller.captureFormat.next(controller.availableFormats))
        val format = controller.captureFormat
        _state.update {
            it.copy(
                captureFormat = format,
                // Entering RAW-only silently disables the looks, so say so once.
                showRawFilterNotice = !format.appliesLook && !prefs.rawFilterNoticeDismissed,
            )
        }
    }

    fun onDismissRawNotice() = _state.update { it.copy(showRawFilterNotice = false) }

    fun onNeverShowRawNotice() {
        prefs.rawFilterNoticeDismissed = true
        _state.update { it.copy(showRawFilterNotice = false) }
    }

    fun onDismissBindMessage() = _state.update { it.copy(bindMessage = null) }

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
            onResult = { result -> persist(result) },
            onFailure = { throwable ->
                _state.update { it.copy(isSaving = false) }
                emit(CameraEvent.Error(throwable.message ?: "Capture failed"))
            },
        )
    }

    private fun persist(result: CaptureResult) {
        viewModelScope.launch {
            val bitmap = when (result) {
                is CaptureResult.Jpeg -> result.bitmap
                is CaptureResult.Raw -> result.jpeg?.bitmap
            }
            try {
                val indexed = withContext(Dispatchers.IO) { record(result) }
                _state.update { it.copy(isSaving = false, lastSavedThumbUri = indexed) }
                emit(CameraEvent.Saved(savedMessage(result)))
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false) }
                emit(CameraEvent.Error(e.message ?: "Couldn't save photo"))
            } finally {
                if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    /** Writes the capture to storage + the Room index, returning the URI the gallery should show. */
    private suspend fun record(result: CaptureResult): String = when (result) {
        is CaptureResult.Jpeg -> {
            val saved = PhotoSaver.save(getApplication(), result.bitmap)
            repository.record(entity(saved.uri.toString(), saved.displayName, result.filter, saved.width, saved.height, null))
            saved.uri.toString()
        }

        is CaptureResult.Raw -> {
            val jpeg = result.jpeg
            val raw = result.dngUri.toString()
            // A DNG carries no look, so it's always indexed as Original rather than claiming the
            // active filter was applied to unprocessed sensor data.
            val rawEntity = entity(raw, result.dngName, FilterCatalog.original, result.width, result.height, raw)

            if (jpeg != null) {
                // RAW+JPEG writes two files, so the app gallery gets two independent entries to
                // match the phone gallery: the DNG on its own, and the filtered JPEG as an ordinary
                // editable photo. The DNG is inserted first so the JPEG takes the higher row id and
                // sorts ahead of it when their timestamps tie.
                repository.record(rawEntity)
                val name = result.dngName.removeSuffix(".dng") + ".jpg"
                val saved = PhotoSaver.save(getApplication(), jpeg.bitmap, name)
                repository.record(
                    entity(saved.uri.toString(), saved.displayName, jpeg.filter, saved.width, saved.height, null),
                )
                saved.uri.toString()
            } else {
                repository.record(rawEntity)
                raw
            }
        }
    }

    private fun entity(
        uri: String,
        displayName: String,
        filter: Filter,
        width: Int,
        height: Int,
        rawUri: String?,
    ) = PhotoEntity(
        uri = uri,
        displayName = displayName,
        filterId = filter.id,
        filterName = filter.displayName,
        lensFacing = if (controller.lensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back",
        width = width,
        height = height,
        rawUri = rawUri,
    )

    private fun savedMessage(result: CaptureResult): String = when {
        result !is CaptureResult.Raw -> "Saved to your gallery"
        result.jpeg != null -> "Saved RAW + JPEG to your gallery"
        else -> "Saved RAW to your gallery"
    }

    private fun emit(event: CameraEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    override fun onCleared() {
        controller.release()
        super.onCleared()
    }
}
