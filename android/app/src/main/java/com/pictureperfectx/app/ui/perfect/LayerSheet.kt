package com.pictureperfectx.app.ui.perfect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pictureperfectx.app.layers.Layer
import com.pictureperfectx.app.layers.Mask
import com.pictureperfectx.app.ui.theme.Brand
import androidx.compose.foundation.Canvas

/**
 * The layer stack, with room to say what everything is.
 *
 * These controls used to live as unlabelled chips in the same horizontally scrolling row as the
 * selection tools — ten items where four fit, three unrelated ideas side by side, and **Delete**
 * off the right-hand edge where only scrolling would find it. A destructive action you can only
 * reach by accident is worse than one that isn't there.
 *
 * A sheet costs a tap and buys the space to give every layer its name, its area, and its own
 * actions with labels on them. It is the one place in this editor where covering the photo is the
 * right trade: nothing here is judged against the picture, it is all bookkeeping.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayerSheet(
    state: PerfectEditUiState,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
    onToggleVisible: (Long) -> Unit,
    onMove: (Long, Boolean) -> Unit,
    onDuplicate: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onCycleBlend: (Long) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF141416),
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                text = "Layers",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
            )
            Text(
                text = "Topmost first. Tap one to edit it.",
                color = Color(0x99FFFFFF),
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
            )

            if (state.document.isEmpty) {
                Text(
                    text = "No effects yet. Draw an area, then add one.",
                    color = Color(0x99FFFFFF),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                )
                return@Column
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.document.topDown, key = { it.id }) { layer ->
                    LayerCard(
                        layer = layer,
                        isSelected = layer.id == state.document.selectedId,
                        onSelect = { onSelect(layer.id) },
                        onToggleVisible = { onToggleVisible(layer.id) },
                        onMove = { up -> onMove(layer.id, up) },
                        onDuplicate = { onDuplicate(layer.id) },
                        onRemove = { onRemove(layer.id) },
                        onCycleBlend = { onCycleBlend(layer.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LayerCard(
    layer: Layer,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggleVisible: () -> Unit,
    onMove: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    onRemove: () -> Unit,
    onCycleBlend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Color(0x332B6CFF) else Color(0x14FFFFFF))
            .then(
                if (isSelected) Modifier.border(1.dp, Brand, RoundedCornerShape(12.dp)) else Modifier,
            )
            .clickable(onClick = onSelect)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MaskThumbnail(mask = layer.mask, modifier = Modifier.size(34.dp))
            Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                Text(
                    text = layer.name,
                    color = if (layer.isVisible) Color.White else Color(0x66FFFFFF),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = layer.summary(),
                    color = Color(0x99FFFFFF),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onToggleVisible) {
                Icon(
                    imageVector = if (layer.isVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = if (layer.isVisible) "Hide this layer" else "Show this layer",
                    tint = if (layer.isVisible) Color.White else Color(0x66FFFFFF),
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SheetAction(label = layer.blend.label, onClick = onCycleBlend)
            SheetIcon(Icons.Filled.KeyboardArrowUp, "Move up") { onMove(true) }
            SheetIcon(Icons.Filled.KeyboardArrowDown, "Move down") { onMove(false) }
            SheetIcon(Icons.Filled.ContentCopy, "Duplicate") { onDuplicate() }
            Box(modifier = Modifier.weight(1f))
            // Red, apart from the rest, and last: the one action here that cannot be taken back by
            // repeating it. It used to look exactly like a mode toggle.
            SheetIcon(Icons.Filled.Delete, "Delete", tint = Color(0xFFFF6B6B), onClick = onRemove)
        }
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x22FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun SheetIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/**
 * The layer's area at a glance.
 *
 * Sampled down to a few dozen cells rather than drawn per coverage cell: a selection grid is 256
 * across, and fifty thousand rectangles to draw a thumbnail the size of a fingernail would cost more
 * than the sheet it sits in.
 */
@Composable
private fun MaskThumbnail(mask: Mask, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x22FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        if (mask.isEmpty) {
            // No mask means the layer applies to the whole photo, which is worth saying in words —
            // an all-white thumbnail would read as "a mask covering everything", a different thing.
            Text(text = "All", color = Color(0xAAFFFFFF), fontSize = 9.sp)
            return@Box
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(34.dp)) {
            val columns = SAMPLES.coerceAtMost(mask.columns)
            val rows = SAMPLES.coerceAtMost(mask.rows)
            val cellWidth = size.width / columns
            val cellHeight = size.height / rows
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val coverage = mask.coverageAt(
                        column = column * mask.columns / columns,
                        row = row * mask.rows / rows,
                    )
                    if (coverage <= 0.02f) continue
                    drawRect(
                        color = Brand.copy(alpha = 0.25f + 0.75f * coverage),
                        topLeft = Offset(column * cellWidth, row * cellHeight),
                        size = Size(cellWidth, cellHeight),
                    )
                }
            }
        }
    }
}

/** What kind of layer this is and how strongly it applies, in a few words. */
private fun Layer.summary(): String {
    val kind = when (this) {
        is Layer.Tone -> "Tone"
        is Layer.Look -> "Look"
        is Layer.Blur -> "Blur"
        is Layer.Text -> "Text"
        is Layer.Shape -> "Shape"
        is Layer.Curve -> "Curve"
        is Layer.Gradient -> if (solid) "Fill" else "Gradient"
        is Layer.Smooth -> "Smooth"
        is Layer.Heal -> "Heal"
    }
    val where = if (mask.isEmpty) "whole photo" else "masked"
    return "$kind · $where · ${(opacity * 100).toInt()}%"
}

/** How many cells across a thumbnail samples the mask. */
private const val SAMPLES = 22
