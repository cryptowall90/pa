package com.pictureperfectx.app.ui.components

import androidx.camera.core.ImageCapture
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Circular icon button used by the flash and lens-switch controls. */
@Composable
private fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 48,
    tint: Color = Color.White,
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(0x33000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}

/** Top overlay bar: flash mode toggle (left) and manual-adjustments toggle (right). */
@Composable
fun CameraTopBar(
    flashMode: Int,
    adjustmentsOpen: Boolean,
    onCycleFlash: () -> Unit,
    onToggleAdjustments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = when (flashMode) {
        ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn
        ImageCapture.FLASH_MODE_AUTO -> Icons.Filled.FlashAuto
        else -> Icons.Filled.FlashOff
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        CircleIconButton(icon = icon, contentDescription = "Toggle flash", onClick = onCycleFlash)
        CircleIconButton(
            icon = Icons.Filled.Tune,
            contentDescription = "Adjustments",
            onClick = onToggleAdjustments,
            tint = if (adjustmentsOpen) Color(0xFFFF4D6D) else Color.White,
        )
    }
}

/** Bottom control row (Step 4): last-photo thumbnail, shutter, lens switch. */
@Composable
fun CameraControls(
    isSaving: Boolean,
    lastSavedThumbUri: String?,
    onCapture: () -> Unit,
    onToggleLens: () -> Unit,
    onOpenGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LastPhotoThumb(uri = lastSavedThumbUri, onClick = onOpenGallery)
        ShutterButton(isSaving = isSaving, onClick = onCapture)
        CircleIconButton(
            icon = Icons.Filled.Cameraswitch,
            contentDescription = "Switch camera",
            onClick = onToggleLens,
            size = 52,
        )
    }
}

@Composable
private fun ShutterButton(isSaving: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (isSaving) 0.9f else 1f, label = "shutter")
    Box(
        modifier = Modifier
            .size(84.dp)
            .scale(scale)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(if (isSaving) Color(0xFFB3324B) else Color(0xFFFF4D6D))
            .clickable(enabled = !isSaving, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isSaving) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun LastPhotoThumb(uri: String?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x33000000))
            .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = "Open gallery",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = "Open gallery", tint = Color.White)
        }
    }
}
