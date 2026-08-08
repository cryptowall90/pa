package com.pictureperfectx.app.capture

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A crop rectangle in **normalized** coordinates (0..1) of the oriented image. Keeping it
 * resolution-independent is what makes the on-screen preview and the full-resolution export agree:
 * both apply the same fractions to whatever pixel size they happen to be working with.
 */
data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** An integer pixel rectangle, ready for `Bitmap.createBitmap`. */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int)

/** Crop ratio presets. [ratio] is width/height; null means unconstrained. */
enum class AspectRatio(val label: String) {
    Original("Original"),
    Free("Free"),
    Square("1:1"),
    R4x5("4:5"),
    R9x16("9:16"),
    R16x9("16:9"),
    R3x2("3:2"),
    R4x3("4:3"),
    R5x7("5:7"),
    ;

    /** @param sourceRatio the image's own width/height, used by [Original]. */
    fun ratio(sourceRatio: Float): Float? = when (this) {
        Original -> sourceRatio
        Free -> null
        Square -> 1f
        R4x5 -> 4f / 5f
        R9x16 -> 9f / 16f
        R16x9 -> 16f / 9f
        R3x2 -> 3f / 2f
        R4x3 -> 4f / 3f
        R5x7 -> 5f / 7f
    }
}

/**
 * The full geometric edit, applied in a fixed order: **flips → quarter turns → straighten → crop**.
 * Order matters — the crop is expressed in the space of the already-rotated image, so changing it
 * would silently reframe every existing edit.
 */
data class ImageGeometry(
    val quarterTurns: Int = 0,          // clockwise, 0..3
    val straightenDegrees: Float = 0f,  // -45..45
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val crop: CropRect = CropRect(),
    val aspect: AspectRatio = AspectRatio.Original,
) {
    val isIdentity: Boolean
        get() = quarterTurns == 0 && straightenDegrees == 0f && !flipHorizontal && !flipVertical &&
            crop.left <= 0f && crop.top <= 0f && crop.right >= 1f && crop.bottom >= 1f
}

/**
 * Maths behind [ImageGeometry]. Deliberately free of Android types so it can be unit-tested on the
 * JVM — the only place this project can execute code at all.
 */
object CropMath {

    /** Smallest crop allowed, as a fraction of each axis — stops handles collapsing to nothing. */
    const val MIN_SIZE = 0.05f

    const val MAX_STRAIGHTEN_DEGREES = 45f

    /** Size after [quarterTurns] 90-degree turns: odd turns swap the axes. */
    fun orientedSize(width: Int, height: Int, quarterTurns: Int): Pair<Int, Int> =
        if (Math.floorMod(quarterTurns, 2) == 0) width to height else height to width

    /** Bounding box of a [width] x [height] rectangle rotated by [degrees]. */
    fun rotatedBounds(width: Float, height: Float, degrees: Float): Pair<Float, Float> {
        val rad = Math.toRadians(degrees.toDouble())
        val c = abs(cos(rad)).toFloat()
        val s = abs(sin(rad)).toFloat()
        return (width * c + height * s) to (width * s + height * c)
    }

    /**
     * The largest rectangle of [targetRatio] (width/height in *pixels*) that fits centred in an
     * image whose own ratio is [sourceRatio], in normalized coordinates.
     *
     * A normalized rect of w x h covers pixels (w·W) x (h·H), so its pixel ratio is
     * `(w/h) · sourceRatio` — which is why the normalized aspect is `targetRatio / sourceRatio`.
     */
    fun centeredCrop(sourceRatio: Float, targetRatio: Float): CropRect {
        if (sourceRatio <= 0f || targetRatio <= 0f) return CropRect()
        return if (targetRatio > sourceRatio) {
            val h = sourceRatio / targetRatio // wider than the image: full width, trim height
            val inset = (1f - h) / 2f
            CropRect(0f, inset, 1f, 1f - inset)
        } else {
            val w = targetRatio / sourceRatio
            val inset = (1f - w) / 2f
            CropRect(inset, 0f, 1f - inset, 1f)
        }
    }

    /** Clamp a rect into 0..1, keeping at least [MIN_SIZE] on each axis. */
    fun clamp(crop: CropRect): CropRect {
        val left = crop.left.coerceIn(0f, 1f - MIN_SIZE)
        val top = crop.top.coerceIn(0f, 1f - MIN_SIZE)
        val right = crop.right.coerceIn(left + MIN_SIZE, 1f)
        val bottom = crop.bottom.coerceIn(top + MIN_SIZE, 1f)
        return CropRect(left, top, right, bottom)
    }

    /**
     * Reshape [crop] to [targetRatio] about its own centre, shrinking to stay inside the image and
     * sliding back in if the centre sat near an edge.
     */
    fun withRatio(crop: CropRect, sourceRatio: Float, targetRatio: Float): CropRect {
        if (sourceRatio <= 0f || targetRatio <= 0f) return clamp(crop)
        val normalizedAspect = targetRatio / sourceRatio
        var width = crop.width
        var height = width / normalizedAspect
        if (height > 1f) {
            height = 1f
            width = height * normalizedAspect
        }
        if (width > 1f) {
            width = 1f
            height = width / normalizedAspect
        }
        val centerX = (crop.left + crop.right) / 2f
        val centerY = (crop.top + crop.bottom) / 2f
        val left = (centerX - width / 2f).coerceIn(0f, 1f - width)
        val top = (centerY - height / 2f).coerceIn(0f, 1f - height)
        return CropRect(left, top, left + width, top + height)
    }

    /** Convert a normalized crop into integer pixels, never returning an empty rectangle. */
    fun pixelRect(crop: CropRect, width: Int, height: Int): PixelRect {
        if (width <= 0 || height <= 0) return PixelRect(0, 0, 0, 0)
        val safe = clamp(crop)
        val x = (safe.left * width).roundToInt().coerceIn(0, width - 1)
        val y = (safe.top * height).roundToInt().coerceIn(0, height - 1)
        val right = (safe.right * width).roundToInt().coerceIn(x + 1, width)
        val bottom = (safe.bottom * height).roundToInt().coerceIn(y + 1, height)
        return PixelRect(x, y, right - x, bottom - y)
    }
}
