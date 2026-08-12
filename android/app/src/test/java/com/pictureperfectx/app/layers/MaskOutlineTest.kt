package com.pictureperfectx.app.layers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outline is what the user sees instead of the old fill, so its shape has to be right rather
 * than roughly right — a boundary that doubles back on itself or leaves a gap at the photo's edge
 * looks like a bug in the selection, not in the drawing.
 */
class MaskOutlineTest {

    /** A mask with [inside] returning true for the covered cells. */
    private fun maskOf(columns: Int, rows: Int, inside: (Int, Int) -> Boolean): Mask {
        val coverage = FloatArray(columns * rows) { index ->
            if (inside(index % columns, index / columns)) 1f else 0f
        }
        return Mask(columns, rows, coverage)
    }

    @Test
    fun `a rectangle is traced by exactly its perimeter`() {
        // Columns 2..5 and rows 2..5: a 4x4 block, so 16 cell edges around it.
        val mask = maskOf(8, 8) { column, row -> column in 2..5 && row in 2..5 }
        val edges = MaskOutline.segments(mask)
        assertEquals("four sides of four cells each", 16, edges.size)

        // Every segment should sit on the block's boundary, never inside it.
        val left = 2 / 8f
        val right = 6 / 8f
        val top = 2 / 8f
        val bottom = 6 / 8f
        edges.forEach { edge ->
            val onVerticalSide = edge.x0 == edge.x1 && (edge.x0 == left || edge.x0 == right)
            val onHorizontalSide = edge.y0 == edge.y1 && (edge.y0 == top || edge.y0 == bottom)
            assertTrue("stray segment at $edge", onVerticalSide || onHorizontalSide)
        }
    }

    @Test
    fun `a selection touching the photo edge still closes`() {
        // Covering the left half means the outline has to run along the image border, which only
        // happens if off-grid neighbours count as outside.
        val mask = maskOf(8, 8) { column, _ -> column < 4 }
        val edges = MaskOutline.segments(mask)
        assertTrue("the left border should be traced", edges.any { it.x0 == 0f && it.x1 == 0f })
        assertTrue("the top border should be traced", edges.any { it.y0 == 0f && it.y1 == 0f })
        assertTrue("the split down the middle should be traced", edges.any { it.x0 == 0.5f })
        assertEquals("8 down each long side, 4 across each short one", 24, edges.size)
    }

    @Test
    fun `a hole in the middle is outlined as well as the outside`() {
        val mask = maskOf(8, 8) { column, row -> !(column in 3..4 && row in 3..4) }
        val edges = MaskOutline.segments(mask)
        // The image border is 8 per side, the hole's rim is 2 per side.
        assertEquals(32 + 8, edges.size)
        assertTrue("the hole's own rim is traced", edges.any { it.x0 == 3 / 8f && it.x1 == 3 / 8f })
    }

    @Test
    fun `a fully covered mask traces only the image border`() {
        val edges = MaskOutline.segments(Mask.full(8, 8))
        assertEquals(32, edges.size)
        assertTrue(
            "nothing should run through the middle",
            edges.none { it.x0 > 0f && it.x0 < 1f && it.x0 == it.x1 },
        )
    }

    @Test
    fun `nothing covered means nothing to draw`() {
        assertTrue(MaskOutline.segments(Mask.blank(8, 8)).isEmpty())
    }

    @Test
    fun `an unmasked layer has no outline even though it applies everywhere`() {
        // Mask() is empty, which renders as "covers the whole photo" — but there is no drawn area,
        // and framing the entire picture would suggest the user had selected it.
        assertTrue(MaskOutline.segments(Mask()).isEmpty())
    }

    @Test
    fun `partial coverage is inside only once it passes the threshold`() {
        val mask = Mask(4, 1, floatArrayOf(0.1f, 0.4f, 0.6f, 0.9f))
        val edges = MaskOutline.segments(mask)
        // Cells 2 and 3 are in, 0 and 1 are out: a border round the right-hand pair only.
        assertTrue("the boundary falls between cells 1 and 2", edges.any { it.x0 == 0.5f && it.x1 == 0.5f })
        assertTrue("nothing is traced at the left edge", edges.none { it.x0 == 0f && it.x1 == 0f })
        assertEquals("two sides of two cells, plus two ends", 6, edges.size)
    }

    @Test
    fun `a lasso selection produces a closed loop`() {
        // Every vertex of a closed outline is shared by exactly two segments; an odd count means a
        // dangling end, which would show as a gap on screen.
        val mask = MaskLasso.fill(
            Mask.blank(32, 32),
            listOf(
                MaskPoint(0.25f, 0.25f),
                MaskPoint(0.75f, 0.3f),
                MaskPoint(0.7f, 0.8f),
                MaskPoint(0.3f, 0.7f),
            ),
        )
        val edges = MaskOutline.segments(mask)
        assertTrue("the lasso should produce an outline", edges.isNotEmpty())
        val endpoints = HashMap<Pair<Float, Float>, Int>()
        edges.forEach { edge ->
            endpoints.merge(edge.x0 to edge.y0, 1, Int::plus)
            endpoints.merge(edge.x1 to edge.y1, 1, Int::plus)
        }
        assertTrue(
            "every corner should join an even number of segments",
            endpoints.values.all { it % 2 == 0 },
        )
    }
}
