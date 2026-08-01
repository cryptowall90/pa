package com.pictureperfectx.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pictureperfectx.app.ui.camera.CameraScreen
import com.pictureperfectx.app.ui.edit.EditScreen
import com.pictureperfectx.app.ui.gallery.GalleryScreen

private sealed interface Screen {
    data object Camera : Screen
    data object Gallery : Screen
    data class Edit(val uri: Uri) : Screen
}

/**
 * Top-level in-app navigation (lightweight, no nav library). Camera is home; the gallery pushes
 * over it; the editor opens from a saved photo or an imported device image and returns to the gallery.
 */
@Composable
fun PicturePerfectRoot() {
    var screen by remember { mutableStateOf<Screen>(Screen.Camera) }

    when (val current = screen) {
        Screen.Camera -> PermissionGate {
            CameraScreen(onOpenGallery = { screen = Screen.Gallery })
        }
        Screen.Gallery -> GalleryScreen(
            onBack = { screen = Screen.Camera },
            onEdit = { uri -> screen = Screen.Edit(uri) },
        )
        is Screen.Edit -> EditScreen(
            sourceUri = current.uri,
            onBack = { screen = Screen.Gallery },
            onSaved = { screen = Screen.Gallery },
        )
    }
}
