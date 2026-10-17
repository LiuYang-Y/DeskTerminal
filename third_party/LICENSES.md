# 第三方依赖与许可证清单

**维护规则**：加依赖时在这里登记一行；许可证**从该版本 POM 的 `<licenses>` 段读**
（`~/.gradle/caches/modules-2/files-2.1/<group>/<artifact>/<ver>/<hash>/<artifact>-<ver>.pom`），
不凭印象填。下表"核实"一列记录的是哪天、从哪份 POM 读的。

本清单只列**第三方**；`androidx.*` / `com.google.*`（AndroidX 与官方工具链，全部 Apache-2.0）
与 `org.jetbrains.kotlin*`（Apache-2.0）作为两个家族各列一行，不逐个枚举。

---

## 运行时依赖（打进 APK）

| 库 | 版本 | 来源 | 许可证 | 核实 | 用途 |
| --- | --- | --- | --- | --- | --- |
| `io.github.kyant0:backdrop` | 2.0.0 | github.com/Kyant0/AndroidLiquidGlass | **Apache-2.0** | 2026-10-11，POM | 液态玻璃（`core:ui` 的 DeskGlass）。另注（2026-10-18）：侧栏水滴的视觉参数（按压鼓出、速度果冻 squash & stretch 比例、内缘厚度/高光弧结构）参考同仓库 `app/.../components/LiquidBottomTabs.kt` 示例（同许可）；当前实现为**纯自绘、不采样 backdrop**，仅借参数不借代码 |
| `dev.rikka.shizuku:api` / `:provider` | 13.1.5 | github.com/RikkaApps/Shizuku-API | **MIT** | 2026-10-14，POM | 无 root 提权（`:hosting`）。MIT 要求在发行物中保留版权与许可声明 |
| `com.amap.api:navi-3dmap-location-search` | 11.3.100_3dmap11.3.100_loc11.3.000_sea9.8.1 | repo1.maven.org（高德官方坐标） | **专有**（"Beijing Autonavi Tuqiang Technology Co., Ltd, Version 1.0"，amap.com/licenses/LICENSE-1.0.txt） | 2026-10-14，POM | 地图 + 导航（`:map`）。闭源 SDK：使用受高德开放平台服务条款约束，需 Key 与隐私合规闸门（`AMapPrivacy`），分发条件以高德条款为准 |
| `com.mocharealm.accompanist:lyrics-core` | 0.5.0 | github.com/6xingyv/Accompanist（**非** Google 官方 Accompanist） | **Apache-2.0** | 2026-10-14，POM | 歌词解析（仅 `LyricsParser` 一个文件 import；逐字/TTML/KRC 能力）。纯 KMP，无 android 变体，Android 解析到 jvm 变体 |
| ↳ 传递：`org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.10.0 | Maven Central | **Apache-2.0** | 2026-10-14，POM | lyrics-core 的运行时依赖 |
| Kotlin 标准库（`kotlin-stdlib`） | 2.4.20 | JetBrains | **Apache-2.0** | 2026-10-14，POM | 语言运行时 |

## AndroidX / Google 官方家族（全部 **Apache-2.0**，不逐个列）

`androidx.core:core-ktx`、`androidx.activity:activity-compose`、`androidx.lifecycle:*`、
`androidx.compose:*`（BOM 2026.09.00 统一）、`androidx.test:*`（androidTest）。

## 测试专用（不打进 APK）

| 库 | 版本 | 许可证 | 核实 | 用途 |
| --- | --- | --- | --- | --- |
| `junit:junit` | 4.13.2 | **EPL-1.0** | 2026-10-14，POM | JVM 单元测试（MusicLayoutTest 等）。EPL-1.0 仅在**分发**修改版时要求源码可见；测试框架本身随测试代码，不影响产品许可 |
| ↳ 传递：`org.hamcrest:hamcrest-core` | 1.3 | **New BSD** | 2026-10-14，hamcrest-parent POM | junit 的匹配器库 |

---

## 已否决 / 有意排除（记录原因，防止下次再踩）

| 项 | 原因 |
| --- | --- |
| `com.mocharealm.accompanist:lyrics-ui` | AAR minSdk=29 > 本项目 26；POM 挂 8 个 `org.jetbrains.compose.*`，会重开 `:core:ui` 排除过的坑。只用 `lyrics-core` |
| **任何 AGPL-3.0 许可的代码** | 本仓库一行都不允许出现。AGPL 照搬即传染整仓。音乐路线已定方向：自研（Compose + 系统媒体会话），无 AGPL 接触面 |

## 备注

- 依赖版本与"已核实"的原始结论统一记录在 `gradle/libs.versions.toml` 的注释里，两处同步维护。
- 若未来需要"应用内关于页"，本清单可直接生成。
