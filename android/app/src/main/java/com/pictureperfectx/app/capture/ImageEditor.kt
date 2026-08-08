package com.pictureperfectx.app.capture

import android.content.Context
import android.graphics.Bitmap
import com.pictureperfectx.app.filter.Filter
import com.pictureperfectx.app.filter.FilterFactory
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageBrightnessFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageContrastFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSaturationFilter

/**
 * Applies a look ([Filter] + intensity) and tone adjustments to a still [Bitmap] off-screen via
 * GPUImage. Shared by the editor screen (preview + save) and reusable anywhere a one-off render is
 * needed. Stateless and thread-safe to call from a background thread.
 */
object ImageEditor {

    /** All percent inputs are -100..100 (0 neutral); intensity is 0..100. */
    fun render(
        context: Context,
        source: Bitmap,
        filter: Filter,
        intensity: Int,
        brightness: Int,
        contrast: Int,
        saturation: Int,
    ): Bitmap {
        val gpu = GPUImage(context.applicationContext)
        val filters = ArrayList<GPUImageFilter>()
        FilterFactory.create(context, filter, intensity.coerceIn(0, 100) / 100f).lookup
            ?.let { filters.add(it) }
        filters.add(GPUImageBrightnessFilter(brightness.coerceIn(-100, 100) / 200f))
        filters.add(GPUImageContrastFilter(1f + contrast.coerceIn(-100, 100) / 100f))
        filters.add(GPUImageSaturationFilter(1f + saturation.coerceIn(-100, 100) / 100f))
        gpu.setFilter(GPUImageFilterGroup(filters))
        return gpu.getBitmapWithFilterApplied(source)
    }
}
