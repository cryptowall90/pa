package com.pictureperfectx.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// The camera experience is always dark — a light scheme would wash out the viewfinder.
private val PicturePerfectColors = darkColorScheme(
    primary = Brand,
    onPrimary = OnSurfaceWhite,
    secondary = BrandDim,
    background = SurfaceBlack,
    onBackground = OnSurfaceWhite,
    surface = SurfaceBlack,
    onSurface = OnSurfaceWhite,
)

@Composable
fun PicturePerfectTheme(
    // Kept for API parity; the app intentionally stays dark regardless of system setting.
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = PicturePerfectColors,
        typography = Typography(),
        content = content,
    )
}
