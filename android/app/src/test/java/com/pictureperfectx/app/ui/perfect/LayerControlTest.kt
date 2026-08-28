package com.pictureperfectx.app.ui.perfect

import com.pictureperfectx.app.capture.ToneAdjustments
import com.pictureperfectx.app.capture.ToneBand
import com.pictureperfectx.app.layers.GradientSpec
import com.pictureperfectx.app.layers.Layer
import com.pictureperfectx.app.layers.Mask
import com.pictureperfectx.app.layers.MaskGradient
import com.pictureperfectx.app.layers.MaskLasso
import com.pictureperfectx.app.layers.MaskPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which chips a layer offers, and in what order.
 *
 * This list *is* the panel: a control missing from it is a feature nobody can reach, which is
 * exactly what happened to the five gradient shapes — they were reachable only by tapping a chip
 * labelled "Falloff", and on a flat fill not at all. A list is cheap to check here and expensive to
 * check by tapping around a phone, so it is checked here.
 */
class LayerControlTest {

    private fun area() = MaskLasso.trace(
        Mask.blank(32, 32),
        listOf(MaskPoint(0.2f, 0.2f), MaskPoint(0.7f, 0.3f), MaskPoint(0.4f, 0.8f)),
    )

    @Test
    fun `a gradient offers its five shapes as a chip of their own`() {
        val ramp = Layer.Gradient(id = 1)
        val controls = LayerControl.forLayer(ramp)

        assertTrue("the shapes need a name of their own", LayerControl.GradientStylePick in controls)
        assertTrue("and a way back to a flat fill", LayerControl.GradientSolid in controls)
        assertEquals(
            "solid first: it is the thing you decide before anything else about a gradient",
            LayerControl.GradientSolid,
            controls.first(),
        )
    }

    @Test
    fun `a flat fill offers no shape and no far end, because it has neither`() {
        val fill = Layer.Gradient(id = 1, solid = true)
        val controls = LayerControl.forLayer(fill)

        assertFalse("a fill runs in no direction", LayerControl.GradientStylePick in controls)
        assertFalse("and has no far end to colour", LayerControl.ColourTo in controls)
        assertFalse("nor a falloff to shape", LayerControl.Falloff in controls)
        assertTrue("but turning it back into a ramp is one tap", LayerControl.GradientSolid in controls)
    }

    @Test
    fun `the solid toggle reports the layer rather than claiming to be the chosen control`() {
        assertTrue(LayerControl.GradientSolid.isOn(Layer.Gradient(id = 1, solid = true)))
        assertFalse(LayerControl.GradientSolid.isOn(Layer.Gradient(id = 1)))
        assertFalse("it means nothing on a layer that isn't a gradient",
            LayerControl.GradientSolid.isOn(Layer.Tone(id = 1)))
    }

    @Test
    fun `a tone layer offers every band and nothing else but opacity`() {
        val controls = LayerControl.forLayer(Layer.Tone(id = 1))
        assertEquals(
            "a band added to ToneAdjustments must not be left without a chip",
            ToneBand.entries.toList(),
            controls.mapNotNull { it.band },
        )
        assertEquals(ToneBand.entries.size + 1, controls.size)
        assertEquals(LayerControl.Opacity, controls.last())
    }

    @Test
    fun `feathering is only offered when there is an edge to soften`() {
        assertFalse(LayerControl.Feather in LayerControl.forLayer(Layer.Tone(id = 1)))
        assertTrue(LayerControl.Feather in LayerControl.forLayer(Layer.Tone(id = 1, mask = area())))
    }

    @Test
    fun `falloff is offered for a faded area, but never twice on a gradient`() {
        val faded = MaskGradient.fill(Mask.blank(32, 32), GradientSpec())
        assertTrue(LayerControl.Falloff in LayerControl.forLayer(Layer.Tone(id = 1, mask = faded)))

        // A gradient layer's own ramp already has a Falloff chip; a second one for its area would
        // be two identical chips doing different things.
        val gradient = LayerControl.forLayer(Layer.Gradient(id = 1, mask = faded))
        assertEquals(1, gradient.count { it == LayerControl.Falloff })
    }

    @Test
    fun `no layer offers a setting that belongs to choosing an area`() {
        // Brush size and wand tolerance are about picking where an effect goes, not about the
        // effect. They padded out every layer's row with a control that had nothing to do with it.
        val everything = listOf(
            Layer.Tone(id = 1, adjustments = ToneAdjustments(shadows = 20), mask = area()),
            Layer.Gradient(id = 2, mask = area()),
            Layer.Text(id = 3, mask = area()),
            Layer.Shape(id = 4),
            Layer.Curve(id = 5),
            Layer.Look(id = 6, filterId = "fuji_provia"),
            Layer.Smooth(id = 7),
            Layer.Heal(id = 8),
            Layer.Blur(id = 9),
        ).flatMap { LayerControl.forLayer(it) }

        assertTrue(everything.none { it.label == "Brush size" || it.label == "Tolerance" })
    }

    @Test
    fun `the shown control is never a toggle, which would leave the panel empty`() {
        val controls = LayerControl.forLayer(Layer.Gradient(id = 1))
        assertEquals(
            "landing on Solid would show nothing at all",
            LayerControl.ColourFrom,
            LayerControl.effective(controls, LayerControl.GradientSolid),
        )
    }

    @Test
    fun `a control this layer doesn't have falls back to one it does`() {
        val controls = LayerControl.forLayer(Layer.Text(id = 1))
        assertEquals(LayerControl.TextContent, LayerControl.effective(controls, LayerControl.Blur))
        assertEquals("and one it has is left alone",
            LayerControl.TextSize, LayerControl.effective(controls, LayerControl.TextSize))
    }

    @Test
    fun `a group opens onto the first of its members`() {
        val controls = LayerControl.forLayer(Layer.Tone(id = 1))
        assertEquals(
            LayerControl.ToneExposure,
            LayerControl.firstOf(controls, ControlGroup.Light),
        )
        assertEquals(
            LayerControl.ToneSaturation,
            LayerControl.firstOf(controls, ControlGroup.Colour),
        )
        assertEquals(
            "a layer with no grouped controls opens nothing",
            null,
            LayerControl.firstOf(LayerControl.forLayer(Layer.Blur(id = 1)), ControlGroup.Light),
        )
    }
}
