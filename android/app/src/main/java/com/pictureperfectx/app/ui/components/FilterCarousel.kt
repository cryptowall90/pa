package com.pictureperfectx.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pictureperfectx.app.filter.Filter

/**
 * Horizontal, scrollable strip of filter thumbnails (Step 3). Tapping a thumbnail selects that
 * look, which updates the live GPU filter through the ViewModel.
 *
 * Thumbnails render as branded gradient chips so the app ships without a bundled sample image;
 * swap in live-preview thumbnails later without changing this API.
 */
@Composable
fun FilterCarousel(
    filters: List<Filter>,
    selectedFilterId: String,
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
                onClick = { onFilterSelected(filter) },
            )
        }
    }
}

@Composable
private fun FilterThumbnail(
    filter: Filter,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor by animateColorAsState(
        targetValue = if (selected) Color(0xFFFF4D6D) else Color(0x33FFFFFF),
        label = "border",
    )
    val thumbSize by animateDpAsState(if (selected) 68.dp else 60.dp, label = "size")

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(thumbSize)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(listOf(filter.swatchStart, filter.swatchEnd)),
                )
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(14.dp),
                )
                .clickable(onClick = onClick),
        )
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
