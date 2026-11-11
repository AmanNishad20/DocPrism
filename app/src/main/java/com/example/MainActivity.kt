package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.PdfViewerModal
import com.example.ui.components.SettingsBottomSheet
import com.example.ui.screens.WorkspaceScreen
import com.example.ui.theme.AmberSheet500
import com.example.ui.theme.EmeraldCache400
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.DocPrismViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: DocPrismViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        inspectIncomingIntent(intent)

        setContent {
            val settings by viewModel.settingsState.collectAsStateWithLifecycle()

            MyApplicationTheme(
                themePreference = settings.themePreference,
                dynamicColor = true
            ) {
                DocPrismSinglePageApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        inspectIncomingIntent(intent)
    }

    private fun inspectIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    viewModel.handleIncomingUri(uri, autoOpenViewer = true)
                }
            }
            Intent.ACTION_SEND -> {
                val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                streamUri?.let { uri ->
                    viewModel.handleIncomingUri(uri, autoOpenViewer = true)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocPrismSinglePageApp(
    viewModel: DocPrismViewModel
) {
    val context = LocalContext.current
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val cachedPdfs by viewModel.cachedPdfsState.collectAsStateWithLifecycle()
    val jobs by viewModel.jobsState.collectAsStateWithLifecycle()
    val activePdfSession by viewModel.activePdfSession.collectAsStateWithLifecycle()
    val osNetworkOnline by viewModel.osNetworkOnline.collectAsStateWithLifecycle()
    val statusToast by viewModel.statusToast.collectAsStateWithLifecycle()

    var showSettingsSheet by remember { mutableStateOf(false) }

    val isEffectiveOffline = !osNetworkOnline || settings.simulateOfflineMode
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusToast) {
        statusToast?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissToast()
        }
    }

    val openOfficeDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.handleIncomingUri(uri, autoOpenViewer = true)
        }
    }

    val savePdfDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { targetUri: Uri? ->
        if (targetUri != null) {
            viewModel.saveActivePdfToDestinationUri(targetUri)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "DocPrism",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        FilterChip(
                            selected = isEffectiveOffline,
                            onClick = { viewModel.setSimulateOffline(!settings.simulateOfflineMode) },
                            label = {
                                Text(
                                    text = if (isEffectiveOffline) "Offline" else "Online",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isEffectiveOffline) Icons.Default.CloudOff else Icons.Default.CloudDone,
                                    contentDescription = "Toggle Offline Mode",
                                    tint = if (isEffectiveOffline) AmberSheet500 else EmeraldCache400,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AmberSheet500.copy(alpha = 0.18f)
                            ),
                            modifier = Modifier.testTag("toggle_offline_chip")
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        // Settings button
                        IconButton(
                            onClick = { showSettingsSheet = true },
                            modifier = Modifier.testTag("settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                WorkspaceScreen(
                    isOffline = isEffectiveOffline,
                    cachedPdfs = cachedPdfs,
                    jobs = jobs,
                    onPickSystemFile = {
                        openOfficeDocumentLauncher.launch(arrayOf("*/*"))
                    },
                    onOpenCachedPdf = { entity ->
                        viewModel.openCachedEntity(entity)
                    },
                    onShareCachedPdf = { entity ->
                        viewModel.shareCachedPdf(context, entity)
                    },
                    onDeleteCachedPdf = { entity ->
                        viewModel.deleteCachedItem(entity)
                    },
                    onClearAllCache = {
                        viewModel.clearAllCachedPdfs()
                    },
                    onRetryJob = { jobId ->
                        viewModel.retryJob(jobId)
                    },
                    onDismissJobs = {
                        viewModel.clearCompletedJobs()
                    },
                    onToggleOfflineSimulation = {
                        viewModel.setSimulateOffline(false)
                    }
                )
            }
        }

        // Settings Bottom Sheet
        if (showSettingsSheet) {
            SettingsBottomSheet(
                settings = settings,
                cachedCount = cachedPdfs.size,
                onDismiss = { showSettingsSheet = false },
                onThemeSelected = { pref -> viewModel.setThemePreference(pref) },
                onToggleFitWideSheets = { enabled -> viewModel.setFitWideSpreadsheets(enabled) },
                onUpdateMaxFileSizeMb = { mb -> viewModel.setMaxFileSizeMb(mb) },
                onClearAllCache = {
                    viewModel.clearAllCachedPdfs()
                    showSettingsSheet = false
                }
            )
        }

        // Full-Screen Smooth PDF Viewer Modal when a document is opened
        activePdfSession?.let { session ->
            PdfViewerModal(
                session = session,
                onClose = { viewModel.closePdfViewer() },
                onSaveToDevice = {
                    val baseName = session.entity.originalFileName.substringBeforeLast('.')
                    savePdfDocumentLauncher.launch("${baseName}.pdf")
                },
                onSharePdf = {
                    viewModel.shareCachedPdf(context, session.entity)
                }
            )
        }
    }
}
