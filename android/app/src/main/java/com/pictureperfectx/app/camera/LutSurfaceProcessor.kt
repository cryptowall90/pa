package com.pictureperfectx.app.camera

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * A CameraX [SurfaceProcessor] that renders the live preview entirely on the GPU and applies the
 * selected LUT in a fragment shader.
 *
 * Camera frames arrive as an OES external texture (no CPU copy), get sampled + graded by the shader,
 * and are drawn straight to the PreviewView's output surface. This is what makes the filtered
 * viewfinder as smooth and instant as the stock camera — the old path copied every frame through
 * the CPU into GPUImage, which caused the lag and the "preview only starts after a filter tap".
 *
 * All GL work happens on a single [glThread]; public setters just post onto it.
 */
class LutSurfaceProcessor(private val appContext: Context) : SurfaceProcessor {

    private val glThread = HandlerThread("PPX-GL").apply { start() }
    private val glHandler = Handler(glThread.looper)

    /** Executor CameraX uses to deliver processor callbacks — the GL thread, so callbacks touch GL safely. */
    val executor: Executor = Executor { glHandler.post(it) }

    // EGL
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var setupSurface: EGLSurface = EGL14.EGL_NO_SURFACE // 1x1 pbuffer for context-current w/o output

    // Input (camera)
    private var oesTexId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    private val mvpTexMatrix = FloatArray(16)

    // Output (PreviewView)
    private var surfaceOutput: SurfaceOutput? = null
    private var outputEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var outWidth = 0
    private var outHeight = 0

    // Program
    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var uIntensity = 0
    private var uApplyLut = 0
    private var uCamera = 0
    private var uLut = 0

    private var lutTexId = 0
    private var intensity = 1f
    private var applyLut = 0f

    init {
        glHandler.post { initGl() }
    }

    // ---- Public API (thread-safe; posts to GL thread) ------------------------------------------

