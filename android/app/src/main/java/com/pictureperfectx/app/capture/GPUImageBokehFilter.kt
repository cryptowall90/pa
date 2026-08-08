package com.pictureperfectx.app.capture

import android.opengl.GLES20
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter

/**
 * Defocus blur that reads like a lens rather than a smear.
 *
 * A gaussian averages every neighbour by distance, which turns a bright point into a soft grey
 * smudge. A real lens spreads it across the shape of its aperture, so highlights stay bright and
 * come out as discs — the "bokeh balls" that make a photo look shot wide open. This shader samples
 * a **disc**, not a bell curve, and weights bright samples up before averaging so specular points
 * bloom instead of being diluted by the dark pixels around them.
 *
 * One pass, because a disc kernel isn't separable the way a gaussian is.
 */
class GPUImageBokehFilter(
    radius: Float = DEFAULT_RADIUS,
) : GPUImageFilter(NO_FILTER_VERTEX_SHADER, BOKEH_FRAGMENT_SHADER) {

    private var radius: Float = radius.coerceAtLeast(0f)

    private var radiusLocation = 0
    private var texelSizeLocation = 0

    override fun onInit() {
        super.onInit()
        radiusLocation = GLES20.glGetUniformLocation(program, "blurRadius")
        texelSizeLocation = GLES20.glGetUniformLocation(program, "texelSize")
    }

    override fun onInitialized() {
        super.onInitialized()
        setRadius(radius)
        pushTexelSize()
    }

    override fun onOutputSizeChanged(width: Int, height: Int) {
        super.onOutputSizeChanged(width, height)
        pushTexelSize()
    }

    fun setRadius(radius: Float) {
        this.radius = radius.coerceAtLeast(0f)
        if (isInitialized) setFloat(radiusLocation, this.radius)
    }

    /**
     * The kernel is specified in pixels, so it needs the texture's pixel size to step in texture
     * coordinates — otherwise the same radius would blur a wide photo less than a tall one.
     */
    private fun pushTexelSize() {
        if (!isInitialized) return
        val width = outputWidth
        val height = outputHeight
        if (width <= 0 || height <= 0) return
        setFloatVec2(texelSizeLocation, floatArrayOf(1f / width, 1f / height))
    }

    companion object {
        const val DEFAULT_RADIUS = 25f

        // GLSL ES 2.0 only allows loops with constant bounds, so the tap count is fixed: three
        // rings of ten, offset ring by ring so the samples land on a disc rather than on spokes.
        private const val BOKEH_FRAGMENT_SHADER = """
            varying highp vec2 textureCoordinate;
            uniform sampler2D inputImageTexture;
            uniform highp float blurRadius;
            uniform highp vec2 texelSize;

            const int RINGS = 3;
            const int SAMPLES = 10;
            const highp float TAU = 6.28318530718;

            // How much brighter a highlight counts than a mid-tone. Without this the discs average
            // away against their darker surroundings and the blur goes flat.
            const highp float BLOOM = 4.0;
            const highp float BLOOM_FLOOR = 0.65;

            highp float weightOf(lowp vec3 colour) {
                highp float luma = dot(colour, vec3(0.299, 0.587, 0.114));
                return 1.0 + BLOOM * smoothstep(BLOOM_FLOOR, 1.0, luma);
            }

            void main() {
                lowp vec4 source = texture2D(inputImageTexture, textureCoordinate);

                if (blurRadius < 0.5) {
                    gl_FragColor = source;
                    return;
                }

                highp float weight = weightOf(source.rgb);
                highp vec3 total = source.rgb * weight;
                highp float totalWeight = weight;

                for (int ring = 1; ring <= RINGS; ring++) {
                    highp float scale = float(ring) / float(RINGS);
                    for (int tap = 0; tap < SAMPLES; tap++) {
                        // The half-ring rotation staggers each ring so the taps don't line up.
                        highp float angle =
                            (float(tap) + 0.5 * float(ring)) * (TAU / float(SAMPLES));
                        highp vec2 offset =
                            vec2(cos(angle), sin(angle)) * scale * blurRadius * texelSize;
                        lowp vec3 sampled =
                            texture2D(inputImageTexture, textureCoordinate + offset).rgb;
                        highp float sampleWeight = weightOf(sampled);
                        total += sampled * sampleWeight;
                        totalWeight += sampleWeight;
                    }
                }

                gl_FragColor = vec4(total / totalWeight, source.a);
            }
        """
    }
}
