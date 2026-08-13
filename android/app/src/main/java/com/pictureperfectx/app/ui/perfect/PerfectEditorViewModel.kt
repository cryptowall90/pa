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
import com.pictureperfectx.app.capture.ToneAdjustments
import com.pictureperfectx.app.capture.ToneBand
import com.pictureperfectx.app.data.PhotoEntity
import com.pictureperfectx.app.filter.Filter
import com.pictureperfectx.app.filter.FilterCatalog
import com.pictureperfectx.app.layers.BlendMode
import com.pictureperfectx.app.layers.ColourTone
import com.pictureperfectx.app.layers.CurveChannel
import com.pictureperfectx.app.layers.CurvePoint
import com.pictureperfectx.app.layers.CurveSpec
import com.pictureperfectx.app.layers.Curves
import com.pictureperfectx.app.layers.Document
import com.pictureperfectx.app.layers.GradientSpec
import com.pictureperfectx.app.layers.GradientStyle
import com.pictureperfectx.app.layers.History
import com.pictureperfectx.app.layers.Layer
import com.pictureperfectx.app.layers.LayerRenderer
import com.pictureperfectx.app.layers.Mask
import com.pictureperfectx.app.layers.MaskBrush
import com.pictureperfectx.app.layers.MaskGradient
import com.pictureperfectx.app.layers.MaskLasso
import com.pictureperfectx.app.layers.MaskPoint
import com.pictureperfectx.app.layers.SelectionMode
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
 * How much of the editor is showing.
 *
 * The editor opens [Closed] — the photo and nothing else. One button opens a [Menu] of two choices,
 * and choosing one *replaces* the menu with that tool's controls rather than leaving a tab behind.
 * Only what's in use is ever on screen, and the photo keeps whatever is left.
 */
enum class EditorPanel(val label: String) {
    Closed(""),
    Menu(""),
    Crop("Crop"),
    Effects("Add effects"),
}

/** An effect the user can add, as offered by the effects picker. */
enum class EffectKind(val label: String, val description: String) {
    Tone("Tone", "Blacks, shadows, highlights and whites."),
    Look("Look", "One of a hundred film and colour looks."),
    Curve("Curve", "Tone curves, per channel, for contrast and grading."),
    Gradient("Gradient", "A wash of colour across the photo."),
}

/** How an area is chosen: drawn round, painted in by hand, or faded across the frame. */
enum class SelectionTool(val label: String) {
    Lasso("Lasso"),
    Brush("Brush"),
    Gradient("Gradient"),
}

/**
 * The one property the effects panel's single slider is editing, picked from a row of chips.
 *
 * One slider at a time is what keeps the controls to a couple of short rows — and the controls
 * short is what keeps them from eating the photo they're meant to be adjusting.
 */
enum class LayerControl(val label: String, val band: ToneBand? = null) {
    ToneExposure(ToneBand.Exposure.label, ToneBand.Exposure),
    ToneContrast(ToneBand.Contrast.label, ToneBand.Contrast),
    ToneBlacks(ToneBand.Blacks.label, ToneBand.Blacks),
    ToneShadows(ToneBand.Shadows.label, ToneBand.Shadows),
    ToneHighlights(ToneBand.Highlights.label, ToneBand.Highlights),
    ToneWhites(ToneBand.Whites.label, ToneBand.Whites),
    ToneSaturation(ToneBand.Saturation.label, ToneBand.Saturation),
    ToneVibrance(ToneBand.Vibrance.label, ToneBand.Vibrance),
    ToneWarmth(ToneBand.Warmth.label, ToneBand.Warmth),
    Blur("Blur"),
    Intensity("Strength"),
    Opacity("Opacity"),
    Feather("Feather"),
    Falloff("Falloff"),
    ColourFrom("From"),
    ColourTo("To"),
    BrushSize("Brush size");

