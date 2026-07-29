package com.pictureperfectx.app.ui.camera

import androidx.camera.core.ImageCapture
import com.pictureperfectx.app.filter.Filter
import com.pictureperfectx.app.filter.FilterCatalog

data class CameraUiState(
    val filters: List<Filter> = FilterCatalog.filters,
    val selectedFilterId: String = FilterCatalog.default.id,
    val flashMode: Int = ImageCapture.FLASH_MODE_OFF,
    val isFrontFacing: Boolean = false,
    val isSaving: Boolean = false,
    val lastSavedThumbUri: String? = null,
)

/** One-shot messages surfaced as a snackbar. */
sealed interface CameraEvent {
    data class Saved(val message: String) : CameraEvent
    data class Error(val message: String) : CameraEvent
}
