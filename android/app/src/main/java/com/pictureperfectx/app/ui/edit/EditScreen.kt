package com.pictureperfectx.app.ui.edit

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pictureperfectx.app.ui.camera.Adjustment
import com.pictureperfectx.app.ui.components.FilterCarousel
import com.pictureperfectx.app.ui.components.IntensitySlider

private val Brand = Color(0xFFFF4D6D)

/**
 * Full editor: a source photo (a saved capture or a picked device image) with the same looks +
 * tone controls as the camera, rendered live and saved as a **new** photo.
 */
@Composable
fun EditScreen(
    sourceUri: Uri,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: EditViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showOriginal by remember { mutableStateOf(false) }

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
                    text = "Edit",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (state.isSaving) {
                    CircularProgressIndicator(
                        color = Brand, strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp).padding(end = 8.dp),
                    )
                } else {
                    IconButton(onClick = { viewModel.save(onSaved) }, enabled = state.ready) {
                        Icon(Icons.Filled.Check, contentDescription = "Save", tint = Brand)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(state.original) {
                        // Press and hold to compare against the unedited original.
                        detectTapGestures(onPress = {
                            showOriginal = true
                            tryAwaitRelease()
                            showOriginal = false
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                val shown = if (showOriginal) state.original else state.preview
                if (shown != null) {
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = "Preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    )
                } else {
                    CircularProgressIndicator(color = Brand)
                }
                if (showOriginal) {
                    Text(
                        text = "Original",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x66000000))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                } else if (state.ready) {
                    Text(
                        text = "Hold to compare",
                        color = Color(0x99FFFFFF),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EditAdjust(
                    selected = state.selectedAdjustment,
                    exposure = state.exposure,
                    brightness = state.brightness,
                    contrast = state.contrast,
                    saturation = state.saturation,
                    onSelect = viewModel::onSelectAdjustment,
                    onExposure = viewModel::onExposureChanged,
                    onBrightness = viewModel::onBrightnessChanged,
                    onContrast = viewModel::onContrastChanged,
                    onSaturation = viewModel::onSaturationChanged,
                )
                if (state.intensityEnabled) {
                    IntensitySlider(
                        filterName = state.selectedFilter?.displayName.orEmpty(),
                        intensity = state.intensity,
                        onIntensityChange = viewModel::onIntensityChanged,
                    )
                }
                FilterCarousel(
                    filters = state.filters,
                    selectedFilterId = state.selectedFilterId,
                    onFilterSelected = viewModel::onFilterSelected,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Compact exposure/brightness/contrast/saturation control: chip row + single slider. */
@Composable
private fun EditAdjust(
    selected: Adjustment,
    exposure: Int,
    brightness: Int,
    contrast: Int,
    saturation: Int,
    onSelect: (Adjustment) -> Unit,
    onExposure: (Int) -> Unit,
    onBrightness: (Int) -> Unit,
    onContrast: (Int) -> Unit,
    onSaturation: (Int) -> Unit,
) {
    val chips = listOf(Adjustment.Exposure, Adjustment.Brightness, Adjustment.Contrast, Adjustment.Saturation)
    val active = if (selected in chips) selected else Adjustment.Exposure

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
            chips.forEach { adj ->
                Text(
                    text = adj.label,
                    color = if (adj == active) Color.White else Color(0xCCFFFFFF),
                    fontSize = 12.sp,
                    fontWeight = if (adj == active) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (adj == active) Brand else Color(0x22FFFFFF))
                        .border(
                            1.dp,
                            if (adj == active) Brand else Color(0x33FFFFFF),
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelect(adj) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        val value = when (active) {
            Adjustment.Exposure -> exposure
            Adjustment.Contrast -> contrast
            Adjustment.Saturation -> saturation
            else -> brightness
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value.toFloat(),
                onValueChange = {
                    val v = it.toInt()
                    when (active) {
                        Adjustment.Exposure -> onExposure(v)
                        Adjustment.Contrast -> onContrast(v)
                        Adjustment.Saturation -> onSaturation(v)
                        else -> onBrightness(v)
                    }
                },
                valueRange = -100f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = Brand, activeTrackColor = Brand, inactiveTrackColor = Color(0x55FFFFFF),
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
