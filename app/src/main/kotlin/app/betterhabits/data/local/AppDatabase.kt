package app.betterhabits.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Local copy of household data. The UI reads only from here; [app.betterhabits.data.sync.SyncEngine]
 * keeps it in step with Supabase. Cleared on sign-out.
 */
@Database(
    entities = [ChoreEntity::class, OccurrenceEntity::class, OutboxEntity::class, SyncCursorEntity::class, CacheEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun choreDao(): ChoreDao
    abstract fun outboxDao(): OutboxDao
    abstract fun syncCursorDao(): SyncCursorDao
    abstract fun cacheDao(): CacheDao
}

/** A chore stored as its server row JSON (ChoreDto), with the fields needed for queries. */
@Entity(tableName = "chores", indices = [Index("householdId")])
data class ChoreEntity(
    @PrimaryKey val id: String,
    val householdId: String,
    val json: String,
    val deleted: Boolean,
    /** Server updated_at; null for local changes not yet synced. */
    val updatedAt: String?,
)

/** One occurrence record. [time] is "" for all-day occurrences (primary keys can't be null). */
@Entity(
    tableName = "occurrences",
    primaryKeys = ["choreId", "date", "time"],
    indices = [Index("householdId", "date")],
)
data class OccurrenceEntity(
    val choreId: String,
    val date: String,
    val time: String,
    val householdId: String,
    val status: String,
    val assigneeId: String?,
    val completedBy: String?,
    val completedAtMillis: Long?,
    val snoozedUntilMillis: Long?,
    val note: String?,
    val updatedAt: String?,
)

/** A local change waiting to be sent to the server, in [seq] order. */
@Entity(tableName = "outbox", indices = [Index("householdId")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val householdId: String,
    /** Serialized [app.betterhabits.data.sync.PendingOp]. */
    val op: String,
    /** What the op touches ("chore:<id>" or "occurrence:<choreId>|<date>|<time>"), so pulls don't overwrite it. */
    val entityKey: String,
    val createdAtMillis: Long,
    val attempts: Int = 0,
)

@Entity(tableName = "sync_cursors", primaryKeys = ["householdId", "stream"])
data class SyncCursorEntity(val householdId: String, val stream: String, val cursor: String)

/** Small JSON blobs cached for offline reads (household list, household details). */
@Entity(tableName = "cache")
data class CacheEntity(@PrimaryKey val key: String, val json: String)

@Dao
interface ChoreDao {
    @Query("SELECT * FROM chores WHERE householdId = :householdId AND deleted = 0")
    fun observeChores(householdId: String): Flow<List<ChoreEntity>>

    @Query("SELECT * FROM chores WHERE id = :id")
    fun observeChore(id: String): Flow<ChoreEntity?>

    @Query("SELECT * FROM chores WHERE id = :id")
    suspend fun chore(id: String): ChoreEntity?

    @Upsert
    suspend fun upsertChores(chores: List<ChoreEntity>)

    @Query("SELECT * FROM occurrences WHERE householdId = :householdId AND date BETWEEN :from AND :to")
    fun observeOccurrences(householdId: String, from: String, to: String): Flow<List<OccurrenceEntity>>

    @Query(
        """SELECT * FROM occurrences WHERE householdId = :householdId AND status IN ('COMPLETED', 'SKIPPED')
           AND (:choreId IS NULL OR choreId = :choreId)
           ORDER BY date DESC, COALESCE(completedAtMillis, 0) DESC LIMIT :limit""",
    )
    fun observeHistory(householdId: String, choreId: String?, limit: Int): Flow<List<OccurrenceEntity>>

    @Query("SELECT * FROM occurrences WHERE choreId = :choreId AND date = :date AND time = :time")
    suspend fun occurrence(choreId: String, date: String, time: String): OccurrenceEntity?

    @Upsert
    suspend fun upsertOccurrences(occurrences: List<OccurrenceEntity>)

    @Query("DELETE FROM chores WHERE id = :id")
    suspend fun deleteChore(id: String)

    @Query("DELETE FROM occurrences WHERE choreId = :choreId AND date = :date AND time = :time")
    suspend fun deleteOccurrence(choreId: String, date: String, time: String)

    @Query("DELETE FROM chores WHERE householdId = :householdId")
    suspend fun deleteChores(householdId: String)

    @Query("DELETE FROM occurrences WHERE householdId = :householdId")
    suspend fun deleteOccurrences(householdId: String)
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(op: OutboxEntity): Long

    @Query("SELECT * FROM outbox WHERE householdId = :householdId ORDER BY seq")
    suspend fun ops(householdId: String): List<OutboxEntity>

    @Query("SELECT DISTINCT householdId FROM outbox")
    suspend fun householdsWithPending(): List<String>

    @Query("SELECT entityKey FROM outbox WHERE householdId = :householdId")
    suspend fun pendingKeys(householdId: String): List<String>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM outbox WHERE seq = :seq")
    suspend fun delete(seq: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE seq = :seq")
    suspend fun incrementAttempts(seq: Long)
}

@Dao
interface SyncCursorDao {
    @Query("SELECT cursor FROM sync_cursors WHERE householdId = :householdId AND stream = :stream")
    suspend fun cursor(householdId: String, stream: String): String?

    @Upsert
    suspend fun set(cursor: SyncCursorEntity)

    @Query("DELETE FROM sync_cursors WHERE householdId = :householdId")
    suspend fun clear(householdId: String)
}

@Dao
interface CacheDao {
    @Query("SELECT json FROM cache WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(entry: CacheEntity)
}
