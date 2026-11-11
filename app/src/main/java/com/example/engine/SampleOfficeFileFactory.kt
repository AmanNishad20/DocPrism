package com.example.engine

import android.content.Context
import com.example.data.OfficeFormat
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SampleOfficeAsset(
    val id: String,
    val fileName: String,
    val title: String,
    val subtitle: String,
    val format: OfficeFormat?,
    val highlightTag: String,
    val isDiagnosticErrorSample: Boolean = false
)

object SampleOfficeFileFactory {

    val catalog: List<SampleOfficeAsset> = listOf(
        SampleOfficeAsset(
            id = "docx_report",
            fileName = "Q4_Product_Strategy_Report.docx",
            title = "Product Strategy.docx",
            subtitle = "Word document with headings, tables & multilingual summary",
            format = OfficeFormat.DOCX,
            highlightTag = "DOCX"
        ),
        SampleOfficeAsset(
            id = "xlsx_ledger",
            fileName = "Annual_Revenue_Ledger_2026.xlsx",
            title = "Revenue Ledger.xlsx",
            subtitle = "Wide 9-column financial spreadsheet auto-fitted to page width",
            format = OfficeFormat.XLSX,
            highlightTag = "XLSX"
        ),
        SampleOfficeAsset(
            id = "pptx_deck",
            fileName = "Mobile_Launch_Keynote.pptx",
            title = "Launch Keynote.pptx",
            subtitle = "3-slide widescreen presentation deck",
            format = OfficeFormat.PPTX,
            highlightTag = "PPTX"
        ),
        SampleOfficeAsset(
            id = "csv_sales",
            fileName = "Global_Regional_Metrics.csv",
            title = "Regional Metrics.csv",
            subtitle = "Wide tabular CSV dataset with 8 columns",
            format = OfficeFormat.CSV,
            highlightTag = "CSV"
        ),
        SampleOfficeAsset(
            id = "txt_multilingual",
            fileName = "Multilingual_Release_Notes.txt",
            title = "Release Notes.txt",
            subtitle = "English, Hindi (देवनागरी), Arabic (العربية) & CJK text",
            format = OfficeFormat.TXT,
            highlightTag = "TXT"
        ),
        SampleOfficeAsset(
            id = "err_password",
            fileName = "Confidential_Protected.docx",
            title = "Locked File.docx",
            subtitle = "Password-protected Office file test",
            format = OfficeFormat.DOCX,
            highlightTag = "Locked",
            isDiagnosticErrorSample = true
        )
    )

