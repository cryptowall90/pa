package com.pictureperfectx.app.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pictureperfectx.app.ui.components.AdjustPanel
import com.pictureperfectx.app.ui.components.CameraControls
import com.pictureperfectx.app.ui.components.CameraTopBar
import com.pictureperfectx.app.ui.components.FilterCarousel
import com.pictureperfectx.app.ui.components.IntensitySlider

/**
 * The single-screen camera experience: full-bleed filtered preview with the flash control up top,
 * and the filter carousel + shutter row anchored to the bottom.
 */
@Composable
fun CameraScreen(
    onOpenGallery: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CameraViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is CameraEvent.Saved -> event.message
                is CameraEvent.Error -> event.message
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Live, GPU-filtered camera preview (SurfaceProcessor effect) fills the screen.
            // Tap-to-focus and pinch-to-zoom are handled inside CameraPreview.
            CameraPreview(
                controller = viewModel.controller,
                onFocus = viewModel::onFocus,
                onZoom = viewModel::onZoom,
                modifier = Modifier.fillMaxSize(),
            )

            CameraTopBar(
                flashMode = state.flashMode,
                adjustmentsOpen = state.showAdjustments,
                filtersOpen = state.showFilters,
                onCycleFlash = viewModel::onCycleFlash,
                onToggleAdjustments = viewModel::onToggleAdjustments,
                onToggleFilters = viewModel::onToggleFilters,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 8.dp),
            )

            // Bottom stack: filter carousel (Step 3) above the shutter row (Step 4).
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Manual adjustments: chip row + a single slider (one adjustment at a time).
                if (state.showAdjustments) {
                    AdjustPanel(
                        state = state,
                        onSelect = viewModel::onSelectAdjustment,
                        onExposure = viewModel::onExposureChanged,
                        onBrightness = viewModel::onBrightnessChanged,
                        onContrast = viewModel::onContrastChanged,
                        onSaturation = viewModel::onSaturationChanged,
                    )
                }
                // Filters (carousel + intensity) — hideable for a full-screen camera.
                if (state.showFilters) {
                    if (state.intensityEnabled) {
                        IntensitySlider(
                            filterName = state.selectedFilter?.displayName.orEmpty(),
                            intensity = state.intensity,
                            onIntensityChange = viewModel::onIntensityChanged,
                        )
                    }
                    FilterCarousel(
                        filters = state.filters,
                        selectedFilterId = state.selectedFilterId,
                        onFilterSelected = viewModel::onFilterSelected,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                CameraControls(
                    isSaving = state.isSaving,
                    lastSavedThumbUri = state.lastSavedThumbUri,
                    onCapture = viewModel::onCapture,
                    onToggleLens = viewModel::onToggleLens,
                    onOpenGallery = onOpenGallery,
                )
            }
        }
    }
}
