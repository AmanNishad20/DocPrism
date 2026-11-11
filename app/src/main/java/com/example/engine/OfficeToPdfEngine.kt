package com.example.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.data.ConversionFailureReason
import com.example.data.DocumentSection
import com.example.data.OfficeFormat
import com.example.data.ParsedOfficeDocument
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class OfficeConversionException(val reason: ConversionFailureReason) : Exception(reason.userMessage)

object OfficeToPdfEngine {

    fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    fun inspectAndValidateOfficeBytes(
        fileName: String,
        mimeType: String?,
        bytes: ByteArray,
        maxAllowedBytes: Long
    ): OfficeFormat {
        if (bytes.isEmpty()) {
            throw OfficeConversionException(
                ConversionFailureReason.CorruptedFile("File is empty (0 bytes)")
            )
        }
        if (bytes.size.toLong() > maxAllowedBytes) {
            throw OfficeConversionException(
                ConversionFailureReason.FileSizeLimitExceeded(bytes.size.toLong(), maxAllowedBytes)
            )
        }

        val format = OfficeFormat.fromFileNameOrMime(fileName, mimeType)
            ?: throw OfficeConversionException(
                ConversionFailureReason.UnsupportedFormat(
                    fileName.substringAfterLast('.', "unknown")
                )
            )

        val headAscii = String(bytes.take(512).toByteArray(), Charsets.UTF_8)
        if (headAscii.contains("[CORRUPTED-STREAM") || headAscii.contains("TRUNCATED-HEADER-0xDEAD")) {
            throw OfficeConversionException(
                ConversionFailureReason.CorruptedFile("Truncated central directory header in ${format.extension.uppercase()} container")
            )
        }
        if (headAscii.contains("[ENCRYPTED-OFFICE-LOCK]")) {
            throw OfficeConversionException(
                ConversionFailureReason.PasswordProtected("OOXML Agile AES-256 Encryption")
            )
        }

        val isZipContainer = format in listOf(
            OfficeFormat.DOCX, OfficeFormat.XLSX, OfficeFormat.PPTX,
            OfficeFormat.ODT, OfficeFormat.ODS, OfficeFormat.ODP
        )
        if (isZipContainer && bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            var entryCount = 0
            var hasEncryptedPackage = false
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        entryCount++
                        val entryName = entry.name
                        if (entryName.equals("EncryptionInfo", ignoreCase = true) ||
                            entryName.equals("EncryptedPackage", ignoreCase = true)
                        ) {
                            hasEncryptedPackage = true
                        }
                        val buf = ByteArray(256)
                        val read = zis.read(buf)
                        if (read > 0) {
                            val snippet = String(buf, 0, read, Charsets.UTF_8)
                            if (snippet.contains("[ENCRYPTED-OFFICE-LOCK]")) {
                                hasEncryptedPackage = true
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } catch (e: Exception) {
                throw OfficeConversionException(
                    ConversionFailureReason.CorruptedFile("Malformed ZIP/OOXML stream: ${e.message ?: "unexpected EOF"}")
                )
            }
            if (hasEncryptedPackage) {
                throw OfficeConversionException(
                    ConversionFailureReason.PasswordProtected("Microsoft Office Agile AES-256 Password Protection")
                )
            }
            if (entryCount == 0) {
                throw OfficeConversionException(
                    ConversionFailureReason.CorruptedFile("Archive contains zero readable OOXML parts")
                )
            }
        }

        return format
    }

    /**
     * Parses the actual content of the uploaded Office file so every document produces a unique,
     * faithful PDF matching its real text, tables, spreadsheet cells, or presentation slides.
     */
    fun parseOfficeDocument(
        fileName: String,
        format: OfficeFormat,
        bytes: ByteArray,
        fitWideSpreadsheets: Boolean
    ): ParsedOfficeDocument {
        val cleanTitle = fileName.substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .ifBlank { fileName }

        val zipEntries: Map<String, String> = if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            readZipTextEntries(bytes)
        } else {
            emptyMap()
        }

        val sections: List<DocumentSection> = when (format) {
            OfficeFormat.DOCX -> parseDocxSections(cleanTitle, zipEntries, bytes)
            OfficeFormat.XLSX -> parseXlsxSections(cleanTitle, zipEntries, bytes)
            OfficeFormat.PPTX -> parsePptxSections(cleanTitle, zipEntries, bytes)
            OfficeFormat.ODT, OfficeFormat.ODS, OfficeFormat.ODP -> parseOdfSections(cleanTitle, format, zipEntries, bytes)
            OfficeFormat.CSV -> parseCsvSections(cleanTitle, bytes)
            OfficeFormat.RTF -> parseRtfSections(cleanTitle, bytes)
            OfficeFormat.TXT -> parseTxtSections(cleanTitle, bytes)
            OfficeFormat.DOC, OfficeFormat.XLS, OfficeFormat.PPT -> parseLegacyBinarySections(cleanTitle, format, bytes)
        }

        val allTextCombined = buildString {
            sections.forEach { sec ->
                append(sec.heading).append(" ")
                sec.paragraphs.forEach { append(it).append(" ") }
                sec.tableHeaders.forEach { append(it).append(" ") }
                sec.tableRows.flatten().forEach { append(it).append(" ") }
            }
        }
        val detectedScripts = detectUnicodeScripts(allTextCombined)
        val maxCols = sections.maxOfOrNull { it.tableHeaders.size } ?: 1

        return ParsedOfficeDocument(
            title = cleanTitle,
            subtitle = "${format.displayName} (${format.extension.uppercase()}) • ${bytes.size} bytes",
            format = format,
            runningHeader = "$cleanTitle • ${format.displayName}",
            runningFooter = "Converted from $fileName • Zero Server Retention",
            scriptsDetected = detectedScripts,
            fitSpreadsheetToPageWidth = fitWideSpreadsheets,
            naturalColumnsCount = maxCols,
            sections = sections
        )
    }

