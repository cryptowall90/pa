package com.pictureperfectx.app.capture

/**
 * Tonal-zone adjustments, each -100..100 with 0 neutral.
 *
 * The four target overlapping bands of the luminance range rather than the whole image, which is
 * what separates them from plain brightness: [blacks] and [whites] move the extreme ends,
 * [shadows] and [highlights] the darker and brighter midtones.
 */
data class ToneAdjustments(
    val blacks: Int = 0,
    val whites: Int = 0,
    val highlights: Int = 0,
    val shadows: Int = 0,
) {
    val isNeutral: Boolean get() = blacks == 0 && whites == 0 && highlights == 0 && shadows == 0

    /** Shader-space value for a slider, clamped to -1..1. */
    fun normalized(band: ToneBand): Float = normalize(valueOf(band))

    fun valueOf(band: ToneBand): Int = when (band) {
        ToneBand.Blacks -> blacks
        ToneBand.Shadows -> shadows
        ToneBand.Highlights -> highlights
        ToneBand.Whites -> whites
    }

    fun with(band: ToneBand, value: Int): ToneAdjustments = when (band) {
        ToneBand.Blacks -> copy(blacks = value)
        ToneBand.Shadows -> copy(shadows = value)
        ToneBand.Highlights -> copy(highlights = value)
        ToneBand.Whites -> copy(whites = value)
    }

    companion object {
        /** Sliders are -100..100 for the user; the shader wants -1..1. */
        fun normalize(percent: Int): Float = percent.coerceIn(-100, 100) / 100f
    }
}

/** The four tonal bands, ordered dark to light so the controls read like a histogram. */
enum class ToneBand(val label: String) {
    Blacks("Blacks"),
    Shadows("Shadows"),
    Highlights("Highlights"),
    Whites("Whites"),
}
