package com.pictureperfectx.app.layers

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/** The five gradient shapes, each a different way of measuring distance from the start. */
@Serializable
enum class GradientStyle(val label: String) {
    Linear("Linear"),
    Radial("Radial"),
    Angular("Angular"),
    Reflected("Reflected"),
    Diamond("Diamond"),
}

/**
 * A gradient's placement: full strength at [start], falling to nothing at [end].
 *
 * The two points are normalized like everything else a mask stores, so a gradient means the same
 * thing on the preview and on the full-resolution export.
 */
@Serializable
data class GradientSpec(
    val style: GradientStyle = GradientStyle.Linear,
    val start: MaskPoint = MaskPoint(0.5f, 0f),
    val end: MaskPoint = MaskPoint(0.5f, 0.6f),
    /** Where the halfway point falls along the run, 0.05..0.95. 0.5 is a straight ramp. */
    val midpoint: Float = 0.5f,
)

/**
 * A gradient, as a number between 0 and 1 for every point.
 *
 * One function serves both jobs the feature has: as an *area* that number is coverage, and as
 * *colour* it is how far between two colours a pixel sits. Sharing it isn't only less code — it is
 * what guarantees a gradient fill and a gradient mask of the same placement line up exactly.
 *
 * Pure Kotlin with no Android types, like [MaskLasso] and `CropMath`, so CI can check the parts
 * that are invisible until they're wrong.
 */
object MaskGradient {

    /** Fills [mask] from [spec], combining with what's there the same way a lasso does. */
    fun fill(mask: Mask, spec: GradientSpec, mode: SelectionMode = SelectionMode.Replace): Mask {
        if (mask.columns <= 0 || mask.rows <= 0) return mask
        val drawn = FloatArray(mask.columns * mask.rows) { index ->
            val column = index % mask.columns
            val row = index / mask.columns
            // Sample cell centres, matching how the coverage grid is read back.
            coverage(
                spec = spec,
                x = (column + 0.5f) / mask.columns,
                y = (row + 0.5f) / mask.rows,
                columns = mask.columns,
                rows = mask.rows,
            )
        }

        val existing = if (mask.isEmpty) FloatArray(drawn.size) else mask.coverage
        val combined = when (mode) {
            SelectionMode.Replace -> drawn
            SelectionMode.Add -> FloatArray(drawn.size) { max(existing[it], drawn[it]) }
            SelectionMode.Subtract -> FloatArray(drawn.size) {
                (existing[it] - drawn[it]).coerceAtLeast(0f)
            }
        }
        // A placed gradient stays adjustable; combining it with something else leaves a shape it no
        // longer describes, exactly as a lasso's points are dropped once they stop being the area.
        val kept = if (mode == SelectionMode.Replace) spec else null
        return mask.copy(coverage = combined, gradient = kept, path = null)
    }

    /**
     * Coverage at normalized ([x], [y]) — 1 at the gradient's start, 0 at its end.
     *
     * The start being the strong end is what makes dragging from the top of a sky downwards darken
     * the top, which is the way round a graduated filter is used.
     *
     * **Measured in cell units, not normalized ones.** Normalized coordinates squash a circle into
     * an ellipse on any photo that isn't square, so a radial gradient drawn around a face would
     * come out an oval. The selection grid's cells are square, so cell space is the honest metric.
     */
    fun coverage(spec: GradientSpec, x: Float, y: Float, columns: Int, rows: Int): Float {
        if (columns <= 0 || rows <= 0) return 0f
        val startX = spec.start.x * columns
        val startY = spec.start.y * rows
        val axisX = (spec.end.x - spec.start.x) * columns
        val axisY = (spec.end.y - spec.start.y) * rows
        val lengthSquared = axisX * axisX + axisY * axisY
        // A tap rather than a drag has no direction and no length; nothing sensible to draw.
        if (lengthSquared <= 0f) return 0f

        val pointX = x * columns - startX
        val pointY = y * rows - startY
        val length = sqrt(lengthSquared)

        val distance = when (spec.style) {
            GradientStyle.Linear -> (pointX * axisX + pointY * axisY) / lengthSquared

            GradientStyle.Reflected ->
                abs(pointX * axisX + pointY * axisY) / lengthSquared

            GradientStyle.Radial -> sqrt(pointX * pointX + pointY * pointY) / length

            GradientStyle.Diamond -> {
                // In the frame rotated onto the axis, a diamond is where |u| + |v| is constant.
                val along = (pointX * axisX + pointY * axisY) / lengthSquared
                val across = (pointX * -axisY + pointY * axisX) / lengthSquared
                abs(along) + abs(across)
            }

            GradientStyle.Angular -> {
                // Angle of the point relative to the axis, over a full turn.
                val angle = atan2(pointY, pointX) - atan2(axisY, axisX)
                val turns = angle / (2f * PI.toFloat())
                turns - floor(turns)
            }
        }

        return (1f - shaped(distance.coerceIn(0f, 1f), spec.midpoint)).coerceIn(0f, 1f)
    }

    /**
     * Bends the ramp so the halfway point lands at [midpoint] instead of the middle.
     *
     * The same curve Photoshop's gradient midpoint applies, and the identity at 0.5 — so leaving the
     * control alone changes nothing.
     */
    private fun shaped(t: Float, midpoint: Float): Float {
        val m = midpoint.coerceIn(MIN_MIDPOINT, MAX_MIDPOINT)
        if (m == 0.5f) return t
        return t.pow(ln(0.5f) / ln(m))
    }

    const val MIN_MIDPOINT = 0.05f
    const val MAX_MIDPOINT = 0.95f
}
