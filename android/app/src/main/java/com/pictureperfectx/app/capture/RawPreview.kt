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

    /** A displayable preview of a RAW capture, or null when the file carries none. */
    fun thumbnail(context: Context, uri: Uri, size: Int): Bitmap? {
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

    /** Pixel dimensions of the RAW frame, or `0 x 0` when they can't be read. */
    fun dimensions(context: Context, uri: Uri): Pair<Int, Int> = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) to
                exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
        }
    }.getOrNull() ?: (0 to 0)
}
