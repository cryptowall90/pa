package com.pictureperfectx.app.layers

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

    @Test
    fun `undo returns the previous state and redo replays it`() {
        val first = Document().add(::tone)
        val second = first.add(::tone)
        val history = History().push(first).push(second)

        val undone = history.undo()
        assertEquals(first, undone.current)
        assertTrue(undone.canRedo)
        assertEquals(second, undone.redo().current)
    }

    @Test
    fun `pushing an identical state does not add a history step`() {
        val document = Document().add(::tone)
        val history = History().push(document)
        assertEquals(history, history.push(document))
    }

    @Test
    fun `a new edit after undo drops the redo branch`() {
        val first = Document().add(::tone)
        val second = first.add(::tone)
        val branched = History().push(first).push(second).undo().push(first.add(::tone))
        assertFalse("the redone future belonged to an abandoned timeline", branched.canRedo)
    }

    @Test
    fun `undo and redo at the ends are no-ops`() {
        val history = History()
        assertEquals(history, history.undo())
        assertEquals(history, history.redo())
    }
}
