package name.caiyao.fakegps.integration.v1

/**
 * #176 方案 A+C：运行期地址读回验证与自动跟随（delivery readback gate）。
 *
 * A+ 合同的 observe 腿证明的是 provider **声称**的环境；#175（载荷钉死在生效档案）、
 * 2026-09-11 appops 重置（addTestProvider SecurityException 穿 binder）、Vector 半套注入
 * （载荷静默不更新）三次设备事故的共同形态都是"声称正确、设备事实错误"——引擎的坐
 * 标断言查 provider 侧记录，全绿。
 *
 * 本门在 `applyEnvironment` 投递三层（系统 mock / hook 载荷坐标 / 载荷 addname）之后
 * 读回**事实**并与当前日程项的期望值比对：
 *
 *  - hook 载荷层（对被测 app 生效的主要层）**字节级**验证：读传输文件本身，绕过进程内
 *    SharedPreferences 缓存——"缓存有值但文件未落盘"的半套注入形态会以陈旧事实暴露。
 *    读不到即 fail-closed。
 *  - 系统 mock 层：坐标可读（前台/定位 appops 允许的进程）则比对坐标（抓 stale）；
 *    后台 provider 进程读不到坐标时（FINE_LOCATION 默认 foreground 模式，设备实证
 *    2026-09-12）退回"投递即证明"——replaceGpsProvider/publish 任一断链都会在投递阶段
 *    抛异常，到读回这一步仍无异常即注册+注入成立。降级以日志明示，不冒充坐标已验。
 *  - 任一层比对不一致 → 触发一次完整重投递（修复阶梯第 1 级）后再读回；
 *  - 修复后仍不一致 → Mismatch（含期望值、两层实际值与修复标记）。调用方必须把它
 *    变成 typed 失败（CAPABILITY_UNAVAILABLE），**绝不返回"部分成功"的收据**。
 *
 * 读回的事实源（[Readback]）由调用方注入——**绝不采信 app 自维护的状态记录**
 * （2026-09-12 设备实证：QWY 本地 publish_state 残留六天前的 publish_failed=true，
 * UI 横幅据此误报"系统 mock 未生效"，而 dumpsys 证明三层实际全部生效——自记录与
 * 事实会双向漂移）。
 *
 * 坐标容差 [COORDINATE_TOLERANCE] 吸收档案 DB REAL → JSON 载荷 → 读回的浮点往返
 * （设备实测 49.732763 vs 49.73276311），与计划导入的逐位比对口径一致。
 *
 * #189 CI 腿：expectedCi 非空时比对载荷 fields.ci（十进制 28-bit ECI，与
 * CellRebel 的 getCi() 同域，零换算）。语义与坐标腿同 fail-closed：item 的
 * ci 为 null（3 列旧档案 / 未带 ECGI 的计划行）→ **跳过** ci 比对，行为与
 * #176 完全一致（向后兼容）；非空且与载荷不符（含载荷缺 ci 列）→ MISMATCH
 * 拒绝，detail 以 `payloadCi=` 标明是 ci 腿。修复阶梯（重投递→重读回）不动。
 *
 * #189 一期边界（明确不做，保持透传真实值）：
 *  - mcc/mnc/tac/pci 不比对：档案/站点表无这些列，一期接受"新 ECI + 旧
 *    TAC/PCI 同框"的呈现（验收口径 = ECGI 对照，见 issue #189 调研报告 §6）；
 *  - 设备驻留 NR 时 CellRebel 走 CellIdentityNr.getNci()，本门只护 LTE ci 腿，
 *    nci 路径一期不生效。
 */
class DeliveryReadbackGate(private val log: (String) -> Unit = {}) {

    /**
     * 三层读回快照。mock 坐标 null = 该进程读不到坐标（后台 provider 进程常态），
     * 此时 mock 层按"投递即证明"接受；载荷坐标 null = hook 载荷读不回来，fail-closed。
     * payloadCi null = 载荷 fields 无 ci 列（3 列旧档案的合法形态，是否构成
     * mismatch 由 expectedCi 决定）。
     */
    data class Readback(
        val mockLatitude: Double?,
        val mockLongitude: Double?,
        val payloadLatitude: Double?,
        val payloadLongitude: Double?,
        val payloadAddname: String?,
        val payloadCi: Long? = null,
    )

