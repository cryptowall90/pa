package com.pictureperfectx.app.layers

import kotlin.math.roundToInt

/**
 * How a layer's result lands on what is already there.
 *
 * Arithmetic in Kotlin rather than `PorterDuffXfermode`, because those modes get the alpha wrong for
 * what a layer means here. `PorterDuff.MULTIPLY` computes the result's alpha as `Sa × Da`, so
 * anywhere the effect is transparent — outside its mask, or the fading-out end of a gradient — it
 * punched a **hole in the photo** instead of leaving it alone. A gradient on Multiply erased
 * everything it was supposed to be washing over.
 *
 * Here alpha means one thing only: *how much of this layer applies*. At zero the destination comes
 * back untouched, whatever the mode, which is the property the whole layer model rests on.
 *
 * Pure `IntArray` in, `IntArray` out, so CI can check every mode — a blend that is subtly wrong
 * looks like a slightly off photo, which is not something to be judging by eye on a phone.
 */
object LayerBlend {

    /**
     * Draws [source] onto [destination] in place, [opacity] of the way.
     *
     * Both are packed ARGB, un-premultiplied — what `Bitmap.getPixels` hands back. The destination
     * keeps its own alpha: it is the photo, and a layer changes its colour, never its presence.
     */
    fun composite(
        destination: IntArray,
        source: IntArray,
        mode: BlendMode,
        opacity: Float = 1f,
        count: Int = minOf(destination.size, source.size),
    ) {
        val amount = opacity.coerceIn(0f, 1f)
        if (amount <= 0f) return
        val limit = minOf(count, destination.size, source.size)
        for (index in 0 until limit) {
            destination[index] = pixel(destination[index], source[index], mode, amount)
        }
    }

    /** One pixel of [source] over one of [destination]. */
    fun pixel(destination: Int, source: Int, mode: BlendMode, opacity: Float): Int {
        val alpha = ((source ushr 24) and 0xFF) * opacity.coerceIn(0f, 1f) / 255f
        // The common cases by far: a masked-out pixel, and a fully applied one.
        if (alpha <= 0f) return destination

        fun channel(shift: Int): Int {
            val s = (source shr shift) and 0xFF
            val d = (destination shr shift) and 0xFF
            val blended = blend(mode, s, d)
            return (d + (blended - d) * alpha).roundToInt().coerceIn(0, 255) shl shift
        }
        return (destination and ALPHA_MASK) or channel(16) or channel(8) or channel(0)
    }

    /**
     * One channel of [source] against one of [destination], both 0..255, before opacity.
     *
     * The five modes are the ones with a `PorterDuff` equivalent, so nothing that already renders
     * changes shape — only the alpha handling around them does.
     */
    fun blend(mode: BlendMode, source: Int, destination: Int): Int = when (mode) {
        BlendMode.Normal -> source
        BlendMode.Multiply -> source * destination / 255
        BlendMode.Screen -> source + destination - source * destination / 255
        // Screen where the photo is light, multiply where it is dark — the pivot is the photo's
        // value, not the layer's, which is what makes it a contrast move rather than a tint.
        BlendMode.Overlay -> if (destination < 128) {
            2 * source * destination / 255
        } else {
            255 - 2 * (255 - source) * (255 - destination) / 255
        }
        BlendMode.Darken -> minOf(source, destination)
        BlendMode.Lighten -> maxOf(source, destination)
    }

    private const val ALPHA_MASK = 0xFF shl 24
}
