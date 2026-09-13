package com.example.cellrebelauto.ui.dashboard.v2

/**
 * Tile map (T-tilemap, 2026-09-08) — the tiles CONTRACT constants.
 *
 * ## 生产环境注意（承 ui-hifi/v3 README）
 * 底图默认 = OSM 德国镜像标准栅格（tile.openstreetmap.de，FOSSGIS 运营）。
 * 2026-09-13 换源（operator 拍板，决策记录见 issue #182）：官方源
 * tile.openstreetmap.org 对"蜂窝 CGNAT 出口 + 非浏览器 UA"整页 403（Access
 * blocked；三组对照实验见 PR #183 设备验证段），镜像同日真机验证瓦片真实
 * 下载渲染。镜像服务的是同一份 ODbL OSM 数据，归属信用文本不变；其使用
 * 政策与官方源同样仅允许低量/内部测试（限制商业/高流量用途）——量产仍
 * 必须换自建/商用瓦片源。选型结论（operator 2026-09-08 拍板）：osmdroid
 * （栅格、最省事、可换源）——不引 MapLibre（体积与复杂度都超出本卡需要；
 * 未来若要矢量/3D，迁移路径 = 换 [TilePlanMapCard] 的 AndroidView 实现，
 * 选卡决策与降级策略全部复用）。
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
     * （镜像与官方源同为 OSM ODbL 数据，换源不变。）
     */
    const val ATTRIBUTION = "© OpenStreetMap contributors"

    /**
     * 默认瓦片源 = OSM 德国镜像标准栅格（FOSSGIS 运营；2026-09-13 换源，
     * 见类注释与 issue #182）。镜像与官方源同样仅低量/内部测试合规。
     */
    const val TILE_SOURCE_URL = "https://tile.openstreetmap.de/"

    /** v3 MAPZ.min — 原型全屏地图的最小整数层级。 */
    const val MIN_ZOOM = 3

    /**
     * 镜像实测可服务 z19（2026-09-13 巴黎/纽约/东京/柏林 z19 瓦片均 HTTP 200
     * 且为真实渲染内容，非占位块）——"镜像 z≤18" 的预设不成立，维持 19 与原
     * 官方源契约一致（镜像实际还能出 z20，保守不跟）。原型 fit 的 maxZ=16 只
     * 约束初始 fit。
     */
    const val MAX_ZOOM = 19

    /**
     * 瓦片服务器（OSMF 官方与 FOSSGIS 镜像同政策）要求可识别的 HTTP
     * User-Agent（osmdroid 通用默认 "osmdroid" 会被限流/拒绝）。
     * 这里带真实 application id。
     */
    fun userAgent(applicationId: String): String = "$applicationId osmdroid-android"
}
