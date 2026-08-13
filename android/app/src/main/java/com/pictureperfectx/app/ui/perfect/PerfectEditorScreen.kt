package com.pictureperfectx.app.ui.perfect

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
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
import com.pictureperfectx.app.layers.ColourTone
import com.pictureperfectx.app.layers.GradientStyle
import com.pictureperfectx.app.layers.Layer
import com.pictureperfectx.app.layers.Mask
import com.pictureperfectx.app.layers.MaskGradient
import com.pictureperfectx.app.layers.MaskOutline
import com.pictureperfectx.app.layers.MaskPoint
import com.pictureperfectx.app.layers.SelectionMode
import com.pictureperfectx.app.ui.components.CameraNotice
import kotlin.math.min
import kotlin.math.roundToInt

private val Brand = Color(0xFFFF4D6D)

/** Margin around the photo, in dp. Small — the picture is what the screen is for. */
private const val STAGE_INSET = 4

/** How far in a pinch can go. Beyond this the preview's own pixels are the limit, not the zoom. */
private const val MAX_ZOOM = 8f

/** Drawn size of a lasso handle, and how close a touch must land to grab one. */
private const val HANDLE_RADIUS = 5
private const val HANDLE_TOUCH_RADIUS = 24

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
                onPaint = viewModel::onPaintMask,
                onStrokeEnd = viewModel::endStroke,
                onLasso = viewModel::onLassoCommitted,
                onMovePoint = viewModel::onMoveHandle,
                onGradientStart = viewModel::onGradientStart,
                onGradient = viewModel::onGradientDrawn,
                modifier = Modifier.weight(1f).fillMaxWidth().statusBarsPadding(),
            )

            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.notice?.let { CameraNotice(text = it, onDismiss = viewModel::consumeNotice) }

                when (state.panel) {
                    EditorPanel.Closed -> Unit

                    EditorPanel.Menu -> MenuRow(onOpen = viewModel::onOpenPanel)

                    EditorPanel.Effects -> EffectsControls(
                        state = state,
                        viewModel = viewModel,
                        addingEffect = addingEffect,
                        onAddingEffect = { addingEffect = it },
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
                    onSave = { viewModel.save(onSaved) },
                    onToggleMenu = viewModel::onToggleMenu,
                )
            }
        }
    }
}

