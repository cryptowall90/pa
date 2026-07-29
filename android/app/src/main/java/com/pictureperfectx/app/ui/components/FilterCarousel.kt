package com.pictureperfectx.app.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pictureperfectx.app.filter.Filter
import com.pictureperfectx.app.filter.LutThumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Horizontal, scrollable strip of filter thumbnails (Step 3). Each thumbnail shows a **live**
 * preview of the current scene with that look applied — computed on-device from [previewSource]
 * (a small camera snapshot) via [LutThumbnails]. Tapping one selects the look.
 *
 * Until the first camera snapshot arrives, thumbnails fall back to the look's swatch gradient.
 */
@Composable
fun FilterCarousel(
    filters: List<Filter>,
    selectedFilterId: String,
    previewSource: Bitmap?,
    onFilterSelected: (Filter) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(filters, key = { it.id }) { filter ->
            FilterThumbnail(
                filter = filter,
                selected = filter.id == selectedFilterId,
                previewSource = previewSource,
                onClick = { onFilterSelected(filter) },
            )
        }
    }
}

@Composable
private fun FilterThumbnail(
    filter: Filter,
    selected: Boolean,
    previewSource: Bitmap?,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val borderColor by animateColorAsState(
        targetValue = if (selected) Color(0xFFFF4D6D) else Color(0x33FFFFFF),
        label = "border",
    )
    val thumbSize by animateDpAsState(if (selected) 68.dp else 60.dp, label = "size")

    // Render the LUT preview off the main thread; recomputed when the scene snapshot changes.
    val preview by produceState<Bitmap?>(initialValue = null, filter.id, previewSource) {
        val src = previewSource
        value = if (src == null) null else withContext(Dispatchers.Default) {
            runCatching { LutThumbnails.render(context, filter, src) }.getOrNull()
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(thumbSize)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(filter.swatchStart, filter.swatchEnd)))
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(14.dp),
                )
                .clickable(onClick = onClick),
        ) {
            preview?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = filter.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                )
            }
        }
        Text(
            text = filter.displayName,
            color = if (selected) Color(0xFFFF4D6D) else Color(0xCCFFFFFF),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp).size(width = 68.dp, height = 16.dp),
        )
    }
}
