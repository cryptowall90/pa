package com.pictureperfectx.app.ui.perfect

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pictureperfectx.app.PicturePerfectApp
import com.pictureperfectx.app.capture.AspectRatio
import com.pictureperfectx.app.capture.BitmapIO
import com.pictureperfectx.app.capture.CropMath
import com.pictureperfectx.app.capture.CropRect
import com.pictureperfectx.app.capture.ImageGeometry
import com.pictureperfectx.app.capture.ImageTransformer
import com.pictureperfectx.app.capture.PhotoSaver
import com.pictureperfectx.app.data.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PerfectEditUiState(
    val geometry: ImageGeometry = ImageGeometry(),
    /** The source with flips/turns/straighten applied — what the crop overlay is drawn over. */
    val canvas: Bitmap? = null,
    val isSaving: Boolean = false,
    val ready: Boolean = false,
    val notice: String? = null,
    val savedMessage: String? = null,
) {
    /** Width/height of the canvas the crop is expressed against. */
    val canvasRatio: Float
        get() = canvas?.let { if (it.height > 0) it.width.toFloat() / it.height else 1f } ?: 1f
}

/**
 * Backs the Perfect Editor's geometry tools. The edit is held as a declarative [ImageGeometry] and
 * only ever rendered — the source bitmap is never mutated, and saving writes a brand-new photo.
 */
class PerfectEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as PicturePerfectApp).photoRepository

    private var sourceFull: Bitmap? = null
    private var sourcePreview: Bitmap? = null

    private val _state = MutableStateFlow(PerfectEditUiState())
    val state: StateFlow<PerfectEditUiState> = _state.asStateFlow()

    fun load(uri: Uri) {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                BitmapIO.loadForEdit(getApplication(), uri, FULL_MAX_EDGE)
            }
            val full = loaded?.bitmap
            val preview = full?.let { scaleToMaxEdge(it, PREVIEW_MAX_EDGE) }
            sourceFull = full
            sourcePreview = preview
            // The ViewModel is Activity-scoped and reused, so every load starts from scratch.
            _state.update {
                PerfectEditUiState(
                    geometry = ImageGeometry(),
                    canvas = preview,
                    ready = full != null,
                    notice = when {
                        loaded == null -> "This photo couldn't be opened for editing."
                        loaded.degraded ->
                            "This device can't decode the raw file, so you're editing its embedded " +
                                "preview — the saved photo will be lower resolution than the original."
                        else -> null
                    },
                )
            }
        }
    }

    // ---- Geometry -------------------------------------------------------------------------------

    /** Crop changes come straight from the overlay's drag maths, already in normalized space. */
    fun onCropChanged(crop: CropRect) = updateGeometry { it.copy(crop = CropMath.clamp(crop)) }

    fun onAspectSelected(aspect: AspectRatio) {
        val canvasRatio = _state.value.canvasRatio
        val ratio = aspect.ratio(canvasRatio)
        updateGeometry { geometry ->
            geometry.copy(
                aspect = aspect,
                // Free keeps whatever is on screen; a fixed ratio re-frames to the largest fit.
                crop = if (ratio == null) geometry.crop else CropMath.centeredCrop(canvasRatio, ratio),
            )
        }
    }

    fun onRotate(clockwise: Boolean) {
        val geometry = _state.value.geometry
        applyGeometry(
            geometry.copy(
                quarterTurns = geometry.quarterTurns + if (clockwise) 1 else -1,
                // The canvas swaps axes, so the old crop would mean something else entirely.
                crop = CropRect(),
            ),
        )
    }

    fun onFlip(horizontal: Boolean) {
        val geometry = _state.value.geometry
        applyGeometry(
            if (horizontal) {
                geometry.copy(flipHorizontal = !geometry.flipHorizontal)
            } else {
                geometry.copy(flipVertical = !geometry.flipVertical)
            },
        )
    }

    fun onStraighten(degrees: Float) {
        val clamped = degrees.coerceIn(-CropMath.MAX_STRAIGHTEN_DEGREES, CropMath.MAX_STRAIGHTEN_DEGREES)
        applyGeometry(_state.value.geometry.copy(straightenDegrees = clamped))
    }

    fun onReset() = applyGeometry(ImageGeometry())

    /** Crop-only changes don't touch the canvas, so they never need a re-render. */
    private fun updateGeometry(transform: (ImageGeometry) -> ImageGeometry) {
        _state.update { it.copy(geometry = transform(it.geometry)) }
    }

    /** Anything that reshapes the canvas re-renders the preview off the main thread. */
    private fun applyGeometry(geometry: ImageGeometry) {
        val source = sourcePreview ?: return
        _state.update { it.copy(geometry = geometry) }
        viewModelScope.launch {
            val canvas = withContext(Dispatchers.Default) {
                runCatching { ImageTransformer.orient(source, geometry) }.getOrNull()
            } ?: return@launch
            _state.update { current ->
                val ratio = if (canvas.height > 0) canvas.width.toFloat() / canvas.height else 1f
                val ratioTarget = current.geometry.aspect.ratio(ratio)
                current.copy(
                    canvas = canvas,
                    // A reshaped canvas changes what a locked ratio means, so re-fit the crop.
                    geometry = if (ratioTarget == null) {
                        current.geometry
                    } else {
                        current.geometry.copy(crop = CropMath.centeredCrop(ratio, ratioTarget))
                    },
                )
            }
        }
    }

    // ---- Save -----------------------------------------------------------------------------------

    fun save(onSaved: () -> Unit) {
        val source = sourceFull ?: return
        if (_state.value.isSaving) return
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val geometry = _state.value.geometry
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val out = ImageTransformer.apply(source, geometry)
                    val saved = PhotoSaver.save(getApplication(), out)
                    repository.record(
                        PhotoEntity(
                            uri = saved.uri.toString(),
                            displayName = saved.displayName,
                            filterId = "original",
                            filterName = "Perfect Editor",
                            lensFacing = "edit",
                            width = saved.width,
                            height = saved.height,
                        ),
                    )
                    if (out !== source && !out.isRecycled) out.recycle()
                }.isSuccess
            }
            _state.update {
                it.copy(
                    isSaving = false,
                    savedMessage = if (ok) "Saved to your gallery" else "Couldn't save",
                )
            }
            if (ok) onSaved()
        }
    }

    fun consumeMessage() = _state.update { it.copy(savedMessage = null) }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    private fun scaleToMaxEdge(source: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxEdge) return source
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private companion object {
        const val FULL_MAX_EDGE = 2560
        const val PREVIEW_MAX_EDGE = 1280
    }
}
