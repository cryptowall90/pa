package com.pictureperfectx.app.ui.perfect

import com.pictureperfectx.app.layers.Document
import com.pictureperfectx.app.layers.GradientSpec
import com.pictureperfectx.app.layers.Layer
import com.pictureperfectx.app.layers.Mask
import com.pictureperfectx.app.layers.MaskBrush
import com.pictureperfectx.app.layers.MaskGradient
import com.pictureperfectx.app.layers.MaskLasso
import com.pictureperfectx.app.layers.MaskPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A handle is dragged by index: the canvas decides where the points are drawn and the view model
 * decides what dragging one means. If the two ever disagreed about the order, dragging one handle
 * would move a different one — so the order lives in one function, and this holds it there.
 */
class EditHandlesTest {

    private val triangle = listOf(
        MaskPoint(0.2f, 0.2f),
        MaskPoint(0.8f, 0.3f),
        MaskPoint(0.5f, 0.8f),
    )

    private fun editing(
        mask: Mask? = null,
        layer: Layer? = null,
        tool: SelectionTool = SelectionTool.Lasso,
    ): PerfectEditUiState {
        val document = if (layer == null) Document() else Document().add { layer }
        return PerfectEditUiState(
            panel = EditorPanel.Effects,
            document = document,
            pendingSelection = mask,
            selectionTool = tool,
        )
    }

    private fun lassoed() = MaskLasso.trace(Mask.blank(32, 32), triangle)

    @Test
    fun `nothing is draggable while the effects panel is away`() {
        val state = editing(mask = lassoed()).copy(panel = EditorPanel.Menu)
        assertTrue(editHandles(state).isEmpty())
    }

    @Test
    fun `a lassoed area offers the points it kept`() {
        val mask = lassoed()
        assertEquals(mask.path, editHandles(editing(mask = mask)))
    }

    @Test
    fun `a lasso keeps its points after the brush is picked up`() {
        // The reported bug: retouching switched the tool to Brush, and the lasso's points went with
        // it. The tool decides what a *new* drag draws, not whether an existing shape can be moved.
        val mask = lassoed()
        val handles = editHandles(editing(mask = mask, tool = SelectionTool.Brush))
        assertEquals(mask.path, handles)
        assertTrue("there should be points to drag", handles.isNotEmpty())
    }

    @Test
    fun `a brushed area has no points, because no shape describes it`() {
        val brushed = MaskBrush.paint(Mask.blank(32, 32), 0.5f, 0.5f, 0.2f)
        assertTrue(editHandles(editing(mask = brushed)).isEmpty())
    }

    @Test
    fun `a faded area offers its two ends, in that order`() {
        val spec = GradientSpec(start = MaskPoint(0.1f, 0.2f), end = MaskPoint(0.9f, 0.7f))
        val faded = MaskGradient.fill(Mask.blank(32, 32), spec)
        assertEquals(listOf(spec.start, spec.end), editHandles(editing(mask = faded)))
    }

    @Test
    fun `a text layer is placed by its own handle rather than its mask's`() {
        val text = Layer.Text(id = 1, centre = MaskPoint(0.3f, 0.4f), mask = lassoed())
        assertEquals(listOf(text.centre), editHandles(editing(layer = text)))
    }

    @Test
    fun `a shape is placed by its centre and sized by its corner, in that order`() {
        val shape = Layer.Shape(id = 1, centre = MaskPoint(0.5f, 0.5f), width = 0.4f, height = 0.2f)
        assertEquals(listOf(shape.centre, shape.corner), editHandles(editing(layer = shape)))
    }

    @Test
    fun `a gradient layer offers its area first and its own ramp last`() {
        // Both are draggable and they mean different things: the area says where the wash lands,
        // the ramp says which way the colour runs inside it. The ramp goes last so the area's
        // handles keep the indices they have for every other layer.
        val area = lassoed()
        val spec = GradientSpec(start = MaskPoint(0.4f, 0.3f), end = MaskPoint(0.6f, 0.7f))
        val layer = Layer.Gradient(id = 1, spec = spec, mask = area)
        val handles = editHandles(editing(layer = layer))

        assertEquals(area.path!! + listOf(spec.start, spec.end), handles)
        assertEquals(spec.start, handles[handles.size - 2])
        assertEquals(spec.end, handles.last())
    }

    @Test
    fun `a gradient layer with no area drawn is placed by its ramp alone`() {
        val spec = GradientSpec(start = MaskPoint(0.1f, 0.1f), end = MaskPoint(0.9f, 0.9f))
        val layer = Layer.Gradient(id = 1, spec = spec)
        assertEquals(listOf(spec.start, spec.end), editHandles(editing(layer = layer)))
    }

    @Test
    fun `an unmasked layer with no shape of its own has nothing to drag`() {
        assertTrue(editHandles(editing(layer = Layer.Tone(id = 1))).isEmpty())
    }
}
