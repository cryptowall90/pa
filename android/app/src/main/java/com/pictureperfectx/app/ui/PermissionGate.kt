package com.pictureperfectx.app.ui

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

/**
 * Requests CAMERA at first launch and shows a rationale + retry button if it isn't granted yet.
 * Renders [content] only once permission is held.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    if (cameraPermission.status.isGranted) {
        content()
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.PhotoCamera,
                contentDescription = null,
                tint = Color(0xFFFF4D6D),
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Text(
                text = "Picture Perfect X needs your camera to take filtered photos.",
                color = Color.White,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 24.dp),
            )
            Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                Text("Grant camera access")
            }
        }
    }
}
