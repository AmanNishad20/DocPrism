package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.formatBytes
import com.example.viewmodel.ActivePdfSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PdfViewerModal(
    session: ActivePdfSession,
    onClose: () -> Unit,
    onSaveToDevice: () -> Unit,
    onSharePdf: () -> Unit
) {
    BackHandler(onBack = onClose)

    val entity = session.entity
    var renderedPages by remember(entity.localPdfPath) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var isLoadingPages by remember(entity.localPdfPath) { mutableStateOf(true) }
    var renderError by remember(entity.localPdfPath) { mutableStateOf<String?>(null) }

    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var invertDarkPaper by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val currentVisiblePage by remember {
        derivedStateOf { (listState.firstVisibleItemIndex + 1).coerceAtLeast(1) }
    }

    LaunchedEffect(entity.localPdfPath) {
        isLoadingPages = true
        renderError = null
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(entity.localPdfPath)
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val bitmaps = mutableListOf<Bitmap>()
                try {
                    for (i in 0 until renderer.pageCount) {
                        val page = renderer.openPage(i)
                        val width = (page.width * 2.2f).toInt().coerceAtLeast(800)
                        val height = (page.height * 2.2f).toInt().coerceAtLeast(600)
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        bitmaps.add(bmp)
                    }
                } finally {
                    renderer.close()
                    pfd.close()
                }
                renderedPages = bitmaps
                isLoadingPages = false
            }.onFailure { e ->
                renderError = e.message ?: "Failed to render PDF"
                isLoadingPages = false
            }
        }
    }

    DisposableEffect(entity.localPdfPath) {
        onDispose { renderedPages = emptyList() }
    }

    val colorFilter: ColorFilter? = remember(invertDarkPaper) {
        if (!invertDarkPaper) null
        else ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    -0.88f, 0f, 0f, 0f, 240f,
                    0f, -0.88f, 0f, 0f, 242f,
                    0f, 0f, -0.85f, 0f, 250f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("pdf_viewer_modal"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Minimal, clean top bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("close_pdf_viewer_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp)
                    ) {
                        Text(
                            text = entity.originalFileName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                append("${entity.pageCount} ${if (entity.pageCount == 1) "page" else "pages"}")
                                append(" • ${formatBytes(entity.pdfSizeBytes)}")
                                if (session.openedFromCacheHit) append(" • Cached Offline")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (zoomScale > 1.02f) {
                        IconButton(
                            onClick = {
                                zoomScale = 1f
                                panOffset = Offset.Zero
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.FitScreen,
                                contentDescription = "Reset Zoom"
                            )
                        }
                    }

                    IconButton(
                        onClick = { invertDarkPaper = !invertDarkPaper },
                        modifier = Modifier.testTag("toggle_pdf_paper_filter")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Contrast,
                            contentDescription = "Toggle Dark Paper Mode"
                        )
                    }

                    IconButton(
                        onClick = onSaveToDevice,
                        modifier = Modifier.testTag("save_pdf_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Save PDF"
                        )
                    }

                    IconButton(
                        onClick = onSharePdf,
                        modifier = Modifier.testTag("share_pdf_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share PDF"
                        )
                    }
                }
            }

            // Smooth PDF Page Canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (zoomScale * zoom).coerceIn(1f, 3.2f)
                            zoomScale = newScale
                            panOffset = if (newScale > 1.02f) {
                                Offset(
                                    x = (panOffset.x + pan.x).coerceIn(-600f, 600f),
                                    y = (panOffset.y + pan.y).coerceIn(-600f, 600f)
                                )
                            } else {
                                Offset.Zero
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (zoomScale > 1.1f) {
                                    zoomScale = 1f
                                    panOffset = Offset.Zero
                                } else {
                                    zoomScale = 2.0f
                                }
                            }
                        )
                    }
            ) {
                when {
                    isLoadingPages -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    renderError != null -> {
                        Text(
                            text = renderError ?: "Error loading PDF",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp)
                        )
                    }
                    else -> {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = zoomScale
                                    scaleY = zoomScale
                                    translationX = panOffset.x
                                    translationY = panOffset.y
                                }
                        ) {
                            itemsIndexed(renderedPages) { index, bitmap ->
                                val aspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
                                Card(
                                    shape = RoundedCornerShape(8.dp),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("pdf_page_card_${index + 1}")
                                ) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Page ${index + 1} of ${renderedPages.size}",
                                        contentScale = ContentScale.FillWidth,
                                        colorFilter = colorFilter,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(aspect)
                                    )
                                }
                            }
                        }

                        // Floating minimal page indicator pill at bottom
                        if (renderedPages.isNotEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
                                shape = CircleShape,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(bottom = 16.dp)
                            ) {
                                Text(
                                    text = "Page $currentVisiblePage of ${renderedPages.size}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.inverseOnSurface,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
