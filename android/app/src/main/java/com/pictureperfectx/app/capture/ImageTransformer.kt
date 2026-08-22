package com.pictureperfectx.app.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Applies an [ImageGeometry] to a bitmap: flips, then quarter turns, then straighten, then crop.
 *
 * The crop lives in normalized coordinates, so running it at preview size and at full resolution
 * yields the same framing — which is what keeps "what you saw" and "what got saved" in agreement.
 *
 * Deliberately offered as **two calls rather than one**. Anything drawn in normalized coordinates —
 * a mask, a text placement, a gradient — means "this fraction of the frame", so it has to be
 * composited while the frame is still the whole photo and cropped afterwards. A single convenient
 * `apply` that did both at once is what let the export crop first and land every layer somewhere the
 * preview never showed it; there is now no such call to reach for by mistake.
 */
object ImageTransformer {

    /**
     * The crop alone, on an already-oriented bitmap. Call this **after** compositing, never before.
     */
    fun crop(oriented: Bitmap, geometry: ImageGeometry): Bitmap {
        val rect = CropMath.pixelRect(geometry.crop, oriented.width, oriented.height)
        if (rect.x == 0 && rect.y == 0 && rect.width == oriented.width && rect.height == oriented.height) {
            return oriented
        }
        return Bitmap.createBitmap(oriented, rect.x, rect.y, rect.width, rect.height)
    }

    /** Flips, quarter turns and straighten — everything except the crop. */
    fun orient(source: Bitmap, geometry: ImageGeometry): Bitmap {
        val turns = Math.floorMod(geometry.quarterTurns, 4)
        val flipped = flipAndTurn(source, turns, geometry.flipHorizontal, geometry.flipVertical)
        if (geometry.straightenDegrees == 0f) return flipped
        return straighten(flipped, geometry.straightenDegrees)
    }

    private fun flipAndTurn(source: Bitmap, turns: Int, flipH: Boolean, flipV: Boolean): Bitmap {
        if (turns == 0 && !flipH && !flipV) return source
        val matrix = Matrix().apply {
            if (flipH) postScale(-1f, 1f)
            if (flipV) postScale(1f, -1f)
            if (turns != 0) postRotate(turns * 90f)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Rotates by a small angle while scaling just enough to cover the original frame, so a
     * straightened photo never shows empty triangles in the corners. The result keeps the input's
     * dimensions, which lets the normalized crop stay meaningful across the operation.
     */
    private fun straighten(source: Bitmap, degrees: Float): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return source

        val radians = Math.toRadians(degrees.toDouble())
        val cosine = abs(cos(radians)).toFloat()
        val sine = abs(sin(radians)).toFloat()
        val scale = max(
            (width * cosine + height * sine) / width,
            (width * sine + height * cosine) / height,
        )

        val output = Bitmap.createBitmap(width, height, source.config ?: Bitmap.Config.ARGB_8888)
        val matrix = Matrix().apply {
            postTranslate(-width / 2f, -height / 2f)
            postRotate(degrees)
            postScale(scale, scale)
            postTranslate(width / 2f, height / 2f)
        }
        Canvas(output).drawBitmap(
            source,
            matrix,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        return output
    }
}
