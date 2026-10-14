# DeskTerminal · 给外部 AI 的项目交接说明

> 用途：把项目交给没有上下文的 AI **继续开发**时，把这份文档（或第 0 节）一起给它。
> 三份文档的分工：
> - `PROJECT-INTRO.md` —— **做了什么、做到哪一步**（先给这个，让 AI 建立整体认识）
> - 本文件 —— **怎么在上面干活**（架构、硬约束、坑、已否决方案）
> - `README.md` —— 权限逐条说明、数据去向、合规（给用户/评审看，26 KB）
>
> 最后更新：2026-10-14。

---

## 0. 如果你只有两分钟（可直接粘贴给 AI 的最小上下文）

```
项目：DeskTerminal —— 把闲置 Android 手机变成长时间摆在桌上的横屏终端。
      一屏显示时间/待办/天气/音乐/地图，可投屏到车机、可把第三方 App 托管进本应用显示。

技术栈：Kotlin + Jetpack Compose（Material 3），多模块 Gradle，AGP 9.4.1，
        Kotlin 2.4.20，Compose BOM 2026.09.00，compileSdk 37 / minSdk 26 / JDK 17。
        约 83 个 Kotlin 文件、约 2 万行。非 root。许可证 GPL-3.0。

模块：:app（唯一 app 模块）:shell（桌面壳/侧栏/首页）:core:{common,model,ui,platform,data}
      :feature:{home,media} :map（高德合包：地图+导航）:hosting（Shizuku 虚拟屏托管）
      :projection:{api,carplay,auto} :connection

四条硬约束（违反会出真 bug，不是风格问题）：
1. 尺寸不许写死 dp。所有尺寸按运行时可用空间计算（core/ui/AdaptiveLayout.kt），
   因为要同时跑 794×361dp 与 914×411dp 两种屏幕。heightIn(max=…) 这类"上限"也算写死。
2. 不代用户点授权弹窗。Shizuku / 系统权限只能用户本人点，应用侧只发 launch intent。
3. 调试入口只进 debug 变体，不进正式构建。
4. 效果档位只有一个权威：`EffectTier`（`LocalEffectTier`）。玻璃层建不建、是否采样背景、
   侧栏透镜拿哪一档，全部读它；不许再按 `SDK_INT` 自行分档（两条线永不相交，
   症状是"用户在设置里切档、画面一分不变"）。

两个反直觉事实（不知道就会误判）：
· 地图浮层做不了真高斯模糊，也拿不到 Compose 玻璃层的采样——WebView / SurfaceView
  都是独立图层。所以"给地图/网页内容加毛玻璃"是死路，只能靠遮罩 + 对比度。
· adb logcat 在这台开发机恒为空，日志走文件（files/map/map.log）；
  存活判据用 pidof，崩溃看 tombstone 数量，不用 ps -A | grep。

当前在做：给项目加"横屏歌词舞台 + 登录在线音乐平台"的能力。
已完成评估：引入开源 Web 播放器 folia（AGPL-3.0）走 WebView/PWA 路线——
结论是"只做一个平台时，原生自研比引入 folia 划算"，详见 §8。
```

---

## 1. 产品定位（一句话之外的补充）

- **形态**：一个横屏 Android 桌面（Launcher 性质），长期摆在桌上、昼夜不熄。
- **不是**：不是车载系统，不是 ROM。是一个普通三方 App，靠 `Activity` + 沉浸式实现"桌面感"。
- **核心卖点**：不按某一台机器写死；效果按设备能力分四档并可运行时降级。
- **两台交叉验证机**（都不是"基准"，只是两个样本）：
  - OnePlus PKR110，Android 16 / API 36，横屏 **794 × 361 dp**，1264×2780 @560dpi，**无 root**
  - 小米 11 Ultra，Android 11 / API 30，横屏 **914 × 411 dp**，Magisk root
  - **两者差 120dp 宽、50dp 高**——跨尺寸适配 bug 基本都由这两个差值捅出来

---

## 2. 模块地图

