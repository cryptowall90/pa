package com.pictureperfectx.app.ui.gallery

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.pictureperfectx.app.capture.RawPreview
import com.pictureperfectx.app.data.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Brand = Color(0xFFFF4D6D)

private const val RAW_THUMB_PX = 512
private const val RAW_FULL_PX = 2048

/**
 * A photo in the grid. RAW-only captures have no JPEG to show, so they fall back to the DNG's
 * embedded preview — Android can't decode the sensor data itself. [previewPx] sizes that fallback:
 * the grid wants a small tile, the full-screen viewer wants as much as the file will give.
 */
@Composable
private fun PhotoThumb(
    photo: PhotoEntity,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    previewPx: Int = RAW_THUMB_PX,
) {
    if (!photo.isRawOnly) {
        AsyncImage(
            model = photo.uri,
            contentDescription = photo.filterName,
            contentScale = contentScale,
            modifier = modifier,
        )
        return
    }

    val context = LocalContext.current
    var preview by remember(photo.uri, previewPx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(photo.uri, previewPx) {
        preview = withContext(Dispatchers.IO) {
            RawPreview.thumbnail(context, Uri.parse(photo.uri), previewPx)
        }
    }

    val bitmap = preview
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = photo.filterName,
            contentScale = contentScale,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(text = "RAW", color = Color(0x66FFFFFF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Corner marker identifying a capture that also wrote a DNG. */
@Composable
private fun RawBadge(modifier: Modifier = Modifier) {
    Text(
        text = "RAW",
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/**
 * Local gallery. Tap a photo to view it; long-press to start selecting; tap more to add to the
 * selection; delete asks for confirmation first. Import a device photo (or open a saved one) into
 * the editor via [onEdit].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    onBack: () -> Unit,
    onEdit: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GalleryViewModel = viewModel(),
) {
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val selectionMode = selected.isNotEmpty()

    // Tracked by id, not by entity: the viewer is a pager over the live list, and the row it shows
    // can be deleted or reordered underneath it.
    var viewingId by remember { mutableStateOf<Long?>(null) }
    var confirmSelected by remember { mutableStateOf(false) }
    var confirmSingle by remember { mutableStateOf<PhotoEntity?>(null) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onEdit) }

    // Back exits selection first, then the screen.
    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }
    BackHandler(enabled = !selectionMode, onBack = onBack)

    Column(
        modifier = modifier.fillMaxSize().background(Color(0xFF0E0E10)).statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                IconButton(onClick = { viewModel.clearSelection() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel selection", tint = Color.White)
                }
                Text(
                    text = "${selected.size} selected",
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { confirmSelected = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete selected", tint = Brand)
                }
            } else {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    text = if (photos.isEmpty()) "Gallery" else "Gallery · ${photos.size}",
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }) {
                    Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Import to edit", tint = Color.White)
                }
            }
        }

        if (photos.isEmpty()) {
            EmptyState(modifier = Modifier.fillMaxSize())
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(photos, key = { it.id }) { photo ->
                    val isSelected = photo.id in selected
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1C1C1F))
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) viewModel.toggle(photo.id) else viewingId = photo.id
                                },
                                onLongClick = {
                                    if (!selectionMode) viewModel.startSelection(photo.id)
                                    else viewModel.toggle(photo.id)
                                },
                            ),
                    ) {
                        PhotoThumb(photo = photo, modifier = Modifier.fillMaxSize())
                        if (photo.isRaw) {
                            RawBadge(modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
                        }
                        if (isSelected) {
                            Box(Modifier.fillMaxSize().background(Color(0x552B6CFF)))
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = "Selected",
                                tint = Brand,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // Full-screen swipeable viewer (only when not selecting). If the photo it opened on is gone —
    // deleted from here or elsewhere — close rather than index into a stale position.
    viewingId?.let { id ->
        val startIndex = photos.indexOfFirst { it.id == id }
        if (startIndex < 0) {
            viewingId = null
        } else {
            PhotoPager(
                photos = photos,
                startIndex = startIndex,
                onClose = { viewingId = null },
                onEdit = { photo -> onEdit(Uri.parse(photo.uri)) },
                onDelete = { photo -> confirmSingle = photo },
            )
        }
    }

    if (confirmSelected) {
        ConfirmDeleteDialog(
            count = selected.size,
            onConfirm = { viewModel.deleteSelected(); confirmSelected = false },
            onDismiss = { confirmSelected = false },
        )
    }
    confirmSingle?.let { photo ->
        ConfirmDeleteDialog(
            count = 1,
            onConfirm = { viewModel.deleteSingle(photo); confirmSingle = null; viewingId = null },
            onDismiss = { confirmSingle = null },
        )
    }
}

@Composable
private fun ConfirmDeleteDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Delete photo?" else "Delete $count photos?") },
        text = { Text("This can't be undone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = Brand) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.PhotoLibrary,
            contentDescription = null,
            tint = Color(0x66FFFFFF),
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = "No photos yet",
            color = Color(0x99FFFFFF),
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * Full-screen viewer you can swipe through, opening at [startIndex]. The header and its actions
 * follow whichever page is showing, so Edit and Delete always act on the photo in front of you.
 */
@Composable
private fun PhotoPager(
    photos: List<PhotoEntity>,
    startIndex: Int,
    onClose: () -> Unit,
    onEdit: (PhotoEntity) -> Unit,
    onDelete: (PhotoEntity) -> Unit,
) {
    BackHandler(onBack = onClose)
    val pagerState = rememberPagerState(initialPage = startIndex) { photos.size }
    val current = photos.getOrNull(pagerState.currentPage)

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xF2000000)),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            PhotoThumb(
                photo = photos[page],
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 64.dp)
                    // Tap-to-close as a gesture rather than `clickable`, which would swallow the
                    // pager's horizontal drags.
                    .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
                contentScale = ContentScale.Fit,
                previewPx = RAW_FULL_PX,
            )
        }

        if (current != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
                Text(
                    text = current.filterName,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp),
                )
                if (current.isRaw) {
                    RawBadge(modifier = Modifier.padding(start = 8.dp))
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { onEdit(current) }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = Color.White)
                }
                IconButton(onClick = { onDelete(current) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Brand)
                }
            }

            if (photos.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${photos.size}",
                    color = Color(0x99FFFFFF),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                )
            }
        }
    }
}
