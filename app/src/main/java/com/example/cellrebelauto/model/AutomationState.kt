package com.example.cellrebelauto.model

/**
 * All possible states of the automation engine.
 * # 自动化引擎的所有可能状态，每个状态有对应的中文显示名
 *
 * Workflow order: GPS first, then test.
 * # 工作流顺序：先设置 GPS 位置，再运行测试
 *   1. Set fake GPS location
 *   2. Wait for GPS to settle
 *   3. Run CellRebel test
 *   4. Collect results
 *   5. Repeat with new location
 */
enum class AutomationState(val displayName: String) {
    // # 空闲状态
    IDLE("Idle"),

    // --- Fake GPS phase ---
    // # 正在启动 Fake GPS 应用
    LAUNCHING_FAKE_GPS("Launching Fake GPS..."),
    // # 正在停止上一次的 GPS 伪造
    STOPPING_OLD_GPS("Stopping old GPS..."),
    // # 正在地图上设置新坐标
    SETTING_LOCATION("Setting location on map..."),
    // # 正在确认 GPS 位置（点击地图或搜索结果）
    CONFIRMING_LOCATION("Confirming location..."),
    // # 正在启动 GPS 伪造
    STARTING_FAKE_GPS("Starting Fake GPS..."),

    // --- CellRebel phase ---
    // # 正在启动 CellRebel 应用
    LAUNCHING_CELLREBEL("Launching CellRebel..."),
    // # 正在导航到测试页面
    NAVIGATING_TO_TEST("Navigating to test..."),
    // # 正在点击开始测试
    STARTING_TEST("Starting test..."),
    // # 测试进行中，等待结果
    WAITING_FOR_RESULT("Running test..."),
    // # 正在收集测试分数
    COLLECTING_RESULT("Collecting results..."),

    // --- Timing ---
    // # 等待 GPS 信号稳定 / 周期间隔
    WAITING_INTERVAL("Waiting for next cycle..."),

    // --- Attempt terminal + scheduler (F001, wireframe v2.1 §1.2) ---
    // # 正在收尾一次尝试（持久化结果）
    PROCESSING("Processing..."),
    // # 尝试成功（终态）
    SUCCEEDED("Attempt succeeded"),
    // # 尝试失败（终态，类型化原因）
    FAILED("Attempt failed"),
    // # scheduler 全局缓冲倒计时（attempt 终态之后，INV-5）
    COOLDOWN("Scheduler cooldown..."),

    // --- Terminal ---
    // # 所有循环已完成
    DONE("Done"),
    // # 发生不可恢复的错误
    ERROR("Error")
}
