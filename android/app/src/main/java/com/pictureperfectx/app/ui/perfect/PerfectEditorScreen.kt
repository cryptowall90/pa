package com.pictureperfectx.app.ui.perfect

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pictureperfectx.app.capture.AspectRatio
import com.pictureperfectx.app.capture.CropMath
import com.pictureperfectx.app.capture.CropRect
import com.pictureperfectx.app.filter.Filter
import com.pictureperfectx.app.layers.ColourTone
import com.pictureperfectx.app.layers.CurveChannel
import com.pictureperfectx.app.layers.CurvePoint
import com.pictureperfectx.app.layers.Curves
import com.pictureperfectx.app.layers.GradientStyle
import com.pictureperfectx.app.layers.Layer
import com.pictureperfectx.app.layers.Mask
import com.pictureperfectx.app.layers.MaskGradient
import com.pictureperfectx.app.layers.MaskOutline
import com.pictureperfectx.app.layers.MaskPoint
import com.pictureperfectx.app.layers.MaskWand
import com.pictureperfectx.app.layers.PathCurve
import com.pictureperfectx.app.layers.SelectionMode
import com.pictureperfectx.app.layers.ShapeKind
import com.pictureperfectx.app.layers.TextFont
import com.pictureperfectx.app.ui.components.CameraNotice
import kotlin.math.min
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private val Brand = Color(0xFFFF4D6D)

/** Margin around the photo, in dp. Small — the picture is what the screen is for. */
private const val STAGE_INSET = 4

/** How far in a pinch can go. Beyond this the preview's own pixels are the limit, not the zoom. */
private const val MAX_ZOOM = 8f

/** How long a finger has to sit still before it means "show me the photo underneath". */
private const val PEEK_DELAY_MS = 400L

/** What a gesture turned out to be, once the finger did something. */
private enum class Settled { Moved, Pinched, Lifted }

/** Drawn size of a lasso handle, and how close a touch must land to grab one. */
private const val HANDLE_RADIUS = 5
private const val HANDLE_TOUCH_RADIUS = 24

/** The curve graph's side, in dp. Square, so its steepness reads honestly. */
private const val CURVE_SIZE = 210
private const val CURVE_GRAB = 0.08f
private const val CURVE_HISTOGRAM_BUCKETS = 48
private const val HISTOGRAM_STRIDE = 7

private const val LOUPE_RADIUS = 46
private const val LOUPE_MAGNIFICATION = 2.5f

/**
 * The Perfect Editor: crop and geometry, plus a stack of masked effect layers.
 *
 * Every control lives in the column *beneath* the photo, never over it. The photo gets whatever
 * height is left, so adjusting a slider always means watching the picture change — which is the
 * one thing a dialog full of sliders made impossible.
 */
@Composable
fun PerfectEditorScreen(
    sourceUri: Uri,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: PerfectEditorViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var addingEffect by remember { mutableStateOf(false) }
    var showingLayers by remember { mutableStateOf(false) }

    LaunchedEffect(sourceUri) { viewModel.load(sourceUri) }
    // Leaving effects abandons a half-opened picker, so it isn't waiting on the way back in.
    LaunchedEffect(state.panel) { if (state.panel != EditorPanel.Effects) addingEffect = false }
    LaunchedEffect(state.savedMessage) {
        state.savedMessage?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }
    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // The photo takes everything above the controls, and starts just under the status bar
            // rather than behind it.
            EditorStage(
                state = state,
                onCropChanged = viewModel::onCropChanged,
                onCropCommitted = viewModel::commitFraming,
                onPaint = viewModel::onPaintMask,
                onStrokeEnd = viewModel::endStroke,
                onLasso = viewModel::onLassoCommitted,
                onMovePoint = viewModel::onMoveHandle,
                onGradientStart = viewModel::onGradientStart,
                onGradient = viewModel::onGradientDrawn,
                onHeal = viewModel::onHealAt,
                onWand = viewModel::onWandAt,
                modifier = Modifier.weight(1f).fillMaxWidth().statusBarsPadding(),
            )

            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.notice?.let { CameraNotice(text = it, onDismiss = viewModel::consumeNotice) }

                if (state.previewing) {
                    // The controls stand down too, so the photo gets the screen — but the row below
                    // stays put, so getting back is the same tap that got here.
                    StatusLine("The edit as it will save — no outlines, no handles. Tap the eye to keep editing.")
                } else when (state.panel) {
                    EditorPanel.Closed -> Unit

                    EditorPanel.Menu -> MenuRow(onOpen = viewModel::onOpenPanel)

                    EditorPanel.Effects -> EffectsControls(
                        state = state,
                        viewModel = viewModel,
                        addingEffect = addingEffect,
                        onAddingEffect = { addingEffect = it },
                        onOpenLayers = { showingLayers = true },
                    )

                    EditorPanel.Crop -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BackToMenu(onClick = viewModel::onBackToMenu)
                            StraightenSlider(
                                degrees = state.geometry.straightenDegrees,
                                onChange = viewModel::onStraighten,
                                onFinished = viewModel::commitFraming,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            ToolButton(Icons.AutoMirrored.Filled.RotateLeft, "Rotate left") {
                                viewModel.onRotate(false)
                            }
                            ToolButton(Icons.AutoMirrored.Filled.RotateRight, "Rotate right") {
                                viewModel.onRotate(true)
                            }
                            ToolButton(Icons.Filled.Flip, "Flip horizontally") { viewModel.onFlip(true) }
                            ToolButton(
                                icon = Icons.Filled.Flip,
                                description = "Flip vertically",
                                rotate = 90f,
                            ) { viewModel.onFlip(false) }
                        }
                        AspectRow(selected = state.geometry.aspect, onSelect = viewModel::onAspectSelected)
                    }
                }

                // Everything that used to sit in a bar above the photo, in one row below it. The
                // circle stays put as panels open and close — a control that moves is a control you
                // have to go looking for.
                ActionRow(
                    state = state,
                    onBack = onBack,
                    onUndo = viewModel::onUndo,
                    onRedo = viewModel::onRedo,
                    onReset = viewModel::onReset,
                    onDraft = viewModel::saveDraft,
                    onSave = { viewModel.save(onSaved) },
                    onToggleMenu = viewModel::onToggleMenu,
                    onTogglePreview = viewModel::onTogglePreview,
                )
            }
        }

        if (showingLayers) {
            LayerSheet(
                state = state,
                onDismiss = { showingLayers = false },
                // Choosing a layer is what you opened this for, so it closes on the way out.
                onSelect = { id -> viewModel.onSelectLayer(id); showingLayers = false },
                // Aiming at a mask is the point of closing the sheet too: you tapped it to go and
                // draw.
                onEditMask = { id -> viewModel.onEditLayerMask(id); showingLayers = false },
                onToggleVisible = viewModel::onToggleLayerVisibility,
                onMove = viewModel::onMoveLayer,
                onDuplicate = viewModel::onDuplicateLayer,
                onRemove = viewModel::onRemoveLayer,
                onCycleBlend = viewModel::onCycleBlend,
            )
        }
    }
}

