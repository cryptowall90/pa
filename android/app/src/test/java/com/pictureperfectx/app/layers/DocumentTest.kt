package com.pictureperfectx.app.layers

import com.pictureperfectx.app.capture.CropRect
import com.pictureperfectx.app.capture.ImageGeometry
import com.pictureperfectx.app.capture.ToneAdjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layer stack and its undo history are pure Kotlin, which makes them the part of the editor CI
 * can genuinely execute. Ordering and selection bugs here surface as layers rendering in the wrong
 * order or an undo that jumps somewhere unexpected — both hard to spot by eye on a phone.
 */
class DocumentTest {

    private fun tone(id: Long) = Layer.Tone(id = id, adjustments = ToneAdjustments(blacks = 10))
    private fun docWithThree(): Document =
        Document().add(::tone).add(::tone).add(::tone)

    @Test
    fun `a new document is empty with nothing selected`() {
        val document = Document()
        assertTrue(document.isEmpty)
        assertNull(document.selected)
    }

    @Test
    fun `adding stacks on top and selects the new layer`() {
        val document = Document().add(::tone).add(::tone)
        assertEquals(2, document.layers.size)
        assertEquals(document.layers.last().id, document.selectedId)
    }

    @Test
    fun `ids are never reused even after deletion`() {
        val document = Document().add(::tone)
        val firstId = document.layers.single().id
        val afterRemoveAndAdd = document.remove(firstId).add(::tone)
        assertFalse("a recycled id would let undo confuse two layers",
            afterRemoveAndAdd.layers.single().id == firstId)
    }

    @Test
    fun `topDown reverses the render order for the panel`() {
        val document = docWithThree()
        assertEquals(document.layers.reversed().map { it.id }, document.topDown.map { it.id })
    }

    @Test
    fun `removing selects the neighbour rather than losing the selection`() {
        val document = docWithThree()
        val middle = document.layers[1].id
        val after = document.select(middle).remove(middle)
        assertEquals(2, after.layers.size)
        assertTrue("something should still be selected", after.selected != null)
    }

    @Test
    fun `removing the only layer clears the selection`() {
        val document = Document().add(::tone)
        val after = document.remove(document.layers.single().id)
        assertTrue(after.isEmpty)
        assertNull(after.selected)
    }

    @Test
    fun `removing an unknown id changes nothing`() {
        val document = docWithThree()
        assertEquals(document, document.remove(9999L))
    }

    @Test
    fun `selecting an unknown id clears rather than pointing at a ghost`() {
        assertNull(docWithThree().select(9999L).selectedId)
    }

    @Test
    fun `moving up swaps with the layer above`() {
        val document = docWithThree()
        val bottom = document.layers.first().id
        val moved = document.move(bottom, up = true)
        assertEquals(bottom, moved.layers[1].id)
    }

    @Test
    fun `moving past either end is a no-op`() {
        val document = docWithThree()
        assertEquals(document, document.move(document.layers.first().id, up = false))
        assertEquals(document, document.move(document.layers.last().id, up = true))
    }

    @Test
    fun `hidden and transparent layers are excluded from a render`() {
        val document = docWithThree()
        val hidden = document.layers[0].id
        val transparent = document.layers[1].id
        val prepared = document.toggleVisibility(hidden).setOpacity(transparent, 0f)
        assertEquals(listOf(document.layers[2].id), prepared.renderable().map { it.id })
    }

    @Test
    fun `opacity is clamped to a sane range`() {
        val document = Document().add(::tone)
        val id = document.layers.single().id
        assertEquals(1f, document.setOpacity(id, 5f).layers.single().opacity, 0.0001f)
        assertEquals(0f, document.setOpacity(id, -5f).layers.single().opacity, 0.0001f)
    }

    @Test
    fun `common edits preserve the layer's type and payload`() {
        val document = Document().add { Layer.Look(it, filterId = "fuji_provia", intensity = 80) }
        val id = document.layers.single().id
        val updated = document.setBlend(id, BlendMode.Multiply).layers.single()
        assertTrue(updated is Layer.Look)
        assertEquals("fuji_provia", (updated as Layer.Look).filterId)
        assertEquals(80, updated.intensity)
        assertEquals(BlendMode.Multiply, updated.blend)
    }

