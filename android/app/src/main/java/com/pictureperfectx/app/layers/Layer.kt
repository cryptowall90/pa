package com.pictureperfectx.app.layers

import com.pictureperfectx.app.capture.ToneAdjustments
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a layer's pixels combine with everything beneath it. The set is deliberately limited to modes
 * `PorterDuff` supports on every API level the app runs on, so a document renders identically on a
 * 2016 phone and a current one.
 */
@Serializable
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
@Serializable
data class Mask(
    val columns: Int = DEFAULT_RESOLUTION,
    val rows: Int = DEFAULT_RESOLUTION,
    /** Row-major coverage, 0 = hidden, 1 = fully painted. Empty means "no mask, show everything". */
    @Serializable(with = CoverageSerializer::class)
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

    /**
     * The normalized box around everything this mask covers, or null when it covers nothing.
     *
     * What it is for: placing something *inside* an area that was already drawn. A gradient added
     * after a lasso should run across the selection, not across the whole frame with most of its
     * ramp clipped away outside.
     *
     * An empty mask is null rather than the whole frame. It renders as "applies everywhere", but
     * here the question is where the user pointed, and an empty mask is them not having pointed.
     */
    fun coveredBounds(threshold: Float = COVERED_THRESHOLD): MaskBounds? {
        if (isEmpty) return null
        var left = columns
        var top = rows
        var right = -1
        var bottom = -1
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if (coverageAt(column, row) <= threshold) continue
                if (column < left) left = column
                if (column > right) right = column
                if (row < top) top = row
                if (row > bottom) bottom = row
            }
        }
        if (right < left || bottom < top) return null
        // Cell edges rather than centres, so the box contains the covered cells whole.
        return MaskBounds(
            left = left.toFloat() / columns,
            top = top.toFloat() / rows,
            right = (right + 1).toFloat() / columns,
            bottom = (bottom + 1).toFloat() / rows,
        )
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

        /** Below this a cell is the soft edge of an area rather than part of it. */
        private const val COVERED_THRESHOLD = 0.02f

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

/** The normalized box around a covered area, in the same 0..1 space masks and points use. */
data class MaskBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centreX: Float get() = (left + right) / 2f
    val centreY: Float get() = (top + bottom) / 2f
}

/**
 * One entry in the stack. A layer is a *description* of an edit, never pixels the user has already
 * paid for — that is what makes the stack non-destructive: reordering, hiding or deleting a layer
 * re-renders from the original every time.
 */
@Serializable
sealed interface Layer {
    val id: Long
    val name: String
    val isVisible: Boolean
    val opacity: Float
    val blend: BlendMode
    val mask: Mask

    /**
     * Exposure, contrast and colour, applied to whatever this layer produces.
     *
     * Common to every layer rather than owned by one kind of them, because the thing a person
     * actually has is an *area*: lasso the sky, put a filter in it, and wanting that same area
     * brighter is the next thing you want. It used to mean a second layer given the same area by
     * hand — [Document.duplicate] exists for exactly that problem and cannot solve it, since it
     * copies the effect along with the mask and a layer's kind can never change.
     *
     * Free when untouched. [ToneAdjustments.isNeutral] and `ImageToner`'s early return mean a
     * layer nobody has adjusted costs no GPU pass and allocates no bitmap.
     */
    val adjustments: ToneAdjustments

    /** Nothing but [adjustments], applied through this layer's mask and blend mode. */
    @Serializable
    @SerialName("tone")
    data class Tone(
        override val id: Long,
        override val name: String = "Tone",
        override val isVisible: Boolean = true,
        override val opacity: Float = 1f,
        override val blend: BlendMode = BlendMode.Normal,
        override val mask: Mask = Mask(),
        override val adjustments: ToneAdjustments = ToneAdjustments(),
    ) : Layer

