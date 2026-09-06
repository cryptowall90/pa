package com.pictureperfectx.app.layers

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Healing has three separate ways to be subtly wrong — the patch lands in the wrong place, it
 * doesn't match the skin around it, or the join shows — and all three are judged by eye on a phone
 * otherwise. Pixels in, pixels out, so CI can do the judging.
 */
class HealTest {

    private val width = 64
    private val height = 64

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun red(pixel: Int) = (pixel shr 16) and 0xFF

    /** A flat field of skin-ish colour. */
    private fun flat(r: Int = 200, g: Int = 170, b: Int = 150) =
        IntArray(width * height) { argb(r, g, b) }

    private fun spot(pixels: IntArray, x: Int, y: Int, radius: Int, colour: Int) {
        for (row in -radius..radius) {
            for (column in -radius..radius) {
                if (column * column + row * row > radius * radius) continue
                pixels[(y + row) * width + x + column] = colour
            }
        }
    }

    @Test
    fun `a blemish on flat skin is gone afterwards`() {
        val pixels = flat()
        spot(pixels, 32, 32, 4, argb(90, 40, 40))
        assertEquals(90, red(pixels[32 * width + 32]))

        val source = Heal.chooseSource(pixels, width, height, 32, 32, 5)
        assertNotNull("clean skin should be available all around", source)
        Heal.apply(pixels, width, height, 32, 32, source!!.first, source.second, 5)

        assertEquals("the spot should read as skin again", 200, red(pixels[32 * width + 32]))
    }

    @Test
    fun `nothing outside the disc is touched`() {
        val pixels = flat()
        spot(pixels, 32, 32, 3, argb(90, 40, 40))
        val before = pixels.copyOf()

        Heal.apply(pixels, width, height, 32, 32, 20, 20, 5)

        // A corner well clear of both the destination and the source it borrowed from.
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                assertEquals(before[y * width + x], pixels[y * width + x])
            }
        }
    }

    @Test
    fun `a patch borrowed from lighter skin is shifted to match its surroundings`() {
        // Left half dark, right half light. Healing on the dark side from the light side must not
        // leave a bright disc: the patch is corrected to the colour around where it lands.
        val pixels = IntArray(width * height) { index ->
            if (index % width < width / 2) argb(120, 100, 90) else argb(220, 200, 190)
        }
        spot(pixels, 16, 32, 3, argb(40, 20, 20))

        Heal.apply(pixels, width, height, 16, 32, 48, 32, 5)

        val healed = red(pixels[32 * width + 16])
        assertTrue(
            "should match the dark side, was $healed",
            abs(healed - 120) <= 12,
        )
    }

    @Test
    fun `the repair fades out rather than leaving a rim`() {
        // The fade is only observable when the patch differs from the skin *after* the colour
        // match, so the source's interior is darker than its own rim: the shift comes out zero and
        // the interior lands as-is.
        val pixels = flat()
        val radius = 6
        for (row in -radius..radius) {
            for (column in -radius..radius) {
                if (column * column + row * row <= 25) {
                    pixels[(20 + row) * width + 20 + column] = argb(120, 120, 120)
                }
            }
        }

        Heal.apply(pixels, width, height, 32, 32, 20, 20, radius)

        val centre = red(pixels[32 * width + 32])
        val rim = red(pixels[(32 + 5) * width + 32])
        val outside = red(pixels[(32 + 8) * width + 32])
        assertEquals("the middle takes the patch whole", 120, centre)
        assertEquals("outside the disc is untouched", 200, outside)
        assertTrue("the rim should sit between the two, was $rim", rim in (centre + 1) until outside)
    }

    @Test
    fun `the flattest neighbour is preferred`() {
        // Busy stripes on one side, flat on the other. The flat side is the clean skin.
        val pixels = flat()
        for (y in 0 until height) {
            for (x in 0 until width / 2) {
                if ((x / 2) % 2 == 0) pixels[y * width + x] = argb(40, 30, 30)
            }
        }
        val source = Heal.chooseSource(pixels, width, height, 32, 32, 5)
        assertNotNull(source)
        assertTrue("should borrow from the flat side, picked ${source!!.first}", source.first > 32)
    }

    @Test
    fun `a spot with no room around it reports rather than healing from nowhere`() {
        // A spot this big in the corner leaves nowhere for a whole patch to sit. Clamping one back
        // inside would overlap the blemish and heal it with itself.
        val pixels = flat()
        assertNull(Heal.chooseSource(pixels, width, height, 1, 1, 26))
    }

    @Test
    fun `a dab at the edge is clipped rather than crashing`() {
        val pixels = flat()
        spot(pixels, 2, 2, 1, argb(0, 0, 0))
        Heal.apply(pixels, width, height, 2, 2, 30, 30, 6)
        assertEquals("still the right number of pixels", width * height, pixels.size)
    }

    @Test
    fun `a zero radius does nothing`() {
        val pixels = flat()
        val before = pixels.copyOf()
        Heal.apply(pixels, width, height, 32, 32, 20, 20, 0)
        assertTrue(before.contentEquals(pixels))
    }

    @Test
    fun `healing stays inside the visible colour range`() {
        // Borrowing from near-black to cover near-white asks for a shift that would overflow.
        val pixels = IntArray(width * height) { index ->
            if (index % width < width / 2) argb(250, 250, 250) else argb(5, 5, 5)
        }
        Heal.apply(pixels, width, height, 16, 32, 48, 32, 5)
        pixels.forEach { pixel ->
            assertTrue(red(pixel) in 0..255)
        }
    }
}
