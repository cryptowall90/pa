package com.pictureperfectx.app.ui.gallery

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.pictureperfectx.app.data.PhotoEntity

private val Brand = Color(0xFFFF4D6D)

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

    var viewing by remember { mutableStateOf<PhotoEntity?>(null) }
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
                                    if (selectionMode) viewModel.toggle(photo.id) else viewing = photo
                                },
                                onLongClick = {
                                    if (!selectionMode) viewModel.startSelection(photo.id)
                                    else viewModel.toggle(photo.id)
                                },
                            ),
                    ) {
                        AsyncImage(
                            model = photo.uri,
                            contentDescription = photo.filterName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
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

    // Full-screen viewer (only when not selecting).
    viewing?.let { photo ->
        PhotoViewer(
            photo = photo,
            onClose = { viewing = null },
            onEdit = { onEdit(Uri.parse(photo.uri)) },
            onDelete = { confirmSingle = photo },
        )
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
            onConfirm = { viewModel.deleteSingle(photo); confirmSingle = null; viewing = null },
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

/** Full-screen single-photo viewer with edit + delete actions. */
@Composable
private fun PhotoViewer(
    photo: PhotoEntity,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2000000))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = photo.filterName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(vertical = 64.dp),
        )
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
                text = photo.filterName,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = Color.White)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Brand)
            }
        }
    }
}
