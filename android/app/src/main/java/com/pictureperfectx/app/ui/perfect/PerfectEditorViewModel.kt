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
import com.pictureperfectx.app.capture.ImageToner
import com.pictureperfectx.app.capture.ImageTransformer
import com.pictureperfectx.app.capture.PhotoSaver
import com.pictureperfectx.app.capture.ToneAdjustments
import com.pictureperfectx.app.capture.ToneBand
import com.pictureperfectx.app.data.PhotoEntity
import com.pictureperfectx.app.layers.BlendMode
import com.pictureperfectx.app.layers.Document
import com.pictureperfectx.app.layers.History
import com.pictureperfectx.app.layers.Layer
import com.pictureperfectx.app.layers.LayerRenderer
import com.pictureperfectx.app.layers.Mask
import com.pictureperfectx.app.layers.MaskBrush
import com.pictureperfectx.app.layers.SubjectMask
import com.pictureperfectx.app.layers.SubjectMaskResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which set of controls the editor is showing. Everything that changes pixels is an effect layer,
 * so there is no separate "tone" mode — that split was what made one word mean two different things.
 */
enum class PerfectTool(val label: String) { Crop("Crop"), Effects("Effects") }

/** An effect the user can add, as offered by the effects picker. */
enum class EffectKind(val label: String, val description: String) {
    Tone("Tone", "Blacks, shadows, highlights and whites."),
    Bokeh("Bokeh", "Blur the background behind your subject."),
}

