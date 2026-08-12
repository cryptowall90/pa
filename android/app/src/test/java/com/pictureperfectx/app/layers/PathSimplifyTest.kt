package com.pictureperfectx.app.layers

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simplification decides both how many handles a lasso offers and how faithful the traced line
 * stays. Dropping a corner would visibly change the selection, so the guarantee — no point of the
 * original strays further than the tolerance from what's kept — is pinned here rather than judged
 * by eye on a phone.
 */
class PathSimplifyTest {

    private fun line(count: Int, from: MaskPoint, to: MaskPoint): List<MaskPoint> =
        (0 until count).map { step ->
            val t = step.toFloat() / (count - 1)
            MaskPoint(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
        }

    /** How far [original] strays from the polyline [kept]. */
    private fun maxDeviation(original: List<MaskPoint>, kept: List<MaskPoint>): Float {
        fun distanceToSegment(p: MaskPoint, a: MaskPoint, b: MaskPoint): Float {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared <= 0f) {
                return sqrt((p.x - a.x) * (p.x - a.x) + (p.y - a.y) * (p.y - a.y))
            }
            val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
            val cx = a.x + t * dx
            val cy = a.y + t * dy
            return sqrt((p.x - cx) * (p.x - cx) + (p.y - cy) * (p.y - cy))
        }
        return original.maxOf { point ->
            (0 until kept.lastIndex).minOf { i -> distanceToSegment(point, kept[i], kept[i + 1]) }
        }
    }

    @Test
    fun `a straight run collapses to its two ends`() {
        val straight = line(50, MaskPoint(0.1f, 0.1f), MaskPoint(0.9f, 0.9f))
        val simplified = PathSimplify.simplify(straight)
        assertEquals(2, simplified.size)
        assertEquals(straight.first(), simplified.first())
        assertEquals(straight.last(), simplified.last())
    }

    @Test
    fun `a corner survives`() {
        // Down one side and along the bottom: the turn is the whole shape, so it must be kept.
        val corner = line(30, MaskPoint(0.2f, 0.2f), MaskPoint(0.2f, 0.8f)) +
            line(30, MaskPoint(0.2f, 0.8f), MaskPoint(0.8f, 0.8f))
        val simplified = PathSimplify.simplify(corner)
        assertEquals("both ends and the turn", 3, simplified.size)
        assertTrue(
            "the corner point itself should be kept",
            simplified.any { it.x == 0.2f && it.y == 0.8f },
        )
    }

    @Test
    fun `nothing strays further than the tolerance`() {
        // A traced arc with tremor on top, the shape a fingertip actually produces.
        val traced = (0..200).map { step ->
            val t = step / 200f
            val wobble = if (step % 3 == 0) 0.001f else -0.0012f
            MaskPoint(0.2f + 0.6f * t + wobble, 0.5f + 0.25f * t * (1f - t) * 4f)
        }
        val simplified = PathSimplify.simplify(traced)
        assertTrue("it should actually simplify", simplified.size < traced.size / 4)
        assertTrue(
            "the kept line must stay within tolerance of every original point",
            maxDeviation(traced, simplified) <= PathSimplify.DEFAULT_TOLERANCE + 0.0001f,
        )
    }

    @Test
    fun `the handle cap holds even for a busy path`() {
        // A tight zigzag: every point is a corner, so only a coarser tolerance can thin it.
        val zigzag = (0..400).map { step ->
            MaskPoint(step / 400f, if (step % 2 == 0) 0.4f else 0.6f)
        }
        val simplified = PathSimplify.simplify(zigzag)
        assertTrue(
            "should be capped, was ${simplified.size}",
            simplified.size <= PathSimplify.MAX_POINTS,
        )
        assertTrue("but not emptied", simplified.size >= 2)
    }

    @Test
    fun `the ends are never dropped`() {
        val zigzag = (0..400).map { step ->
            MaskPoint(step / 400f, if (step % 2 == 0) 0.4f else 0.6f)
        }
        val simplified = PathSimplify.simplify(zigzag)
        assertEquals(zigzag.first(), simplified.first())
        assertEquals(zigzag.last(), simplified.last())
    }

    @Test
    fun `paths too short to simplify come back untouched`() {
        val two = listOf(MaskPoint(0.1f, 0.1f), MaskPoint(0.9f, 0.9f))
        assertSame(two, PathSimplify.simplify(two))
        assertTrue(PathSimplify.simplify(emptyList()).isEmpty())
    }

    @Test
    fun `a zero tolerance keeps everything`() {
        val traced = line(20, MaskPoint(0f, 0f), MaskPoint(1f, 1f))
        assertEquals(traced.size, PathSimplify.simplify(traced, tolerance = 0f).size)
    }

    @Test
    fun `a lasso keeps its points but a brushed area does not`() {
        val square = listOf(
            MaskPoint(0.25f, 0.25f),
            MaskPoint(0.75f, 0.25f),
            MaskPoint(0.75f, 0.75f),
            MaskPoint(0.25f, 0.75f),
        )
        val lassoed = MaskLasso.fill(Mask.blank(32, 32), square)
        assertTrue("a fresh lasso keeps its points", lassoed.path?.isNotEmpty() == true)

        val added = MaskLasso.fill(lassoed, square, SelectionMode.Add)
        assertEquals("adding leaves a shape the polygon no longer describes", null, added.path)

        val brushed = MaskBrush.paint(lassoed, 0.5f, 0.5f, 0.1f)
        assertEquals("brushing leaves no points to drag", null, brushed.path)
    }
}