/** Cancel, history, the edit circle, draft and save — the whole chrome of the editor, in one row. */
@Composable
private fun ActionRow(
    state: PerfectEditUiState,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    onDraft: () -> Unit,
    onSave: () -> Unit,
    onToggleMenu: () -> Unit,
    onTogglePreview: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionIcon(Icons.Filled.Close, "Cancel", onClick = onBack)
        ActionIcon(
            icon = Icons.AutoMirrored.Filled.Undo,
            description = "Undo",
            enabled = state.canUndo,
            onClick = onUndo,
        )
        ActionIcon(
            icon = Icons.AutoMirrored.Filled.Redo,
            description = "Redo",
            enabled = state.canRedo,
            onClick = onRedo,
        )

        EditCircle(isOpen = state.panel != EditorPanel.Closed, onClick = onToggleMenu)

        // The photo on its own. Every mark that says *where* an effect applies sits on top of the
        // thing being judged, so without this there was no way to see the edit itself short of
        // saving it and going to the gallery.
        ActionIcon(
            icon = if (state.previewing) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            description = if (state.previewing) "Back to editing" else "Preview without overlays",
            enabled = state.ready,
            tint = if (state.previewing) Brand else Color.White,
            onClick = onTogglePreview,
        )

        ActionIcon(
            icon = Icons.Filled.Refresh,
            description = "Reset",
            enabled = state.ready,
            onClick = onReset,
        )
        // Stopping for now, as distinct from finishing. Nothing is exported and nothing new appears
        // in the gallery — reopening this photo simply picks the work back up.
        ActionIcon(
            icon = Icons.Filled.BookmarkBorder,
            description = "Save as draft",
            enabled = state.ready,
            onClick = onDraft,
        )
        if (state.isSaving) {
            CircularProgressIndicator(color = Brand, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            ActionIcon(
                icon = Icons.Filled.Check,
                description = "Save",
                enabled = state.ready,
                tint = Brand,
                onClick = onSave,
            )
        }
    }
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) tint else Color(0x55FFFFFF),
            modifier = Modifier.size(21.dp),
        )
    }
}

/**
 * The one control the editor opens with. Tapping it puts everything away and leaves the photo —
 * unless there is nothing to put away, in which case it offers the menu.
 */
@Composable
private fun EditCircle(isOpen: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (isOpen) Color(0x33FFFFFF) else Brand)
            .border(width = 1.dp, color = Brand, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isOpen) Icons.Filled.Close else Icons.Filled.Edit,
            contentDescription = if (isOpen) "Hide the controls" else "Edit this photo",
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The two things the editor can do. Choosing one replaces this row with that tool's controls. */
@Composable
private fun MenuRow(onOpen: (EditorPanel) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        listOf(EditorPanel.Crop, EditorPanel.Effects).forEach { panel ->
            Text(
                text = panel.label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x22FFFFFF))
                    .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(12.dp))
                    .clickable { onOpen(panel) }
                    .padding(vertical = 10.dp),
            )
        }
    }
}

/** Steps a tool back to the menu, so switching tools needn't go through the photo. */
@Composable
private fun BackToMenu(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back to the edit menu",
            tint = Color(0xCCFFFFFF),
            modifier = Modifier.size(20.dp),
        )
    }
}

// ---- Stage --------------------------------------------------------------------------------------

/**
 * The photo, its overlays, and every gesture that lands on it.
 *
 * One gesture loop routes by pointer count — two fingers zoom and pan, one draws — because two
 * competing detectors on the same surface each steal events from the other. The transform is folded
 * into the [fittedBounds] rect that overlays and touch mapping already work against, so all of them
 * follow the zoom without knowing it exists. Nothing may read the untransformed rect.
 */
