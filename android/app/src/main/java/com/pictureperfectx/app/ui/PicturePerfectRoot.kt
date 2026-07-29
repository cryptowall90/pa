package com.pictureperfectx.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pictureperfectx.app.ui.camera.CameraScreen
import com.pictureperfectx.app.ui.gallery.GalleryScreen

private enum class Screen { Camera, Gallery }

/**
 * Top-level in-app navigation. Lightweight (no nav library): the camera is the home screen and the
 * gallery is pushed over it. Camera permission is only gated around the camera screen.
 */
@Composable
fun PicturePerfectRoot() {
    var screen by remember { mutableStateOf(Screen.Camera) }

    when (screen) {
        Screen.Camera -> PermissionGate {
            CameraScreen(onOpenGallery = { screen = Screen.Gallery })
        }
        Screen.Gallery -> GalleryScreen(onBack = { screen = Screen.Camera })
    }
}
