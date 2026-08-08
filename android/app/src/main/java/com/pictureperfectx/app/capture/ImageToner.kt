package com.pictureperfectx.app.capture

import android.content.Context
import android.graphics.Bitmap
import jp.co.cyberagent.android.gpuimage.GPUImage

/**
 * Applies [ToneAdjustments] to a still, off-screen on the GPU. Stateless and safe to call from a
 * background thread; the source is never modified.
 */
object ImageToner {

    fun apply(context: Context, source: Bitmap, tone: ToneAdjustments): Bitmap {
        // Neutral sliders shouldn't cost a GPU round trip, or a needless bitmap copy.
        if (tone.isNeutral) return source
        val gpu = GPUImage(context.applicationContext)
        gpu.setFilter(GPUImageToneFilter(tone))
        return gpu.getBitmapWithFilterApplied(source)
    }
}
