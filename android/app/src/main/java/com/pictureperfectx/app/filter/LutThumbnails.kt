package com.pictureperfectx.app.filter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/**
 * Renders per-thumbnail LUT previews on the CPU, on-device. Given a small live camera snapshot and
 * a [Filter], it applies the filter's 512x512 GPUImage lookup table with the exact nearest-neighbor
 * mapping the GPU shader uses. Fast enough to run for the visible thumbnails on every snapshot.
 *
 * Decoded LUTs are cached as raw pixel arrays (an LRU capped at [MAX_CACHED_LUTS]) so refreshing the
 * previews when the scene changes never re-decodes a PNG.
 */
object LutThumbnails {

    private const val MAX_CACHED_LUTS = 16
    private const val LUT_DIM = 512
    private const val CUBE = 64 // 64^3, 8x8 tiles of 64x64

    private val lutCache = object : LruCache<String, IntArray>(MAX_CACHED_LUTS) {}

    /** Returns [source] graded by [filter]. For Original, returns [source] unchanged. */
    fun render(context: Context, filter: Filter, source: Bitmap): Bitmap {
        val asset = filter.lutAsset ?: return source
        val lut = lutPixels(context, filter.id, asset) ?: return source

        val w = source.width
        val h = source.height
        val px = IntArray(w * h)
        source.getPixels(px, 0, w, 0, 0, w, h)

        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val bi = b * (CUBE - 1) / 255
            val qx = bi % 8
            val qy = bi / 8
            val lx = qx * CUBE + r * (CUBE - 1) / 255
            val ly = qy * CUBE + g * (CUBE - 1) / 255
            // Preserve source alpha; take RGB from the LUT.
            px[i] = (c and 0xFF000000.toInt()) or (lut[ly * LUT_DIM + lx] and 0x00FFFFFF)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun lutPixels(context: Context, id: String, asset: String): IntArray? {
        lutCache.get(id)?.let { return it }
        return try {
            val bmp = context.assets.open(asset).use { BitmapFactory.decodeStream(it) } ?: return null
            val arr = IntArray(LUT_DIM * LUT_DIM)
            bmp.getPixels(arr, 0, LUT_DIM, 0, 0, LUT_DIM, LUT_DIM)
            bmp.recycle()
            lutCache.put(id, arr)
            arr
        } catch (e: Exception) {
            null
        }
    }
}