| 模块 | 职责 | 规模 | 主要入口 |
| --- | --- | --- | --- |
| `:app` | 唯一 app 模块，启动与全局装配 | ~460 行 | — |
| `:shell` | 桌面壳：侧栏、首页分页、设置浮层、玻璃透镜 | ~6,000 行 | `shell/DeskRoot.kt`、`shell/HomeScreen.kt` |
| `:core:ui` | 玻璃材质、自适应尺寸、主题 | 含 `DeskGlass.kt`、`AdaptiveLayout.kt`、`AdaptiveLocals.kt` | |
| `:core:platform` | 运行时设备档案（尺寸/刷新率/挖孔/能力档） | `core/platform/DeviceProfile.kt` | |
| `:core:data` | 持久化与仓储 | `HomeRepository.kt` | |
| `:core:{common,model}` | 基础工具与数据模型 | | |
| `:feature:home` | 首页卡片 | ~1.5k 行 | `feature/home/HomeCards.kt` |
| `:feature:media` | **读取**别的播放器的 MediaSession 显示到卡片 | `MediaSessionMonitor.kt`、`MediaNotificationListener.kt`、`MediaCard.kt` | |
| `:map` | 高德合包：底图 + 定位 + 搜索 + 导航 | ~4.8k 行 | `map/.../navi/NaviSession.kt`、`map/.../MapViewHost.kt` |
| `:hosting` | 借 Shizuku 建虚拟屏，托管无接口的第三方 App | ~2.3k 行 | |
| `:projection:{carplay,auto}` | 投屏协议 | ~1k 行 | |
| `:connection` | 连接管理 | ~460 行 | |

**注意 `:feature:media` 的性质**：它是**观察者**，用 `MediaSessionManager.getActiveSessions` + `NotificationListenerService` 读"别的 App 正在放什么"。**它自己不播放**。

---

## 3. 关键架构决策（别建议推翻）

1. **只有一个 app 模块（`:app`）**，其余全是 library。
2. **非 root**：需要提权的能力走 Shizuku，且**不代用户点授权弹窗**。
3. **导航状态是进程级 object**（`NaviSession`）：界面销毁/翻页/切侧栏/息屏/切后台都**不允许结束导航**，只有用户显式退出才 `stopNavi`。这是修过的真 bug，不要改成"跟着界面生命周期"。
4. **高德用合包** `navi-3dmap-location-search`：导航包自带 3D 地图 SDK，与独立地图包并存会 duplicate class。**只能有一个包**。
5. **效果档位随设备能力 + 运行时降级**，不写死；**唯一权威是 `EffectTier`（`LocalEffectTier`）**。
   玻璃离屏层建不建（`EffectTier.glassLayerEnabled`）、`deskGlassSurface` 是否采样背景、
   侧栏透镜拿哪一档镜片，全部读这一个 Local。
   **不许再按 `Build.VERSION.SDK_INT` 自行分档**（侧栏透镜曾经这么干）—— 那两条线永不相交，
   症状是"用户在设置里切高/中/低/关，画面一分不变"。已修，别改回去。
   **OFF 档的定义是"不建离屏层"，不是"整条侧栏消失"**：它仍画一块静态纯色半透明底，
   关掉的只是采样、合成与透镜放大/描边。

---

## 4. 硬约束（违反会出真 bug）

| 约束 | 为什么 | 具体表现 |
| --- | --- | --- |
| **尺寸不写死 dp** | 两种屏幕差 120dp 宽 / 50dp 高 | 曾在 143dp 窄卡把时钟裁成 `08:3`；`heightIn(max=332.dp)` 这类**上限**也算写死（可用高度剩 293dp 时照样顶出屏幕）。换不同尺寸机器**必须进回归清单** |
| **不代点授权弹窗** | 系统限制 | Shizuku / 系统权限只能用户点，应用侧只发 launch intent |
| **调试入口只进 debug 变体** | 探针代码不进正式构建 | |
| **`MapViewHost.release()` 只在 `refCount == 0` 时才允许 detach + onPause** | 切屏那一帧是"新使用点 acquire → 旧使用点 release" | 无条件 detach 摘掉的是**刚挂好的新父容器**，MapView 成游离 View：不渲染也收不到触摸（整块黑且拖不动） |
| **组合期不许读动画状态** | 每帧重组 → 重建图形层 → hwui 事务压爆 → **原生 SIGSEGV，Java 层抓不到** | 只许在 `offset {}` / `graphicsLayer {}` 这类布局/绘制阶段 lambda 里读 |
| **玻璃透镜恒为叶子节点** | 自采样会成环 → RenderThread SIGSEGV | 一块层只有一个写入者，且不采样自己写的层 |
| **效果档位只有一个权威（`EffectTier` / `LocalEffectTier`）** | 曾经同时存在三条档位来源（设置面板改 `EffectTier`、侧栏透镜读 `SDK_INT`、`DeskGlass` 层根本不读） | 症状："在设置里切高/中/低/关，画面一分不变"。改档位门槛只改 `core/platform/EffectTier.kt` 一处 |
| **侧栏"容量"判据是纯图标下限 54dp，不是触控下限 64dp** | `fitSidebar` 里条目下限曾被夹在 64，而"只留图标"的阈值是 54 → `ICON_ONLY` 恒不触发，是死代码 | 判据是 64 会让矮屏**直接砍入口**、且 361dp 屏要么吞掉标签要么触发不了降级。现在：≥64 显示文字 → 54~64 只留图标 → <54 才减入口 |

