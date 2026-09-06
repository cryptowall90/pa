package com.pictureperfectx.app.layers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import com.pictureperfectx.app.capture.GPUImageBokehFilter
import com.pictureperfectx.app.capture.ImageToner
import com.pictureperfectx.app.filter.FilterCatalog
import com.pictureperfectx.app.filter.FilterFactory
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageBilateralBlurFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageToneCurveFilter
import kotlin.math.roundToInt

/**
 * Composites a [Document] over a base image.
 *
 * The same function serves the on-screen preview and the full-resolution export — masks are stored
 * as resolution-independent coverage, so running the identical stack at two sizes produces the same
 * picture. That is what keeps "what you saw" and "what got saved" honest.
 *
 * Each layer's effect is computed from the composite *so far*, not from the original, so layers
 * genuinely stack the way an adjustment layer is expected to.
 */
object LayerRenderer {

    private const val TAG = "LayerRenderer"

    /** Blur strength is quoted against a 1000px short edge; anything else scales from there. */
    private const val BLUR_REFERENCE_EDGE = 1000f

    /** The size the defocus is computed at, whatever the photo's own resolution. */
    private const val BLUR_WORKING_EDGE = 1280

    /** A colour gradient is a smooth ramp, so it is generated small and scaled up. */
    private const val GRADIENT_WORKING_EDGE = 512

    /** Rows composited at a time, so a big export doesn't hold the whole photo twice over. */
    private const val BLEND_BAND_ROWS = 128

    /** A stroked shape asked for no width still needs one, or it draws nothing at all. */
    private const val MIN_STROKE = 0.004f

    // How far the bilateral blur reaches across a colour difference: low smooths hard, high barely
    // at all. The strength cap is what stops the top of the slider turning skin to plastic.
    private const val SMOOTH_MIN_DISTANCE = 2f
    private const val SMOOTH_MAX_DISTANCE = 12f
    private const val SMOOTH_MAX_STRENGTH = 0.8f

    fun render(context: Context, base: Bitmap, document: Document): Bitmap {
        val layers = document.renderable()
        if (layers.isEmpty()) return base

        val result = base.copy(Bitmap.Config.ARGB_8888, true) ?: return base
        val canvas = Canvas(result)

        layers.forEach { layer ->
            val payload = runCatching { effectFor(context, result, layer) }
                .onFailure { Log.e(TAG, "Layer '${layer.name}' failed to render", it) }
                .getOrNull()

            // A layer that neither draws anything nor adjusts anything has nothing to composite.
            // Null used to mean that on its own; now the adjustments have to be neutral too, or a
            // Look with no lookup — or a Tone layer, whose payload is *always* null — would be
            // dropped before its sliders were ever applied.
            if (payload == null && layer.adjustments.isNeutral) return@forEach

            // Adjustments come after the layer's own effect: brightening a *filtered* area is what
            // anyone means by it. Untouched sliders cost nothing — ImageToner hands the same bitmap
            // straight back — so this is free for every layer nobody has adjusted.
            val effect = runCatching { ImageToner.apply(context, payload ?: result, layer.adjustments) }
                .onFailure { Log.e(TAG, "Layer '${layer.name}' failed to adjust", it) }
                .getOrNull() ?: payload ?: return@forEach

            val masked = applyMask(effect, layer.mask)
            compose(canvas, result, masked, layer)
        }
        return result
    }

    /**
     * Puts one layer's finished pixels onto the running result.
     *
     * `Normal` is a plain source-over draw, which is both correct and the fast path. Every other
     * mode goes through [LayerBlend] instead of a `PorterDuffXfermode`, because those get the alpha
     * wrong for what a layer means here — `MULTIPLY` computes the result's alpha as `Sa × Da`, so
     * a masked-out or faded-out pixel punched a hole in the photo rather than leaving it alone.
     *
     * A band at a time rather than the whole photo: two full-resolution `IntArray`s of a 12MP
     * export would be the better part of a hundred megabytes, and this runs per layer.
     */
    private fun compose(canvas: Canvas, result: Bitmap, masked: Bitmap, layer: Layer) {
        if (layer.blend == BlendMode.Normal) {
            val paint = Paint().apply {
                isFilterBitmap = true
                alpha = (layer.opacity.coerceIn(0f, 1f) * 255).roundToInt()
            }
            canvas.drawBitmap(masked, 0f, 0f, paint)
            return
        }

        val width = result.width
        val height = result.height
        // Every effect is generated at the source's size, but a scaled one would silently blend
        // offset pixels, which is worse than the cost of putting it right.
        val source = if (masked.width == width && masked.height == height) {
            masked
        } else {
            Bitmap.createScaledBitmap(masked, width, height, true)
        }

        val bandRows = BLEND_BAND_ROWS.coerceAtMost(height)
        val below = IntArray(width * bandRows)
        val above = IntArray(width * bandRows)
        var top = 0
        while (top < height) {
            val rows = bandRows.coerceAtMost(height - top)
            val count = width * rows
            result.getPixels(below, 0, width, 0, top, width, rows)
            source.getPixels(above, 0, width, 0, top, width, rows)
            LayerBlend.composite(below, above, layer.blend, layer.opacity, count)
            result.setPixels(below, 0, width, 0, top, width, rows)
            top += rows
        }
        if (source !== masked && !source.isRecycled) source.recycle()
    }

