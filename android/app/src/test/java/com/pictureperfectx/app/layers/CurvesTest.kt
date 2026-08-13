package com.pictureperfectx.app.layers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Curve editing looks right until a point is dragged past its neighbour, at which point the spline
 * folds back on itself and the picture inverts somewhere in the middle of a tone range. That is not
 * something to discover by dragging on a phone, so the ordering rules are held here.
 */
class CurvesTest {

    private fun ordered(points: List<CurvePoint>) =
        points.zipWithNext().all { (a, b) -> b.x > a.x }

    @Test
    fun `a fresh curve is a straight line that changes nothing`() {
        assertTrue(CurveSpec().isIdentity)
        assertTrue(Curves.IDENTITY.all { it.x == it.y })
    }

    @Test
    fun `bending any channel stops it being an identity`() {
        CurveChannel.entries.forEach { channel ->
            val bent = CurveSpec().with(channel, Curves.move(Curves.IDENTITY, 1, CurvePoint(0.5f, 0.8f)))
            assertFalse("${channel.label} should count as an edit", bent.isIdentity)
        }
    }

    @Test
    fun `each channel reads back what was written to it`() {
        CurveChannel.entries.forEach { channel ->
            val points = Curves.add(Curves.IDENTITY, CurvePoint(0.25f, 0.4f))
            assertEquals(points, CurveSpec().with(channel, points).channel(channel))
        }
    }

    @Test
    fun `writing one channel leaves the others straight`() {
        val spec = CurveSpec().with(CurveChannel.Red, Curves.move(Curves.IDENTITY, 1, CurvePoint(0.5f, 0.9f)))
        assertEquals(Curves.IDENTITY, spec.rgb)
        assertEquals(Curves.IDENTITY, spec.green)
        assertEquals(Curves.IDENTITY, spec.blue)
    }

    @Test
    fun `the ends move only vertically`() {
        // A curve has to map every input level. An end dragged inwards would leave the levels
        // beyond it undefined.
        val lifted = Curves.move(Curves.IDENTITY, 0, CurvePoint(0.4f, 0.3f))
        assertEquals(0f, lifted.first().x, 0.0001f)
        assertEquals(0.3f, lifted.first().y, 0.0001f)

        val pulled = Curves.move(Curves.IDENTITY, 2, CurvePoint(0.6f, 0.7f))
        assertEquals(1f, pulled.last().x, 0.0001f)
        assertEquals(0.7f, pulled.last().y, 0.0001f)
    }

    @Test
    fun `a point cannot be dragged past its neighbours`() {
        val points = Curves.add(Curves.add(Curves.IDENTITY, CurvePoint(0.25f, 0.25f)), CurvePoint(0.75f, 0.75f))
        // points: 0, 0.25, 0.5, 0.75, 1 — try to drag the middle one off both ends.
        val left = Curves.move(points, 2, CurvePoint(-5f, 0.5f))
        assertTrue("still in order after a hard left drag", ordered(left))
        assertTrue(left[2].x >= points[1].x + Curves.MIN_GAP - 0.0001f)

        val right = Curves.move(points, 2, CurvePoint(5f, 0.5f))
        assertTrue("still in order after a hard right drag", ordered(right))
        assertTrue(right[2].x <= points[3].x - Curves.MIN_GAP + 0.0001f)
    }

    @Test
    fun `output is clamped to the visible range`() {
        val high = Curves.move(Curves.IDENTITY, 1, CurvePoint(0.5f, 9f))
        val low = Curves.move(Curves.IDENTITY, 1, CurvePoint(0.5f, -9f))
        assertEquals(1f, high[1].y, 0.0001f)
        assertEquals(0f, low[1].y, 0.0001f)
    }

    @Test
    fun `adding puts a point in its place along the axis`() {
        val points = Curves.add(Curves.IDENTITY, CurvePoint(0.25f, 0.1f))
        assertEquals(4, points.size)
        assertEquals(1, points.indexOfFirst { it.x == 0.25f })
        assertTrue(ordered(points))
    }

    @Test
    fun `adding on top of an existing point does nothing`() {
        // Two points at the same input level have no single answer for what that level maps to.
        val points = Curves.add(Curves.IDENTITY, CurvePoint(0.5f, 0.9f))
        assertSame(Curves.IDENTITY, points)
    }

    @Test
    fun `adding past either end still lands inside the curve`() {
        val before = Curves.add(Curves.IDENTITY, CurvePoint(-1f, 0.5f))
        val after = Curves.add(Curves.IDENTITY, CurvePoint(2f, 0.5f))
        assertSame("0 is already taken", Curves.IDENTITY, before)
        assertSame("1 is already taken", Curves.IDENTITY, after)
    }

    @Test
    fun `removing takes out the point asked for`() {
        val points = Curves.add(Curves.IDENTITY, CurvePoint(0.25f, 0.1f))
        val fewer = Curves.remove(points, 1)
        assertEquals(3, fewer.size)
        assertTrue(fewer.none { it.x == 0.25f })
    }

    @Test
    fun `the ends can never be removed`() {
        val points = Curves.add(Curves.IDENTITY, CurvePoint(0.25f, 0.1f))
        assertEquals(points, Curves.remove(points, 0))
        assertEquals(points, Curves.remove(points, points.lastIndex))
    }

    @Test
    fun `a curve is never reduced below a shape it can be bent back from`() {
        // Two points is a straight line that can only be tilted — there would be no way back to a
        // curve without starting the layer over.
        assertEquals(Curves.IDENTITY, Curves.remove(Curves.IDENTITY, 1))
    }

    @Test
    fun `the nearest point is found only within reach`() {
        val points = Curves.IDENTITY
        assertEquals(1, Curves.nearest(points, CurvePoint(0.52f, 0.48f), radius = 0.1f))
        assertEquals(-1, Curves.nearest(points, CurvePoint(0.3f, 0.9f), radius = 0.1f))
        assertEquals(0, Curves.nearest(points, CurvePoint(0.01f, 0.01f), radius = 0.1f))
    }

    @Test
    fun `an out-of-range index is ignored rather than crashing`() {
        assertEquals(Curves.IDENTITY, Curves.move(Curves.IDENTITY, 9, CurvePoint(0.5f, 0.5f)))
        assertEquals(Curves.IDENTITY, Curves.remove(Curves.IDENTITY, -1))
    }
}
