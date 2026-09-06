package com.pictureperfectx.app.layers

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/**
 * One tap of the heal brush: the spot to cover and the clean skin it borrows from.
 *
 * The source is resolved **once**, when the dab is placed, and stored — not searched for again at
 * render time. Searching twice would mean the preview and the export could pick different patches
 * and produce visibly different photos, which is the whole thing the layer model exists to prevent.
 */
@Serializable
data class HealDab(
    val centre: MaskPoint,
    val source: MaskPoint,
    /** Radius as a fraction of the image's shorter edge. */
    val radius: Float,
)

/**
 * Blemish removal: copy nearby skin over a spot and make the join disappear.
 *
 * Pure Kotlin over an `IntArray` of pixels, so CI can check it — the alternative is judging by eye
 * on a phone whether a patch landed, matched and feathered, which are three separate ways for this
 * to be subtly wrong.
 *
 * Deliberately not a shader. It touches a disc a few dozen pixels across, a handful of times per
 * photo; a GPU round trip per dab would cost more than the work.
 */
object Heal {

    /** Candidate directions to look for clean skin, and how far out to look, in radii. */
    private const val CANDIDATES = 8
    private const val SOURCE_DISTANCE = 2.2f

    /**
     * Picks the flattest patch of skin near ([x], [y]) to borrow from.
     *
     * Flattest, because variance is a good proxy for "nothing going on here": a candidate straddling
     * an eyebrow or the edge of a nostril scores badly and loses to plain cheek. Candidates that
     * fall outside the image are skipped rather than clamped, since a clamped one would overlap the
     * blemish and heal it with itself.
     */
    fun chooseSource(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        radius: Int,
    ): Pair<Int, Int>? {
        if (radius <= 0 || width <= 0 || height <= 0) return null
        var best: Pair<Int, Int>? = null
        var bestVariance = Float.MAX_VALUE
        val distance = radius * SOURCE_DISTANCE

        for (step in 0 until CANDIDATES) {
            val angle = step * 2.0 * PI / CANDIDATES
            val candidateX = (x + cos(angle) * distance).roundToInt()
            val candidateY = (y + sin(angle) * distance).roundToInt()
            if (candidateX - radius < 0 || candidateX + radius >= width) continue
            if (candidateY - radius < 0 || candidateY + radius >= height) continue

            val variance = variance(pixels, width, candidateX, candidateY, radius)
            if (variance < bestVariance) {
                bestVariance = variance
                best = candidateX to candidateY
            }
        }
        return best
    }

    /**
     * Copies the patch at ([sourceX], [sourceY]) over the one at ([x], [y]).
     *
     * Two things make it a heal rather than a paste. The patch is **shifted to match** the colour
     * already around the spot, so skin borrowed from a slightly lighter area doesn't land as a
     * blotch; and it fades out towards the rim, so there is no visible seam. The shift is measured
     * on the ring around each disc rather than the whole of it — the middle of the destination is
     * the blemish, and averaging that in would drag the correction towards the thing being removed.
     */
    fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        sourceX: Int,
        sourceY: Int,
        radius: Int,
    ) {
        if (radius <= 0 || width <= 0 || height <= 0) return
        val destinationRing = ringMean(pixels, width, height, x, y, radius) ?: return
        val sourceRing = ringMean(pixels, width, height, sourceX, sourceY, radius) ?: return
        val shiftRed = destinationRing[0] - sourceRing[0]
        val shiftGreen = destinationRing[1] - sourceRing[1]
        val shiftBlue = destinationRing[2] - sourceRing[2]

        // Read the source before writing, so a patch overlapping its own destination can't heal
        // from pixels this very dab has already changed.
        val diameter = radius * 2 + 1
        val patch = IntArray(diameter * diameter)
        for (row in -radius..radius) {
            for (column in -radius..radius) {
                val sx = (sourceX + column).coerceIn(0, width - 1)
                val sy = (sourceY + row).coerceIn(0, height - 1)
                patch[(row + radius) * diameter + column + radius] = pixels[sy * width + sx]
            }
        }

        for (row in -radius..radius) {
            val targetY = y + row
            if (targetY !in 0 until height) continue
            for (column in -radius..radius) {
                val targetX = x + column
                if (targetX !in 0 until width) continue

                val distance = sqrt((column * column + row * row).toFloat()) / radius
                if (distance > 1f) continue
                // Solid through the middle, fading over the outer third: enough of a ramp to hide
                // the join without softening the middle of the repair.
                val strength = ((1f - distance) / FEATHER).coerceIn(0f, 1f)

                val patched = patch[(row + radius) * diameter + column + radius]
                val index = targetY * width + targetX
                val existing = pixels[index]
                pixels[index] = mix(
                    existing,
                    shift(patched, shiftRed, shiftGreen, shiftBlue),
                    strength,
                )
            }
        }
    }

    /** Mean red, green and blue around the rim of a disc. */
    private fun ringMean(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        radius: Int,
    ): FloatArray? {
        var red = 0f
        var green = 0f
        var blue = 0f
        var samples = 0
        for (step in 0 until RING_SAMPLES) {
            val angle = step * 2.0 * PI / RING_SAMPLES
            val sampleX = (x + cos(angle) * radius).roundToInt()
            val sampleY = (y + sin(angle) * radius).roundToInt()
            if (sampleX !in 0 until width || sampleY !in 0 until height) continue
            val pixel = pixels[sampleY * width + sampleX]
            red += (pixel shr 16) and 0xFF
            green += (pixel shr 8) and 0xFF
            blue += pixel and 0xFF
            samples++
        }
        if (samples == 0) return null
        return floatArrayOf(red / samples, green / samples, blue / samples)
    }

    private fun variance(pixels: IntArray, width: Int, x: Int, y: Int, radius: Int): Float {
        var total = 0f
        var totalSquared = 0f
        var samples = 0
        for (row in -radius..radius step 2) {
            for (column in -radius..radius step 2) {
                val pixel = pixels[(y + row) * width + x + column]
                val luma = (
                    ((pixel shr 16) and 0xFF) * 299 +
                        ((pixel shr 8) and 0xFF) * 587 +
                        (pixel and 0xFF) * 114
                    ) / 1000f
                total += luma
                totalSquared += luma * luma
                samples++
            }
        }
        if (samples == 0) return Float.MAX_VALUE
        val mean = total / samples
        return totalSquared / samples - mean * mean
    }

    private fun shift(pixel: Int, red: Float, green: Float, blue: Float): Int {
        val r = (((pixel shr 16) and 0xFF) + red).roundToInt().coerceIn(0, 255)
        val g = (((pixel shr 8) and 0xFF) + green).roundToInt().coerceIn(0, 255)
        val b = ((pixel and 0xFF) + blue).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun mix(from: Int, to: Int, amount: Float): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * amount).roundToInt().coerceIn(0, 255) shl shift
        }
        return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
    }

    private const val RING_SAMPLES = 24

    /** The outer fraction of the disc the repair fades across. */
    private const val FEATHER = 0.35f
}
