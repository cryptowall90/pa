package com.pictureperfectx.app.layers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Applying a drawn area to a layer that already has one.
 *
 * This is what New, Add and Subtract finally *mean* against a layer, and it is the operation that
 * was missing: a selection could be drawn but never reached the layer, so Add appeared to do
 * nothing. Getting the combination wrong destroys an area rather than looking wrong, so it is
 * pinned here rather than judged on a phone.
 */
class MaskCombineTest {

    private val size = 32

    private fun blank() = Mask.blank(size, size)

    /** A filled box, as coverage of exactly 0 or 1 so the arithmetic is checkable by eye. */
    private fun box(columns: IntRange, rows: IntRange): Mask {
        val coverage = FloatArray(size * size)
        rows.forEach { row -> columns.forEach { column -> coverage[row * size + column] = 1f } }
        return blank().copy(coverage = coverage)
    }

    private fun Mask.covered() = (0 until columns * rows).count { coverage[it] > 0.5f }

    @Test
    fun `adding grows the area to cover both`() {
        val left = box(2..9, 2..9)
        val right = box(20..27, 2..9)
        val both = left.combinedWith(right, SelectionMode.Add)

        assertEquals(8 * 8 * 2, both.covered())
        assertEquals("the original is still there", 1f, both.coverageAt(4, 4), 0.001f)
        assertEquals("and so is the new one", 1f, both.coverageAt(24, 4), 0.001f)
    }

    @Test
    fun `subtracting cuts the new shape out of the old`() {
        val big = box(2..17, 2..17)
        val bite = box(10..17, 2..17)
        val left = big.combinedWith(bite, SelectionMode.Subtract)

        assertEquals(8 * 16, left.covered())
        assertEquals("the far side survives", 1f, left.coverageAt(4, 8), 0.001f)
        assertEquals("the bitten side is gone", 0f, left.coverageAt(14, 8), 0.001f)
    }

    @Test
    fun `replacing takes the new area whole, shape and all`() {
        // A lasso must stay draggable after it has been applied, so Replace adopts the incoming
        // mask rather than copying its numbers into the old one.
        val old = box(2..9, 2..9)
        val drawn = MaskLasso.trace(
            blank(),
            listOf(MaskPoint(0.5f, 0.5f), MaskPoint(0.9f, 0.6f), MaskPoint(0.7f, 0.9f)),
        )
        val replaced = old.combinedWith(drawn, SelectionMode.Replace)

        assertEquals("the old area is gone", 0f, replaced.coverageAt(4, 4), 0.001f)
        assertNotNull("the points came along", replaced.path)
        assertEquals(drawn.path, replaced.path)
    }

    @Test
    fun `combining leaves no shape, because none describes the result`() {
        val drawn = MaskLasso.trace(
            blank(),
            listOf(MaskPoint(0.5f, 0.5f), MaskPoint(0.9f, 0.6f), MaskPoint(0.7f, 0.9f)),
        )
        val grown = box(2..9, 2..9).combinedWith(drawn, SelectionMode.Add)
        assertNull("handles on a shape this isn't would be worse than none", grown.path)
        assertNull(grown.gradient)
    }

    @Test
    fun `a layer with no area of its own simply takes the drawn one`() {
        // An unmasked layer renders as "applies everywhere". As an operand that is "no area yet",
        // so adding to it is the drawn shape rather than the whole frame.
        val drawn = box(4..11, 4..11)
        assertEquals(drawn, Mask().combinedWith(drawn, SelectionMode.Add))
        assertEquals(drawn, Mask().combinedWith(drawn, SelectionMode.Replace))
    }

    @Test
    fun `subtracting from a layer with no area leaves it alone`() {
        // Cutting a hole in "everywhere" via an empty mask would silently make the layer vanish.
        val unmasked = Mask()
        assertEquals(unmasked, unmasked.combinedWith(box(4..11, 4..11), SelectionMode.Subtract))
    }

    @Test
    fun `adding to an inverted area grows what you can see, not what is underneath`() {
        // The raw numbers of an inverted mask are the opposite of the area on screen. Combining
        // those would make Add subtract, which reads as the button being broken.
        val middle = box(12..19, 12..19).copy(inverted = true)
        assertEquals("inverted, the middle is the hole", 0f, middle.coverageAt(16, 16), 0.001f)
        assertEquals("and the outside is covered", 1f, middle.coverageAt(2, 2), 0.001f)

        val patched = middle.combinedWith(box(14..17, 14..17), SelectionMode.Add)
        assertEquals("the hole is filled in", 1f, patched.coverageAt(16, 16), 0.001f)
        assertEquals("the outside is still covered", 1f, patched.coverageAt(2, 2), 0.001f)
        assertTrue("and it is no longer inside out", !patched.inverted)
    }

    @Test
    fun `areas measured on different grids are not silently mixed`() {
        // Coverage would land offset from where it was drawn; taking the incoming area whole is
        // wrong in a visible way rather than a subtle one.
        val coarse = Mask.blank(16, 16)
        val fine = box(4..11, 4..11)
        assertEquals(fine, coarse.combinedWith(fine, SelectionMode.Add))
    }

    @Test
    fun `combining nothing into an area changes nothing`() {
        val existing = box(4..11, 4..11)
        assertEquals(existing, existing.combinedWith(Mask(), SelectionMode.Add))
        assertEquals(existing, existing.combinedWith(Mask(), SelectionMode.Subtract))
    }
}
