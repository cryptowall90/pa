package com.pictureperfectx.app.layers

import kotlin.math.abs
import kotlin.math.sqrt

/** A control point on a tone curve: input level on [x], the level it maps to on [y]. Both 0..1. */
data class CurvePoint(val x: Float, val y: Float)

/**
 * Which channel a curve is being drawn on.
 *
 * The three colour channels are what makes this colour grading and not just contrast: lifting red
 * in the shadows warms them, pulling blue down in the highlights cools them. That's the same tool
 * professional work uses, rather than a separate pair of tint sliders that would fight with it.
 */
enum class CurveChannel(val label: String) {
    Rgb("RGB"),
    Red("Red"),
    Green("Green"),
    Blue("Blue"),
}

/** The four curves of one layer. */
data class CurveSpec(
    val rgb: List<CurvePoint> = Curves.IDENTITY,
    val red: List<CurvePoint> = Curves.IDENTITY,
    val green: List<CurvePoint> = Curves.IDENTITY,
    val blue: List<CurvePoint> = Curves.IDENTITY,
) {
    fun channel(channel: CurveChannel): List<CurvePoint> = when (channel) {
        CurveChannel.Rgb -> rgb
        CurveChannel.Red -> red
        CurveChannel.Green -> green
        CurveChannel.Blue -> blue
    }

    fun with(channel: CurveChannel, points: List<CurvePoint>): CurveSpec = when (channel) {
        CurveChannel.Rgb -> copy(rgb = points)
        CurveChannel.Red -> copy(red = points)
        CurveChannel.Green -> copy(green = points)
        CurveChannel.Blue -> copy(blue = points)
    }

    /** True when every channel is still a straight line, so the layer would change nothing. */
    val isIdentity: Boolean
        get() = CurveChannel.entries.all { which ->
            channel(which).all { abs(it.x - it.y) < Curves.EPSILON }
        }
}

/**
 * Editing operations on a curve's control points.
 *
 * Pure Kotlin, like the rest of `layers`, because the rules here are exactly the sort that look
 * right until a point is dragged past its neighbour and the spline folds back on itself — visible
 * as a curve that suddenly inverts, and hard to reason about after the fact.
 */
object Curves {

    /** A straight line. Three points, since a spline through two is what GPUImage starts from. */
    val IDENTITY = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 1f))

    /** Closest two points may sit on the input axis, so a drag can't stack them. */
    const val MIN_GAP = 0.02f

    const val EPSILON = 0.001f

    /**
     * Moves the point at [index] to [to].
     *
     * The two ends keep their input level and only move vertically — that's what makes a curve a
     * mapping of every level rather than one with gaps at the ends. Everything between is held
     * inside its neighbours, so the points stay in order and the spline can't fold back on itself.
     */
    fun move(points: List<CurvePoint>, index: Int, to: CurvePoint): List<CurvePoint> {
        if (index !in points.indices) return points
        val y = to.y.coerceIn(0f, 1f)
        val x = when (index) {
            0 -> points.first().x
            points.lastIndex -> points.last().x
            else -> to.x.coerceIn(
                points[index - 1].x + MIN_GAP,
                points[index + 1].x - MIN_GAP,
            )
        }
        return points.toMutableList().also { it[index] = CurvePoint(x, y) }
    }

    /** Adds a point, in its place along the input axis. Too close to an existing one is a no-op. */
    fun add(points: List<CurvePoint>, at: CurvePoint): List<CurvePoint> {
        val x = at.x.coerceIn(0f, 1f)
        val y = at.y.coerceIn(0f, 1f)
        if (points.any { abs(it.x - x) < MIN_GAP }) return points
        val index = points.indexOfFirst { it.x > x }.let { if (it < 0) points.size else it }
        return points.toMutableList().also { it.add(index, CurvePoint(x, y)) }
    }

    /**
     * Removes the point at [index].
     *
     * The ends stay, and so does a third point: a curve of two is a straight line that can only be
     * tilted, which would leave no way back to a shape without starting over.
     */
    fun remove(points: List<CurvePoint>, index: Int): List<CurvePoint> {
        if (index !in points.indices) return points
        if (index == 0 || index == points.lastIndex) return points
        if (points.size <= 3) return points
        return points.toMutableList().also { it.removeAt(index) }
    }

    /** The index of the point within [radius] of [of], or -1. Ties go to the closest. */
    fun nearest(points: List<CurvePoint>, of: CurvePoint, radius: Float): Int {
        var best = -1
        var bestDistance = radius
        points.forEachIndexed { index, point ->
            val dx = point.x - of.x
            val dy = point.y - of.y
            val distance = sqrt(dx * dx + dy * dy)
            if (distance <= bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }
}
