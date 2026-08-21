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
    // # 状态：running / completed / advance_pending / stopped / error / interrupted
    // # advance_pending = 所有 task 配额已满但 advance 记录未全部 resolved（§8.1）
    // # → recovery sweep 解决后自动升级为 completed
    val status: String = "running",
    // # 配置快照（序列化字符串）
    val configSnapshot: String = "",
    // # 已完成的循环数
    val totalCycles: Int = 0,
    // # 关联的位置计划（v3 新增，旧会话为 null）
    val planId: Long? = null
)
