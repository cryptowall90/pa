package com.pictureperfectx.app.layers

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A closed smooth curve through a lasso's points.
 *
 * This is the shape a lasso *is*. The mask is rasterised from it, the outline over the photo is
 * drawn from it, and the draggable handles sit on the points it passes through — one curve, so the
 * three cannot disagree. They used to: the mask came from every sample a finger produced while the
 * outline came from the thinned list, which meant the two were different shapes and grabbing a
 * handle made the mask jump to the coarser one.
 *
 * Centripetal Catmull–Rom rather than uniform. Uniform loops back on itself wherever two points sit
 * close together, which is exactly what a finger slowing down at a corner produces, so the curve
 * would tie a knot at the very places drawn most carefully.
 */
object PathCurve {

    /** Samples per span. Eight is smooth at any zoom this editor allows and cheap to rasterise. */
    const val SEGMENTS = 8

    /** The exponent that makes this centripetal; 0.5 is the value that rules the loops out. */
    private const val ALPHA = 0.5f

    /** Coincident points would divide by a zero span, so knots are never allowed to touch. */
    private const val MIN_KNOT = 1e-6f

    /**
     * Samples the closed curve running through every one of [points].
     *
     * The result starts at `points[0]` and hits each point exactly, at index `i * segments` — which
     * is what lets a handle be dragged and the curve stay tied to it.
     */
    fun smooth(points: List<MaskPoint>, segments: Int = SEGMENTS): List<MaskPoint> {
        // Two points enclose no area, so there is no loop to round off.
        if (points.size < 3 || segments < 1) return points

        val count = points.size
        val curve = ArrayList<MaskPoint>(count * segments)
        for (index in 0 until count) {
            val p0 = points[(index - 1 + count) % count]
            val p1 = points[index]
            val p2 = points[(index + 1) % count]
            val p3 = points[(index + 2) % count]

            val t0 = 0f
            val t1 = t0 + knot(p0, p1)
            val t2 = t1 + knot(p1, p2)
            val t3 = t2 + knot(p2, p3)

            for (step in 0 until segments) {
                val t = t1 + (t2 - t1) * step / segments
                // Barry–Goldman: three nested interpolations, which is the form that takes the
                // uneven knot spacing centripetal parameterisation produces.
                val a1 = between(p0, p1, t0, t1, t)
                val a2 = between(p1, p2, t1, t2, t)
                val a3 = between(p2, p3, t2, t3, t)
                val b1 = between(a1, a2, t0, t2, t)
                val b2 = between(a2, a3, t1, t3, t)
                curve.add(between(b1, b2, t1, t2, t))
            }
        }
        return curve
    }

    /** Distance between two knots, raised to [ALPHA] and never zero. */
    private fun knot(from: MaskPoint, to: MaskPoint): Float {
        val distance = hypot(to.x - from.x, to.y - from.y)
        // ALPHA is 0.5, so this is the square root — spelled out rather than via pow, which would
        // return NaN for the negative-zero case a subtraction can produce.
        return max(sqrt(distance), MIN_KNOT)
    }

    /** [from] to [to] as [t] runs from [start] to [end]. */
    private fun between(
        from: MaskPoint,
        to: MaskPoint,
        start: Float,
        end: Float,
        t: Float,
    ): MaskPoint {
        val span = end - start
        if (span <= 0f) return from
        val amount = (t - start) / span
        return MaskPoint(
            x = from.x + (to.x - from.x) * amount,
            y = from.y + (to.y - from.y) * amount,
        )
    }
}
