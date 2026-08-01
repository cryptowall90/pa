package com.pictureperfectx.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.pictureperfectx.app.filter.Filter
import com.pictureperfectx.app.filter.FilterFactory
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageLookupFilter
import jp.co.cyberagent.android.gpuimage.util.Rotation
import java.util.concurrent.Executors

/**
 * Owns the CameraX <-> GPUImage bridge.
 *
 * Two GPUImage instances by design:
 *  - [previewGpuImage] is attached to a [GLSurfaceView] and fed live NV21 frames from an
 *    [ImageAnalysis] use case. This is what the user sees, filtered in real time.
 *  - [captureGpuImage] is detached and only used to run the full-resolution still through the
 *    exact same filter. Running an offscreen render on the surface-attached instance is unsafe,
 *    so capture gets its own context.
 *
 * A separate [ImageCapture] use case grabs the full-resolution frame on shutter.
 */
class CameraController(context: Context) {

    private val appContext = context.applicationContext
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val previewGpuImage = GPUImage(appContext)
    private val captureGpuImage = GPUImage(appContext)

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var boundLifecycleOwner: LifecycleOwner? = null

    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set
    var flashMode: Int = ImageCapture.FLASH_MODE_OFF
        private set

    private var currentFilter: Filter = com.pictureperfectx.app.filter.FilterCatalog.original

    // 0..1 LUT blend, controlled live by the app's 0-100 intensity slider.
    private var intensity: Float = 1f
    private var previewLookup: GPUImageLookupFilter? = null
    private var captureLookup: GPUImageLookupFilter? = null

    // Reused across frames so the preview path doesn't allocate a fresh NV21 buffer 30x/sec
    // (that per-frame garbage was a major source of GC-induced stutter).
    private var nv21Buffer: ByteArray? = null

    init {
        previewGpuImage.setScaleType(GPUImage.ScaleType.CENTER_CROP)
    }

    /** Wire the GL surface that renders the live, filtered preview. */
    fun attachGlSurface(surfaceView: GLSurfaceView) {
        previewGpuImage.setGLSurfaceView(surfaceView)
        applyFilter(currentFilter)
    }

    /** Bind CameraX use cases to [lifecycleOwner]; safe to call repeatedly (e.g. on lens switch). */
    fun bind(lifecycleOwner: LifecycleOwner) {
        boundLifecycleOwner = lifecycleOwner
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            rebindUseCases(provider, lifecycleOwner)
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun rebindUseCases(provider: ProcessCameraProvider, lifecycleOwner: LifecycleOwner) {
        provider.unbindAll()

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, ::onFrame) }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .build()
        imageCapture = capture

        try {
            provider.bindToLifecycle(lifecycleOwner, selector, analysis, capture)
            previewGpuImage.setRotation(rotationForLens(), isFront(), false)
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
        }
    }

    private fun onFrame(image: ImageProxy) {
        try {
            val needed = image.width * image.height * 3 / 2
            val buffer = nv21Buffer?.takeIf { it.size == needed } ?: ByteArray(needed).also { nv21Buffer = it }
            image.fillNv21(buffer)
            previewGpuImage.updatePreviewFrame(buffer, image.width, image.height)
        } catch (e: Exception) {
            Log.e(TAG, "Frame conversion failed", e)
        } finally {
            image.close()
        }
    }

    fun applyFilter(filter: Filter) {
        currentFilter = filter
        // Distinct instances: one per GL context. Keep the lookup refs for live intensity control.
        val preview = FilterFactory.create(appContext, filter, intensity)
        val capture = FilterFactory.create(appContext, filter, intensity)
        previewLookup = preview.lookup
        captureLookup = capture.lookup
        previewGpuImage.setFilter(preview.filter)
        captureGpuImage.setFilter(capture.filter)
    }

    /** Live intensity update (0..100). Applies to the current LUT without rebuilding the pipeline. */
    fun setIntensity(percent: Int) {
        intensity = (percent.coerceIn(0, 100)) / 100f
        previewLookup?.setIntensity(intensity)
        captureLookup?.setIntensity(intensity)
        previewGpuImage.requestRender()
    }

    fun toggleLens() {
        lensFacing = if (isFront()) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        cameraProvider?.let { provider ->
            boundLifecycleOwner?.let { owner -> rebindUseCases(provider, owner) }
        }
    }

    /** Cycles OFF -> ON -> AUTO. Returns the new mode. */
    fun cycleFlash(): Int {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        return flashMode
    }

    /**
     * Captures a full-resolution still, renders it through the selected filter, and returns the
     * finished [Bitmap] on the main thread via [onResult]. [onFailure] fires on failure.
     */
    fun capture(onResult: (Bitmap, Filter) -> Unit, onFailure: (Throwable) -> Unit) {
        val capture = imageCapture ?: return onFailure(IllegalStateException("Camera not ready"))
        capture.takePicture(
            ContextCompat.getMainExecutor(appContext),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val oriented = image.toOrientedBitmap(isFront())
                        val filtered = captureGpuImage.getBitmapWithFilterApplied(oriented)
                        onResult(filtered, currentFilter)
                    } catch (e: Exception) {
                        onFailure(e)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) = onFailure(exception)
            },
        )
    }

    fun release() {
        analysisExecutor.shutdown()
        cameraProvider?.unbindAll()
    }

    private fun isFront() = lensFacing == CameraSelector.LENS_FACING_FRONT

    private fun rotationForLens(): Rotation =
        if (isFront()) Rotation.ROTATION_270 else Rotation.ROTATION_90

    companion object {
        private const val TAG = "CameraController"
    }
}

/** Decodes the JPEG [ImageProxy] and applies its EXIF rotation (and front-camera mirroring). */
private fun ImageProxy.toOrientedBitmap(mirror: Boolean): Bitmap {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val degrees = imageInfo.rotationDegrees
    if (degrees == 0 && !mirror) return decoded
    val matrix = Matrix().apply {
        postRotate(degrees.toFloat())
        if (mirror) postScale(-1f, 1f)
    }
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
}
