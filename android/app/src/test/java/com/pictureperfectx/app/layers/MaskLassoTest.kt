package com.pictureperfectx.app.layers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lasso fill is normalized-coordinate maths, so CI can run it — which matters more here than
 * usual. A fill that leaks past its outline, or that drops the middle of a concave shape, is hard
 * to judge on a phone and easy to pin down at this size.
 */
class MaskLassoTest {

    private fun grid() = Mask.blank(32, 32)

    /** A square from (0.25, 0.25) to (0.75, 0.75). */
    private fun square() = listOf(
        MaskPoint(0.25f, 0.25f),
        MaskPoint(0.75f, 0.25f),
        MaskPoint(0.75f, 0.75f),
        MaskPoint(0.25f, 0.75f),
    )

    @Test
    fun `a square fills its inside and nothing outside`() {
        val mask = MaskLasso.fill(grid(), square())
        assertEquals("the centre should be solid", 1f, mask.coverageAt(16, 16), 0.001f)
        assertEquals("outside the corner should be untouched", 0f, mask.coverageAt(2, 2), 0.001f)
        assertEquals(0f, mask.coverageAt(29, 29), 0.001f)
        assertEquals("just outside the left edge", 0f, mask.coverageAt(6, 16), 0.001f)
    }

    @Test
    fun `the interior is filled all the way across, not just at its outline`() {
        val mask = MaskLasso.fill(grid(), square())
        val row = 16
        val inside = (9..22).map { mask.coverageAt(it, row) }
        assertTrue("every cell across the middle should be covered: $inside", inside.all { it > 0.99f })
    }

    @Test
    fun `a concave shape leaves its notch empty`() {
        // A U: two legs joined at the bottom, with a gap between them at the top. Filling this
        // solid is the classic symptom of a fill that ignores the even-odd rule.
        val u = listOf(
            MaskPoint(0.2f, 0.1f),
            MaskPoint(0.4f, 0.1f),
            MaskPoint(0.4f, 0.7f),
            MaskPoint(0.6f, 0.7f),
            MaskPoint(0.6f, 0.1f),
            MaskPoint(0.8f, 0.1f),
            MaskPoint(0.8f, 0.9f),
            MaskPoint(0.2f, 0.9f),
        )
        val mask = MaskLasso.fill(grid(), u)
        assertTrue("the left leg should be filled", mask.coverageAt(9, 8) > 0.9f)
        assertTrue("the right leg should be filled", mask.coverageAt(22, 8) > 0.9f)
        assertEquals("the notch between them must stay empty", 0f, mask.coverageAt(16, 8), 0.001f)
        assertTrue("the base joining them should be filled", mask.coverageAt(16, 25) > 0.9f)
    }

    @Test
    fun `adding a second shape keeps the first`() {
        val left = MaskLasso.fill(grid(), square())
        val right = listOf(
            MaskPoint(0.8f, 0.05f),
            MaskPoint(0.95f, 0.05f),
            MaskPoint(0.95f, 0.2f),
            MaskPoint(0.8f, 0.2f),
        )
        val both = MaskLasso.fill(left, right, SelectionMode.Add)
        assertTrue("the original square survives", both.coverageAt(16, 16) > 0.99f)
        assertTrue("the added shape is covered too", both.coverageAt(28, 4) > 0.9f)
    }

    @Test
    fun `replacing discards what was there before`() {
        val first = MaskLasso.fill(grid(), square())
        val elsewhere = listOf(
            MaskPoint(0.8f, 0.05f),
            MaskPoint(0.95f, 0.05f),
            MaskPoint(0.95f, 0.2f),
            MaskPoint(0.8f, 0.2f),
        )
        val replaced = MaskLasso.fill(first, elsewhere, SelectionMode.Replace)
        assertEquals("the old square is gone", 0f, replaced.coverageAt(16, 16), 0.001f)
        assertTrue("the new shape is there", replaced.coverageAt(28, 4) > 0.9f)
    }