    sealed interface Outcome {
        data object Verified : Outcome
        data class Mismatch(val detail: String) : Outcome
    }

    fun enforce(
        expectedLatitude: Double,
        expectedLongitude: Double,
        expectedAddname: String?,
        initialReadback: Readback,
        repairAndReadback: () -> Readback,
        expectedCi: Long? = null,
    ): Outcome {
        initialReadback.diagnose(expectedLatitude, expectedLongitude, expectedAddname, expectedCi)
            ?.let { detail ->
            log("readback MISMATCH before repair: $detail")
            val afterRepair = repairAndReadback()
            afterRepair.diagnose(expectedLatitude, expectedLongitude, expectedAddname, expectedCi)
                ?.let { repairedDetail ->
                log("readback MISMATCH after 1 repair (delivery did not follow the schedule item): $repairedDetail")
                return Outcome.Mismatch(
                    "expected=($expectedLatitude,$expectedLongitude)${addnameSuffix(expectedAddname)}" +
                        "${ciSuffix(expectedCi)} " +
                        "initial=[$detail] afterRepair=[$repairedDetail]",
                )
            }
            log(
                "readback repaired: delivery now follows expected=($expectedLatitude,$expectedLongitude)" +
                    addnameSuffix(expectedAddname) + ciSuffix(expectedCi),
            )
            return Outcome.Verified
        }
        log(
            "readback OK: mock+payload follow expected=($expectedLatitude,$expectedLongitude)" +
                addnameSuffix(expectedAddname) + ciSuffix(expectedCi) + initialReadback.mockNote(),
        )
        return Outcome.Verified
    }

    /** null = 比对通过；非 null = 第一处不一致的人读诊断。 */
    private fun Readback.diagnose(
        expectedLatitude: Double,
        expectedLongitude: Double,
        expectedAddname: String?,
        expectedCi: Long?,
    ): String? {
        val problems = mutableListOf<String>()
        // mock 坐标可读才比对（后台读不到≠错，见类注释）；可读但陈旧 = 真实漂移。
        if (mockLatitude != null && mockLongitude != null) {
            if (!co(expectedLatitude, mockLatitude) || !co(expectedLongitude, mockLongitude)) {
                problems += "mock=($mockLatitude,$mockLongitude)"
            }
        }
        if (payloadLatitude == null || payloadLongitude == null) {
            problems += "payload=unreadable"
        } else {
            if (!co(expectedLatitude, payloadLatitude) || !co(expectedLongitude, payloadLongitude)) {
                problems += "payload=($payloadLatitude,$payloadLongitude)"
            }
            if (payloadAddname != expectedAddname) {
                problems += "payloadAddname=$payloadAddname"
            }
            // #189 CI 腿：item 期望 ci 非空才比对（null = 旧档案/未带 ECGI 的计划行，
            // 完全向后兼容——跳过）。载荷缺 ci 列（payloadCi=null）或值不符都算腿错，
            // 与坐标腿同 fail-closed；载荷整体不可读已由 payload=unreadable 覆盖，不重复报。
            if (expectedCi != null && payloadLatitude != null && payloadLongitude != null) {
                if (payloadCi != expectedCi) {
                    problems += "payloadCi=$payloadCi"
                }
            }
        }
        return if (problems.isEmpty()) null else problems.joinToString(" ")
    }

    private fun Readback.mockNote(): String =
        if (mockLatitude == null || mockLongitude == null) {
            " [mock=coords-unverifiable-from-this-process; registration proven by exception-free delivery]"
        } else ""

    private fun co(expected: Double, actual: Double): Boolean =
        kotlin.math.abs(expected - actual) <= COORDINATE_TOLERANCE

    private fun addnameSuffix(expectedAddname: String?): String =
        expectedAddname?.let { " addname=$it" } ?: " addname=null"

    /** 期望 ci 只进日志/detail 的期望侧；载荷侧的实际值由 payloadCi 腿名点出。 */
    private fun ciSuffix(expectedCi: Long?): String =
        expectedCi?.let { " ci=$it" } ?: ""

    companion object {
        /** 档案→载荷→读回的浮点往返容差（设备实测口径，见类注释）。 */
        const val COORDINATE_TOLERANCE = 1e-6
    }
}
