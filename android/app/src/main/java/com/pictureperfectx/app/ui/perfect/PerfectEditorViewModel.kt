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
import com.pictureperfectx.app.data.EditStore
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
import com.pictureperfectx.app.layers.EditDocument
import com.pictureperfectx.app.layers.GradientSpec
import com.pictureperfectx.app.layers.GradientStyle
import com.pictureperfectx.app.layers.Heal
import com.pictureperfectx.app.layers.HealDab
import com.pictureperfectx.app.layers.History
import com.pictureperfectx.app.layers.Layer
import com.pictureperfectx.app.layers.LayerRenderer
import com.pictureperfectx.app.layers.Mask
import com.pictureperfectx.app.layers.MaskBrush
import com.pictureperfectx.app.layers.MaskGradient
import com.pictureperfectx.app.layers.MaskLasso
import com.pictureperfectx.app.layers.MaskPoint
import com.pictureperfectx.app.layers.MaskWand
import com.pictureperfectx.app.layers.SelectionMode
import com.pictureperfectx.app.layers.ShapeKind
import com.pictureperfectx.app.layers.TextFont
import kotlin.math.abs
import kotlin.math.roundToInt
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
    Text("Text", "Words on the photo."),
    Shape("Shape", "A rectangle, ellipse or line."),
    Smooth("Smooth", "Soften skin without losing its edges."),
    Heal("Heal", "Tap a blemish to cover it with skin from nearby."),
    Whiten("Whiten", "Brush over teeth or eyes to lift them."),
    Brighten("Brighten", "Brush under the eyes to lift the shadows."),
    Gradient("Gradient", "A wash of colour across the photo."),
    Fill("Fill", "A flat colour inside your selection."),
}

/**
 * How an area is chosen: drawn round, painted in by hand, or faded across the frame.
 *
 * [Fade] rather than "Gradient": it shapes *where* an effect applies, while the Gradient effect is a
 * wash of colour. Two things called the same thing, one a tool and one a layer, read as one thing
 * that doesn't work.
 */
enum class SelectionTool(val label: String) {
    Lasso("Lasso"),
    Brush("Brush"),
    Fade("Fade"),
    Wand("Wand"),
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
    TextSize("Size"),
    TextRotation("Rotate"),
    TextColour("Colour"),
    ShapeStroke("Outline"),
    SmoothAmount("Amount"),
    HealSize("Spot size"),
    BrushSize("Brush size"),
    WandTolerance("Tolerance"),

    // These five are controls that aren't a slider. They are chips in the same row as everything
    // else, and what they open replaces the slider rather than stacking another row beneath it —
    // which is what used to put six rows under the photo for a text layer.
    TextContent("Words"),
    TextTypeface("Font"),
    ShapeKindPick("Shape"),
    LookPick("Filter"),
    CurveGraph("Curve");

