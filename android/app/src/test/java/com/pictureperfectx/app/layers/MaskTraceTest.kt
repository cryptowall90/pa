package com.pictureperfectx.app.layers

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deriving draggable points from an area that never had any.
 *
 * The order is the whole point and the only thing that can silently go wrong: a set of boundary
 * points in the wrong sequence still *looks* like a plausible outline in a list, and turns into a
 * star-shaped tangle the moment anything draws a closed curve through it.
 */
class MaskTraceTest {

    private val size = 96

    private fun mask(covered: (Float, Float) -> Boolean): Mask {
        val coverage = FloatArray(size * size) { index ->
            val x = (index % size + 0.5f) / size
            val y = (index / size + 0.5f) / size
            if (covered(x, y)) 1f else 0f
        }
        return Mask(size, size, coverage)
    }

    private fun disc(cx: Float, cy: Float, radius: Float) =
        mask { x, y -> hypot(x - cx, y - cy) < radius }

    @Test
    fun `a brushed blob comes back as a handful of points on its edge`() {
        val traced = MaskTrace.outline(disc(0.5f, 0.5f, 0.25f))

        assertTrue("there should be a shape", traced.isUsable)
        assertEquals("one blob is one ring", 1, traced.loops)
        assertTrue("few enough to drag", traced.points.size <= MaskTrace.HANDLE_COUNT)
        traced.points.forEach { point ->
            val radius = hypot(point.x - 0.5f, point.y - 0.5f)
            assertTrue("every point should sit on the edge, not inside it", radius in 0.22f..0.28f)
        }
    }

    @Test
    fun `the points come back in the order they run round the shape`() {
        // The failure this guards against: boundary points in list order rather than ring order.
        // Every step round a circle should turn the same way; a scrambled list doubles back.
        val traced = MaskTrace.outline(disc(0.5f, 0.5f, 0.25f))
        val angles = traced.points.map { atan2Degrees(it.y - 0.5f, it.x - 0.5f) }

        var turned = 0f
        for (index in angles.indices) {
            val step = angles[(index + 1) % angles.size] - angles[index]
            // Round the wrap at 180 degrees the short way, which is the step actually taken.
            turned += ((step + 540f) % 360f) - 180f
        }
        assertEquals("one clean turn round the shape", 360f, kotlin.math.abs(turned), 1f)
    }

    @Test
    fun `a shape traced and refilled is the same shape`() {
        // The point of the feature: the handles must describe the area they came from, or using
        // them would move the effect somewhere it was never painted.
        val original = disc(0.5f, 0.5f, 0.25f)
        val traced = MaskTrace.outline(original)
        val refilled = MaskLasso.shape(Mask.blank(size, size), traced.points)

        val agree = (0 until size * size).count { index ->
            val column = index % size
            val row = index / size
            (original.coverageAt(column, row) > 0.5f) == (refilled.coverageAt(column, row) > 0.5f)
        }
        assertTrue(
            "the refilled area should match to within its own edge softness",
            // A ring in the wrong order scores far worse than this: a curve through scrambled
            // points is a star, not a disc.
            agree.toFloat() / (size * size) > 0.95f,
        )
        assertEquals("and it keeps the points, so they can be dragged", traced.points, refilled.path)
    }

    @Test
    fun `two separate blobs are counted, and the larger one is the one described`() {
        // Handles that quietly described one blob while the other stayed put would be worse than
        // none, so the count is reported and the caller says so.
        val two = mask { x, y ->
            hypot(x - 0.25f, y - 0.5f) < 0.1f || hypot(x - 0.75f, y - 0.5f) < 0.18f
        }
        val traced = MaskTrace.outline(two)

        assertEquals(2, traced.loops)
        val centreX = traced.points.map { it.x }.average().toFloat()
        assertEquals("the bigger blob is the one traced", 0.75f, centreX, 0.05f)
    }

    @Test
    fun `an area with a hole in it finds both rings`() {
        val ring = mask { x, y -> hypot(x - 0.5f, y - 0.5f) in 0.12f..0.3f }
        assertEquals(2, MaskTrace.outline(ring).loops)
    }

    @Test
    fun `an area running off the side of the photo still closes`() {
        // MaskOutline pads with a ring of empty samples for exactly this, and the chaining has to
        // survive a boundary that runs along the padding rather than curving away from it.
        val half = mask { x, _ -> x < 0.5f }
        val traced = MaskTrace.outline(half)
        assertTrue(traced.isUsable)
        assertEquals(1, traced.loops)
    }

    @Test
    fun `an empty area has nothing to trace rather than crashing`() {
        assertFalse(MaskTrace.outline(Mask()).isUsable)
        assertFalse(MaskTrace.outline(Mask.blank(size, size)).isUsable)
        assertEquals(0, MaskTrace.outline(Mask.blank(size, size)).loops)
    }

    @Test
    fun `a soft edge traces where the effect actually takes hold`() {
        // A feathered area has no crisp boundary at all; the contour is the half-coverage line,
        // which is the same line the outline on screen is drawn along.
        val soft = Mask(
            size,
            size,
            FloatArray(size * size) { index ->
                val x = (index % size + 0.5f) / size
                val y = (index / size + 0.5f) / size
                ((0.25f - hypot(x - 0.5f, y - 0.5f)) / 0.06f + 0.5f).coerceIn(0f, 1f)
            },
        )
        val traced = MaskTrace.outline(soft)
        assertTrue(traced.isUsable)
        traced.points.forEach {
            assertEquals(0.25f, hypot(it.x - 0.5f, it.y - 0.5f), 0.02f)
        }
    }

    private fun atan2Degrees(y: Float, x: Float): Float =
        Math.toDegrees(kotlin.math.atan2(y, x).toDouble()).toFloat()
}
