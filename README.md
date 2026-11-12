# DocPrism PDF

DocPrism is a lightweight, mobile-first Office document viewer and PDF conversion module for Android. It converts Office documents into clean vector PDFs, maintains an on-device SHA-256 cache for offline access, and ensures zero server data retention.

---

## Features

- **Supported Formats**: `DOC`, `DOCX`, `XLS`, `XLSX`, `PPT`, `PPTX`, `ODT`, `ODS`, `ODP`, `RTF`, `CSV`, and `TXT`.
- **Fast Offline Caching**: Documents are hashed with SHA-256 before conversion. Once converted, identical files open instantly from local storage without hitting the network.
- **Accurate Document Rendering**: Preserves multilingual scripts (Indic, Arabic RTL, CJK) and complex formatting.
- **Wide Spreadsheet Auto-Fit**: Wide spreadsheets (`XLS`, `XLSX`, `ODS`, `CSV`) automatically fit to the page width without breaking across awkward horizontal pages.
- **Reading Themes**: Includes System Default, Light, Dark, and a warm Sepia **Reading** theme.
- **Zero Server Retention**: Client-server transport is encrypted (TLS 1.3), and temporary files on the conversion backend are deleted immediately upon response streaming.

---

## Tech Stack

- **Platform**: Android (Native Kotlin)
- **UI Framework**: Jetpack Compose with Material 3
- **Local Persistence**: Android Native SQLite (`SQLiteOpenHelper`) for PDF metadata & SHA-256 cache, plus DataStore Preferences
- **Rendering**: Android `PdfRenderer`
- **Networking**: OkHttp & Retrofit

---

---

## Direct APK Download

A pre-built debug APK is attached directly in the repository under:
- **`apk/DocPrism-debug.apk`**

You can download and install this `.apk` directly onto your Android device or emulator.

---

## Building the APK Locally

### Prerequisites
- JDK 17
- Android SDK (API 36 / 35, minimum API 24)

### Build Command
```bash
chmod +x gradlew
./gradlew assembleDebug
```
Output location: `app/build/outputs/apk/debug/app-debug.apk`

