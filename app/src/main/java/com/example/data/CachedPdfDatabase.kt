package com.example.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

data class CachedPdfEntity(
    val sha256Hash: String,
    val originalFileName: String,
    val formatExtension: String,
    val originalSizeBytes: Long,
    val pdfSizeBytes: Long,
    val localPdfPath: String,
    val pageCount: Int,
    val fitToWidthApplied: Boolean,
    val scriptsDetected: String,
    val serverPurgeVerified: Boolean,
    val convertedAtEpochMs: Long,
    val lastOpenedAtEpochMs: Long,
    val cacheHitCount: Int = 0
)

/**
 * Robust, lightweight native SQLite database without KSP or heavy code-generation plugins.
 * Eliminates ApplicationManager.getApplication() CI crashes while keeping 100% functionality.
 */
class CachedPdfDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "docprism_pdf_cache_native.db",
    null,
    1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_pdfs (
                sha256Hash TEXT PRIMARY KEY,
                originalFileName TEXT NOT NULL,
                formatExtension TEXT NOT NULL,
                originalSizeBytes INTEGER NOT NULL,
                pdfSizeBytes INTEGER NOT NULL,
                localPdfPath TEXT NOT NULL,
                pageCount INTEGER NOT NULL,
                fitToWidthApplied INTEGER NOT NULL,
                scriptsDetected TEXT NOT NULL,
                serverPurgeVerified INTEGER NOT NULL,
                convertedAtEpochMs INTEGER NOT NULL,
                lastOpenedAtEpochMs INTEGER NOT NULL,
                cacheHitCount INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS cached_pdfs")
        onCreate(db)
    }

    fun getAllCachedPdfs(): List<CachedPdfEntity> {
        val list = mutableListOf<CachedPdfEntity>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM cached_pdfs ORDER BY lastOpenedAtEpochMs DESC",
            null
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(
                    CachedPdfEntity(
                        sha256Hash = c.getString(c.getColumnIndexOrThrow("sha256Hash")),
                        originalFileName = c.getString(c.getColumnIndexOrThrow("originalFileName")),
                        formatExtension = c.getString(c.getColumnIndexOrThrow("formatExtension")),
                        originalSizeBytes = c.getLong(c.getColumnIndexOrThrow("originalSizeBytes")),
                        pdfSizeBytes = c.getLong(c.getColumnIndexOrThrow("pdfSizeBytes")),
                        localPdfPath = c.getString(c.getColumnIndexOrThrow("localPdfPath")),
                        pageCount = c.getInt(c.getColumnIndexOrThrow("pageCount")),
                        fitToWidthApplied = c.getInt(c.getColumnIndexOrThrow("fitToWidthApplied")) == 1,
                        scriptsDetected = c.getString(c.getColumnIndexOrThrow("scriptsDetected")),
                        serverPurgeVerified = c.getInt(c.getColumnIndexOrThrow("serverPurgeVerified")) == 1,
                        convertedAtEpochMs = c.getLong(c.getColumnIndexOrThrow("convertedAtEpochMs")),
                        lastOpenedAtEpochMs = c.getLong(c.getColumnIndexOrThrow("lastOpenedAtEpochMs")),
                        cacheHitCount = c.getInt(c.getColumnIndexOrThrow("cacheHitCount"))
                    )
                )
            }
        }
        return list
    }

    fun getBySha256(hash: String): CachedPdfEntity? {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM cached_pdfs WHERE sha256Hash = ? LIMIT 1",
            arrayOf(hash)
        )
        return cursor.use { c ->
            if (c.moveToFirst()) {
                CachedPdfEntity(
                    sha256Hash = c.getString(c.getColumnIndexOrThrow("sha256Hash")),
                    originalFileName = c.getString(c.getColumnIndexOrThrow("originalFileName")),
                    formatExtension = c.getString(c.getColumnIndexOrThrow("formatExtension")),
                    originalSizeBytes = c.getLong(c.getColumnIndexOrThrow("originalSizeBytes")),
                    pdfSizeBytes = c.getLong(c.getColumnIndexOrThrow("pdfSizeBytes")),
                    localPdfPath = c.getString(c.getColumnIndexOrThrow("localPdfPath")),
                    pageCount = c.getInt(c.getColumnIndexOrThrow("pageCount")),
                    fitToWidthApplied = c.getInt(c.getColumnIndexOrThrow("fitToWidthApplied")) == 1,
                    scriptsDetected = c.getString(c.getColumnIndexOrThrow("scriptsDetected")),
                    serverPurgeVerified = c.getInt(c.getColumnIndexOrThrow("serverPurgeVerified")) == 1,
                    convertedAtEpochMs = c.getLong(c.getColumnIndexOrThrow("convertedAtEpochMs")),
                    lastOpenedAtEpochMs = c.getLong(c.getColumnIndexOrThrow("lastOpenedAtEpochMs")),
                    cacheHitCount = c.getInt(c.getColumnIndexOrThrow("cacheHitCount"))
                )
            } else {
                null
            }
        }
    }

    fun insertOrReplace(entity: CachedPdfEntity) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("sha256Hash", entity.sha256Hash)
            put("originalFileName", entity.originalFileName)
            put("formatExtension", entity.formatExtension)
            put("originalSizeBytes", entity.originalSizeBytes)
            put("pdfSizeBytes", entity.pdfSizeBytes)
            put("localPdfPath", entity.localPdfPath)
            put("pageCount", entity.pageCount)
            put("fitToWidthApplied", if (entity.fitToWidthApplied) 1 else 0)
            put("scriptsDetected", entity.scriptsDetected)
            put("serverPurgeVerified", if (entity.serverPurgeVerified) 1 else 0)
            put("convertedAtEpochMs", entity.convertedAtEpochMs)
            put("lastOpenedAtEpochMs", entity.lastOpenedAtEpochMs)
            put("cacheHitCount", entity.cacheHitCount)
        }
        db.insertWithOnConflict("cached_pdfs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun recordHit(hash: String, nowMs: Long) {
        val db = writableDatabase
        db.execSQL(
            "UPDATE cached_pdfs SET cacheHitCount = cacheHitCount + 1, lastOpenedAtEpochMs = ? WHERE sha256Hash = ?",
            arrayOf(nowMs, hash)
        )
    }

    fun deleteByHash(hash: String) {
        val db = writableDatabase
        db.delete("cached_pdfs", "sha256Hash = ?", arrayOf(hash))
    }

    fun clearAll() {
        val db = writableDatabase
        db.delete("cached_pdfs", null, null)
    }
}

