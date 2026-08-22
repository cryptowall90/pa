package com.pictureperfectx.app.layers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wheel is a direct drawing of this conversion, so a mistake here is a picker whose thumb sits
 * on one colour while the photo shows another — the kind of thing that reads as the control being
 * broken rather than as an arithmetic slip.
 */
class ColourMathTest {

    private fun rgb(hue: Float, saturation: Float = 1f, value: Float = 1f) =
        Triple(
            hsvToRgb(hue, saturation, value) shr 16 and 0xFF,
            hsvToRgb(hue, saturation, value) shr 8 and 0xFF,
            hsvToRgb(hue, saturation, value) and 0xFF,
        )

    @Test
    fun `the primaries land where the wheel says they do`() {
        assertEquals(Triple(255, 0, 0), rgb(0f))
        assertEquals(Triple(255, 255, 0), rgb(60f))
        assertEquals(Triple(0, 255, 0), rgb(120f))
        assertEquals(Triple(0, 255, 255), rgb(180f))
        assertEquals(Triple(0, 0, 255), rgb(240f))
        assertEquals(Triple(255, 0, 255), rgb(300f))
    }

    @Test
    fun `the wheel closes, so dragging past red comes back to red`() {
        assertEquals(rgb(0f), rgb(360f))
        assertEquals(rgb(10f), rgb(370f))
        assertEquals("and dragging the other way too", rgb(350f), rgb(-10f))
    }

    @Test
    fun `the middle of the wheel is white and the bottom of the slider is black`() {
        // The two colours most wanted on a photo, and neither was reachable before: every colour
        // the editor could make was fully saturated and fully bright.
        assertEquals(Triple(255, 255, 255), rgb(200f, saturation = 0f))
        assertEquals(Triple(0, 0, 0), rgb(200f, value = 0f))
    }

    @Test
    fun `pulling in from the rim pales the colour rather than changing it`() {
        val (red, green, blue) = rgb(0f, saturation = 0.5f)
        assertEquals("still as red as red gets", 255, red)
        assertEquals("with the other two lifted evenly towards white", green, blue)
        assertEquals(128, green)
    }

    @Test
    fun `dropping the brightness darkens without shifting the hue`() {
        val (red, green, blue) = rgb(210f, value = 0.5f)
        // Half of the same 0,127,255 ramp: a navy, which a hue-only control could never reach.
        assertEquals(0, red)
        assertEquals(64, green)
        assertEquals(128, blue)
    }

    @Test
    fun `no saturation at any hue is the same grey`() {
        assertEquals(rgb(0f, saturation = 0f, value = 0.5f), rgb(280f, saturation = 0f, value = 0.5f))
    }

    @Test
    fun `values outside the range are clamped rather than wrapped`() {
        // A slider that overshoots by a rounding error must not flip white to black.
        assertEquals(Triple(255, 255, 255), rgb(0f, saturation = -1f, value = 2f))
        assertEquals(Triple(0, 0, 0), rgb(0f, saturation = 2f, value = -1f))
    }

    @Test
    fun `alpha bits are left alone, because the caller owns transparency`() {
        // A gradient's Clear end reuses this to keep its colour while losing opacity.
        assertEquals(0, hsvToRgb(120f) ushr 24)
    }
}
