package com.pictureperfectx.app.layers

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A point on a lasso path, in the same normalized 0..1 space masks are stored in. */
data class MaskPoint(val x: Float, val y: Float)

/** How a new selection combines with whatever the layer's mask already covers. */
enum class SelectionMode(val label: String) {
    Replace("New"),
    Add("Add"),
    Subtract("Subtract"),
}

/**
 * Fills a closed lasso path into a [Mask].
 *
 * Pure Kotlin with no Android types, like [MaskBrush] and `CropMath`, so CI can actually run it —
 * which matters more than usual here, since a fill that leaks outside its outline or drops the
 * interior of a concave shape is hard to judge by eye on a phone.
 */
object MaskLasso {

    /**
     * Sub-scanlines per mask row. The horizontal edges of a span are already exact, so this only
     * has to soften the top and bottom, and four is enough to stop a near-horizontal edge reading
     * as a staircase.
     */
    private const val SUB_SCANLINES = 4

    /**
     * A freehand loop, as drawn: thinned to the points worth offering as handles, then filled from
     * the curve through them.
     *
     * Thinning **before** filling rather than after is the whole point. The area, the outline drawn
     * over the photo and the handles all come from one curve, so none of them can describe a shape
     * the others don't — which is what used to make the mask jump the moment a handle was grabbed.
     */
    fun trace(mask: Mask, drawn: List<MaskPoint>, mode: SelectionMode = SelectionMode.Replace): Mask =
        shape(mask, PathSimplify.simplify(drawn), mode)

    /**
     * Fills the closed curve through [handles], keeping them as the shape's control points.
     *
     * This is what a dragged handle re-runs, and it is deliberately the same call the freehand
     * trace ends in: dragging a point can only move the shape, never change what kind of shape it
     * is.
     */
    fun shape(mask: Mask, handles: List<MaskPoint>, mode: SelectionMode = SelectionMode.Replace): Mask {
        // Fewer than three encloses nothing, and keeping them would leave a mask carrying a shape
        // that describes no area at all.
        if (handles.size < 3) return mask
        val filled = fill(mask, PathCurve.smooth(handles), mode)
        // A fresh shape *is* the area, so its points are worth keeping to drag later. Adding or
        // subtracting leaves a shape this one no longer describes, so nothing is kept.
        return if (mode == SelectionMode.Replace) filled.copy(path = handles) else filled
    }

    /**
     * Rasterises [path] as a closed polygon and combines it with [mask] according to [mode].
     *
     * Purely a rasteriser: it draws exactly the polygon it is handed and keeps no control points.
     * [trace] and [shape] are the lasso's way in, and they decide what the shape is first.
     *
     * Uses the **even-odd** rule, which is what makes a shape drawn with a crossing-over stroke —
     * or one lassoed around an inner hole — behave the way it looks rather than filling solid.
     *
     * Combination matches [MaskBrush]'s semantics so the two tools agree about what coverage means:
     * adding takes the greater value, subtracting drives it back towards zero.
     */
    fun fill(mask: Mask, path: List<MaskPoint>, mode: SelectionMode = SelectionMode.Replace): Mask {
        // Two points enclose no area — usually a stray tap rather than an intended selection.
        if (path.size < 3 || mask.columns <= 0 || mask.rows <= 0) return mask

        val columns = mask.columns
        val rows = mask.rows
        val drawn = FloatArray(columns * rows)
        val rowCoverage = FloatArray(columns)
        val crossings = ArrayList<Float>(path.size)

        for (row in 0 until rows) {
            rowCoverage.fill(0f)
            for (sub in 0 until SUB_SCANLINES) {
                val y = (row + (sub + 0.5f) / SUB_SCANLINES) / rows
                crossings.clear()
                collectCrossings(path, y, crossings)
                if (crossings.size < 2) continue
                crossings.sort()
                // Even-odd: the interior is between the 1st and 2nd crossing, the 3rd and 4th, ...
                var index = 0
                while (index + 1 < crossings.size) {
                    addSpan(rowCoverage, crossings[index] * columns, crossings[index + 1] * columns)
                    index += 2
                }
            }
            val offset = row * columns
            for (column in 0 until columns) {
                drawn[offset + column] = (rowCoverage[column] / SUB_SCANLINES).coerceIn(0f, 1f)
            }
        }

        // An empty mask means "covers everything" when rendering, but "nothing painted yet" when
        // editing — the same reading MaskBrush takes.
        val existing = if (mask.isEmpty) FloatArray(drawn.size) else mask.coverage
        val combined = when (mode) {
            SelectionMode.Replace -> drawn
            SelectionMode.Add -> FloatArray(drawn.size) { max(existing[it], drawn[it]) }
            SelectionMode.Subtract -> FloatArray(drawn.size) {
                (existing[it] - drawn[it]).coerceAtLeast(0f)
            }
        }
        // Whatever described the old area no longer describes this one.
        return mask.copy(coverage = combined, path = null, gradient = null)
    }

    /** Where the closed polygon crosses the horizontal line at [y], in normalized x. */
    private fun collectCrossings(path: List<MaskPoint>, y: Float, into: MutableList<Float>) {
        var previous = path.last()
        path.forEach { current ->
            // Half-open comparison: a vertex exactly on the line counts once, not twice, and a
            // horizontal edge contributes nothing — both are what stop stray single crossings from
            // inverting the rest of the row.
            if ((previous.y <= y) != (current.y <= y)) {
                val span = current.y - previous.y
                val t = if (span != 0f) (y - previous.y) / span else 0f
                into.add(previous.x + t * (current.x - previous.x))
            }
            previous = current
        }
    }

    /** Adds the horizontal span [start]..[end], in cell units, with fractional ends. */
    private fun addSpan(coverage: FloatArray, start: Float, end: Float) {
        if (end <= start) return
        val first = max(0, floor(start).toInt())
        val last = min(coverage.size - 1, ceil(end).toInt() - 1)
        for (cell in first..last) {
            val left = max(start, cell.toFloat())
            val right = min(end, cell + 1f)
            if (right > left) coverage[cell] += right - left
        }
    }
}
