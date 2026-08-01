package com.pictureperfectx.app.ui.camera

import androidx.camera.core.MeteringPoint
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pictureperfectx.app.camera.CameraController

/**
 * The live viewfinder: a hardware [PreviewView] whose frames are filtered on the GPU by the
 * CameraController's SurfaceProcessor effect. Adds tap-to-focus (with a focus ring) and
 * pinch-to-zoom on top.
 */
@Composable
fun CameraPreview(
    controller: CameraController,
    onFocus: (MeteringPoint) -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var focusRing by remember { mutableStateOf<Offset?>(null) }
    val ringAlpha = remember { Animatable(0f) }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Pinch-to-zoom.
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) onZoom(zoom)
                    }
                }
                .pointerInput(Unit) {
                    // Tap-to-focus.
                    detectTapGestures { offset ->
                        previewView?.let { pv ->
                            onFocus(pv.meteringPointFactory.createPoint(offset.x, offset.y))
                            focusRing = offset
                        }
                    }
                },
            factory = { context ->
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                }.also { pv ->
                    previewView = pv
                    controller.setSurfaceProvider(pv.surfaceProvider)
                    controller.bind(lifecycleOwner)
                }
            },
        )

        // Focus ring feedback.
        focusRing?.let { point ->
            androidx.compose.runtime.LaunchedEffect(point) {
                ringAlpha.snapTo(1f)
                ringAlpha.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(900))
            }
            val ringSize = 72.dp
            val half = with(density) { ringSize.toPx() / 2f }
            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) { (point.x - half).toDp() },
                        y = with(density) { (point.y - half).toDp() },
                    )
                    .size(ringSize)
                    .alpha(ringAlpha.value)
                    .clip(CircleShape)
                    .border(2.dp, Color.White, CircleShape),
            )
        }
    }
}