---

## 5. 反直觉事实（不知道就会给出错误建议）

| 事实 | 后果 |
| --- | --- |
| **地图（SurfaceView）与 WebView 都是独立图层**，Compose 玻璃层采样不到 | **"给地图/网页内容加真高斯模糊"是死路**，换 `TextureMapView` 也没用。只能遮罩 + 提对比度 |
| **`adb logcat` 在这台开发机恒为空** | 日志落盘 `files/map/map.log`，读法 `adb exec-out run-as com.qingke.desk cat files/map/map.log`。**日志有轮转**，关键取证立刻存文件 |
| **`ps -A \| grep -c` 本机不可靠** | 存活判据用 `pidof com.qingke.desk`；崩溃看 tombstone **数量**是否增长 |
| **`SurfaceView` 有独立 buffer**，GL 视口按建 surface 那次尺寸分配 | View 变大不跟随（单向下黑：视口比新容器宽时被裁掉看不出来）。`onPause()` 只停渲染线程、**不销毁 surface**，所以"重挂时补一次 pause"是在赌时序（实测 6 次只中 1 次） |
| **uiautomator dump 在导航页必失败**（SurfaceView 一直在动） | 导航页只能靠像素量 |
| **小米上注入触摸要 root**：`su -c "input tap X Y"`；**一加直接可用** | 但 root 通道**偶发 ~20s 延迟投递**，看起来像"界面自己跳了页" |
| **一加 WebView 是 Chrome 156，小米是 Chrome 90** | 任何"浏览器支不支持 X"的结论必须注明是哪台机器 |

---

## 6. 验证与取证方法（本机通用，复用价值最高）

- 坐标一律先用 `uiautomator dump` 标定，**不要目测截图**（截图预览被压缩，要按比例换算）。
- 定位自绘图形用**开/关两帧差分**，比调颜色阈值稳。
- 图形看不懂就把 mask 渲成 ASCII 打印。
- 量"覆盖物在不在"：沿**路线中心线取一列**像素输出颜色游程，别用白色连通域全卡扫描。
- 判"手势有没有漏进地图"：算像素 MAD，但**先建噪声底**（连拍两张未触摸帧）。
- 判"有没有重新加载"：数日志里的 `map loaded`；`MapView attached` 每次组合都打，**不能当计数**。
- 判"遮罩有没有误伤别处"：用**亮度比值**不是绝对值。
- **`adb` 在 `C:/platform-tools/platform-tools/adb.exe`**（不在 Android/Sdk）。
  传 `/sdcard/...` 要 `MSYS_NO_PATHCONV=1`，但 `pull` 的目标必须是 Windows 形式 `D:/...`。

---

## 7. 当前状态

- 分支 `master`，最新提交 `12f6216`（地图跨尺寸重挂后右侧纯黑 —— 改用 TextureMapView）。
- 有个废弃分支 `wip/mapview-resize`（试修已被 `12f6216` 取代）。
- **未提交**（`git status` 实测）：本次 UI/自适应缺陷修复涉及的 `:core:ui` 与 `:shell` 源文件
  （`AdaptiveLayout.kt`、`DeskDimens.kt`、`DeskGlass.kt`、`DeskRoot.kt`、`LensSideBar.kt`、
  `SideBar.kt`、`SettingsPane.kt`、`NaviSearchLayer.kt` 及 `shell/src/debug/` 两处）；
  `shell/src/debug/` 下的 folia WebView 预览 Activity 与 debug 资源（实验用，仅 debug 变体）；
  根目录 `AI-HANDOFF.md` / `PROJECT-INTRO.md` / `gemini_prompt.md` 尚未 `git add`。
