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

    /**
     * Whether the selected look reaches the saved file. A RAW-only shot is pure sensor data, so the
     * LUT and the post-processing adjustments apply to nothing — the viewfinder shows the frame
     * unfiltered rather than promising a rendering that won't exist.
     */
    val appliesLook: Boolean get() = this != RAW

    /**
     * The next format the user can actually shoot, cycling within [available] so the chip can never
     * land on a mode this camera has already refused.
     */
    fun next(available: Set<CaptureFormat>): CaptureFormat {
        val ordered = CaptureFormat.entries.filter { it in available }
        if (ordered.isEmpty()) return JPEG
        return ordered[(ordered.indexOf(this) + 1) % ordered.size]
    }
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
