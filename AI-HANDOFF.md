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
        15 个 Gradle 模块；产品代码 87 个 Kotlin 文件 / 约 21,700 行
        （另有 debug-only 探针 8 文件 / 约 2,000 行，不进正式构建）。非 root。许可证 GPL-3.0。

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
现状：路线 D（跟随系统媒体会话 + LRCLIB 歌词）**已真机可用**；
      路线 B（原生自研播放器）Phase 0 五条断言**已真机全过**，但产物只是 debug 探针、未合入 master。
已否决：不要建议引入开源 Web 播放器 folia（AGPL-3.0，30 万行）走 WebView/PWA ——
        已按实测否决（播放中单核 CPU 71.4% vs 原生 5.4%），详见 §8 / §9。
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
| `:feature:media` | 读别的播放器的 MediaSession → 首页卡片 **+ 全屏音乐页（路线 D）** | ~4.3k 行 | `media/MediaSessionMonitor.kt`、`media/MediaCard.kt`、`media/music/MusicPage.kt`、`media/music/PlaybackSource.kt` |
| `:map` | 高德合包：底图 + 定位 + 搜索 + 导航 | ~4.8k 行 | `map/.../navi/NaviSession.kt`、`map/.../MapViewHost.kt` |
| `:hosting` | 借 Shizuku 建虚拟屏，托管无接口的第三方 App | ~2.3k 行 | |
| `:projection:{carplay,auto}` | 投屏协议 | ~1k 行 | |
| `:connection` | 连接管理 | ~460 行 | |

**注意 `:feature:media` 的性质**：它整体是**观察者**，用 `MediaSessionManager.getActiveSessions` +
`NotificationListenerService` 读"别的 App 正在放什么"。**它自己不播放、不登录**。
全屏音乐页（`media/music/`）是这套观察能力的展示层，歌词走 LRCLIB。播放实现通过
`PlaybackSource` 抽象注入 —— 路线 B（原生播放器）落地时是新增实现，不是改页面。

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
6. **音乐页与播放实现是分开的**：音乐页只跟 `PlaybackSource` 这个抽象说话，现在挂的是
   `MediaSessionPlaybackSource`（跟随别的 App 的会话位置）。路线 B 落地时新增一个原生播放实现，
   **音乐页不需要改**。不要把"读别人的会话"当成临时代码删掉 —— 它是产品要的一个真实能力
   （用户在用别的 App 放歌时，桌面也该显示歌词）。

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
| **`dumpsys audio` 的事件日志是"音频真的在出声"唯一可信的判据** | AudioService 用 PlayerBase 记账（`new player piid:N uid/pid:U/P type:android.media.AudioTrack attr:usage=USAGE_MEDIA` + `state:started/paused/stopped`），**不受音量影响，应用层伪造不了**。⚠️ 别拿 `dumpsys media.audio_flinger` 的 `FrmCnt` 当"播了多少"——它**不是累计呈现帧数，而是轨道缓冲帧数**（实测 960 / 22050 / 99944 = 48k×0.02s / 44.1k×0.5s / 192k×0.52s）；真正的累计量是 **`Underruns`**。另外轨道行有**两种形状**（有没有 `Type` 列），按写死的列偏移解析会静默漏检本进程轨道 |
| **targetSdk 36 下 http 明文被系统直接拒**（`Cleartext HTTP traffic ... not permitted`） | 但同一 CDN 域名的 **https 全部可用**（同一个 `purl` 在 `ws/dl/isure/sjy6.stream.qqmusic.qq.com` 上全部 206）→ **不需要开 `usesCleartextTraffic`**。看到"http 拉不到"先试 https，不要先改清单 |
| **`am start` 对 `singleInstance` Activity 的第二次调用会被系统吞掉** | 日志是 `intent has been delivered to currently running top-most instance`。探针要原地重跑必须实现 `onNewIntent` + `recreate()`——否则你以为在重跑，其实跑的还是上一个实例 |

---

## 6. 验证与取证方法（本机通用，复用价值最高）

