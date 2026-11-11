package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CachedPdfDatabaseHelper
import com.example.data.CachedPdfEntity
import com.example.data.CachedPdfRepository
import com.example.data.ConversionFailureReason
import com.example.data.ConversionJob
import com.example.data.ConversionPolicySettings
import com.example.data.ConversionSettingsRepository
import com.example.data.JobStatus
import com.example.data.OfficeFormat
import com.example.data.ThemePreference
import com.example.engine.OfficeConversionException
import com.example.engine.OfficeToPdfEngine
import com.example.engine.SampleOfficeAsset
import com.example.engine.SampleOfficeFileFactory
import com.example.network.ConversionNetworkService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ActivePdfSession(
    val entity: CachedPdfEntity,
    val openedFromCacheHit: Boolean,
    val serverWorkerId: String = "local-sha256-cache"
)

class DocPrismViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context
        get() = getApplication<Application>().applicationContext

    private val dbHelper = CachedPdfDatabaseHelper(context)
    private val cacheRepository = CachedPdfRepository(
        dbHelper = dbHelper,
        cacheDirectory = File(context.filesDir, "pdf_cache_v2")
    )
    private val settingsRepository = ConversionSettingsRepository(context)
    private val networkService = ConversionNetworkService(
        scratchDir = File(context.cacheDir, "ephemeral_server_tmp")
    )

    val settingsState: StateFlow<ConversionPolicySettings> = settingsRepository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ConversionPolicySettings()
        )

    val cachedPdfsState: StateFlow<List<CachedPdfEntity>> = cacheRepository.allCachedPdfs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _jobsState = MutableStateFlow<List<ConversionJob>>(emptyList())
    val jobsState: StateFlow<List<ConversionJob>> = _jobsState.asStateFlow()

    private val _activePdfSession = MutableStateFlow<ActivePdfSession?>(null)
    val activePdfSession: StateFlow<ActivePdfSession?> = _activePdfSession.asStateFlow()

    private val _osNetworkOnline = MutableStateFlow(true)
    val osNetworkOnline: StateFlow<Boolean> = _osNetworkOnline.asStateFlow()

    private val _rateLimitUsedInWindow = MutableStateFlow(0)
    val rateLimitUsedInWindow: StateFlow<Int> = _rateLimitUsedInWindow.asStateFlow()

    private val _statusToast = MutableStateFlow<String?>(null)
    val statusToast: StateFlow<String?> = _statusToast.asStateFlow()

    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    init {
        registerNetworkMonitor()
    }

    private fun registerNetworkMonitor() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            val activeNet = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNet)
            _osNetworkOnline.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != false

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _osNetworkOnline.value = true
                }

                override fun onLost(network: Network) {
                    _osNetworkOnline.value = false
                }
            }
            runCatching { cm.registerDefaultNetworkCallback(callback) }
            connectivityCallback = callback
        }
    }

    fun dismissToast() {
        _statusToast.value = null
    }

    fun showToast(message: String) {
        _statusToast.value = message
    }

    fun closePdfViewer() {
        _activePdfSession.value = null
    }

    fun openCachedEntity(entity: CachedPdfEntity) {
        viewModelScope.launch {
            val verified = withContext(Dispatchers.IO) {
                cacheRepository.lookupValidCachedPdf(entity.sha256Hash)
            }
            if (verified != null) {
                withContext(Dispatchers.IO) {
                    cacheRepository.recordCacheHit(verified.sha256Hash)
                }
                _activePdfSession.value = ActivePdfSession(
                    entity = verified.copy(cacheHitCount = verified.cacheHitCount + 1),
                    openedFromCacheHit = true
                )
            } else {
                showToast("Cached PDF file was missing on disk; please reconvert.")
            }
        }
    }

    fun processSampleAsset(asset: SampleOfficeAsset, autoOpenViewer: Boolean = true) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                SampleOfficeFileFactory.materializeSampleFile(context, asset)
            }
            enqueueAndProcessFile(
                fileName = asset.fileName,
                mimeType = asset.format?.mimeTypes?.firstOrNull(),
                sourceFile = file,
                sourceUri = null,
                autoOpenViewer = autoOpenViewer
            )
        }
    }

    fun runBatchQueueSimulation() {
        val batchAssets = listOf(
            SampleOfficeFileFactory.catalog[2], // PPTX Keynote
            SampleOfficeFileFactory.catalog[3], // Wide 10-Col CSV
            SampleOfficeFileFactory.catalog[4], // ODT Engineering Standard
            SampleOfficeFileFactory.catalog[5]  // ODS Budget Spreadsheet
        )
        showToast("Queued 4 Office documents into FIFO conversion pipeline...")
        batchAssets.forEachIndexed { idx, asset ->
            processSampleAsset(asset, autoOpenViewer = (idx == 0))
        }
    }

    fun handleIncomingUri(uri: Uri, autoOpenViewer: Boolean = true) {
        viewModelScope.launch {
            val meta = withContext(Dispatchers.IO) { resolveUriMetadata(uri) }
            enqueueAndProcessFile(
                fileName = meta.first,
                mimeType = meta.second,
                sourceFile = null,
                sourceUri = uri,
                autoOpenViewer = autoOpenViewer
            )
        }
    }

    fun retryJob(jobId: String) {
        val existing = _jobsState.value.firstOrNull { it.jobId == jobId } ?: return
        _jobsState.update { list -> list.filterNot { it.jobId == jobId } }
        if (existing.sourceFilePath != null) {
            val file = File(existing.sourceFilePath)
            if (file.exists()) {
                enqueueAndProcessFile(
                    fileName = existing.fileName,
                    mimeType = existing.format?.mimeTypes?.firstOrNull(),
                    sourceFile = file,
                    sourceUri = null,
                    autoOpenViewer = true
                )
                return
            }
        }
        if (existing.sourceUriString != null) {
            handleIncomingUri(Uri.parse(existing.sourceUriString), autoOpenViewer = true)
        }
    }

    private fun enqueueAndProcessFile(
        fileName: String,
        mimeType: String?,
        sourceFile: File?,
        sourceUri: Uri?,
        autoOpenViewer: Boolean
    ) {
        val jobId = UUID.randomUUID().toString()
        val detectedFormat = OfficeFormat.fromFileNameOrMime(fileName, mimeType)
        val initialJob = ConversionJob(
            jobId = jobId,
            fileName = fileName,
            format = detectedFormat,
            fileSizeBytes = sourceFile?.length() ?: 0L,
            sourceFilePath = sourceFile?.absolutePath,
            sourceUriString = sourceUri?.toString(),
            status = JobStatus.HASHING_CACHE_CHECK,
            progressFraction = 0.05f,
            stageDetail = "Computing SHA-256 content digest...",
            queuePosition = (_jobsState.value.count { it.status == JobStatus.QUEUED || it.status == JobStatus.UPLOADING_TLS || it.status == JobStatus.CONVERTING_SERVER } + 1)
        )
        _jobsState.update { listOf(initialJob) + it }

        viewModelScope.launch(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            try {
                val bytes = when {
                    sourceFile != null -> sourceFile.readBytes()
                    sourceUri != null -> context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                        ?: throw OfficeConversionException(ConversionFailureReason.CorruptedFile("Unable to open input stream"))
                    else -> ByteArray(0)
                }

                val sha256 = OfficeToPdfEngine.computeSha256(bytes)
                updateJob(jobId) {
                    it.copy(
                        fileSizeBytes = bytes.size.toLong(),
                        sha256Hash = sha256,
                        progressFraction = 0.10f
                    )
                }

                // Step 1: Check SHA-256 On-Device Cache FIRST (works offline, never converts the same file twice!)
                val cachedEntity = cacheRepository.lookupValidCachedPdf(sha256)
                if (cachedEntity != null) {
                    cacheRepository.recordCacheHit(sha256)
                    val elapsed = System.currentTimeMillis() - startMs
                    val updatedEntity = cachedEntity.copy(
                        cacheHitCount = cachedEntity.cacheHitCount + 1,
                        lastOpenedAtEpochMs = System.currentTimeMillis()
                    )
                    updateJob(jobId) {
                        it.copy(
                            status = JobStatus.INSTANT_CACHE_HIT,
                            progressFraction = 1.0f,
                            stageDetail = "Instant SHA-256 Cache Hit (0 bytes uploaded • Offline Ready)",
                            elapsedMs = elapsed,
                            cachedPdfPath = updatedEntity.localPdfPath,
                            pageCount = updatedEntity.pageCount,
                            scriptsRendered = updatedEntity.scriptsDetected.split(",").map { s -> s.trim() }
                        )
                    }
                    if (autoOpenViewer) {
                        _activePdfSession.value = ActivePdfSession(
                            entity = updatedEntity,
                            openedFromCacheHit = true
                        )
                    }
                    return@launch
                }

                // Step 2: Not in cache -> Queue & Convert via Online Conversion Service
                val currentSettings = settingsState.value
                val isOnline = _osNetworkOnline.value && !currentSettings.simulateOfflineMode

                updateJob(jobId) {
                    it.copy(
                        status = JobStatus.QUEUED,
                        progressFraction = 0.15f,
                        stageDetail = "Queued for stateless conversion worker...",
                        fitSpreadsheetWidth = currentSettings.fitWideSpreadsheets
                    )
                }

                val result = networkService.convertWithQueueAndGuards(
                    fileName = fileName,
                    mimeType = mimeType,
                    fileBytes = bytes,
                    sha256Hash = sha256,
                    settings = currentSettings,
                    isDeviceOnline = isOnline,
                    onStageProgress = { fraction, stageText ->
                        val stepStatus = when {
                            fraction < 0.45f -> JobStatus.UPLOADING_TLS
                            fraction < 0.80f -> JobStatus.CONVERTING_SERVER
                            else -> JobStatus.PURGING_SERVER
                        }
                        updateJob(jobId) { job ->
                            job.copy(
                                status = stepStatus,
                                progressFraction = fraction,
                                stageDetail = stageText
                            )
                        }
                    }
                )

                _rateLimitUsedInWindow.value = networkService.rateLimiter.currentWindowCount()

                // Step 3: Save converted PDF to local SHA-256 cache on device
                val targetPdfFile = cacheRepository.getTargetCacheFile(sha256)
                targetPdfFile.writeBytes(result.pdfBytes)

                val verifiedFormat = detectedFormat ?: OfficeFormat.DOCX
                val entity = CachedPdfEntity(
                    sha256Hash = sha256,
                    originalFileName = fileName,
                    formatExtension = verifiedFormat.extension,
                    originalSizeBytes = bytes.size.toLong(),
                    pdfSizeBytes = targetPdfFile.length(),
                    localPdfPath = targetPdfFile.absolutePath,
                    pageCount = result.pageCount,
                    fitToWidthApplied = result.fitWidthApplied,
                    scriptsDetected = result.scriptsRendered.joinToString(", "),
                    serverPurgeVerified = true,
                    convertedAtEpochMs = System.currentTimeMillis(),
                    lastOpenedAtEpochMs = System.currentTimeMillis(),
                    cacheHitCount = 1
                )
                cacheRepository.saveConvertedPdf(entity)

                val totalElapsed = System.currentTimeMillis() - startMs
                updateJob(jobId) {
                    it.copy(
                        format = verifiedFormat,
                        status = JobStatus.COMPLETED,
                        progressFraction = 1.0f,
                        stageDetail = "Converted (${result.pageCount} pages) • Server files deleted • Cached offline",
                        elapsedMs = totalElapsed,
                        cachedPdfPath = targetPdfFile.absolutePath,
                        pageCount = result.pageCount,
                        scriptsRendered = result.scriptsRendered,
                        serverPurgedAtMs = result.serverPurgedAtEpochMs
                    )
                }

                if (autoOpenViewer) {
                    _activePdfSession.value = ActivePdfSession(
                        entity = entity,
                        openedFromCacheHit = false,
                        serverWorkerId = result.serverWorkerId
                    )
                }
            } catch (e: OfficeConversionException) {
                _rateLimitUsedInWindow.value = networkService.rateLimiter.currentWindowCount()
                updateJob(jobId) {
                    it.copy(
                        status = JobStatus.FAILED,
                        progressFraction = 1.0f,
                        stageDetail = e.reason.title,
                        elapsedMs = System.currentTimeMillis() - startMs,
                        failureReason = e.reason
                    )
                }
            } catch (e: Exception) {
                val reason = ConversionFailureReason.ServerFailure(500, e.message ?: "Unexpected conversion error")
                updateJob(jobId) {
                    it.copy(
                        status = JobStatus.FAILED,
                        progressFraction = 1.0f,
                        stageDetail = reason.title,
                        elapsedMs = System.currentTimeMillis() - startMs,
                        failureReason = reason
                    )
                }
            }
        }
    }

    private fun updateJob(jobId: String, transform: (ConversionJob) -> ConversionJob) {
        _jobsState.update { list ->
            list.map { if (it.jobId == jobId) transform(it) else it }
        }
    }

    fun deleteCachedItem(entity: CachedPdfEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            cacheRepository.deleteCachedPdf(entity)
            showToast("Removed ${entity.originalFileName} from local cache")
        }
    }

    fun clearAllCachedPdfs() {
        viewModelScope.launch(Dispatchers.IO) {
            cacheRepository.purgeEntireCache()
            showToast("Purged all cached PDFs from device storage")
        }
    }

    fun clearCompletedJobs() {
        _jobsState.update { list ->
            list.filter { it.status == JobStatus.QUEUED || it.status == JobStatus.UPLOADING_TLS || it.status == JobStatus.CONVERTING_SERVER }
        }
    }

    fun saveActivePdfToDestinationUri(destinationUri: Uri) {
        val session = _activePdfSession.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val srcFile = File(session.entity.localPdfPath)
                context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                    srcFile.inputStream().use { input -> input.copyTo(out) }
                }
                showToast("Saved converted PDF to device storage")
            }.onFailure {
                showToast("Failed to save PDF: ${it.message}")
            }
        }
    }

    fun shareCachedPdf(activityContext: Context, entity: CachedPdfEntity) {
        runCatching {
            val pdfFile = File(entity.localPdfPath)
            if (!pdfFile.exists()) {
                showToast("PDF file not found in cache")
                return
            }
            val contentUri = FileProvider.getUriForFile(
                activityContext,
                "${activityContext.packageName}.fileprovider",
                pdfFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "${entity.originalFileName.substringBeforeLast('.')}.pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activityContext.startActivity(
                Intent.createChooser(shareIntent, "Share Converted PDF")
            )
        }.onFailure {
            showToast("Unable to launch share sheet: ${it.message}")
        }
    }

    // Settings updates
    fun setSimulateOffline(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSimulateOffline(enabled)
            showToast(
                if (enabled) "Offline Simulation Enabled: Cached PDFs still open instantly!"
                else "Online Mode Restored: Cloud conversion pipeline ready."
            )
        }
    }

    fun setFitWideSpreadsheets(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateFitWideSpreadsheets(enabled) }
    }

    fun setMaxFileSizeMb(sizeMb: Int) {
        viewModelScope.launch { settingsRepository.updateMaxFileSizeMb(sizeMb) }
    }

    fun setTimeoutSeconds(sec: Int) {
        viewModelScope.launch { settingsRepository.updateTimeoutSec(sec) }
    }

    fun setRateLimitPerMinute(limit: Int) {
        viewModelScope.launch { settingsRepository.updateRateLimitPerMin(limit) }
    }

    fun setServerEndpointUrl(url: String) {
        viewModelScope.launch {
            settingsRepository.updateServerUrl(url)
            showToast("Updated conversion server endpoint")
        }
    }

    fun setThemePreference(pref: ThemePreference) {
        viewModelScope.launch { settingsRepository.updateThemePreference(pref) }
    }

    private fun resolveUriMetadata(uri: Uri): Pair<String, String?> {
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Shared_Office_Document.docx"
        val mimeType = context.contentResolver.getType(uri)
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) displayName = name
                    }
                }
            }
        }
        if (!displayName.contains('.') && mimeType != null) {
            val fmt = OfficeFormat.fromFileNameOrMime("", mimeType)
            if (fmt != null) {
                displayName = "$displayName.${fmt.extension}"
            }
        }
        return displayName to mimeType
    }

    override fun onCleared() {
        super.onCleared()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        connectivityCallback?.let { cb ->
            runCatching { cm?.unregisterNetworkCallback(cb) }
        }
    }
}
