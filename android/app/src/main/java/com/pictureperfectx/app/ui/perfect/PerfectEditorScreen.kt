package com.pictureperfectx.app.ui.perfect

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pictureperfectx.app.capture.AspectRatio
import com.pictureperfectx.app.capture.CropMath
import com.pictureperfectx.app.capture.CropRect
import com.pictureperfectx.app.layers.Layer
import com.pictureperfectx.app.layers.Mask
import com.pictureperfectx.app.layers.MaskOutline
import com.pictureperfectx.app.layers.MaskPoint
import com.pictureperfectx.app.layers.SelectionMode
import com.pictureperfectx.app.ui.components.CameraNotice
import kotlin.math.min
import kotlin.math.roundToInt

private val Brand = Color(0xFFFF4D6D)

/** Margin around the photo, in dp. Small — the picture is what the screen is for. */
private const val STAGE_INSET = 4

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
 * The photo, plus whichever overlay the current tool needs: the crop frame, or a surface for
 * drawing the area an effect applies to.
 */
@Composable
private fun EditorStage(
    state: PerfectEditUiState,
    onCropChanged: (CropRect) -> Unit,
    onPaint: (Float, Float) -> Unit,
    onStrokeEnd: () -> Unit,
    onLasso: (List<MaskPoint>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var stageSize by remember { mutableStateOf(IntSize.Zero) }
    val canvas = state.canvas

    Box(
        modifier = modifier.onSizeChanged { stageSize = it },
        contentAlignment = Alignment.Center,
    ) {
        if (canvas == null) {
            CircularProgressIndicator(color = Brand)
            return@Box
        }

        Image(
            bitmap = canvas.asImageBitmap(),
            contentDescription = "Preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(STAGE_INSET.dp),
        )

        // ContentScale.Fit letterboxes, so work out the drawn rectangle to anchor everything else.
        // The inset here has to match the padding above or every touch lands slightly off.
        val insetPx = with(LocalDensity.current) { STAGE_INSET.dp.toPx() }
        val bounds = remember(stageSize, canvas.width, canvas.height, insetPx) {
            fittedBounds(
                containerWidth = stageSize.width.toFloat(),
                containerHeight = stageSize.height.toFloat(),
                imageWidth = canvas.width.toFloat(),
                imageHeight = canvas.height.toFloat(),
                inset = insetPx,
            )
        }

        // The area is outlined rather than filled. A fill sits over exactly the pixels whose change
        // the user is judging, which makes it useless the moment an adjustment starts.
        if (state.canSelect) {
            state.activeMask?.let { mask ->
                SelectionOutline(mask = mask, imageBounds = bounds, modifier = Modifier.fillMaxSize())
            }
        }

        when {
            state.canSelect && state.selectionTool == SelectionTool.Lasso -> LassoSurface(
                imageBounds = bounds,
                onCommit = onLasso,
                modifier = Modifier.fillMaxSize(),
            )

            // Painting takes over the drag gesture rather than competing with the crop frame.
            state.canSelect -> MaskPaintSurface(
                imageBounds = bounds,
                onPaint = onPaint,
                onStrokeEnd = onStrokeEnd,
                modifier = Modifier.fillMaxSize(),
            )

            // The crop frame would only get in the way while judging an effect, so it's crop-only.
            state.panel == EditorPanel.Crop -> CropOverlay(
                crop = state.geometry.crop,
                imageBounds = bounds,
                lockedRatio = state.geometry.aspect.ratio(state.canvasRatio),
                sourceRatio = state.canvasRatio,
                onCropChanged = onCropChanged,
                modifier = Modifier.fillMaxSize(),
            )

            // Controls put away, but a crop already set: show what will be kept, without the grips
            // that would invite a drag nothing is listening for.
            state.showsCropPreview -> CropOverlay(
                crop = state.geometry.crop,
                imageBounds = bounds,
                lockedRatio = null,
                sourceRatio = state.canvasRatio,
                onCropChanged = {},
                modifier = Modifier.fillMaxSize(),
                interactive = false,
            )
        }
    }
}

/**
 * Draws the boundary of the chosen area, leaving the pixels inside it alone.
 *
 * The line is stroked twice — a dark underlay then a light line over it — so it stays legible
 * against a bright sky and a dark shadow without needing to animate.
 */
@Composable
private fun SelectionOutline(mask: Mask, imageBounds: Rect, modifier: Modifier = Modifier) {
    // Mask compares by content, so this only recomputes when the area actually changes.
    val edges = remember(mask) { MaskOutline.segments(mask) }
    if (edges.isEmpty()) return

    Canvas(modifier = modifier) {
        if (imageBounds.width <= 0f || imageBounds.height <= 0f) return@Canvas
        val outline = Path()
        edges.forEach { edge ->
            outline.moveTo(
                imageBounds.left + edge.x0 * imageBounds.width,
                imageBounds.top + edge.y0 * imageBounds.height,
            )
            outline.lineTo(
                imageBounds.left + edge.x1 * imageBounds.width,
                imageBounds.top + edge.y1 * imageBounds.height,
            )
        }
        drawPath(outline, color = Color(0xCC000000), style = Stroke(width = 3.dp.toPx()))
        drawPath(outline, color = Color.White, style = Stroke(width = 1.5.dp.toPx()))
    }
}

/**
 * Draws a freehand loop and hands back the closed path.
 *
 * Points are converted against [imageBounds] — where the photo actually sits after letterboxing —
 * rather than the composable's own size, so the outline lands under the finger instead of being
 * offset by the empty margins.
 */
@Composable
private fun LassoSurface(
    imageBounds: Rect,
    onCommit: (List<MaskPoint>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var path by remember { mutableStateOf(emptyList<Offset>()) }

    Box(
        modifier = modifier.pointerInput(imageBounds) {
            if (imageBounds.width <= 0f || imageBounds.height <= 0f) return@pointerInput
            // Sampling every touch event would put hundreds of near-identical points in the path
            // for no extra accuracy; a couple of dp between them is plenty.
            val minStep = 3.dp.toPx()
            detectDragGestures(
                onDragStart = { position -> path = listOf(position) },
                onDragEnd = {
                    val drawn = path
                    path = emptyList()
                    onCommit(
                        drawn.map { point ->
                            MaskPoint(
                                x = (point.x - imageBounds.left) / imageBounds.width,
                                y = (point.y - imageBounds.top) / imageBounds.height,
                            )
                        },
                    )
                },
                onDragCancel = { path = emptyList() },
            ) { change, _ ->
                change.consume()
                val last = path.lastOrNull()
                if (last == null || (change.position - last).getDistance() >= minStep) {
                    path = path + change.position
                }
            }
        },
    ) {
        val drawn = path
        if (drawn.size >= 2) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val outline = Path().apply {
                    moveTo(drawn.first().x, drawn.first().y)
                    drawn.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(
                    path = outline,
                    color = Brand,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
                // The dashed run back to the start shows how the loop will close on release.
                drawLine(
                    color = Color.White,
                    start = drawn.last(),
                    end = drawn.first(),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }
        }
    }
}

/** Turns drags into mask dabs, in the same normalized space the lasso uses. */
@Composable
private fun MaskPaintSurface(
    imageBounds: Rect,
    onPaint: (Float, Float) -> Unit,
    onStrokeEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.pointerInput(imageBounds) {
            if (imageBounds.width <= 0f || imageBounds.height <= 0f) return@pointerInput
            fun emit(position: Offset) {
                val x = (position.x - imageBounds.left) / imageBounds.width
                val y = (position.y - imageBounds.top) / imageBounds.height
                if (x in 0f..1f && y in 0f..1f) onPaint(x, y)
            }
            detectDragGestures(
                onDragStart = { position -> emit(position) },
                onDragEnd = onStrokeEnd,
                onDragCancel = onStrokeEnd,
            ) { change, _ ->
                change.consume()
                emit(change.position)
            }
        },
    )
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
                "Draw around an area, then add an effect to apply it only there."
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

/** The stack itself: add on the left, then a chip per layer, newest on top first. */
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
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconButton(onClick = onAdd, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = "Add an effect", tint = Brand)
        }
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