- 坐标一律先用 `uiautomator dump` 标定，**不要目测截图**（截图预览被压缩，要按比例换算）。
- 定位自绘图形用**开/关两帧差分**，比调颜色阈值稳。
- 图形看不懂就把 mask 渲成 ASCII 打印。
- 量"覆盖物在不在"：沿**路线中心线取一列**像素输出颜色游程，别用白色连通域全卡扫描。
- 判"手势有没有漏进地图"：算像素 MAD，但**先建噪声底**（连拍两张未触摸帧）。
- 判"有没有重新加载"：数日志里的 `map loaded`；`MapView attached` 每次组合都打，**不能当计数**。
- 判"遮罩有没有误伤别处"：用**亮度比值**不是绝对值。
- 判"**音频真的在出声**"：`adb shell dumpsys audio` 里数 PlayerBase 事件的持续时间，再和探针自己的
  `maxsec` 对表（实测 210.3s vs `maxsec=210`，差 0.3s）。**这个判据与音量无关**——静音播放照样成立。
- 判"**播放位置有没有回退**"：**必须显式排除循环回绕**。单曲循环时 `currentPosition` 会
  `269xxx → ~0`，只比"新位置 < 上一位置"会把 7 次正常回绕全判成 bug。正解是三条**同时**成立：
  `REPEAT_MODE_ONE` + 上一位置 ≥95% 曲长 + 新位置 <2000ms。
- 判"**下载到的是不是完整曲目**"：比 CDN 响应的 `Content-Range` 总量与接口声明的字节数，
  **逐字节相等**才算（实测 6/6 相等，含 flac 55,397,039）。**不要用"能打开就算对"**——
  那证伪不了任何东西（0.92MB 的试听片段照样能打开）。
- 改动过判据的结论要在**同一份原始数据**上跑一遍**独立实现**复核（本次是离线 Python 脚本），
  有争议再真机重跑；两套实现结论一致才写进报告。
- **`adb` 在 `C:/platform-tools/platform-tools/adb.exe`**（不在 Android/Sdk）。
  **MSYS 路径转换是双向坑**：不加 `MSYS_NO_PATHCONV=1`，设备路径 `/data/local/tmp/x` 会被改写成
  `C:\...`；加了之后 `/d/...` 又不再被转换（被 Windows 程序当成 `D:\d\...`）。
  给外部 exe 传路径一律写 `D:/...` 正斜杠 Windows 形式。
- 长跑功耗测试拔线后要走 `adb tcpip`；**`adb usb` 在拔线状态下会让设备直接失联**（`device offline`），
  下命令前先确认线是插着的。
- MIUI 上跑长测前把应用加进 `deviceidle whitelist`，否则**中途杀进程会表现成"播放中断"，是假阴性**。
  测完记得移除。

---

## 7. 当前状态（2026-10-14 实测）

- 当前分支 **`spike/music-phase0-qq`**，HEAD `edc9c36`（Phase 0 第二步：原生取链接 + ExoPlayer 真机取证）。
- **`master` 未被本分支触碰** —— 按约定，音乐 spike 的**代码严禁合入 master**。
  master 上音乐相关的最新提交是 `b55b23f`（路线 D 音乐页）；此外还有若干 `docs:` 提交**只动文档**
  （含 Phase 0 两步的决策留档，那是决策记录，不是代码）。
- `master` 近期提交链：`b55b23f`（路线 D 音乐页）← `ce3a75e`（阶段一前置材料）←
  `3d583ba`（UI 自适应修复 + 效果档位打通到玻璃/透镜）← `12f6216`（改用 TextureMapView 修跨尺寸纯黑）。
- 其他分支：`archive/folia-webview-probe`（folia WebView 探针留档）、
  `wip/mapview-resize`（试修已被 `12f6216` 取代，**废弃**）。
- `.verify/` **未入库**（验证脚本、探针日志、`dumpsys` 快照、反编译源码副本都在本地）；
  `tools/lyrics-probe/` 入库（LRCLIB 命中率实测脚本 + 结果）。
  `gemini_prompt.md` 仍未 `git add`。

