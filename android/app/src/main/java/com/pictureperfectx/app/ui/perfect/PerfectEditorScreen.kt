package com.pictureperfectx.app.ui.perfect

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pictureperfectx.app.capture.AspectRatio
import com.pictureperfectx.app.capture.CropMath
import com.pictureperfectx.app.capture.ToneAdjustments
import com.pictureperfectx.app.capture.ToneBand
import com.pictureperfectx.app.layers.BlendMode
import com.pictureperfectx.app.layers.Layer
import com.pictureperfectx.app.ui.components.CameraNotice
import kotlin.math.min
import kotlin.math.roundToInt

private val Brand = Color(0xFFFF4D6D)

/**
 * The Perfect Editor's first surface: geometry. Crop with a draggable frame, lock to an aspect
 * ratio, straighten, rotate in quarter turns and flip — then save as a **new** photo.
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

    LaunchedEffect(sourceUri) { viewModel.load(sourceUri) }
    LaunchedEffect(state.savedMessage) {
        state.savedMessage?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }
    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White)
                }
                Text(
                    text = "Perfect Editor",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::onUndo, enabled = state.canUndo) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo",
                        tint = if (state.canUndo) Color.White else Color(0x55FFFFFF),
                    )
                }
                IconButton(onClick = viewModel::onRedo, enabled = state.canRedo) {
                    Icon(
                        Icons.AutoMirrored.Filled.Redo,
                        contentDescription = "Redo",
                        tint = if (state.canRedo) Color.White else Color(0x55FFFFFF),
                    )
                }
                IconButton(onClick = viewModel::onReset, enabled = state.ready) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reset", tint = Color.White)
                }
                if (state.isSaving) {
                    CircularProgressIndicator(
                        color = Brand,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp).padding(end = 8.dp),
                    )
                } else {
                    IconButton(onClick = { viewModel.save(onSaved) }, enabled = state.ready) {
                        Icon(Icons.Filled.Check, contentDescription = "Save", tint = Brand)
                    }
                }
            }

            CropStage(
                state = state,
                onCropChanged = viewModel::onCropChanged,
                onPaint = viewModel::onPaintMask,
                onStrokeEnd = viewModel::endStroke,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.notice?.let { CameraNotice(text = it, onDismiss = viewModel::consumeNotice) }

                ToolSwitch(selected = state.tool, onSelect = viewModel::onSelectTool)

                when (state.tool) {
                    PerfectTool.Crop -> {
                        StraightenSlider(
                            degrees = state.geometry.straightenDegrees,
                            onChange = viewModel::onStraighten,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            ToolButton(Icons.Filled.RotateLeft, "Rotate left") { viewModel.onRotate(false) }
                            ToolButton(Icons.Filled.RotateRight, "Rotate right") { viewModel.onRotate(true) }
                            ToolButton(Icons.Filled.Flip, "Flip horizontally") { viewModel.onFlip(true) }
                            ToolButton(
                                icon = Icons.Filled.Flip,
                                description = "Flip vertically",
                                rotate = 90f,
                            ) { viewModel.onFlip(false) }
                        }
                        AspectRow(selected = state.geometry.aspect, onSelect = viewModel::onAspectSelected)
                    }

                    PerfectTool.Tone -> TonePanel(
                        tone = state.tone,
                        band = state.band,
                        onSelectBand = viewModel::onSelectBand,
                        onChange = { value -> viewModel.onToneChanged(state.band, value) },
                    )

                    PerfectTool.Layers -> LayersPanel(state = state, viewModel = viewModel)
                }
            }
        }
    }
}

/** The image plus its crop frame. The frame is positioned against where the image actually lands. */
@Composable
private fun CropStage(
    state: PerfectEditUiState,
    onCropChanged: (com.pictureperfectx.app.capture.CropRect) -> Unit,
    onPaint: (Float, Float) -> Unit,
    onStrokeEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var stageSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
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
            modifier = Modifier.fillMaxSize().padding(12.dp),
        )

        // ContentScale.Fit letterboxes, so work out the drawn rectangle to anchor the crop frame.
        val bounds = remember(stageSize, canvas.width, canvas.height) {
            fittedBounds(
                containerWidth = stageSize.width.toFloat(),
                containerHeight = stageSize.height.toFloat(),
                imageWidth = canvas.width.toFloat(),
                imageHeight = canvas.height.toFloat(),
                inset = 12f,
            )
        }

        when {
            // Painting takes over the drag gesture rather than competing with the crop frame.
            state.canPaintMask -> MaskPaintSurface(
                imageBounds = bounds,
                onPaint = onPaint,
                onStrokeEnd = onStrokeEnd,
                modifier = Modifier.fillMaxSize(),
            )

            // The crop frame would only get in the way while judging tone, so it's crop-mode only.
            state.tool == PerfectTool.Crop -> CropOverlay(
                crop = state.geometry.crop,
                imageBounds = bounds,
                lockedRatio = state.geometry.aspect.ratio(state.canvasRatio),
                sourceRatio = state.canvasRatio,
                onCropChanged = onCropChanged,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Turns drags into mask dabs. Touch points are converted against [imageBounds] — where the photo
 * actually sits after letterboxing — rather than the composable's own size, so a stroke lands under
 * the finger instead of being offset by the empty margins.
 */
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

/** Switches the bottom controls between the geometry tools and the tonal ones. */
@Composable
private fun ToolSwitch(selected: PerfectTool, onSelect: (PerfectTool) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PerfectTool.entries.forEach { tool ->
            val isSelected = tool == selected
            Text(
                text = tool.label,
                color = if (isSelected) Color.White else Color(0xCCFFFFFF),
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) Brand else Color(0x22FFFFFF))
                    .clickable { onSelect(tool) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

/**
 * Blacks / shadows / highlights / whites: chips to choose a band, one slider for it. Matches how
 * the camera and light editor present adjustments, and keeps the photo visible.
 */
@Composable
private fun TonePanel(
    tone: ToneAdjustments,
    band: ToneBand,
    onSelectBand: (ToneBand) -> Unit,
    onChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x59000000))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ToneBand.entries.forEach { candidate ->
                val isSelected = candidate == band
                Text(
                    text = candidate.label,
                    color = if (isSelected) Color.White else Color(0xCCFFFFFF),
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) Brand else Color(0x22FFFFFF))
                        .clickable { onSelectBand(candidate) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        val value = tone.valueOf(band)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange(it.roundToInt()) },
                valueRange = -100f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = Brand,
                    activeTrackColor = Brand,
                    inactiveTrackColor = Color(0x55FFFFFF),
                ),
                modifier = Modifier.weight(1f).height(26.dp),
            )
            Text(
                text = "$value",
                color = Brand,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

/**
 * The layer stack. Listed **top-down** — the document stores bottom-first so index 0 sits nearest
 * the photo, and showing it unreversed would read upside down.
 */
@Composable
private fun LayersPanel(state: PerfectEditUiState, viewModel: PerfectEditorViewModel) {
    val selected = state.document.selected

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x59000000))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelChip("+ Tone", onClick = viewModel::onAddToneLayer)
            PanelChip("+ Blur", onClick = viewModel::onAddBlurLayer)
        }

        if (state.document.isEmpty) {
            Text(
                text = "Add a layer, then paint on the photo to choose where it applies.",
                color = Color(0xAAFFFFFF),
                fontSize = 11.sp,
            )
            return@Column
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.document.topDown, key = { it.id }) { layer ->
                LayerChip(
                    layer = layer,
                    isSelected = layer.id == selected?.id,
                    onSelect = { viewModel.onSelectLayer(layer.id) },
                    onToggleVisible = { viewModel.onToggleLayerVisibility(layer.id) },
                )
            }
        }

        selected?.let { layer ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Opacity", color = Color(0xCCFFFFFF), fontSize = 11.sp)
                Slider(
                    value = layer.opacity,
                    onValueChange = { viewModel.onLayerOpacity(layer.id, it) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = Brand,
                        activeTrackColor = Brand,
                        inactiveTrackColor = Color(0x55FFFFFF),
                    ),
                    modifier = Modifier.weight(1f).height(26.dp).padding(horizontal = 8.dp),
                )
                PanelChip(if (state.brushErases) "Erase" else "Paint", onClick = viewModel::onToggleBrushErase)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Brush", color = Color(0xCCFFFFFF), fontSize = 11.sp)
                Slider(
                    value = state.brushRadius,
                    onValueChange = viewModel::onBrushRadius,
                    valueRange = 0.02f..0.5f,
                    colors = SliderDefaults.colors(
                        thumbColor = Brand,
                        activeTrackColor = Brand,
                        inactiveTrackColor = Color(0x55FFFFFF),
                    ),
                    modifier = Modifier.weight(1f).height(26.dp).padding(horizontal = 8.dp),
                )
                PanelChip("Up") { viewModel.onMoveLayer(layer.id, up = true) }
                PanelChip("Down") { viewModel.onMoveLayer(layer.id, up = false) }
                PanelChip("Delete") { viewModel.onRemoveLayer(layer.id) }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(BlendMode.entries.toList(), key = { it.name }) { mode ->
                    PanelChip(
                        label = mode.label,
                        isSelected = mode == layer.blend,
                        onClick = { viewModel.onLayerBlend(layer.id, mode) },
                    )
                }
            }
        }
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
            fontSize = 12.sp,
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
        fontSize = 11.sp,
        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) Brand else Color(0x22FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun StraightenSlider(degrees: Float, onChange: (Float) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x59000000))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Straighten", color = Color(0xCCFFFFFF), fontSize = 12.sp)
            Slider(
                value = degrees,
                onValueChange = onChange,
                valueRange = -CropMath.MAX_STRAIGHTEN_DEGREES..CropMath.MAX_STRAIGHTEN_DEGREES,
                colors = SliderDefaults.colors(
                    thumbColor = Brand,
                    activeTrackColor = Brand,
                    inactiveTrackColor = Color(0x55FFFFFF),
                ),
                modifier = Modifier.weight(1f).height(26.dp).padding(horizontal = 10.dp),
            )
            Text(
                text = "${degrees.roundToInt()}°",
                color = Brand,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(AspectRatio.entries.toList(), key = { it.name }) { aspect ->
            val isSelected = aspect == selected
            Text(
                text = aspect.label,
                color = if (isSelected) Color.White else Color(0xCCFFFFFF),
                fontSize = 12.sp,
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