    @Test
    fun `adjustments reach every kind of layer, not just Tone`() {
        // The point of the change: an area is the thing you adjust, so a filter, a smooth or a heal
        // can be brightened in place instead of needing a second layer given the same area by hand.
        val document = Document()
            .add { Layer.Look(it, filterId = "fuji_provia", intensity = 80) }
            .add { Layer.Smooth(it, amount = 70) }
        val warmed = ToneAdjustments(exposure = 20, warmth = 15)

        val look = document.setAdjustments(document.layers[0].id, warmed).layers[0]
        assertEquals(warmed, look.adjustments)
        assertTrue(look is Layer.Look)
        assertEquals("the payload is untouched", 80, (look as Layer.Look).intensity)
        assertEquals("and so is the filter", "fuji_provia", look.filterId)

        assertEquals(
            "a layer nobody adjusted stays neutral",
            ToneAdjustments(),
            document.setAdjustments(document.layers[0].id, warmed).layers[1].adjustments,
        )
    }

    @Test
    fun `common edits carry adjustments along with everything else`() {
        // withCommon is the only path for common properties, so a kind it forgot would silently
        // lose its adjustments the moment the layer was hidden or its opacity moved.
        val adjusted = ToneAdjustments(contrast = 40)
        val every = listOf(
            Layer.Tone(1), Layer.Look(2, filterId = "x"), Layer.Blur(3), Layer.Text(4),
            Layer.Heal(5), Layer.Smooth(6), Layer.Shape(7), Layer.Curve(8), Layer.Gradient(9),
        )
        every.forEach { layer ->
            val kept = layer.withCommon(adjustments = adjusted).withCommon(opacity = 0.5f)
            assertEquals("${layer.name} lost its adjustments", adjusted, kept.adjustments)
        }
    }

    @Test
    fun `an empty mask covers everything`() {
        assertEquals(1f, Mask().coverageAt(0, 0), 0.0001f)
    }

    @Test
    fun `inverting a mask flips its coverage`() {
        val mask = Mask.full(2, 2).copy(inverted = true)
        assertEquals(0f, mask.coverageAt(0, 0), 0.0001f)
    }

    @Test
    fun `sampling outside a mask reads as uncovered`() {
        assertEquals(0f, Mask.full(2, 2).coverageAt(5, 5), 0.0001f)
    }

    @Test
    fun `masks compare by content so undo and recomposition behave`() {
        assertEquals(Mask.full(4, 4), Mask.full(4, 4))
        assertEquals(Mask.full(4, 4).hashCode(), Mask.full(4, 4).hashCode())
    }

    @Test
    fun `softening leaves a fully painted mask fully painted`() {
        val softened = Mask.full(8, 8).softened()
        softened.forEach { assertEquals(1f, it, 0.0001f) }
    }

    @Test
    fun `softening spreads coverage into neighbouring cells`() {
        val coverage = FloatArray(8 * 8)
        coverage[4 * 8 + 4] = 1f // a single painted cell
        val mask = Mask(8, 8, coverage, feather = 1f)
        val softened = mask.softened()

        assertTrue("the painted cell should bleed outwards", softened[4 * 8 + 3] > 0f)
        assertTrue("softening should lower the peak", softened[4 * 8 + 4] < 1f)
    }

    @Test
    fun `zero feather leaves coverage untouched`() {
        val coverage = FloatArray(4 * 4) { if (it == 5) 1f else 0f }
        val softened = Mask(4, 4, coverage, feather = 0f).softened()
        assertEquals(1f, softened[5], 0.0001f)
        assertEquals(0f, softened[0], 0.0001f)
    }

    @Test
    fun `softening applies inversion before blurring`() {
        val softened = Mask.full(4, 4).copy(inverted = true, feather = 0f).softened()
        softened.forEach { assertEquals(0f, it, 0.0001f) }
    }

    @Test
    fun `an empty mask softens to nothing rather than crashing`() {
        assertEquals(0, Mask().softened().size)
    }

    @Test
    fun `the same feather softens the same share of the picture at any grid size`() {
        // The brush works on a coarse grid and a lasso selection on a fine one. If the blur radius
        // were quoted in cells, one slider position would mean a broad falloff on the brush's grid
        // and almost nothing on the selection's, since a cell there covers a third as much picture.
        fun spread(size: Int): Float {
            val coverage = FloatArray(size * size) { index ->
                // The left half covered, so there is one straight edge down the middle to soften.
                if (index % size < size / 2) 1f else 0f
            }
            val softened = Mask(size, size, coverage, feather = 0.5f).softened()
            val row = size / 2
            val touched = (0 until size).count { column ->
                val value = softened[row * size + column]
                value > 0.02f && value < 0.98f
            }
            return touched.toFloat() / size
        }

        assertEquals(
            "the falloff should span the same fraction of the image on both grids",
            spread(64),
            spread(192),
            0.06f, // room for a radius that has to round to a whole cell on each grid
        )
    }

