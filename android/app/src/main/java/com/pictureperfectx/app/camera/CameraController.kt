package com.pictureperfectx.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import android.util.Range
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import com.pictureperfectx.app.filter.Filter
import com.pictureperfectx.app.filter.FilterCatalog
import com.pictureperfectx.app.filter.FilterFactory
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageBrightnessFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageContrastFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup
import jp.co.cyberagent.android.gpuimage.filter.GPUImageLookupFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSaturationFilter
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Owns the CameraX pipeline.
 *
 *  - The **live preview** is filtered on the GPU by [LutSurfaceProcessor], attached as a
 *    [CameraEffect] on the [Preview] use case and rendered straight into a hardware PreviewView.
 *  - The **full-resolution still** is rendered through the same look + tone adjustments on a
 *    detached [captureGpuImage] (GPUImage offscreen), on a background thread so the shutter never
 *    blocks the UI.
 *  - Focus, zoom and exposure go through the bound [camera]'s CameraControl.
 */
class CameraController(context: Context) {

    private val appContext = context.applicationContext
    // Full-res capture processing runs here so pressing the shutter doesn't stall the main thread.
    private val captureExecutor = Executors.newSingleThreadExecutor()

    private val processor = LutSurfaceProcessor(appContext)

    private val captureGpuImage = GPUImage(appContext)
    private var captureLookup: GPUImageLookupFilter? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var boundLifecycleOwner: LifecycleOwner? = null

    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set
    var flashMode: Int = ImageCapture.FLASH_MODE_OFF
        private set

    private var currentFilter: Filter = FilterCatalog.original
    private var intensity: Float = 1f

    // Post-LUT tone adjustments (shared by preview shader + capture pipeline).
    private var brightnessF = 0f // additive, [-0.5, 0.5]
    private var contrastF = 1f    // [0, 2], 1 = unchanged
    private var saturationF = 1f  // [0, 2], 1 = unchanged

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
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flashMode)
            .build()
        imageCapture = capture

        val effect = PreviewLutEffect(processor.executor, processor)
        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(capture)
            .addEffect(effect)
            .build()

        try {
            camera = provider.bindToLifecycle(owner, selector, group)
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
        }
        // Re-assert the current look + adjustments on the processor for the fresh binding.
        processor.setLut(currentFilter.lutAsset)
        processor.setIntensity(intensity)
        processor.setBrightness(brightnessF)
        processor.setContrast(contrastF)
        processor.setSaturation(saturationF)
    }

    fun applyFilter(filter: Filter) {
        currentFilter = filter
        processor.setLut(filter.lutAsset)
        rebuildCaptureFilter()
    }

    /** Live intensity update (0..100) — preview shader + capture pipeline. */
    fun setIntensity(percent: Int) {
        intensity = percent.coerceIn(0, 100) / 100f
        processor.setIntensity(intensity)
        captureLookup?.setIntensity(intensity)
    }

    // ---- Tone adjustments (percent -100..100, 0 = neutral) -------------------------------------

    fun setBrightness(percent: Int) {
        brightnessF = percent.coerceIn(-100, 100) / 200f
        processor.setBrightness(brightnessF)
        rebuildCaptureFilter()
    }

    fun setContrast(percent: Int) {
        contrastF = 1f + percent.coerceIn(-100, 100) / 100f
        processor.setContrast(contrastF)
        rebuildCaptureFilter()
    }

    fun setSaturation(percent: Int) {
        saturationF = 1f + percent.coerceIn(-100, 100) / 100f
        processor.setSaturation(saturationF)
        rebuildCaptureFilter()
    }

    /** Rebuild the capture filter chain: LUT (if any) then brightness/contrast/saturation. */
    private fun rebuildCaptureFilter() {
        val built = FilterFactory.create(appContext, currentFilter, intensity)
        captureLookup = built.lookup
        val filters = ArrayList<GPUImageFilter>()
        built.lookup?.let { filters.add(it) }
        filters.add(GPUImageBrightnessFilter(brightnessF))
        filters.add(GPUImageContrastFilter(contrastF))
        filters.add(GPUImageSaturationFilter(saturationF))
        captureGpuImage.setFilter(GPUImageFilterGroup(filters))
    }

    // ---- Camera control: focus / zoom / exposure ----------------------------------------------

    fun focusAndMeter(point: MeteringPoint) {
        val cam = camera ?: return
        try {
            cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
        } catch (e: Exception) {
            Log.e(TAG, "Focus/metering failed", e)
        }
    }

    /** Multiply the current zoom ratio by [factor] (from a pinch gesture), clamped to device limits. */
    fun scaleZoom(factor: Float) {
        val cam = camera ?: return
        val state = cam.cameraInfo.zoomState.value ?: return
        val target = (state.zoomRatio * factor).coerceIn(state.minZoomRatio, state.maxZoomRatio)
        cam.cameraControl.setZoomRatio(target)
    }

    fun exposureRange(): Range<Int> =
        camera?.cameraInfo?.exposureState?.exposureCompensationRange ?: Range(0, 0)

    fun setExposureIndex(index: Int) {
        camera?.cameraControl?.setExposureCompensationIndex(index)
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
     * Captures a full-resolution still and renders it through the selected look + adjustments on a
     * background thread, delivering the finished [Bitmap] via [onResult] (also off the main thread).
     */
    fun capture(onResult: (Bitmap, Filter) -> Unit, onFailure: (Throwable) -> Unit) {
        val capture = imageCapture ?: return onFailure(IllegalStateException("Camera not ready"))
        capture.takePicture(
            captureExecutor,
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
        captureExecutor.shutdown()
        cameraProvider?.unbindAll()
    }

    private fun isFront() = lensFacing == CameraSelector.LENS_FACING_FRONT

    companion object {
        private const val TAG = "CameraController"
    }
}

/**
 * CameraEffect is abstract with a protected constructor, so the preview LUT effect is a subclass.
 * Targets the preview stream only; the still capture is filtered separately via GPUImage.
 */
private class PreviewLutEffect(executor: Executor, processor: SurfaceProcessor) : CameraEffect(
    CameraEffect.PREVIEW,
    executor,
    processor,
    Consumer<Throwable> { t -> Log.e("CameraController", "Preview effect error", t) },
)

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
