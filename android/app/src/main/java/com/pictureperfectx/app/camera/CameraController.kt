package com.pictureperfectx.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
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
import com.pictureperfectx.app.capture.BitmapIO
import com.pictureperfectx.app.capture.PhotoSaver
import com.pictureperfectx.app.capture.RawPreview
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
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    /** What the shutter writes. Always one of [availableFormats]. */
    var captureFormat: CaptureFormat = CaptureFormat.JPEG
        private set

    /**
     * Formats this lens can actually deliver: what it advertises, minus anything that turned out to
     * be unbindable in practice. RAW is commonly back-camera only.
     */
    var availableFormats: Set<CaptureFormat> = setOf(CaptureFormat.JPEG)
        private set

    /** Notified on the main thread after each binding, since format support is per-lens. */
    var onFormatsChanged: ((Set<CaptureFormat>, CaptureFormat) -> Unit)? = null

    /** Notified when a format had to be abandoned, so the UI can explain the fallback. */
    var onBindError: ((String) -> Unit)? = null

    // What the lens advertises, before subtracting formats that failed to bind.
    private var reportedFormats: Set<CaptureFormat> = setOf(CaptureFormat.JPEG)

    // Formats a device advertises but rejects when actually bound, keyed by lens — a stream
    // combination can be legal on one camera and not the other.
    private val unbindable = mutableSetOf<Pair<Int, CaptureFormat>>()

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

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        // Format support is per-lens and the output format is fixed when ImageCapture is built, so
        // both are resolved fresh on every binding.
        reportedFormats = queryReportedFormats(provider, selector)
        refreshAvailableFormats()

        val rejected = captureFormat
        if (!tryBind(provider, owner, sp, selector, rejected) && rejected.writesRaw) {
            // A camera that advertises a format can still refuse the resulting stream combination.
            // Remember that, drop back to JPEG and bind again: leaving the session unbound after
            // unbindAll() is what froze the viewfinder with no way back. Only RAW gets this
            // treatment — a JPEG failure has nothing to fall back to and is likely transient.
            unbindable += lensFacing to rejected
            refreshAvailableFormats()
            onBindError?.invoke("${rejected.label} isn't supported on this camera — switched to JPEG")
            tryBind(provider, owner, sp, selector, captureFormat)
        }

        // Re-assert the current look + adjustments on the processor for the fresh binding.
        processor.setLut(currentFilter.lutAsset)
        processor.setIntensity(intensity)
        processor.setBrightness(brightnessF)
        processor.setContrast(contrastF)
        processor.setSaturation(saturationF)
        onFormatsChanged?.invoke(availableFormats, captureFormat)
    }

    /**
     * Binds preview + capture for [format], returning false if the device rejects the combination.
     * On success [camera] and [imageCapture] point at the new session.
     */
    private fun tryBind(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        sp: Preview.SurfaceProvider,
        selector: CameraSelector,
        format: CaptureFormat,
    ): Boolean {
        provider.unbindAll()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(sp) }
        val capture = ImageCapture.Builder()
            // Low-latency capture opts into zero-shutter-lag paths that conflict with RAW on many
            // devices — and someone shooting RAW wants the best frame, not the fastest one.
            .setCaptureMode(
                if (format.writesRaw) {
                    ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                } else {
                    ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                },
            )
            .setFlashMode(imageFlashMode())
            .setOutputFormat(outputFormatOf(format))
            .build()

        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(capture)
            .addEffect(PreviewLutEffect(processor.executor, processor))
            .build()

        return try {
            camera = provider.bindToLifecycle(owner, selector, group)
            imageCapture = capture
            true
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed for $format", e)
            false
        }
    }

    /** Drops advertised formats this lens has already refused, keeping [captureFormat] valid. */
    private fun refreshAvailableFormats() {
        availableFormats = reportedFormats.filterNot { (lensFacing to it) in unbindable }.toSet()
        if (captureFormat !in availableFormats) captureFormat = CaptureFormat.JPEG
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

    // ---- Capture format (JPEG / RAW / RAW+JPEG) -------------------------------------------------

    /** Selects what the shutter writes. The output format is baked into [ImageCapture], so rebind. */
    fun setCaptureFormat(format: CaptureFormat) {
        if (captureFormat == format || format !in availableFormats) return
        captureFormat = format
        rebindUseCases()
    }

    private fun outputFormatOf(format: CaptureFormat): Int = when (format) {
        CaptureFormat.JPEG -> ImageCapture.OUTPUT_FORMAT_JPEG
        CaptureFormat.RAW -> ImageCapture.OUTPUT_FORMAT_RAW
        CaptureFormat.RAW_JPEG -> ImageCapture.OUTPUT_FORMAT_RAW_JPEG
    }

    /**
     * Which formats this lens advertises. RAW and RAW+JPEG are checked separately — a camera can
     * support one without the other, so a single "does RAW" flag would offer a mode it will reject.
     */
    private fun queryReportedFormats(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
    ): Set<CaptureFormat> {
        val jpegOnly = setOf(CaptureFormat.JPEG)
        return try {
            val info = selector.filter(provider.availableCameraInfos).firstOrNull() ?: return jpegOnly
            val supported = ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
            buildSet {
                add(CaptureFormat.JPEG)
                if (ImageCapture.OUTPUT_FORMAT_RAW in supported) add(CaptureFormat.RAW)
                if (ImageCapture.OUTPUT_FORMAT_RAW_JPEG in supported) add(CaptureFormat.RAW_JPEG)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Output format capability query failed", e)
            jpegOnly
        }
    }

    /** Cycles OFF -> ON -> AUTO. Returns the new mode. */
    fun cycleFlash(): Int {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = imageFlashMode()
        return flashMode
    }

    /**
     * ImageCapture flash mode. "On" is driven by the torch during capture (reliable across devices),
     * so ImageCapture itself is OFF for that mode to avoid a double fire; "Auto" and "Off" pass
     * straight through to the hardware.
     */
    private fun imageFlashMode(): Int =
        if (flashMode == ImageCapture.FLASH_MODE_ON) ImageCapture.FLASH_MODE_OFF else flashMode

    /**
     * Captures a full-resolution still in the selected [captureFormat] and delivers the outcome via
     * [onResult], off the main thread. JPEGs are rendered through the active look + adjustments;
     * DNGs are written by CameraX exactly as the sensor saw them.
     */
    fun capture(onResult: (CaptureResult) -> Unit, onFailure: (Throwable) -> Unit) {
        val capture = imageCapture ?: return onFailure(IllegalStateException("Camera not ready"))
        // "On" -> light the torch for the duration of the shot so the flash always fires.
        val useTorch = flashMode == ImageCapture.FLASH_MODE_ON
        if (useTorch) runCatching { camera?.cameraControl?.enableTorch(true) }
        val stopTorch = { if (useTorch) runCatching { camera?.cameraControl?.enableTorch(false) } }

        when (captureFormat) {
            CaptureFormat.JPEG -> captureJpeg(capture, stopTorch, onResult, onFailure)
            CaptureFormat.RAW -> captureRaw(capture, stopTorch, onResult, onFailure)
            CaptureFormat.RAW_JPEG -> captureRawAndJpeg(capture, stopTorch, onResult, onFailure)
        }
    }

    /** JPEG only: keep the fast in-memory path, filtering the frame before it ever hits disk. */
    private fun captureJpeg(
        capture: ImageCapture,
        stopTorch: () -> Unit,
        onResult: (CaptureResult) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        capture.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val oriented = image.toOrientedBitmap(isFront())
                        val filtered = captureGpuImage.getBitmapWithFilterApplied(oriented)
                        onResult(CaptureResult.Jpeg(filtered, currentFilter))
                    } catch (e: Exception) {
                        onFailure(e)
                    } finally {
                        image.close()
                        stopTorch()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    stopTorch()
                    onFailure(exception)
                }
            },
        )
    }

    /** RAW only: CameraX streams the DNG straight into MediaStore; there is nothing to filter. */
    private fun captureRaw(
        capture: ImageCapture,
        stopTorch: () -> Unit,
        onResult: (CaptureResult) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val name = "${PhotoSaver.baseName()}.dng"
        capture.takePicture(
            dngOutputOptions(name),
            captureExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    stopTorch()
                    val uri = outputFileResults.savedUri
                    if (uri == null) {
                        onFailure(IllegalStateException("RAW capture returned no URI"))
                        return
                    }
                    val (w, h) = RawPreview.dimensions(appContext, uri)
                    onResult(CaptureResult.Raw(uri, name, w, h, jpeg = null))
                }

                override fun onError(exception: ImageCaptureException) {
                    stopTorch()
                    onFailure(exception)
                }
            },
        )
    }

    /**
     * RAW+JPEG: the DNG goes to MediaStore, but CameraX writes the JPEG itself and knows nothing
     * about our look — so that half lands in a cache file, gets rendered through the active filter,
     * and is handed back for the caller to save. The callback fires once per file.
     */
    private fun captureRawAndJpeg(
        capture: ImageCapture,
        stopTorch: () -> Unit,
        onResult: (CaptureResult) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val name = "${PhotoSaver.baseName()}.dng"
        val temp = File.createTempFile("ppx_capture", ".jpg", appContext.cacheDir)
        val jpegOptions = ImageCapture.OutputFileOptions.Builder(temp)
            .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = isFront() })
            .build()

        val pending = AtomicInteger(2)
        val failed = AtomicBoolean(false)
        var dngUri: Uri? = null

        capture.takePicture(
            dngOutputOptions(name),
            jpegOptions,
            captureExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    // Only the MediaStore-backed half reports a content URI; the JPEG went to a file.
                    outputFileResults.savedUri
                        ?.takeIf { it.scheme == "content" }
                        ?.let { dngUri = it }
                    if (pending.decrementAndGet() > 0 || failed.get()) return
                    stopTorch()
                    try {
                        val uri = dngUri ?: error("RAW capture returned no URI")
                        val decoded = BitmapIO.load(appContext, Uri.fromFile(temp), CAPTURE_MAX_EDGE)
                            ?: error("Could not decode the captured JPEG")
                        val filtered = captureGpuImage.getBitmapWithFilterApplied(decoded)
                        // Dimensions describe the DNG; the JPEG's come from its own bitmap.
                        val (rawWidth, rawHeight) = RawPreview.dimensions(appContext, uri)
                        onResult(
                            CaptureResult.Raw(
                                dngUri = uri,
                                dngName = name,
                                width = rawWidth,
                                height = rawHeight,
                                jpeg = CaptureResult.Jpeg(filtered, currentFilter),
                            ),
                        )
                    } catch (e: Exception) {
                        onFailure(e)
                    } finally {
                        temp.delete()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (failed.compareAndSet(false, true)) {
                        stopTorch()
                        temp.delete()
                        onFailure(exception)
                    }
                }
            },
        )
    }

    private fun dngOutputOptions(displayName: String): ImageCapture.OutputFileOptions =
        ImageCapture.OutputFileOptions.Builder(
            appContext.contentResolver,
            PhotoSaver.imageCollection(),
            PhotoSaver.valuesFor(displayName, PhotoSaver.MIME_DNG),
        ).setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = isFront() }).build()

    fun release() {
        processor.release()
        captureExecutor.shutdown()
        cameraProvider?.unbindAll()
    }

    private fun isFront() = lensFacing == CameraSelector.LENS_FACING_FRONT

    companion object {
        private const val TAG = "CameraController"
        // Full-res for any phone sensor, while capping the decode of an absurdly large frame.
        private const val CAPTURE_MAX_EDGE = 8192
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