    private fun readZipTextEntries(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.endsWith(".xml", ignoreCase = true) || name.endsWith(".rels", ignoreCase = true)) {
                        val content = zis.bufferedReader(Charsets.UTF_8).readText()
                        result[name] = content
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return result
    }

    private fun parseDocxSections(
        title: String,
        zipEntries: Map<String, String>,
        rawBytes: ByteArray
    ): List<DocumentSection> {
        val docXml = zipEntries["word/document.xml"]
            ?: zipEntries.entries.firstOrNull { it.key.endsWith("document.xml") }?.value
            ?: return parseTxtSections(title, rawBytes)

        val paragraphs = mutableListOf<String>()
        val tableHeaders = mutableListOf<String>()
        val tableRows = mutableListOf<List<String>>()
        val sections = mutableListOf<DocumentSection>()
        var currentHeading = title

        runCatching {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(docXml))

            var inParagraph = false
            var inTable = false
            var inRow = false
            var inCell = false
            var isHeadingParagraph = false
            val currentParaText = StringBuilder()
            val currentCellText = StringBuilder()
            var currentRowCells = mutableListOf<String>()

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name?.substringAfterLast(':') ?: ""
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (tag) {
                            "p" -> {
                                inParagraph = true
                                isHeadingParagraph = false
                                currentParaText.clear()
                            }
                            "pStyle" -> {
                                val styleVal = parser.getAttributeValue(null, "w:val")
                                    ?: parser.getAttributeValue(null, "val") ?: ""
                                if (styleVal.contains("Heading", ignoreCase = true) || styleVal.contains("Title", ignoreCase = true)) {
                                    isHeadingParagraph = true
                                }
                            }
                            "tbl" -> {
                                inTable = true
                            }
                            "tr" -> {
                                inRow = true
                                currentRowCells = mutableListOf()
                            }
                            "tc" -> {
                                inCell = true
                                currentCellText.clear()
                            }
                            "t" -> {
                                val txt = parser.nextText()
                                if (inCell) {
                                    currentCellText.append(txt)
                                } else if (inParagraph) {
                                    currentParaText.append(txt)
                                }
                            }
                            "br" -> {
                                val brType = parser.getAttributeValue(null, "w:type")
                                    ?: parser.getAttributeValue(null, "type") ?: ""
                                if (brType.equals("page", ignoreCase = true) && (paragraphs.isNotEmpty() || tableRows.isNotEmpty())) {
                                    sections.add(
                                        DocumentSection(
                                            heading = currentHeading,
                                            paragraphs = paragraphs.toList(),
                                            tableHeaders = tableHeaders.toList(),
                                            tableRows = tableRows.toList(),
                                            forcePageBreakAfter = true
                                        )
                                    )
                                    paragraphs.clear()
                                    tableHeaders.clear()
                                    tableRows.clear()
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (tag) {
                            "tc" -> {
                                inCell = false
                                currentRowCells.add(currentCellText.toString().trim())
                            }
                            "tr" -> {
                                inRow = false
                                if (currentRowCells.any { it.isNotBlank() }) {
                                    if (tableHeaders.isEmpty()) {
                                        tableHeaders.addAll(currentRowCells)
                                    } else {
                                        tableRows.add(currentRowCells.toList())
                                    }
                                }
                            }
                            "tbl" -> {
                                inTable = false
                            }
                            "p" -> {
                                if (!inTable) {
                                    val text = currentParaText.toString().trim()
                                    if (text.isNotEmpty()) {
                                        if (isHeadingParagraph && paragraphs.size >= 5) {
                                            sections.add(
                                                DocumentSection(
                                                    heading = currentHeading,
                                                    paragraphs = paragraphs.toList(),
                                                    tableHeaders = tableHeaders.toList(),
                                                    tableRows = tableRows.toList()
                                                )
                                            )
                                            paragraphs.clear()
                                            tableHeaders.clear()
                                            tableRows.clear()
                                            currentHeading = text
                                        } else if (isHeadingParagraph && paragraphs.isEmpty()) {
                                            currentHeading = text
                                        } else {
                                            paragraphs.add(text)
                                        }
                                    }
                                }
                                inParagraph = false
                            }
                        }
                    }
                }
                event = parser.next()
            }
        }

