package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import java.io.File

@Entity(tableName = "cached_pdfs")
data class CachedPdfEntity(
    @PrimaryKey val sha256Hash: String,
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

@Dao
interface CachedPdfDao {
    @Query("SELECT * FROM cached_pdfs ORDER BY lastOpenedAtEpochMs DESC")
    fun observeAllCachedPdfs(): Flow<List<CachedPdfEntity>>

    @Query("SELECT * FROM cached_pdfs WHERE sha256Hash = :sha256Hash LIMIT 1")
    suspend fun getBySha256(sha256Hash: String): CachedPdfEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(entity: CachedPdfEntity)

    @Query("UPDATE cached_pdfs SET cacheHitCount = cacheHitCount + 1, lastOpenedAtEpochMs = :nowMs WHERE sha256Hash = :sha256Hash")
    suspend fun recordCacheHit(sha256Hash: String, nowMs: Long = System.currentTimeMillis())

    @Query("DELETE FROM cached_pdfs WHERE sha256Hash = :sha256Hash")
    suspend fun deleteByHash(sha256Hash: String)

    @Query("DELETE FROM cached_pdfs")
    suspend fun clearAll()
}

@Database(entities = [CachedPdfEntity::class], version = 2, exportSchema = false)
abstract class CachedPdfDatabase : RoomDatabase() {
    abstract fun cachedPdfDao(): CachedPdfDao

    companion object {
        @Volatile
        private var INSTANCE: CachedPdfDatabase? = null

        fun getInstance(context: Context): CachedPdfDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CachedPdfDatabase::class.java,
                    "docprism_pdf_cache.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class CachedPdfRepository(
    private val dao: CachedPdfDao,
    private val cacheDirectory: File
) {
    init {
        if (!cacheDirectory.exists()) {
            cacheDirectory.mkdirs()
        }
    }

    val allCachedPdfs: Flow<List<CachedPdfEntity>> = dao.observeAllCachedPdfs()

    suspend fun lookupValidCachedPdf(sha256Hash: String): CachedPdfEntity? {
        val existing = dao.getBySha256(sha256Hash) ?: return null
        val file = File(existing.localPdfPath)
        return if (file.exists() && file.length() > 100L) {
            existing
        } else {
            dao.deleteByHash(sha256Hash)
            null
        }
    }

    suspend fun recordCacheHit(sha256Hash: String) {
        dao.recordCacheHit(sha256Hash, System.currentTimeMillis())
    }

    fun getTargetCacheFile(sha256Hash: String): File {
        if (!cacheDirectory.exists()) cacheDirectory.mkdirs()
        return File(cacheDirectory, "$sha256Hash.pdf")
    }

    suspend fun saveConvertedPdf(entity: CachedPdfEntity) {
        dao.insertOrReplace(entity)
    }

    suspend fun deleteCachedPdf(entity: CachedPdfEntity) {
        runCatching { File(entity.localPdfPath).delete() }
        dao.deleteByHash(entity.sha256Hash)
    }

    suspend fun purgeEntireCache() {
        cacheDirectory.listFiles()?.forEach { runCatching { it.delete() } }
        dao.clearAll()
    }
}
