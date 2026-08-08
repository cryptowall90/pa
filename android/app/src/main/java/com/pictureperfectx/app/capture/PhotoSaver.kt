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
data class SavedPhoto(
    val uri: Uri,
    val displayName: String,
    val width: Int,
    val height: Int,
    val mimeType: String = PhotoSaver.MIME_JPEG,
)

/**
 * Writes finished images into the phone gallery under `DCIM/PicturePerfectX`.
 *
 * Two ways in: [save] compresses a [Bitmap] itself, while [imageCollection] + [valuesFor] hand the
 * MediaStore coordinates to CameraX so it can stream a capture (a JPEG or a DNG) straight to disk.
 *
 * Uses the scoped-storage MediaStore API on API 29+ (no runtime permission needed) and falls
 * back to a direct public-directory insert on older devices, where WRITE_EXTERNAL_STORAGE is
 * declared in the manifest.
 */
object PhotoSaver {

    const val MIME_JPEG = "image/jpeg"
    const val MIME_DNG = "image/x-adobe-dng"

    private const val ALBUM = "PicturePerfectX"
    // Save under DCIM (where the phone camera stores photos) in an app-created album, so shots show
    // up in the phone's gallery alongside camera photos. Not `const`: DIRECTORY_DCIM is a runtime field.
    private val RELATIVE_PATH = "${Environment.DIRECTORY_DCIM}/$ALBUM"

    /** Shared stem for the files of one shutter press, so a DNG and its JPEG stay paired by name. */
    fun baseName(): String = "PPX_${System.currentTimeMillis()}"

    /** The MediaStore collection every capture is written into. */
    fun imageCollection(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    /**
     * Row values for a new image. Deliberately omits `IS_PENDING` — CameraX manages that itself
     * when it owns the write; [save] adds it for the writes we perform.
     */
    fun valuesFor(displayName: String, mimeType: String): ContentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
        }
    }

    fun save(context: Context, bitmap: Bitmap): SavedPhoto = save(context, bitmap, "${baseName()}.jpg")

    fun save(context: Context, bitmap: Bitmap, displayName: String): SavedPhoto {
        val resolver = context.contentResolver

        val values = valuesFor(displayName, MIME_JPEG).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(imageCollection(), values)
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

        return SavedPhoto(uri, displayName, bitmap.width, bitmap.height, MIME_JPEG)
    }
}
