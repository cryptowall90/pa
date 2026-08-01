package com.pictureperfectx.app.camera

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Converts a CameraX [ImageProxy] (YUV_420_888) into tightly-packed NV21 — the format GPUImage's
 * `updatePreviewFrame(byte[], w, h)` expects — writing into the caller-provided [out] buffer so the
 * hot preview path allocates nothing per frame. Handles arbitrary row/pixel strides so it works
 * across devices that pad rows or interleave the U/V planes.
 *
 * [out] must be at least width * height * 3 / 2 bytes.
 */
internal fun ImageProxy.fillNv21(out: ByteArray) {
    val width = width
    val height = height
    val ySize = width * height

    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    // --- Y plane (copy row by row to strip any row padding) ---
    copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, width, height, out, 0)

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
            out[outPos++] = vBuffer.get(vPos) // NV21 = Y + V + U interleaved
            out[outPos++] = uBuffer.get(uPos)
            uPos += uPixelStride
            vPos += vPixelStride
        }
    }
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
