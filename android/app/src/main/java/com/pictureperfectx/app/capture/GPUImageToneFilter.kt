package com.pictureperfectx.app.capture

import android.opengl.GLES20
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter

/**
 * Blacks / shadows / highlights / whites in one pass.
 *
 * GPUImage ships `GPUImageHighlightShadowFilter`, but it only *lightens* shadows and only *darkens*
 * highlights, so it can't drive sliders that go both ways. This shader weights each adjustment by
 * where a pixel sits in the luminance range, using overlapping bands so the four controls blend
 * into each other instead of banding at their boundaries.
 */
class GPUImageToneFilter(
    private var tone: ToneAdjustments = ToneAdjustments(),
) : GPUImageFilter(NO_FILTER_VERTEX_SHADER, TONE_FRAGMENT_SHADER) {

    private var blacksLocation = 0
    private var shadowsLocation = 0
    private var highlightsLocation = 0
    private var whitesLocation = 0

    override fun onInit() {
        super.onInit()
        blacksLocation = GLES20.glGetUniformLocation(program, "blacks")
        shadowsLocation = GLES20.glGetUniformLocation(program, "shadows")
        highlightsLocation = GLES20.glGetUniformLocation(program, "highlights")
        whitesLocation = GLES20.glGetUniformLocation(program, "whites")
    }

    override fun onInitialized() {
        super.onInitialized()
        setTone(tone)
    }

    fun setTone(tone: ToneAdjustments) {
        this.tone = tone
        setFloat(blacksLocation, tone.normalized(ToneBand.Blacks))
        setFloat(shadowsLocation, tone.normalized(ToneBand.Shadows))
        setFloat(highlightsLocation, tone.normalized(ToneBand.Highlights))
        setFloat(whitesLocation, tone.normalized(ToneBand.Whites))
    }

    private companion object {
        // Weights: blacks/whites ramp in at the ends, shadows/highlights are bell curves centred on
        // the quarter and three-quarter tones. Sigma 0.25 -> the 2*sigma^2 = 0.125 divisor below.
        const val TONE_FRAGMENT_SHADER = """
            varying highp vec2 textureCoordinate;
            uniform sampler2D inputImageTexture;
            uniform mediump float blacks;
            uniform mediump float shadows;
            uniform mediump float highlights;
            uniform mediump float whites;

            void main() {
                lowp vec4 source = texture2D(inputImageTexture, textureCoordinate);
                mediump float luma = dot(source.rgb, vec3(0.299, 0.587, 0.114));

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

                gl_FragColor = vec4(clamp(source.rgb + delta, 0.0, 1.0), source.a);
            }
        """
    }
}
