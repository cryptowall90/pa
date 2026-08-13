package com.pictureperfectx.app.layers

import com.pictureperfectx.app.capture.ToneAdjustments
import kotlin.math.roundToInt

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
    /**
     * The lasso that produced this coverage, kept only while it still describes the whole area.
     *
     * It survives a lasso drawn fresh and nothing else: brushing, adding and subtracting all leave
     * a shape the polygon no longer matches, and handles on a shape they don't describe would be
     * worse than no handles. When it's present the outline is drawn from it directly and its points
     * can be dragged.
     */
    val path: List<MaskPoint>? = null,
    /**
     * The gradient that produced this coverage, kept on the same terms as [path] and never
     * alongside it — a mask is described by a polygon, or by a gradient, or by neither once it has
     * been brushed or combined into something no single shape accounts for.
     */
    val gradient: GradientSpec? = null,
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
        val radius = featherRadius()
        if (radius <= 0) return oriented

        var source = oriented
        var target = FloatArray(source.size)
        repeat(PASSES) {
            blurAxis(source, target, radius, horizontal = true)
            blurAxis(target, source, radius, horizontal = false)
        }
        return source
    }

    /**
     * The blur radius in cells, as a share of the grid's long axis.
     *
     * Quoting it in cells would make the same [feather] mean three different things on the brush's
     * 64-cell grid and a selection's 192-cell one — the finer the grid, the less a fixed number of
     * cells covers. As a fraction it means one thing: a share of the picture.
     */
    private fun featherRadius(): Int {
        val amount = feather.coerceIn(0f, 1f)
        if (amount <= 0f) return 0
        val span = maxOf(columns, rows) * amount * FEATHER_FRACTION
        // Any feather at all should visibly soften, even on a grid too coarse to round up to one.
        return maxOf(1, span.roundToInt())
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
            inverted == other.inverted && path == other.path && gradient == other.gradient &&
            coverage.contentEquals(other.coverage)
    }

    override fun hashCode(): Int {
        var result = columns
        result = 31 * result + rows
        result = 31 * result + feather.hashCode()
        result = 31 * result + inverted.hashCode()
        result = 31 * result + path.hashCode()
        result = 31 * result + gradient.hashCode()
        result = 31 * result + coverage.contentHashCode()
        return result
    }

    companion object {
        const val DEFAULT_RESOLUTION = 64

        /**
         * Selections get a finer grid than the brush. 64 cells is plenty for a soft dab and far too
         * coarse for a drawn outline, where the staircase would be the first thing you notice.
         */
        const val SELECTION_RESOLUTION = 256

        /** A drawn edge should read as deliberate, so selections feather less than a brushed mask. */
        const val SELECTION_FEATHER = 0.12f

        /** Blur radius at full feather, as a share of the grid's long axis. */
        private const val FEATHER_FRACTION = 0.08f
        private const val PASSES = 2

        fun full(columns: Int = DEFAULT_RESOLUTION, rows: Int = DEFAULT_RESOLUTION): Mask =
            Mask(columns, rows, FloatArray(columns * rows) { 1f })

        /** An empty coverage grid, ready to be painted into. */
        fun blank(columns: Int = DEFAULT_RESOLUTION, rows: Int = DEFAULT_RESOLUTION): Mask =
            Mask(columns, rows, FloatArray(columns * rows))

        /**
         * A blank selection grid whose cells are square on an image of the given width/height
         * [ratio]. A square grid over a 4:3 photo would resolve one axis more finely than the
         * other, which shows up as a lasso edge that's crisper vertically than horizontally.
         */
        fun forRatio(
            ratio: Float,
            resolution: Int = SELECTION_RESOLUTION,
            /** Start fully covered, for trimming an effect that currently applies everywhere. */
            covered: Boolean = false,
        ): Mask {
            val safe = if (ratio.isFinite() && ratio > 0f) ratio else 1f
            val longest = resolution.coerceAtLeast(1)
            val columns = if (safe >= 1f) longest else (longest * safe).roundToInt().coerceAtLeast(1)
            val rows = if (safe >= 1f) (longest / safe).roundToInt().coerceAtLeast(1) else longest
            val fill = if (covered) 1f else 0f
            return Mask(
                columns = columns,
                rows = rows,
                coverage = FloatArray(columns * rows) { fill },
                feather = SELECTION_FEATHER,
            )
        }
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

    /**
     * A tone curve per channel.
     *
     * The colour channels are what make this colour grading rather than only contrast: lifting red
     * in the shadows warms them, pulling blue down in the highlights cools them. Separate tint
     * sliders would be a second mechanism arguing with this one over the same pixels.
     */
    data class Curve(
        override val id: Long,
        override val name: String = "Curve",
        override val isVisible: Boolean = true,
        override val opacity: Float = 1f,
        override val blend: BlendMode = BlendMode.Normal,
        override val mask: Mask = Mask(),
        val spec: CurveSpec = CurveSpec(),
    ) : Layer

    /**
     * A wash of colour running from [from] to [to] across the frame.
     *
     * The gradient it is drawn from is the same [GradientSpec] a mask uses, rendered through the
     * same coverage function — so a colour gradient and a masked one placed identically line up
     * exactly, rather than nearly.
     */
    data class Gradient(
        override val id: Long,
        override val name: String = "Gradient",
        override val isVisible: Boolean = true,
        override val opacity: Float = 1f,
        override val blend: BlendMode = BlendMode.Normal,
        override val mask: Mask = Mask(),
        val spec: GradientSpec = GradientSpec(),
        val from: GradientColour = GradientColour(hue = 20f),
        val to: GradientColour = GradientColour(tone = ColourTone.Clear),
    ) : Layer
}