    /** A named look from the filter catalog, applied at [intensity] through the mask. */
    @Serializable
    @SerialName("look")
    data class Look(
        override val id: Long,
        override val name: String = "Look",
        override val isVisible: Boolean = true,
        override val opacity: Float = 1f,
        override val blend: BlendMode = BlendMode.Normal,
        override val mask: Mask = Mask(),
        val filterId: String,
        val intensity: Int = 100,
        /** Exposure, contrast and colour, on top of what this layer does. Free when untouched. */
        override val adjustments: ToneAdjustments = ToneAdjustments(),
    ) : Layer

    /**
     * A defocus blur confined to the mask.
     *
     * Dormant: nothing in the UI creates one while bokeh is shelved. It stays because it renders
     * correctly and is the re-entry point — a blur confined to a hand-drawn selection is bokeh
     * with no model involved at all.
     */
    @Serializable
    @SerialName("blur")
    data class Blur(
        override val id: Long,
        override val name: String = "Blur",
        override val isVisible: Boolean = true,
        override val opacity: Float = 1f,
        override val blend: BlendMode = BlendMode.Normal,
        override val mask: Mask = Mask(),
        val radius: Int = 25,
        /** Exposure, contrast and colour, on top of what this layer does. Free when untouched. */
        override val adjustments: ToneAdjustments = ToneAdjustments(),
    ) : Layer

    /**
     * Words on the photo.
     *
     * The first layer that carries *content* rather than an effect applied through a mask — and the
     * renderer needs no change for it, because masking passes an effect straight through when there
     * is no mask and the composite draws with alpha. Glyphs on a transparent bitmap were always
     * going to composite correctly.
     */
    @Serializable
    @SerialName("text")
    data class Text(
        override val id: Long,
        override val name: String = "Text",
        override val isVisible: Boolean = true,
        override val opacity: Float = 1f,
        override val blend: BlendMode = BlendMode.Normal,
        override val mask: Mask = Mask(),
        val content: String = "Your text",
        /** Where the text is centred, normalized like everything else a layer stores. */
        val centre: MaskPoint = MaskPoint(0.5f, 0.5f),
        /** Type size as a fraction of the image's shorter edge, so it survives the export. */
        val size: Float = 0.09f,
        val rotation: Float = 0f,
        /** Black by default: it is what a caption is, and what most photographs are lighter than. */
        val colour: GradientColour = GradientColour(tone = ColourTone.Black),
        val font: TextFont = TextFont.Sans,
        /** Exposure, contrast and colour, on top of what this layer does. Free when untouched. */
        override val adjustments: ToneAdjustments = ToneAdjustments(),
    ) : Layer

    /**
     * Blemishes covered with clean skin from nearby.
     *
     * A list of taps rather than pixels, like every other layer: the dabs replay over the original
     * at whatever resolution is being rendered, so the layer stays non-destructive and the export
     * matches the preview. Each dab carries the source it borrowed from, resolved once when it was
     * placed — searching again at export time could pick a different patch.
     */
    @Serializable
    @SerialName("heal")
    data class Heal(
        override val id: Long,
        override val name: String = "Heal",
        override val isVisible: Boolean = true,
        override val opacity: Float = 1f,
        override val blend: BlendMode = BlendMode.Normal,
        override val mask: Mask = Mask(),
        val dabs: List<HealDab> = emptyList(),
        /** Exposure, contrast and colour, on top of what this layer does. Free when untouched. */
        override val adjustments: ToneAdjustments = ToneAdjustments(),
    ) : Layer

    /**
     * Edge-preserving smoothing, for skin.
     *
     * A plain blur takes the pores and the eyelashes with them. A bilateral blur averages only
     * neighbours of a similar colour, so flat areas soften and edges stay — which is the whole
     * difference between retouching and smearing.
     */
    @Serializable
    @SerialName("smooth")
    data class Smooth(
        override val id: Long,
        override val name: String = "Smooth",
        override val isVisible: Boolean = true,
        override val opacity: Float = 1f,
        override val blend: BlendMode = BlendMode.Normal,
        override val mask: Mask = Mask(),
        val amount: Int = 55,
        /** Exposure, contrast and colour, on top of what this layer does. Free when untouched. */
        override val adjustments: ToneAdjustments = ToneAdjustments(),
    ) : Layer

