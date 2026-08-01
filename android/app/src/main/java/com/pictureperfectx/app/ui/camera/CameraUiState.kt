package com.pictureperfectx.app.ui.camera

import androidx.camera.core.ImageCapture
import com.pictureperfectx.app.filter.Filter
import com.pictureperfectx.app.filter.FilterCatalog

data class CameraUiState(
    val filters: List<Filter> = listOf(FilterCatalog.original),
    val selectedFilterId: String = Filter.ORIGINAL_ID,
    val intensity: Int = 100,               // 0-100 LUT strength
    val flashMode: Int = ImageCapture.FLASH_MODE_OFF,
    val isFrontFacing: Boolean = false,
    val isSaving: Boolean = false,
    val lastSavedThumbUri: String? = null,
    // Manual controls
    val showAdjustments: Boolean = false,
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
}

/** One-shot messages surfaced as a snackbar. */
sealed interface CameraEvent {
    data class Saved(val message: String) : CameraEvent
    data class Error(val message: String) : CameraEvent
}