    /** The layer's edit applied to the whole of [source]; the mask decides where it survives. */
    private fun effectFor(context: Context, source: Bitmap, layer: Layer): Bitmap? = when (layer) {
        // Nothing of its own: a Tone layer *is* its adjustments, and those are applied to every
        // layer by the shared pass in `render`. Doing it here as well would apply them twice.
        is Layer.Tone -> null

        is Layer.Look -> {
            val filter = FilterCatalog.byId(context, layer.filterId)
            val built = FilterFactory.create(context, filter, layer.intensity.coerceIn(0, 100) / 100f)
            built.lookup?.let { lookup ->
                GPUImage(context.applicationContext).apply { setFilter(lookup) }
                    .getBitmapWithFilterApplied(source)
            }
        }

        is Layer.Blur -> bokeh(context, source, layer.radius)

        is Layer.Gradient -> gradient(source, layer)

        is Layer.Text -> text(source, layer)

        is Layer.Shape -> shape(source, layer)

        is Layer.Smooth -> smooth(context, source, layer.amount)

        is Layer.Heal -> heal(source, layer)

        // One pass however many channels are bent: the filter bakes all four splines into a single
        // lookup texture and the shader takes one sample per channel.
        is Layer.Curve -> if (layer.spec.isIdentity) {
            null
        } else {
            GPUImage(context.applicationContext)
                .apply {
                    setFilter(
                        GPUImageToneCurveFilter().apply {
                            setRgbCompositeControlPoints(layer.spec.rgb.toControlPoints())
                            setRedControlPoints(layer.spec.red.toControlPoints())
                            setGreenControlPoints(layer.spec.green.toControlPoints())
                            setBlueControlPoints(layer.spec.blue.toControlPoints())
                        },
                    )
                }
                .getBitmapWithFilterApplied(source)
        }
    }

    /**
     * Draws the words onto a transparent bitmap the size of the photo.
     *
     * Type size is a fraction of the shorter edge rather than a pixel count, so the same layer
     * comes out the same size on the preview and on the full-resolution export — the same reason
     * masks are coverage grids and blur radius is a strength.
     */
    private fun text(source: Bitmap, layer: Layer.Text): Bitmap? {
        if (layer.content.isBlank()) return null
        val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = layer.colour.toArgb()
            textSize = layer.size * minOf(source.width, source.height)
            typeface = when (layer.font) {
                TextFont.Sans -> Typeface.SANS_SERIF
                TextFont.Serif -> Typeface.SERIF
                TextFont.Mono -> Typeface.MONOSPACE
            }
            textAlign = Paint.Align.CENTER
        }

        val lines = layer.content.split('\n')
        val lineHeight = paint.fontSpacing
        val centreX = layer.centre.x * source.width
        val centreY = layer.centre.y * source.height

