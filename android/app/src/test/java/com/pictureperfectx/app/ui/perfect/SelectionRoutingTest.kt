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
import org.junit.Assert.assertNotNull
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
    fun `the area tools have their own panel, and drawing survives leaving it`() {
        // Choosing where an effect applies is its own errand, so the tools moved off the effect's
        // controls. But a Text or Shape layer's placement handle is dragged from the effects panel,
        // so drawing cannot be confined to the area panel alone.
        val drawn = area()
        assertTrue(PerfectEditUiState(panel = EditorPanel.Area, selection = drawn).canSelect)
        assertTrue(PerfectEditUiState(panel = EditorPanel.Effects, selection = drawn).canSelect)
        assertFalse("cropping is a different tool entirely",
            PerfectEditUiState(panel = EditorPanel.Crop).canSelect)
        assertFalse("and a clean preview must not take a stray stroke",
            PerfectEditUiState(panel = EditorPanel.Area, previewing = true).canSelect)
    }

    @Test
    fun `drawing needs the tools on screen, even though reshaping does not`() {
        // A drag on the photo while adjusting an effect would otherwise start a lasso — a shape you
        // never asked for, on a panel showing no tool you could have drawn it with.
        assertTrue(PerfectEditUiState(panel = EditorPanel.Area).canDraw)
        assertFalse(PerfectEditUiState(panel = EditorPanel.Effects).canDraw)
        assertFalse(PerfectEditUiState(panel = EditorPanel.Area, previewing = true).canDraw)
    }

    @Test
    fun `the gradient shapes are on screen with a layer selected, not only without one`() {
        // The reported regression: the five Fade styles rendered only when nothing was selected, so
        // they vanished the moment an effect existed. On the area panel there is no such fork, and
        // that is also what makes restyling an area in place reachable at all.
        val faded = MaskGradient.fill(Mask.blank(32, 32), GradientSpec())
        val state = withLayer(faded).copy(panel = EditorPanel.Area)

        assertTrue(state.canSelect)
        assertNotNull("there is a gradient to restyle", state.activeMask?.gradient)
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
    fun `drawing never lands on a selected layer's area, but Feather and Invert do`() {
        // Two different questions, and treating them as one is what broke both halves.
        //
        // *Drawing* must not touch a selected layer's area — that is the rule that stops a second
        // lasso silently overwriting the first layer's, and it needs the mask thumbnail tapped
        // first. But Feather and Invert are not drawing: with a layer selected and nothing drawn,
        // its area is the only area on screen, so acting on anything else means appearing to do
        // nothing at all. That is exactly what they did.
        val layerArea = area()
        val state = withLayer(layerArea)

        assertEquals("Feather and Invert have something to act on", layerArea, state.activeMask)
        assertNull("but a new shape starts from scratch", state.drawingBase)
        assertFalse("and it is not the layer's mask being reshaped", state.isEditingMask)
    }

    @Test
    fun `a selected layer with no area of its own leaves nothing to act on`() {
        // An unmasked layer renders as "applies everywhere". Offering Invert on that would mean
        // "cover nothing", which reads as the layer vanishing rather than as an inversion.
        val state = withLayer(Mask())
        assertNull(state.activeMask)
        assertFalse(state.canClearArea)
    }

    @Test
    fun `a drawn area is never what a layer is already wearing`() {
        // Drawing a second shape with a layer selected: the shape combines with nothing, so the
        // layer's own area cannot be eaten by it before Apply is pressed.
        val layerArea = area()
        val drawn = area(offset = 0.4f)
        val state = withLayer(layerArea).copy(selection = drawn)

        assertEquals("a further stroke builds on the drawn shape", drawn, state.drawingBase)
        assertTrue("and there is an obvious way to hand it over", state.canApplySelection)
    }

    @Test
    fun `aiming at a layer's mask is what makes drawing edit it`() {
        val layerArea = area()
        val state = withLayer(layerArea).copy(maskTarget = 1L)
        assertEquals(layerArea, state.drawingBase)
    }

    @Test
    fun `there is nothing to apply without both a shape and a layer to give it to`() {
        assertFalse(
            "a shape and no layer is what + Effect is for",
            PerfectEditUiState(panel = EditorPanel.Effects, selection = area()).canApplySelection,
        )
        assertFalse("and a layer with nothing drawn has nothing to take", withLayer(area()).canApplySelection)
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
