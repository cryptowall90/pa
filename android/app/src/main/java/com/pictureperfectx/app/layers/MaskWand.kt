package com.pictureperfectx.app.layers

import kotlin.math.sqrt

/**
 * Selecting an area by its colour instead of tracing it.
 *
 * Lasso, brush and fade all ask the user to say *where*. This one asks them to say *what*: tap the
 * sky and the sky is chosen, edge and all. It is the difference between an editor you can use on a
 * bus and one you can only use with a stylus and ten minutes.
 *
 * Pure Kotlin over a grid of pixels, like [MaskLasso] and [MaskBrush], so CI can hold it to the
 * behaviour that matters — a wand that quietly swallows the whole photo is the classic failure and
 * it is invisible until it happens to you.
 */
object MaskWand {

    /** How alike is alike enough, as a share of the longest possible colour difference. */
    const val MIN_TOLERANCE = 0f
    const val MAX_TOLERANCE = 0.6f
    const val DEFAULT_TOLERANCE = 0.15f

    /**
     * The share of the tolerance that is fully selected; the rest is the soft edge.
     *
     * A wand that returns a hard 0/1 mask leaves a jagged rim that has to be feathered away
     * afterwards, and feathering rounds off the corners it got right along with the ones it didn't.
     * Fading over the last quarter of the tolerance gives an edge that is already usable.
     */
    private const val SOLID_SHARE = 0.75f

    /** The longest possible distance between two colours, so tolerance can be a 0..1 share. */
    private val MAX_DISTANCE = sqrt(3f * 255f * 255f)

    /**
     * Chooses the area around ([x], [y]) that matches the colour under it.
     *
     * [grid] is the photo sampled down to the mask's own resolution, row-major packed ARGB. Working
     * at mask resolution rather than the photo's is what keeps this instant on a 12MP file, and the
     * mask was never finer than this anyway.
     *
     * Every cell is compared to the **seed colour**, never to its neighbour. Comparing neighbours is
     * what makes a wand crawl down a gradient one indistinguishable step at a time and end up
     * selecting the entire photo; comparing to the seed means the tolerance means what it says.
     *
     * [contiguous] is the difference between "this shirt" and "every red thing in the frame": a
     * flood fill outward from the tap, or every matching cell wherever it sits.
     */
    fun select(
        grid: IntArray,
        columns: Int,
        rows: Int,
        mask: Mask,
        x: Float,
        y: Float,
        tolerance: Float = DEFAULT_TOLERANCE,
        contiguous: Boolean = true,
        mode: SelectionMode = SelectionMode.Replace,
    ): Mask {
        // The grid has to describe the same cells the mask does, or coverage would land offset from
        // the colours it was chosen by.
        if (columns != mask.columns || rows != mask.rows) return mask
        if (columns <= 0 || rows <= 0 || grid.size < columns * rows) return mask

        val seedColumn = (x * columns).toInt()
        val seedRow = (y * rows).toInt()
        if (seedColumn !in 0 until columns || seedRow !in 0 until rows) return mask

        val seed = grid[seedRow * columns + seedColumn]
        val limit = tolerance.coerceIn(MIN_TOLERANCE, MAX_TOLERANCE) * MAX_DISTANCE
        val solid = limit * SOLID_SHARE

        val drawn = FloatArray(columns * rows)
        if (contiguous) {
            floodFrom(grid, columns, rows, seedRow * columns + seedColumn, seed, limit, solid, drawn)
        } else {
            for (index in 0 until columns * rows) {
                drawn[index] = coverageFor(distance(seed, grid[index]), limit, solid)
            }
        }

        val existing = if (mask.isEmpty) FloatArray(drawn.size) else mask.coverage
        val combined = when (mode) {
            SelectionMode.Replace -> drawn
            SelectionMode.Add -> FloatArray(drawn.size) { maxOf(existing[it], drawn[it]) }
            SelectionMode.Subtract -> FloatArray(drawn.size) {
                (existing[it] - drawn[it]).coerceAtLeast(0f)
            }
        }
        // No polygon and no gradient describes what a wand chose, so neither is kept.
        return mask.copy(coverage = combined, path = null, gradient = null)
    }

    /**
     * Spreads out from [start] over cells within [limit] of the seed colour.
     *
     * An explicit stack rather than recursion: a matching region can run to tens of thousands of
     * cells, and that many stack frames is a crash rather than a slow selection.
     *
     * Cells in the soft edge are still crossed. They are within tolerance — the falloff decides how
     * strongly they are selected, not whether the area continues through them.
     */
    private fun floodFrom(
        grid: IntArray,
        columns: Int,
        rows: Int,
        start: Int,
        seed: Int,
        limit: Float,
        solid: Float,
        drawn: FloatArray,
    ) {
        val seen = BooleanArray(grid.size.coerceAtMost(columns * rows))
        val stack = ArrayDeque<Int>()
        stack.addLast(start)
        seen[start] = true

        while (stack.isNotEmpty()) {
            val index = stack.removeLast()
            val distance = distance(seed, grid[index])
            if (distance > limit) continue
            drawn[index] = coverageFor(distance, limit, solid)

            val column = index % columns
            val row = index / columns
            if (column > 0) push(stack, seen, index - 1)
            if (column < columns - 1) push(stack, seen, index + 1)
            if (row > 0) push(stack, seen, index - columns)
            if (row < rows - 1) push(stack, seen, index + columns)
        }
    }

    private fun push(stack: ArrayDeque<Int>, seen: BooleanArray, index: Int) {
        if (seen[index]) return
        seen[index] = true
        stack.addLast(index)
    }

    /** Straight distance in RGB. Alpha is ignored: the photo underneath is always opaque. */
    private fun distance(a: Int, b: Int): Float {
        val red = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val green = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val blue = (a and 0xFF) - (b and 0xFF)
        return sqrt((red * red + green * green + blue * blue).toFloat())
    }

    /** Full strength out to [solid], fading to nothing at [limit]. */
    private fun coverageFor(distance: Float, limit: Float, solid: Float): Float {
        if (distance > limit) return 0f
        val span = limit - solid
        // A tolerance of zero leaves no room to fade across: it is an exact-colour match.
        if (span <= 0f) return if (distance <= limit) 1f else 0f
        val t = ((distance - solid) / span).coerceIn(0f, 1f)
        // Smoothstep, so the edge eases off rather than ramping linearly into a visible line.
        return 1f - t * t * (3f - 2f * t)
    }
}
