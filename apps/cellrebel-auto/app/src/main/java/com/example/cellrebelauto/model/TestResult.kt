package com.example.cellrebelauto.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Single test result from one automation cycle.
 * # 单次自动化循环的测试结果实体
 */
@Entity(
    tableName = "test_results",
    foreignKeys = [
        ForeignKey(
            entity = RunSession::class,
            parentColumns = ["id"],
            childColumns = ["runSessionId"],
            onDelete = ForeignKey.CASCADE // # 级联删除：删除 session 时一并删除结果
        )
    ],
    indices = [Index("runSessionId")]
)
data class TestResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runSessionId: Long,
    // # 测试时间戳（毫秒）
    val timestamp: Long,
    // # 网页浏览分数（0-10，-1 表示采集失败）
    val webBrowsingScore: Double,
    // # 视频流分数（0-10，-1 表示采集失败）
    val videoStreamingScore: Double,
    // # 测试时的 GPS 纬度
    val latitude: Double,
    // # 测试时的 GPS 经度
    val longitude: Double,
    // # 当前循环编号（从 1 开始）
    val cycleIndex: Int,
    // # 结果状态：ok / error_no_scores / error_timeout
    val status: String = "ok"
)
