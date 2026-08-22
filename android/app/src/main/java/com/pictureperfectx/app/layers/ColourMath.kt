package com.pictureperfectx.app.layers

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A colour with every axis back inside the range it means something in.
 *
 * [hsvToRgb] copes with anything, but what is *stored* is also what the readout reports and where
 * the wheel puts its thumb — so a drag that overshoots by a rounding error should not be able to
 * leave a layer claiming a hue of 361 degrees.
 */
fun GradientColour.sane(): GradientColour = copy(
    hue = ((hue % 360f) + 360f) % 360f,
    saturation = saturation.coerceIn(0f, 1f),
    value = value.coerceIn(0f, 1f),
)

/**
 * A colour from an angle round the wheel, a distance out from its middle, and a brightness.
 *
 * The editor used to offer a hue and nothing else — every colour it could make was fully saturated
 * and fully bright, which is a rainbow rather than a palette. There was no pink, no navy, no
 * charcoal, no way to pick anything you would actually put on a photograph.
 *
 * HSV rather than HSL because it is what a wheel *is*: the angle is the hue, the distance from the
 * middle is the saturation, and the third axis is a slider. A picker is then a direct drawing of the
 * model rather than a translation of it.
 *
 * Pure Kotlin with no Android types, like the rest of this package, so the conversion is checked by
 * CI rather than by eye.
 *
 * @param hue degrees round the wheel, wrapped, so 0 and 360 are the same red.
 * @param saturation 0 for grey, 1 for the pure hue.
 * @param value 0 for black, 1 for as bright as the hue goes.
 * @return packed 0xRRGGBB, with no alpha bits set.
 */
fun hsvToRgb(hue: Float, saturation: Float = 1f, value: Float = 1f): Int {
    val sector = (((hue % 360f) + 360f) % 360f) / 60f
    val chroma = value.coerceIn(0f, 1f) * saturation.coerceIn(0f, 1f)
    // How far this sector is from its nearest primary — the ramp between two of them.
    val ramp = chroma * (1f - abs(sector % 2f - 1f))
    val (red, green, blue) = when (sector.toInt()) {
        0 -> Triple(chroma, ramp, 0f)
        1 -> Triple(ramp, chroma, 0f)
        2 -> Triple(0f, chroma, ramp)
        3 -> Triple(0f, ramp, chroma)
        4 -> Triple(ramp, 0f, chroma)
        else -> Triple(chroma, 0f, ramp)
    }
    // Lifting all three by the same amount is what turns a pure hue into a paler or darker one
    // without shifting which colour it is.
    val lift = value.coerceIn(0f, 1f) - chroma
    fun channel(component: Float) = ((component + lift) * 255f).roundToInt().coerceIn(0, 255)
    return (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
}
