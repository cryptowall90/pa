package com.pictureperfectx.app.filter

import androidx.compose.ui.graphics.Color

/**
 * Declarative description of a camera look. This is the "schema" for a filter: the app
 * never hard-codes GPU pipelines in the UI — it maps a [Filter] to a GPUImage pipeline in
 * [FilterFactory]. Adding a look means adding one entry to [FilterCatalog].
 *
 * @param id          Stable identifier persisted alongside captured photos (see PhotoEntity).
 * @param displayName Shown under the thumbnail in the selector.
 * @param lutAsset    Optional 512x512 lookup-table PNG under `assets/luts/`. When present it is
 *                    applied via GPUImageLookupFilter; when null the [FilterFactory] falls back to
 *                    a parametric pipeline so the app ships with real looks and no binary assets.
 * @param swatch      Two colors used to render the thumbnail chip when no live preview is available.
 */
data class Filter(
    val id: String,
    val displayName: String,
    val lutAsset: String? = null,
    val swatchStart: Color,
    val swatchEnd: Color,
) {
    companion object {
        const val ORIGINAL_ID = "original"
    }
}
