package com.pictureperfectx.app.ui.perfect

import com.pictureperfectx.app.capture.ToneBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the two drawers between them hold every tone adjustment.
 *
 * The failure this exists to prevent is quiet: a band added to [ToneBand] later gets a chip from
 * `forLayer` automatically, but if nobody gives it a group it lands inside a drawer that never
 * opens and simply cannot be reached. Nothing would look broken — the control would just not be
 * there.
 */
class ControlGroupTest {

    private val grouped = LayerControl.entries.filter { it.group != null }

    @Test
    fun `grouping and being a tone band are the same thing`() {
        assertEquals(
            "a grouped control that isn't a band would open a drawer onto nothing",
            LayerControl.entries.filter { it.band != null }.toSet(),
            grouped.toSet(),
        )
    }

    @Test
    fun `the two groups between them hold every band exactly once`() {
        val light = grouped.filter { it.group == ControlGroup.Light }
        val colour = grouped.filter { it.group == ControlGroup.Colour }

        assertTrue("nothing belongs to both", light.intersect(colour.toSet()).isEmpty())
        assertEquals(
            "and nothing belongs to neither",
            ToneBand.entries.toSet(),
            (light + colour).mapNotNull { it.band }.toSet(),
        )
    }

    @Test
    fun `each group keeps its members together, so a drawer opens onto one run`() {
        // chipItems inserts a group's members straight after its chip. If the enum interleaved
        // them, the row order and the declaration order would disagree about what a group contains.
        val runs = grouped.map { it.group }
        val collapsed = runs.filterIndexed { index, group -> index == 0 || runs[index - 1] != group }
        assertEquals(
            "Light then Color, each in one unbroken run",
            listOf(ControlGroup.Light, ControlGroup.Colour),
            collapsed,
        )
    }

    @Test
    fun `light holds the ones about brightness and color the ones about hue`() {
        assertEquals(
            listOf(
                ToneBand.Exposure,
                ToneBand.Contrast,
                ToneBand.Blacks,
                ToneBand.Shadows,
                ToneBand.Highlights,
                ToneBand.Whites,
            ),
            grouped.filter { it.group == ControlGroup.Light }.mapNotNull { it.band },
        )
        assertEquals(
            listOf(ToneBand.Saturation, ToneBand.Vibrance, ToneBand.Warmth),
            grouped.filter { it.group == ControlGroup.Colour }.mapNotNull { it.band },
        )
    }
}