@Composable
private fun EditorStage(
    state: PerfectEditUiState,
    onCropChanged: (CropRect) -> Unit,
    onCropCommitted: () -> Unit,
    onPaint: (Float, Float) -> Unit,
    onStrokeEnd: () -> Unit,
    onLasso: (List<MaskPoint>) -> Unit,
    onMovePoint: (Int, MaskPoint) -> Unit,
    onGradientStart: () -> Unit,
    onGradient: (MaskPoint, MaskPoint) -> Unit,
    onHeal: (Float, Float) -> Unit,
    onWand: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var stageSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // The lasso being drawn right now, in screen pixels. Committed on release.
    var trace by remember { mutableStateOf(emptyList<Offset>()) }
    // Where the finger is, so the magnifier can show what it's covering. Null between strokes.
    var touch by remember { mutableStateOf<Offset?>(null) }
    // Held still on the photo: show what was there before any of this. Nothing is rendered for it —
    // the un-composited preview is already kept, so the peek is a swap rather than a re-render.
    var peeking by remember { mutableStateOf(false) }
    val canvas = state.canvas

    // Zoom is for precision inside an area. The crop frame is about the whole picture, so cropping
    // always happens at fit.
    LaunchedEffect(state.panel) {
        if (state.panel == EditorPanel.Crop) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    Box(
        modifier = modifier.clipToBounds().onSizeChanged { stageSize = it },
        contentAlignment = Alignment.Center,
    ) {
        if (canvas == null) {
            CircularProgressIndicator(color = Brand)
            return@Box
        }

        val image = remember(canvas) { canvas.asImageBitmap() }
        // Only what is *drawn* swaps during a peek. Everything else — the stage geometry, the touch
        // mapping, the keys on the gesture block — keeps reading the real canvas, so holding a
        // finger down cannot restart the very gesture loop that is watching it.
        val shown = if (peeking) state.original?.asImageBitmap() ?: image else image
        val insetPx = with(LocalDensity.current) { STAGE_INSET.dp.toPx() }
        val fitted = remember(stageSize, canvas.width, canvas.height, insetPx) {
            fittedBounds(
                containerWidth = stageSize.width.toFloat(),
                containerHeight = stageSize.height.toFloat(),
                imageWidth = canvas.width.toFloat(),
                imageHeight = canvas.height.toFloat(),
                inset = insetPx,
            )
        }
        val centre = Offset(stageSize.width / 2f, stageSize.height / 2f)
        // The single source of truth for where the photo actually is. Overlays and touches both
        // read it, so they cannot end up disagreeing about the zoom.
        val bounds = fitted.scaledAbout(centre, scale).translated(offset)

        Image(
            bitmap = shown,
            contentDescription = if (peeking) "The photo before this edit" else "Preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(STAGE_INSET.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )

        // Every overlay describes the edit rather than being part of it — so both a peek at the
        // photo underneath and a clean preview of the edit itself show none of them.
        if (!peeking && !state.previewing) {
            DrawingLayer(
                state = state,
                image = image,
                bounds = bounds,
                trace = trace,
                touch = touch,
                stageSize = stageSize,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Controls put away, but a crop already set: show what will be kept, without the grips that
        // would invite a drag nothing is listening for. It takes no touches, so it doesn't stand
        // between the gesture surface and the photo.
        if (state.showsCropPreview && !peeking) {
            CropOverlay(
                crop = state.geometry.crop,
                imageBounds = bounds,
                lockedRatio = null,
                sourceRatio = state.canvasRatio,
                onCropChanged = {},
                modifier = Modifier.fillMaxSize(),
                interactive = false,
            )
        }

        // Cropping keeps its own gestures and its own frame. Hit testing stops at the topmost
        // sibling under the finger, so the drawing surface is left out entirely here rather than
        // sitting on top declining to consume — which would swallow every crop drag.
        if (state.panel == EditorPanel.Crop) {
            CropOverlay(
                crop = state.geometry.crop,
                imageBounds = bounds,
                lockedRatio = state.geometry.aspect.ratio(state.canvasRatio),
                sourceRatio = state.canvasRatio,
                onCropChanged = onCropChanged,
                onCropCommitted = onCropCommitted,
                modifier = Modifier.fillMaxSize(),
            )
            return@Box
        }

        // The gesture block outlives recompositions, so these are read through the latest state
        // rather than captured — otherwise a pan would be mapped against the bounds from before it.
        val latestBounds by rememberUpdatedState(bounds)
        val latestHandles by rememberUpdatedState(editHandles(state))
        val canSelect = state.canSelect
        val tool = state.selectionTool
        val healing = state.document.selected is Layer.Heal

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // A zoomed-in canvas with no way back is a trap.
                    detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
                }
                .pointerInput(stageSize, canvas.width, canvas.height, canSelect, tool, healing) {
                    val handleRadius = HANDLE_TOUCH_RADIUS.dp.toPx()
                    val minStep = 3.dp.toPx()
                    val touchSlop = viewConfiguration.touchSlop

                    // Any of this block's keys changing restarts it, cancelling whatever gesture was
                    // in progress. Without the reset below, a stroke cut short that way leaves its
                    // half-drawn loop, its magnifier or a stuck peek painted on the photo with no
                    // gesture left alive to finish them — a red circle sitting there over handles
                    // belonging to a different shape entirely.
                    try {
                        awaitEachGesture {
                            val first = awaitFirstDown(requireUnconsumed = false)
                            var transforming = false
                            var painting = false
                            var tracing = false
                            var sweeping = false
                            var gradientStart = MaskPoint(0f, 0f)
                            var grabbed = -1
                            var lastCentroid = first.position
                            var lastSpan = 0f
                            var began = false

                            /**
                             * Starts whatever this tool does with a drag, from where the finger landed.
                             *
                             * Deliberately not called on the down event. Nothing can commit until the
                             * finger has either moved or lifted, which is what lets a held finger mean
                             * "show me the photo underneath" — and what stops the first finger of a
                             * pinch leaving a stray wand selection or heal dab behind.
                             */
                            fun begin() {
                                if (began || !canSelect) return
                                began = true
                                touch = first.position
                                when {
                                    grabbed >= 0 -> Unit
                                    // The tap tools have nothing to begin: they act on release.
                                    healing || tool == SelectionTool.Wand -> touch = null
                                    tool == SelectionTool.Lasso -> {
                                        tracing = true
                                        trace = listOf(first.position)
                                    }
                                    tool == SelectionTool.Fade -> {
                                        sweeping = true
                                        gradientStart = first.position.normalizedIn(latestBounds)
                                        onGradientStart()
                                    }
                                    else -> {
                                        painting = true
                                        paintAt(first.position, latestBounds, onPaint)
                                    }
                                }
                            }

                            if (canSelect) {
                                grabbed = handleAt(latestHandles, first.position, latestBounds, handleRadius)
                            }

                            // Nothing has happened yet. Whichever of these comes first decides what this
                            // gesture was: a move, a second finger, a lift, or a finger held still.
                            val settled = withTimeoutOrNull(PEEK_DELAY_MS) {
                                var outcome = Settled.Lifted
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break
                                    if (pressed.size >= 2) {
                                        outcome = Settled.Pinched
                                        break
                                    }
                                    val moved = (pressed.first().position - first.position).getDistance()
                                    if (moved >= touchSlop) {
                                        outcome = Settled.Moved
                                        break
                                    }
                                }
                                outcome
                            }

                            if (settled == null) {
                                // Held still: the photo as it was, until the finger lifts. Nothing this
                                // gesture might have drawn was ever started, so there is nothing to undo.
                                peeking = true
                                do {
                                    val event = awaitPointerEvent()
                                } while (event.changes.any { it.pressed })
                                peeking = false
                                touch = null
                                return@awaitEachGesture
                            }

                            if (settled == Settled.Lifted) {
                                // A tap. The tools that act on one act now, on release rather than on
                                // press, so a hold could have meant something else.
                                if (canSelect && grabbed < 0) {
                                    val point = first.position.normalizedIn(latestBounds)
                                    if (point.x in 0f..1f && point.y in 0f..1f) {
                                        when {
                                            healing -> onHeal(point.x, point.y)
                                            tool == SelectionTool.Wand -> onWand(point.x, point.y)
                                            tool == SelectionTool.Brush -> {
                                                onPaint(point.x, point.y)
                                                onStrokeEnd()
                                            }
                                            else -> Unit
                                        }
                                    }
                                }
                                touch = null
                                return@awaitEachGesture
                            }

                            if (settled == Settled.Moved) begin()

                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break

                                if (pressed.size >= 2) {
                                    if (!transforming) {
                                        // A second finger makes this a pinch. Abandon what one finger
                                        // began rather than leaving half a stroke behind — but a brush
                                        // or a moved point has already changed the mask, so those close
                                        // their undo step instead of vanishing.
                                        if (painting || sweeping || grabbed >= 0) onStrokeEnd()
                                        trace = emptyList()
                                        touch = null
                                        painting = false
                                        tracing = false
                                        sweeping = false
                                        grabbed = -1
                                        transforming = true
                                        lastCentroid = pressed.centroid()
                                        lastSpan = pressed.spread()
                                    }
                                    val centroid = pressed.centroid()
                                    val spread = pressed.spread()
                                    if (lastSpan > 0f && spread > 0f) {
                                        val next = (scale * (spread / lastSpan)).coerceIn(1f, MAX_ZOOM)
                                        // Hold the picture still under the fingers as it grows.
                                        offset = centroid - centre - (centroid - centre - offset) * (next / scale)
                                        scale = next
                                    }
                                    offset = clampPan(offset + (centroid - lastCentroid), fitted, scale, stageSize)
                                    lastCentroid = centroid
                                    lastSpan = spread
                                    pressed.forEach { it.consume() }
                                } else if (!transforming && (painting || tracing || sweeping || grabbed >= 0)) {
                                    val change = pressed.first()
                                    val position = change.position
                                    touch = position
                                    when {
                                        grabbed >= 0 -> onMovePoint(grabbed, position.normalizedIn(latestBounds))
                                        tracing -> {
                                            val last = trace.lastOrNull()
                                            if (last == null || (position - last).getDistance() >= minStep) {
                                                trace = trace + position
                                            }
                                        }
                                        sweeping -> onGradient(gradientStart, position.normalizedIn(latestBounds))
                                        else -> paintAt(position, latestBounds, onPaint)
                                    }
                                    change.consume()
                                }
                            }

                            touch = null
                            when {
                                transforming -> Unit
                                grabbed >= 0 || painting || sweeping -> onStrokeEnd()
                                tracing -> {
                                    val drawn = trace
                                    trace = emptyList()
                                    onLasso(drawn.map { it.normalizedIn(latestBounds) })
                                }
                            }
                        }
                    } finally {
                        trace = emptyList()
                        touch = null
                        peeking = false
                    }
                },
        )
    }
}

/**
 * Everything drawn over the photo: the boundary of the chosen area, the loop being drawn, the
 * handles that reshape it, and the magnifier.
 *
 * One canvas rather than four, because they're all a function of the same transform and drawing
 * them together is what keeps them from drifting apart.
 */
