package com.pictureperfectx.app.ui.perfect

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.pictureperfectx.app.capture.CropMath
import com.pictureperfectx.app.capture.CropRect

/** Which part of the crop frame a drag grabbed. */
private enum class Handle { TopLeft, TopRight, BottomLeft, BottomRight, Left, Top, Right, Bottom, Move }

private val Brand = Color(0xFFFF4D6D)

/**
 * The draggable crop frame: corner and edge handles, a rule-of-thirds grid, and a dimmed surround.
 *
 * Everything is computed in the **normalized** space the geometry model uses, then mapped onto
 * whatever rectangle the image occupies on screen — so the frame the user drags and the pixels that
 * get exported are describing the same thing.
 *
 * @param imageBounds where the image is actually drawn inside this composable (letterboxed by Fit).
 * @param lockedRatio pixel width/height the frame must keep, or null when dragging is unconstrained.
 */
@Composable
fun CropOverlay(
    crop: CropRect,
    imageBounds: Rect,
    lockedRatio: Float?,
    sourceRatio: Float,
    onCropChanged: (CropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The gesture block outlives recompositions, so reading `crop` directly would start each drag
    // from whatever the frame was when the block was created.
    val latestCrop by rememberUpdatedState(crop)

    Canvas(
        modifier = modifier.pointerInput(imageBounds, lockedRatio, sourceRatio) {
            if (imageBounds.width <= 0f || imageBounds.height <= 0f) return@pointerInput
            var handle: Handle? = null
            var working = latestCrop
            detectDragGestures(
                onDragStart = { position ->
                    working = latestCrop
                    handle = handleAt(position, working.toScreen(imageBounds), lockedRatio != null)
                },
                onDragEnd = { handle = null },
                onDragCancel = { handle = null },
            ) { change, drag ->
                change.consume()
                val grabbed = handle ?: return@detectDragGestures
                val dx = drag.x / imageBounds.width
                val dy = drag.y / imageBounds.height
                working = resize(working, grabbed, dx, dy, lockedRatio, sourceRatio)
                onCropChanged(working)
            }
        },
    ) {
        if (imageBounds.width <= 0f || imageBounds.height <= 0f) return@Canvas
        val frame = crop.toScreen(imageBounds)

        // Dim everything outside the frame so the crop reads at a glance.
        val shade = Color(0x99000000)
        drawRect(shade, topLeft = imageBounds.topLeft, size = Size(imageBounds.width, frame.top - imageBounds.top))
        drawRect(
            shade,
            topLeft = Offset(imageBounds.left, frame.bottom),
            size = Size(imageBounds.width, imageBounds.bottom - frame.bottom),
        )
        drawRect(
            shade,
            topLeft = Offset(imageBounds.left, frame.top),
            size = Size(frame.left - imageBounds.left, frame.height),
        )
        drawRect(
            shade,
            topLeft = Offset(frame.right, frame.top),
            size = Size(imageBounds.right - frame.right, frame.height),
        )

        // Rule of thirds.
        val thin = Stroke(width = 1.dp.toPx())
        for (i in 1..2) {
            val x = frame.left + frame.width * i / 3f
            val y = frame.top + frame.height * i / 3f
            drawLine(Color(0x66FFFFFF), Offset(x, frame.top), Offset(x, frame.bottom), thin.width)
            drawLine(Color(0x66FFFFFF), Offset(frame.left, y), Offset(frame.right, y), thin.width)
        }

        drawRect(
            color = Color.White,
            topLeft = frame.topLeft,
            size = Size(frame.width, frame.height),
            style = Stroke(width = 1.5.dp.toPx()),
        )

        // Corner grips, drawn inside the frame so they never leave the image.
        val arm = 20.dp.toPx()
        val thick = 3.dp.toPx()
        listOf(
            Offset(frame.left, frame.top) to Offset(1f, 1f),
            Offset(frame.right, frame.top) to Offset(-1f, 1f),
            Offset(frame.left, frame.bottom) to Offset(1f, -1f),
            Offset(frame.right, frame.bottom) to Offset(-1f, -1f),
        ).forEach { (corner, direction) ->
            drawLine(Brand, corner, Offset(corner.x + arm * direction.x, corner.y), thick)
            drawLine(Brand, corner, Offset(corner.x, corner.y + arm * direction.y), thick)
        }
    }
}

private fun CropRect.toScreen(bounds: Rect) = Rect(
    left = bounds.left + left * bounds.width,
    top = bounds.top + top * bounds.height,
    right = bounds.left + right * bounds.width,
    bottom = bounds.top + bottom * bounds.height,
)

/**
 * Corners win over edges, and a grab inside the frame moves the whole thing. With a ratio locked
 * only corners resize — an edge drag can't preserve a ratio, so it falls through to a move.
 */
private fun handleAt(position: Offset, frame: Rect, cornersOnly: Boolean): Handle? {
    val slop = 48f
    val nearLeft = kotlin.math.abs(position.x - frame.left) <= slop
    val nearRight = kotlin.math.abs(position.x - frame.right) <= slop
    val nearTop = kotlin.math.abs(position.y - frame.top) <= slop
    val nearBottom = kotlin.math.abs(position.y - frame.bottom) <= slop
    val withinX = position.x in (frame.left - slop)..(frame.right + slop)
    val withinY = position.y in (frame.top - slop)..(frame.bottom + slop)

    return when {
        nearLeft && nearTop -> Handle.TopLeft
        nearRight && nearTop -> Handle.TopRight
        nearLeft && nearBottom -> Handle.BottomLeft
        nearRight && nearBottom -> Handle.BottomRight
        cornersOnly -> if (frame.contains(position)) Handle.Move else null
        nearLeft && withinY -> Handle.Left
        nearRight && withinY -> Handle.Right
        nearTop && withinX -> Handle.Top
        nearBottom && withinX -> Handle.Bottom
        frame.contains(position) -> Handle.Move
        else -> null
    }
}

/**
 * Applies a normalized drag to the frame. With a ratio locked, edges are ignored and corners resize
 * proportionally about the opposite corner, which is what keeps a 1:1 crop square while dragging.
 */
private fun resize(
    crop: CropRect,
    handle: Handle,
    dx: Float,
    dy: Float,
    lockedRatio: Float?,
    sourceRatio: Float,
): CropRect {
    if (handle == Handle.Move) {
        val width = crop.width
        val height = crop.height
        val left = (crop.left + dx).coerceIn(0f, 1f - width)
        val top = (crop.top + dy).coerceIn(0f, 1f - height)
        return CropRect(left, top, left + width, top + height)
    }

    if (lockedRatio != null) {
        // Normalized aspect: a rect of w x h covers (w*W) x (h*H) pixels.
        val aspect = lockedRatio / sourceRatio
        val anchorX = if (handle == Handle.TopLeft || handle == Handle.BottomLeft) crop.right else crop.left
        val anchorY = if (handle == Handle.TopLeft || handle == Handle.TopRight) crop.bottom else crop.top
        val towardRight = anchorX == crop.left
        val towardBottom = anchorY == crop.top

        var width = (crop.width + if (towardRight) dx else -dx).coerceAtLeast(CropMath.MIN_SIZE)
        var height = width / aspect
        if (height < CropMath.MIN_SIZE) {
            height = CropMath.MIN_SIZE
            width = height * aspect
        }
        // Don't let the frame run off the image on the side it's growing towards.
        val maxWidth = if (towardRight) 1f - anchorX else anchorX
        val maxHeight = if (towardBottom) 1f - anchorY else anchorY
        if (width > maxWidth) {
            width = maxWidth
            height = width / aspect
        }
        if (height > maxHeight) {
            height = maxHeight
            width = height * aspect
        }
        val left = if (towardRight) anchorX else anchorX - width
        val top = if (towardBottom) anchorY else anchorY - height
        return CropMath.clamp(CropRect(left, top, left + width, top + height))
    }

    var left = crop.left
    var top = crop.top
    var right = crop.right
    var bottom = crop.bottom
    when (handle) {
        Handle.TopLeft -> { left += dx; top += dy }
        Handle.TopRight -> { right += dx; top += dy }
        Handle.BottomLeft -> { left += dx; bottom += dy }
        Handle.BottomRight -> { right += dx; bottom += dy }
        Handle.Left -> left += dx
        Handle.Right -> right += dx
        Handle.Top -> top += dy
        Handle.Bottom -> bottom += dy
        Handle.Move -> Unit
    }
    return CropMath.clamp(CropRect(left, top, right, bottom))
}
