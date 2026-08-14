package com.pictureperfectx.app.layers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blending is where a layer stops being a description and becomes the photo, and the way it goes
 * wrong is quiet: a slightly off colour, or — the bug this replaced — the photo disappearing
 * wherever the effect happened to be transparent.
 */
class LayerBlendTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun grey(value: Int, alpha: Int = 255) = argb(alpha, value, value, value)

    private fun red(pixel: Int) = (pixel shr 16) and 0xFF

    private fun alpha(pixel: Int) = (pixel ushr 24) and 0xFF

    @Test
    fun `a transparent effect leaves the photo exactly as it was`() {
        // The whole reason this exists. PorterDuff.MULTIPLY computes alpha as Sa x Da, so a
        // transparent source erased the destination — outside a mask, and at a gradient's faded
        // end, the photo simply vanished.
        val photo = argb(255, 200, 130, 90)
        BlendMode.entries.forEach { mode ->
            val nothing = argb(0, 0, 0, 0)
            assertEquals("$mode should leave the photo alone", photo, LayerBlend.pixel(photo, nothing, mode, 1f))
            // Even a fully saturated colour contributes nothing at zero alpha.
            val clear = argb(0, 255, 0, 0)
            assertEquals("$mode with a clear red", photo, LayerBlend.pixel(photo, clear, mode, 1f))
        }
    }

    @Test
    fun `the photo keeps its own alpha, whatever the layer does`() {
        val photo = argb(255, 40, 40, 40)
        BlendMode.entries.forEach { mode ->
            val result = LayerBlend.pixel(photo, grey(220), mode, 1f)
            assertEquals("$mode must not make the photo see-through", 255, alpha(result))
        }
    }

    @Test
    fun `multiplying by white changes nothing and by mid grey darkens`() {
        val photo = grey(200)
        assertEquals(200, red(LayerBlend.pixel(photo, grey(255), BlendMode.Multiply, 1f)))
        // 128 x 200 / 255
        assertEquals(100, red(LayerBlend.pixel(photo, grey(128), BlendMode.Multiply, 1f)))
        assertEquals(0, red(LayerBlend.pixel(photo, grey(0), BlendMode.Multiply, 1f)))
    }

    @Test
    fun `screening by black changes nothing and by white blows out`() {
        val photo = grey(80)
        assertEquals(80, red(LayerBlend.pixel(photo, grey(0), BlendMode.Screen, 1f)))
        assertEquals(255, red(LayerBlend.pixel(photo, grey(255), BlendMode.Screen, 1f)))
    }

    @Test
    fun `overlay multiplies through the dark half and screens through the light`() {
        // The pivot is the photo's value, not the layer's: this is a contrast move.
        assertEquals(78, red(LayerBlend.pixel(grey(50), grey(200), BlendMode.Overlay, 1f)))
        assertEquals(232, red(LayerBlend.pixel(grey(200), grey(200), BlendMode.Overlay, 1f)))
    }

    @Test
    fun `darken and lighten take the side they are named after`() {
        assertEquals(60, red(LayerBlend.pixel(grey(200), grey(60), BlendMode.Darken, 1f)))
        assertEquals(200, red(LayerBlend.pixel(grey(200), grey(220), BlendMode.Darken, 1f)))
        assertEquals(220, red(LayerBlend.pixel(grey(200), grey(220), BlendMode.Lighten, 1f)))
        assertEquals(200, red(LayerBlend.pixel(grey(200), grey(60), BlendMode.Lighten, 1f)))
    }

    @Test
    fun `opacity and the effect's own alpha both scale it the same way`() {
        val photo = grey(200)
        val black = grey(0)
        assertEquals(100, red(LayerBlend.pixel(photo, black, BlendMode.Multiply, 0.5f)))
        // Half alpha rather than half opacity: 128/255 of the way, so a hair over halfway.
        assertEquals(100, red(LayerBlend.pixel(photo, grey(0, alpha = 128), BlendMode.Multiply, 1f)))
        assertEquals("zero opacity is a layer turned off", 200, red(LayerBlend.pixel(photo, black, BlendMode.Multiply, 0f)))
    }

    @Test
    fun `nothing ever leaves the visible range`() {
        val values = listOf(0, 1, 60, 127, 128, 200, 254, 255)
        BlendMode.entries.forEach { mode ->
            values.forEach { destination ->
                values.forEach { source ->
                    listOf(0f, 0.37f, 1f).forEach { opacity ->
                        val result = LayerBlend.pixel(grey(destination), grey(source), mode, opacity)
                        listOf(16, 8, 0).forEach { shift ->
                            val channel = (result shr shift) and 0xFF
                            assertTrue("$mode $destination/$source at $opacity gave $channel", channel in 0..255)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `compositing walks the whole band and stops where told`() {
        val destination = IntArray(6) { grey(200) }
        val source = IntArray(6) { grey(0) }
        LayerBlend.composite(destination, source, BlendMode.Multiply, opacity = 1f, count = 4)
        assertEquals(0, red(destination[0]))
        assertEquals(0, red(destination[3]))
        assertEquals("past the count is another band's business", 200, red(destination[4]))
        assertEquals(200, red(destination[5]))
    }

    @Test
    fun `each channel is blended on its own`() {
        val photo = argb(255, 200, 100, 50)
        val layer = argb(255, 255, 128, 0)
        val result = LayerBlend.pixel(photo, layer, BlendMode.Multiply, 1f)
        assertEquals(200, (result shr 16) and 0xFF)
        assertEquals(50, (result shr 8) and 0xFF)
        assertEquals(0, result and 0xFF)
    }
}
