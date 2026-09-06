package com.pictureperfectx.app.layers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wand's failure mode is famous and quiet: comparing each cell to its neighbour instead of to
 * the colour that was tapped, so a gentle gradient walks it one indistinguishable step at a time
 * until the whole photo is selected. That's the thing worth pinning down, and it can't be judged by
 * eye until it has already ruined an edit.
 */
class MaskWandTest {

    private val edge = 32

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val red = argb(255, 0, 0)
    private val blue = argb(0, 0, 255)

    private fun mask() = Mask.blank(edge, edge)

    private fun grid(fill: Int) = IntArray(edge * edge) { fill }

    private fun IntArray.box(columns: IntRange, rows: IntRange, colour: Int) = apply {
        rows.forEach { row -> columns.forEach { column -> this[row * edge + column] = colour } }
    }

    /** The centre of a cell, in the normalized space a tap arrives in. */
    private fun at(column: Int, row: Int) = (column + 0.5f) / edge to (row + 0.5f) / edge

    private fun Mask.covered() = (0 until columns * rows).count { coverage[it] > 0.5f }

    @Test
    fun `a shape is taken whole and its surroundings are left alone`() {
        val pixels = grid(blue).box(8..23, 8..23, red)
        val (x, y) = at(16, 16)
        val chosen = MaskWand.select(pixels, edge, edge, mask(), x, y, tolerance = 0.2f)

        assertEquals("the tapped colour", 1f, chosen.coverageAt(16, 16), 0.001f)
        assertEquals("well outside it", 0f, chosen.coverageAt(2, 2), 0.001f)
        assertEquals("exactly the square, no more", 16 * 16, chosen.covered())
    }

    @Test
    fun `contiguous takes this shape, global takes every shape like it`() {
        // Two identical squares with a gap between them: the difference between "this shirt" and
        // "every red thing in the frame".
        val pixels = grid(blue).box(4..11, 12..19, red).box(20..27, 12..19, red)
        val (x, y) = at(8, 16)

        val near = MaskWand.select(pixels, edge, edge, mask(), x, y, 0.2f, contiguous = true)
        assertEquals(8 * 8, near.covered())
        assertEquals("the far square is a different area", 0f, near.coverageAt(24, 16), 0.001f)

        val everywhere = MaskWand.select(pixels, edge, edge, mask(), x, y, 0.2f, contiguous = false)
        assertEquals(2 * 8 * 8, everywhere.covered())
        assertEquals("the far square matches too", 1f, everywhere.coverageAt(24, 16), 0.001f)
    }

    @Test
    fun `a gradient does not walk the selection across the whole photo`() {
        // Black to white left to right. Every neighbour is nearly identical to the last, so a wand
        // that compared neighbours would select all of it. Tolerance is measured from the tap.
        val pixels = IntArray(edge * edge) { index ->
            val level = Math.round((index % edge) * 255f / (edge - 1))
            argb(level, level, level)
        }
        val (x, y) = at(0, 16)
        val chosen = MaskWand.select(pixels, edge, edge, mask(), x, y, tolerance = 0.1f)

        val touched = (0 until edge * edge).count { chosen.coverage[it] > 0f }
        assertTrue(
            "a tenth of the range should not select ${touched * 100 / (edge * edge)}% of the photo",
            touched < edge * edge / 4,
        )
        assertTrue("but it should select something", touched > 0)
    }

    @Test
    fun `the edge fades rather than stepping straight to nothing`() {
        val pixels = IntArray(edge * edge) { index ->
            val level = Math.round((index % edge) * 255f / (edge - 1))
            argb(level, level, level)
        }
        val (x, y) = at(0, 16)
        val chosen = MaskWand.select(pixels, edge, edge, mask(), x, y, tolerance = 0.1f)

        val partial = (0 until edge).count { column ->
            val value = chosen.coverageAt(column, 16)
            value > 0f && value < 1f
        }
        assertTrue("there should be a soft edge, found $partial partial cells", partial > 0)
    }

    @Test
    fun `no tolerance at all means exactly this colour and nothing near it`() {
        // Two reds five levels apart — indistinguishable by eye, and deliberately not the same.
        val pixels = grid(argb(200, 0, 0)).box(16..31, 0..31, argb(205, 0, 0))
        val (x, y) = at(3, 16)
        val chosen = MaskWand.select(pixels, edge, edge, mask(), x, y, 0f, contiguous = false)

        assertEquals("only the exact colour", 16 * edge, chosen.covered())
        assertEquals(1f, chosen.coverageAt(3, 16), 0.001f)
        assertEquals(0f, chosen.coverageAt(20, 16), 0.001f)
    }

    @Test
    fun `adding and subtracting combine the way the other tools do`() {
        val pixels = grid(blue).box(4..11, 12..19, red).box(20..27, 12..19, red)
        val (leftX, leftY) = at(8, 16)
        val (rightX, rightY) = at(24, 16)

        val left = MaskWand.select(pixels, edge, edge, mask(), leftX, leftY, 0.2f)
        val both = MaskWand.select(
            pixels, edge, edge, left, rightX, rightY, 0.2f, mode = SelectionMode.Add,
        )
        assertEquals("both squares", 2 * 8 * 8, both.covered())

        val backToOne = MaskWand.select(
            pixels, edge, edge, both, rightX, rightY, 0.2f, mode = SelectionMode.Subtract,
        )
        assertEquals(8 * 8, backToOne.covered())
        assertEquals(1f, backToOne.coverageAt(8, 16), 0.001f)
        assertEquals(0f, backToOne.coverageAt(24, 16), 0.001f)
    }

    @Test
    fun `a wand selection keeps no shape, because no shape describes it`() {
        val lassoed = MaskLasso.trace(
            mask(),
            listOf(MaskPoint(0.2f, 0.2f), MaskPoint(0.8f, 0.3f), MaskPoint(0.5f, 0.8f)),
        )
        val (x, y) = at(16, 16)
        val chosen = MaskWand.select(grid(red), edge, edge, lassoed, x, y, 0.2f)
        assertEquals(null, chosen.path)
        assertEquals(null, chosen.gradient)
    }

    @Test
    fun `a tap outside the photo changes nothing`() {
        val before = mask()
        assertEquals(before, MaskWand.select(grid(red), edge, edge, before, -0.2f, 0.5f))
        assertEquals(before, MaskWand.select(grid(red), edge, edge, before, 0.5f, 1.4f))
    }

    @Test
    fun `a grid that doesn't describe the mask's cells is refused`() {
        // Coverage would land offset from the colours it was chosen by, which is worse than nothing.
        val before = mask()
        assertEquals(before, MaskWand.select(IntArray(16 * 16), 16, 16, before, 0.5f, 0.5f))
    }

    @Test
    fun `a whole frame of one colour is selected whole without running out of stack`() {
        // Every cell matches, so the flood visits all of them — the case that overflows a recursive
        // implementation on a real photo.
        val big = Mask.blank(256, 192)
        val chosen = MaskWand.select(IntArray(256 * 192) { red }, 256, 192, big, 0.5f, 0.5f, 0.2f)
        assertTrue(chosen.coverage.all { it == 1f })
    }
}
