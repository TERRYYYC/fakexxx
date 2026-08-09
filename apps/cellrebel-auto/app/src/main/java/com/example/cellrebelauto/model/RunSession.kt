package com.example.cellrebelauto.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents one automation session (start → stop/done/error).
 * # 一次自动化会话记录（从启动到停止/完成/出错）
 */
@Entity(tableName = "run_sessions")
data class RunSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // # 开始时间戳
    val startedAt: Long,
    // # 结束时间戳（null = 仍在运行）
    val endedAt: Long? = null,
    // # 状态：running / completed / stopped / error
    val status: String = "running",
    // # 配置快照（序列化字符串）
    val configSnapshot: String = "",
    // # 已完成的循环数
    val totalCycles: Int = 0,
    // # 关联的位置计划（v3 新增，旧会话为 null）
    val planId: Long? = null
)
