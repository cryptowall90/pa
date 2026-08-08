package com.pictureperfectx.app.camera

import android.graphics.Bitmap
import android.net.Uri
import com.pictureperfectx.app.filter.Filter

/**
 * What a shutter press writes.
 *
 * A LUT is a creative rendering, so it is baked into JPEGs only — a DNG is unprocessed sensor data
 * by definition and is always saved as the camera captured it.
 */
enum class CaptureFormat(val label: String) {
    JPEG("JPEG"),
    RAW("RAW"),
    RAW_JPEG("RAW+JPEG"),
    ;

    val writesRaw: Boolean get() = this != JPEG

    fun next(): CaptureFormat = CaptureFormat.entries[(ordinal + 1) % CaptureFormat.entries.size]
}

/** Outcome of a capture, handed back off the main thread. */
sealed interface CaptureResult {

    /** A finished, filtered still the caller still has to save. */
    data class Jpeg(val bitmap: Bitmap, val filter: Filter) : CaptureResult

    /**
     * A DNG already written to MediaStore by CameraX. [jpeg] carries the filtered companion still
     * in RAW+JPEG mode and is null when only RAW was requested.
     */
    data class Raw(
        val dngUri: Uri,
        val dngName: String,
        val width: Int,
        val height: Int,
        val jpeg: Jpeg?,
    ) : CaptureResult
}
