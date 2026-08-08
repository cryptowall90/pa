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
 * Applies an [ImageGeometry] to a bitmap, in the order the model declares: flips, then quarter
 * turns, then straighten, then crop.
 *
 * The same function serves the live preview and the full-resolution export — the crop lives in
 * normalized coordinates, so running it at two different sizes yields the same framing, which is
 * what keeps "what you saw" and "what got saved" in agreement.
 */
object ImageTransformer {

    fun apply(source: Bitmap, geometry: ImageGeometry): Bitmap {
        val oriented = orient(source, geometry)
        val rect = CropMath.pixelRect(geometry.crop, oriented.width, oriented.height)
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