    companion object {
        /** What [layer] offers, plus brush size when the brush is what's in hand. */
        fun forLayer(layer: Layer, tool: SelectionTool): List<LayerControl> = buildList {
            when (layer) {
                // Declaration order is the chip order, and taking the list straight from the enum
                // means a band added to ToneAdjustments can't be left without a control.
                is Layer.Tone -> addAll(entries.filter { it.band != null })
                is Layer.Blur -> add(Blur)
                is Layer.Gradient -> {
                    add(ColourFrom)
                    // A fill has one colour, so a second control for it would be a duplicate — and
                    // the shape of the ramp means nothing when both ends match.
                    if (!layer.solid) { add(ColourTo); add(Falloff) }
                }
                // A curve's control is the graph itself, not a slider.
                is Layer.Curve -> add(CurveGraph)
                // Words first: it is the thing you came to change on a layer that is words.
                is Layer.Text -> {
                    add(TextContent); add(TextSize); add(TextRotation)
                    add(TextColour); add(TextTypeface)
                }
                is Layer.Shape -> {
                    add(ShapeKindPick); add(ShapeStroke); add(TextRotation); add(TextColour)
                }
                is Layer.Smooth -> add(SmoothAmount)
                is Layer.Heal -> add(HealSize)
                is Layer.Look -> { add(LookPick); add(Intensity) }
            }
            add(Opacity)
            // Feathering an area that doesn't exist is a slider that does nothing.
            if (!layer.mask.isEmpty) add(Feather)
            // Falloff belongs to a gradient, so it only appears when there is one to shape. A
            // gradient layer has its own and offered it above.
            if (layer.mask.gradient != null && layer !is Layer.Gradient) add(Falloff)
            if (tool == SelectionTool.Brush) add(BrushSize)
            if (tool == SelectionTool.Wand) add(WandTolerance)
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
    /** How alike a colour has to be for the wand to take it. */
    val wandTolerance: Float = MaskWand.DEFAULT_TOLERANCE,
    /** Whether the wand takes only the shape it was tapped on, or every colour like it. */
    val wandContiguous: Boolean = true,
    /** Heal spot radius, likewise. Much smaller: a blemish is not a brush stroke. */
    val healRadius: Float = 0.02f,
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
 * The draggable points on the photo, in the order the canvas draws them.
 *
 * One function rather than two, because a handle is dragged by *index*: the screen decides where
 * they are drawn and the view model decides what a drag means, and if the two ever disagreed about
 * the order, dragging one handle would move a different one. Kept out of the view model class so a
 * unit test can hold it to that order without an Android runtime.
 *
 * What they are: a text or shape layer's own placement, a gradient area's two ends, or the points a
 * lasso kept. A brushed area has none, since no shape describes it — but a lassoed one keeps its
 * points whichever tool happens to be in hand afterwards.
 */
fun editHandles(state: PerfectEditUiState): List<MaskPoint> {
    if (!state.canSelect) return emptyList()
    return when (val selected = state.document.selected) {
        is Layer.Text -> listOf(selected.centre)
        is Layer.Shape -> listOf(selected.centre, selected.corner)
        // A gradient layer has two shapes worth dragging, and they mean different things: the area
        // says where the wash lands, its own ramp says which way the colour runs inside it. The
        // ramp goes last so an area's handles keep the indices they have for every other layer —
        // and a flat fill has no ramp to aim, so it gets none.
        is Layer.Gradient -> if (selected.solid) {
            areaHandles(state)
        } else {
            areaHandles(state) + listOf(selected.spec.start, selected.spec.end)
        }
        else -> areaHandles(state)
    }
}

/** The handles belonging to the area itself: a fade's two ends, or the points a lasso kept. */
internal fun areaHandles(state: PerfectEditUiState): List<MaskPoint> {
    val mask = state.activeMask ?: return emptyList()
    mask.gradient?.let { return listOf(it.start, it.end) }
    return mask.path.orEmpty()
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

    // The gallery row the editor was opened on, if the photo is one of ours. Saving reads it to
    // point the new photo back at the same original rather than at this one.
    private var openedPhoto: PhotoEntity? = null
    private var openedUri: Uri? = null

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

    /**
     * Opens [uri] for editing, with its layers if it has any.
     *
     * A photo the editor produced is reopened *as the edit that made it*: the original comes back
     * along with the stack, so every layer is still live and every revision starts from pixels that
     * have only been through JPEG once. Without a stack — or with one whose original has since been
     * deleted — it opens as the flat photo it is.
     */
    fun load(uri: Uri) {
        viewModelScope.launch {
            openedUri = uri
            val photo = runCatching { repository.findByUri(uri.toString()) }.getOrNull()
            openedPhoto = photo

            val stack = photo?.takeIf { it.isEdited }?.let { edited ->
                withContext(Dispatchers.IO) { EditStore.read(getApplication(), edited.editUri) }
            }
            val openUri = if (stack != null) Uri.parse(photo!!.sourceUri) else uri

            var loaded = withContext(Dispatchers.IO) {
                BitmapIO.loadForEdit(getApplication(), openUri, FULL_MAX_EDGE)
            }
            // The original is the user's own file and they may have deleted it since. Falling back
            // to the flat photo is better than an editor that won't open.
            val lostOriginal = stack != null && loaded == null
            if (lostOriginal) {
                loaded = withContext(Dispatchers.IO) {
                    BitmapIO.loadForEdit(getApplication(), uri, FULL_MAX_EDGE)
                }
            }
            val restored = stack.takeUnless { lostOriginal }

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
                    geometry = restored?.geometry ?: ImageGeometry(),
                    document = restored?.document ?: Document(),
                    canvas = preview,
                    ready = full != null,
                    notice = when {
                        loaded == null -> "This photo couldn't be opened for editing."
                        lostOriginal ->
                            "The photo this was edited from is gone, so you're editing the saved " +
                                "version and its layers couldn't be reopened."
                        restored != null -> restored.document.layers.size.let { count ->
                            "Reopened with $count ${if (count == 1) "layer" else "layers"}."
                        }
                        loaded.degraded ->
                            "This device can't decode the raw file, so you're editing its embedded " +
                                "preview — the saved photo will be lower resolution than the original."
                        else -> null
                    },
                )
            }
            // A restored stack has to be composited before it is visible; a fresh photo is already
            // exactly what is on screen.
            if (restored != null && full != null) renderRestored(restored.geometry)
        }
    }

    /**
     * Composites a reopened edit onto its preview.
     *
     * Deliberately not [applyGeometry], which re-fits the crop to whatever aspect is locked — that
     * is right when the user has just changed the framing and wrong here, where the crop being
     * restored is the one they already chose.
     */
    private fun renderRestored(geometry: ImageGeometry) {
        val source = sourcePreview ?: return
        viewModelScope.launch {
            val oriented = withContext(Dispatchers.Default) {
                runCatching { ImageTransformer.orient(source, geometry) }.getOrNull()
            } ?: return@launch
            orientedPreview = oriented
            val canvas = withContext(Dispatchers.Default) {
                runCatching { LayerRenderer.render(getApplication(), oriented, _state.value.document) }
                    .getOrDefault(oriented)
            }
            _state.update { it.copy(canvas = canvas) }
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
                    // Orient, composite, *then* crop — the same order the preview uses, and the only
                    // order that can be right. A layer's mask means "this fraction of the frame", so
                    // cropping before compositing changes what the frame is and lands every mask,
                    // text placement and gradient somewhere the preview never showed. This used to
                    // crop first, and the saved photo silently disagreed with the screen.
                    val oriented = ImageTransformer.orient(source, geometry)
                    val rendered = LayerRenderer.render(getApplication(), oriented, document)
                    if (rendered !== oriented && oriented !== source && !oriented.isRecycled) {
                        oriented.recycle()
                    }
                    val out = ImageTransformer.crop(rendered, geometry)
                    if (out !== rendered && !rendered.isRecycled) rendered.recycle()
                    val saved = PhotoSaver.save(getApplication(), out)
                    // The stack goes beside the photo, so this edit can be reopened and revised
                    // rather than being the last thing that will ever happen to it.
                    val editUri = EditStore.write(
                        context = getApplication(),
                        baseName = saved.displayName.substringBeforeLast('.'),
                        edit = EditDocument(geometry = geometry, document = document),
                    )
                    repository.record(
                        PhotoEntity(
                            uri = saved.uri.toString(),
                            displayName = saved.displayName,
                            filterId = "original",
                            filterName = "Perfect Editor",
                            lensFacing = "edit",
                            width = saved.width,
                            height = saved.height,
                            // Always the original, never this export: revising an edit re-points at
                            // the same source, so the chain stays one link long and every revision
                            // starts from pixels that have been through JPEG once.
                            sourceUri = editUri?.let {
                                openedPhoto?.sourceUri ?: openedUri?.toString()
                            },
                            editUri = editUri,
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

            EffectKind.Text -> state.document.add {
                Layer.Text(id = it, name = "$ordinal · ${kind.label}", mask = mask)
            }

            EffectKind.Shape -> state.document.add {
                Layer.Shape(id = it, name = "$ordinal · ${kind.label}", mask = mask)
            }

            EffectKind.Smooth -> state.document.add {
                Layer.Smooth(id = it, name = "$ordinal · ${kind.label}", mask = mask)
            }

            EffectKind.Heal -> state.document.add {
                Layer.Heal(id = it, name = "$ordinal · ${kind.label}", mask = mask)
            }

            // Presets, honestly: teeth and under-eyes are a saturation and a lift through a small
            // brushed area, which the adjustment set already does. What they add is knowing which
            // way to move the sliders.
            EffectKind.Whiten -> state.document.add {
                Layer.Tone(
                    id = it,
                    name = "$ordinal · ${kind.label}",
                    adjustments = ToneAdjustments(saturation = -45, exposure = 14),
                    mask = mask,
                )
            }

            EffectKind.Brighten -> state.document.add {
                Layer.Tone(
                    id = it,
                    name = "$ordinal · ${kind.label}",
                    adjustments = ToneAdjustments(shadows = 32, exposure = 8),
                    mask = mask,
                )
            }

            // A gradient with both ends locked together. Normal rather than Multiply, which is
            // right for a wash and wrong for a fill — over a solid colour it darkens the photo
            // instead of covering it.
            EffectKind.Fill -> state.document.add {
                Layer.Gradient(
                    id = it,
                    name = "$ordinal · ${kind.label}",
                    blend = BlendMode.Normal,
                    solid = true,
                    mask = mask,
                )
            }

            EffectKind.Gradient -> state.document.add {
                Layer.Gradient(
                    id = it,
                    name = "$ordinal · ${kind.label}",
                    // Down the middle of whatever area was drawn, so a gradient added after a lasso
                    // ramps across the selection instead of running off the edge of it. With no
                    // area: top-down and fading out, a sunset wash, the commonest thing this is for.
                    spec = mask.coveredBounds()?.let { bounds ->
                        GradientSpec(
                            start = MaskPoint(bounds.centreX, bounds.top),
                            end = MaskPoint(bounds.centreX, bounds.bottom),
                        )
                    } ?: GradientSpec(start = MaskPoint(0.5f, 0f), end = MaskPoint(0.5f, 0.7f)),
                    // Normal, not Multiply: a wash has to be visibly there the moment it arrives,
                    // and Multiply is one chip away for anyone who wants it shading instead.
                    blend = BlendMode.Normal,
                    mask = mask,
                )
            }
        }
        val control = when (kind) {
            EffectKind.Gradient -> LayerControl.ColourFrom
            EffectKind.Look -> LayerControl.LookPick
            EffectKind.Curve -> LayerControl.CurveGraph
            EffectKind.Text -> LayerControl.TextContent
            EffectKind.Shape -> LayerControl.ShapeKindPick
            EffectKind.Smooth -> LayerControl.SmoothAmount
            EffectKind.Heal -> LayerControl.HealSize
            EffectKind.Whiten, EffectKind.Brighten -> LayerControl.ToneExposure
            EffectKind.Fill -> LayerControl.ColourFrom
            EffectKind.Tone -> LayerControl.ToneShadows
        }
        // The retouching presets are meant to be brushed onto a small area, so hand over the brush
        // rather than leaving them applied to the whole face — but only when no area was drawn
        // first. Someone who lassoed the area already said where it goes, and switching tools under
        // them would put their lasso away mid-edit.
        val tool = when {
            !mask.isEmpty -> state.selectionTool
            kind == EffectKind.Whiten || kind == EffectKind.Brighten || kind == EffectKind.Smooth ->
                SelectionTool.Brush
            else -> state.selectionTool
        }
        _state.update { it.copy(pendingSelection = null, control = control, selectionTool = tool) }
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

    /** Copies a layer, mask and all — a second effect through an area already drawn once. */
    fun onDuplicateLayer(id: Long) = commit(_state.value.document.duplicate(id))

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

    fun onTextContent(id: Long, content: String) =
        updateText(id, record = false) { it.copy(content = content) }

    fun onTextSize(id: Long, size: Float) =
        updateText(id, record = false) { it.copy(size = size.coerceIn(0.02f, 0.5f)) }

    fun onTextRotation(id: Long, degrees: Float) =
        updateText(id, record = false) { it.copy(rotation = degrees.coerceIn(-180f, 180f)) }

    fun onTextHue(id: Long, hue: Float) =
        updateText(id, record = false) { it.copy(colour = it.colour.copy(hue = hue.coerceIn(0f, 360f))) }

    fun onTextTone(id: Long, tone: ColourTone) =
        updateText(id, record = true) { it.copy(colour = it.colour.copy(tone = tone)) }

    fun onTextFont(id: Long, font: TextFont) = updateText(id, record = true) { it.copy(font = font) }

    private fun updateText(id: Long, record: Boolean, transform: (Layer.Text) -> Layer.Text) {
        val document = _state.value.document.update(id) { layer ->
            if (layer is Layer.Text) transform(layer) else layer
        }
        if (record) commit(document) else applyDocument(document, record = false)
    }

    fun onSmoothAmount(id: Long, amount: Int) {
        val document = _state.value.document.update(id) { layer ->
            if (layer is Layer.Smooth) layer.copy(amount = amount.coerceIn(0, 100)) else layer
        }
        applyDocument(document, record = false)
    }

    fun onHealRadius(radius: Float) =
        _state.update { it.copy(healRadius = radius.coerceIn(0.005f, 0.08f)) }

    /**
     * Places one heal dab where the user tapped.
     *
     * The clean skin to borrow from is chosen **here**, once, and stored on the dab — not searched
     * for again at render time, where a different resolution could land on a different patch and
     * make the export disagree with the preview.
     *
     * It is chosen from the oriented preview rather than the composite, so reordering or hiding
     * another layer later can't quietly change which skin a finished repair used.
     */
    fun onHealAt(x: Float, y: Float) {
        val state = _state.value
        val layer = state.document.selected as? Layer.Heal ?: return
        val source = orientedPreview ?: return
        val radius = state.healRadius

        viewModelScope.launch {
            val dab = withContext(Dispatchers.Default) {
                runCatching {
                    val width = source.width
                    val height = source.height
                    val pixels = IntArray(width * height)
                    source.getPixels(pixels, 0, width, 0, 0, width, height)
                    val spot = (radius * minOf(width, height)).roundToInt()
                    Heal.chooseSource(
                        pixels = pixels,
                        width = width,
                        height = height,
                        x = (x * width).roundToInt(),
                        y = (y * height).roundToInt(),
                        radius = spot,
                    )?.let { (sourceX, sourceY) ->
                        HealDab(
                            centre = MaskPoint(x, y),
                            source = MaskPoint(sourceX.toFloat() / width, sourceY.toFloat() / height),
                            radius = radius,
                        )
                    }
                }.getOrNull()
            }

            if (dab == null) {
                _state.update {
                    it.copy(
                        notice = "No clean skin close enough to borrow from. Try a smaller spot " +
                            "size, or move in from the edge of the photo.",
                    )
                }
                return@launch
            }
            commit(
                _state.value.document.update(layer.id) { existing ->
                    if (existing is Layer.Heal) existing.copy(dabs = existing.dabs + dab) else existing
                },
            )
        }
    }

    fun onShapeKind(id: Long, kind: ShapeKind) = updateShape(id, record = true) { it.copy(kind = kind) }

    fun onShapeStroke(id: Long, stroke: Float) =
        updateShape(id, record = false) { it.copy(stroke = stroke.coerceIn(0f, 0.1f)) }

    fun onShapeRotation(id: Long, degrees: Float) =
        updateShape(id, record = false) { it.copy(rotation = degrees.coerceIn(-180f, 180f)) }

    fun onShapeHue(id: Long, hue: Float) =
        updateShape(id, record = false) { it.copy(colour = it.colour.copy(hue = hue.coerceIn(0f, 360f))) }

    fun onShapeTone(id: Long, tone: ColourTone) =
        updateShape(id, record = true) { it.copy(colour = it.colour.copy(tone = tone)) }

    private fun updateShape(id: Long, record: Boolean, transform: (Layer.Shape) -> Layer.Shape) {
        val document = _state.value.document.update(id) { layer ->
            if (layer is Layer.Shape) transform(layer) else layer
        }
        if (record) commit(document) else applyDocument(document, record = false)
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

    /**
     * The hue at one end of a gradient layer's wash — or at both, when it's a fill.
     *
     * Without that, the first thing anyone does to a fill breaks it: the colour slider edits one
     * end, and a flat orange silently becomes an orange-to-something ramp.
     */
    fun onGradientHue(id: Long, atStart: Boolean, hue: Float) = updateGradientLayer(id) { layer ->
        val colour = (if (atStart) layer.from else layer.to).copy(hue = hue.coerceIn(0f, 360f))
        when {
            layer.solid -> layer.copy(from = colour, to = colour)
            atStart -> layer.copy(from = colour)
            else -> layer.copy(to = colour)
        }
    }

    /** Black, white, clear or a colour, at one end of a gradient layer's wash. */
    fun onGradientTone(id: Long, atStart: Boolean, tone: ColourTone) = updateGradientLayer(id) { layer ->
        val colour = (if (atStart) layer.from else layer.to).copy(tone = tone)
        when {
            layer.solid -> layer.copy(from = colour, to = colour)
            atStart -> layer.copy(from = colour)
            else -> layer.copy(to = colour)
        }
    }

    /** Off is how a fill becomes a gradient: the far end is freed and gets its own control back. */
    fun onToggleGradientSolid(id: Long) = updateGradientLayer(id) { layer ->
        if (layer.solid) {
            // Leaving on the colour it already had would look like nothing happened, so the far end
            // fades out — the shape a gradient is usually wanted in.
            layer.copy(solid = false, to = layer.from.copy(tone = ColourTone.Clear))
        } else {
            layer.copy(solid = true, to = layer.from)
        }
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

    fun onSelectionMode(mode: SelectionMode) = _state.update { it.copy(selectionMode = mode) }

    fun onBrushRadius(radius: Float) = _state.update { it.copy(brushRadius = radius.coerceIn(0.02f, 0.5f)) }

    fun onWandTolerance(value: Float) = _state.update {
        it.copy(wandTolerance = value.coerceIn(MaskWand.MIN_TOLERANCE, MaskWand.MAX_TOLERANCE))
    }

    /** The difference between choosing this shape and choosing every colour like it. */
    fun onToggleWandContiguous() = _state.update { it.copy(wandContiguous = !it.wandContiguous) }

    /**
     * A tap with the wand: takes the area matching the colour underneath it.
     *
     * The photo is sampled down to the mask's own grid rather than read at full resolution — the
     * mask was never finer than that, and a 12MP flood fill would stall the tap it came from.
     *
     * Deliberately samples the **rendered** canvas, which is what is on screen: tapping a sky that
     * a previous layer has already turned orange should choose the orange the user can see.
     */
    fun onWandAt(x: Float, y: Float) {
        val state = _state.value
        val canvas = state.canvas ?: return
        val layer = state.document.selected
        val base = selectionBase(state, layer, state.selectionMode)
        val grid = sampleToGrid(canvas, base.columns, base.rows) ?: return
        val chosen = MaskWand.select(
            grid = grid,
            columns = base.columns,
            rows = base.rows,
            mask = base,
            x = x,
            y = y,
            tolerance = state.wandTolerance,
            contiguous = state.wandContiguous,
            mode = state.selectionMode,
        )
        applySelection(state, layer, chosen, record = true)
    }

    /** The canvas as one packed-ARGB value per mask cell. */
    private fun sampleToGrid(canvas: Bitmap, columns: Int, rows: Int): IntArray? = runCatching {
        if (columns <= 0 || rows <= 0) return null
        val scaled = Bitmap.createScaledBitmap(canvas, columns, rows, true)
        val pixels = IntArray(columns * rows)
        scaled.getPixels(pixels, 0, columns, 0, 0, columns, rows)
        if (scaled !== canvas && !scaled.isRecycled) scaled.recycle()
        pixels
    }.getOrNull()

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
        val filled = MaskLasso.trace(
            mask = selectionBase(state, layer, state.selectionMode),
            drawn = path,
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
     * Drags one point of a lasso and redraws the area from the whole shape.
     *
     * Deliberately [MaskLasso.shape] rather than a fresh trace: re-simplifying mid-drag would drop
     * a point dragged into line with its neighbours, taking the handle out from under the finger
     * holding it.
     */
    fun onMoveHandle(index: Int, point: MaskPoint) {
        // Content layers put their own handles on the canvas rather than the mask's.
        when (val selected = _state.value.document.selected) {
            is Layer.Text -> {
                updateText(selected.id, record = false) { it.copy(centre = point) }
                return
            }

            is Layer.Shape -> {
                updateShape(selected.id, record = false) {
                    // Handle 0 moves the box; handle 1 is the corner, which sizes it about the
                    // centre so the shape grows evenly rather than crawling across the photo.
                    if (index == 0) {
                        it.copy(centre = point)
                    } else {
                        it.copy(
                            width = (abs(point.x - it.centre.x) * 2f).coerceIn(0.01f, 2f),
                            height = (abs(point.y - it.centre.y) * 2f).coerceIn(0.01f, 2f),
                        )
                    }
                }
                return
            }

            is Layer.Gradient -> {
                // Its own ramp is appended after the area's handles by editHandles, so anything
                // past the end of that list is one of the two ends of the wash.
                val area = areaHandles(_state.value).size
                if (index >= area) {
                    val moved = point
                    onGradientLayerSpec(selected.id) {
                        if (index == area) it.copy(start = moved) else it.copy(end = moved)
                    }
                    return
                }
            }

            else -> Unit
        }
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
        val redrawn = MaskLasso.shape(
            mask = Mask.forRatio(state.canvasRatio).copy(feather = current.feather),
            handles = moved,
        )
        applySelection(state, layer, redrawn, record = false)
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