/**
 * One end of a colour gradient.
 *
 * A hue with a lightness shortcut rather than an arbitrary colour: it needs no picker widget, which
 * nothing else in the app has, and drives the same one-slider-and-chips pattern as every other
 * control. [alpha] of 0 is what makes a gradient fade into the photo rather than over it.
 */
data class GradientColour(
    /** 0..360 around the wheel. Ignored unless [tone] is [ColourTone.Hue]. */
    val hue: Float = 20f,
    val tone: ColourTone = ColourTone.Hue,
)

/**
 * The shortcuts worth having without a colour picker.
 *
 * Black and white are most of what a gradient wash is actually used for, and [Clear] is what lets a
 * gradient fade *into* the photo rather than sitting over all of it.
 */
enum class ColourTone(val label: String) {
    Hue("Colour"),
    Black("Black"),
    White("White"),
    Clear("Clear"),
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
    is Layer.Gradient -> copy(name = name, isVisible = isVisible, opacity = opacity, blend = blend, mask = mask)
    is Layer.Curve -> copy(name = name, isVisible = isVisible, opacity = opacity, blend = blend, mask = mask)
}

/**
 * The colour this end of a gradient contributes, as packed ARGB.
 *
 * A saturated hue at mid lightness reads as a wash rather than a stain, which is what a gradient
 * over a photo is for; black and white skip the wheel entirely.
 */
fun GradientColour.toArgb(): Int = when (tone) {
    // Clear keeps the colour it fades from, so the ramp loses opacity without drifting through grey.
    ColourTone.Clear -> hueToRgb(hue)
    ColourTone.Black -> 0xFF000000.toInt()
    ColourTone.White -> 0xFFFFFFFF.toInt()
    ColourTone.Hue -> 0xFF000000.toInt() or hueToRgb(hue)
}

/** A fully saturated colour at the given angle round the wheel, 0..360. */
private fun hueToRgb(hue: Float): Int {
    val h = ((hue % 360f) + 360f) % 360f / 60f
    val x = 1f - kotlin.math.abs(h % 2f - 1f)
    val (r, g, b) = when (h.toInt()) {
        0 -> Triple(1f, x, 0f)
        1 -> Triple(x, 1f, 0f)
        2 -> Triple(0f, 1f, x)
        3 -> Triple(0f, x, 1f)
        4 -> Triple(x, 0f, 1f)
        else -> Triple(1f, 0f, x)
    }
    fun channel(value: Float) = (value * 255).roundToInt().coerceIn(0, 255)
    return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}