    companion object {
        /** What [layer] offers, plus brush size when the brush is what's in hand. */
        fun forLayer(layer: Layer, tool: SelectionTool): List<LayerControl> = buildList {
            when (layer) {
                // Declaration order is the chip order, and taking the list straight from the enum
                // means a band added to ToneAdjustments can't be left without a control.
                is Layer.Tone -> addAll(entries.filter { it.band != null })
                is Layer.Blur -> add(Blur)
                is Layer.Gradient -> { add(ColourFrom); add(ColourTo); add(Falloff) }
                // A curve's control is the graph itself, not a slider.
                is Layer.Curve -> Unit
                is Layer.Look -> add(Intensity)
            }
            add(Opacity)
            // Feathering an area that doesn't exist is a slider that does nothing.
            if (!layer.mask.isEmpty) add(Feather)
            // Falloff belongs to a gradient, so it only appears when there is one to shape. A
            // gradient layer has its own and offered it above.
            if (layer.mask.gradient != null && layer !is Layer.Gradient) add(Falloff)
            if (tool == SelectionTool.Brush) add(BrushSize)
        }
    }
}

data class PerfectEditUiState(
    val geometry: ImageGeometry = ImageGeometry(),
    val panel: EditorPanel = EditorPanel.Closed,
    /** Geometry plus the layer stack applied — what's displayed. */
    val canvas: Bitmap? = null,
    val document: Document = Document(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val control: LayerControl = LayerControl.ToneShadows,
    val selectionTool: SelectionTool = SelectionTool.Lasso,
    val selectionMode: SelectionMode = SelectionMode.Replace,
    /** The shape the next gradient will be drawn in. */
    val gradientStyle: GradientStyle = GradientStyle.Linear,
    /** Which channel the curve editor is drawing on. */
    val curveChannel: CurveChannel = CurveChannel.Rgb,
    /**
     * An area drawn before any effect was chosen. The next effect added takes it as its mask, which
     * is the draw-then-adjust order selections are normally used in.
     */
    val pendingSelection: Mask? = null,
    /** Brush radius as a fraction of the image's shorter edge. */
    val brushRadius: Float = 0.12f,
    val isSaving: Boolean = false,
    val ready: Boolean = false,
    val notice: String? = null,
    val savedMessage: String? = null,
) {
    /** Width/height of the canvas the crop is expressed against. */
    val canvasRatio: Float
        get() = canvas?.let { if (it.height > 0) it.width.toFloat() / it.height else 1f } ?: 1f

    /**
     * Areas can be drawn whenever effects are showing, with or without a layer selected — drawing
     * first and choosing the effect after is the whole point of a pending selection.
     */
    val canSelect: Boolean get() = panel == EditorPanel.Effects

    /**
     * A crop that has been set but not saved is invisible once its controls are put away, since the
     * crop is applied at export rather than baked into the preview. Showing it read-only stops that
     * being a surprise at save time; an untouched photo shows nothing at all.
     */
    val showsCropPreview: Boolean
        get() = (panel == EditorPanel.Closed || panel == EditorPanel.Menu) && !geometry.crop.isFull

    /** The area currently being edited: the selected layer's, or the one drawn ahead of a layer. */
    val activeMask: Mask?
        get() = document.selected?.mask?.takeUnless { it.isEmpty } ?: pendingSelection
}

/**
 * Backs the Perfect Editor. The edit is held as a declarative [ImageGeometry] plus a layer
 * [Document] and only ever rendered — the source bitmap is never mutated, and saving writes a
 * brand-new photo.
 */
class PerfectEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as PicturePerfectApp).photoRepository

    private var sourceFull: Bitmap? = null
    private var sourcePreview: Bitmap? = null

    // Geometry applied but not the layer stack, so a slider drag re-renders colour without redoing
    // the rotate/crop work each frame.
    private var orientedPreview: Bitmap? = null
    private var renderJob: Job? = null

    // What the live area looked like when a gradient drag began; see onGradientStart.
    private var gradientBase: Mask? = null

    /**
     * The look catalog, shared with the chooser so both name the same thing.
     *
     * All hundred have shipped in the APK since the camera got them, and the layer renderer has
     * always known how to apply one — nothing ever built a layer that used them.
     */
    val filters: List<Filter> by lazy { FilterCatalog.load(getApplication()) }

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
            it.copy(
                document = Document(),
                pendingSelection = null,
                canUndo = false,
                canRedo = false,
            )
        }
        applyGeometry(ImageGeometry())
    }

    /**
     * The edit button. It always means "put everything away and show me the photo" — except when
     * there is nothing to put away, where it opens the menu instead.
     */
    fun onToggleMenu() = _state.update {
        it.copy(panel = if (it.panel == EditorPanel.Closed) EditorPanel.Menu else EditorPanel.Closed)
    }

    fun onOpenPanel(panel: EditorPanel) = _state.update { it.copy(panel = panel) }

    /** Steps a tool back to the menu, so switching tools doesn't mean collapsing first. */
    fun onBackToMenu() = _state.update { it.copy(panel = EditorPanel.Menu) }

    // ---- Rendering ------------------------------------------------------------------------------

    /**
     * Re-renders the layer stack from the already-oriented preview. Debounced, so dragging a slider
     * or painting a stroke doesn't queue a GPU pass per pixel of travel.
     */
    private fun schedulePreview() {
        val source = orientedPreview ?: return
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            delay(60)
            val document = _state.value.document
            val rendered = withContext(Dispatchers.Default) {
                runCatching { LayerRenderer.render(getApplication(), source, document) }
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
                runCatching { LayerRenderer.render(getApplication(), oriented, _state.value.document) }
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
            val document = _state.value.document
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // Geometry, then the stack — the same order the preview composites in, which is
                    // what keeps the saved photo matching what was on screen.
                    val cropped = ImageTransformer.apply(source, geometry)
                    val out = LayerRenderer.render(getApplication(), cropped, document)
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
     * Adds an effect and selects it, taking over any area drawn beforehand — lasso the sky, add
     * Tone, and only the sky changes.
     */
    fun onAddEffect(kind: EffectKind) {
        val state = _state.value
        // No pending selection means no mask, which renders as "applies to the whole photo".
        val mask = state.pendingSelection ?: Mask()
        // Numbered, so two layers of the same kind can be told apart in the row.
        val ordinal = state.document.layers.size + 1
        val document = when (kind) {
            // Not neutral: a fresh layer must visibly do something, or adding it looks like a no-op.
            EffectKind.Tone -> state.document.add {
                Layer.Tone(
                    id = it,
                    name = "$ordinal · ${kind.label}",
                    adjustments = ToneAdjustments(shadows = 25),
                    mask = mask,
                )
            }

            // Not Original: that one is a passthrough with no lookup table, so a layer using it
            // would render nothing and read as broken on arrival.
            EffectKind.Look -> state.document.add {
                Layer.Look(
                    id = it,
                    name = "$ordinal · ${kind.label}",
                    filterId = filters.firstOrNull { filter -> !filter.isOriginal }?.id
                        ?: Filter.ORIGINAL_ID,
                    mask = mask,
                )
            }

            EffectKind.Curve -> state.document.add {
                Layer.Curve(
                    id = it,
                    name = "$ordinal · ${kind.label}",
                    // A gentle S: more contrast, and a visible starting shape to bend from rather
                    // than a straight line that looks like nothing happened.
                    spec = CurveSpec(
                        rgb = listOf(
                            CurvePoint(0f, 0f),
                            CurvePoint(0.25f, 0.19f),
                            CurvePoint(0.75f, 0.81f),
                            CurvePoint(1f, 1f),
                        ),
                    ),
                    mask = mask,
                )
            }

            EffectKind.Gradient -> state.document.add {
                Layer.Gradient(
                    id = it,
                    name = "$ordinal · ${kind.label}",
                    // Top-down and fading out: a sunset wash, the commonest thing this is for, and
                    // visible the moment it is added rather than needing to be placed first.
                    spec = GradientSpec(start = MaskPoint(0.5f, 0f), end = MaskPoint(0.5f, 0.7f)),
                    blend = BlendMode.Multiply,
                    mask = mask,
                )
            }
        }
        val control = when (kind) {
            EffectKind.Gradient -> LayerControl.ColourFrom
            EffectKind.Look -> LayerControl.Intensity
            EffectKind.Curve -> LayerControl.Opacity
            EffectKind.Tone -> LayerControl.ToneShadows
        }
        _state.update { it.copy(pendingSelection = null, control = control) }
        commit(document)
    }

    /** Tapping the selected layer deselects it, which is how you get back to drawing a fresh area. */
    fun onSelectLayer(id: Long) {
        val document = _state.value.document
        val next = if (document.selectedId == id) document.select(null) else document.select(id)
        // A pending area belongs to "nothing selected yet"; carrying it past a selection would make
        // it reappear on some later effect out of nowhere.
        _state.update { it.copy(pendingSelection = null) }
        applyDocument(next, record = false)
    }

    fun onToggleLayerVisibility(id: Long) = commit(_state.value.document.toggleVisibility(id))

    fun onRemoveLayer(id: Long) = commit(_state.value.document.remove(id))

    fun onMoveLayer(id: Long, up: Boolean) = commit(_state.value.document.move(id, up))

    fun onLayerOpacity(id: Long, opacity: Float) =
        applyDocument(_state.value.document.setOpacity(id, opacity), record = false)

    /** Cycles to the next blend mode, so the choice needs no menu to open over the photo. */
    fun onCycleBlend(id: Long) {
        val layer = _state.value.document.layers.firstOrNull { it.id == id } ?: return
        val modes = BlendMode.entries
        val next = modes[(modes.indexOf(layer.blend) + 1) % modes.size]
        commit(_state.value.document.setBlend(id, next))
    }

    /**
     * Edits what a layer actually *does*, as opposed to how it's composited.
     *
     * `withCommon` deliberately can't reach a layer's payload, so these go through
     * [Document.update] directly. Without them a Tone layer stays neutral for its whole life and
     * quietly renders nothing — which also makes the mask tools look broken, since masking a no-op
     * layer changes nothing on screen.
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

    fun onSelectCurveChannel(channel: CurveChannel) =
        _state.update { it.copy(curveChannel = channel) }

    /** Drags one control point. The whole drag is one undo step, like a brush stroke. */
    fun onMoveCurvePoint(id: Long, index: Int, to: CurvePoint) =
        updateCurve(id, record = false) { Curves.move(it, index, to) }

    fun onAddCurvePoint(id: Long, at: CurvePoint) =
        updateCurve(id, record = true) { Curves.add(it, at) }

    fun onRemoveCurvePoint(id: Long, index: Int) =
        updateCurve(id, record = true) { Curves.remove(it, index) }

    private fun updateCurve(
        id: Long,
        record: Boolean,
        transform: (List<CurvePoint>) -> List<CurvePoint>,
    ) {
        val channel = _state.value.curveChannel
        val document = _state.value.document.update(id) { layer ->
            if (layer is Layer.Curve) {
                layer.copy(spec = layer.spec.with(channel, transform(layer.spec.channel(channel))))
            } else {
                layer
            }
        }
        if (record) commit(document) else applyDocument(document, record = false)
    }

    /** Swaps inside for outside, so lassoing a subject can adjust everything except it. */
    fun onInvertMask() {
        val state = _state.value
        val current = state.activeMask ?: return
        applySelection(state, state.document.selected, current.copy(inverted = !current.inverted), record = true)
    }

    /**
     * Drops the area, putting the layer back to covering the whole photo.
     *
     * The only way out of an area other than undo, which is no help once you've made other edits
     * since. On a layer it's an empty mask; before one, the pending selection simply goes.
     */
    fun onClearMask() {
        val state = _state.value
        val layer = state.document.selected
        if (layer == null) {
            _state.update { it.copy(pendingSelection = null) }
            return
        }
        commit(state.document.setMask(layer.id, Mask()))
    }

    fun onLayerFilter(id: Long, filterId: String) {
        val document = _state.value.document.update(id) { layer ->
            if (layer is Layer.Look) layer.copy(filterId = filterId) else layer
        }
        commit(document)
    }

    fun onLayerIntensity(id: Long, intensity: Int) {
        val document = _state.value.document.update(id) { layer ->
            if (layer is Layer.Look) layer.copy(intensity = intensity.coerceIn(0, 100)) else layer
        }
        applyDocument(document, record = false)
    }

    /** The hue at one end of a gradient layer's wash. */
    fun onGradientHue(id: Long, atStart: Boolean, hue: Float) = updateGradientLayer(id) { layer ->
        val colour = (if (atStart) layer.from else layer.to).copy(hue = hue.coerceIn(0f, 360f))
        if (atStart) layer.copy(from = colour) else layer.copy(to = colour)
    }

    /** Black, white, clear or a colour, at one end of a gradient layer's wash. */
    fun onGradientTone(id: Long, atStart: Boolean, tone: ColourTone) = updateGradientLayer(id) { layer ->
        val colour = (if (atStart) layer.from else layer.to).copy(tone = tone)
        if (atStart) layer.copy(from = colour) else layer.copy(to = colour)
    }

    /** How a gradient layer's own wash is placed, as opposed to the area any layer applies through. */
    fun onGradientLayerSpec(id: Long, transform: (GradientSpec) -> GradientSpec) =
        updateGradientLayer(id) { it.copy(spec = transform(it.spec)) }

    private fun updateGradientLayer(id: Long, transform: (Layer.Gradient) -> Layer.Gradient) {
        val document = _state.value.document.update(id) { layer ->
            if (layer is Layer.Gradient) transform(layer) else layer
        }
        applyDocument(document, record = false)
    }

    /** Which single property the panel's slider is editing. */
    fun onSelectControl(control: LayerControl) = _state.update { it.copy(control = control) }

    /** Slider drags don't each deserve an undo step; the gesture ending pushes one. */
    fun commitLayerEdit() = commit(_state.value.document)

    // ---- Selections -----------------------------------------------------------------------------

    fun onSelectionTool(tool: SelectionTool) = _state.update { it.copy(selectionTool = tool) }

    fun onCycleSelectionMode() = _state.update {
        val modes = SelectionMode.entries
        it.copy(selectionMode = modes[(modes.indexOf(it.selectionMode) + 1) % modes.size])
    }

    fun onBrushRadius(radius: Float) = _state.update { it.copy(brushRadius = radius.coerceIn(0.02f, 0.5f)) }

    /**
     * How softly the effect stops at the area's edge. Routes to whichever area is live — the
     * selected layer's, or one drawn before an effect was chosen — the same way an edit does.
     */
    fun onFeather(value: Float) {
        val state = _state.value
        val layer = state.document.selected
        val current = if (layer != null) layer.mask.takeUnless { it.isEmpty } else state.pendingSelection
        val feathered = (current ?: return).copy(feather = value.coerceIn(0f, 1f))
        applySelection(state, layer, feathered, record = false)
    }

    fun onSelectGradientStyle(style: GradientStyle) {
        _state.update { it.copy(gradientStyle = style) }
        // Restyling an existing gradient in place is the point of the chips; redrawing to change
        // shape would throw away a placement that was probably already right.
        _state.value.activeMask?.gradient?.let { current ->
            if (current.style != style) placeGradient(current.copy(style = style), record = true)
        }
    }

    /**
     * Marks the start of a gradient drag, remembering what the area looked like beforehand.
     *
     * Every frame of the drag combines against *this*, not against the frame before it. Adding a
     * gradient onto the running result instead would ratchet coverage upwards until the whole photo
     * was selected, because a gradient covers the entire frame by definition.
     */
    fun onGradientStart() {
        val state = _state.value
        gradientBase = if (state.document.selected != null) {
            state.document.selected?.mask?.takeUnless { it.isEmpty }
        } else {
            state.pendingSelection
        }
    }

    /** A gradient dragged from [start] to [end], both normalized. */
    fun onGradientDrawn(start: MaskPoint, end: MaskPoint) {
        val state = _state.value
        val layer = state.document.selected
        val base = gradientBase
        val spec = GradientSpec(
            style = state.gradientStyle,
            start = start,
            end = end,
            // Re-dragging keeps the falloff already dialled in.
            midpoint = base?.gradient?.midpoint ?: 0.5f,
        )
        val mode = state.selectionMode
        val onto = if (mode == SelectionMode.Replace || base == null) {
            Mask.forRatio(state.canvasRatio).copy(feather = base?.feather ?: Mask.SELECTION_FEATHER)
        } else {
            base
        }
        applySelection(state, layer, MaskGradient.fill(onto, spec, mode), record = false)
    }

    /** The falloff slider: where along the run the gradient reaches halfway. */
    fun onFalloff(value: Float) {
        val current = _state.value.activeMask?.gradient ?: return
        placeGradient(
            current.copy(midpoint = value.coerceIn(MaskGradient.MIN_MIDPOINT, MaskGradient.MAX_MIDPOINT)),
            record = false,
        )
    }

    /**
     * Re-places a gradient that *is* the whole area.
     *
     * Restyling and falloff are only offered when the mask kept its spec, which only happens on a
     * `Replace` — a gradient combined into something else leaves a shape it no longer describes, so
     * there's nothing to re-place. That's why starting from a fresh grid here is right rather than
     * lossy.
     */
    private fun placeGradient(spec: GradientSpec, record: Boolean) {
        val state = _state.value
        val layer = state.document.selected
        val current = if (layer != null) layer.mask.takeUnless { it.isEmpty } else state.pendingSelection
        val filled = MaskGradient.fill(
            mask = Mask.forRatio(state.canvasRatio)
                .copy(feather = current?.feather ?: Mask.SELECTION_FEATHER),
            spec = spec,
            mode = SelectionMode.Replace,
        )
        applySelection(state, layer, filled, record = record)
    }

    /** A finished lasso, as normalized points. One drawn shape is one undo step. */
    fun onLassoCommitted(path: List<MaskPoint>) {
        if (path.size < 3) return
        val state = _state.value
        val layer = state.document.selected
        val filled = MaskLasso.fill(
            mask = selectionBase(state, layer, state.selectionMode),
            path = path,
            mode = state.selectionMode,
        )
        applySelection(state, layer, filled, record = true)
    }

    /**
     * A brush dab at normalized ([x], [y]). Strokes aren't recorded step by step — that would bury
     * the history under hundreds of entries — so [endStroke] pushes the finished stroke instead.
     */
    fun onPaintMask(x: Float, y: Float) {
        val state = _state.value
        val layer = state.document.selected
        // The brush only ever adds or removes; "New" is a lasso idea, so it paints.
        val mode = if (state.selectionMode == SelectionMode.Subtract) {
            SelectionMode.Subtract
        } else {
            SelectionMode.Add
        }
        val painted = MaskBrush.paint(
            mask = selectionBase(state, layer, mode),
            x = x,
            y = y,
            radius = state.brushRadius,
            erase = mode == SelectionMode.Subtract,
        )
        applySelection(state, layer, painted, record = false)
    }

    /**
     * Drags one point of a lasso and refills the area from the whole polygon.
     *
     * The moved path is written back verbatim rather than letting the fill re-simplify it — a point
     * dragged into line with its neighbours would otherwise be dropped mid-drag, taking the handle
     * out from under the finger holding it.
     */
    fun onMoveHandle(index: Int, point: MaskPoint) {
        val current = _state.value.activeMask ?: return
        val gradient = current.gradient
        if (gradient != null) {
            // Two handles: the strong end and the far end. Moving either rotates and stretches it.
            placeGradient(
                if (index == 0) gradient.copy(start = point) else gradient.copy(end = point),
                record = false,
            )
            return
        }
        onMovePathPoint(index, point)
    }

    private fun onMovePathPoint(index: Int, point: MaskPoint) {
        val state = _state.value
        val layer = state.document.selected
        val current = if (layer != null) layer.mask.takeUnless { it.isEmpty } else state.pendingSelection
        val path = current?.path ?: return
        if (index !in path.indices) return

        val moved = path.toMutableList().also { it[index] = point }
        val refilled = MaskLasso.fill(
            mask = Mask.forRatio(state.canvasRatio).copy(feather = current.feather),
            path = moved,
            mode = SelectionMode.Replace,
        )
        applySelection(state, layer, refilled.copy(path = moved), record = false)
    }

    fun endStroke() = commit(_state.value.document)

    /**
     * The mask a selection edit starts from.
     *
     * The case worth spelling out is an unmasked layer. An empty mask renders as "covers
     * everything", so subtracting from one has to start at full coverage and cut a hole — starting
     * from nothing would silently wipe the effect out instead of trimming it.
     */
    private fun selectionBase(state: PerfectEditUiState, layer: Layer?, mode: SelectionMode): Mask {
        val current = if (layer != null) layer.mask.takeUnless { it.isEmpty } else state.pendingSelection
        return when {
            mode == SelectionMode.Replace -> Mask.forRatio(state.canvasRatio)
            current != null -> current
            mode == SelectionMode.Subtract && layer != null ->
                Mask.forRatio(state.canvasRatio, covered = true)
            else -> Mask.forRatio(state.canvasRatio)
        }
    }

    /** Writes an edited area to the selected layer, or holds it for the next effect added. */
    private fun applySelection(
        state: PerfectEditUiState,
        layer: Layer?,
        mask: Mask,
        record: Boolean,
    ) {
        if (layer == null) {
            _state.update { it.copy(pendingSelection = mask) }
            return
        }
        val document = state.document.setMask(layer.id, mask)
        if (record) commit(document) else applyDocument(document, record = false)
    }

    // ---- History --------------------------------------------------------------------------------

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
        renderJob?.cancel()
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
