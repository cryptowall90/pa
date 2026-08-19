package com.pictureperfectx.app.layers

import kotlin.math.roundToInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A mask's coverage grid, written small enough to keep.
 *
 * A selection is 256 cells on its long edge — 49,152 floats for a 4:3 photo — and written out as
 * JSON numbers that is over 300KB **per layer**. A saved edit would be bigger than the photo.
 *
 * Two things shrink it. Values are quantized to a byte, which is far finer than the grid's own
 * precision once it is scaled up over the photo; and runs of equal values are collapsed, which is
 * almost all of a mask — a selection is a large flat inside, a large flat outside, and a thin soft
 * edge between them. A full-frame mask comes out as a handful of characters.
 *
 * The wire form is `count x value` pairs separated by spaces, values 0..255: `3072x0 8x140 41x255`.
 * Plain text on purpose — a saved edit that misbehaves can be read with your eyes.
 */
object CoverageCodec {

    /** Collapses [coverage] into run-length pairs against 8-bit values. */
    fun encode(coverage: FloatArray): String {
        if (coverage.isEmpty()) return ""
        val out = StringBuilder()
        var value = quantize(coverage[0])
        var length = 1
        for (index in 1 until coverage.size) {
            val next = quantize(coverage[index])
            if (next == value) {
                length++
                continue
            }
            out.append(length).append('x').append(value).append(' ')
            value = next
            length = 1
        }
        return out.append(length).append('x').append(value).toString()
    }

    /**
     * Expands what [encode] wrote.
     *
     * Sizes the array from the counts before filling it: an `ArrayList<Float>` would box fifty
     * thousand values on the way to a `FloatArray` that was always going to be a known length.
     */
    fun decode(text: String): FloatArray {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return FloatArray(0)

        val runs = trimmed.split(' ')
        var total = 0
        runs.forEach { total += lengthOf(it) }

        val coverage = FloatArray(total)
        var index = 0
        runs.forEach { run ->
            val separator = run.indexOf('x')
            val length = lengthOf(run)
            val value = run.substring(separator + 1).toIntOrNull()
                ?: throw SerializationException("Coverage run '$run' has no value")
            val level = value.coerceIn(0, 255) / 255f
            repeat(length) { coverage[index++] = level }
        }
        return coverage
    }

    private fun lengthOf(run: String): Int {
        val separator = run.indexOf('x')
        if (separator <= 0) throw SerializationException("Coverage run '$run' is not count x value")
        val length = run.substring(0, separator).toIntOrNull()
            ?: throw SerializationException("Coverage run '$run' has no count")
        if (length <= 0) throw SerializationException("Coverage run '$run' covers nothing")
        return length
    }

    private fun quantize(value: Float) = (value.coerceIn(0f, 1f) * 255f).roundToInt()
}

/** Wires [CoverageCodec] into the serializer, so a mask is one string rather than an array. */
object CoverageSerializer : KSerializer<FloatArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Coverage", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: FloatArray) =
        encoder.encodeString(CoverageCodec.encode(value))

    override fun deserialize(decoder: Decoder): FloatArray =
        CoverageCodec.decode(decoder.decodeString())
}
