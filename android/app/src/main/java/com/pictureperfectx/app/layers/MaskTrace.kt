package com.pictureperfectx.app.layers

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Turns a painted area back into a handful of points that can be dragged.
 *
 * A lasso keeps the points it was drawn with, so it stays adjustable forever. Everything else — a
 * brushed area, a wand selection, a fade — has no shape describing it, only coverage, so once it was
 * made the only way to change it was to paint over it again. That is the gap: an area is easy to get
 * roughly right and tedious to get exactly right, and *exactly* right is what handles are for.
 *
 * [MaskOutline] already finds where the boundary runs, but as a heap of unordered segments — enough
 * to stroke, useless to drag, because a handle needs to know which point comes next. This chains
 * them into closed rings and thins the longest one down to something a fingertip can work with.
 *
 * The chaining is deliberately **undirected**. Marching squares emits the same segment for a corner
 * that is inside and for a corner that is outside — codes 1 and 14 both draw left-to-bottom — so the
 * winding of any given edge says nothing, and matching ends to starts would break the ring at every
 * second cell.
 *
 * Pure Kotlin with no Android types, like the rest of this package, so CI can check the shapes.
 */
object MaskTrace {

    /** A traced boundary, and how many separate rings the area turned out to have. */
    data class Traced(val points: List<MaskPoint>, val loops: Int) {
        /** Fewer than three points enclose nothing, so there is no shape to hand back. */
        val isUsable: Boolean get() = points.size >= 3
    }

    /** Enough to follow a hand-painted edge, few enough that they don't overlap on screen. */
    const val HANDLE_COUNT = 24

    /** Coarser than a freehand trace: these are being *derived*, not recorded. */
    const val TOLERANCE = 0.01f

    /**
     * The boundary of [mask]'s covered area, as points in the order they run round it.
     *
     * Only the longest ring is returned. A mask with two separate blobs has no single shape, and a
     * set of handles that quietly described one of them while the other stayed put would be worse
     * than none — so [Traced.loops] reports how many there were, and the caller says so.
     */
    fun outline(
        mask: Mask,
        threshold: Float = MaskOutline.DEFAULT_THRESHOLD,
        handles: Int = HANDLE_COUNT,
    ): Traced {
        val rings = rings(MaskOutline.segments(mask, threshold))
        val longest = rings.maxByOrNull { perimeter(it) } ?: return Traced(emptyList(), 0)
        val thinned = PathSimplify.simplify(longest, tolerance = TOLERANCE, maxPoints = handles)
        return Traced(thinned, rings.size)
    }

    /** Chains loose segments into closed rings by walking from one shared endpoint to the next. */
    private fun rings(edges: List<MaskEdge>): List<List<MaskPoint>> {
        if (edges.isEmpty()) return emptyList()

        // Which segments meet at each point. Quantized, because two cells computing the same
        // crossing from the same two samples should land on the same key even if the last bit of
        // the float disagrees.
        val meeting = HashMap<Long, MutableList<Int>>()
        edges.forEachIndexed { index, edge ->
            meeting.getOrPut(key(edge.x0, edge.y0)) { mutableListOf() }.add(index)
            meeting.getOrPut(key(edge.x1, edge.y1)) { mutableListOf() }.add(index)
        }

        val used = BooleanArray(edges.size)
        val rings = mutableListOf<List<MaskPoint>>()
        for (seed in edges.indices) {
            if (used[seed]) continue
            used[seed] = true
            val start = MaskPoint(edges[seed].x0, edges[seed].y0)
            var here = MaskPoint(edges[seed].x1, edges[seed].y1)
            val ring = mutableListOf(start, here)

            while (true) {
                val next = meeting[key(here.x, here.y)]?.firstOrNull { !used[it] } ?: break
                used[next] = true
                val edge = edges[next]
                // Undirected: continue from whichever end of this segment isn't the one we arrived
                // at. Following the stored direction would stall on every other cell.
                val far = if (key(edge.x0, edge.y0) == key(here.x, here.y)) {
                    MaskPoint(edge.x1, edge.y1)
                } else {
                    MaskPoint(edge.x0, edge.y0)
                }
                if (key(far.x, far.y) == key(start.x, start.y)) break // closed
                ring.add(far)
                here = far
            }
            if (ring.size >= 3) rings.add(ring)
        }
        return rings
    }

    private fun perimeter(ring: List<MaskPoint>): Float {
        var total = 0f
        for (index in 1 until ring.size) {
            total += hypot(ring[index].x - ring[index - 1].x, ring[index].y - ring[index - 1].y)
        }
        return total
    }

    /** Both coordinates rounded onto a grid far finer than a cell, packed into one key. */
    private fun key(x: Float, y: Float): Long =
        ((x / QUANTUM).roundToInt().toLong() shl 32) or ((y / QUANTUM).roundToInt().toLong() and 0xFFFFFFFFL)

    private const val QUANTUM = 1e-5f
}