**接手这条音乐线要注意**：Phase 0 的产物分布在
`app/src/debug/java/com/qingke/desk/phase0/Phase0ProbeActivity.kt` 与
`app/src/debug/AndroidManifest.xml` —— 它们是**探针**，只在 debug 变体（已验 release 清单 0 命中），
**不是产品代码**。要往下走先把 `docs/2026-10-14-music-phase0-native-playback.md` 读完，
里面记了全部实测字段与踩坑。

---

## 8. 当前在做的事：加"音乐"能力（请优先看这一节）

**需求**：横屏全屏歌词舞台（渐变底 + 音频涟漪 + 逐字高亮歌词），并能登录在线音乐平台。

**现状**：路线 **D** 已落地并合入 `master` —— 音乐页是**观察者**，跟随系统媒体会话显示曲目 +
LRCLIB 歌词（`PlaybackSource` 是抽象缝，未来换原生播放实现时页面不用改）。
路线 **B**（自己会播 + 能登录）在 `spike/music-phase0-qq` 上做完 Phase 0 验证，**产品代码尚未开始**。
`gradle/libs.versions.toml` 已加 `media3 = "1.11.1"`（**只在 Google Maven，不在 Central**，
仓库声明里必须有 `google()`），并以 `debugImplementation` 挂在 `:app` 上供探针使用。

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

### 路线 B 的 Phase 0 已完成（小米 11 Ultra / API 30，2026-10-14）—— 五条断言全部成立

| # | 断言 | 结论 | 关键证据 |
| --- | --- | --- | --- |
| ① | `GetVkey` 拿到非空 `purl` | ✅ | 6/6 非空，`midurlinfo.result=0` |
| ② | 拼出的 URL 能被 ExoPlayer 打开 | ✅ | CDN `Content-Range` 总量与声明 `size128` **逐字节相等**（6/6，含 flac 55,397,039） |
| ③ | **实际出声**且位置单调 | ✅ | AudioTrack `state:started` 持续 210.3s；本进程轨道 `Underruns` 全 0；1900 次采样真回退 0 次 |
| ④ | 3 分钟单核 CPU < 20% | ✅ | 3 分钟窗口 4.2~9.7%；31 分钟窗口均 5.40%、峰 6.6% |
| ⑤ | 换不同 songmid 复现 | ✅ | 6 首（含 2 首免费歌、1 个 flac 档）全复现 |

**顺带查实的协议事实（写产品代码前必读，全部来自实测，不是推测）：**

- **`musicu.fcg` 这条路径不需要签名**：`callMusicu` 的请求体就只有
  `{comm, req_0:{module,method,param}}` + `Cookie` 头 + `User-Agent`。协议包里那套
  `md5×6 / rsa×5 / aes×6` **不在取链接这条路上** —— 原生实现用 `HttpURLConnection` 就够，
  **不必引入 OkHttp**。
- **`UrlGetVkey` 返回的 `sip` 恒为空数组**（实测 6/6）→ 必须自备兜底域名，不能直接信这个字段。
- **https CDN 可用**：同一个 `purl` 在 `ws / dl / isure / sjy6.stream.qqmusic.qq.com` 的 https
  上全部 206 → **不需要开明文流量**（见 §5 那条）。
- **服务端会把 `purl` 的文件名规范化**：请求送 `M500+songmid+media_mid+.mp3`（36 字符），
  返回 `M500+media_mid+.mp3`（22 字符）—— **songmid 段被丢弃**，这不是异常。
- **"完整曲目"的诚实判据**：`Content-Range` 总量 == 搜索响应里的 `size_128mp3`（逐字节）。
  试听片段 `preview.trysize` 约 0.92 MB，可当对照尺。
- **会话有寿命**：本次 `expiresAt` 约 **+2 天**、`musickey` 约 **+59 天**。Phase 1（QR 登录 + MQTT 保活）
  要么赶在过期前做，要么准备重新扫码 —— 过期后上面这些结论都要重取凭证再验一遍。

**合规红线（不许越过）**：这些是 QQ 音乐的非官方接口，自用不触发，**分发 / 商业化需单独评估**。
本次验证的停止条件是 ——「**合法凭证下依然拿不到会员歌曲，立刻停止，绝不尝试任何绕过付费或 DRM 限制的手段**」，
**该条件未触发**。这条红线在后续任何阶段都有效。