@Composable
private fun DrawingLayer(
    state: PerfectEditUiState,
    image: ImageBitmap,
    bounds: Rect,
    trace: List<Offset>,
    touch: Offset?,
    stageSize: IntSize,
    modifier: Modifier = Modifier,
) {
    if (!state.canSelect) return
    val mask = state.activeMask
    // Mask compares by content, so these are only recomputed when the area actually changes.
    val contour = remember(mask) {
        if (mask == null || mask.path != null) emptyList() else MaskOutline.segments(mask)
    }
    // The same curve the mask was filled from, so the outline is the edge rather than near it.
    val curve = remember(mask) { mask?.path?.let { PathCurve.smooth(it) }.orEmpty() }
    val handles = editHandles(state)
    val gradient = mask?.gradient
    // A gradient layer's own wash runs along its spec, which is a different thing from the area it
    // applies through — both get an axis, or dragging the handles would be guesswork.
    val ramp = (state.document.selected as? Layer.Gradient)?.takeUnless { it.solid }?.spec

    Canvas(modifier = modifier) {
        if (bounds.width <= 0f || bounds.height <= 0f) return@Canvas

        fun onScreen(point: MaskPoint) =
            Offset(bounds.left + point.x * bounds.width, bounds.top + point.y * bounds.height)

        // A mask that still has its shape is drawn from the very curve it was filled from — exact,
        // with no grid in the way. Anything brushed or combined falls back to the traced contour.
        val outline = Path()
        if (curve.size >= 2) {
            val start = onScreen(curve.first())
            outline.moveTo(start.x, start.y)
            curve.drop(1).forEach { point ->
                val screen = onScreen(point)
                outline.lineTo(screen.x, screen.y)
            }
            outline.close()
        } else {
            contour.forEach { edge ->
                outline.moveTo(bounds.left + edge.x0 * bounds.width, bounds.top + edge.y0 * bounds.height)
                outline.lineTo(bounds.left + edge.x1 * bounds.width, bounds.top + edge.y1 * bounds.height)
            }
        }
        drawPath(outline, color = Color(0xCC000000), style = Stroke(width = 3.dp.toPx()))
        drawPath(outline, color = Color.White, style = Stroke(width = 1.5.dp.toPx()))

        // The loop in progress, with a dashed run back to the start showing how it will close.
        if (trace.size >= 2) {
            val live = Path().apply {
                moveTo(trace.first().x, trace.first().y)
                trace.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(live, color = Brand, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            drawLine(
                color = Color.White,
                start = trace.last(),
                end = trace.first(),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
        }

        // A gradient's contour is only its halfway line, which says nothing about which way it
        // runs. The axis does.
        fun axis(from: MaskPoint, to: MaskPoint) {
            val start = onScreen(from)
            val end = onScreen(to)
            drawLine(Color(0xCC000000), start, end, 3.dp.toPx())
            drawLine(
                color = Brand,
                start = start,
                end = end,
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)),
            )
        }
        gradient?.let { axis(it.start, it.end) }
        ramp?.let { axis(it.start, it.end) }

        handles.forEach { point ->
            val screen = onScreen(point)
            drawCircle(Color(0xCC000000), radius = HANDLE_RADIUS.dp.toPx() + 1.dp.toPx(), center = screen)
            drawCircle(Color.White, radius = HANDLE_RADIUS.dp.toPx(), center = screen)
            drawCircle(Brand, radius = HANDLE_RADIUS.dp.toPx() - 2.dp.toPx(), center = screen)
        }

        touch?.let { position ->
            drawLoupe(image, bounds, position, stageSize)
        }
    }
}

/**
 * A magnified circle of the photo around the finger.
 *
 * Fixed in a corner rather than following the touch: a loupe that tracks the finger runs off screen
 * exactly at the edges, which is where the careful work happens. It swaps corners when the finger
 * strays into the one it is occupying.
 */
private fun DrawScope.drawLoupe(
    image: ImageBitmap,
    bounds: Rect,
    touch: Offset,
    stageSize: IntSize,
) {
    if (bounds.width <= 0f || bounds.height <= 0f || image.width <= 0 || image.height <= 0) return
    val radius = LOUPE_RADIUS.dp.toPx()
    val margin = 12.dp.toPx()
    val placeLeft = touch.x > stageSize.width / 2f
    val centre = Offset(
        x = if (placeLeft) margin + radius else stageSize.width - margin - radius,
        y = margin + radius,
    )

    // The patch of the photo under the finger, in image pixels.
    val pixelsPerScreen = image.width / bounds.width
    val half = (radius / LOUPE_MAGNIFICATION * pixelsPerScreen).coerceAtLeast(1f)
    val u = (touch.x - bounds.left) * pixelsPerScreen
    val v = (touch.y - bounds.top) * (image.height / bounds.height)
    val left = (u - half).roundToInt().coerceIn(0, (image.width - 1).coerceAtLeast(0))
    val top = (v - half).roundToInt().coerceIn(0, (image.height - 1).coerceAtLeast(0))
    val size = (half * 2).roundToInt().coerceAtLeast(1)
    val width = size.coerceAtMost(image.width - left)
    val height = size.coerceAtMost(image.height - top)
    if (width <= 0 || height <= 0) return

    val circle = Path().apply { addOval(Rect(centre - Offset(radius, radius), centre + Offset(radius, radius))) }
    clipPath(circle) {
        drawImage(
            image = image,
            srcOffset = IntOffset(left, top),
            srcSize = IntSize(width, height),
            dstOffset = IntOffset((centre.x - radius).roundToInt(), (centre.y - radius).roundToInt()),
            dstSize = IntSize((radius * 2).roundToInt(), (radius * 2).roundToInt()),
        )
    }
    drawCircle(Color(0xCC000000), radius = radius + 1.dp.toPx(), center = centre, style = Stroke(2.dp.toPx()))
    drawCircle(Color.White, radius = radius, center = centre, style = Stroke(1.dp.toPx()))
    // Crosshair, so it's clear which point of the photo the loupe is centred on.
    drawLine(Color.White, centre - Offset(6.dp.toPx(), 0f), centre + Offset(6.dp.toPx(), 0f), 1.dp.toPx())
    drawLine(Color.White, centre - Offset(0f, 6.dp.toPx()), centre + Offset(0f, 6.dp.toPx()), 1.dp.toPx())
}

// ---- Stage geometry -----------------------------------------------------------------------------

private fun Rect.scaledAbout(pivot: Offset, factor: Float) = Rect(
    left = pivot.x + (left - pivot.x) * factor,
    top = pivot.y + (top - pivot.y) * factor,
    right = pivot.x + (right - pivot.x) * factor,
    bottom = pivot.y + (bottom - pivot.y) * factor,
)

private fun Rect.translated(delta: Offset) =
    Rect(left + delta.x, top + delta.y, right + delta.x, bottom + delta.y)

/** Keeps a zoomed photo covering the stage, and a fitted one centred. */
private fun clampPan(offset: Offset, fitted: Rect, scale: Float, stage: IntSize): Offset {
    val maxX = ((fitted.width * scale - stage.width) / 2f).coerceAtLeast(0f)
    val maxY = ((fitted.height * scale - stage.height) / 2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

private fun Offset.normalizedIn(bounds: Rect): MaskPoint =
    if (bounds.width <= 0f || bounds.height <= 0f) {
        MaskPoint(0f, 0f)
    } else {
        MaskPoint((x - bounds.left) / bounds.width, (y - bounds.top) / bounds.height)
    }

private fun paintAt(position: Offset, bounds: Rect, onPaint: (Float, Float) -> Unit) {
    val point = position.normalizedIn(bounds)
    if (point.x in 0f..1f && point.y in 0f..1f) onPaint(point.x, point.y)
}


/** The index of the handle [position] grabbed, or -1. */
private fun handleAt(path: List<MaskPoint>, position: Offset, bounds: Rect, radius: Float): Int {
    if (path.isEmpty() || bounds.width <= 0f || bounds.height <= 0f) return -1
    var best = -1
    var bestDistance = radius
    path.forEachIndexed { index, point ->
        val screen = Offset(bounds.left + point.x * bounds.width, bounds.top + point.y * bounds.height)
        val distance = (position - screen).getDistance()
        if (distance <= bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    return best
}

private fun List<PointerInputChange>.centroid(): Offset {
    if (isEmpty()) return Offset.Zero
    var sum = Offset.Zero
    forEach { sum += it.position }
    return sum / size.toFloat()
}

/** Mean distance of the pointers from their centroid — the thing a pinch changes. */
private fun List<PointerInputChange>.spread(): Float {
    if (size < 2) return 0f
    val middle = centroid()
    var total = 0f
    forEach { total += (it.position - middle).getDistance() }
    return total / size
}

/** Where a Fit-scaled image of [imageWidth] x [imageHeight] lands inside the container. */
private fun fittedBounds(
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
    inset: Float,
): Rect {
    val availableWidth = (containerWidth - inset * 2).coerceAtLeast(0f)
    val availableHeight = (containerHeight - inset * 2).coerceAtLeast(0f)
    if (imageWidth <= 0f || imageHeight <= 0f || availableWidth <= 0f || availableHeight <= 0f) {
        return Rect(0f, 0f, 0f, 0f)
    }
    val scale = min(availableWidth / imageWidth, availableHeight / imageHeight)
    val drawnWidth = imageWidth * scale
    val drawnHeight = imageHeight * scale
    val left = (containerWidth - drawnWidth) / 2f
    val top = (containerHeight - drawnHeight) / 2f
    return Rect(left, top, left + drawnWidth, top + drawnHeight)
}

// ---- Effects controls ---------------------------------------------------------------------------

/**
 * Everything an effect needs, as a few short rows under the photo: what's in the stack, which
 * property the slider is on, that slider, and the actions.
 *
 * The predecessor put all of this in a dialog, which meant the photo was behind a scrim exactly
 * when the slider was moving.
 */
@Composable
private fun EffectsControls(
    state: PerfectEditUiState,
    viewModel: PerfectEditorViewModel,
    addingEffect: Boolean,
    onAddingEffect: (Boolean) -> Unit,
    onOpenLayers: () -> Unit,
) {
    val layer = state.document.selected

    // Zone one: how an area gets chosen. Four tools and a mode, in fixed slots that always fit —
    // this used to be a scrolling row of up to ten chips mixing tools, mask actions and layer
    // actions, with Delete off the right-hand edge.
    ToolBar(state = state, viewModel = viewModel)

    // Zone two: the stack, and what to do with the area. Adding an effect and reaching the layers
    // are always here — not conditionally, as they were when the stack lived in a scrolling row
    // that a selected layer pushed off the end of.
    StackBar(
        state = state,
        viewModel = viewModel,
        onAdd = { onAddingEffect(true) },
        onOpenLayers = onOpenLayers,
    )

    if (addingEffect) {
        EffectPickerRow(
            onPick = { kind ->
                onAddingEffect(false)
                viewModel.onAddEffect(kind)
            },
            onCancel = { onAddingEffect(false) },
        )
    }

    // Zone three: exactly one control for whatever is selected, and a line saying what a tap does.
    if (layer == null) {
        ToolInspector(state = state, viewModel = viewModel)
        return
    }

    val controls = LayerControl.forLayer(layer)
    val control = LayerControl.effective(controls, state.control)

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(controls, key = { it.name }) { candidate ->
            // A toggle changes the layer and leaves the slider where it was, so it is never the
            // chip that looks chosen — it reports its own on/off state instead.
            if (candidate.kind == ControlKind.Toggle) {
                PanelChip(
                    label = candidate.label,
                    isSelected = candidate.isOn(layer),
                ) { viewModel.onToggleControl(layer.id, candidate) }
            } else {
                PanelChip(label = candidate.label, isSelected = candidate == control) {
                    viewModel.onSelectControl(candidate)
                }
            }
        }
    }

    LayerInspector(layer = layer, control = control, state = state, viewModel = viewModel)
    StatusLine(text = describe(layer, state))
}

/** What is being edited, where it lands, and what touching the photo will do next. */
private fun describe(layer: Layer, state: PerfectEditUiState): String {
    val where = if (layer.mask.isEmpty) "the whole photo" else "its own area"
    // Drawing no longer touches this layer unless its mask is the target, so the line has to say
    // which of the two is about to change — that ambiguity is what used to destroy people's areas.
    return "${layer.name} · applies to $where — ${describeDrawing(state).replaceFirstChar { it.lowercase() }}"
}

/**
 * The settings for whichever tool is in hand, when no layer is selected yet.
 *
 * One block, never more than two rows, and it says in words what a tap or a drag will do — the one
 * thing the old panel never told anyone.
 */
@Composable
private fun ToolInspector(state: PerfectEditUiState, viewModel: PerfectEditorViewModel) {
    when (state.selectionTool) {
        SelectionTool.Brush -> ValueSlider(
            value = state.brushRadius,
            range = 0.02f..0.5f,
            readout = "${(state.brushRadius * 100).roundToInt()}",
            onChange = viewModel::onBrushRadius,
        )

        SelectionTool.Wand -> ValueSlider(
            value = state.wandTolerance,
            range = MaskWand.MIN_TOLERANCE..MaskWand.MAX_TOLERANCE,
            readout = "${(state.wandTolerance * 100).roundToInt()}",
            onChange = viewModel::onWandTolerance,
        )

        SelectionTool.Fade -> ChipRow(
            items = GradientStyle.entries.toList(),
            label = { it.label },
            isSelected = { it == state.gradientStyle },
            onSelect = viewModel::onSelectGradientStyle,
        )

        SelectionTool.Lasso -> Unit
    }

    // An area can be softened before its effect is chosen, the same as after.
    if (state.activeMask != null) FeatherSlider(state = state, viewModel = viewModel)

    StatusLine(text = describeDrawing(state))
}

/**
 * What a tap or a drag will do right now, in words.
 *
 * The mode is the part nobody could work out from the screen: three chips labelled New, Add and
 * Subtract, with nothing anywhere saying what they combine. They combine what you are drawing *now*
 * with the area you already have — so that is what this says, every time, in the same shape.
 */
private fun describeDrawing(state: PerfectEditUiState): String {
    val doing = when (state.selectionTool) {
        SelectionTool.Lasso -> "draw around an area"
        SelectionTool.Brush -> "paint over an area"
        SelectionTool.Fade -> "drag across the photo"
        SelectionTool.Wand -> "tap a color"
    }
    // Aiming at a layer's mask is the exception and has to say so loudest.
    state.targetedLayer?.let { layer ->
        return "Editing ${layer.name}'s area — $doing to ${state.selectionMode.verb}."
    }
    // A drawn area with a layer selected is the state that used to be silently useless: the shape
    // was on screen, the layer went on using the area it already had, and nothing said why. Naming
    // the button that joins them is the difference between a bug and a step.
    state.document.selected?.let { layer ->
        if (state.selection != null) {
            return "Area drawn — tap Apply to ${layer.name} to ${state.selectionMode.verb}, " +
                "or add an effect to use it on a new one."
        }
    }
    if (state.activeMask == null) {
        return "${doing.replaceFirstChar { it.uppercase() }}, then add an effect to apply it only there."
    }
    return "Area ready — add an effect, or $doing to ${state.selectionMode.verb}."
}

/** What this mode does to the area you already have. */
private val SelectionMode.verb: String
    get() = when (this) {
        SelectionMode.Replace -> "replace it"
        SelectionMode.Add -> "grow it"
        SelectionMode.Subtract -> "cut it away"
    }

/**
 * The one control the selected chip asks for.
 *
 * Every branch is a *replacement*, not an addition. A text layer used to stack its chips, a slider,
 * a text field and a font row — four rows under the photo on top of the two above them. Here the
 * words, the font and the size are three chips that each swap out the same block.
 */
@Composable
private fun LayerInspector(
    layer: Layer,
    control: LayerControl,
    state: PerfectEditUiState,
    viewModel: PerfectEditorViewModel,
) {
    when {
        control == LayerControl.TextContent && layer is Layer.Text -> BasicTextField(
            value = layer.content,
            onValueChange = { viewModel.onTextContent(layer.id, it) },
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            cursorBrush = SolidColor(Brand),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x22FFFFFF))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )

        control == LayerControl.TextTypeface && layer is Layer.Text -> ChipRow(
            items = TextFont.entries.toList(),
            label = { it.label },
            isSelected = { it == layer.font },
            onSelect = { viewModel.onTextFont(layer.id, it) },
        )

        // The five shapes a gradient runs in, as a control of their own. They used to hide under
        // the chip labelled Falloff, which is a different thing entirely.
        control == LayerControl.GradientStylePick && layer is Layer.Gradient -> ChipRow(
            items = GradientStyle.entries.toList(),
            label = { it.label },
            isSelected = { it == layer.spec.style },
            onSelect = { style ->
                viewModel.onGradientLayerSpec(layer.id) { it.copy(style = style) }
            },
        )

        control == LayerControl.ShapeKindPick && layer is Layer.Shape -> ChipRow(
            items = ShapeKind.entries.toList(),
            label = { it.label },
            isSelected = { it == layer.kind },
            onSelect = { viewModel.onShapeKind(layer.id, it) },
        )

        control == LayerControl.LookPick && layer is Layer.Look -> LookRow(
            filters = viewModel.filters,
            selectedId = layer.filterId,
            onSelect = { viewModel.onLayerFilter(layer.id, it) },
        )

        control == LayerControl.CurveGraph && layer is Layer.Curve -> {
            ChipRow(
                items = CurveChannel.entries.toList(),
                label = { it.label },
                isSelected = { it == state.curveChannel },
                onSelect = viewModel::onSelectCurveChannel,
            )
            CurveEditor(
                points = layer.spec.channel(state.curveChannel),
                channel = state.curveChannel,
                histogram = state.canvas?.let { rememberHistogram(it, state.curveChannel) },
                onMove = { index, point -> viewModel.onMoveCurvePoint(layer.id, index, point) },
                onAdd = { viewModel.onAddCurvePoint(layer.id, it) },
                onRemove = { viewModel.onRemoveCurvePoint(layer.id, it) },
                onFinished = viewModel::commitLayerEdit,
            )
        }

        else -> {
            ControlSlider(layer = layer, control = control, state = state, viewModel = viewModel)
            // The wheel reaches black and white in principle, but not transparent — and nobody
            // should have to aim for an exact corner of a picker for the two colours captions are
            // usually set in. The shortcuts ride with it rather than as a row of their own.
            ColourTones(layer = layer, control = control, viewModel = viewModel)
        }
    }
}

/** The shortcuts beside the wheel: black, white, and — for a ramp — clear. */
@Composable
private fun ColourTones(layer: Layer, control: LayerControl, viewModel: PerfectEditorViewModel) {
    when {
        layer is Layer.Text && control == LayerControl.TextColour -> ChipRow(
            // Clear is a gradient idea; invisible text is a bug report, not a choice.
            items = ColourTone.entries.filter { it != ColourTone.Clear },
            label = { it.label },
            isSelected = { it == layer.colour.tone },
            onSelect = { viewModel.onTextTone(layer.id, it) },
        )

        layer is Layer.Shape && control == LayerControl.TextColour -> ChipRow(
            items = ColourTone.entries.filter { it != ColourTone.Clear },
            label = { it.label },
            isSelected = { it == layer.colour.tone },
            onSelect = { viewModel.onShapeTone(layer.id, it) },
        )

        layer is Layer.Gradient &&
            (control == LayerControl.ColourFrom || control == LayerControl.ColourTo) -> {
            val atStart = control == LayerControl.ColourFrom
            val colour = if (atStart) layer.from else layer.to
            // Clear is a ramp idea — a fill of nothing is just a hidden layer. Solid used to ride
            // along at the end of this row, which made turning a fill back into a gradient
            // something you found by accident; it is a chip of its own now.
            ChipRow(
                items = ColourTone.entries.filter { !layer.solid || it != ColourTone.Clear },
                label = { it.label },
                isSelected = { it == colour.tone },
                onSelect = { viewModel.onGradientTone(layer.id, atStart, it) },
            )
        }

        else -> Unit
    }
}

/** One row of chips where one is chosen. The shape most of this panel takes. */
@Composable
private fun <T> ChipRow(
    items: List<T>,
    label: (T) -> String,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items) { item ->
            PanelChip(label = label(item), isSelected = isSelected(item)) { onSelect(item) }
        }
    }
}

