package com.pictureperfectx.app.camera

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Converts a CameraX [ImageProxy] (YUV_420_888) into a tightly-packed NV21 byte array, which is
 * the format GPUImage's `updatePreviewFrame(byte[], w, h)` expects. Handles arbitrary row/pixel
 * strides so it works across devices that pad rows or interleave the U/V planes.
 */
internal fun ImageProxy.toNv21(): ByteArray {
    val width = width
    val height = height
    val ySize = width * height
    val nv21 = ByteArray(ySize + ySize / 2)

    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    // --- Y plane (copy row by row to strip any row padding) ---
    copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, width, height, nv21, 0)

    // --- Interleave V,U into the NV21 chroma plane ---
    val chromaWidth = width / 2
    val chromaHeight = height / 2
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val uRowStride = uPlane.rowStride
    val vRowStride = vPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    val vPixelStride = vPlane.pixelStride

    var outPos = ySize
    for (row in 0 until chromaHeight) {
        var uPos = row * uRowStride
        var vPos = row * vRowStride
        for (col in 0 until chromaWidth) {
            nv21[outPos++] = vBuffer.get(vPos) // NV21 = Y + V + U interleaved
            nv21[outPos++] = uBuffer.get(uPos)
            uPos += uPixelStride
            vPos += vPixelStride
        }
    }
    return nv21
}

private fun copyPlane(
    buffer: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    width: Int,
    height: Int,
    out: ByteArray,
    offset: Int,
) {
    var outPos = offset
    if (pixelStride == 1 && rowStride == width) {
        buffer.get(out, outPos, width * height)
        return
    }
    val row = ByteArray(rowStride)
    for (r in 0 until height) {
        buffer.position(r * rowStride)
        val remaining = minOf(rowStride, buffer.remaining())
        buffer.get(row, 0, remaining)
        var col = 0
        var i = 0
        while (col < width) {
            out[outPos++] = row[i]
            i += pixelStride
            col++
        }
    }
}
