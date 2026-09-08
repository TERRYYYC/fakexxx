package com.example.cellrebelauto.ui.dashboard.v2

/**
 * Tile map (T-tilemap, 2026-09-08) — the tiles CONTRACT constants.
 *
 * ## 生产环境注意（承 ui-hifi/v3 README）
 * 底图默认 = OSM 官方标准栅格（tile.openstreetmap.org）。OSMF 使用政策仅允许
 * 低量/内部测试使用官方瓦片；量产必须换自建/商用瓦片源。选型结论（operator
 * 2026-09-08 拍板）：osmdroid（栅格、最省事、可换源）——不引 MapLibre（体积与
 * 复杂度都超出本卡需要；未来若要矢量/3D，迁移路径 = 换 [TilePlanMapCard] 的
 * AndroidView 实现，选卡决策与降级策略全部复用）。
 *
 * [TILE_SOURCE_URL] 是唯一的瓦片源配置点（TILE_SOURCE 配置点）：换源只改这里
 * 与 [userAgent]，选卡/降级/归属逻辑不动。注意换源后归属字符串须同步替换为
 * 数据源要求的信用文本（ODbL © OpenStreetMap contributors 仅对 OSM 数据）。
 *
 * # 瓦片契约：归属常量（ODbL 强制）、TILE_SOURCE 单点、UA 带 app id
 */
object OsmTileSource {

    /**
     * ODbL 要求的归属信用文本；UI 在瓦片图右下角常驻显示（测试钉死）。
     * 原型 v3 显示 "© OpenStreetMap 贡献者"，生产用规范英文信用。
     */
    const val ATTRIBUTION = "© OpenStreetMap contributors"

    /** 默认瓦片源 = OSM 官方标准栅格（仅低量/内部测试合规，见类注释）。 */
    const val TILE_SOURCE_URL = "https://tile.openstreetmap.org/"

    /** v3 MAPZ.min — 原型全屏地图的最小整数层级。 */
    const val MIN_ZOOM = 3

    /** tile.openstreetmap.org 服务上限 z19（原型 fit 的 maxZ=16 只约束初始 fit）。 */
    const val MAX_ZOOM = 19

    /**
     * OSMF 使用政策要求可识别的 HTTP User-Agent（osmdroid 通用默认
     * "osmdroid" 会被限流/拒绝）。这里带真实 application id。
     */
    fun userAgent(applicationId: String): String = "$applicationId osmdroid-android"
}
