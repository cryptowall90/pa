package com.pictureperfectx.app.layers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The box around a drawn area is what lets something be placed *inside* it — a gradient added after
 * a lasso should ramp across the selection rather than across the whole frame with most of it
 * clipped away outside.
 */
class MaskBoundsTest {

    private fun grid() = Mask.blank(32, 32)

    @Test
    fun `a square area reports the square`() {
        val mask = MaskLasso.fill(grid(), listOf(
            MaskPoint(0.25f, 0.25f),
            MaskPoint(0.75f, 0.25f),
            MaskPoint(0.75f, 0.75f),
            MaskPoint(0.25f, 0.75f),
        ))
        val bounds = mask.coveredBounds()
        assertNotNull(bounds)
        assertEquals(0.25f, bounds!!.left, 0.04f)
        assertEquals(0.25f, bounds.top, 0.04f)
        assertEquals(0.75f, bounds.right, 0.04f)
        assertEquals(0.75f, bounds.bottom, 0.04f)
        assertEquals(0.5f, bounds.centreX, 0.02f)
        assertEquals(0.5f, bounds.centreY, 0.02f)
    }

    @Test
    fun `an area drawn off to one side reports that side`() {
        val mask = MaskLasso.fill(grid(), listOf(
            MaskPoint(0.6f, 0.1f),
            MaskPoint(0.9f, 0.1f),
            MaskPoint(0.9f, 0.4f),
            MaskPoint(0.6f, 0.4f),
        ))
        val bounds = mask.coveredBounds()!!
        assertEquals(0.75f, bounds.centreX, 0.04f)
        assertEquals(0.25f, bounds.centreY, 0.04f)
    }

    @Test
    fun `nothing drawn is null rather than the whole frame`() {
        // An empty mask renders as "applies everywhere", but the question here is where the user
        // pointed, and an empty mask is them not having pointed.
        assertNull(Mask().coveredBounds())
        assertNull("a grid with nothing painted into it", grid().coveredBounds())
    }

    @Test
    fun `an inverted area reports the part that is now covered`() {
        val middle = MaskLasso.fill(grid(), listOf(
            MaskPoint(0.4f, 0.4f),
            MaskPoint(0.6f, 0.4f),
            MaskPoint(0.6f, 0.6f),
            MaskPoint(0.4f, 0.6f),
        ))
        val inverted = middle.copy(inverted = true)
        val bounds = inverted.coveredBounds()!!
        assertEquals("everything outside the middle is covered", 0f, bounds.left, 0.001f)
        assertEquals(1f, bounds.right, 0.001f)
    }

    @Test
    fun `a fully covered mask reports the whole frame`() {
        val bounds = Mask.full(32, 32).coveredBounds()!!
        assertEquals(0f, bounds.left, 0.001f)
        assertEquals(0f, bounds.top, 0.001f)
        assertEquals(1f, bounds.right, 0.001f)
        assertEquals(1f, bounds.bottom, 0.001f)
    }

    @Test
    fun `the soft edge of a brushed area does not stretch the box to the corners`() {
        val brushed = MaskBrush.paint(grid(), 0.5f, 0.5f, 0.15f)
        val bounds = brushed.coveredBounds()!!
        assertEquals(0.5f, bounds.centreX, 0.05f)
        assertEquals(0.5f, bounds.centreY, 0.05f)
    }
}