data class PerfectEditUiState(
    val geometry: ImageGeometry = ImageGeometry(),
    val tone: ToneAdjustments = ToneAdjustments(),
    val tool: PerfectTool = PerfectTool.Crop,
    val band: ToneBand = ToneBand.Blacks,
    /** Geometry + tone + the layer stack applied — what's displayed. */
    val canvas: Bitmap? = null,
    val document: Document = Document(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Brush radius as a fraction of the image's shorter edge. */
    val brushRadius: Float = 0.12f,
    val brushErases: Boolean = false,
    /** A bokeh layer is waiting on subject detection. */
    val isDetecting: Boolean = false,
    val isSaving: Boolean = false,
    val ready: Boolean = false,
    val notice: String? = null,
    val savedMessage: String? = null,
) {
    /** Width/height of the canvas the crop is expressed against. */
    val canvasRatio: Float
        get() = canvas?.let { if (it.height > 0) it.width.toFloat() / it.height else 1f } ?: 1f

    /** Painting only makes sense on a chosen layer, while the effects tool is showing. */
    val canPaintMask: Boolean get() = tool == PerfectTool.Effects && document.selected != null
}

/**
 * Backs the Perfect Editor's geometry tools. The edit is held as a declarative [ImageGeometry] and
 * only ever rendered — the source bitmap is never mutated, and saving writes a brand-new photo.
 */
class PerfectEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as PicturePerfectApp).photoRepository

    private var sourceFull: Bitmap? = null
    private var sourcePreview: Bitmap? = null

    // Geometry applied but not tone or layers, so a slider drag re-renders colour without redoing
    // the rotate/crop work each frame.
    private var orientedPreview: Bitmap? = null
    private var toneJob: Job? = null
    private var detectJob: Job? = null

    // Undo holds whole documents; a Document stores descriptions rather than pixels, so snapshots
    // are cheap enough for that to be the simplest correct approach.
    private var history = History()

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
            orientedPreview = preview
            // The ViewModel is Activity-scoped and reused, so every load starts from scratch —
            // including the layer stack and its undo history, which would otherwise carry a
            // previous photo's edits onto this one.
            history = History()
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

    fun onReset() {
        history = History()
        _state.update {
            it.copy(tone = ToneAdjustments(), document = Document(), canUndo = false, canRedo = false)
        }
        applyGeometry(ImageGeometry())
    }

    // ---- Tone -----------------------------------------------------------------------------------

    fun onSelectTool(tool: PerfectTool) = _state.update { it.copy(tool = tool) }

    fun onSelectBand(band: ToneBand) = _state.update { it.copy(band = band) }

    fun onToneChanged(band: ToneBand, value: Int) {
        _state.update { it.copy(tone = it.tone.with(band, value.coerceIn(-100, 100))) }
        schedulePreview()
    }

    /**
     * Re-renders tone and the layer stack from the already-oriented preview. Debounced, so dragging
     * a slider or painting a stroke doesn't queue a GPU pass per pixel of travel.
     */
    private fun schedulePreview() {
        val source = orientedPreview ?: return
        toneJob?.cancel()
        toneJob = viewModelScope.launch {
            delay(60)
            val tone = _state.value.tone
            val document = _state.value.document
            val rendered = withContext(Dispatchers.Default) {
                runCatching { composite(source, tone, document) }
            }
            rendered.onSuccess { canvas -> _state.update { it.copy(canvas = canvas) } }
            // Swallowing this left the previous canvas on screen, so a failed GPU pass and a layer
            // that simply does nothing looked identical. Say which it was.
            rendered.onFailure { error ->
                _state.update {
                    it.copy(notice = "That effect couldn't be rendered: ${error.message ?: "unknown error"}")
                }
            }
        }
    }

    /** Tone first, then the stack on top — the same order the export uses. */
    private fun composite(source: Bitmap, tone: ToneAdjustments, document: Document): Bitmap =
        LayerRenderer.render(getApplication(), ImageToner.apply(getApplication(), source, tone), document)

    /** Crop-only changes don't touch the canvas, so they never need a re-render. */
    private fun updateGeometry(transform: (ImageGeometry) -> ImageGeometry) {
        _state.update { it.copy(geometry = transform(it.geometry)) }
    }

    /** Anything that reshapes the canvas re-renders the preview off the main thread. */
    private fun applyGeometry(geometry: ImageGeometry) {
        val source = sourcePreview ?: return
        _state.update { it.copy(geometry = geometry) }
        viewModelScope.launch {
            val oriented = withContext(Dispatchers.Default) {
                runCatching { ImageTransformer.orient(source, geometry) }.getOrNull()
            } ?: return@launch
            orientedPreview = oriented
            val canvas = withContext(Dispatchers.Default) {
                runCatching { composite(oriented, _state.value.tone, _state.value.document) }
                    .getOrDefault(oriented)
            }
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
            val tone = _state.value.tone
            val document = _state.value.document
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // Geometry, then tone, then the stack — the same order the preview composites in,
                    // which is what keeps the saved photo matching what was on screen.
                    val cropped = ImageTransformer.apply(source, geometry)
                    val out = composite(cropped, tone, document)
                    if (out !== cropped && cropped !== source && !cropped.isRecycled) cropped.recycle()
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

    // ---- Layers ---------------------------------------------------------------------------------

    /**
     * Adds an effect and selects it. The caller opens its settings straight away — a layer whose
     * controls aren't immediately reachable is a layer that looks broken, which is exactly what
     * happened before.
     */
    fun onAddEffect(kind: EffectKind) {
        val document = when (kind) {
            // Not neutral: a fresh layer must visibly do something, or adding it looks like a no-op.
            EffectKind.Tone -> _state.value.document.add {
                Layer.Tone(it, adjustments = ToneAdjustments(shadows = 25))
            }
            // A blank mask covers nothing, so the layer waits instead of blurring the whole photo.
            // Detection fills it in a moment later; that is what makes this bokeh and not a blur.
            EffectKind.Bokeh -> _state.value.document.add {
                Layer.Blur(it, name = "Bokeh", mask = Mask.blank())
            }
        }
        commit(document)
        if (kind == EffectKind.Bokeh) document.selectedId?.let { detectSubject(it) }
    }

    /**
     * Runs detection again for an existing bokeh layer — the way back if the user cropped or
     * rotated after adding it, or painted the mask into a mess.
     */
    fun onDetectSubject(id: Long) = detectSubject(id)

    /**
     * Finds the subject and masks the background behind it.
     *
     * Deliberately a one-shot when the layer is added, never part of rendering: a segmentation pass
     * costs far more than the blur it feeds, and the preview re-renders on every slider tick.
     */
    private fun detectSubject(layerId: Long) {
        val source = orientedPreview ?: return
        detectJob?.cancel()
        _state.update { it.copy(isDetecting = true) }
        detectJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { SubjectMask.background(source) }
            // The layer can be gone by now — undo, or a quick delete while detection ran.
            if (_state.value.document.layers.none { it.id == layerId }) {
                _state.update { it.copy(isDetecting = false) }
                return@launch
            }
            when (result) {
                is SubjectMaskResult.Found -> {
                    _state.update { it.copy(isDetecting = false) }
                    commit(_state.value.document.setMask(layerId, result.mask))
                }
                // The mask stays blank, so nothing is blurred and the brush is the way forward.
                is SubjectMaskResult.NotFound ->
                    _state.update { it.copy(isDetecting = false, notice = result.reason) }
            }
        }
    }

    fun onSelectLayer(id: Long) = applyDocument(_state.value.document.select(id), record = false)

    fun onToggleLayerVisibility(id: Long) = commit(_state.value.document.toggleVisibility(id))

    fun onRemoveLayer(id: Long) = commit(_state.value.document.remove(id))

    fun onMoveLayer(id: Long, up: Boolean) = commit(_state.value.document.move(id, up))

    fun onLayerOpacity(id: Long, opacity: Float) =
        applyDocument(_state.value.document.setOpacity(id, opacity), record = false)

    fun onLayerBlend(id: Long, blend: BlendMode) = commit(_state.value.document.setBlend(id, blend))

    /**
     * Edits what a layer actually *does*, as opposed to how it's composited.
     *
     * `withCommon` deliberately can't reach a layer's payload, so these go through
     * [Document.update] directly. Without them a Tone layer stays neutral for its whole life and
     * quietly renders nothing — which also makes the mask brush look broken, since painting a
     * mask onto a no-op layer changes nothing on screen.
     */
    fun onLayerToneChanged(id: Long, band: ToneBand, value: Int) {
        val document = _state.value.document.update(id) { layer ->
            if (layer is Layer.Tone) {
                layer.copy(adjustments = layer.adjustments.with(band, value.coerceIn(-100, 100)))
            } else {
                layer
            }
        }
        applyDocument(document, record = false)
    }

    fun onLayerBlurRadius(id: Long, radius: Int) {
        val document = _state.value.document.update(id) { layer ->
            if (layer is Layer.Blur) layer.copy(radius = radius.coerceIn(1, 60)) else layer
        }
        applyDocument(document, record = false)
    }

    /** Which tonal band a selected Tone layer's single slider is editing. */
    fun onSelectLayerBand(band: ToneBand) = _state.update { it.copy(band = band) }

    /** Slider drags don't each deserve an undo step; the gesture ending pushes one. */
    fun commitLayerEdit() = commit(_state.value.document)

    fun onBrushRadius(radius: Float) = _state.update { it.copy(brushRadius = radius.coerceIn(0.02f, 0.5f)) }

    fun onToggleBrushErase() = _state.update { it.copy(brushErases = !it.brushErases) }

    /**
     * A dab at normalized ([x], [y]). Strokes aren't recorded step by step — that would bury the
     * history under hundreds of entries — so [endStroke] pushes the finished stroke instead.
     */
    fun onPaintMask(x: Float, y: Float) {
        val state = _state.value
        val layer = state.document.selected ?: return
        val painted = MaskBrush.paint(
            mask = if (layer.mask.isEmpty) Mask.blank() else layer.mask,
            x = x,
            y = y,
            radius = state.brushRadius,
            erase = state.brushErases,
        )
        applyDocument(state.document.setMask(layer.id, painted), record = false)
    }

    fun endStroke() = commit(_state.value.document)

    fun onUndo() {
        history = history.undo()
        applyHistory()
    }

    fun onRedo() {
        history = history.redo()
        applyHistory()
    }

    /** Records an undo step, then re-renders. */
    private fun commit(document: Document) {
        history = history.push(document)
        applyDocument(document, record = false)
    }

    private fun applyHistory() {
        applyDocument(history.current, record = false)
    }

    private fun applyDocument(document: Document, record: Boolean) {
        if (record) history = history.push(document)
        _state.update {
            it.copy(document = document, canUndo = history.canUndo, canRedo = history.canRedo)
        }
        schedulePreview()
    }

    override fun onCleared() {
        toneJob?.cancel()
        detectJob?.cancel()
        super.onCleared()
    }

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
