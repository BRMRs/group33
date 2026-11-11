package com.group33.medicationreminder.integration

import android.content.Context
import androidx.room.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

/** Private, scope-partitioned snapshots of the existing wire collections; never a second UI truth. */
@Entity(tableName = "chain_documents")
internal data class ChainDocument(
    @PrimaryKey val id: String,
    val scopeId: String,
    val kind: String,
    val target: String,
    val body: String,
    val syncedAt: Long,
    val access: String = "AUTHORIZED",
    val stale: Boolean = false,
)

@Dao
internal interface ChainDocuments {
    @Query("SELECT * FROM chain_documents WHERE id = :id") fun observe(id: String): Flow<ChainDocument?>
    @Query("SELECT * FROM chain_documents WHERE id = :id") suspend fun read(id: String): ChainDocument?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: ChainDocument)
    @Query("UPDATE chain_documents SET access = 'UNKNOWN', stale = 1 WHERE scopeId = :scope")
    suspend fun invalidateAuthority(scope: String)
    @Query("UPDATE chain_documents SET stale = 1 WHERE scopeId = :scope AND kind = :kind AND target = :target")
    suspend fun markStale(scope: String, kind: String, target: String)
    @Query("UPDATE chain_documents SET access = 'REVOKED', stale = 1 WHERE scopeId = :scope AND target = :target")
    suspend fun revoke(scope: String, target: String)
}

@Database(entities = [ChainDocument::class], version = 1, exportSchema = true)
internal abstract class ChainCache : RoomDatabase() {
    abstract fun documents(): ChainDocuments

    companion object {
        fun open(context: Context) = Room.databaseBuilder(context.applicationContext, ChainCache::class.java,
            File(context.noBackupFilesDir, "debug-online-cache.db").absolutePath)
            .enableMultiInstanceInvalidation().build()
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
