package com.pictureperfectx.app.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException

/** Result of a save attempt, carrying the MediaStore [uri] on success. */
data class SavedPhoto(val uri: Uri, val displayName: String, val width: Int, val height: Int)

/**
 * Writes a finished [Bitmap] into the shared gallery under `Pictures/PicturePerfectX`.
 *
 * Uses the scoped-storage MediaStore API on API 29+ (no runtime permission needed) and falls
 * back to a direct public-directory insert on older devices, where WRITE_EXTERNAL_STORAGE is
 * declared in the manifest.
 */
object PhotoSaver {

    private const val ALBUM = "PicturePerfectX"
    // Not `const`: Environment.DIRECTORY_PICTURES is a runtime field, so this is resolved at runtime.
    private val RELATIVE_PATH = "${Environment.DIRECTORY_PICTURES}/$ALBUM"

    fun save(context: Context, bitmap: Bitmap): SavedPhoto {
        val displayName = "PPX_${System.currentTimeMillis()}.jpg"
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore insert returned null")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                    throw IOException("Bitmap.compress failed")
                }
            } ?: throw IOException("Could not open output stream")
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // don't leave an empty pending row behind
            throw e
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        return SavedPhoto(uri, displayName, bitmap.width, bitmap.height)
    }
}
