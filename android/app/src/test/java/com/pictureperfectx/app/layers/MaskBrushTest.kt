package com.pictureperfectx.app.layers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The brush is normalized-coordinate maths, so CI can run it. Its failure modes are subtle on a
 * phone — a stroke that erases where it should paint, or dabs that show their rims — and easy to
 * pin down here.
 */
class MaskBrushTest {

    private fun blank() = Mask.blank(32, 32)

    @Test
    fun `painting the centre covers the middle and leaves the corners alone`() {
        val mask = MaskBrush.paint(blank(), x = 0.5f, y = 0.5f, radius = 0.2f)
        assertEquals(1f, mask.coverageAt(16, 16), 0.001f)
        assertEquals(0f, mask.coverageAt(0, 0), 0.001f)
        assertEquals(0f, mask.coverageAt(31, 31), 0.001f)
    }

    @Test
    fun `the dab fades towards its rim rather than ending abruptly`() {
        val mask = MaskBrush.paint(blank(), x = 0.5f, y = 0.5f, radius = 0.25f)
        val centre = mask.coverageAt(16, 16)
        val edge = mask.coverageAt(23, 16) // just inside the rim
        assertTrue("centre should be solid", centre > 0.95f)
        assertTrue("rim should be partial, was $edge", edge > 0f && edge < centre)
    }

    @Test
    fun `overlapping dabs build a stroke without dark seams`() {
        var mask = blank()
        mask = MaskBrush.paint(mask, 0.4f, 0.5f, 0.15f)
        val afterFirst = mask.coverageAt(16, 16)
        mask = MaskBrush.paint(mask, 0.6f, 0.5f, 0.15f)
        assertTrue(
            "the overlap must not dip below either dab",
            mask.coverageAt(16, 16) >= afterFirst,
        )
    }

    @Test
    fun `erasing removes coverage that was painted`() {
        val painted = MaskBrush.paint(blank(), 0.5f, 0.5f, 0.3f)
        val erased = MaskBrush.paint(painted, 0.5f, 0.5f, 0.3f, erase = true)
        assertEquals(0f, erased.coverageAt(16, 16), 0.001f)
    }

    @Test
    fun `erasing an untouched mask stays at zero rather than going negative`() {
        val erased = MaskBrush.paint(blank(), 0.5f, 0.5f, 0.3f, erase = true)
        assertTrue(erased.coverage.all { it >= 0f })
    }

    @Test
    fun `painting never exceeds full coverage`() {
        var mask = blank()
        repeat(5) { mask = MaskBrush.paint(mask, 0.5f, 0.5f, 0.2f) }
        assertTrue(mask.coverage.all { it <= 1f })
    }

    @Test
    fun `painting an empty mask gives it a coverage grid to hold the stroke`() {
        val mask = MaskBrush.paint(Mask(8, 8), 0.5f, 0.5f, 0.3f)
        assertTrue(!mask.isEmpty)
        assertEquals(64, mask.coverage.size)
    }

    @Test
    fun `strokes near an edge are clipped instead of wrapping or crashing`() {
        val mask = MaskBrush.paint(blank(), x = 0.0f, y = 0.0f, radius = 0.2f)
        assertTrue("the corner should be painted", mask.coverageAt(0, 0) > 0f)
        assertEquals("the far side must be untouched", 0f, mask.coverageAt(31, 31), 0.001f)
    }

    @Test
    fun `a zero radius does nothing`() {
        val mask = blank()
        assertEquals(mask, MaskBrush.paint(mask, 0.5f, 0.5f, 0f))
    }

    @Test
    fun `the dab is round even when the grid is not square`() {
        val mask = MaskBrush.paint(Mask.blank(64, 16), x = 0.5f, y = 0.5f, radius = 0.25f)
        // Equal normalized distances along each axis should land on equal coverage.
        val horizontal = mask.coverageAt(32 + 12, 8)
        val vertical = mask.coverageAt(32, 8 + 3)
        assertEquals(horizontal, vertical, 0.15f)
    }
}
