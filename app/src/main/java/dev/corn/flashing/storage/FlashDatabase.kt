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

@Entity(tableName = "jobs")
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
)

@Dao
interface FlashDao {
    @Query("SELECT * FROM firmware WHERE id = 1")
    suspend fun firmware(): FirmwareEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveFirmware(value: FirmwareEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveJob(value: JobEntity)
    @Query("SELECT * FROM jobs ORDER BY startedAt DESC LIMIT 100")
    fun recentJobs(): Flow<List<JobEntity>>
    @Query("SELECT * FROM jobs ORDER BY startedAt DESC")
    suspend fun allJobs(): List<JobEntity>
    @Query("UPDATE jobs SET status = 'INTERRUPTED', stage = '任务中断', message = '应用进程已终止；结果未知，请重新执行', finishedAt = :now WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun recoverInterrupted(now: Long)
}

@Database(entities = [FirmwareEntity::class, JobEntity::class], version = 1, exportSchema = true)
abstract class FlashDatabase : RoomDatabase() { abstract fun dao(): FlashDao }