    @Test
    fun `subtracting cuts a hole without going negative`() {
        val filled = MaskLasso.fill(grid(), square())
        val middle = listOf(
            MaskPoint(0.4f, 0.4f),
            MaskPoint(0.6f, 0.4f),
            MaskPoint(0.6f, 0.6f),
            MaskPoint(0.4f, 0.6f),
        )
        val cut = MaskLasso.fill(filled, middle, SelectionMode.Subtract)
        assertEquals("the middle is cut away", 0f, cut.coverageAt(16, 16), 0.001f)
        assertTrue("the surrounding ring survives", cut.coverageAt(10, 16) > 0.9f)
        assertTrue("nothing goes negative", cut.coverage.all { it >= 0f })
    }

    @Test
    fun `subtracting from an untouched mask stays at zero`() {
        val cut = MaskLasso.fill(grid(), square(), SelectionMode.Subtract)
        assertTrue(cut.coverage.all { it == 0f })
    }

    @Test
    fun `the edge is antialiased rather than a hard step`() {
        // An edge at 0.255 falls part-way across a cell, so that cell should be partly covered
        // instead of snapping to fully in or fully out.
        val offset = listOf(
            MaskPoint(0.255f, 0.25f),
            MaskPoint(0.755f, 0.25f),
            MaskPoint(0.755f, 0.75f),
            MaskPoint(0.255f, 0.75f),
        )
        val mask = MaskLasso.fill(grid(), offset)
        val edge = mask.coverageAt(8, 16)
        assertTrue("the boundary cell should be partial, was $edge", edge > 0f && edge < 1f)
    }

    @Test
    fun `adding the same shape twice does not push coverage past one`() {
        var mask = MaskLasso.fill(grid(), square())
        mask = MaskLasso.fill(mask, square(), SelectionMode.Add)
        assertTrue(mask.coverage.all { it <= 1f })
        assertEquals(1f, mask.coverageAt(16, 16), 0.001f)
    }

    @Test
    fun `a path with fewer than three points does nothing`() {
        val blank = grid()
        assertEquals(blank, MaskLasso.fill(blank, emptyList()))
        assertEquals(blank, MaskLasso.fill(blank, listOf(MaskPoint(0.5f, 0.5f))))
        assertEquals(
            blank,
            MaskLasso.fill(blank, listOf(MaskPoint(0.2f, 0.2f), MaskPoint(0.8f, 0.8f))),
        )
    }

    @Test
    fun `a path running outside the image is clipped instead of wrapping`() {
        val overhanging = listOf(
            MaskPoint(-0.5f, -0.5f),
            MaskPoint(0.5f, -0.5f),
            MaskPoint(0.5f, 0.5f),
            MaskPoint(-0.5f, 0.5f),
        )
        val mask = MaskLasso.fill(grid(), overhanging)
        assertTrue("the visible part is covered", mask.coverageAt(4, 4) > 0.9f)
        assertEquals("the far side must be untouched", 0f, mask.coverageAt(28, 28), 0.001f)
    }

    @Test
    fun `a selection grid keeps its cells square on a wide image`() {
        val mask = Mask.forRatio(16f / 9f, resolution = 192)
        assertEquals(192, mask.columns)
        assertEquals(108, mask.rows)
        // A square drawn in normalized space must come out square in cells too, which is the whole
        // reason the grid follows the image's ratio.
        val filled = MaskLasso.fill(mask, listOf(
            MaskPoint(0.4f, 0.4f),
            MaskPoint(0.6f, 0.4f),
            MaskPoint(0.6f, 0.6f),
            MaskPoint(0.4f, 0.6f),
        ))
        val across = (0 until filled.columns).count { filled.coverageAt(it, filled.rows / 2) > 0.5f }
        val down = (0 until filled.rows).count { filled.coverageAt(filled.columns / 2, it) > 0.5f }
        assertEquals(
            "the same normalized span should cover the same fraction of each axis",
            across.toFloat() / filled.columns,
            down.toFloat() / filled.rows,
            0.02f,
        )
    }

    @Test
    fun `a selection grid is taller than wide for a portrait image`() {
        val mask = Mask.forRatio(3f / 4f, resolution = 192)
        assertEquals(192, mask.rows)
        assertEquals(144, mask.columns)
        assertTrue("selections start empty", mask.coverage.all { it == 0f })
        assertTrue("selections feather less than a brushed mask", mask.feather < Mask().feather)
    }
}
