package com.pictureperfectx.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pictureperfectx.app.ui.camera.Adjustment
import com.pictureperfectx.app.ui.camera.CameraUiState

private val Brand = Color(0xFFFF4D6D)

/**
 * Compact manual-adjustment control: a row of selectable chips (Exposure / Brightness / Contrast /
 * Saturation) with a **single** slider underneath for the chosen one, so the camera stays visible.
 */
@Composable
fun AdjustPanel(
    state: CameraUiState,
    onSelect: (Adjustment) -> Unit,
    onExposure: (Int) -> Unit,
    onBrightness: (Int) -> Unit,
    onContrast: (Int) -> Unit,
    onSaturation: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = buildList {
        if (state.exposureSupported) add(Adjustment.Exposure)
        add(Adjustment.Brightness)
        add(Adjustment.Contrast)
        add(Adjustment.Saturation)
    }
    val selected = if (state.selectedAdjustment in chips) state.selectedAdjustment else Adjustment.Brightness

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x59000000))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            chips.forEach { adj ->
                Chip(
                    label = adj.label,
                    selected = adj == selected,
                    onClick = { onSelect(adj) },
                )
            }
        }

        when (selected) {
            Adjustment.Exposure -> ValueSlider(
                value = state.exposure,
                range = state.exposureMin.toFloat()..state.exposureMax.toFloat(),
                onChange = onExposure,
            )
            Adjustment.Brightness -> ValueSlider(state.brightness, -100f..100f, onBrightness)
            Adjustment.Contrast -> ValueSlider(state.contrast, -100f..100f, onContrast)
            Adjustment.Saturation -> ValueSlider(state.saturation, -100f..100f, onSaturation)
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color.White else Color(0xCCFFFFFF),
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Brand else Color(0x22FFFFFF))
            .border(
                width = 1.dp,
                color = if (selected) Brand else Color(0x33FFFFFF),
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun ValueSlider(
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Slider(
            value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Brand,
                activeTrackColor = Brand,
                inactiveTrackColor = Color(0x55FFFFFF),
            ),
            modifier = Modifier.weight(1f),
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
