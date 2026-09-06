package com.pictureperfectx.app.layers

/** A straight run of a mask's boundary, in the normalized 0..1 space masks are stored in. */
data class MaskEdge(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/**
 * Traces the boundary of a mask's covered area.
 *
 * Drawing a selection as a translucent fill puts colour over exactly the pixels whose change the
 * user is trying to judge. An outline says the same thing — here is where this applies — while
 * leaving the picture alone.
 *
 * **Marching squares with linear interpolation.** Following cell edges would snap every crossing to
 * the nearest grid line, which turns a smoothly drawn diagonal into a staircase — and that
 * staircase, not the mask underneath, is most of what reads as an inaccurate lasso. Interpolating
 * between the samples either side of the threshold puts the crossing where the coverage actually
 * reaches it, well inside a cell.
 *
 * Pure Kotlin with no Android types, like [MaskLasso] and `CropMath`, so CI can check the shapes it
 * produces rather than leaving that to the eye.
 */
object MaskOutline {

    /** Coverage at or above this counts as inside, matching where an effect visibly takes hold. */
    const val DEFAULT_THRESHOLD = 0.5f

    /**
     * The contour where coverage crosses [threshold].
     *
     * Samples are taken at cell centres and the field is padded with a ring of empty samples, so a
     * selection running to the edge of the photo closes against the border instead of leaving the
     * outline hanging open.
     */
    fun segments(mask: Mask, threshold: Float = DEFAULT_THRESHOLD): List<MaskEdge> {
        // An empty mask renders as "applies everywhere", but there is no drawn area to outline.
        if (mask.isEmpty || mask.columns <= 0 || mask.rows <= 0) return emptyList()

        val columns = mask.columns
        val rows = mask.rows
        // The padded lattice: one ring of empty samples around the cell centres.
        val width = columns + 2
        val height = rows + 2
        val field = FloatArray(width * height)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                field[(row + 1) * width + column + 1] = mask.coverageAt(column, row)
            }
        }

        // Sample (i, j) sits at the centre of cell (i-1, j-1), so the padding ring lands half a
        // cell outside the image — which is where the contour should close.
        fun positionX(i: Int): Float = (i - 0.5f) / columns
        fun positionY(j: Int): Float = (j - 0.5f) / rows

        val edges = ArrayList<MaskEdge>()
        for (j in 0 until height - 1) {
            for (i in 0 until width - 1) {
                val topLeft = field[j * width + i]
                val topRight = field[j * width + i + 1]
                val bottomRight = field[(j + 1) * width + i + 1]
                val bottomLeft = field[(j + 1) * width + i]

                var code = 0
                if (topLeft >= threshold) code = code or 8
                if (topRight >= threshold) code = code or 4
                if (bottomRight >= threshold) code = code or 2
                if (bottomLeft >= threshold) code = code or 1
                if (code == 0 || code == 15) continue

                val left = positionX(i)
                val right = positionX(i + 1)
                val top = positionY(j)
                val bottom = positionY(j + 1)

                // Where the threshold falls between two samples, as a point on that cell side.
                val onTop = Pair(left + (right - left) * cross(topLeft, topRight, threshold), top)
                val onBottom =
                    Pair(left + (right - left) * cross(bottomLeft, bottomRight, threshold), bottom)
                val onLeft = Pair(left, top + (bottom - top) * cross(topLeft, bottomLeft, threshold))
                val onRight =
                    Pair(right, top + (bottom - top) * cross(topRight, bottomRight, threshold))

                fun emit(a: Pair<Float, Float>, b: Pair<Float, Float>) {
                    edges.add(MaskEdge(a.first, a.second, b.first, b.second))
                }

                when (code) {
                    1, 14 -> emit(onLeft, onBottom)
                    2, 13 -> emit(onBottom, onRight)
                    3, 12 -> emit(onLeft, onRight)
                    4, 11 -> emit(onTop, onRight)
                    6, 9 -> emit(onTop, onBottom)
                    7, 8 -> emit(onLeft, onTop)
                    // Saddles: two opposite corners in, two out. Both crossings are drawn, which
                    // keeps every loop closed — the alternative reading only changes which of two
                    // touching regions is joined, and at this scale that is invisible.
                    5 -> { emit(onLeft, onTop); emit(onBottom, onRight) }
                    10 -> { emit(onTop, onRight); emit(onLeft, onBottom) }
                }
            }
        }
        return edges
    }

    /**
     * How far along the run from [from] to [to] the threshold falls, 0..1.
     *
     * This is the whole difference between a contour and a staircase: without it every crossing
     * would land at the midpoint, which is just the cell boundary by another name.
     */
    private fun cross(from: Float, to: Float, threshold: Float): Float {
        val span = to - from
        if (span == 0f) return 0.5f
        return ((threshold - from) / span).coerceIn(0f, 1f)
    }
}
