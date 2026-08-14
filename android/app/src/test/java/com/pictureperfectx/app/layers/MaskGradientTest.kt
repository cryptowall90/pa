package com.pictureperfectx.app.layers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A gradient is smooth by nature, so the ways it goes wrong are the quiet ones: an ellipse where a
 * circle was drawn, a reflection that isn't symmetric, a midpoint control that does nothing. None of
 * those announce themselves on a phone screen, so they're pinned here.
 */
class MaskGradientTest {

    /** Coverage on a square grid, where cell units and normalized units agree. */
    private fun square(spec: GradientSpec, x: Float, y: Float, size: Int = 100) =
        MaskGradient.coverage(spec, x, y, size, size)

    private fun linear(midpoint: Float = 0.5f) = GradientSpec(
        style = GradientStyle.Linear,
        start = MaskPoint(0f, 0f),
        end = MaskPoint(1f, 0f),
        midpoint = midpoint,
    )

    @Test
    fun `a linear gradient runs from full at the start to nothing at the end`() {
        val spec = linear()
        assertEquals(1f, square(spec, 0f, 0.5f), 0.01f)
        assertEquals(0.5f, square(spec, 0.5f, 0.5f), 0.01f)
        assertEquals(0f, square(spec, 1f, 0.5f), 0.01f)
    }

    @Test
    fun `a linear gradient is constant across its axis`() {
        val spec = linear()
        val top = square(spec, 0.3f, 0.05f)
        val middle = square(spec, 0.3f, 0.5f)
        val bottom = square(spec, 0.3f, 0.95f)
        assertEquals(top, middle, 0.001f)
        assertEquals(middle, bottom, 0.001f)
    }

    @Test
    fun `past either end it holds rather than wrapping`() {
        val spec = GradientSpec(
            style = GradientStyle.Linear,
            start = MaskPoint(0.4f, 0.5f),
            end = MaskPoint(0.6f, 0.5f),
        )
        assertEquals("before the start stays full", 1f, square(spec, 0.05f, 0.5f), 0.001f)
        assertEquals("past the end stays empty", 0f, square(spec, 0.95f, 0.5f), 0.001f)
    }

    @Test
    fun `a radial gradient is circular on a wide photo, not oval`() {
        // The reason coverage is measured in cell units. On a 16:9 grid, the same distance in
        // *pixels* has to give the same coverage whichever direction it's in — normalized
        // coordinates would stretch the circle out sideways.
        val columns = 160
        val rows = 90
        val spec = GradientSpec(
            style = GradientStyle.Radial,
            start = MaskPoint(0.5f, 0.5f),
            end = MaskPoint(0.75f, 0.5f), // radius of 40 cells
        )
        // 20 cells from the centre, once across and once down.
        val across = MaskGradient.coverage(spec, 0.5f + 20f / columns, 0.5f, columns, rows)
        val down = MaskGradient.coverage(spec, 0.5f, 0.5f + 20f / rows, columns, rows)
        assertEquals("equal distances should give equal coverage", across, down, 0.01f)
        assertEquals("and 20 of 40 cells is halfway", 0.5f, across, 0.02f)
    }

    @Test
    fun `a reflected gradient is symmetric about its start`() {
        val spec = GradientSpec(
            style = GradientStyle.Reflected,
            start = MaskPoint(0.5f, 0.5f),
            end = MaskPoint(0.9f, 0.5f),
        )
        assertEquals(1f, square(spec, 0.5f, 0.5f), 0.01f)
        assertEquals(square(spec, 0.7f, 0.5f), square(spec, 0.3f, 0.5f), 0.001f)
        assertEquals(square(spec, 0.9f, 0.5f), square(spec, 0.1f, 0.5f), 0.001f)
    }