/** What is selected and what a tap will do, in one line that is always in the same place. */
@Composable
private fun StatusLine(text: String) {
    Text(
        text = text,
        color = Color(0xAAFFFFFF),
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

/** The single control, showing whichever property the chips selected: a slider, or the wheel. */
@Composable
private fun ControlSlider(
    layer: Layer,
    control: LayerControl,
    state: PerfectEditUiState,
    viewModel: PerfectEditorViewModel,
) {
    val band = control.band
    when {
        band != null && layer is Layer.Tone -> {
            val value = layer.adjustments.valueOf(band)
            ValueSlider(
                value = value.toFloat(),
                range = -100f..100f,
                readout = "$value",
                onChange = { viewModel.onLayerToneChanged(layer.id, band, it.roundToInt()) },
                onChangeFinished = viewModel::commitLayerEdit,
            )
        }

        control == LayerControl.Blur && layer is Layer.Blur -> ValueSlider(
            value = layer.radius.toFloat(),
            range = 1f..60f,
            readout = "${layer.radius}",
            onChange = { viewModel.onLayerBlurRadius(layer.id, it.roundToInt()) },
            onChangeFinished = viewModel::commitLayerEdit,
        )

        (control == LayerControl.ColourFrom || control == LayerControl.ColourTo) &&
            layer is Layer.Gradient -> {
            val atStart = control == LayerControl.ColourFrom
            ColourWheel(
                colour = if (atStart) layer.from else layer.to,
                onPick = { viewModel.onGradientColour(layer.id, atStart, it) },
                onPicked = viewModel::commitLayerEdit,
            )
        }

        control == LayerControl.TextSize && layer is Layer.Text -> ValueSlider(
            value = layer.size,
            range = 0.02f..0.5f,
            readout = "${(layer.size * 100).roundToInt()}",
            onChange = { viewModel.onTextSize(layer.id, it) },
            onChangeFinished = viewModel::commitLayerEdit,
        )

        control == LayerControl.TextRotation && layer is Layer.Text -> ValueSlider(
            value = layer.rotation,
            range = -180f..180f,
            readout = "${layer.rotation.roundToInt()}°",
            onChange = { viewModel.onTextRotation(layer.id, it) },
            onChangeFinished = viewModel::commitLayerEdit,
        )

        control == LayerControl.TextColour && layer is Layer.Text -> ColourWheel(
            colour = layer.colour,
            onPick = { viewModel.onTextColour(layer.id, it) },
            onPicked = viewModel::commitLayerEdit,
        )

        control == LayerControl.SmoothAmount && layer is Layer.Smooth -> ValueSlider(
            value = layer.amount.toFloat(),
            range = 0f..100f,
            readout = "${layer.amount}",
            onChange = { viewModel.onSmoothAmount(layer.id, it.roundToInt()) },
            onChangeFinished = viewModel::commitLayerEdit,
        )

        control == LayerControl.HealSize -> ValueSlider(
            value = state.healRadius,
            range = 0.005f..0.08f,
            readout = "${(state.healRadius * 1000).roundToInt()}",
            onChange = viewModel::onHealRadius,
        )

        control == LayerControl.ShapeStroke && layer is Layer.Shape -> ValueSlider(
            value = layer.stroke,
            range = 0f..0.1f,
            readout = if (layer.stroke <= 0f) "Fill" else "${(layer.stroke * 100).roundToInt()}",
            onChange = { viewModel.onShapeStroke(layer.id, it) },
            onChangeFinished = viewModel::commitLayerEdit,
        )

        control == LayerControl.TextRotation && layer is Layer.Shape -> ValueSlider(
            value = layer.rotation,
            range = -180f..180f,
            readout = "${layer.rotation.roundToInt()}°",
            onChange = { viewModel.onShapeRotation(layer.id, it) },
            onChangeFinished = viewModel::commitLayerEdit,
        )

        control == LayerControl.TextColour && layer is Layer.Shape -> ColourWheel(
            colour = layer.colour,
            onPick = { viewModel.onShapeColour(layer.id, it) },
            onPicked = viewModel::commitLayerEdit,
        )

        control == LayerControl.Intensity && layer is Layer.Look -> ValueSlider(
            value = layer.intensity.toFloat(),
            range = 0f..100f,
            readout = "${layer.intensity}",
            onChange = { viewModel.onLayerIntensity(layer.id, it.roundToInt()) },
            onChangeFinished = viewModel::commitLayerEdit,
        )

        control == LayerControl.Falloff && layer is Layer.Gradient -> {
            val midpoint = layer.spec.midpoint
            ValueSlider(
                value = midpoint,
                range = MaskGradient.MIN_MIDPOINT..MaskGradient.MAX_MIDPOINT,
                readout = "${(midpoint * 100).roundToInt()}",
                onChange = { value ->
                    viewModel.onGradientLayerSpec(layer.id) {
                        it.copy(
                            midpoint = value.coerceIn(
                                MaskGradient.MIN_MIDPOINT,
                                MaskGradient.MAX_MIDPOINT,
                            ),
                        )
                    }
                },
                onChangeFinished = viewModel::commitLayerEdit,
            )
        }

        control == LayerControl.Falloff -> {
            val midpoint = state.activeMask?.gradient?.midpoint ?: 0.5f
            ValueSlider(
                value = midpoint,
                range = MaskGradient.MIN_MIDPOINT..MaskGradient.MAX_MIDPOINT,
                readout = "${(midpoint * 100).roundToInt()}",
                onChange = viewModel::onFalloff,
                onChangeFinished = viewModel::commitLayerEdit,
            )
        }

        control == LayerControl.Feather -> FeatherSlider(state = state, viewModel = viewModel)

        else -> ValueSlider(
            value = layer.opacity,
            range = 0f..1f,
            readout = "${(layer.opacity * 100).roundToInt()}%",
            onChange = { viewModel.onLayerOpacity(layer.id, it) },
            onChangeFinished = viewModel::commitLayerEdit,
        )
    }
}

/**
 * Zone one: what you are drawing with.
 *
 * Four tools in fixed slots that always fit, and the mode beside them. Deliberately not a scrolling
 * row — a control that has to be scrolled to is a control that isn't there, which is exactly what
 * happened to Delete when tools, mask actions and layer actions all shared one LazyRow.
 */
@Composable
private fun ToolBar(state: PerfectEditUiState, viewModel: PerfectEditorViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BackToMenu(onClick = viewModel::onBackToMenu)
        Segmented(
            options = SelectionTool.entries.map { it.label },
            selectedIndex = SelectionTool.entries.indexOf(state.selectionTool),
            onSelect = { viewModel.onSelectionTool(SelectionTool.entries[it]) },
            modifier = Modifier.weight(1f),
        )
        Segmented(
            options = SelectionMode.entries.map { it.label },
            selectedIndex = SelectionMode.entries.indexOf(state.selectionMode),
            onSelect = { viewModel.onSelectionMode(SelectionMode.entries[it]) },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Zone two: the stack, and what to do with the area.
 *
 * Add and Layers are pinned, so nothing can push them off the end. Everything after them depends on
 * what is in hand and scrolls, which is what lets the row grow without either of the two buttons
 * that are always wanted going missing.
 */
@Composable
private fun StackBar(
    state: PerfectEditUiState,
    viewModel: PerfectEditorViewModel,
    onAdd: () -> Unit,
    onOpenLayers: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PanelChip(label = "+ Effect", isSelected = true, onClick = onAdd)
        LayersButton(state = state, onClick = onOpenLayers)
        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.selectionTool == SelectionTool.Wand) {
                item {
                    PanelChip(
                        label = if (state.wandContiguous) "This area" else "All alike",
                        isSelected = !state.wandContiguous,
                        onClick = viewModel::onToggleWandContiguous,
                    )
                }
            }
            // The step that makes a drawn area mean something: it belongs to the document until it
            // is deliberately handed to a layer. First in the row, because with something drawn it
            // is almost always what you meant to do next.
            if (state.canApplySelection) {
                val layer = state.document.selected
                if (layer != null) {
                    item {
                        PanelChip(
                            label = "Apply to ${layer.name}",
                            isSelected = true,
                            onClick = viewModel::onApplySelection,
                        )
                    }
                }
            }
            // Inverting an empty mask means "cover nothing", which would make the layer silently
            // vanish rather than do what was asked — so it needs an area to act on.
            if (state.activeMask != null) {
                item {
                    PanelChip(
                        label = "Invert",
                        isSelected = state.activeMask?.inverted == true,
                        onClick = viewModel::onInvertMask,
                    )
                }
            }
            if (state.canClearArea) {
                item {
                    PanelChip(
                        label = if (state.selection != null) "Deselect" else "Clear",
                        onClick = viewModel::onClearMask,
                    )
                }
            }
            // Putting the stack down. Without it the only way to stop editing a layer was to add
            // another one, which is a strange thing to have to do to look at the photo.
            if (state.document.selected != null) {
                item { PanelChip(label = "Done", onClick = viewModel::onDeselectLayer) }
            }
        }
    }
}

/** The count is the point: it says the stack exists without anything having to be opened. */
@Composable
private fun LayersButton(state: PerfectEditUiState, onClick: () -> Unit) {
    val count = state.document.layers.size
    PanelChip(
        label = if (count == 0) "Layers" else "Layers · $count",
        isSelected = false,
        onClick = onClick,
    )
}

/**
 * A row of choices where exactly one is on, sized so every option is always visible.
 *
 * The chips it replaces looked identical whether they were a mode, an action or a tool. A segment
 * that is visibly part of a set says "one of these", which is what these actually are.
 */
@Composable
private fun Segmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x14FFFFFF))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Brand else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) Color.White else Color(0xAAFFFFFF),
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The curve, and the touch handling that bends it.
 *
 * Square and centred, because a curve read on a stretched graph misleads about how steep it is —
 * and steepness is the whole reading. It takes height from the photo while it's open, which is the
 * one place in this editor that's worth it: you can't judge a curve you can't see.
 */
