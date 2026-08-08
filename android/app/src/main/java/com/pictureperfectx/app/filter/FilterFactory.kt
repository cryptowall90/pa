package com.pictureperfectx.app.filter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
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

    /**
     * Decoded LUT bitmaps, keyed by asset path. Each 512x512 lookup costs 1 MB, so the cache is
     * bounded and least-recently-used looks are dropped. GPUImage uploads the bitmap to a GL
     * texture and never recycles it, so evicting only drops our reference — live filters keep
     * working.
     */
    private val lutCache = object : LruCache<String, Bitmap>(CACHE_SIZE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** @param intensity 0..1 blend of the LUT against the original frame. */
    fun create(context: Context, filter: Filter, intensity: Float): BuiltFilter {
        val asset = filter.lutAsset ?: return BuiltFilter(GPUImageFilter(), null)
        val bitmap = lutBitmap(context, asset) ?: return BuiltFilter(GPUImageFilter(), null)
        val lookup = GPUImageLookupFilter(intensity.coerceIn(0f, 1f))
        lookup.setBitmap(bitmap)
        return BuiltFilter(lookup, lookup)
    }

    private fun lutBitmap(context: Context, asset: String): Bitmap? {
        lutCache.get(asset)?.let { cached ->
            if (!cached.isRecycled) return cached
            lutCache.remove(asset)
        }
        return try {
            context.assets.open(asset).use { stream ->
                BitmapFactory.decodeStream(stream)?.also { lutCache.put(asset, it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load LUT '$asset'", e)
            null
        }
    }

    private const val CACHE_SIZE_KB = 16 * 1024 // ~16 LUTs resident
}
