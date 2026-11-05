package com.group33.medicationreminder.core.data.journal

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import com.group33.shared.contract.ContractProtocol
import com.group33.medicationreminder.core.data.contract.OnlineCommandState
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "commands")
internal data class CommandRow(
    @PrimaryKey val operationId: String,
    val kind: String,
    val targetId: String?,
    val canonicalVersion: String = ContractProtocol.CANONICALIZATION_VERSION,
    val payload: ByteArray? = null,
    val state: String = OnlineCommandState.NOT_SUBMITTED.name,
    val claimToken: String? = null,
    val claimBoot: String? = null,
    val claimUntil: Long? = null,
    val resultKind: String? = null,
    val resultId: String? = null,
    val cacheState: String? = null,
    val rejectionJson: String? = null,
)

@Dao
internal interface CommandDao {
    @Insert suspend fun insert(row: CommandRow)
    @Update suspend fun update(row: CommandRow)
    @Query("SELECT * FROM commands WHERE operationId = :id") suspend fun find(id: String): CommandRow?
    @Query("SELECT * FROM commands ORDER BY operationId") fun observe(): Flow<List<CommandRow>>
}

@Database(entities = [CommandRow::class], version = 1, exportSchema = true)
internal abstract class CommandJournalDatabase : RoomDatabase() {
    abstract fun commands(): CommandDao
}