    /**
     * A rectangle, ellipse or line drawn over the photo.
     *
     * Content like [Text], and placed the same way: a centre, a size as fractions of the frame, and
     * a rotation. Two handles — the centre to move it, a corner to size it.
     */
    @Serializable
    @SerialName("shape")
    data class Shape(
        override val id: Long,
        override val name: String = "Shape",
        override val isVisible: Boolean = true,
        override val opacity: Float = 1f,
        override val blend: BlendMode = BlendMode.Normal,
        override val mask: Mask = Mask(),
        val kind: ShapeKind = ShapeKind.Rectangle,
        val centre: MaskPoint = MaskPoint(0.5f, 0.5f),
        val width: Float = 0.4f,
        val height: Float = 0.3f,
        val rotation: Float = 0f,
        val colour: GradientColour = GradientColour(tone = ColourTone.White),
        /** Outline width as a fraction of the shorter edge; 0 fills the shape instead. */
        val stroke: Float = 0f,
        /** Exposure, contrast and colour, on top of what this layer does. Free when untouched. */
        override val adjustments: ToneAdjustments = ToneAdjustments(),
    ) : Layer {
        /** Where the sizing handle sits: the corner of the box, before any rotation. */
        val corner: MaskPoint
            get() = MaskPoint(centre.x + width / 2f, centre.y + height / 2f)
    }

    /**
     * A tone curve per channel.
     *
     * The colour channels are what make this colour grading rather than only contrast: lifting red
     * in the shadows warms them, pulling blue down in the highlights cools them. Separate tint
     * sliders would be a second mechanism arguing with this one over the same pixels.
     */
    @Serializable
    @SerialName("curve")
    data class Curve(
        override val id: Long,
        override val name: String = "Curve",
        override val isVisible: Boolean = true,
        override val opacity: Float = 1f,
        override val blend: BlendMode = BlendMode.Normal,
        override val mask: Mask = Mask(),
        val spec: CurveSpec = CurveSpec(),
        /** Exposure, contrast and colour, on top of what this layer does. Free when untouched. */
        override val adjustments: ToneAdjustments = ToneAdjustments(),
    ) : Layer

    /**
     * A wash of colour running from [from] to [to] across the frame.
     *
     * The gradient it is drawn from is the same [GradientSpec] a mask uses, rendered through the
     * same coverage function — so a colour gradient and a masked one placed identically line up
     * exactly, rather than nearly.
     */
    @Serializable
    @SerialName("gradient")
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
        /**
         * Both ends the same colour, so this is a flat fill rather than a ramp.
         *
         * A separate layer kind would duplicate all of this for no capability it doesn't already
         * have. Turning the flag off is how a fill becomes a gradient again, which is the reason
         * they share a layer at all.
         */
        val solid: Boolean = false,
        /** Exposure, contrast and colour, on top of what this layer does. Free when untouched. */
        override val adjustments: ToneAdjustments = ToneAdjustments(),
    ) : Layer
}

/**
 * A colour: where it sits on the wheel, how far out from the middle, and how bright.
 *
 * It was a hue and nothing else, which meant every colour the app could make was fully saturated and
 * fully bright — a rainbow, not a palette, with no pink, no navy and no charcoal anywhere in it.
 * [saturation] and [value] are the two axes that were missing, and both default to 1 so a colour
 * saved before they existed still reads back as exactly the colour it was.
 */
