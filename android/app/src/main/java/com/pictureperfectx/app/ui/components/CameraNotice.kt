package com.pictureperfectx.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Brand = Color(0xFFFF4D6D)

/**
 * Compact overlay note for things the user needs to know but shouldn't have to chase — a mode
 * caveat, or a format the camera turned down. Deliberately small so it doesn't cover the
 * viewfinder, and it stays put until acknowledged rather than vanishing like a snackbar.
 *
 * @param onNeverShowAgain when non-null, adds a "Don't show again" action.
 */
@Composable
fun CameraNotice(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onNeverShowAgain: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xD9000000))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = text, color = Color(0xEEFFFFFF), fontSize = 12.sp, lineHeight = 16.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onNeverShowAgain != null) {
                NoticeAction(label = "Don't show again", color = Color(0xB3FFFFFF), onClick = onNeverShowAgain)
            }
            NoticeAction(label = "OK", color = Brand, onClick = onDismiss)
        }
    }
}

@Composable
private fun NoticeAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
