package com.pictureperfectx.app.layers

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Reduces a traced path to the points that actually describe its shape.
 *
 * Two jobs at once. A finger dragged across the screen produces a couple of hundred samples, which
 * is far too many to offer as draggable handles — and most of them describe tremor rather than
 * intent, so dropping them makes the line *more* faithful to what was meant, not less.
 *
 * Ramer–Douglas–Peucker: keep the endpoints, find the point furthest from the line between them,
 * and recurse on both halves if it's further off than the tolerance. Pure Kotlin, so CI can hold it
 * to the guarantee that matters — nothing kept or dropped moves the line by more than the tolerance.
 */
object PathSimplify {

    /** Tolerance as a fraction of the image, roughly a finger's worth of tremor. */
    const val DEFAULT_TOLERANCE = 0.004f

    /** More handles than this is a thicket rather than a set of controls. */
    const val MAX_POINTS = 48

    /**
     * Simplifies [path], then thins it further if it still exceeds [maxPoints].
     *
     * The cap is a second pass with a growing tolerance rather than a truncation: dropping the tail
     * of the list would cut a corner off the shape, while a coarser tolerance loses the least
     * important points wherever they are.
     */
    fun simplify(
        path: List<MaskPoint>,
        tolerance: Float = DEFAULT_TOLERANCE,
        maxPoints: Int = MAX_POINTS,
    ): List<MaskPoint> {
        if (path.size <= 2) return path

        var current = reduce(path, tolerance.coerceAtLeast(0f))
        var growing = tolerance.coerceAtLeast(MIN_TOLERANCE)
        while (current.size > maxPoints && growing < MAX_TOLERANCE) {
            growing *= 1.6f
            current = reduce(path, growing)
        }
        return current
    }

    private fun reduce(path: List<MaskPoint>, tolerance: Float): List<MaskPoint> {
        if (path.size <= 2 || tolerance <= 0f) return path
        val keep = BooleanArray(path.size)
        keep[0] = true
        keep[path.lastIndex] = true
        divide(path, 0, path.lastIndex, tolerance, keep)
        return path.filterIndexed { index, _ -> keep[index] }
    }

    /** Marks the furthest point between [first] and [last] when it strays past [tolerance]. */
    private fun divide(
        path: List<MaskPoint>,
        first: Int,
        last: Int,
        tolerance: Float,
        keep: BooleanArray,
    ) {
        if (last <= first + 1) return
        var furthest = -1
        var furthestDistance = 0f
        for (index in first + 1 until last) {
            val distance = distanceToSegment(path[index], path[first], path[last])
            if (distance > furthestDistance) {
                furthestDistance = distance
                furthest = index
            }
        }
        if (furthest < 0 || furthestDistance <= tolerance) return
        keep[furthest] = true
        divide(path, first, furthest, tolerance, keep)
        divide(path, furthest, last, tolerance, keep)
    }

    /** Perpendicular distance from [point] to the segment [start]..[end]. */
    private fun distanceToSegment(point: MaskPoint, start: MaskPoint, end: MaskPoint): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        // A closed loop can hand us a segment whose ends coincide; fall back to point distance.
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0f) return distanceBetween(point, start)

        // Project onto the segment, clamped to its ends so a point beyond either one measures to
        // that end rather than to the infinite line.
        val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared)
            .coerceIn(0f, 1f)
        return distanceBetween(point, MaskPoint(start.x + t * dx, start.y + t * dy))
    }

    private fun distanceBetween(a: MaskPoint, b: MaskPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy).let { if (it.isNaN()) abs(dx) + abs(dy) else it }
    }

    private const val MIN_TOLERANCE = 0.0005f
    private const val MAX_TOLERANCE = 0.2f
}
