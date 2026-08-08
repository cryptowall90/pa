package com.pictureperfectx.app.ui.perfect

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pictureperfectx.app.capture.AspectRatio
import com.pictureperfectx.app.capture.CropMath
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
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.notice?.let { CameraNotice(text = it, onDismiss = viewModel::consumeNotice) }

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
        }
    }
}

/** The image plus its crop frame. The frame is positioned against where the image actually lands. */
@Composable
private fun CropStage(
    state: PerfectEditUiState,
    onCropChanged: (com.pictureperfectx.app.capture.CropRect) -> Unit,
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

        CropOverlay(
            crop = state.geometry.crop,
            imageBounds = bounds,
            lockedRatio = state.geometry.aspect.ratio(state.canvasRatio),
            sourceRatio = state.canvasRatio,
            onCropChanged = onCropChanged,
            modifier = Modifier.fillMaxSize(),
        )
    }
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
