package com.pictureperfectx.app.capture

import com.pictureperfectx.app.ui.perfect.LayerControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slider-to-shader mapping is the part of the tone tools that can run in CI. Getting it wrong
 * shows up as a control that does nothing, or one that saturates halfway along its travel.
 */
class ToneAdjustmentsTest {

    @Test
    fun `a fresh set of adjustments is neutral`() {
        assertTrue(ToneAdjustments().isNeutral)
    }

    @Test
    fun `any non-zero band breaks neutrality`() {
        ToneBand.entries.forEach { band ->
            assertFalse(
                "${band.label} should count as an adjustment",
                ToneAdjustments().with(band, 25).isNeutral,
            )
        }
    }

    @Test
    fun `each band reads back the value written to it`() {
        ToneBand.entries.forEach { band ->
            assertEquals(42, ToneAdjustments().with(band, 42).valueOf(band))
        }
    }

    @Test
    fun `setting one band leaves the others alone`() {
        val tone = ToneAdjustments().with(ToneBand.Shadows, 60)
        assertEquals(60, tone.shadows)
        assertEquals(0, tone.blacks)
        assertEquals(0, tone.highlights)
        assertEquals(0, tone.whites)
    }

    @Test
    fun `sliders map onto the shader's minus one to one range`() {
        assertEquals(0f, ToneAdjustments.normalize(0), 0.0001f)
        assertEquals(1f, ToneAdjustments.normalize(100), 0.0001f)
        assertEquals(-1f, ToneAdjustments.normalize(-100), 0.0001f)
        assertEquals(0.5f, ToneAdjustments.normalize(50), 0.0001f)
    }

    @Test
    fun `out-of-range values are clamped rather than overdriving the shader`() {
        assertEquals(1f, ToneAdjustments.normalize(500), 0.0001f)
        assertEquals(-1f, ToneAdjustments.normalize(-500), 0.0001f)
    }

    @Test
    fun `normalized reads the band it was asked for`() {
        val tone = ToneAdjustments(blacks = -100, whites = 100)
        assertEquals(-1f, tone.normalized(ToneBand.Blacks), 0.0001f)
        assertEquals(1f, tone.normalized(ToneBand.Whites), 0.0001f)
        assertEquals(0f, tone.normalized(ToneBand.Shadows), 0.0001f)
    }

    @Test
    fun `bands run in the order a photograph is worked`() {
        // Also the order the shader applies them in: overall exposure and contrast, then dark to
        // light, then colour. A control that reads third and applies first behaves oddly.
        assertEquals(
            listOf(
                ToneBand.Exposure,
                ToneBand.Contrast,
                ToneBand.Blacks,
                ToneBand.Shadows,
                ToneBand.Highlights,
                ToneBand.Whites,
                ToneBand.Saturation,
                ToneBand.Vibrance,
                ToneBand.Warmth,
            ),
            ToneBand.entries.toList(),
        )
    }

    @Test
    fun `every band is reachable from a control`() {
        // LayerControl and ToneBand are two lists that have to agree. A band added to the shader
        // but not to the chips would be a slider nobody can reach, and nothing else would fail.
        val reachable = LayerControl.entries.mapNotNull { it.band }
        assertEquals(ToneBand.entries.toList(), reachable)
    }

    @Test
    fun `each control carries its band's own label`() {
        LayerControl.entries.forEach { control ->
            control.band?.let { assertEquals(it.label, control.label) }
        }
    }
}
