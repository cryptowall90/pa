package com.pictureperfectx.app.layers

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** What subject detection came back with. */
sealed interface SubjectMaskResult {
    /** A usable mask covering the background behind the detected subject. */
    data class Found(val mask: Mask) : SubjectMaskResult

    /** No usable subject. [reason] is written to be shown to the user as-is. */
    data class NotFound(val reason: String) : SubjectMaskResult
}

/**
 * Finds the background behind a photo's subject, as a [Mask] the bokeh layer blurs through.
 *
 * The model ships inside the APK, so this works with no network, no Play Services and no extra
 * permission. It only recognises *people* — the guard below is what stops that limit from turning
 * into a feature that silently blurs the whole picture.
 *
 * The result is an ordinary [Mask], which is the point: detection gives the user a starting
 * boundary, and the brush stays available to fix wherever the model got it wrong.
 */
object SubjectMask {

    private const val TAG = "SubjectMask"

    /**
     * Below this, the model found essentially nobody; above it, it called the whole frame a person.
     * Either way the mask it produced is not worth applying.
     */
    private const val MIN_SUBJECT_FRACTION = 0.02f
    private const val MAX_SUBJECT_FRACTION = 0.98f

    private const val NO_SUBJECT =
        "No subject was found in this photo, so nothing was blurred. Paint the background with " +
            "the brush to blur it by hand."

    /**
     * Detects the subject in [bitmap] and returns a mask covering everything *else*.
     *
     * Call this once, when the layer is created — never from the render path. A segmentation pass
     * costs far more than a blur, and the preview re-renders on every slider tick.
     */
    suspend fun background(
        bitmap: Bitmap,
        columns: Int = Mask.DEFAULT_RESOLUTION,
        rows: Int = Mask.DEFAULT_RESOLUTION,
    ): SubjectMaskResult {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            // The model's own output resolution — around 256 square, which already downsamples
            // cleanly into the coverage grid. Asking for a full-size mask would allocate a float
            // per photo pixel to throw almost all of it away.
            .enableRawSizeMask()
            .build()
        val segmenter = Segmentation.getClient(options)

        val detected = try {
            segmenter.await(bitmap)
        } catch (error: Throwable) {
            Log.e(TAG, "Subject detection failed", error)
            null
        } finally {
            runCatching { segmenter.close() }
        } ?: return SubjectMaskResult.NotFound(
            "Subject detection isn't available on this device. Paint the background with the " +
                "brush to blur it by hand.",
        )

        val foreground = downsample(detected, columns, rows)
            ?: return SubjectMaskResult.NotFound(NO_SUBJECT)

        val fraction = foreground.average().toFloat()
        if (fraction < MIN_SUBJECT_FRACTION || fraction > MAX_SUBJECT_FRACTION) {
            return SubjectMaskResult.NotFound(NO_SUBJECT)
        }

        // The layer blurs where its mask covers, and bokeh blurs the *background* — so the mask is
        // the inverse of what the model reports.
        val coverage = FloatArray(foreground.size) { 1f - foreground[it] }
        return SubjectMaskResult.Found(Mask(columns = columns, rows = rows, coverage = coverage))
    }

    /** Bridges ML Kit's `Task` without pulling in a coroutines-for-Play-Services dependency. */
    private suspend fun Segmenter.await(bitmap: Bitmap): SegmentationMask? =
        suspendCancellableCoroutine { continuation ->
            process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { mask ->
                    if (continuation.isActive) continuation.resume(mask)
                }
                .addOnFailureListener { error ->
                    Log.e(TAG, "Segmentation returned an error", error)
                    if (continuation.isActive) continuation.resume(null)
                }
                .addOnCanceledListener {
                    if (continuation.isActive) continuation.resume(null)
                }
        }

    /**
     * Box-averages the model's confidence map down to the coverage grid.
     *
     * Averaging rather than point-sampling matters: a single sample per cell would alias the
     * subject's outline into a staircase that the mask's feathering can't hide.
     */
    private fun downsample(mask: SegmentationMask, columns: Int, rows: Int): FloatArray? {
        val width = mask.width
        val height = mask.height
        if (width <= 0 || height <= 0 || columns <= 0 || rows <= 0) return null

        val buffer = mask.buffer
        buffer.rewind()
        if (buffer.remaining() < width * height * Float.SIZE_BYTES) return null

        // Read sequentially, the order ML Kit writes the mask in: row-major, one float per pixel.
        val confidence = FloatArray(width * height) { buffer.getFloat() }

        return FloatArray(columns * rows) { index ->
            val column = index % columns
            val row = index / columns
            val left = column * width / columns
            val right = ((column + 1) * width / columns).coerceAtLeast(left + 1).coerceAtMost(width)
            val top = row * height / rows
            val bottom = ((row + 1) * height / rows).coerceAtLeast(top + 1).coerceAtMost(height)

            var total = 0f
            var samples = 0
            for (y in top until bottom) {
                for (x in left until right) {
                    total += confidence[y * width + x]
                    samples++
                }
            }
            if (samples > 0) (total / samples).coerceIn(0f, 1f) else 0f
        }
    }
}