@Composable
private fun CurveEditor(
    points: List<CurvePoint>,
    channel: CurveChannel,
    histogram: FloatArray?,
    onMove: (Int, CurvePoint) -> Unit,
    onAdd: (CurvePoint) -> Unit,
    onRemove: (Int) -> Unit,
    onFinished: () -> Unit,
) {
    val ink = when (channel) {
        CurveChannel.Rgb -> Color.White
        CurveChannel.Red -> Color(0xFFFF6B6B)
        CurveChannel.Green -> Color(0xFF6BE07A)
        CurveChannel.Blue -> Color(0xFF6BA8FF)
    }
    val latestPoints by rememberUpdatedState(points)

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      Box(
        modifier = Modifier
            .size(CURVE_SIZE.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x59000000))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                // Input runs bottom-left to top-right, so y is flipped against the screen.
                fun at(position: Offset) = CurvePoint(
                    x = (position.x / size.width).coerceIn(0f, 1f),
                    y = (1f - position.y / size.height).coerceIn(0f, 1f),
                )
                // Both detectors in one block, each on its own coroutine: two pointerInput
                // modifiers on the same node race for the down event and one loses.
                coroutineScope {
                    launch {
                        detectTapGestures(
                            onTap = { onAdd(at(it)) },
                            // Long press to remove rather than a double tap: a second tap would
                            // first be read as the tap that adds a point.
                            onLongPress = { position ->
                                val index = Curves.nearest(latestPoints, at(position), CURVE_GRAB)
                                if (index >= 0) onRemove(index)
                            },
                        )
                    }
                    launch {
                        var dragging = -1
                        detectDragGestures(
                            onDragStart = {
                                dragging = Curves.nearest(latestPoints, at(it), CURVE_GRAB)
                            },
                            onDragEnd = { if (dragging >= 0) onFinished(); dragging = -1 },
                            onDragCancel = { dragging = -1 },
                        ) { change, _ ->
                            change.consume()
                            if (dragging >= 0) onMove(dragging, at(change.position))
                        }
                    }
                }
            },
      ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            fun onScreen(point: CurvePoint) =
                Offset(point.x * size.width, (1f - point.y) * size.height)

            // The histogram says where the tones actually are, which is what turns a curve from
            // guesswork into a decision.
            histogram?.let { bars ->
                val width = size.width / bars.size
                bars.forEachIndexed { index, value ->
                    drawRect(
                        color = Color(0x33FFFFFF),
                        topLeft = Offset(index * width, size.height * (1f - value)),
                        size = Size(width, size.height * value),
                    )
                }
            }

            // Quarters, so an eye can find the shadows and highlights without measuring.
            for (step in 1..3) {
                val at = size.width * step / 4f
                drawLine(Color(0x22FFFFFF), Offset(at, 0f), Offset(at, size.height), 1.dp.toPx())
                val down = size.height * step / 4f
                drawLine(Color(0x22FFFFFF), Offset(0f, down), Offset(size.width, down), 1.dp.toPx())
            }
            drawLine(
                color = Color(0x33FFFFFF),
                start = Offset(0f, size.height),
                end = Offset(size.width, 0f),
                strokeWidth = 1.dp.toPx(),
            )

            val path = Path()
            points.forEachIndexed { index, point ->
                val screen = onScreen(point)
                if (index == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
            }
            drawPath(path, color = ink, style = Stroke(width = 2.dp.toPx()))

            points.forEach { point ->
                val screen = onScreen(point)
                drawCircle(Color(0xCC000000), radius = 6.dp.toPx(), center = screen)
                drawCircle(ink, radius = 4.dp.toPx(), center = screen)
            }
        }
      }
    }
}