- 已完成的近期工作：自适应框架、侧栏玻璃透镜、导航会话进程化、TextureMapView 修复、
  **效果四档真正打通到玻璃层与侧栏透镜**、**侧栏三级降级（361 dp 屏保住文字标签）**、
  设置浮层与导航搜索层改用容器实测约束宽高。

---

## 8. 当前在做的事：加"音乐"能力（请优先看这一节）

**需求**：横屏全屏歌词舞台（渐变底 + 音频涟漪 + 逐字高亮歌词），并能登录在线音乐平台。

**现状**：`feature/media` 只能**显示别人在放什么**，没有播放器、没有登录、没有歌词。
`gradle/libs.versions.toml:52` 已预留 `# media3-exoplayer（:feature:music）`（坑位已开）。

**已完成的评估**（结论，别重做）：

| 方案 | 结论 |
| --- | --- |
| 引入开源 Web 播放器 **folia**（AGPL-3.0，30 万行）走 WebView/PWA | 只做 1 个平台时**不划算**：原生 UI 只值 ~700 行，却要背 AGPL 传染 + 实测 CPU 71.4%（常温 38→52℃）+ 19 MiB 资产 + 进不了玻璃体系 |
| folia 的"多平台登录" | folia **已内建 4 家**（QQ / 网易云 / 酷狗 / 波点）——所以"多平台"**不构成选 folia 的理由** |
| folia 是否零后端 | **不是**：需 `VITE_QQ_API_BASE` / `VITE_NETEASE_API_BASE` / `VITE_KUGOU_API_BASE` 三个后端 |
| PWA / TWA 路线 | **TWA 只能加载公网 https**，离线出局；自编译 Chromium 不现实；唯一现实的"自带内核"是 GeckoView（但要换渲染引擎） |
| 浏览器能否直连音乐平台接口 | **不能**：`u.y.qq.com` 无任何 CORS 头（实测），必须有浏览器之外的转发层。**但 QQ 的 MQTT 登录走标准 WSS 且不校验 Origin（实测）→ 登录可浏览器直连** |
| WebView 里的 MediaSession | **Android WebView 全版本不支持**（MDN / chromium bug 40611412），换内核也救不了 → 必须原生桥 |

**当前判断**：先做 **QQ 一家 + 图上一屏**（约 2,500 行）。QQ 协议是四家里唯一已被本项目实测跑通的（登录成功、CDN 出声、`currentTime` 单调推进）。**酷狗先别碰**——folia 自己都没有它的协议包（靠外部服务）。

**动手前必须先证伪的一项**：`android.media.audiofx.Visualizer` 在目标机上能否拿到 FFT（涟漪的数据源；需 `MODIFY_AUDIO_SETTINGS`，部分 ROM / 蓝牙路径恒为 0）。为 0 就换 Media3 Analyser。

---

## 9. 请不要建议这些（已被评估并否决）

1. **把 folia 集成进来当播放器** —— AGPL 传染 + 性能 + 视觉三件套（§8）
2. **给地图或 WebView 内容加真高斯模糊** —— 独立图层，采样不到（§5）
3. **把尺寸写成固定 dp** —— 两种屏幕会现形（§4）
4. **用 TWA 承载 Web UI** —— 只能公网 https，离线不行
5. **代用户点授权弹窗** —— 做不到（§4）

---

## 10. 建议分析顺序

1. `README.md` 第九、十节（当前状态 + 自适应框架）
2. `core/ui/AdaptiveLayout.kt`、`core/platform/DeviceProfile.kt`（尺寸与能力怎么来）
3. `shell/DeskRoot.kt` → `shell/HomeScreen.kt`（界面骨架）
4. 按你要分析的具体模块进入（见 §2 的入口文件）
5. `docs/` 下有按日期的方案与验证记录，`docs/known-issues/` 是已知问题台账