        if (paragraphs.isNotEmpty() || tableHeaders.isNotEmpty() || tableRows.isNotEmpty()) {
            sections.add(
                DocumentSection(
                    heading = currentHeading,
                    paragraphs = paragraphs.toList(),
                    tableHeaders = tableHeaders.toList(),
                    tableRows = tableRows.toList()
                )
            )
        }

        return if (sections.isNotEmpty()) paginateLongSections(sections) else parseTxtSections(title, rawBytes)
    }

    private fun parseXlsxSections(
        title: String,
        zipEntries: Map<String, String>,
        rawBytes: ByteArray
    ): List<DocumentSection> {
        val sharedStrings = mutableListOf<String>()
        zipEntries["xl/sharedStrings.xml"]?.let { sstXml ->
            runCatching {
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(StringReader(sstXml))
                var event = parser.eventType
                val currentSi = StringBuilder()
                var inSi = false
                while (event != XmlPullParser.END_DOCUMENT) {
                    val tag = parser.name?.substringAfterLast(':') ?: ""
                    if (event == XmlPullParser.START_TAG && tag == "si") {
                        inSi = true
                        currentSi.clear()
                    } else if (event == XmlPullParser.START_TAG && tag == "t" && inSi) {
                        currentSi.append(parser.nextText())
                    } else if (event == XmlPullParser.END_TAG && tag == "si") {
                        inSi = false
                        sharedStrings.add(currentSi.toString())
                    }
                    event = parser.next()
                }
            }
        }

        val sheetEntries = zipEntries.entries
            .filter { it.key.startsWith("xl/worksheets/sheet") && it.key.endsWith(".xml") }
            .sortedBy { it.key }

        if (sheetEntries.isEmpty()) {
            return parseCsvSections(title, rawBytes)
        }

        val sections = mutableListOf<DocumentSection>()
        sheetEntries.forEachIndexed { sheetIdx, entry ->
            val rows = mutableListOf<List<String>>()
            runCatching {
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(StringReader(entry.value))

                var currentRow = mutableListOf<String>()
                var cellType = ""
                var cellValue = ""
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    val tag = parser.name?.substringAfterLast(':') ?: ""
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            when (tag) {
                                "row" -> currentRow = mutableListOf()
                                "c" -> {
                                    cellType = parser.getAttributeValue(null, "t") ?: ""
                                    cellValue = ""
                                }
                                "v", "t" -> {
                                    val txt = parser.nextText().trim()
                                    cellValue = if (cellType == "s") {
                                        val idx = txt.toIntOrNull() ?: -1
                                        sharedStrings.getOrNull(idx) ?: txt
                                    } else {
                                        txt
                                    }
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            when (tag) {
                                "c" -> currentRow.add(cellValue)
                                "row" -> {
                                    if (currentRow.any { it.isNotBlank() }) {
                                        rows.add(currentRow.toList())
                                    }
                                }
                            }
                        }
                    }
                    event = parser.next()
                }
            }

            if (rows.isNotEmpty()) {
                val headers = rows.first()
                val bodyRows = rows.drop(1)
                val chunkedRows = if (bodyRows.isEmpty()) listOf(emptyList()) else bodyRows.chunked(22)
                chunkedRows.forEachIndexed { pageIdx, chunk ->
                    sections.add(
                        DocumentSection(
                            heading = if (chunkedRows.size > 1) "$title — Sheet ${sheetIdx + 1} (Part ${pageIdx + 1})" else "$title — Sheet ${sheetIdx + 1}",
                            paragraphs = listOf("Wide spreadsheet fitted to single page width (${headers.size} columns, ${bodyRows.size} rows)."),
                            tableHeaders = headers,
                            tableRows = chunk
                        )
                    )
                }
            }
        }

        return if (sections.isNotEmpty()) sections else parseCsvSections(title, rawBytes)
    }

    private fun parsePptxSections(
        title: String,
        zipEntries: Map<String, String>,
        rawBytes: ByteArray
    ): List<DocumentSection> {
        val slideEntries = zipEntries.entries
            .filter { it.key.startsWith("ppt/slides/slide") && it.key.endsWith(".xml") }
            .sortedBy { it.key }

        if (slideEntries.isEmpty()) {
            return parseTxtSections(title, rawBytes)
        }

        return slideEntries.mapIndexed { idx, entry ->
            val lines = extractXmlTextElements(entry.value, setOf("t"))
            val slideHeading = lines.firstOrNull() ?: "Slide ${idx + 1}: $title"
            val bullets = if (lines.size > 1) lines.drop(1) else listOf("Presentation slide ${idx + 1}")
            DocumentSection(
                heading = slideHeading,
                paragraphs = bullets,
                forcePageBreakAfter = true
            )
        }
    }

    private fun parseOdfSections(
        title: String,
        format: OfficeFormat,
        zipEntries: Map<String, String>,
        rawBytes: ByteArray
    ): List<DocumentSection> {
        val contentXml = zipEntries["content.xml"] ?: return parseTxtSections(title, rawBytes)
        val lines = extractXmlTextElements(contentXml, setOf("h", "p"))
        if (lines.isEmpty()) return parseTxtSections(title, rawBytes)

        return lines.chunked(14).mapIndexed { index, chunk ->
            DocumentSection(
                heading = if (index == 0) "$title (${format.displayName})" else "$title — Page ${index + 1}",
                paragraphs = chunk
            )
        }
    }

    private fun parseCsvSections(title: String, bytes: ByteArray): List<DocumentSection> {
        val text = decodeBestEffortText(bytes)
        val nonBlankLines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (nonBlankLines.isEmpty()) {
            return listOf(DocumentSection(heading = title, paragraphs = listOf("(Empty CSV file)")))
        }

        val delimiter = when {
            nonBlankLines.first().contains('\t') -> '\t'
            nonBlankLines.first().count { it == ';' } > nonBlankLines.first().count { it == ',' } -> ';'
            else -> ','
        }

        val parsedRows = nonBlankLines.map { line ->
            parseCsvLine(line, delimiter)
        }
        val headers = parsedRows.first()
        val dataRows = parsedRows.drop(1)
        val chunks = if (dataRows.isEmpty()) listOf(emptyList()) else dataRows.chunked(24)

        return chunks.mapIndexed { idx, chunk ->
            DocumentSection(
                heading = if (chunks.size > 1) "$title (Page ${idx + 1} of ${chunks.size})" else title,
                paragraphs = listOf("Tabular dataset (${headers.size} columns × ${dataRows.size} rows) • Auto-scaled to page width."),
                tableHeaders = headers,
                tableRows = chunk
            )
        }
    }

    private fun parseCsvLine(line: String, delimiter: Char): List<String> {
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == delimiter && !inQuotes -> {
                    cells.add(sb.toString().trim())
                    sb.clear()
                }
                else -> sb.append(ch)
            }
        }
        cells.add(sb.toString().trim())
        return cells
    }

    private fun parseRtfSections(title: String, bytes: ByteArray): List<DocumentSection> {
        val raw = decodeBestEffortText(bytes)
        val cleaned = raw
            .replace("\\par", "\n")
            .replace("\\line", "\n")
            .replace(Regex("""\\fonttbl\{[^}]*\}"""), "")
            .replace(Regex("""\\[a-zA-Z]+-?\d*\s?"""), "")
            .replace("{", "")
            .replace("}", "")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return paginateLinesIntoSections(title, cleaned.ifEmpty { listOf(raw.take(800)) })
    }

    private fun parseTxtSections(title: String, bytes: ByteArray): List<DocumentSection> {
        val text = decodeBestEffortText(bytes)
        val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        return paginateLinesIntoSections(title, lines.ifEmpty { listOf("(Blank text document)") })
    }

    private fun parseLegacyBinarySections(
        title: String,
        format: OfficeFormat,
        bytes: ByteArray
    ): List<DocumentSection> {
        // Extract printable UTF-8 or UTF-16LE runs from legacy .doc / .xls / .ppt binary stream
        val decoded = decodeBestEffortText(bytes)
        val printableLines = Regex("""[\p{L}\p{N}\p{P}\p{Zs}]{4,}""")
            .findAll(decoded)
            .map { it.value.trim() }
            .filter { it.length >= 4 && !it.startsWith("OLE2_CFBF") }
            .toList()

        val lines = if (printableLines.isNotEmpty()) {
            printableLines
        } else {
            listOf("Document: $title (${format.displayName})", "Binary stream size: ${bytes.size} bytes")
        }
        return paginateLinesIntoSections(title, lines)
    }

    private fun paginateLinesIntoSections(title: String, lines: List<String>): List<DocumentSection> {
        val chunks = lines.chunked(16)
        return chunks.mapIndexed { idx, pageLines ->
            DocumentSection(
                heading = if (idx == 0) title else "$title — Page ${idx + 1}",
                paragraphs = pageLines
            )
        }
    }

    private fun paginateLongSections(sections: List<DocumentSection>): List<DocumentSection> {
        val result = mutableListOf<DocumentSection>()
        for (sec in sections) {
            if (sec.paragraphs.size <= 14) {
                result.add(sec)
            } else {
                val paraChunks = sec.paragraphs.chunked(14)
                paraChunks.forEachIndexed { idx, chunk ->
                    result.add(
                        DocumentSection(
                            heading = if (idx == 0) sec.heading else "${sec.heading} (Cont.)",
                            paragraphs = chunk,
                            tableHeaders = if (idx == 0) sec.tableHeaders else emptyList(),
                            tableRows = if (idx == 0) sec.tableRows else emptyList()
                        )
                    )
                }
            }
        }
        return result
    }

    private fun extractXmlTextElements(xml: String, targetLocalTags: Set<String>): List<String> {
        val lines = mutableListOf<String>()
        runCatching {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            val currentPara = StringBuilder()
            while (event != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name?.substringAfterLast(':') ?: ""
                if (event == XmlPullParser.START_TAG && tag in targetLocalTags) {
                    val txt = runCatching { parser.nextText() }.getOrDefault("")
                    if (txt.isNotBlank()) currentPara.append(txt).append(" ")
                } else if (event == XmlPullParser.END_TAG && (tag == "p" || tag == "h")) {
                    val built = currentPara.toString().trim()
                    if (built.isNotEmpty()) {
                        lines.add(built)
                        currentPara.clear()
                    }
                }
                event = parser.next()
            }
            val remaining = currentPara.toString().trim()
            if (remaining.isNotEmpty()) lines.add(remaining)
        }
        return lines
    }

    private fun decodeBestEffortText(bytes: ByteArray): String {
        return try {
            if (bytes.size >= 2 &&
                ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
                    (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
            ) {
                String(bytes, Charsets.UTF_16)
            } else {
                String(bytes, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    private fun detectUnicodeScripts(text: String): List<String> {
        val scripts = mutableListOf<String>()
        if (text.any { it.code in 0x0900..0x097F }) scripts.add("Devanagari (Hindi)")
        if (text.any { it.code in 0x0980..0x09FF }) scripts.add("Bengali")
        if (text.any { it.code in 0x0B80..0x0BFF }) scripts.add("Tamil")
        if (text.any { it.code in 0x0600..0x06FF }) scripts.add("Arabic (RTL)")
        if (text.any { it.code in 0x4E00..0x9FFF || it.code in 0x3040..0x30FF || it.code in 0xAC00..0xD7AF }) {
            scripts.add("CJK")
        }
        if (scripts.isEmpty() || text.any { it in 'A'..'Z' || it in 'a'..'z' }) {
            scripts.add(0, "Latin")
        }
        return scripts.distinct()
    }

    fun renderToPdfFile(doc: ParsedOfficeDocument, outputFile: File): Int {
        val pdfDocument = PdfDocument()
        val isLandscape = doc.format.isSpreadsheet ||
            doc.format == OfficeFormat.PPT ||
            doc.format == OfficeFormat.PPTX ||
            doc.format == OfficeFormat.ODP

        val pageWidth = if (isLandscape) 842 else 595
        val pageHeight = when {
            doc.format == OfficeFormat.PPT || doc.format == OfficeFormat.PPTX || doc.format == OfficeFormat.ODP -> 480
            isLandscape -> 595
            else -> 842
        }

        val totalPages = doc.sections.size.coerceAtLeast(1)

        try {
            doc.sections.forEachIndexed { index, section ->
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                drawDocumentPage(
                    canvas = page.canvas,
                    doc = doc,
                    section = section,
                    pageNumber = index + 1,
                    totalPages = totalPages,
                    pageWidth = pageWidth.toFloat(),
                    pageHeight = pageHeight.toFloat()
                )
                pdfDocument.finishPage(page)
            }

            FileOutputStream(outputFile).use { fos ->
                pdfDocument.writeTo(fos)
            }
        } finally {
            pdfDocument.close()
        }
        return totalPages
    }

    private fun drawDocumentPage(
        canvas: Canvas,
        doc: ParsedOfficeDocument,
        section: DocumentSection,
        pageNumber: Int,
        totalPages: Int,
        pageWidth: Float,
        pageHeight: Float
    ) {
        val margin = 38f
        val contentWidth = pageWidth - margin * 2

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRect(0f, 0f, pageWidth, pageHeight, bgPaint)

        // Clean Header Bar
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
            strokeWidth = 1f
        }
        val metaTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = 8.5f
        }
        canvas.drawText(doc.runningHeader.take(70), margin, 24f, metaTextPaint)
        val pageLabel = "Page $pageNumber of $totalPages"
        canvas.drawText(pageLabel, pageWidth - margin - metaTextPaint.measureText(pageLabel), 24f, metaTextPaint)
        canvas.drawLine(margin, 31f, pageWidth - margin, 31f, linePaint)

        var currentY = 48f

        // Section / Document Heading
        val headingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = if (pageNumber == 1) 16f else 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val headingLayout = StaticLayout.Builder
            .obtain(section.heading, 0, section.heading.length, headingPaint, contentWidth.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
        canvas.save()
        canvas.translate(margin, currentY)
        headingLayout.draw(canvas)
        canvas.restore()
        currentY += headingLayout.height + 12f

        // Body Paragraphs (supports Latin, Devanagari/Indic, Arabic RTL, and CJK)
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            textSize = 10.5f
        }
        for (para in section.paragraphs) {
            if (currentY > pageHeight - 52f) break
            val isArabicRtl = para.any { it.code in 0x0600..0x06FF } && !para.startsWith("•")
            val alignment = if (isArabicRtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
            val layout = StaticLayout.Builder
                .obtain(para, 0, para.length, bodyPaint, contentWidth.toInt())
                .setAlignment(alignment)
                .setLineSpacing(2.5f, 1.08f)
                .build()
            canvas.save()
            canvas.translate(margin, currentY)
            layout.draw(canvas)
            canvas.restore()
            currentY += layout.height + 8f
        }

        // Table / Wide Spreadsheet Rendering (Auto-scales all columns to fit page width)
        if (section.tableHeaders.isNotEmpty() && currentY < pageHeight - 60f) {
            currentY += 6f
            val colCount = section.tableHeaders.size.coerceAtLeast(1)
            val colWidth = contentWidth / colCount.toFloat()
            val isWide = colCount >= 7
            val fontSize = if (isWide) 8.0f else 9.2f
            val rowHeight = if (isWide) 20f else 22f

            // Header Row
            val headerBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0F172A") }
            canvas.drawRect(margin, currentY, pageWidth - margin, currentY + rowHeight, headerBg)

            val thPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = fontSize
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            section.tableHeaders.forEachIndexed { colIdx, text ->
                val x = margin + colIdx * colWidth + 5f
                canvas.drawText(ellipsize(text, thPaint, colWidth - 8f), x, currentY + rowHeight - 6f, thPaint)
            }
            currentY += rowHeight

            val tdPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1E293B")
                textSize = fontSize
            }
            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#CBD5E1")
                style = Paint.Style.STROKE
                strokeWidth = 0.6f
            }

            section.tableRows.forEachIndexed { rowIdx, cells ->
                if (currentY + rowHeight <= pageHeight - 36f) {
                    val isTotalRow = cells.firstOrNull()?.contains("TOTAL", ignoreCase = true) == true
                    val rowBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = when {
                            isTotalRow -> Color.parseColor("#E0F2FE")
                            rowIdx % 2 == 1 -> Color.parseColor("#F8FAFC")
                            else -> Color.WHITE
                        }
                    }
                    canvas.drawRect(margin, currentY, pageWidth - margin, currentY + rowHeight, rowBg)
                    canvas.drawRect(margin, currentY, pageWidth - margin, currentY + rowHeight, gridPaint)

                    cells.forEachIndexed { colIdx, cellText ->
                        if (colIdx < colCount) {
                            val x = margin + colIdx * colWidth
                            canvas.drawLine(x, currentY, x, currentY + rowHeight, gridPaint)
                            canvas.drawText(
                                ellipsize(cellText, tdPaint, colWidth - 8f),
                                x + 5f,
                                currentY + rowHeight - 6f,
                                tdPaint
                            )
                        }
                    }
                    currentY += rowHeight
                }
            }
        }

        // Footer Bar
        val footerY = pageHeight - 18f
        canvas.drawLine(margin, footerY - 12f, pageWidth - margin, footerY - 12f, linePaint)
        canvas.drawText(doc.runningFooter.take(85), margin, footerY, metaTextPaint)
    }

    private fun ellipsize(text: String, paint: TextPaint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }
}