    fun materializeSampleFile(context: Context, asset: SampleOfficeAsset): File {
        val dir = File(context.cacheDir, "sample_office_files_v2")
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, asset.fileName)
        when (asset.id) {
            "docx_report" -> writeRealDocxReport(target)
            "xlsx_ledger" -> writeRealXlsxLedger(target)
            "pptx_deck" -> writeRealPptxDeck(target)
            "csv_sales" -> writeWideCsvFile(target)
            "txt_multilingual" -> writeMultilingualTxtFile(target)
            "err_password" -> writePasswordProtectedDocx(target)
            else -> writeMultilingualTxtFile(target)
        }
        return target
    }

    private fun writePasswordProtectedDocx(file: File) {
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            zos.putNextEntry(ZipEntry("EncryptionInfo"))
            zos.write("OOXML_AGILE_ENCRYPTION_AES_256_SHA512_[ENCRYPTED-OFFICE-LOCK]".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("EncryptedPackage"))
            zos.write(ByteArray(256) { (it * 31).toByte() })
            zos.closeEntry()
        }
    }

    private fun writeRealDocxReport(file: File) {
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/pkg/2006/content-types"></Types>""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("word/document.xml"))
            val xml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                      <w:r><w:t>Q4 Product Strategy &amp; Architecture Review</w:t></w:r>
                    </w:p>
                    <w:p>
                      <w:r><w:t>Prepared by the Mobile Core Engineering Team • November 2026</w:t></w:r>
                    </w:p>
                    <w:p>
                      <w:pPr><w:pStyle w:val="Heading2"/></w:pPr>
                      <w:r><w:t>1. Executive Summary</w:t></w:r>
                    </w:p>
                    <w:p>
                      <w:r><w:t>This document outlines the Q4 product roadmap for the lightweight mobile Office viewing module. By offloading heavy typography layout and pagination to a stateless conversion worker, the mobile client remains fast and compact while delivering pixel-accurate PDF output.</w:t></w:r>
                    </w:p>
                    <w:p>
                      <w:pPr><w:pStyle w:val="Heading2"/></w:pPr>
                      <w:r><w:t>2. Key Quarterly Milestones</w:t></w:r>
                    </w:p>
                    <w:tbl>
                      <w:tr>
                        <w:tc><w:p><w:r><w:t>Workstream</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>Owner</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>Target Date</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>Status</w:t></w:r></w:p></w:tc>
                      </w:tr>
                      <w:tr>
                        <w:tc><w:p><w:r><w:t>Zero-Retention Cloud Worker</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>Platform Team</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>Oct 15</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>Completed</w:t></w:r></w:p></w:tc>
                      </w:tr>
                      <w:tr>
                        <w:tc><w:p><w:r><w:t>SHA-256 Offline Device Cache</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>Mobile Core</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>Nov 02</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>Completed</w:t></w:r></w:p></w:tc>
                      </w:tr>
                      <w:tr>
                        <w:tc><w:p><w:r><w:t>Indic, Arabic &amp; CJK Font Shaping</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>Rendering Team</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>Nov 18</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>On Track</w:t></w:r></w:p></w:tc>
                      </w:tr>
                      <w:tr>
                        <w:tc><w:p><w:r><w:t>Wide Spreadsheet Auto-Scaling</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>Calc Engine</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>Nov 29</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>Verified</w:t></w:r></w:p></w:tc>
                      </w:tr>
                    </w:tbl>
                    <w:p>
                      <w:pPr><w:pStyle w:val="Heading2"/></w:pPr>
                      <w:r><w:t>3. International Localization Sign-Off</w:t></w:r>
                    </w:p>
                    <w:p>
                      <w:r><w:t>हिन्दी (Devanagari): सभी दस्तावेज़ों का पीडीएफ रूपांतरण सटीक फ़ॉन्ट और तालिकाओं के साथ सत्यापित किया गया है।</w:t></w:r>
                    </w:p>
                    <w:p>
                      <w:r><w:t>العربية (Arabic): تم التحقق من محاذاة النصوص العربية من اليمين إلى اليسار بنجاح.</w:t></w:r>
                    </w:p>
                    <w:p>
                      <w:r><w:t>中文 / 日本語 / 한국어: 跨平台文档转换质量已通过多语言排版测试。</w:t></w:r>
                    </w:p>
                  </w:body>
                </w:document>
            """.trimIndent()
            zos.write(xml.toByteArray())
            zos.closeEntry()
        }
    }

    private fun writeRealXlsxLedger(file: File) {
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8"?><Types></Types>""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            val sheetXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <worksheet>
                  <sheetData>
                    <row r="1">
                      <c><v>Account Code</v></c>
                      <c><v>Department</v></c>
                      <c><v>Region</v></c>
                      <c><v>Q1 Actual</v></c>
                      <c><v>Q2 Actual</v></c>
                      <c><v>Q3 Actual</v></c>
                      <c><v>Q4 Forecast</v></c>
                      <c><v>FY26 Total</v></c>
                      <c><v>YoY Delta</v></c>
                    </row>
                    <row r="2">
                      <c><v>GL-1040</v></c>
                      <c><v>Cloud Infrastructure</v></c>
                      <c><v>North America</v></c>
                      <c><v>$142,500</v></c>
                      <c><v>$148,200</v></c>
                      <c><v>$151,900</v></c>
                      <c><v>$159,000</v></c>
                      <c><v>$601,600</v></c>
                      <c><v>+11.4%</v></c>
                    </row>
                    <row r="3">
                      <c><v>GL-2085</v></c>
                      <c><v>Mobile R&amp;D</v></c>
                      <c><v>Europe (EMEA)</v></c>
                      <c><v>$218,000</v></c>
                      <c><v>$225,400</v></c>
                      <c><v>$234,100</v></c>
                      <c><v>$241,800</v></c>
                      <c><v>$919,300</v></c>
                      <c><v>+16.2%</v></c>
                    </row>
                    <row r="4">
                      <c><v>GL-3012</v></c>
                      <c><v>Enterprise Sales</v></c>
                      <c><v>India (मुंबई)</v></c>
                      <c><v>$96,400</v></c>
                      <c><v>$108,700</v></c>
                      <c><v>$121,500</v></c>
                      <c><v>$138,200</v></c>
                      <c><v>$464,800</v></c>
                      <c><v>+28.5%</v></c>
                    </row>
                    <row r="5">
                      <c><v>GL-4090</v></c>
                      <c><v>Customer Success</v></c>
                      <c><v>Japan (東京)</v></c>
                      <c><v>$112,300</v></c>
                      <c><v>$117,900</v></c>
                      <c><v>$124,000</v></c>
                      <c><v>$131,500</v></c>
                      <c><v>$485,700</v></c>
                      <c><v>+14.8%</v></c>
                    </row>
                    <row r="6">
                      <c><v>GL-5022</v></c>
                      <c><v>Security &amp; Privacy</v></c>
                      <c><v>MENA (دبي)</v></c>
                      <c><v>$74,800</v></c>
                      <c><v>$79,200</v></c>
                      <c><v>$83,600</v></c>
                      <c><v>$89,100</v></c>
                      <c><v>$326,700</v></c>
                      <c><v>+19.1%</v></c>
                    </row>
                    <row r="7">
                      <c><v>TOTAL</v></c>
                      <c><v>Consolidated Ledger</v></c>
                      <c><v>All Regions</v></c>
                      <c><v>$644,000</v></c>
                      <c><v>$679,400</v></c>
                      <c><v>$715,100</v></c>
                      <c><v>$759,600</v></c>
                      <c><v>$2,798,100</v></c>
                      <c><v>+17.4%</v></c>
                    </row>
                  </sheetData>
                </worksheet>
            """.trimIndent()
            zos.write(sheetXml.toByteArray())
            zos.closeEntry()
        }
    }

    private fun writeRealPptxDeck(file: File) {
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8"?><Types></Types>""".toByteArray())
            zos.closeEntry()

            val slides = listOf(
                """
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                  <a:p><a:t>Slide 1: Next-Gen Mobile Document Viewer</a:t></a:p>
                  <a:p><a:t>• Lightweight mobile footprint with stateless cloud PDF conversion</a:t></a:p>
                  <a:p><a:t>• Supports DOC, DOCX, XLS, XLSX, PPT, PPTX, ODT, ODS, ODP, RTF, CSV, TXT</a:t></a:p>
                  <a:p><a:t>• Encrypted TLS 1.3 transfer with immediate server file deletion</a:t></a:p>
                </p:sld>
                """.trimIndent(),
                """
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                  <a:p><a:t>Slide 2: Offline-First SHA-256 Caching</a:t></a:p>
                  <a:p><a:t>• Every file is hashed locally before network upload</a:t></a:p>
                  <a:p><a:t>• Identical files are never converted twice (0 ms repeat load time)</a:t></a:p>
                  <a:p><a:t>• Previously converted PDFs remain accessible when completely offline</a:t></a:p>
                </p:sld>
                """.trimIndent(),
                """
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                  <a:p><a:t>Slide 3: Global Script &amp; Wide Table Fidelity</a:t></a:p>
                  <a:p><a:t>• Full HarfBuzz shaping for Hindi (देवनागरी), Arabic (العربية) &amp; CJK (中文/日本語/한국어)</a:t></a:p>
                  <a:p><a:t>• Wide spreadsheets automatically scale to fit single page width</a:t></a:p>
                  <a:p><a:t>• Runs on free-tier serverless infrastructure (Cloud Run / Oracle ARM)</a:t></a:p>
                </p:sld>
                """.trimIndent()
            )

            slides.forEachIndexed { index, slideXml ->
                zos.putNextEntry(ZipEntry("ppt/slides/slide${index + 1}.xml"))
                zos.write(slideXml.toByteArray())
                zos.closeEntry()
            }
        }
    }

    private fun writeWideCsvFile(file: File) {
        val csv = buildString {
            appendLine("Market,Country,Script,Active_Users,Conversion_Rate,Avg_Latency,Cache_Ratio,Status")
            appendLine("APAC-South,India (भारत),हिन्दी,148200,99.4%,410ms,94.8%,Healthy")
            appendLine("MENA-Gulf,UAE (الإمارات),العربية,84900,99.1%,445ms,92.1%,Healthy")
            appendLine("APAC-East,Japan (日本),日本語,192400,99.7%,380ms,96.3%,Optimal")
            appendLine("APAC-East,Korea (한국),한국어,115600,99.5%,390ms,95.4%,Optimal")
            appendLine("Greater China,Taiwan (台灣),繁體中文,134800,99.3%,405ms,93.9%,Healthy")
            appendLine("EMEA-West,Germany,Deutsch,210500,99.6%,420ms,94.2%,Optimal")
            appendLine("NAMER,United States,English,482000,99.8%,350ms,97.1%,Optimal")
        }
        file.writeText(csv)
    }

    private fun writeMultilingualTxtFile(file: File) {
        val txt = """
            Release Notes v2.4 — Multilingual Typography & Offline Engine
            =============================================================

            1. Indic Script Rendering (Hindi, Bengali, Tamil)
            • हिन्दी (Devanagari): नमस्ते! यह पीडीएफ रूपांतरण देवनागरी लिपि के संयुक्त अक्षरों और मात्राओं को पूरी स्पष्टता के साथ प्रदर्शित करता है।
            • বাংলা (Bengali): সমস্ত ফন্ট এবং টেবিল নিখুঁতভাবে সংরক্ষণ করা হয়।
            • தமிழ் (Tamil): தமிழ் எழுத்துருக்கள் மற்றும் பக்க வடிவமைப்பு துல்லியமாக மாற்றப்படுகிறது।

            2. Right-to-Left Arabic Layout (العربية)
            • العربية: يتم تحويل المستندات بدقة عالية مع الحفاظ على محاذاة النصوص العربية من اليمين إلى اليسار وحذف الملفات فوراً من الخادم.

            3. East Asian CJK Scripts (中文 / 日本語 / 한국어)
            • 简体中文: 支持宽幅电子表格自动缩放至单页宽度，离线缓存秒开。
            • 日本語: フォント埋め込み・表計算の幅自動調整・ヘッダーとフッターの完全再現。
            • 한국어: 모든 오피스 문서를 원본 레이아웃 그대로 빠르고 안전하게 PDF로 변환합니다.

            4. Privacy & Zero-Retention Summary
            • Encrypted transfer over HTTPS/TLS 1.3.
            • Uploaded Office files and generated PDFs are deleted from the server immediately after conversion.
        """.trimIndent()
        file.writeText(txt)
    }
}
