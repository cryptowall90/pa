package com.pictureperfectx.app.filter

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.util.Log
import jp.co.cyberagent.android.gpuimage.filter.GPUImageBrightnessFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageContrastFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup
import jp.co.cyberagent.android.gpuimage.filter.GPUImageGrayscaleFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageLookupFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSaturationFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageVignetteFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageWhiteBalanceFilter

/**
 * Turns a declarative [Filter] into a live GPUImage pipeline.
 *
 * Every call returns a fresh [GPUImageFilter] instance because a filter is bound to a single GL
 * context — the live preview and the full-resolution capture path each need their own copy.
 *
 * Resolution order for a look:
 *  1. If [Filter.lutAsset] exists in `assets/`, apply it as a real 3D lookup table.
 *  2. Otherwise build a parametric approximation so the app ships with distinct looks and no
 *     binary assets. Replace these with real LUT PNGs at any time — no code change needed.
 */
object FilterFactory {

    private const val TAG = "FilterFactory"

    fun create(context: Context, filter: Filter): GPUImageFilter {
        loadLut(context, filter)?.let { return it }
        return when (filter.id) {
            "fujifilm" -> group(
                GPUImageContrastFilter(1.08f),
                GPUImageSaturationFilter(1.28f),
                GPUImageWhiteBalanceFilter(4800f, 6f), // gentle warm cast
            )
            "leica" -> group(
                GPUImageContrastFilter(1.22f),
                GPUImageSaturationFilter(0.82f),
                vignette(0.62f, 0.85f),
            )
            "polaroid" -> group(
                GPUImageBrightnessFilter(0.06f),   // lifted blacks / faded look
                GPUImageContrastFilter(0.92f),
                GPUImageWhiteBalanceFilter(5200f, 18f),
                vignette(0.5f, 0.78f),
            )
            "monochrome" -> group(
                GPUImageGrayscaleFilter(),
                GPUImageContrastFilter(1.15f),
            )
            else -> GPUImageFilter() // Original: passthrough
        }
    }

    private fun loadLut(context: Context, filter: Filter): GPUImageFilter? {
        val asset = filter.lutAsset ?: return null
        return try {
            context.assets.open(asset).use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream) ?: return null
                GPUImageLookupFilter().apply { setBitmap(bitmap) }
            }
        } catch (e: Exception) {
            // Missing LUT is expected until real assets are added — fall back silently to parametric.
            Log.d(TAG, "No LUT asset '$asset', using parametric look for ${filter.id}")
            null
        }
    }

    private fun group(vararg filters: GPUImageFilter): GPUImageFilterGroup =
        GPUImageFilterGroup(filters.toList())

    private fun vignette(start: Float, end: Float): GPUImageVignetteFilter =
        GPUImageVignetteFilter(PointF(0.5f, 0.5f), floatArrayOf(0f, 0f, 0f), start, end)
}
