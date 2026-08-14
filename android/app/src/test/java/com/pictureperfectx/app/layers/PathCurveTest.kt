package com.pictureperfectx.app.layers

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lasso's curve decides three things at once — where the mask's edge falls, where the outline is
 * drawn, and where the handles sit. All three are judged by eye on a phone otherwise, and the way
 * this goes wrong is subtle: a curve that misses its own control points lets a dragged handle drift
 * away from the shape it is supposed to be holding.
 */
class PathCurveTest {

    private fun square() = listOf(
        MaskPoint(0.25f, 0.25f),
        MaskPoint(0.75f, 0.25f),
        MaskPoint(0.75f, 0.75f),
        MaskPoint(0.25f, 0.75f),
    )

    @Test
    fun `the curve passes through every point it was given`() {
        val points = square()
        val curve = PathCurve.smooth(points, segments = 8)
        points.forEachIndexed { index, point ->
            val sampled = curve[index * 8]
            assertEquals("point $index x", point.x, sampled.x, 1e-5f)
            assertEquals("point $index y", point.y, sampled.y, 1e-5f)
        }
    }

    @Test
    fun `there are as many samples as points times segments`() {
        assertEquals(4 * 8, PathCurve.smooth(square(), segments = 8).size)
        assertEquals(4 * 3, PathCurve.smooth(square(), segments = 3).size)
    }

    @Test
    fun `fewer than three points is not a loop and comes back untouched`() {
        val two = listOf(MaskPoint(0.2f, 0.2f), MaskPoint(0.8f, 0.8f))
        assertEquals(two, PathCurve.smooth(two))
        assertEquals(emptyList<MaskPoint>(), PathCurve.smooth(emptyList()))
    }

    @Test
    fun `a traced circle comes out rounder than the polygon through the same points`() {
        // Twelve points off a circle. The straight-line version misses by the sagitta of each
        // chord; the curve should be an order of magnitude closer.
        val count = 12
        val radius = 0.3f
        val points = (0 until count).map { step ->
            val angle = step * 2.0 * PI / count
            MaskPoint(0.5f + radius * cos(angle).toFloat(), 0.5f + radius * sin(angle).toFloat())
        }

        fun worstError(samples: List<MaskPoint>) =
            samples.maxOf { abs(hypot(it.x - 0.5f, it.y - 0.5f) - radius) }

        val chords = (0 until count).flatMap { index ->
            val from = points[index]
            val to = points[(index + 1) % count]
            (0 until 8).map { step ->
                val amount = step / 8f
                MaskPoint(
                    from.x + (to.x - from.x) * amount,
                    from.y + (to.y - from.y) * amount,
                )
            }
        }
        val polygon = worstError(chords)
        val curved = worstError(PathCurve.smooth(points, segments = 8))
        assertTrue(
            "curve should be much closer to the circle: $curved vs $polygon",
            curved < polygon / 5f,
        )
    }

    @Test
    fun `a straight run between points in line stays straight`() {
        // The middle span has collinear neighbours on both sides, so nothing should bend it — a
        // curve that bows here would round off edges the user drew deliberately straight.
        val points = listOf(
            MaskPoint(0.1f, 0.5f),
            MaskPoint(0.3f, 0.5f),
            MaskPoint(0.5f, 0.5f),
            MaskPoint(0.7f, 0.5f),
            MaskPoint(0.7f, 0.9f),
            MaskPoint(0.1f, 0.9f),
        )
        val curve = PathCurve.smooth(points, segments = 8)
        // The span from points[1] to points[2].
        curve.subList(8, 16).forEach { sample ->
            assertEquals("should not leave the line", 0.5f, sample.y, 1e-5f)
        }
    }

    @Test
    fun `points on top of each other produce numbers rather than NaN`() {
        // A finger held still emits repeated samples at the same place, which is a zero-length span
        // and a division waiting to happen.
        val repeated = listOf(
            MaskPoint(0.2f, 0.2f),
            MaskPoint(0.2f, 0.2f),
            MaskPoint(0.8f, 0.2f),
            MaskPoint(0.8f, 0.8f),
        )
        val curve = PathCurve.smooth(repeated)
        assertEquals(4 * PathCurve.SEGMENTS, curve.size)
        curve.forEach { point ->
            assertTrue("$point", !point.x.isNaN() && !point.y.isNaN())
            assertTrue("$point", point.x.isFinite() && point.y.isFinite())
        }
    }

    @Test
    fun `the curve stays near the shape rather than swinging wide`() {
        // Catmull-Rom overshoots at a corner by design; a square is the worst case there is, and
        // even that must stay a recognisable square rather than a flower.
        val curve = PathCurve.smooth(square(), segments = 8)
        curve.forEach { point ->
            assertTrue("$point strayed too far", point.x > 0.15f && point.x < 0.85f)
            assertTrue("$point strayed too far", point.y > 0.15f && point.y < 0.85f)
        }
    }
}