    @Test
    fun `a diamond falls off along its diagonals as well as its axis`() {
        val spec = GradientSpec(
            style = GradientStyle.Diamond,
            start = MaskPoint(0.5f, 0.5f),
            end = MaskPoint(0.9f, 0.5f),
        )
        assertEquals("full at the centre", 1f, square(spec, 0.5f, 0.5f), 0.01f)
        // The four points of the diamond sit at the same distance along each axis.
        val right = square(spec, 0.7f, 0.5f)
        val up = square(spec, 0.5f, 0.3f)
        assertEquals(right, up, 0.001f)
        // A corner combines both, so it runs out sooner than either axis alone.
        assertTrue("the diagonal should fall off faster", square(spec, 0.7f, 0.7f) < right)
    }

    @Test
    fun `an angular gradient sweeps a full turn around its start`() {
        val spec = GradientSpec(
            style = GradientStyle.Angular,
            start = MaskPoint(0.5f, 0.5f),
            end = MaskPoint(0.9f, 0.5f),
        )
        // Just off the axis it is still nearly full; three quarters round, nearly empty.
        assertTrue("just past the axis is still strong", square(spec, 0.9f, 0.51f) > 0.9f)
        val quarter = square(spec, 0.5f, 0.9f)
        val threeQuarters = square(spec, 0.5f, 0.1f)
        assertTrue("coverage should fall as the sweep goes round", threeQuarters < quarter)
    }

    @Test
    fun `the midpoint moves where the halfway line falls`() {
        val early = linear(midpoint = 0.25f)
        val late = linear(midpoint = 0.75f)
        assertEquals("an early midpoint reaches half sooner", 0.5f, square(early, 0.25f, 0.5f), 0.02f)
        assertEquals("a late one holds on longer", 0.5f, square(late, 0.75f, 0.5f), 0.02f)
        // Both ends are fixed whatever the midpoint does.
        listOf(early, late).forEach { spec ->
            assertEquals(1f, square(spec, 0f, 0.5f), 0.01f)
            assertEquals(0f, square(spec, 1f, 0.5f), 0.01f)
        }
    }

    @Test
    fun `a midpoint of one half changes nothing`() {
        assertEquals(square(linear(), 0.3f, 0.5f), square(linear(midpoint = 0.5f), 0.3f, 0.5f), 0.0001f)
    }

    @Test
    fun `a tap with no drag produces nothing rather than dividing by zero`() {
        val spec = GradientSpec(start = MaskPoint(0.5f, 0.5f), end = MaskPoint(0.5f, 0.5f))
        val value = square(spec, 0.5f, 0.5f)
        assertTrue("should be a real number, was $value", !value.isNaN())
        assertEquals(0f, value, 0.0001f)
    }

    @Test
    fun `filling a mask keeps the gradient adjustable but combining drops it`() {
        val spec = GradientSpec(start = MaskPoint(0f, 0f), end = MaskPoint(1f, 0f))
        val filled = MaskGradient.fill(Mask.blank(32, 32), spec)
        assertEquals("a placed gradient stays editable", spec, filled.gradient)
        assertTrue("the start end should be covered", filled.coverageAt(0, 16) > 0.9f)
        assertTrue("the far end should not", filled.coverageAt(31, 16) < 0.1f)

        val added = MaskGradient.fill(filled, spec, SelectionMode.Add)
        assertNull("combining leaves a shape the gradient no longer describes", added.gradient)
    }

    @Test
    fun `a gradient replaces a lasso's points rather than sitting alongside them`() {
        // A mask is described by a polygon, or by a gradient, or by neither — never both, or the
        // editor would not know which handles to show.
        val lassoed = MaskLasso.trace(
            Mask.blank(32, 32),
            listOf(MaskPoint(0.2f, 0.2f), MaskPoint(0.8f, 0.2f), MaskPoint(0.8f, 0.8f)),
        )
        assertTrue(lassoed.path != null)

        val gradient = MaskGradient.fill(lassoed, GradientSpec())
        assertNull(gradient.path)
        assertTrue(gradient.gradient != null)

        val relassoed = MaskLasso.trace(
            gradient,
            listOf(MaskPoint(0.2f, 0.2f), MaskPoint(0.8f, 0.2f), MaskPoint(0.8f, 0.8f)),
        )
        assertNull(relassoed.gradient)
        assertTrue(relassoed.path != null)
    }
}
