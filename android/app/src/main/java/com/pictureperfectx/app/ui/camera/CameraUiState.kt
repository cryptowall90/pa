package com.pictureperfectx.app.ui.camera

import androidx.camera.core.ImageCapture
import com.pictureperfectx.app.camera.CaptureFormat
import com.pictureperfectx.app.filter.Filter
import com.pictureperfectx.app.filter.FilterCatalog

data class CameraUiState(
    val filters: List<Filter> = listOf(FilterCatalog.original),
    val selectedFilterId: String = Filter.ORIGINAL_ID,
    val intensity: Int = 100,               // 0-100 LUT strength
    val flashMode: Int = ImageCapture.FLASH_MODE_OFF,
    val captureFormat: CaptureFormat = CaptureFormat.JPEG,
    // Per-lens, and minus anything the camera refused to bind; hides the chip when there's no choice.
    val availableFormats: Set<CaptureFormat> = setOf(CaptureFormat.JPEG),
    val isFrontFacing: Boolean = false,
    val isSaving: Boolean = false,
    val lastSavedThumbUri: String? = null,
    val showFilters: Boolean = true,         // filter carousel + intensity visibility
    // Manual controls
    val showAdjustments: Boolean = false,
    val selectedAdjustment: Adjustment = Adjustment.Brightness,
    val brightness: Int = 0,                 // -100..100
    val contrast: Int = 0,                   // -100..100
    val saturation: Int = 0,                 // -100..100
    val exposure: Int = 0,                   // EV index within [exposureMin, exposureMax]
    val exposureMin: Int = 0,
    val exposureMax: Int = 0,
) {
    val selectedFilter: Filter?
        get() = filters.firstOrNull { it.id == selectedFilterId }

    /** Intensity only applies to a real LUT — hide the slider on Original. */
    val intensityEnabled: Boolean
        get() = selectedFilter?.isOriginal == false

    val exposureSupported: Boolean
        get() = exposureMax > exposureMin

    /** Only worth showing the format chip when there is more than one format to pick. */
    val formatSwitchable: Boolean
        get() = availableFormats.size > 1
}

/** One manual adjustment, chosen one-at-a-time in the adjustments row. */
enum class Adjustment(val label: String) {
    Exposure("Exposure"),
    Brightness("Brightness"),
    Contrast("Contrast"),
    Saturation("Saturation"),
}

/** One-shot messages surfaced as a snackbar. */
sealed interface CameraEvent {
    data class Saved(val message: String) : CameraEvent
    data class Error(val message: String) : CameraEvent
}
