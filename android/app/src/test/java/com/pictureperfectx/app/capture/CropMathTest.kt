package com.pictureperfectx.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crop maths is the one part of the Perfect Editor that can actually be executed in CI, and a
 * mistake here shows up as a saved photo that doesn't match what the preview promised — so the
 * normalized-to-pixel round trip is worth pinning down.
 */
class CropMathTest {

    private val tolerance = 0.0001f

    @Test
    fun `quarter turns swap the axes only on odd turns`() {
        assertEquals(4000 to 3000, CropMath.orientedSize(4000, 3000, 0))
        assertEquals(3000 to 4000, CropMath.orientedSize(4000, 3000, 1))
        assertEquals(4000 to 3000, CropMath.orientedSize(4000, 3000, 2))
        assertEquals(3000 to 4000, CropMath.orientedSize(4000, 3000, 3))
    }

    @Test
    fun `negative quarter turns still swap correctly`() {
        assertEquals(3000 to 4000, CropMath.orientedSize(4000, 3000, -1))
        assertEquals(4000 to 3000, CropMath.orientedSize(4000, 3000, -2))
    }

    @Test
    fun `rotating by zero leaves the bounds alone`() {
        val (w, h) = CropMath.rotatedBounds(100f, 50f, 0f)
        assertEquals(100f, w, tolerance)
        assertEquals(50f, h, tolerance)
    }

    @Test
    fun `rotating a square by 45 degrees grows it by root two`() {
        val (w, h) = CropMath.rotatedBounds(100f, 100f, 45f)
        val expected = (100.0 * Math.sqrt(2.0)).toFloat()
        assertEquals(expected, w, 0.01f)
        assertEquals(expected, h, 0.01f)
    }

    @Test
    fun `rotating by 90 degrees swaps the bounds`() {
        val (w, h) = CropMath.rotatedBounds(100f, 50f, 90f)
        assertEquals(50f, w, 0.01f)
        assertEquals(100f, h, 0.01f)
    }

    @Test
    fun `square crop of a landscape image keeps full height and insets the width`() {
        // 2:1 image, want 1:1 -> normalized width should be half, full height.
        val crop = CropMath.centeredCrop(sourceRatio = 2f, targetRatio = 1f)
        assertEquals(0.5f, crop.width, tolerance)
        assertEquals(1f, crop.height, tolerance)
        assertEquals(0.25f, crop.left, tolerance)
        assertEquals(0.75f, crop.right, tolerance)
    }

    @Test
    fun `wide crop of a portrait image keeps full width and insets the height`() {
        // 1:2 image, want 1:1 -> full width, half height.
        val crop = CropMath.centeredCrop(sourceRatio = 0.5f, targetRatio = 1f)
        assertEquals(1f, crop.width, tolerance)
        assertEquals(0.5f, crop.height, tolerance)
        assertEquals(0.25f, crop.top, tolerance)
    }

    @Test
    fun `a crop matching the source ratio fills the frame`() {
        val crop = CropMath.centeredCrop(sourceRatio = 1.5f, targetRatio = 1.5f)
        assertEquals(0f, crop.left, tolerance)
        assertEquals(0f, crop.top, tolerance)
        assertEquals(1f, crop.right, tolerance)
        assertEquals(1f, crop.bottom, tolerance)
    }

    @Test
    fun `centered crop produces the requested pixel ratio`() {
        val width = 4000
        val height = 3000
        val sourceRatio = width.toFloat() / height
        val crop = CropMath.centeredCrop(sourceRatio, targetRatio = 16f / 9f)
        val rect = CropMath.pixelRect(crop, width, height)
        assertEquals(16f / 9f, rect.width.toFloat() / rect.height, 0.01f)
    }

