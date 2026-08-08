package com.pictureperfectx.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayInputStream
import kotlin.math.max

/**
 * A loaded source image. [degraded] is true when the real image couldn't be decoded and this is a
 * lower-resolution stand-in — an editor saving it produces a smaller file than the original.
 */
data class LoadedImage(val bitmap: Bitmap, val degraded: Boolean)

/** Loads bitmaps from content URIs, downscaled and rotated per EXIF so edits start upright. */
object BitmapIO {

    /**
     * Load [uri] for editing, falling back to a RAW file's embedded preview.
     *
     * Android's Java decoders don't guarantee DNG support — some builds ship a RAW codec and decode
     * it at full size, others return null — so a failed decode drops to the embedded preview rather
     * than leaving the caller with nothing. That preview is a *thumbnail*, hence [LoadedImage.degraded].
     */
    fun loadForEdit(context: Context, uri: Uri, maxEdge: Int): LoadedImage? {
        load(context, uri, maxEdge)?.let { return LoadedImage(it, degraded = false) }
        val preview = RawPreview.thumbnail(context, uri, maxEdge) ?: return null
        return LoadedImage(preview, degraded = true)
    }

    /** Load [uri], downscaled so its longest edge is <= [maxEdge], with EXIF orientation applied. */
    fun load(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest > 0 && longest / sample > maxEdge) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        return applyExifOrientation(decoded, orientation)
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }
}