/** Cancel, history, the edit circle and save — the whole chrome of the editor, in one row. */
@Composable
private fun ActionRow(
    state: PerfectEditUiState,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onToggleMenu: () -> Unit,
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

        ActionIcon(
            icon = Icons.Filled.Refresh,
            description = "Reset",
            enabled = state.ready,
            onClick = onReset,
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
    onPaint: (Float, Float) -> Unit,
    onStrokeEnd: () -> Unit,
    onLasso: (List<MaskPoint>) -> Unit,
    onMovePoint: (Int, MaskPoint) -> Unit,
    onGradientStart: () -> Unit,
    onGradient: (MaskPoint, MaskPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    var stageSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // The lasso being drawn right now, in screen pixels. Committed on release.
    var trace by remember { mutableStateOf(emptyList<Offset>()) }
    // Where the finger is, so the magnifier can show what it's covering. Null between strokes.
    var touch by remember { mutableStateOf<Offset?>(null) }
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
            bitmap = image,
            contentDescription = "Preview",
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

        DrawingLayer(
            state = state,
            image = image,
            bounds = bounds,
            trace = trace,
            touch = touch,
            stageSize = stageSize,
            modifier = Modifier.fillMaxSize(),
        )

        // Controls put away, but a crop already set: show what will be kept, without the grips that
        // would invite a drag nothing is listening for. It takes no touches, so it doesn't stand
        // between the gesture surface and the photo.
        if (state.showsCropPreview) {
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
                modifier = Modifier.fillMaxSize(),
            )
            return@Box
        }

        // The gesture block outlives recompositions, so these are read through the latest state
        // rather than captured — otherwise a pan would be mapped against the bounds from before it.
        val latestBounds by rememberUpdatedState(bounds)
        val latestHandles by rememberUpdatedState(handlesOf(state))
        val canSelect = state.canSelect
        val tool = state.selectionTool

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // A zoomed-in canvas with no way back is a trap.
                    detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
                }
                .pointerInput(stageSize, canvas.width, canvas.height, canSelect, tool) {
                    val handleRadius = HANDLE_TOUCH_RADIUS.dp.toPx()
                    val minStep = 3.dp.toPx()

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

                        if (canSelect) {
                            if (tool != SelectionTool.Brush) {
                                grabbed = handleAt(latestHandles, first.position, latestBounds, handleRadius)
                            }
                            touch = first.position
                            when {
                                grabbed >= 0 -> Unit
                                tool == SelectionTool.Lasso -> {
                                    tracing = true
                                    trace = listOf(first.position)
                                }
                                tool == SelectionTool.Gradient -> {
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
    // Mask compares by content, so the contour is only retraced when the area actually changes.
    val contour = remember(mask) {
        if (mask == null || mask.path != null) emptyList() else MaskOutline.segments(mask)
    }
    val handles = handlesOf(state)
    val gradient = mask?.gradient

    Canvas(modifier = modifier) {
        if (bounds.width <= 0f || bounds.height <= 0f) return@Canvas

        fun onScreen(point: MaskPoint) =
            Offset(bounds.left + point.x * bounds.width, bounds.top + point.y * bounds.height)

        // A mask that still has its polygon is drawn from it directly — exact, with no grid in the
        // way. Anything brushed or combined falls back to the traced contour.
        val outline = Path()
        val path = mask?.path
        if (path != null && path.size >= 2) {
            val start = onScreen(path.first())
            outline.moveTo(start.x, start.y)
            path.drop(1).forEach { point ->
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
        if (gradient != null) {
            val from = onScreen(gradient.start)
            val to = onScreen(gradient.end)
            drawLine(Color(0xCC000000), from, to, 3.dp.toPx())
            drawLine(
                color = Brand,
                start = from,
                end = to,
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)),
            )
        }

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

/**
 * The draggable points of whatever describes the active area: a lasso's simplified vertices, or a
 * gradient's strong and far ends. A brushed area has neither, since no shape describes it.
 */
private fun handlesOf(state: PerfectEditUiState): List<MaskPoint> {
    if (!state.canSelect || state.selectionTool == SelectionTool.Brush) return emptyList()
    val mask = state.activeMask ?: return emptyList()
    mask.gradient?.let { return listOf(it.start, it.end) }
    return mask.path.orEmpty()
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
) {
    val layer = state.document.selected

    // How an area gets chosen, first — drawing comes before there is anything to apply.
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackToMenu(onClick = viewModel::onBackToMenu)
        SelectionChips(
            state = state,
            viewModel = viewModel,
            layer = layer,
            modifier = Modifier.weight(1f),
        )
    }

    // Only while the gradient tool is in hand — five more chips permanently on screen would undo
    // the height the photo was given.
    if (state.selectionTool == SelectionTool.Gradient) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(GradientStyle.entries.toList(), key = { it.name }) { style ->
                PanelChip(label = style.label, isSelected = style == state.gradientStyle) {
                    viewModel.onSelectGradientStyle(style)
                }
            }
        }
    }

    if (addingEffect) {
        EffectPickerRow(
            onPick = { kind ->
                onAddingEffect(false)
                viewModel.onAddEffect(kind)
            },
            onCancel = { onAddingEffect(false) },
        )
    } else {
        LayerRow(
            state = state,
            onAdd = { onAddingEffect(true) },
            onSelectLayer = viewModel::onSelectLayer,
            onToggleVisible = viewModel::onToggleLayerVisibility,
        )
    }

    if (layer == null) {
        if (state.selectionTool == SelectionTool.Brush) {
            ValueSlider(
                value = state.brushRadius,
                range = 0.02f..0.5f,
                readout = "${(state.brushRadius * 100).roundToInt()}",
                onChange = viewModel::onBrushRadius,
            )
        }
        // An area can be softened before its effect is chosen, the same as after.
        if (state.pendingSelection != null) {
            FeatherSlider(state = state, viewModel = viewModel)
        }
        Text(
            text = if (state.pendingSelection == null) {
                if (state.selectionTool == SelectionTool.Gradient) {
                    "Drag across the photo, then add an effect to fade it in along the gradient."
                } else {
                    "Draw around an area, then add an effect to apply it only there."
                }
            } else {
                "Area ready — add an effect and it applies only there."
            },
            color = Color(0xAAFFFFFF),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        return
    }

    val controls = LayerControl.forLayer(layer, state.selectionTool)
    val control = if (state.control in controls) state.control else controls.first()

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(controls, key = { it.name }) { candidate ->
            PanelChip(label = candidate.label, isSelected = candidate == control) {
                viewModel.onSelectControl(candidate)
            }
        }
    }

    ControlSlider(layer = layer, control = control, state = state, viewModel = viewModel)

    if (layer is Layer.Gradient &&
        (control == LayerControl.ColourFrom || control == LayerControl.ColourTo)
    ) {
        val atStart = control == LayerControl.ColourFrom
        val colour = if (atStart) layer.from else layer.to
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(ColourTone.entries.toList(), key = { it.name }) { tone ->
                PanelChip(label = tone.label, isSelected = tone == colour.tone) {
                    viewModel.onGradientTone(layer.id, atStart, tone)
                }
            }
        }
    }

    // Beside the falloff slider, since both are about the gradient's shape — and because a second
    // permanent chip row would give back the height the photo was given.
    if (layer is Layer.Gradient && control == LayerControl.Falloff) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(GradientStyle.entries.toList(), key = { it.name }) { style ->
                PanelChip(label = style.label, isSelected = style == layer.spec.style) {
                    viewModel.onGradientLayerSpec(layer.id) { it.copy(style = style) }
                }
            }
        }
    }
}