/**
 * A coarse histogram of the preview.
 *
 * Sampled on a stride rather than every pixel — a million reads on the main thread for a graph an
 * inch wide would be a stutter every time the preview re-renders, and a stride of seven describes
 * the same distribution.
 */
@Composable
private fun rememberHistogram(canvas: Bitmap, channel: CurveChannel): FloatArray =
    remember(canvas, channel) {
        val buckets = FloatArray(CURVE_HISTOGRAM_BUCKETS)
        val width = canvas.width
        if (width <= 0 || canvas.height <= 0 || canvas.isRecycled) return@remember buckets

        val row = IntArray(width)
        var y = 0
        while (y < canvas.height) {
            canvas.getPixels(row, 0, width, 0, y, width, 1)
            var x = 0
            while (x < width) {
                val pixel = row[x]
                val value = when (channel) {
                    CurveChannel.Red -> (pixel shr 16) and 0xFF
                    CurveChannel.Green -> (pixel shr 8) and 0xFF
                    CurveChannel.Blue -> pixel and 0xFF
                    CurveChannel.Rgb -> (
                        ((pixel shr 16) and 0xFF) * 299 +
                            ((pixel shr 8) and 0xFF) * 587 +
                            (pixel and 0xFF) * 114
                        ) / 1000
                }
                buckets[value * (CURVE_HISTOGRAM_BUCKETS - 1) / 255]++
                x += HISTOGRAM_STRIDE
            }
            y += HISTOGRAM_STRIDE
        }
        // Scaled against the tallest bar: the shape is the point, not the absolute counts.
        val tallest = buckets.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        FloatArray(buckets.size) { (buckets[it] / tallest).coerceIn(0f, 1f) }
    }

