package dev.corn.flashing.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "firmware")
data class FirmwareEntity(
    @PrimaryKey val id: Int = 1,
    val name: String,
    val fileName: String,
    val sha256: String,
    val binAddress: Long?,
)

@Entity(tableName = "jobs", indices = [Index("batchId"), Index("startedAt")])
data class JobEntity(
    @PrimaryKey val id: String,
    val firmwareName: String,
    val sha256: String,
    val profileName: String,
    val mode: String = "DEMO",
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: String = "QUEUED",
    val stage: String = "等待启动",
    val message: String = "模拟任务，不连接目标芯片",
    val targetUid: String? = null,
    @ColumnInfo(defaultValue = "NULL") val batchId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val seq: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val serial: String? = null,
)

/** Status values stored in [BatchEntity.status]. */
object BatchStatus {
    const val RUNNING = "RUNNING"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val ENDED = "ENDED"
    val ACTIVE = listOf(RUNNING, PAUSED)
}

@Entity(tableName = "batches")
data class BatchEntity(
    @PrimaryKey val id: String,
    val workOrder: String,
    val planned: Int,
    val duplicatePolicy: String,
    val pauseAfterFailures: Int,
    val serialPrefix: String?,
    val serialStart: Long,
    val serialDigits: Int,
    val firmwareName: String,
    val sha256: String,
    val profileId: String,
    val profileName: String,
    /** [dev.corn.flashing.core.ProductProfile.configSha256] frozen when the batch started. */
    @ColumnInfo(defaultValue = "") val configSha256: String = "",
    val mode: String = "DEMO",
    val injectFaults: Boolean = false,
    val status: String = BatchStatus.RUNNING,
    val pauseReason: String? = null,
    val startedAt: Long,
    val endedAt: Long? = null,
)

@Dao
interface FlashDao {
    @Query("SELECT * FROM firmware WHERE id = 1")
    suspend fun firmware(): FirmwareEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveFirmware(value: FirmwareEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveJob(value: JobEntity)
    @Query("SELECT * FROM jobs ORDER BY startedAt DESC LIMIT 10000")
    fun recentJobs(): Flow<List<JobEntity>>
    @Query("SELECT * FROM jobs ORDER BY startedAt DESC")
    suspend fun allJobs(): List<JobEntity>
    @Query("SELECT * FROM jobs WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun unfinishedJobs(): List<JobEntity>
    @Query("UPDATE jobs SET status = 'INTERRUPTED', stage = '任务中断', message = '应用进程已终止；这块板的程序可能不完整，需要重新烧录', finishedAt = :now WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun recoverInterrupted(now: Long)
    @Query("SELECT COUNT(*) FROM jobs")
    suspend fun jobCount(): Int
    @Query("DELETE FROM jobs WHERE id IN (SELECT id FROM jobs ORDER BY startedAt ASC LIMIT :count)")
    suspend fun deleteOldestJobs(count: Int)
    @Query("SELECT COUNT(*) FROM jobs WHERE startedAt >= :from AND startedAt < :to AND status IN ('SIMULATED','SUCCEEDED')")
    suspend fun countSucceededBetween(from: Long, to: Long): Int
    @Query("SELECT COUNT(*) FROM jobs WHERE startedAt >= :from AND startedAt < :to AND status = 'FAILED'")
    suspend fun countFailedBetween(from: Long, to: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBatch(value: BatchEntity)
    @Query("SELECT * FROM batches WHERE status IN ('RUNNING', 'PAUSED') ORDER BY startedAt DESC LIMIT 1")
    suspend fun activeBatch(): BatchEntity?
    @Query("SELECT * FROM batches WHERE id = :id")
    fun batch(id: String): Flow<BatchEntity?>
    @Query("SELECT * FROM batches ORDER BY startedAt DESC LIMIT 50")
    fun batches(): Flow<List<BatchEntity>>
    @Query("SELECT * FROM jobs WHERE batchId = :batchId ORDER BY seq")
    fun batchJobs(batchId: String): Flow<List<JobEntity>>
    @Query("SELECT * FROM jobs WHERE batchId = :batchId ORDER BY seq")
    suspend fun batchJobsNow(batchId: String): List<JobEntity>
}

@Database(
    entities = [FirmwareEntity::class, JobEntity::class, BatchEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
    ],
)
abstract class FlashDatabase : RoomDatabase() { abstract fun dao(): FlashDao }