/** The single slider, showing whichever property the chips selected. */
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
            val colour = if (atStart) layer.from else layer.to
            ValueSlider(
                value = colour.hue,
                range = 0f..360f,
                readout = if (colour.tone == ColourTone.Hue) "${colour.hue.roundToInt()}°" else colour.tone.label,
                onChange = { viewModel.onGradientHue(layer.id, atStart, it) },
                onChangeFinished = viewModel::commitLayerEdit,
            )
        }

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

        control == LayerControl.BrushSize -> ValueSlider(
            value = state.brushRadius,
            range = 0.02f..0.5f,
            readout = "${(state.brushRadius * 100).roundToInt()}",
            onChange = viewModel::onBrushRadius,
        )

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
 * How an area is chosen, and what to do with the layer. Blend cycles through its modes on tap
 * rather than opening a menu — a menu would need somewhere to appear, and that somewhere is the
 * photo.
 */
@Composable
private fun SelectionChips(
    state: PerfectEditUiState,
    viewModel: PerfectEditorViewModel,
    layer: Layer?,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(SelectionTool.entries.toList(), key = { it.name }) { tool ->
            PanelChip(label = tool.label, isSelected = tool == state.selectionTool) {
                viewModel.onSelectionTool(tool)
            }
        }
        item {
            PanelChip(
                label = state.selectionMode.label,
                isSelected = state.selectionMode != SelectionMode.Replace,
                onClick = viewModel::onCycleSelectionMode,
            )
        }
        if (layer != null) {
            item { PanelChip(label = layer.blend.label) { viewModel.onCycleBlend(layer.id) } }
            item { PanelChip(label = "Up") { viewModel.onMoveLayer(layer.id, up = true) } }
            item { PanelChip(label = "Down") { viewModel.onMoveLayer(layer.id, up = false) } }
            item { PanelChip(label = "Delete") { viewModel.onRemoveLayer(layer.id) } }
        }
    }
}

/**
 * The stack, said out loud: the word "Layers", a button that names what it does, then a chip per
 * layer with the newest on top.
 *
 * Every effect has been its own layer with its own area since the layer engine landed. A sparkle
 * icon and unlabelled chips just never said so, which made a capability that already existed look
 * like one that didn't.
 */
@Composable
private fun LayerRow(
    state: PerfectEditUiState,
    onAdd: () -> Unit,
    onSelectLayer: (Long) -> Unit,
    onToggleVisible: (Long) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "Layers", color = Color(0xAAFFFFFF), fontSize = 10.sp)
        PanelChip(label = "+ New", isSelected = true, onClick = onAdd)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.document.topDown, key = { it.id }) { layer ->
                LayerChip(
                    layer = layer,
                    isSelected = layer.id == state.document.selectedId,
                    onSelect = { onSelectLayer(layer.id) },
                    onToggleVisible = { onToggleVisible(layer.id) },
                )
            }
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

/** How softly the effect stops at the area's edge, for whichever area is live. */
@Composable
private fun FeatherSlider(state: PerfectEditUiState, viewModel: PerfectEditorViewModel) {
    val feather = state.activeMask?.feather ?: return
    ValueSlider(
        value = feather,
        range = 0f..1f,
        readout = "${(feather * 100).roundToInt()}",
        onChange = viewModel::onFeather,
        onChangeFinished = viewModel::commitLayerEdit,
    )
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
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
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
private fun LayerChip(
    layer: Layer,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggleVisible: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) Brand else Color(0x22FFFFFF))
            .border(
                width = 1.dp,
                color = if (isSelected) Brand else Color(0x33FFFFFF),
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onSelect)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Text(
            text = layer.name,
            color = if (layer.isVisible) Color.White else Color(0x77FFFFFF),
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
        )
        IconButton(onClick = onToggleVisible, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = if (layer.isVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = if (layer.isVisible) "Hide layer" else "Show layer",
                tint = if (layer.isVisible) Color.White else Color(0x77FFFFFF),
                modifier = Modifier.size(16.dp),
            )
        }
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
private fun StraightenSlider(degrees: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Straighten", color = Color(0xCCFFFFFF), fontSize = 11.sp)
        Slider(
            value = degrees,
            onValueChange = onChange,
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