        canvas.save()
        canvas.rotate(layer.rotation, centreX, centreY)
        // Centre the block on the point, then the first baseline sits half a line above it. The
        // ascent/descent term is what centres the glyphs themselves rather than their baselines.
        val firstBaseline =
            centreY - (lines.size - 1) * lineHeight / 2f - (paint.ascent() + paint.descent()) / 2f
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, centreX, firstBaseline + index * lineHeight, paint)
        }
        canvas.restore()
        return bitmap
    }

    /**
     * Replays the heal dabs over a copy of the photo.
     *
     * Radii are fractions of the shorter edge and the dabs are normalized, so the same list lands
     * in the same places on the preview and on the export — the whole reason a heal is stored as
     * taps rather than as painted pixels.
     */
    private fun heal(source: Bitmap, layer: Layer.Heal): Bitmap? {
        if (layer.dabs.isEmpty()) return null
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return null

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val shortEdge = minOf(width, height)

        layer.dabs.forEach { dab ->
            Heal.apply(
                pixels = pixels,
                width = width,
                height = height,
                x = (dab.centre.x * width).roundToInt(),
                y = (dab.centre.y * height).roundToInt(),
                sourceX = (dab.source.x * width).roundToInt(),
                sourceY = (dab.source.y * height).roundToInt(),
                radius = (dab.radius * shortEdge).roundToInt(),
            )
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Softens skin without flattening it.
     *
     * The amount drives how far the bilateral blur will average across a colour difference — but
     * some of the original is always drawn back over the top, because a bilateral blur taken to its
     * limit turns skin to plastic, which is the failure mode of every retouching tool.
     */
    private fun smooth(context: Context, source: Bitmap, amount: Int): Bitmap? {
        val strength = amount.coerceIn(0, 100) / 100f
        if (strength <= 0f) return null

        val blurred = GPUImage(context.applicationContext)
            .apply {
                setFilter(
                    GPUImageBilateralBlurFilter(
                        SMOOTH_MIN_DISTANCE + (SMOOTH_MAX_DISTANCE - SMOOTH_MIN_DISTANCE) * (1f - strength),
                    ),
                )
            }
            .getBitmapWithFilterApplied(source)

        val keep = 1f - SMOOTH_MAX_STRENGTH * strength
        val out = blurred.copy(Bitmap.Config.ARGB_8888, true) ?: return blurred
        if (blurred !== out && !blurred.isRecycled) blurred.recycle()
        Canvas(out).drawBitmap(
            source,
            0f,
            0f,
            Paint().apply { alpha = (keep.coerceIn(0f, 1f) * 255).roundToInt() },
        )
        return out
    }

    /** Draws the shape onto a transparent bitmap the size of the photo. */
    private fun shape(source: Bitmap, layer: Layer.Shape): Bitmap? {
        if (layer.width <= 0f && layer.height <= 0f) return null
        val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val shortEdge = minOf(source.width, source.height)
        val strokeWidth = layer.stroke * shortEdge
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = layer.colour.toArgb()
            // A line has no interior to fill, so it ignores a stroke of zero rather than vanishing.
            style = if (strokeWidth > 0f || layer.kind == ShapeKind.Line) {
                Paint.Style.STROKE
            } else {
                Paint.Style.FILL
            }
            this.strokeWidth = if (strokeWidth > 0f) strokeWidth else shortEdge * MIN_STROKE
            strokeCap = Paint.Cap.ROUND
        }

        val centreX = layer.centre.x * source.width
        val centreY = layer.centre.y * source.height
        val halfWidth = layer.width * source.width / 2f
        val halfHeight = layer.height * source.height / 2f

        canvas.save()
        canvas.rotate(layer.rotation, centreX, centreY)
        when (layer.kind) {
            ShapeKind.Rectangle -> canvas.drawRect(
                centreX - halfWidth,
                centreY - halfHeight,
                centreX + halfWidth,
                centreY + halfHeight,
                paint,
            )

            ShapeKind.Ellipse -> canvas.drawOval(
                centreX - halfWidth,
                centreY - halfHeight,
                centreX + halfWidth,
                centreY + halfHeight,
                paint,
            )

            // Drawn corner to corner of the same box, so one set of handles places all three.
            ShapeKind.Line -> canvas.drawLine(
                centreX - halfWidth,
                centreY - halfHeight,
                centreX + halfWidth,
                centreY + halfHeight,
                paint,
            )
        }
        canvas.restore()
        return bitmap
    }

    private fun List<CurvePoint>.toControlPoints(): Array<PointF> =
        Array(size) { PointF(this[it].x, this[it].y) }

    /**
     * Renders a colour gradient at [source]'s size.
     *
     * Built from [MaskGradient.coverage], not from Android's `LinearGradient` and friends. Those
     * would cover three of the five styles and leave Diamond with no equivalent, so the fill would
     * have to be written twice — and the two halves would drift, which is exactly the bug that ends
     * with a colour gradient and a masked one placed identically not lining up.
     *
     * Generated at a modest size and scaled up: it is a smooth ramp, so there is nothing to lose.
     */
    private fun gradient(source: Bitmap, layer: Layer.Gradient): Bitmap {
        val longEdge = maxOf(source.width, source.height).coerceAtLeast(1)
        val scale = if (longEdge > GRADIENT_WORKING_EDGE) {
            GRADIENT_WORKING_EDGE.toFloat() / longEdge
        } else {
            1f
        }
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)

        val from = layer.from.toArgb()
        // Read one end twice rather than trusting the pair to be equal: if they ever drifted, a
        // "fill" would quietly render a faint ramp and look like a rendering bug.
        val to = if (layer.solid) from else layer.to.toArgb()
        val pixels = IntArray(width * height) { index ->
            val column = index % width
            val row = index / width
            val t = MaskGradient.coverage(
                spec = layer.spec,
                x = (column + 0.5f) / width,
                y = (row + 0.5f) / height,
                columns = width,
                rows = height,
            )
            // Coverage is 1 at the gradient's start, which is where `from` belongs.
            blend(to, from, t)
        }

        val small = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        if (width == source.width && height == source.height) return small
        val full = Bitmap.createScaledBitmap(small, source.width, source.height, true)
        if (full !== small && !small.isRecycled) small.recycle()
        return full
    }

    /** Straight interpolation in premultiplied-free ARGB; a wash needs nothing cleverer. */
    private fun blend(start: Int, end: Int, t: Float): Int {
        val amount = t.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (start shr shift) and 0xFF
            val b = (end shr shift) and 0xFF
            return (a + (b - a) * amount).roundToInt().coerceIn(0, 255) shl shift
        }
        return channel(24) or channel(16) or channel(8) or channel(0)
    }

    /**
     * Defocuses [source] at a reduced working size, then scales the result back.
     *
     * A disc kernel costs one texture fetch per tap per pixel, and a full-resolution export has
     * four times the pixels of the preview — so blurring at export size would be four times the
     * work to produce detail the blur is throwing away regardless. Working at a fixed size also
     * means the export is blurred exactly as the preview was, rather than merely similarly.
     */
    private fun bokeh(context: Context, source: Bitmap, strength: Int): Bitmap {
        val longEdge = maxOf(source.width, source.height).coerceAtLeast(1)
        val scale = if (longEdge > BLUR_WORKING_EDGE) BLUR_WORKING_EDGE.toFloat() / longEdge else 1f
        val working = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            source
        }

        val blurred = GPUImage(context.applicationContext)
            .apply { setFilter(GPUImageBokehFilter(blurRadiusPixels(working, strength))) }
            .getBitmapWithFilterApplied(working)
        if (working !== source && working !== blurred && !working.isRecycled) working.recycle()

        if (blurred.width == source.width && blurred.height == source.height) return blurred
        val restored = Bitmap.createScaledBitmap(blurred, source.width, source.height, true)
        if (restored !== blurred && !blurred.isRecycled) blurred.recycle()
        return restored
    }

    /**
     * The slider is a strength, not a pixel count — so it scales with the image it's applied to.
     * A fixed pixel radius would mean the same setting blurred a small photo far more than a large
     * one, and the number the user judged on the preview wouldn't survive the export.
     */
    private fun blurRadiusPixels(source: Bitmap, strength: Int): Float {
        val shortEdge = minOf(source.width, source.height).coerceAtLeast(1)
        return (strength.coerceAtLeast(1) * shortEdge / BLUR_REFERENCE_EDGE).coerceAtLeast(1f)
    }

    /**
     * Punches the mask into the effect's alpha channel, so only the painted area is drawn.
     *
     * The coverage grid is far smaller than the photo and is scaled up with filtering, which softens
     * the edge for free — [Mask.softened] handles the deliberate feathering on top of that.
     */
    private fun applyMask(effect: Bitmap, mask: Mask): Bitmap {
        if (mask.isEmpty && !mask.inverted) return effect

        val masked = effect.copy(Bitmap.Config.ARGB_8888, true) ?: return effect
        val coverage = mask.softened()
        val pixels = IntArray(mask.columns * mask.rows) { index ->
            // Only the alpha channel matters to DST_IN; the colour is irrelevant.
            (coverage[index].coerceIn(0f, 1f) * 255).roundToInt() shl 24
        }
        val alpha = Bitmap.createBitmap(pixels, mask.columns, mask.rows, Bitmap.Config.ARGB_8888)

        Canvas(masked).drawBitmap(
            alpha,
            null,
            Rect(0, 0, masked.width, masked.height),
            Paint().apply {
                isFilterBitmap = true
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            },
        )
        return masked
    }

}
