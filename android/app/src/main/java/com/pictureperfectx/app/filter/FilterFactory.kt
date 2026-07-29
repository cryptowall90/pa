package com.pictureperfectx.app.filter

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageLookupFilter

/** A built GPU pipeline plus the lookup filter (if any) whose intensity can be tuned live. */
class BuiltFilter(
    val filter: GPUImageFilter,
    val lookup: GPUImageLookupFilter?,
)

/**
 * Turns a [Filter] into a live GPUImage pipeline. LUT-backed looks become a [GPUImageLookupFilter]
 * whose intensity (0..1) the app's 0-100 slider drives; Original is a passthrough.
 *
 * Each call returns a fresh instance because a filter is bound to a single GL context — the live
 * preview and the full-resolution capture path each need their own copy.
 */
object FilterFactory {

    private const val TAG = "FilterFactory"

    /** @param intensity 0..1 blend of the LUT against the original frame. */
    fun create(context: Context, filter: Filter, intensity: Float): BuiltFilter {
        val asset = filter.lutAsset ?: return BuiltFilter(GPUImageFilter(), null)
        return try {
            context.assets.open(asset).use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                    ?: return BuiltFilter(GPUImageFilter(), null)
                val lookup = GPUImageLookupFilter(intensity.coerceIn(0f, 1f))
                lookup.setBitmap(bitmap)
                BuiltFilter(lookup, lookup)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load LUT '${filter.lutAsset}' for ${filter.id}", e)
            BuiltFilter(GPUImageFilter(), null)
        }
    }
}