/**
 * The hundred looks, as a scrolling row wearing their own colours.
 *
 * The swatch matters more than the name here: "Portra 400" tells you nothing at a glance, and the
 * catalog has carried a start and end colour for every look since the camera got them.
 */
@Composable
private fun LookRow(filters: List<Filter>, selectedId: String, onSelect: (String) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(filters, key = { it.id }) { filter ->
            val isSelected = filter.id == selectedId
            Text(
                text = filter.displayName,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.horizontalGradient(listOf(filter.swatchStart, filter.swatchEnd)))
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) Brand else Color(0x33FFFFFF),
                        shape = RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelect(filter.id) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** Choosing what to add, inline — the photo stays visible even while the list is open. */
@Composable
private fun EffectPickerRow(onPick: (EffectKind) -> Unit, onCancel: () -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(EffectKind.entries.toList(), key = { it.name }) { kind ->
            PanelChip(label = kind.label, isSelected = true) { onPick(kind) }
        }
        item { PanelChip(label = "Cancel", onClick = onCancel) }
    }
}

// ---- Shared pieces ------------------------------------------------------------------------------

/**
 * How softly the effect stops at the area's edge, plus a way to get hold of that edge.
 *
 * The two belong together: softening an edge is the moment you start caring exactly where it runs,
 * and until now a brushed, wand-picked or faded area had no points to take hold of at all — the only
 * way to adjust one was to paint over it again.
 */
@Composable
private fun FeatherSlider(state: PerfectEditUiState, viewModel: PerfectEditorViewModel) {
    val mask = state.activeMask ?: return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ValueSlider(
            value = mask.feather,
            range = 0f..1f,
            readout = "${(mask.feather * 100).roundToInt()}",
            onChange = viewModel::onFeather,
            onChangeFinished = viewModel::commitLayerEdit,
            modifier = Modifier.weight(1f),
        )
        // A lasso already carries its points, so offering to derive them would be a button that
        // replaces a hand-drawn shape with an approximation of itself.
        if (mask.path == null) {
            PanelChip(label = "Points", onClick = viewModel::onTraceArea)
            Spacer(modifier = Modifier.width(20.dp))
        }
    }
}

/**
 * One slider with a readout. [onChangeFinished] is what turns a whole drag into a single undo
 * step, rather than one per pixel of travel.
 */
@Composable
private fun ValueSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    readout: String,
    onChange: (Float) -> Unit,
    onChangeFinished: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Brand,
                activeTrackColor = Brand,
                inactiveTrackColor = Color(0x55FFFFFF),
            ),
            modifier = Modifier.weight(1f).height(28.dp),
        )
        Text(
            text = readout,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Brand,
            modifier = Modifier.padding(start = 10.dp).width(44.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun PanelChip(label: String, isSelected: Boolean = false, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (isSelected) Color.White else Color(0xCCFFFFFF),
        fontSize = 10.sp,
        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) Brand else Color(0x22FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun StraightenSlider(
    degrees: Float,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Straighten", color = Color(0xCCFFFFFF), fontSize = 11.sp)
        Slider(
            value = degrees,
            onValueChange = onChange,
            // The gesture ending is one undo step; every frame of it would be hundreds.
            onValueChangeFinished = onFinished,
            valueRange = -CropMath.MAX_STRAIGHTEN_DEGREES..CropMath.MAX_STRAIGHTEN_DEGREES,
            colors = SliderDefaults.colors(
                thumbColor = Brand,
                activeTrackColor = Brand,
                inactiveTrackColor = Color(0x55FFFFFF),
            ),
            modifier = Modifier.weight(1f).height(28.dp).padding(horizontal = 10.dp),
        )
        Text(
            text = "${degrees.roundToInt()}°",
            color = Brand,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    rotate: Float = 0f,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.graphicsLayer { rotationZ = rotate },
        )
    }
}

@Composable
private fun AspectRow(selected: AspectRatio, onSelect: (AspectRatio) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(AspectRatio.entries.toList(), key = { it.name }) { aspect ->
            val isSelected = aspect == selected
            Text(
                text = aspect.label,
                color = if (isSelected) Color.White else Color(0xCCFFFFFF),
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) Brand else Color(0x22FFFFFF))
                    .border(
                        width = 1.dp,
                        color = if (isSelected) Brand else Color(0x33FFFFFF),
                        shape = RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelect(aspect) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