    @Test
    fun `every preset lands on its own ratio`() {
        val width = 4000
        val height = 3000
        val sourceRatio = width.toFloat() / height
        AspectRatio.entries.forEach { preset ->
            val target = preset.ratio(sourceRatio) ?: return@forEach // Free is unconstrained
            val rect = CropMath.pixelRect(CropMath.centeredCrop(sourceRatio, target), width, height)
            assertEquals(
                "${preset.label} should crop to its own ratio",
                target,
                rect.width.toFloat() / rect.height,
                0.01f,
            )
        }
    }

    @Test
    fun `Original resolves to the source ratio and Free stays unconstrained`() {
        assertEquals(1.5f, AspectRatio.Original.ratio(1.5f)!!, tolerance)
        assertNull(AspectRatio.Free.ratio(1.5f))
    }

    @Test
    fun `clamp pulls an out-of-bounds rect back inside`() {
        val crop = CropMath.clamp(CropRect(-0.5f, -0.2f, 1.7f, 1.3f))
        assertEquals(0f, crop.left, tolerance)
        assertEquals(0f, crop.top, tolerance)
        assertEquals(1f, crop.right, tolerance)
        assertEquals(1f, crop.bottom, tolerance)
    }

    @Test
    fun `clamp keeps a minimum size when handles collapse`() {
        val crop = CropMath.clamp(CropRect(0.5f, 0.5f, 0.5f, 0.5f))
        assertTrue(crop.width >= CropMath.MIN_SIZE - tolerance)
        assertTrue(crop.height >= CropMath.MIN_SIZE - tolerance)
        assertTrue(crop.right <= 1f)
        assertTrue(crop.bottom <= 1f)
    }

    @Test
    fun `withRatio reshapes about the centre and stays in bounds`() {
        val source = 4000f / 3000f
        val crop = CropMath.withRatio(CropRect(0.1f, 0.1f, 0.9f, 0.9f), source, targetRatio = 1f)
        assertEquals(0.5f, (crop.left + crop.right) / 2f, tolerance)
        assertEquals(0.5f, (crop.top + crop.bottom) / 2f, tolerance)
        assertTrue(crop.left >= -tolerance && crop.right <= 1f + tolerance)
        assertTrue(crop.top >= -tolerance && crop.bottom <= 1f + tolerance)
    }

    @Test
    fun `withRatio slides a rect back inside when its centre is near an edge`() {
        val crop = CropMath.withRatio(CropRect(0.8f, 0.8f, 1f, 1f), sourceRatio = 1f, targetRatio = 1f)
        assertTrue("left=${crop.left}", crop.left >= -tolerance)
        assertTrue("right=${crop.right}", crop.right <= 1f + tolerance)
        assertTrue("bottom=${crop.bottom}", crop.bottom <= 1f + tolerance)
    }

    @Test
    fun `a full crop maps to the whole bitmap`() {
        val rect = CropMath.pixelRect(CropRect(), 4000, 3000)
        assertEquals(PixelRect(0, 0, 4000, 3000), rect)
    }

    @Test
    fun `pixel rect never escapes the bitmap`() {
        val rect = CropMath.pixelRect(CropRect(-1f, -1f, 2f, 2f), 100, 80)
        assertTrue(rect.x >= 0 && rect.y >= 0)
        assertTrue(rect.x + rect.width <= 100)
        assertTrue(rect.y + rect.height <= 80)
    }

    @Test
    fun `pixel rect is never empty even for a degenerate crop`() {
        val rect = CropMath.pixelRect(CropRect(0.5f, 0.5f, 0.5f, 0.5f), 100, 80)
        assertTrue(rect.width >= 1)
        assertTrue(rect.height >= 1)
    }

    @Test
    fun `a fresh geometry is the identity`() {
        assertTrue(ImageGeometry().isIdentity)
        assertTrue(!ImageGeometry(quarterTurns = 1).isIdentity)
        assertTrue(!ImageGeometry(crop = CropRect(0.1f, 0f, 1f, 1f)).isIdentity)
        assertTrue(!ImageGeometry(flipHorizontal = true).isIdentity)
    }
}
