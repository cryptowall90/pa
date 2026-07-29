package com.pictureperfectx.app.ui.camera

import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pictureperfectx.app.camera.CameraController

/**
 * Hosts the [GLSurfaceView] that GPUImage renders the live, filtered camera feed into, and binds
 * the CameraX use cases to the composition's lifecycle.
 *
 * This is the Step 1 real-time preview (Step 2's GPUImage wrapper is what makes it filtered):
 * frames flow CameraX ImageAnalysis -> NV21 -> GPUImage -> this surface.
 */
@Composable
fun GpuCameraPreview(
    controller: CameraController,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { context ->
            GLSurfaceView(context).also { surface ->
                controller.attachGlSurface(surface)
                controller.bind(lifecycleOwner)
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose { /* CameraController.release() is handled in the ViewModel's onCleared. */ }
    }
}
