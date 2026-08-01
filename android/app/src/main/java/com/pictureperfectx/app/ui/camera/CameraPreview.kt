package com.pictureperfectx.app.ui.camera

import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pictureperfectx.app.camera.CameraController

/**
 * The live viewfinder: a hardware [PreviewView] whose frames are filtered on the GPU by the
 * CameraController's SurfaceProcessor effect. Because it's the real preview stream (not a CPU
 * re-render), it appears immediately and stays as smooth as the stock camera.
 */
@Composable
fun CameraPreview(
    controller: CameraController,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { context ->
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            }.also { previewView ->
                controller.setSurfaceProvider(previewView.surfaceProvider)
                controller.bind(lifecycleOwner)
            }
        },
    )
}
