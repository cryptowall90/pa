package com.pictureperfectx.app.layers

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Paints coverage into a [Mask].
 *
 * Everything is in normalized 0..1 coordinates, matching how masks are stored, so a stroke means
 * the same thing whether it was drawn on a phone screen or replayed against the full-resolution
 * export.
 */
object MaskBrush {

    /** Softest edge of the brush, as a fraction of its radius. */
    private const val SOFT_EDGE = 0.4f

    /**
     * Stamps a soft circular dab at ([x], [y]) with [radius], all normalized.
     *
     * Coverage accumulates towards 1 rather than being overwritten, so overlapping dabs along a
     * drag build up into a smooth stroke instead of showing each dab's rim. Erasing does the
     * reverse, driving coverage back down towards 0.
     */
    fun paint(mask: Mask, x: Float, y: Float, radius: Float, erase: Boolean = false): Mask {
        if (mask.columns <= 0 || mask.rows <= 0 || radius <= 0f) return mask
        val coverage = if (mask.isEmpty) FloatArray(mask.columns * mask.rows) else mask.coverage.copyOf()

        // Work in grid cells; the grid needn't be square, so each axis converts separately.
        val centreColumn = x * mask.columns
        val centreRow = y * mask.rows
        val radiusColumns = radius * mask.columns
        val radiusRows = radius * mask.rows

        val firstColumn = max(0, (centreColumn - radiusColumns).toInt())
        val lastColumn = minOf(mask.columns - 1, ceil(centreColumn + radiusColumns).toInt())
        val firstRow = max(0, (centreRow - radiusRows).toInt())
        val lastRow = minOf(mask.rows - 1, ceil(centreRow + radiusRows).toInt())

        for (row in firstRow..lastRow) {
            for (column in firstColumn..lastColumn) {
                val strength = strengthAt(
                    column + 0.5f - centreColumn,
                    row + 0.5f - centreRow,
                    radiusColumns,
                    radiusRows,
                )
                if (strength <= 0f) continue
                val index = row * mask.columns + column
                coverage[index] = if (erase) {
                    (coverage[index] - strength).coerceAtLeast(0f)
                } else {
                    maxOf(coverage[index], strength)
                }
            }
        }
        // A brushed edge has no points to drag, so any lasso this mask started as stops describing
        // it. Keeping the polygon would put handles on a shape that has moved out from under them.
        return mask.copy(coverage = coverage, path = null, gradient = null)
    }

    /** 1 at the centre, falling to 0 at the rim over the outer [SOFT_EDGE] of the radius. */
    private fun strengthAt(dx: Float, dy: Float, radiusX: Float, radiusY: Float): Float {
        if (radiusX <= 0f || radiusY <= 0f) return 0f
        // Normalising each axis by its own radius makes the dab round on screen even when the grid
        // isn't square.
        val nx = dx / radiusX
        val ny = dy / radiusY
        val distance = sqrt(nx * nx + ny * ny)
        return when {
            distance >= 1f -> 0f
            distance <= 1f - SOFT_EDGE -> 1f
            else -> (1f - distance) / SOFT_EDGE
        }
    }
}
