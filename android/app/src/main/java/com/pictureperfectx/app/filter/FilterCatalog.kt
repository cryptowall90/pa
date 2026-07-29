package com.pictureperfectx.app.filter

import androidx.compose.ui.graphics.Color

/**
 * The ordered set of looks offered in the selector. Drop a matching PNG into `assets/luts/`
 * and set [Filter.lutAsset] to switch any of these from the parametric fallback to a true LUT.
 */
object FilterCatalog {

    val filters: List<Filter> = listOf(
        Filter(
            id = Filter.ORIGINAL_ID,
            displayName = "Original",
            swatchStart = Color(0xFF3A3A3D),
            swatchEnd = Color(0xFF161618),
        ),
        Filter(
            id = "fujifilm",
            displayName = "Fujifilm",
            lutAsset = "luts/fujifilm.png",
            swatchStart = Color(0xFF7FB77E),
            swatchEnd = Color(0xFFB7D99B),
        ),
        Filter(
            id = "leica",
            displayName = "Leica",
            lutAsset = "luts/leica.png",
            swatchStart = Color(0xFF6E7B8B),
            swatchEnd = Color(0xFF2C333D),
        ),
        Filter(
            id = "polaroid",
            displayName = "Polaroid",
            lutAsset = "luts/polaroid.png",
            swatchStart = Color(0xFFE8C39E),
            swatchEnd = Color(0xFFB98E63),
        ),
        Filter(
            id = "monochrome",
            displayName = "Monochrome",
            swatchStart = Color(0xFFE6E6E6),
            swatchEnd = Color(0xFF2A2A2A),
        ),
    )

    val default: Filter get() = filters.first()

    fun byId(id: String): Filter = filters.firstOrNull { it.id == id } ?: default
}