    @Test
    fun `a blank mask starts with no coverage`() {
        val blank = Mask.blank(4, 4)
        assertFalse(blank.isEmpty)
        assertEquals(0f, blank.coverageAt(2, 2), 0.0001f)
    }

    @Test
    fun `history starts with nothing to undo or redo`() {
        val history = History()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    /** A step in the history: the stack, and the framing it was made under. */
    private fun step(document: Document = Document(), geometry: ImageGeometry = ImageGeometry()) =
        EditDocument(geometry = geometry, document = document)

    @Test
    fun `undo returns the previous state and redo replays it`() {
        val first = step(Document().add(::tone))
        val second = step(first.document.add(::tone))
        val history = History().push(first).push(second)

        val undone = history.undo()
        assertEquals(first, undone.current)
        assertTrue(undone.canRedo)
        assertEquals(second, undone.redo().current)
    }

    @Test
    fun `pushing an identical state does not add a history step`() {
        val document = step(Document().add(::tone))
        val history = History().push(document)
        assertEquals(history, history.push(document))
    }

    @Test
    fun `a new edit after undo drops the redo branch`() {
        val first = step(Document().add(::tone))
        val second = step(first.document.add(::tone))
        val branched = History().push(first).push(second).undo().push(step(first.document.add(::tone)))
        assertFalse("the redone future belonged to an abandoned timeline", branched.canRedo)
    }

    @Test
    fun `a crop is an undo step, not something undo silently ignores`() {
        // History used to hold only the layer stack, so cropping, rotating or straightening recorded
        // nothing at all — the undo arrow sat there looking like it should take the crop back off.
        val stack = Document().add(::tone)
        val uncropped = step(stack)
        val cropped = step(stack, ImageGeometry(crop = CropRect(0.2f, 0.2f, 0.8f, 0.8f)))
        val history = History().push(uncropped).push(cropped)

        assertTrue("the crop is a step of its own", history.canUndo)
        assertEquals(uncropped.geometry, history.undo().current.geometry)
        assertEquals("and the stack came along untouched", stack, history.undo().current.document)
    }

    @Test
    fun `a rotate and a layer change are separate steps`() {
        val start = step()
        val turned = step(geometry = ImageGeometry(quarterTurns = 1))
        val layered = step(Document().add(::tone), ImageGeometry(quarterTurns = 1))
        val history = History().push(start).push(turned).push(layered)

        assertEquals("undo takes the layer back", turned, history.undo().current)
        assertEquals("undo again takes the rotation back", start, history.undo().undo().current)
    }

    @Test
    fun `a duplicate lands directly above its original and takes the selection`() {
        val document = docWithThree()
        val middle = document.layers[1]
        val duplicated = document.duplicate(middle.id)

        assertEquals(4, duplicated.layers.size)
        assertEquals("the original stays put", middle.id, duplicated.layers[1].id)
        assertEquals("the copy sits on top of it", duplicated.layers[2].id, duplicated.selectedId)
        assertTrue("ids are never reused", duplicated.layers.map { it.id }.toSet().size == 4)
    }

    @Test
    fun `a duplicate keeps the area that took the work`() {
        // The point of the feature: a second effect through the same hand-drawn area.
        val mask = MaskLasso.trace(
            Mask.blank(32, 32),
            listOf(MaskPoint(0.2f, 0.2f), MaskPoint(0.8f, 0.3f), MaskPoint(0.5f, 0.8f)),
        )
        val document = Document().add { Layer.Tone(id = it, mask = mask) }
        val twin = document.duplicate(document.layers.single().id).layers[1]

        assertEquals(mask, twin.mask)
        assertTrue("and says what it is", twin.name.endsWith("copy"))
    }

    @Test
    fun `duplicating a layer that isn't there changes nothing`() {
        val document = docWithThree()
        assertEquals(document, document.duplicate(999L))
    }

    @Test
    fun `undo and redo at the ends are no-ops`() {
        val history = History()
        assertEquals(history, history.undo())
        assertEquals(history, history.redo())
    }
}
