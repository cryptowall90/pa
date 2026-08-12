package com.pictureperfectx.app.layers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outline is what the user sees instead of the old fill, so its shape has to be right rather
 * than roughly right. Two failures matter and neither is easy to judge on a phone: a contour that
 * doesn't close leaves a gap in the boundary, and a contour that snaps to grid lines turns a
 * smoothly drawn diagonal into a staircase — which is most of what read as an inaccurate lasso.
 */
class MaskOutlineTest {

    private fun maskOf(columns: Int, rows: Int, inside: (Int, Int) -> Boolean): Mask {
        val coverage = FloatArray(columns * rows) { index ->
            if (inside(index % columns, index / columns)) 1f else 0f
        }
        return Mask(columns, rows, coverage)
    }

    /** Every corner of a closed contour is shared by an even number of segments. */
    private fun isClosed(edges: List<MaskEdge>): Boolean {
        val degree = HashMap<Pair<Float, Float>, Int>()
        edges.forEach { edge ->
            degree.merge(edge.x0 to edge.y0, 1, Int::plus)
            degree.merge(edge.x1 to edge.y1, 1, Int::plus)
        }
        return degree.values.all { it % 2 == 0 }
    }

    private fun xs(edges: List<MaskEdge>) = edges.flatMap { listOf(it.x0, it.x1) }

    private fun ys(edges: List<MaskEdge>) = edges.flatMap { listOf(it.y0, it.y1) }

    @Test
    fun `a rectangle is traced by a closed contour on its edges`() {
        val mask = maskOf(8, 8) { column, row -> column in 2..5 && row in 2..5 }
        val edges = MaskOutline.segments(mask)
        assertTrue("the contour must close", isClosed(edges))
        assertEquals("left edge", 2 / 8f, xs(edges).min(), 0.001f)
        assertEquals("right edge", 6 / 8f, xs(edges).max(), 0.001f)
        assertEquals("top edge", 2 / 8f, ys(edges).min(), 0.001f)
        assertEquals("bottom edge", 6 / 8f, ys(edges).max(), 0.001f)
    }

    @Test
    fun `a selection touching the photo edge closes against the border`() {
        val mask = maskOf(8, 8) { column, _ -> column < 4 }
        val edges = MaskOutline.segments(mask)
        assertTrue("the contour must close", isClosed(edges))
        assertEquals("it should reach the left border", 0f, xs(edges).min(), 0.001f)
        assertEquals("and stop at the halfway split", 0.5f, xs(edges).max(), 0.001f)
        assertEquals(0f, ys(edges).min(), 0.001f)
        assertEquals(1f, ys(edges).max(), 0.001f)
    }

    @Test
    fun `a fully covered mask traces only the image border`() {
        val edges = MaskOutline.segments(Mask.full(8, 8))
        assertTrue(isClosed(edges))
        assertEquals(0f, xs(edges).min(), 0.001f)
        assertEquals(1f, xs(edges).max(), 0.001f)
        assertTrue(
            "nothing should run through the middle",
            edges.none { it.x0 > 0.01f && it.x0 < 0.99f && it.y0 > 0.01f && it.y0 < 0.99f },
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
    fun `the crossing is interpolated rather than snapped to a grid line`() {
        // Coverage ramps 0.4 to 0.9 between columns 3 and 4, so the halfway crossing sits a fifth
        // of the way between their centres — 0.4625 — and nowhere near a cell boundary. Snapping
        // would put it at 0.5, which is the staircase this exists to avoid.
        val row = floatArrayOf(0f, 0f, 0f, 0.4f, 0.9f, 1f, 1f, 1f)
        val coverage = FloatArray(64) { row[it % 8] }
        val edges = MaskOutline.segments(Mask(8, 8, coverage))
        assertEquals(0.4625f, xs(edges).min(), 0.0005f)
    }

    @Test
    fun `a diagonal boundary comes out diagonal, not as a staircase`() {
        val mask = maskOf(64, 64) { column, row -> column <= row }
        val edges = MaskOutline.segments(mask)
        assertTrue(isClosed(edges))
        // Away from the borders the whole contour is the diagonal, so every segment there should
        // be sloped. A cell-edge tracing would make all of them axis-aligned.
        val alongTheDiagonal = edges.filter { edge ->
            val midX = (edge.x0 + edge.x1) / 2
            val midY = (edge.y0 + edge.y1) / 2
            midX in 0.15f..0.85f && midY in 0.15f..0.85f
        }
        assertTrue("expected segments along the diagonal", alongTheDiagonal.size > 20)
        val sloped = alongTheDiagonal.count { it.x0 != it.x1 && it.y0 != it.y1 }
        assertEquals(
            "every segment along the diagonal should be sloped",
            alongTheDiagonal.size,
            sloped,
        )
    }

    @Test
    fun `a lasso edge is traced to well within a cell of where it was drawn`() {
        // Deliberately off-grid edges: nothing here lines up with a cell boundary, so the contour
        // can only land in the right place by interpolating.
        val columns = 64
        val left = 0.2571f
        val right = 0.7429f
        val mask = MaskLasso.fill(
            Mask.blank(columns, columns),
            listOf(
                MaskPoint(left, 0.3313f),
                MaskPoint(right, 0.3313f),
                MaskPoint(right, 0.6687f),
                MaskPoint(left, 0.6687f),
            ),
        )
        val edges = MaskOutline.segments(mask)
        assertTrue("the contour must close", isClosed(edges))

        val cell = 1f / columns
        assertTrue(
            "left edge was ${xs(edges).min()}, drawn at $left",
            kotlin.math.abs(xs(edges).min() - left) < cell * 0.25f,
        )
        assertTrue(
            "right edge was ${xs(edges).max()}, drawn at $right",
            kotlin.math.abs(xs(edges).max() - right) < cell * 0.25f,
        )
    }
}
