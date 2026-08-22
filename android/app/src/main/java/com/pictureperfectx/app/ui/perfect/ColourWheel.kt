package com.pictureperfectx.app.ui.perfect

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pictureperfectx.app.layers.ColourTone
import com.pictureperfectx.app.layers.GradientColour
import com.pictureperfectx.app.layers.hsvToRgb
import com.pictureperfectx.app.layers.toArgb
import com.pictureperfectx.app.ui.theme.Brand
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Small enough to leave the photo the screen, big enough for a fingertip to aim inside. */
private val WHEEL_SIZE = 104.dp

/**
 * A colour wheel: hue round it, saturation out from the middle, brightness on a slider beside it.
 *
 * What it replaces was a hue slider and nothing else, so every colour the editor could produce was
 * fully saturated and fully bright. There was no dusty rose, no navy, no charcoal — the palette was
 * a rainbow, which is almost never what anyone wants on a photograph.
 *
 * A wheel rather than three sliders because it is a direct drawing of the model: the angle *is* the
 * hue and the distance out *is* the saturation, so one finger reaches every variation of a colour
 * at once instead of hunting for it on two separate tracks.
 *
 * Touching it commits to [ColourTone.Hue]. The Black and White chips stay beside it — reachable on
 * the wheel in principle, but nobody should have to aim for an exact corner of a picker to get the
 * two colours captions are usually set in.
 */
@Composable
fun ColourWheel(
    colour: GradientColour,
    onPick: (GradientColour) -> Unit,
    onPicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Recomposition happens on every frame of a drag, and the gesture block outlives all of them —
    // so it reads the latest callbacks through these rather than closing over the first ones.
    val latest = rememberUpdatedState(colour)
    val pick = rememberUpdatedState(onPick)
    val picked = rememberUpdatedState(onPicked)

    // A full turn, closed on red at both ends so the wheel has no seam where the sweep restarts.
    val ring = remember {
        (0..12).map { step -> Color(0xFF000000.toInt() or hsvToRgb(step * 30f)) }
    }
    val swatch = remember(colour) { Color(colour.toArgb()) }
    // Black and White are set by chip, not by the wheel, so the wheel steps back rather than
    // claiming a thumb position that isn't what the layer is wearing.
    val inUse = colour.tone == ColourTone.Hue

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Canvas(
            modifier = Modifier
                .size(WHEEL_SIZE)
                .pointerInput(Unit) {
                    fun pickAt(position: Offset) {
                        val radius = minOf(size.width, size.height) / 2f
                        if (radius <= 0f) return
                        val dx = position.x - size.width / 2f
                        val dy = position.y - size.height / 2f
                        // Screen y grows downwards and a sweep gradient also runs clockwise from
                        // three o'clock, so this angle and the colour under the finger agree.
                        val degrees = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                        pick.value(
                            latest.value.copy(
                                hue = ((degrees % 360f) + 360f) % 360f,
                                saturation = (hypot(dx, dy) / radius).coerceIn(0f, 1f),
                                tone = ColourTone.Hue,
                            ),
                        )
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pickAt(down.position)
                        down.consume()
                        // No slop: a tap on the wheel is as much a choice as a drag across it.
                        drag(down.id) { change ->
                            pickAt(change.position)
                            change.consume()
                        }
                        // One gesture is one undo step, the same as a slider drag.
                        picked.value()
                    }
                },
        ) {
            val radius = size.minDimension / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Brush.sweepGradient(ring, centre), radius, centre)
            // Saturation is the distance out, so the middle washes to white.
            drawCircle(
                Brush.radialGradient(listOf(Color.White, Color.Transparent), centre, radius),
                radius,
                centre,
            )
            // The brightness slider drawn onto the wheel, so the disc shows the colours actually
            // on offer rather than a bright ring that lies about what a tap will produce.
            val dim = if (inUse) 1f - colour.value.coerceIn(0f, 1f) else 0.55f
            if (dim > 0f) drawCircle(Color.Black.copy(alpha = dim), radius, centre)

            if (inUse) {
                val radians = Math.toRadians(colour.hue.toDouble())
                val reach = colour.saturation.coerceIn(0f, 1f) * radius
                val thumb = centre + Offset(
                    (cos(radians) * reach).toFloat(),
                    (sin(radians) * reach).toFloat(),
                )
                // Two rings, light over dark, so the thumb stays findable over both a pale middle
                // and a saturated rim.
                drawCircle(Color.Black.copy(alpha = 0.5f), 8.dp.toPx(), thumb, style = Stroke(3f))
                drawCircle(Color.White, 8.dp.toPx(), thumb, style = Stroke(4f))
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // The colour as it will actually land, tone and all — the one thing on this control
            // that is never a guess about what the wheel means.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.size(18.dp)) {
                    drawCircle(swatch)
                    drawCircle(Color(0x55FFFFFF), style = Stroke(2f))
                }
                Text(
                    text = if (inUse) "${colour.hue.roundToInt()}°" else colour.tone.label,
                    color = Brand,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(text = "Brightness", color = Color(0xAAFFFFFF), fontSize = 10.sp)
            Slider(
                value = if (inUse) colour.value.coerceIn(0f, 1f) else 1f,
                // Moving it is a colour choice too, so it takes the wheel back off Black or White.
                onValueChange = { onPick(colour.copy(value = it, tone = ColourTone.Hue)) },
                onValueChangeFinished = onPicked,
                colors = SliderDefaults.colors(
                    thumbColor = Brand,
                    activeTrackColor = Brand,
                    inactiveTrackColor = Color(0x55FFFFFF),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
