package com.pictureperfectx.app.capture

import android.opengl.GLES20
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter

/**
 * Every adjustment a layer can make, in one pass.
 *
 * GPUImage ships separate filters for exposure, contrast, saturation, vibrance and white balance,
 * and `GPUImageHighlightShadowFilter` for two of the bands — but chaining nine of them would mean
 * nine GPU round trips per layer on top of a composite that already costs a full-image pass each.
 * One shader keeps a layer to one pass however many of its sliders are moved.
 *
 * The bands also need a shader of their own regardless: GPUImage's only *lightens* shadows and only
 * *darkens* highlights, so it can't drive sliders that go both ways.
 */
class GPUImageToneFilter(
    private var tone: ToneAdjustments = ToneAdjustments(),
) : GPUImageFilter(NO_FILTER_VERTEX_SHADER, TONE_FRAGMENT_SHADER) {

    private val locations = HashMap<ToneBand, Int>()

    override fun onInit() {
        super.onInit()
        ToneBand.entries.forEach { band ->
            locations[band] = GLES20.glGetUniformLocation(program, band.uniform)
        }
    }

    override fun onInitialized() {
        super.onInitialized()
        setTone(tone)
    }

    fun setTone(tone: ToneAdjustments) {
        this.tone = tone
        ToneBand.entries.forEach { band ->
            locations[band]?.let { setFloat(it, tone.normalized(band)) }
        }
    }

    private companion object {
        /** The uniform each band drives, named for it so the two can't drift apart. */
        val ToneBand.uniform: String get() = name.lowercase()

        // Weights: blacks/whites ramp in at the ends, shadows/highlights are bell curves centred on
        // the quarter and three-quarter tones. Sigma 0.25 -> the 2*sigma^2 = 0.125 divisor below.
        const val TONE_FRAGMENT_SHADER = """
            varying highp vec2 textureCoordinate;
            uniform sampler2D inputImageTexture;
            uniform mediump float exposure;
            uniform mediump float contrast;
            uniform mediump float blacks;
            uniform mediump float shadows;
            uniform mediump float highlights;
            uniform mediump float whites;
            uniform mediump float saturation;
            uniform mediump float vibrance;
            uniform mediump float warmth;

            const mediump vec3 LUMA = vec3(0.299, 0.587, 0.114);

            void main() {
                lowp vec4 source = texture2D(inputImageTexture, textureCoordinate);
                mediump vec3 rgb = source.rgb;

                // Exposure first, in stops, so everything after judges a corrected image.
                rgb = clamp(rgb * pow(2.0, exposure * 2.0), 0.0, 1.0);

                mediump float luma = dot(rgb, LUMA);
                mediump float blackWeight = 1.0 - smoothstep(0.0, 0.35, luma);
                mediump float whiteWeight = smoothstep(0.65, 1.0, luma);
                mediump float shadowWeight = exp(-((luma - 0.25) * (luma - 0.25)) / 0.125);
                mediump float highlightWeight = exp(-((luma - 0.75) * (luma - 0.75)) / 0.125);

                mediump float delta = 0.5 * (
                    blacks * blackWeight +
                    shadows * shadowWeight +
                    highlights * highlightWeight +
                    whites * whiteWeight
                );
                rgb = clamp(rgb + delta, 0.0, 1.0);

                // Contrast pivots on mid grey, so it stretches rather than brightens.
                rgb = clamp((rgb - 0.5) * (1.0 + contrast) + 0.5, 0.0, 1.0);

                // Saturation moves everything; vibrance leans on the muted colours and leaves the
                // already-saturated ones alone, which is what stops skin going lurid.
                mediump float grey = dot(rgb, LUMA);
                mediump float chroma =
                    max(rgb.r, max(rgb.g, rgb.b)) - min(rgb.r, min(rgb.g, rgb.b));
                mediump float amount =
                    clamp(1.0 + saturation + vibrance * (1.0 - chroma), 0.0, 3.0);
                rgb = clamp(mix(vec3(grey), rgb, amount), 0.0, 1.0);

                // Warmth trades blue for red the way a white balance slider does.
                rgb.r = clamp(rgb.r + warmth * 0.15, 0.0, 1.0);
                rgb.b = clamp(rgb.b - warmth * 0.15, 0.0, 1.0);

                gl_FragColor = vec4(rgb, source.a);
            }
        """
    }
}
