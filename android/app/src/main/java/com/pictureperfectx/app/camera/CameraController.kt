package com.pictureperfectx.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.pictureperfectx.app.filter.Filter
import com.pictureperfectx.app.filter.FilterCatalog
import com.pictureperfectx.app.filter.FilterFactory
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageLookupFilter

/**
 * Owns the CameraX pipeline.
 *
 *  - The **live preview** is filtered on the GPU by [LutSurfaceProcessor], attached as a
 *    [CameraEffect] on the [Preview] use case and rendered straight into a hardware PreviewView.
 *    No per-frame CPU copy — this is what keeps it stock-camera smooth and instant.
 *  - The **full-resolution still** is rendered through the same look on a detached [captureGpuImage]
 *    (GPUImage offscreen), so the saved photo matches the preview.
 */
class CameraController(context: Context) {

    private val appContext = context.applicationContext

    private val processor = LutSurfaceProcessor(appContext)

    private val captureGpuImage = GPUImage(appContext)
    private var captureLookup: GPUImageLookupFilter? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var boundLifecycleOwner: LifecycleOwner? = null

    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set
    var flashMode: Int = ImageCapture.FLASH_MODE_OFF
        private set

    private var currentFilter: Filter = FilterCatalog.original
    private var intensity: Float = 1f

    /** The PreviewView supplies its surface provider before [bind]. */
    fun setSurfaceProvider(provider: Preview.SurfaceProvider) {
        surfaceProvider = provider
    }

    fun bind(lifecycleOwner: LifecycleOwner) {
        boundLifecycleOwner = lifecycleOwner
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            cameraProvider = future.get()
            rebindUseCases()
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun rebindUseCases() {
        val provider = cameraProvider ?: return
        val owner = boundLifecycleOwner ?: return
        val sp = surfaceProvider ?: return
        provider.unbindAll()

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(sp) }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .build()
        imageCapture = capture

        val effect = CameraEffect(CameraEffect.PREVIEW, processor.executor, processor) { t ->
            Log.e(TAG, "Preview effect error", t)
        }
        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(capture)
            .addEffect(effect)
            .build()

        try {
            provider.bindToLifecycle(owner, selector, group)
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
        }
        // Re-assert the current look on the processor for the fresh binding.
        processor.setLut(currentFilter.lutAsset)
        processor.setIntensity(intensity)
    }

    fun applyFilter(filter: Filter) {
        currentFilter = filter
        processor.setLut(filter.lutAsset)
        val built = FilterFactory.create(appContext, filter, intensity)
        captureLookup = built.lookup
        captureGpuImage.setFilter(built.filter)
    }

    /** Live intensity update (0..100) — preview shader + capture pipeline. */
    fun setIntensity(percent: Int) {
        intensity = percent.coerceIn(0, 100) / 100f
        processor.setIntensity(intensity)
        captureLookup?.setIntensity(intensity)
    }

    fun toggleLens() {
        lensFacing = if (isFront()) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        rebindUseCases()
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
        processor.release()
        cameraProvider?.unbindAll()
    }

    private fun isFront() = lensFacing == CameraSelector.LENS_FACING_FRONT

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
