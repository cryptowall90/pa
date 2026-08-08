package com.pictureperfectx.app.layers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.util.Log
import com.pictureperfectx.app.capture.GPUImageBokehFilter
import com.pictureperfectx.app.capture.ImageToner
import com.pictureperfectx.app.filter.FilterCatalog
import com.pictureperfectx.app.filter.FilterFactory
import jp.co.cyberagent.android.gpuimage.GPUImage
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

    fun render(context: Context, base: Bitmap, document: Document): Bitmap {
        val layers = document.renderable()
        if (layers.isEmpty()) return base

        val result = base.copy(Bitmap.Config.ARGB_8888, true) ?: return base
        val canvas = Canvas(result)

        layers.forEach { layer ->
            val effect = runCatching { effectFor(context, result, layer) }
                .onFailure { Log.e(TAG, "Layer '${layer.name}' failed to render", it) }
                .getOrNull() ?: return@forEach

            val masked = applyMask(effect, layer.mask)
            val paint = Paint().apply {
                isFilterBitmap = true
                alpha = (layer.opacity.coerceIn(0f, 1f) * 255).roundToInt()
                xfermode = layer.blend.toXfermode()
            }
            canvas.drawBitmap(masked, 0f, 0f, paint)
        }
        return result
    }

    /** The layer's edit applied to the whole of [source]; the mask decides where it survives. */
    private fun effectFor(context: Context, source: Bitmap, layer: Layer): Bitmap? = when (layer) {
        is Layer.Tone -> ImageToner.apply(context, source, layer.adjustments)

        is Layer.Look -> {
            val filter = FilterCatalog.byId(context, layer.filterId)
            val built = FilterFactory.create(context, filter, layer.intensity.coerceIn(0, 100) / 100f)
            built.lookup?.let { lookup ->
                GPUImage(context.applicationContext).apply { setFilter(lookup) }
                    .getBitmapWithFilterApplied(source)
            }
        }

        is Layer.Blur -> bokeh(context, source, layer.radius)
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

    /** Normal draws straight over; the rest map onto PorterDuff modes available on every API level. */
    private fun BlendMode.toXfermode(): PorterDuffXfermode? = when (this) {
        BlendMode.Normal -> null
        BlendMode.Multiply -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        BlendMode.Screen -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        BlendMode.Overlay -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
        BlendMode.Darken -> PorterDuffXfermode(PorterDuff.Mode.DARKEN)
        BlendMode.Lighten -> PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
    }
}
