package com.pictureperfectx.app.ui.perfect

import com.pictureperfectx.app.layers.Document
import com.pictureperfectx.app.layers.GradientSpec
import com.pictureperfectx.app.layers.Layer
import com.pictureperfectx.app.layers.Mask
import com.pictureperfectx.app.layers.MaskGradient
import com.pictureperfectx.app.layers.MaskLasso
import com.pictureperfectx.app.layers.MaskPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a drawn area goes.
 *
 * The editor used to write it straight into whichever layer was selected, so lassoing a second area
 * silently overwrote the first layer's — and the only way to avoid it was to know that tapping the
 * already-selected layer deselected it, which nothing said.
 *
 * Photoshop keeps a selection at the *document* level: drawing one changes nothing until an
 * adjustment layer takes it as a mask, and a mask can only be edited after you deliberately click
 * its thumbnail. These are the rules that make that true here, and they are worth pinning down
 * because getting them wrong destroys work rather than merely looking wrong.
 */
class SelectionRoutingTest {

    private fun area(offset: Float = 0f) = MaskLasso.trace(
        Mask.blank(32, 32),
        listOf(
            MaskPoint(0.1f + offset, 0.1f),
            MaskPoint(0.4f + offset, 0.2f),
            MaskPoint(0.25f + offset, 0.5f),
        ),
    )

    private fun withLayer(mask: Mask): PerfectEditUiState {
        val document = Document().add { Layer.Tone(id = it, mask = mask) }
        return PerfectEditUiState(
            panel = EditorPanel.Effects,
            document = document.select(document.layers.single().id),
        )
    }

    @Test
    fun `with nothing drawn and nothing aimed at, there is no area at all`() {
        val state = PerfectEditUiState(panel = EditorPanel.Effects)
        assertNull(state.activeMask)
        assertNull(state.targetedLayer)
        assertFalse(state.isEditingMask)
    }

    @Test
    fun `a drawn area is the live one before any effect exists`() {
        val drawn = area()
        val state = PerfectEditUiState(panel = EditorPanel.Effects, selection = drawn)
        assertEquals(drawn, state.activeMask)
        assertFalse("nothing is being reshaped yet", state.isEditingMask)
    }

    @Test
    fun `selecting a layer does not put its area under your finger`() {
        // The whole point. A selected layer's sliders are live; its *area* is not.
        val layerArea = area()
        val state = withLayer(layerArea)

        assertNull("no floating selection, and the layer's mask is not the target", state.activeMask)
        assertFalse(state.isEditingMask)
    }

    @Test
    fun `a floating selection stays live even with a layer selected`() {
        // Draw a second area while layer one is selected: the new shape is waiting for the *next*
        // effect, and layer one keeps the area it already had.
        val layerArea = area()
        val drawn = area(offset = 0.4f)
        val state = withLayer(layerArea).copy(selection = drawn)

        assertEquals(drawn, state.activeMask)
        assertEquals(
            "the layer's own area is untouched",
            layerArea,
            state.document.layers.single().mask,
        )
    }

    @Test
    fun `aiming at a layer's mask makes that mask the live area`() {
        val layerArea = area()
        val state = withLayer(layerArea).copy(maskTarget = 1L)

        assertTrue(state.isEditingMask)
        assertSame(state.document.layers.single(), state.targetedLayer)
        assertEquals(layerArea, state.activeMask)
    }

    @Test
    fun `an aimed-at mask wins over a floating selection, because it was asked for`() {
        val layerArea = area()
        val drawn = area(offset = 0.4f)
        val state = withLayer(layerArea).copy(selection = drawn, maskTarget = 1L)
        assertEquals(layerArea, state.activeMask)
    }

    @Test
    fun `aiming at a layer that has no area falls back to the selection`() {
        // An unmasked layer renders as "applies everywhere", so there is no area of its own to edit
        // yet — the drawn one is still what a feather or an invert should act on.
        val drawn = area()
        val state = withLayer(Mask()).copy(selection = drawn, maskTarget = 1L)
        assertEquals(drawn, state.activeMask)
    }

    @Test
    fun `aiming at a layer that no longer exists is simply not aiming`() {
        // Deleting the layer you were reshaping must not leave drawing pointed at nothing.
        val state = withLayer(area()).copy(maskTarget = 99L)
        assertNull(state.targetedLayer)
        assertFalse(state.isEditingMask)
    }

    @Test
    fun `a faded area is live the same way a drawn one is`() {
        val faded = MaskGradient.fill(Mask.blank(32, 32), GradientSpec())
        val state = PerfectEditUiState(panel = EditorPanel.Effects, selection = faded)
        assertEquals(faded, state.activeMask)
        assertEquals(faded.gradient, state.activeMask?.gradient)
    }
}
