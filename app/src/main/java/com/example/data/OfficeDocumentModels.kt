package com.example.data

import androidx.compose.ui.graphics.Color

enum class DocumentCategory(val label: String) {
    WORD_PROCESSOR("Word Document"),
    SPREADSHEET("Wide Spreadsheet"),
    PRESENTATION("Presentation Deck"),
    OPEN_DOCUMENT("OpenDocument"),
    TEXT_DATA("Text & Tabular")
}

enum class PageLayoutMode(val label: String) {
    PORTRAIT_PAGINATED("A4 Portrait • Exact Pagination"),
    LANDSCAPE_FIT_WIDTH("A4 Landscape • Fit All Columns to Page Width"),
    SLIDE_WIDESCREEN("16:9 Widescreen Slide Canvas")
}

enum class OfficeFormat(
    val extension: String,
    val displayName: String,
    val mimeTypes: List<String>,
    val category: DocumentCategory,
    val layoutMode: PageLayoutMode,
    val badgeColor: Color
) {
    DOC(
        extension = "doc",
        displayName = "Word 97-2003",
        mimeTypes = listOf("application/msword"),
        category = DocumentCategory.WORD_PROCESSOR,
        layoutMode = PageLayoutMode.PORTRAIT_PAGINATED,
        badgeColor = Color(0xFF4A6572)
    ),
    DOCX(
        extension = "docx",
        displayName = "Word OpenXML",
        mimeTypes = listOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        category = DocumentCategory.WORD_PROCESSOR,
        layoutMode = PageLayoutMode.PORTRAIT_PAGINATED,
        badgeColor = Color(0xFF1E88E5)
    ),
    XLS(
        extension = "xls",
        displayName = "Excel 97-2003",
        mimeTypes = listOf("application/vnd.ms-excel"),
        category = DocumentCategory.SPREADSHEET,
        layoutMode = PageLayoutMode.LANDSCAPE_FIT_WIDTH,
        badgeColor = Color(0xFF43A047)
    ),
    XLSX(
        extension = "xlsx",
        displayName = "Excel OpenXML",
        mimeTypes = listOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        category = DocumentCategory.SPREADSHEET,
        layoutMode = PageLayoutMode.LANDSCAPE_FIT_WIDTH,
        badgeColor = Color(0xFF2E7D32)
    ),
    PPT(
        extension = "ppt",
        displayName = "PowerPoint 97-2003",
        mimeTypes = listOf("application/vnd.ms-powerpoint"),
        category = DocumentCategory.PRESENTATION,
        layoutMode = PageLayoutMode.SLIDE_WIDESCREEN,
        badgeColor = Color(0xFFFB8C00)
    ),
    PPTX(
        extension = "pptx",
        displayName = "PowerPoint OpenXML",
        mimeTypes = listOf("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        category = DocumentCategory.PRESENTATION,
        layoutMode = PageLayoutMode.SLIDE_WIDESCREEN,
        badgeColor = Color(0xFFE64A19)
    ),
    ODT(
        extension = "odt",
        displayName = "OpenDocument Text",
        mimeTypes = listOf("application/vnd.oasis.opendocument.text"),
        category = DocumentCategory.OPEN_DOCUMENT,
        layoutMode = PageLayoutMode.PORTRAIT_PAGINATED,
        badgeColor = Color(0xFF7E57C2)
    ),
    ODS(
        extension = "ods",
        displayName = "OpenDocument Sheet",
        mimeTypes = listOf("application/vnd.oasis.opendocument.spreadsheet"),
        category = DocumentCategory.SPREADSHEET,
        layoutMode = PageLayoutMode.LANDSCAPE_FIT_WIDTH,
        badgeColor = Color(0xFF7E57C2)
    ),
    ODP(
        extension = "odp",
        displayName = "OpenDocument Presentation",
        mimeTypes = listOf("application/vnd.oasis.opendocument.presentation"),
        category = DocumentCategory.PRESENTATION,
        layoutMode = PageLayoutMode.SLIDE_WIDESCREEN,
        badgeColor = Color(0xFF7E57C2)
    ),
    RTF(
        extension = "rtf",
        displayName = "Rich Text Format",
        mimeTypes = listOf("application/rtf", "text/rtf"),
        category = DocumentCategory.WORD_PROCESSOR,
        layoutMode = PageLayoutMode.PORTRAIT_PAGINATED,
        badgeColor = Color(0xFF8D6E63)
    ),
    CSV(
        extension = "csv",
        displayName = "Comma-Separated Values",
        mimeTypes = listOf("text/csv", "text/comma-separated-values"),
        category = DocumentCategory.SPREADSHEET,
        layoutMode = PageLayoutMode.LANDSCAPE_FIT_WIDTH,
        badgeColor = Color(0xFF00897B)
    ),
    TXT(
        extension = "txt",
        displayName = "Plain Text Multilingual",
        mimeTypes = listOf("text/plain"),
        category = DocumentCategory.TEXT_DATA,
        layoutMode = PageLayoutMode.PORTRAIT_PAGINATED,
        badgeColor = Color(0xFF546E7A)
    );

    val isSpreadsheet: Boolean
        get() = this == XLS || this == XLSX || this == ODS || this == CSV

    companion object {
        fun fromFileNameOrMime(fileName: String, mimeType: String?): OfficeFormat? {
            val cleanName = fileName.lowercase().trim()
            val ext = cleanName.substringAfterLast('.', "")
            entries.firstOrNull { it.extension == ext }?.let { return it }
            if (mimeType != null) {
                val lowerMime = mimeType.lowercase()
                entries.firstOrNull { fmt -> fmt.mimeTypes.any { it == lowerMime } }?.let { return it }
            }
            return null
        }

        val supportedExtensionsSummary: String
            get() = entries.joinToString(", ") { it.extension.uppercase() }
    }
}

sealed class ConversionFailureReason(
    val title: String,
    val userMessage: String,
    val technicalCode: String,
    val isRetryable: Boolean
) {
    data class PasswordProtected(val encryptionType: String) : ConversionFailureReason(
        title = "Password-Protected Document",
        userMessage = "This Office file is encrypted ($encryptionType). Headless server conversion cannot unlock password-protected documents without decryption. Please remove the password in Office and try again.",
        technicalCode = "ERR_OFFICE_PASSWORD_PROTECTED_422",
        isRetryable = false
    )

    data class CorruptedFile(val diagnostic: String) : ConversionFailureReason(
        title = "Corrupted or Damaged File",
        userMessage = "The Office container structure could not be verified ($diagnostic). The file may be truncated or incomplete.",
        technicalCode = "ERR_CONTAINER_CORRUPT_400",
        isRetryable = false
    )

    data class UnsupportedFormat(val detectedExt: String) : ConversionFailureReason(
        title = "Unsupported File Format (.${detectedExt.ifEmpty { "unknown" }})",
        userMessage = "Only ${OfficeFormat.supportedExtensionsSummary} formats are supported by the conversion pipeline.",
        technicalCode = "ERR_UNSUPPORTED_MEDIA_TYPE_415",
        isRetryable = false
    )

    data class FileSizeLimitExceeded(val actualBytes: Long, val maxBytes: Long) : ConversionFailureReason(
        title = "File Exceeds Size Limit",
        userMessage = "File size (${formatBytes(actualBytes)}) exceeds the ${formatBytes(maxBytes)} free-tier memory protection cap.",
        technicalCode = "ERR_PAYLOAD_TOO_LARGE_413",
        isRetryable = false
    )

    data class ConversionTimeout(val timeoutSec: Int) : ConversionFailureReason(
        title = "Conversion Timed Out (${timeoutSec}s)",
        userMessage = "The server took longer than ${timeoutSec} seconds to render this document. Tap Retry to re-queue with priority.",
        technicalCode = "ERR_GATEWAY_TIMEOUT_504",
        isRetryable = true
    )

    data class RateLimitExceeded(val retryAfterSec: Int, val limitPerMin: Int) : ConversionFailureReason(
        title = "Rate Limit Reached ($limitPerMin req/min)",
        userMessage = "Too many conversions requested in a short window. Please wait ${retryAfterSec}s and tap Retry.",
        technicalCode = "ERR_TOO_MANY_REQUESTS_429",
        isRetryable = true
    )

    data class OfflineUnavailable(val fileName: String) : ConversionFailureReason(
        title = "You're Currently Offline",
        userMessage = "\"$fileName\" hasn't been converted yet. Connect to the internet to convert new files, or open any previously cached PDF below.",
        technicalCode = "ERR_NETWORK_OFFLINE_503",
        isRetryable = true
    )

    data class ServerFailure(val httpCode: Int, val detail: String) : ConversionFailureReason(
        title = "Conversion Service Error ($httpCode)",
        userMessage = "The online conversion worker encountered an issue: $detail. Tap Retry to attempt again.",
        technicalCode = "ERR_WORKER_HTTP_$httpCode",
        isRetryable = true
    )
}

enum class JobStatus(val label: String) {
    QUEUED("In Queue"),
    HASHING_CACHE_CHECK("Checking SHA-256 Cache"),
    UPLOADING_TLS("TLS 1.3 Encrypted Upload"),
    CONVERTING_SERVER("Server LibreOffice Render"),
    PURGING_SERVER("Immediate Server Wipe"),
    COMPLETED("Converted & Cached"),
    INSTANT_CACHE_HIT("Instant Offline Cache Hit"),
    FAILED("Failed")
}

data class ConversionJob(
    val jobId: String,
    val fileName: String,
    val format: OfficeFormat?,
    val fileSizeBytes: Long,
    val sha256Hash: String = "",
    val sourceFilePath: String? = null,
    val sourceUriString: String? = null,
    val status: JobStatus = JobStatus.QUEUED,
    val progressFraction: Float = 0f,
    val stageDetail: String = "Waiting in conversion queue...",
    val queuePosition: Int = 1,
    val elapsedMs: Long = 0L,
    val cachedPdfPath: String? = null,
    val pageCount: Int = 0,
    val scriptsRendered: List<String> = emptyList(),
    val fitSpreadsheetWidth: Boolean = true,
    val serverPurgedAtMs: Long? = null,
    val failureReason: ConversionFailureReason? = null,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class DocumentChartSeries(
    val label: String,
    val value: Float,
    val target: Float,
    val unit: String = "M"
)

data class DocumentSection(
    val heading: String,
    val paragraphs: List<String>,
    val multilingualSamples: List<Pair<String, String>> = emptyList(), // Script Name -> Sample Text
    val tableHeaders: List<String> = emptyList(),
    val tableRows: List<List<String>> = emptyList(),
    val chartTitle: String? = null,
    val chartSeries: List<DocumentChartSeries> = emptyList(),
    val forcePageBreakAfter: Boolean = false
)

data class ParsedOfficeDocument(
    val title: String,
    val subtitle: String,
    val format: OfficeFormat,
    val runningHeader: String,
    val runningFooter: String,
    val scriptsDetected: List<String>,
    val fitSpreadsheetToPageWidth: Boolean,
    val naturalColumnsCount: Int,
    val sections: List<DocumentSection>
)

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format("%.2f MB", mb)
}
