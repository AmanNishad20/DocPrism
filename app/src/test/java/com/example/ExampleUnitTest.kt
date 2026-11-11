package com.example

import com.example.data.ConversionFailureReason
import com.example.data.OfficeFormat
import com.example.engine.OfficeConversionException
import com.example.engine.OfficeToPdfEngine
import com.example.network.SlidingWindowRateLimiter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun allTwelveOfficeFormats_areRecognizedByExtensionAndMime() {
        val expectedFormats = listOf(
            "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "odt", "ods", "odp", "rtf", "csv", "txt"
        )
        for (ext in expectedFormats) {
            val resolved = OfficeFormat.fromFileNameOrMime("report_2026.$ext", null)
            assertNotNull("Expected format .$ext to be recognized", resolved)
            assertEquals(ext, resolved?.extension)
        }
        assertTrue(OfficeFormat.XLSX.isSpreadsheet)
        assertTrue(OfficeFormat.ODS.isSpreadsheet)
        assertTrue(OfficeFormat.CSV.isSpreadsheet)
    }

    @Test
    fun differentDocuments_produceDistinctParsedContent() {
        val docA = OfficeToPdfEngine.parseOfficeDocument(
            fileName = "Alpha_Notes.txt",
            format = OfficeFormat.TXT,
            bytes = "Alpha Project Heading\nUnique paragraph for Alpha document.".toByteArray(),
            fitWideSpreadsheets = true
        )
        val docB = OfficeToPdfEngine.parseOfficeDocument(
            fileName = "Beta_Metrics.csv",
            format = OfficeFormat.CSV,
            bytes = "City,Revenue,Growth\nTokyo,500M,18%\nMumbai,320M,25%".toByteArray(),
            fitWideSpreadsheets = true
        )

        assertNotEquals(docA.title, docB.title)
        assertTrue(docA.sections.first().paragraphs.any { it.contains("Unique paragraph for Alpha") })
        assertEquals(listOf("City", "Revenue", "Growth"), docB.sections.first().tableHeaders)
        assertEquals("Tokyo", docB.sections.first().tableRows.first().first())
    }

    @Test
    fun sha256Digest_isDeterministicForSameFileContent() {
        val payload = "Global Multilingual Office Payload हिन्दी العربية 中文".toByteArray()
        val hash1 = OfficeToPdfEngine.computeSha256(payload)
        val hash2 = OfficeToPdfEngine.computeSha256(payload)
        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length)
    }

    @Test
    fun passwordProtectedAndCorruptedFiles_areGracefullyRejected() {
        val encryptedBytes = "HEADER_[ENCRYPTED-OFFICE-LOCK]_PAYLOAD".toByteArray()
        var passwordCaught = false
        try {
            OfficeToPdfEngine.inspectAndValidateOfficeBytes(
                fileName = "secret.docx",
                mimeType = null,
                bytes = encryptedBytes,
                maxAllowedBytes = 10 * 1024 * 1024L
            )
        } catch (e: OfficeConversionException) {
            passwordCaught = e.reason is ConversionFailureReason.PasswordProtected
        }
        assertTrue("Expected PasswordProtected failure reason", passwordCaught)

        val corruptedBytes = "PK\u0003\u0004[CORRUPTED-STREAM-TRUNCATED-HEADER-0xDEAD]".toByteArray()
        var corruptCaught = false
        try {
            OfficeToPdfEngine.inspectAndValidateOfficeBytes(
                fileName = "broken.xlsx",
                mimeType = null,
                bytes = corruptedBytes,
                maxAllowedBytes = 10 * 1024 * 1024L
            )
        } catch (e: OfficeConversionException) {
            corruptCaught = e.reason is ConversionFailureReason.CorruptedFile
        }
        assertTrue("Expected CorruptedFile failure reason", corruptCaught)
    }

    @Test
    fun slidingWindowRateLimiter_enforcesPerMinuteCap() = runTest {
        val limiter = SlidingWindowRateLimiter()
        limiter.acquireOrThrow(maxRequestsPerMinute = 2)
        limiter.acquireOrThrow(maxRequestsPerMinute = 2)

        var rateLimited = false
        try {
            limiter.acquireOrThrow(maxRequestsPerMinute = 2)
        } catch (e: OfficeConversionException) {
            rateLimited = e.reason is ConversionFailureReason.RateLimitExceeded
        }
        assertTrue("Expected 3rd request in window to trigger RateLimitExceeded", rateLimited)
    }
}
