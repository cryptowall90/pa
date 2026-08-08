package com.pictureperfectx.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size

/**
 * Reads what can be shown for a DNG without demosaicing it.
 *
 * Android ships no RAW decoder, but every DNG carries an embedded JPEG preview. On API 29+ the
 * media scanner has already extracted one, so `loadThumbnail` is faster and far more reliable than
 * parsing the file ourselves; older devices fall back to the EXIF thumbnail.
 */
object RawPreview {

    private const val TAG = "RawPreview"

    /** A displayable, correctly-oriented preview of a RAW capture, or null when there's none. */
    fun thumbnail(context: Context, uri: Uri, size: Int): Bitmap? {
        val preview = rawThumbnail(context, uri, size) ?: return null
        return orientToFile(context, uri, preview)
    }

    private fun rawThumbnail(context: Context, uri: Uri, size: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fromStore = runCatching {
                context.contentResolver.loadThumbnail(uri, Size(size, size), null)
            }.onFailure { Log.w(TAG, "loadThumbnail failed for $uri", it) }.getOrNull()
            if (fromStore != null) return fromStore
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).thumbnail?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        }.getOrNull()
    }

    /**
     * A DNG can't have its pixels rotated, so orientation lives in a metadata tag — and an embedded
     * preview comes back with that rotation still unapplied, which is why RAW shots appeared
     * sideways next to upright JPEGs.
     *
     * The two sources don't behave alike: the EXIF thumbnail is definitely unrotated, while
     * `loadThumbnail` may already have corrected it, and rotating twice is just as wrong. So rather
     * than guessing by API level, compare the shape we *should* end up with against the shape we
     * actually got, and only rotate when they disagree.
     */
    private fun orientToFile(context: Context, uri: Uri, preview: Bitmap): Bitmap {
        val orientation = orientation(context, uri)
        if (orientation == ExifInterface.ORIENTATION_NORMAL) return preview

        val (rawWidth, rawHeight) = dimensions(context, uri)
        if (rawWidth > 0 && rawHeight > 0 && preview.width != preview.height) {
            val quarterTurned = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                orientation == ExifInterface.ORIENTATION_TRANSVERSE
            // Landscape sensor + a quarter turn should read as portrait, and vice versa.
            val expectPortrait = if (quarterTurned) rawWidth > rawHeight else rawHeight > rawWidth
            if (expectPortrait == (preview.height > preview.width)) return preview // already applied
        }
        return BitmapIO.applyExifOrientation(preview, orientation)
    }

    private fun orientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    /** Pixel dimensions of the RAW frame, or `0 x 0` when they can't be read. */
    fun dimensions(context: Context, uri: Uri): Pair<Int, Int> = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) to
                exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
        }
    }.getOrNull() ?: (0 to 0)
}
