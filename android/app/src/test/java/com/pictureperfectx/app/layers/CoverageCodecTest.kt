package com.pictureperfectx.app.layers

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A mask is fifty thousand numbers. Written out naively a single saved edit is larger than the photo
 * it belongs to, so the encoding has to be both small and exactly reversible — and "exactly" is the
 * word that matters, because a mask that comes back a fraction different is an edit that reopens
 * looking subtly unlike the one that was saved.
 */
class CoverageCodecTest {

    @Test
    fun `a run of the same value collapses to one pair`() {
        assertEquals("4096x0", CoverageCodec.encode(FloatArray(4096)))
        assertEquals("100x255", CoverageCodec.encode(FloatArray(100) { 1f }))
    }

    @Test
    fun `values already on the 8-bit grid come back untouched`() {
        val coverage = FloatArray(256) { it / 255f }
        val decoded = CoverageCodec.decode(CoverageCodec.encode(coverage))
        assertTrue("length", decoded.size == coverage.size)
        coverage.indices.forEach { index ->
            assertEquals("cell $index", coverage[index], decoded[index], 0f)
        }
    }

    @Test
    fun `anything else lands within a step of where it started`() {
        val coverage = FloatArray(500) { it / 499f }
        val decoded = CoverageCodec.decode(CoverageCodec.encode(coverage))
        coverage.indices.forEach { index ->
            assertEquals("cell $index", coverage[index], decoded[index], 1f / 255f)
        }
    }

    @Test
    fun `encoding twice gives the same text, so a resave is not a slow drift`() {
        val coverage = FloatArray(400) { (it % 7) / 6f }
        val once = CoverageCodec.encode(coverage)
        val twice = CoverageCodec.encode(CoverageCodec.decode(once))
        assertEquals(once, twice)
    }

    @Test
    fun `an empty grid stays empty, since that means show everything`() {
        assertEquals("", CoverageCodec.encode(FloatArray(0)))
        assertEquals(0, CoverageCodec.decode("").size)
        assertEquals(0, CoverageCodec.decode("   ").size)
    }

    @Test
    fun `a real selection encodes small enough to keep`() {
        // A lassoed circle on a 256-wide grid: the shape of thing actually being saved.
        val mask = MaskLasso.trace(
            Mask.forRatio(4f / 3f),
            (0 until 64).map { step ->
                val angle = step * 2.0 * PI / 64
                MaskPoint(0.5f + 0.3f * cos(angle).toFloat(), 0.5f + 0.3f * sin(angle).toFloat())
            },
        )
        val encoded = CoverageCodec.encode(mask.coverage)
        assertTrue("${mask.coverage.size} cells should not need ${encoded.length} chars",
            encoded.length < mask.coverage.size / 4)
        // And it still comes back.
        assertEquals(mask.coverage.size, CoverageCodec.decode(encoded).size)
    }

    @Test
    fun `a full frame is a handful of characters`() {
        val full = Mask.full(256, 192)
        assertTrue(CoverageCodec.encode(full.coverage).length < 16)
    }

    @Test
    fun `nonsense is refused rather than decoded into something wrong`() {
        assertThrows(SerializationException::class.java) { CoverageCodec.decode("hello") }
        assertThrows(SerializationException::class.java) { CoverageCodec.decode("4x") }
        assertThrows(SerializationException::class.java) { CoverageCodec.decode("x4") }
        assertThrows(SerializationException::class.java) { CoverageCodec.decode("0x5") }
    }
}
