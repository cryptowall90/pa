package com.pictureperfectx.app.capture

/**
 * The adjustments one layer can make, each -100..100 with 0 neutral.
 *
 * The four tonal bands target overlapping zones of the luminance range rather than the whole image,
 * which is what separates them from plain brightness: [blacks] and [whites] move the extreme ends,
 * [shadows] and [highlights] the darker and brighter midtones. [exposure], [contrast], [saturation],
 * [vibrance] and [warmth] act on everything.
 *
 * They all live in one object because they are applied in one GPU pass. Compositing already costs a
 * full-image pass per layer on the CPU, so a layer that chained nine filters would multiply the
 * expensive part of rendering by nine.
 */
data class ToneAdjustments(
    val exposure: Int = 0,
    val contrast: Int = 0,
    val blacks: Int = 0,
    val shadows: Int = 0,
    val highlights: Int = 0,
    val whites: Int = 0,
    val saturation: Int = 0,
    val vibrance: Int = 0,
    val warmth: Int = 0,
) {
    val isNeutral: Boolean get() = ToneBand.entries.all { valueOf(it) == 0 }

    /** Shader-space value for a slider, clamped to -1..1. */
    fun normalized(band: ToneBand): Float = normalize(valueOf(band))

    fun valueOf(band: ToneBand): Int = when (band) {
        ToneBand.Exposure -> exposure
        ToneBand.Contrast -> contrast
        ToneBand.Blacks -> blacks
        ToneBand.Shadows -> shadows
        ToneBand.Highlights -> highlights
        ToneBand.Whites -> whites
        ToneBand.Saturation -> saturation
        ToneBand.Vibrance -> vibrance
        ToneBand.Warmth -> warmth
    }

    fun with(band: ToneBand, value: Int): ToneAdjustments = when (band) {
        ToneBand.Exposure -> copy(exposure = value)
        ToneBand.Contrast -> copy(contrast = value)
        ToneBand.Blacks -> copy(blacks = value)
        ToneBand.Shadows -> copy(shadows = value)
        ToneBand.Highlights -> copy(highlights = value)
        ToneBand.Whites -> copy(whites = value)
        ToneBand.Saturation -> copy(saturation = value)
        ToneBand.Vibrance -> copy(vibrance = value)
        ToneBand.Warmth -> copy(warmth = value)
    }

    companion object {
        /** Sliders are -100..100 for the user; the shader wants -1..1. */
        fun normalize(percent: Int): Float = percent.coerceIn(-100, 100) / 100f
    }
}

/**
 * Everything one adjustment layer can move, in the order a photograph is usually worked.
 *
 * Exposure and contrast set the overall frame, the four bands shape it dark to light, and colour
 * comes last — which is also the order the shader applies them in, so the controls behave the way
 * the list reads.
 */
enum class ToneBand(val label: String) {
    Exposure("Exposure"),
    Contrast("Contrast"),
    Blacks("Blacks"),
    Shadows("Shadows"),
    Highlights("Highlights"),
    Whites("Whites"),
    Saturation("Saturation"),
    Vibrance("Vibrance"),
    Warmth("Warmth"),
}