    /** Load [assetPath] as the active preview LUT, or null for the unfiltered passthrough. */
    fun setLut(assetPath: String?) {
        glHandler.post {
            if (assetPath == null) {
                applyLut = 0f
                return@post
            }
            try {
                appContext.assets.open(assetPath).use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream) ?: return@use
                    makeCurrent(if (outputEglSurface != EGL14.EGL_NO_SURFACE) outputEglSurface else setupSurface)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                    bmp.recycle()
                    applyLut = 1f
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load preview LUT '$assetPath'", e)
                applyLut = 0f
            }
        }
    }

    /** 0..1 blend of the LUT against the original camera image. */
    fun setIntensity(value: Float) {
        glHandler.post { intensity = value.coerceIn(0f, 1f) }
    }

    fun release() {
        glHandler.post { releaseGl() }
        glThread.quitSafely()
    }

    // ---- SurfaceProcessor callbacks (run on the GL thread via [executor]) -----------------------

    override fun onInputSurface(request: SurfaceRequest) {
        val res = request.resolution
        // Fresh OES texture per input surface so a lens-switch rebind never aliases two
        // SurfaceTextures onto the same texture id.
        makeCurrent(setupSurface)
        val texId = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        oesTexId = texId
        val st = SurfaceTexture(texId).apply {
            setDefaultBufferSize(res.width, res.height)
            setOnFrameAvailableListener({ glHandler.post { drawFrame() } }, glHandler)
        }
        surfaceTexture = st
        val surface = Surface(st)
        request.provideSurface(surface, executor) {
            surface.release()
            st.release()
            makeCurrent(setupSurface)
            GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
            if (surfaceTexture === st) surfaceTexture = null
            if (oesTexId == texId) oesTexId = 0
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        // Drop any previous output.
        releaseOutput()
        surfaceOutput = output
        val surface = output.getSurface(executor) {
            glHandler.post { releaseOutput() }
        }
        outputEglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        outWidth = querySurface(outputEglSurface, EGL14.EGL_WIDTH)
        outHeight = querySurface(outputEglSurface, EGL14.EGL_HEIGHT)
    }

    // ---- GL ------------------------------------------------------------------------------------

    private fun drawFrame() {
        val st = surfaceTexture ?: return
        val output = surfaceOutput
        if (output == null || outputEglSurface == EGL14.EGL_NO_SURFACE) {
            // No output yet: still consume the frame so the camera isn't back-pressured.
            makeCurrent(setupSurface)
            runCatching { st.updateTexImage() }
            return
        }
        makeCurrent(outputEglSurface)
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        output.updateTransformMatrix(mvpTexMatrix, texMatrix)

        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glUniform1i(uCamera, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
        GLES20.glUniform1i(uLut, 1)

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, mvpTexMatrix, 0)
        GLES20.glUniform1f(uIntensity, intensity)
        GLES20.glUniform1f(uApplyLut, applyLut)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quadVertices)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)

        EGL14.eglSwapBuffers(eglDisplay, outputEglSurface)
    }

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfig, 0)
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        setupSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        makeCurrent(setupSurface)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uIntensity = GLES20.glGetUniformLocation(program, "uIntensity")
        uApplyLut = GLES20.glGetUniformLocation(program, "uApplyLut")
        uCamera = GLES20.glGetUniformLocation(program, "sCamera")
        uLut = GLES20.glGetUniformLocation(program, "sLut")

        // The OES (camera) texture is created per input surface in onInputSurface.
        // NEAREST on the LUT: linear filtering bleeds across the 8x8 tile boundaries of the lookup
        // image and produces wrong high-chroma (teal) colors. Blue-axis smoothness still comes from
        // the shader's two-slice blend.
        lutTexId = createTexture(GLES20.GL_TEXTURE_2D, GLES20.GL_NEAREST)
        // A 1x1 dummy so the LUT sampler is always valid (ignored while uApplyLut == 0).
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
        val dummy = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).put(
            byteArrayOf(0, 0, 0, 255.toByte()),
        )
        dummy.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, dummy,
        )
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    private fun createTexture(target: Int, filter: Int = GLES20.GL_LINEAR): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun makeCurrent(surface: EGLSurface) {
        EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)
    }

    private fun querySurface(surface: EGLSurface, what: Int): Int {
        val value = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, surface, what, value, 0)
        return value[0]
    }

    private fun releaseOutput() {
        if (outputEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, outputEglSurface)
            outputEglSurface = EGL14.EGL_NO_SURFACE
        }
        surfaceOutput?.close()
        surfaceOutput = null
    }

    private fun releaseGl() {
        releaseOutput()
        surfaceTexture?.release()
        surfaceTexture = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            if (setupSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, setupSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("Program link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }

    companion object {
        private const val TAG = "LutSurfaceProcessor"

        private val quadVertices: FloatBuffer = floatBuffer(
            floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
        )
        private val quadTexCoords: FloatBuffer = floatBuffer(
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
        )

        private fun floatBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(data); position(0) }

        private const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        // Camera comes in as an OES external texture; LUT applied with GPUImage's 8x8x64 lookup layout.
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sCamera;
            uniform sampler2D sLut;
            uniform float uIntensity;
            uniform float uApplyLut;
            void main() {
                vec4 cam = texture2D(sCamera, vTexCoord);
                float blueColor = cam.b * 63.0;
                vec2 quad1;
                quad1.y = floor(floor(blueColor) / 8.0);
                quad1.x = floor(blueColor) - (quad1.y * 8.0);
                vec2 quad2;
                quad2.y = floor(ceil(blueColor) / 8.0);
                quad2.x = ceil(blueColor) - (quad2.y * 8.0);
                vec2 t1;
                t1.x = (quad1.x * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * cam.r);
                t1.y = (quad1.y * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * cam.g);
                vec2 t2;
                t2.x = (quad2.x * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * cam.r);
                t2.y = (quad2.y * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * cam.g);
                vec4 n1 = texture2D(sLut, t1);
                vec4 n2 = texture2D(sLut, t2);
                vec3 graded = mix(n1.rgb, n2.rgb, fract(blueColor));
                gl_FragColor = vec4(mix(cam.rgb, graded, uIntensity * uApplyLut), 1.0);
            }
        """
    }
}
