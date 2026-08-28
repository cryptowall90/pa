package com.pictureperfectx.app.ui.perfect

import com.pictureperfectx.app.layers.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The control row, collapsed and opened.
 *
 * This is the row the whole panel hangs off, and the failure worth pinning is subtle: a group whose
 * members come out somewhere other than directly behind its own chip reads as an unrelated jumble
 * of sliders, which is the thing the grouping exists to stop.
 */
class ChipItemsTest {

    private val tone = LayerControl.forLayer(Layer.Tone(id = 1))

    private fun labels(expanded: ControlGroup?) = chipItems(tone, expanded).map {
        when (it) {
            is ChipItem.Group -> it.group.label
            is ChipItem.Control -> it.control.label
        }
    }

    @Test
    fun `closed, nine adjustments read as two words`() {
        assertEquals(listOf("Light", "Color", "Opacity"), labels(expanded = null))
    }

    @Test
    fun `opening a group spills its members directly behind its own chip`() {
        assertEquals(
            listOf(
                "Light", "Exposure", "Contrast", "Blacks", "Shadows", "Highlights", "Whites",
                "Color",
                "Opacity",
            ),
            labels(ControlGroup.Light),
        )
    }

    @Test
    fun `only one group is ever open`() {
        val opened = labels(ControlGroup.Colour)
        assertEquals(
            listOf("Light", "Color", "Saturation", "Vibrance", "Warmth", "Opacity"),
            opened,
        )
    }

    @Test
    fun `a group contributes exactly one chip however many members it has`() {
        val groups = chipItems(tone, ControlGroup.Light).filterIsInstance<ChipItem.Group>()
        assertEquals(2, groups.size)
        assertEquals(1, groups.count { it.group == ControlGroup.Light })
        assertTrue("and it says which one is open", groups.single { it.isOpen }.group == ControlGroup.Light)
    }

    @Test
    fun `a layer with no groups passes straight through`() {
        // Every other layer's row is unchanged by any of this.
        val text = LayerControl.forLayer(Layer.Text(id = 1))
        assertEquals(
            text.map { ChipItem.Control(it) },
            chipItems(text, expanded = null),
        )
        assertEquals("and opening a group it hasn't got changes nothing",
            chipItems(text, expanded = null), chipItems(text, ControlGroup.Light))
    }

    @Test
    fun `the row is what the panel actually renders, so nothing is lost on the way`() {
        // Every control still reachable: closed, through its group chip; open, directly.
        val reachable = (chipItems(tone, ControlGroup.Light) + chipItems(tone, ControlGroup.Colour))
            .filterIsInstance<ChipItem.Control>()
            .map { it.control }
            .toSet()
        assertEquals(tone.toSet(), reachable)
    }
}
