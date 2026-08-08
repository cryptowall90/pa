package com.pictureperfectx.app.layers

import com.pictureperfectx.app.capture.ToneAdjustments

/**
 * How a layer's pixels combine with everything beneath it. The set is deliberately limited to modes
 * `PorterDuff` supports on every API level the app runs on, so a document renders identically on a
 * 2016 phone and a current one.
 */
enum class BlendMode(val label: String) {
    Normal("Normal"),
    Multiply("Multiply"),
    Screen("Screen"),
    Overlay("Overlay"),
    Darken("Darken"),
    Lighten("Lighten"),
}

/**
 * A layer's mask, as coverage values in a coarse grid rather than a bitmap.
 *
 * Keeping it resolution-independent is the same trick the crop rect uses: the preview composites at
 * screen size and the export at full size, and both mean the same thing. It also keeps a document
 * cheap to snapshot for undo — a full-resolution mask bitmap per history step would not be.
 */
data class Mask(
    val columns: Int = DEFAULT_RESOLUTION,
    val rows: Int = DEFAULT_RESOLUTION,
    /** Row-major coverage, 0 = hidden, 1 = fully painted. Empty means "no mask, show everything". */
    val coverage: FloatArray = FloatArray(0),
    /** Softness of the mask edge, 0..1. */
    val feather: Float = 0.35f,
    val inverted: Boolean = false,
) {
    val isEmpty: Boolean get() = coverage.isEmpty()

    fun coverageAt(column: Int, row: Int): Float {
        if (isEmpty) return 1f
        if (column !in 0 until columns || row !in 0 until rows) return 0f
        val raw = coverage[row * columns + column]
        return if (inverted) 1f - raw else raw
    }

    /**
     * Coverage with its edges softened, so a painted mask blends instead of showing a hard rim.
     *
     * A separable box blur run [feather]-proportional times: cheap, and on a grid this small the
     * repeated passes approximate a gaussian closely enough that no edge is visible once the grid is
     * scaled up over the photo.
     */
    fun softened(): FloatArray {
        if (isEmpty) return coverage
        val oriented = FloatArray(coverage.size) { index ->
            val value = coverage[index]
            if (inverted) 1f - value else value
        }
        val radius = (feather.coerceIn(0f, 1f) * MAX_FEATHER_RADIUS).toInt()
        if (radius <= 0) return oriented

        var source = oriented
        var target = FloatArray(source.size)
        repeat(PASSES) {
            blurAxis(source, target, radius, horizontal = true)
            blurAxis(target, source, radius, horizontal = false)
        }
        return source
    }

    private fun blurAxis(source: FloatArray, target: FloatArray, radius: Int, horizontal: Boolean) {
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                var total = 0f
                var samples = 0
                for (offset in -radius..radius) {
                    val sampleColumn = if (horizontal) column + offset else column
                    val sampleRow = if (horizontal) row else row + offset
                    if (sampleColumn in 0 until columns && sampleRow in 0 until rows) {
                        total += source[sampleRow * columns + sampleColumn]
                        samples++
                    }
                }
                target[row * columns + column] = if (samples > 0) total / samples else 0f
            }
        }
    }

    // FloatArray gives this data class identity semantics for equals/hashCode, which would break
    // undo comparisons and recomposition; compare the contents instead.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Mask) return false
        return columns == other.columns && rows == other.rows && feather == other.feather &&
            inverted == other.inverted && coverage.contentEquals(other.coverage)
    }

    override fun hashCode(): Int {
        var result = columns
        result = 31 * result + rows
        result = 31 * result + feather.hashCode()
        result = 31 * result + inverted.hashCode()
        result = 31 * result + coverage.contentHashCode()
        return result
    }

    companion object {
        const val DEFAULT_RESOLUTION = 64
        private const val MAX_FEATHER_RADIUS = 6
        private const val PASSES = 2

        fun full(columns: Int = DEFAULT_RESOLUTION, rows: Int = DEFAULT_RESOLUTION): Mask =
            Mask(columns, rows, FloatArray(columns * rows) { 1f })

        /** An empty coverage grid, ready to be painted into. */
        fun blank(columns: Int = DEFAULT_RESOLUTION, rows: Int = DEFAULT_RESOLUTION): Mask =
            Mask(columns, rows, FloatArray(columns * rows))
    }
}

/**
 * One entry in the stack. A layer is a *description* of an edit, never pixels the user has already
 * paid for — that is what makes the stack non-destructive: reordering, hiding or deleting a layer
 * re-renders from the original every time.
 */
sealed interface Layer {
    val id: Long
    val name: String
    val isVisible: Boolean
    val opacity: Float
    val blend: BlendMode
    val mask: Mask

    /** An adjustment applied through this layer's mask and blend mode. */
    data class Tone(
        override val id: Long,
        override val name: String = "Tone",
        override val isVisible: Boolean = true,
        override val opacity: Float = 1f,
        override val blend: BlendMode = BlendMode.Normal,
        override val mask: Mask = Mask(),
        val adjustments: ToneAdjustments = ToneAdjustments(),
    ) : Layer

    /** A named look from the filter catalog, applied at [intensity] through the mask. */
    data class Look(
        override val id: Long,
        override val name: String = "Look",
        override val isVisible: Boolean = true,
        override val opacity: Float = 1f,
        override val blend: BlendMode = BlendMode.Normal,
        override val mask: Mask = Mask(),
        val filterId: String,
        val intensity: Int = 100,
    ) : Layer

    /**
     * A defocus blur confined to the mask.
     *
     * Dormant: nothing in the UI creates one while bokeh is shelved. It stays because it renders
     * correctly and is the re-entry point — a blur confined to a hand-drawn selection is bokeh
     * with no model involved at all.
     */
    data class Blur(
        override val id: Long,
        override val name: String = "Blur",
        override val isVisible: Boolean = true,
        override val opacity: Float = 1f,
        override val blend: BlendMode = BlendMode.Normal,
        override val mask: Mask = Mask(),
        val radius: Int = 25,
    ) : Layer
}

/** Copies a layer with new common properties, preserving its specific type and payload. */
fun Layer.withCommon(
    name: String = this.name,
    isVisible: Boolean = this.isVisible,
    opacity: Float = this.opacity,
    blend: BlendMode = this.blend,
    mask: Mask = this.mask,
): Layer = when (this) {
    is Layer.Tone -> copy(name = name, isVisible = isVisible, opacity = opacity, blend = blend, mask = mask)
    is Layer.Look -> copy(name = name, isVisible = isVisible, opacity = opacity, blend = blend, mask = mask)
    is Layer.Blur -> copy(name = name, isVisible = isVisible, opacity = opacity, blend = blend, mask = mask)
}