**Phase 0 明确没做的**：只验了小米 API 30 一台机（一加 API 36 未跑）；只验了 128k / flac 的
"取链接 + 字节数"，**没放 320k/flac 的声音**；探针是**前台 Activity**（后台 / 息屏 / 被回收未验）；
无 MediaSession 与音频焦点在导航场景下的共存验证（属 Phase 3）；无"零调试连接"的内存对照。

**动手前必须先证伪的一项**：`android.media.audiofx.Visualizer` 在目标机上能否拿到 FFT（涟漪的数据源；需 `MODIFY_AUDIO_SETTINGS`，部分 ROM / 蓝牙路径恒为 0）。为 0 就换 Media3 Analyser。

---

## 9. 请不要建议这些（已被评估并否决）

方案级：

1. **把 folia 集成进来当播放器** —— AGPL 传染 + 性能 + 视觉三件套。实测同机对比：
   播放中单核 CPU **71.4% → 原生 5.4%**、30 分钟内存 **+178 MB 无平台期 → 无爬升**、
   30 分钟耗电 **−35%（且全程 USB 供电）→ −14%（不插线）**、电池 **38→52 ℃ → 39→40 ℃**（§8）
2. **给地图或 WebView 内容加真高斯模糊** —— 独立图层，采样不到（§5）
3. **把尺寸写成固定 dp** —— 两种屏幕会现形（§4）
4. **用 TWA 承载 Web UI** —— 只能公网 https，离线不行
5. **代用户点授权弹窗** —— 做不到（§4）
6. **让 WebView 承担播放与媒体控制** —— Android WebView **全版本不支持 MediaSession**
   （chromium bug 40611412），换内核也救不了，必须原生桥
7. **在 WebView 里直连音乐平台接口** —— `u.y.qq.com` 无任何 CORS 头（curl 实测）。
   ⚠️ 但有个例外别一起否掉：**QQ 登录用的 MQTT 走标准 WSS 且不校验 Origin**（手写握手实测 101），
   所以**登录那一步**是可以浏览器直连的

做法级（这些判据本身是错的，用它们会得出假结论）：

8. **拿 `dumpsys media.audio_flinger` 的 `FrmCnt` 当"累计播放量"** —— 它是**轨道缓冲帧数**，
   不是累计呈现帧数。要累计量用 `Underruns`；要"是否真的在响"用 `dumpsys audio` 的 PlayerBase（§5）
9. **通过开 `usesCleartextTraffic` 解决"http 拉不到音频"** —— 同一 CDN 域名的 https 全部可用，
   正确做法是换 https，不是松明文限制（§5）
10. **用"能打开 / 返回 200"证明"拿到了完整曲目"** —— 0.92 MB 的试听片段照样能打开，
    这个判据**证伪不了任何东西**。要比 `Content-Range` 总量与接口声明的字节数，逐字节相等（§6）
11. **只比 `currentPosition` 与上一次的大小来判断"位置有没有回退"** —— 单曲循环时
    `269xxx → ~0` 是**正常回绕**，这么比会把 7 次正常回绕全判成 bug（§6）

---

## 10. 建议分析顺序

1. `README.md` 第九、十节（当前状态 + 自适应框架）
2. `core/ui/AdaptiveLayout.kt`、`core/platform/DeviceProfile.kt`（尺寸与能力怎么来）
3. `shell/DeskRoot.kt` → `shell/HomeScreen.kt`（界面骨架）
4. 按你要分析的具体模块进入（见 §2 的入口文件）
5. `docs/` 下有按日期的方案与验证记录，`docs/known-issues/` 是已知问题台账
6. 要碰音乐这条线，先读 §8，再按顺序读 `docs/2026-10-14-music-phase1-prep.md`（路线 D 的前置材料）→
   `docs/2026-10-14-music-phase0-qq-vip-check.md` → `docs/2026-10-14-music-phase0-native-playback.md`
   （Phase 0 两步的完整取证；协议字段与踩坑都在最后一份里）