class CachedPdfRepository(
    private val dbHelper: CachedPdfDatabaseHelper,
    private val cacheDirectory: File
) {
    private val _cachedPdfsFlow = MutableStateFlow<List<CachedPdfEntity>>(emptyList())
    val allCachedPdfs: Flow<List<CachedPdfEntity>> = _cachedPdfsFlow.asStateFlow()

    init {
        if (!cacheDirectory.exists()) {
            cacheDirectory.mkdirs()
        }
        refreshList()
    }

    private fun refreshList() {
        val list = dbHelper.getAllCachedPdfs()
        _cachedPdfsFlow.value = list
    }

    suspend fun lookupValidCachedPdf(sha256Hash: String): CachedPdfEntity? = withContext(Dispatchers.IO) {
        val existing = dbHelper.getBySha256(sha256Hash) ?: return@withContext null
        val file = File(existing.localPdfPath)
        if (file.exists() && file.length() > 100L) {
            existing
        } else {
            dbHelper.deleteByHash(sha256Hash)
            refreshList()
            null
        }
    }

    suspend fun recordCacheHit(sha256Hash: String) = withContext(Dispatchers.IO) {
        dbHelper.recordHit(sha256Hash, System.currentTimeMillis())
        refreshList()
    }

    fun getTargetCacheFile(sha256Hash: String): File {
        if (!cacheDirectory.exists()) cacheDirectory.mkdirs()
        return File(cacheDirectory, "$sha256Hash.pdf")
    }

    suspend fun saveConvertedPdf(entity: CachedPdfEntity) = withContext(Dispatchers.IO) {
        dbHelper.insertOrReplace(entity)
        refreshList()
    }

    suspend fun deleteCachedPdf(entity: CachedPdfEntity) = withContext(Dispatchers.IO) {
        runCatching { File(entity.localPdfPath).delete() }
        dbHelper.deleteByHash(entity.sha256Hash)
        refreshList()
    }

    suspend fun purgeEntireCache() = withContext(Dispatchers.IO) {
        cacheDirectory.listFiles()?.forEach { runCatching { it.delete() } }
        dbHelper.clearAll()
        refreshList()
    }
}
