package com.pictureperfectx.app.ui.components

import androidx.compose.foundation.background
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

/**
 * 0-100 intensity control for the active LUT. Shows the look's name and the current percentage,
 * and drives [onIntensityChange] live as the user drags.
 */
@Composable
fun IntensitySlider(
    filterName: String,
    intensity: Int,
    onIntensityChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x33000000))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = filterName,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$intensity%",
                color = Color(0xFFFF4D6D),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = intensity.toFloat(),
            onValueChange = { onIntensityChange(it.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFF4D6D),
                activeTrackColor = Color(0xFFFF4D6D),
                inactiveTrackColor = Color(0x55FFFFFF),
            ),
        )
    }
}
