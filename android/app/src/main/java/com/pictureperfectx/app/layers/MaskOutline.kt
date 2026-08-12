package com.pictureperfectx.app.layers

/** A straight run of a mask's boundary, in the normalized 0..1 space masks are stored in. */
data class MaskEdge(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/**
 * Traces the boundary of a mask's covered area.
 *
 * Drawing a selection as a translucent fill puts colour over exactly the pixels whose change the
 * user is trying to judge. An outline says the same thing — here is where this applies — while
 * leaving the picture itself alone.
 *
 * Pure Kotlin with no Android types, like [MaskLasso] and `CropMath`, so CI can check the shapes it
 * produces rather than leaving that to the eye.
 */
object MaskOutline {

    /** Coverage at or above this counts as inside, matching where an effect visibly takes hold. */
    const val DEFAULT_THRESHOLD = 0.5f

    /**
     * The cell edges separating covered from uncovered.
     *
     * Each boundary is emitted once, from its inside cell, so segments never double up. A neighbour
     * off the grid counts as outside, which is what closes the outline around a selection running
     * to the edge of the photo instead of leaving it hanging open.
     */
    fun segments(mask: Mask, threshold: Float = DEFAULT_THRESHOLD): List<MaskEdge> {
        // An empty mask renders as "applies everywhere", but there is no drawn area to outline.
        if (mask.isEmpty || mask.columns <= 0 || mask.rows <= 0) return emptyList()

        val columns = mask.columns
        val rows = mask.rows
        val cellWidth = 1f / columns
        val cellHeight = 1f / rows
        val edges = ArrayList<MaskEdge>()

        fun isInside(column: Int, row: Int): Boolean {
            if (column !in 0 until columns || row !in 0 until rows) return false
            return mask.coverageAt(column, row) >= threshold
        }

        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if (!isInside(column, row)) continue
                // Each boundary is computed from its cell index rather than by adding a width to
                // the previous one, so the right edge of one cell is bit-identical to the left edge
                // of the next. Otherwise the outline is stitched from segments that don't quite
                // meet.
                val left = column * cellWidth
                val right = (column + 1) * cellWidth
                val top = row * cellHeight
                val bottom = (row + 1) * cellHeight

                if (!isInside(column, row - 1)) edges.add(MaskEdge(left, top, right, top))
                if (!isInside(column, row + 1)) edges.add(MaskEdge(left, bottom, right, bottom))
                if (!isInside(column - 1, row)) edges.add(MaskEdge(left, top, left, bottom))
                if (!isInside(column + 1, row)) edges.add(MaskEdge(right, top, right, bottom))
            }
        }
        return edges
    }
}