@Serializable
data class GradientColour(
    /** 0..360 around the wheel. Ignored unless [tone] is [ColourTone.Hue]. */
    val hue: Float = 20f,
    val tone: ColourTone = ColourTone.Hue,
    /** How far out from the middle of the wheel: 0 is white, 1 the pure hue. */
    val saturation: Float = 1f,
    /** How bright: 0 is black, 1 as bright as this hue goes. */
    val value: Float = 1f,
)

/**
 * The shortcuts sitting beside the wheel.
 *
 * Black and white are reachable on the wheel now — the middle at full brightness, and the bottom of
 * the brightness slider — but they are what most washes and most captions actually want, and hunting
 * for a corner of a picker to get an exact one is worse than a chip that says the word. [Clear] is
 * what lets a gradient fade *into* the photo rather than sitting over all of it, and has no place on
 * a wheel at all.
 */
@Serializable
enum class ColourTone(val label: String) {
    Hue("Color"),
    Black("Black"),
    White("White"),
    Clear("Clear"),
}

/** Copies a layer with new common properties, preserving its specific type and payload. */
fun Layer.withCommon(
    id: Long = this.id,
    name: String = this.name,
    isVisible: Boolean = this.isVisible,
    opacity: Float = this.opacity,
    blend: BlendMode = this.blend,
    mask: Mask = this.mask,
    adjustments: ToneAdjustments = this.adjustments,
): Layer = when (this) {
    is Layer.Tone -> copy(id = id, name = name, isVisible = isVisible, opacity = opacity, blend = blend, mask = mask, adjustments = adjustments)
    is Layer.Look -> copy(id = id, name = name, isVisible = isVisible, opacity = opacity, blend = blend, mask = mask, adjustments = adjustments)
    is Layer.Blur -> copy(id = id, name = name, isVisible = isVisible, opacity = opacity, blend = blend, mask = mask, adjustments = adjustments)
    is Layer.Gradient -> copy(id = id, name = name, isVisible = isVisible, opacity = opacity, blend = blend, mask = mask, adjustments = adjustments)
    is Layer.Curve -> copy(id = id, name = name, isVisible = isVisible, opacity = opacity, blend = blend, mask = mask, adjustments = adjustments)
    is Layer.Text -> copy(id = id, name = name, isVisible = isVisible, opacity = opacity, blend = blend, mask = mask, adjustments = adjustments)
    is Layer.Shape -> copy(id = id, name = name, isVisible = isVisible, opacity = opacity, blend = blend, mask = mask, adjustments = adjustments)
    is Layer.Smooth -> copy(id = id, name = name, isVisible = isVisible, opacity = opacity, blend = blend, mask = mask, adjustments = adjustments)
    is Layer.Heal -> copy(id = id, name = name, isVisible = isVisible, opacity = opacity, blend = blend, mask = mask, adjustments = adjustments)
}

/** A line has no inside, so it is always stroked whatever the stroke width says. */
@Serializable
enum class ShapeKind(val label: String) {
    Rectangle("Rectangle"),
    Ellipse("Ellipse"),
    Line("Line"),
}

/**
 * The typefaces on offer.
 *
 * Three that every Android device has, rather than shipping font files: a missing font renders as
 * something else entirely, and nothing about that is obvious from the layer that asked for it.
 */
@Serializable
enum class TextFont(val label: String) {
    Sans("Sans"),
    Serif("Serif"),
    Mono("Mono"),
}

/**
 * The colour this contributes, as packed ARGB.
 *
 * Black and white skip the wheel entirely rather than being hunted for on it, which is why they are
 * chips; everything else is [hsvToRgb] straight from where the thumb was left.
 */
fun GradientColour.toArgb(): Int = when (tone) {
    // Clear keeps the colour it fades from, so the ramp loses opacity without drifting through grey.
    ColourTone.Clear -> hsvToRgb(hue, saturation, value)
    ColourTone.Black -> 0xFF000000.toInt()
    ColourTone.White -> 0xFFFFFFFF.toInt()
    ColourTone.Hue -> 0xFF000000.toInt() or hsvToRgb(hue, saturation, value)
}
