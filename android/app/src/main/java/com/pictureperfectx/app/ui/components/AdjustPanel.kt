package com.pictureperfectx.app.ui.components

import androidx.compose.foundation.background
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
import com.pictureperfectx.app.ui.camera.CameraUiState

private val Brand = Color(0xFFFF4D6D)

/**
 * Collapsible manual-adjustment panel: exposure (hardware EV, when supported) plus brightness,
 * contrast and saturation (applied live in the preview shader and mirrored on capture).
 */
@Composable
fun AdjustPanel(
    state: CameraUiState,
    onExposure: (Int) -> Unit,
    onBrightness: (Int) -> Unit,
    onContrast: (Int) -> Unit,
    onSaturation: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x40000000))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (state.exposureSupported) {
            LabeledSlider(
                label = "Exposure",
                value = state.exposure,
                range = state.exposureMin.toFloat()..state.exposureMax.toFloat(),
                onChange = onExposure,
            )
        }
        LabeledSlider("Brightness", state.brightness, -100f..100f, onBrightness)
        LabeledSlider("Contrast", state.contrast, -100f..100f, onContrast)
        LabeledSlider("Saturation", state.saturation, -100f..100f, onSaturation)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Int) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(text = "$value", color = Brand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Brand,
                activeTrackColor = Brand,
                inactiveTrackColor = Color(0x55FFFFFF),
            ),
        )
    }
}
