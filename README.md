# DeskTerminal 智能桌面终端

把一台闲置的 Android 手机变成长时间摆在桌上的横屏终端：一屏里看到时间、待办、天气、音乐、地图，
需要时还能把手机画面投到车机、或把第三方应用托管进本应用显示。

Kotlin + Jetpack Compose，多模块（`:app` 是唯一的 app 模块），**非 root**，GPL-3.0。

**不按某一台机器写死尺寸**：屏幕尺寸、刷新率、挖孔、效果能力全部在运行时读
（`core:platform/DeviceProfile.kt`），效果按设备能力分四档并可在运行中自动降级。
换机 / 不同屏幕该看什么，见 **第十节**。

开发期间用两台机器交叉验证：OnePlus PKR110（Android 16，横屏 **794 × 361 dp**）与
小米 11 Ultra M2102K1C（Android 11 / API 30，横屏 **914 × 411 dp**，`wm size`/`wm density`
模拟过的尺寸见第十节实测表）。两台都不是"基准"，只是两个样本。

---

## 一、用到的第三方 SDK 与在线服务

| SDK / 服务 | 坐标 | 用途 | 数据去向 |
|---|---|---|---|
| 高德**合包**（3D 地图 + 定位 + 搜索 + 导航） | `com.amap.api:navi-3dmap-location-search:11.3.100_3dmap11.3.100_loc11.3.000_sea9.8.1` | 底图、定位小蓝点、目的地搜索、**算路与导航引导** | **回传高德服务器** |
| Open-Meteo（天气 API，**非 SDK**） | `https://api.open-meteo.com/v1/forecast`（`HttpURLConnection` 直连） | 天气卡的温度/湿度，以及地图昼夜切换用的日出日落 | **出一个 HTTPS GET** |
| Shizuku | `dev.rikka.shizuku:{api,provider}:13.1.5` | 借 adb/root 身份创建虚拟屏，托管无接口的第三方应用 | **不出设备** |
| 通知使用权（系统能力，非 SDK） | `NotificationListenerService` | 读取播放器的 MediaSession，驱动首页音乐卡 | **不出设备** |
| Backdrop（玻璃效果） | `io.github.kyant0:backdrop:2.0.0` | 侧栏与卡片的液态玻璃材质 | 纯本地渲染 |
| Jetpack Compose / Material 3 | 见 `gradle/libs.versions.toml` | 全部界面 | 纯本地渲染 |

只有前两行会让数据离开设备，其余全部在本机。

---

## 二、高德合包（地图 + 导航）

**为什么是一个包**：导航开发包自带 3D 地图 SDK，与独立的 `3dmap-location-search` 并存会
duplicate class（两个包都带 `com.amap.api.maps.**`）。所以坐标是 `navi-3dmap-location-search`
这一个合包，地图、定位、搜索、导航**共用同一把 Key、同一份隐私声明、同一个接收方**。

**为什么需要**：桌面终端抬眼就要看到自己在哪、周边什么样，还需要在首页卡里直接起导航。
Google 地图在国内不可用，高德是可行的厂商方案。

**数据发往哪里**：北京高德图强科技有限公司。SDK 在本机采集并回传其服务器，官方列明的范围是：

- 经纬度、搜索词
- 设备信息：IP 地址、GNSS 信息、网络类型、WiFi 状态 / 参数 / 列表 / 信号强度 / 网关地址 / SSID / BSSID、
  基站信息、传感器信息（矢量、加速度、压力、方向、地磁、陀螺、温度、光敏、NEMA 信号）
- OAID、当前应用信息（应用名、版本号）、设备参数与系统信息（品牌型号、操作系统、运营商、屏幕分辨率）

导航打开后**额外**会产生这几类（仍是同一个接收方）：

- **算路请求**：起点坐标、终点坐标（含名称与 POI ID）、算路策略。没有它算不出路线。
- **导航过程中的连续定位**：引导需要知道车走到哪了，这是导航期间位置上报最频繁的一路。
- **语音播报不出设备**：用 SDK 内置 TTS（`AMapNavi.setUseInnerVoice(true)`），
  语音包是打进 APK 的离线资源，**本机合成、不做云端合成，也不需要录音权限**。

官方隐私政策：<https://lbs.amap.com/pages/privacy/>

**如何关闭**

1. **从未同意**：地图屏只显示隐私说明卡，**在用户点"同意"之前不会构造 `MapView`、
   也不会取 `AMapNavi` 实例**，SDK 不发生任何采集（这既是隐私要求，也是高德官方的合规要求 ——
   不先声明就构造，地图会白屏、算路回调永远不来）。
2. **同意后想关**：设置浮层（右上角齿轮）→「地图隐私」→「撤回同意」。撤回后地图立刻停用，
   下次进入地图屏重新回到说明卡。**地图与导航两套闸门在同一个写入点里一起开、一起关**
   （`AMapPrivacy.push` / `revoke` 同时调 `MapsInitializer` 与 `NaviSetting`），
   不存在"只撤回一半"。实现见 `:map/AMapPrivacy.kt`。
3. **只想去掉定位**：系统设置 → 应用 → 本应用 → 权限 → 位置 → 拒绝。
   地图仍然可以显示，只是不画自己的位置（导航则无法起，起点拿不到）。

**正在导航时撤回**会把 SDK 的合规状态置回未同意，但**不会替用户结束当前这趟导航** ——
结束导航只能由用户在界面上点「结束」并二次确认（见 `NaviSession.requestExit`）。
要立刻停掉，先结束导航再撤回。

**Key 怎么配**：Key 不入库。在仓库根目录的 `local.properties` 里写一行：

```properties
amap.api.key=你的Key
```

构建时经 `manifestPlaceholders` 注入 `AndroidManifest.xml` 的 `com.amap.api.v2.apikey`，
同时导出一份到 `:map` 的 `BuildConfig`（用来在界面上提示"Key 没配"）。
申请方式见 `map/build.gradle.kts` 顶部注释（需要 Package name + 调试/发布两个 SHA1）。

**为此声明的权限**（`:map/src/main/AndroidManifest.xml`）：
`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE`、
`ACCESS_COARSE_LOCATION`、`ACCESS_FINE_LOCATION`、`ACCESS_LOCATION_EXTRA_COMMANDS`。

> 注意 `CHANGE_WIFI_STATE`：这是**高德 SDK 文档要求声明的**（网络定位用）。
> 本应用自身从不改变 Wi-Fi 开关 —— 见下面"本应用明确不做的事"。

导航**没有**再要任何权限，它复用上面这一组。两项**刻意不声明**：

- `ACCESS_BACKGROUND_LOCATION`（后台定位）—— 它意味着"息屏、切到别的 app 之后仍然持续定位"。
  桌面终端摆着不动，导航在**前台可见**时才有意义，不需要这个口径。
- `RECORD_AUDIO`（录音）—— 语音播报是离线 TTS 合成的，不收音；
  声明它等于给一个桌面终端装上常开麦克风。

因此**长时间后台（例如投屏时息屏跑导航）会被系统降频**，这是已知取舍，不是遗漏。

---

## 三、Shizuku

**为什么需要**：有几个目标应用（例如高德车机版）没有对外接口，只能把它放进一块自建虚拟屏里
再把画面画进本应用。创建虚拟屏需要系统级权限，非 root 环境下唯一的合法途径是 Shizuku。

**数据发往哪里**：**不出设备**。Shizuku 只是一个本机进程间通信的桥（把 adb 或 root 的身份
通过 binder 交给本应用），它本身没有网络行为，本应用也没有把任何东西发给它之外的服务器。

**如何关闭**

- 在 Shizuku 应用里「停止服务」；或在其授权列表里撤销本应用。
- 停掉之后 `:hosting`（虚拟屏托管）相关的功能不可用，**其余功能不受影响**。
- 本应用每次回到前台会重新查一次状态，右上角只在异常时显示一个角标，点它可进入引导页。

**相关组件**：`rikka.shizuku.ShizukuProvider`（authorities 为 `${applicationId}.shizuku`，
token 传递通道，缺了它 `Shizuku.pingBinder()` 永远返回 false）、
前台服务 `VdHostService`（`specialUse` 类型，虚拟屏的持有者）。

---

## 四、通知使用权

**为什么需要**：要画首页那张音乐卡、以及全屏音乐页，就得知道别的播放器在放什么。
Android 上读取第三方 `MediaSession` 的**唯一公开途径**是：
调用方必须是一个"已启用"的 `NotificationListenerService`
（另一条路 `MEDIA_CONTENT_CONTROL` 是 signature 级权限，第三方永远拿不到）。

**数据发往哪里**：**不出设备**。授权后系统会把这台设备的**全部通知**推给本 Service，
但本 Service **没有实现 `onNotificationPosted`** —— 它只作为"已启用"的凭证存在，
真正取曲目信息走 `MediaSessionManager`，且只在内存里用于画卡片，不落盘、不上传。

**如何关闭**

- 系统设置 → 通知 → 通知使用权（部分 ROM 在「特殊应用权限」里）→ 关掉本应用。
- 应用内也已提供直达入口：音乐卡处于"需要授权"状态时点它会跳到该设置页。
- 关掉后音乐卡显示"需要授权/暂无播放"，不影响其它功能。
- 这个开关**只能由用户手动打开**，应用无法自助申请，代码里也没有任何绕过的尝试。

**相关组件**：`MediaNotificationListener`（`BIND_NOTIFICATION_LISTENER_SERVICE`）。

---

## 五、权限逐条说明

权限按**声明它的模块**分组（最终合并进同一个 APK；安装时看到的是合并结果）。

### `:app`（壳层与投屏）

| 权限 | 为什么需要 | 如何关闭 |
|---|---|---|
| `INTERNET` | 高德瓦片与算路、天气 API、将来的投屏发现 | 卸载或断网 |
| `ACCESS_NETWORK_STATE` | 判断当前网络类型（地图网络定位、投屏链路前置检查） | — |
| `ACCESS_WIFI_STATE` | **只读** Wi-Fi 状态：投屏要确认手机连在自己的节点上 | — |
| `NEARBY_WIFI_DEVICES` | Android 13+ 扫描附近设备，用于投屏发现 | 系统设置里拒绝 |
| `BLUETOOTH` / `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` | 投屏的蓝牙引导链路 | 系统设置里关闭蓝牙权限 |
| `WAKE_LOCK` | 屏幕常亮期间不被系统挂起 | — |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 引导用户把本应用加入电池白名单，避免被系统杀掉 | 系统设置里撤销 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 投屏连接期间的前台服务 | 不使用投屏即可 |
| `POST_NOTIFICATIONS` | 投屏连接状态的通知 | 系统设置里关闭通知 |
| `READ_MEDIA_AUDIO` | 本地曲库（Android 13+）。**当前音乐卡的内容全部来自 MediaSession，还没有读本地文件** —— 这条是为下一步的本地播放预留的，不用就直接在系统设置里拒绝 | 系统设置里拒绝 |

### `:map`（高德合包）

`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE`、
`ACCESS_COARSE_LOCATION`、`ACCESS_FINE_LOCATION`、`ACCESS_LOCATION_EXTRA_COMMANDS`，
以及定位 SDK 要求的常驻服务 `com.amap.api.location.APSService`
（缺了它只有地图能显示、定位永远拿不到结果）。逐条理由见第二节。

### `:hosting`（虚拟屏托管）

`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE`、前台服务 `VdHostService`，
以及 Shizuku 的 token 通道 `rikka.shizuku.ShizukuProvider`
（authorities 为 `${applicationId}.shizuku`，缺了它 `Shizuku.pingBinder()` 永远返回 false）。

### 刻意**没有**声明的

- `ACCESS_BACKGROUND_LOCATION`（后台定位）与 `RECORD_AUDIO`（录音）—— 理由见第二节。
- `QUERY_ALL_PACKAGES`。只用 `<queries>` 放行三个包：`com.shihab.diplay`（`:app`）、
  `com.autonavi.minimap` 与 `moe.shizuku.privileged.api`（`:hosting`）。
  targetSdk ≥ 30 上不声明就查不到这些包，会把"已安装"误判成"未安装"；
  但**只放行需要的那几个**，不申请全量包可见性。

运行时权限（位置、通知、蓝牙、媒体）**声明不等于拿到**，仍要在使用前由用户授予；
被拒绝的功能各自降级 —— 地图能看但不画自己的位置、音乐卡显示"需要授权" —— 不会连锁影响其它功能。

---

## 六、数据都放在哪、发往哪

### 存在设备上的

全部是 `MODE_PRIVATE` 的 SharedPreferences，**没有数据库、没有账号、没有自建后端**。

| 文件 | 键 | 内容 |
|---|---|---|
| `desk_shell` | `sidebar_side` | 侧栏在左还是右 |
| | `keep_screen_on` | 屏幕常亮开关（默认开） |
| | `last_lat` / `last_lon` | 上一次定位到的位置（Float，约 1 m 精度），只用来给地图卡一个像样的初始视角 |
| | `home_*` / `work_*`（各 4 个键） | **用户自己设的**家与公司：标题 + 坐标 + 地址 |
| `desk_amap_privacy` | `agreed` / `agreed_at` | 是否同意过高德隐私政策、同意时间 |
| `desk_shizuku_boot` | `last_boot_ms` / `prompted_boot_ms` | 上次开机时间与"这次开机已提示过"，免得每次开机都弹一遍引导 |
| `desk_shizuku_debug` | `forced_status` | 验收用的 Shizuku 状态强制覆盖。release 构建里是**完全的空操作**，连这个文件都不会被创建 |

**刻意不存的**：

- **不存 POI ID**。家/公司只存标题 + 坐标 + 地址 —— ID 是服务端给的，换一把 Key 或升一版 SDK
  就可能查不回来，而坐标是过了今天还能用的东西。
- **不存轨迹与导航历史**。导航过程的数据只在内存（`NaviSession` 的 StateFlow），退出导航即丢；
  除上面那两个用户显式设下的地点外，没有"去过哪"的记录。
- **不存音乐与通知内容**。只在内存里用于画卡片，不落盘、不上传。

### 会离开设备的

1. **高德 SDK**：位置、搜索词、算路的起终点、设备信息 —— 详见第二节。
2. **Open-Meteo 天气**：每 10 分钟一个 HTTPS GET。请求里带的是**写死的西安坐标**
   （`XIAN_LATITUDE` / `XIAN_LONGITUDE`），**不拿设备位置去换天气**；
   没有账号、没有 Key、没有设备标识，服务端能看到的是设备公网 IP。
   数据用于天气卡，以及喂给 `Daylight` 决定地图的昼/夜底色。
   数据 CC BY 4.0，署名写在天气卡脚注里。
3. **投屏**：目前是占位实现（`MockProjectionSession` / `AndroidAutoPlaceholder`），
   **没有实际对外连接**；真接入后只走同一局域网。

### 诊断日志（仅 debug 构建）

这台基准机的 `adb logcat` 恒为空，所以 `:map` 的 `MapLog`、`:hosting` 的 `HostLog`
把日志落到文件里（`BuildConfig.DEBUG` 为 false 时整段被剔除，**release 不落盘**）：

```bash
# 首选：内部目录（/sdcard 那边属主是 u0_aXXX:ext_data_rw，shell 不在组里，pull 会被拒）
adb exec-out run-as com.qingke.desk cat files/map/map.log
adb exec-out run-as com.qingke.desk cat files/hosting/hosting.log
```

单文件 512 KB，写满即**从第一行重新写**（轮转），不做归档、不上传。
**日志里会出现经纬度**（初始相机、定位首帧、导航引导），取证时按这个前提看待：
它只存在于 debug 包，清除应用数据或卸载即随之消失。

### 想一次性清干净

设置 → 应用 → 本应用 → **清除数据**：侧栏位置、常亮开关、家/公司地址、
高德隐私同意状态一起清掉（卸载同理）。注意撤回高德同意**不会**删除已经发到高德侧的那些数据 ——
那一部分按高德的隐私政策处理。

---

## 七、本应用明确不做的事

这几条都是踩过坑之后定下来的口径，改代码时请一并守住：

- **不碰用户的屏幕亮度**。曾有过"按小时分档设亮度"和"屏保时压暗"两版实现，都删了 ——
  前者导致每次进入应用都把用户自己调好的亮度拉走一档。现在只有"屏幕常亮"一个开关
  （设置浮层里，默认开），关掉就跟随系统息屏。
- **不改变 Wi-Fi 开关**。只读状态，开不开由用户在系统界面里决定；代码里连提示都是"去开启"而不是"帮你开启"。
- **不代用户改任何系统开关**。通知使用权、Shizuku 授权、电池白名单，一律只提供跳转，由用户自己按。
- **不要后台定位与录音**。`ACCESS_BACKGROUND_LOCATION` 和 `RECORD_AUDIO` 都没声明，也不打算声明。
- **不拿设备位置换天气**。天气请求用的是写死的城市坐标；要换城市就改那个常量，
  而不是悄悄把设备位置塞进请求里。
- **没有自建后端**。所有数据要么在设备上，要么发给第二节与第六节列明的两个接收方。

---

## 八、构建与运行

```bash
# 需要 JDK 17 与 Android SDK 37
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- Key 未配置时**编译不会失败**，但装到机器上地图会显示"未配置 Key"的提示块而不是白屏 —— 这是刻意的。
- 诊断日志只在 debug 构建落盘，取法见第六节（`run-as` 读内部目录，不是 `adb pull`）。
- `src/debug` 下的探针页只在 debug 变体存在，正式构建不参与合并：
  `MapPreviewActivity` / `NaviProbeActivity`（`:map`）、
  `NoticePreviewActivity` / `ShizukuPreviewActivity`（`:hosting`）、
  `SidebarLensPreviewActivity` / `AdaptiveDebugActivity` / `ValidationDriver`（`:shell`）。
  它们把"地图出不出图""这把 Key 有没有导航权限""提示页长什么样""换台机器会怎样"这类事情单独验一遍。
  自适应框架调试页的用法见第十节。

- **多屏幕回归怎么跑**（换机或改了任何尺寸之后照做一遍）：
  ```bash
  adb shell su -c "wm size 1264x2780"     # 竖屏 px（自然方向），横屏即 2780x1264
  adb shell su -c "wm density 640"        # 密度
  # …按第十节的表逐组看：首页 / 地图页 / 导航页 / 设置浮层 / 侧栏
  adb shell su -c "wm size reset" && adb shell su -c "wm density reset"
  ```
  **用完必须还原并回读确认**（`wm size` / `wm density` 不带参数即读当前值）。
  改尺寸前先 `svc power stayon true`，否则 60 秒后息屏，截出来全是纯黑 ——
  那看起来很像"应用崩了"。验证完记得 `svc power stayon false`。
  本机 `wm` 需要 root（非 root 下静默失败，`wm size` 会照旧打印 Physical 而不报错）。

## 九、当前状态

已完成：首页卡片栅格、侧栏（可左右切换，位置持久化）、地图屏（高德合包，按日出日落切换昼/夜样式）、
**目的地搜索与导航**（算路、多路线选择、全屏导航页、首页卡跟车预览：已走过段变灰 +
未走过段的方向箭头、内置 TTS 语音播报）、天气卡（Open-Meteo 真数据，
同时喂给 `Daylight` 接管地图昼夜切换，本地算法保留为离线兜底）、
音乐卡与**全屏音乐页**、Shizuku 状态指示与虚拟屏托管链路、投屏页、设置浮层。

**音乐页**说明：它读的是**别的 App 正在播放**的内容（经通知使用权 + `MediaSessionManager`），
歌词走 LRCLIB。**它自己不播放、不登录任何平台、不保存任何账号信息。**
详见第四节与 `PROJECT-INTRO.md` §2。

未完成 / 待接：智能家居（侧栏入口已预留 id 与分支）、投屏的真实链路
（现在是占位实现，见第六节）。

**关于导航的两条口径**，改这块代码时请一并守住：

1. 依赖必须是**导航合包** `navi-3dmap-location-search` 一个包，不能与独立地图包并存 ——
   官方导航开发包自带 3D 地图 SDK，两个包都带 `com.amap.api.maps.**`，会 duplicate class。
2. 覆盖物（路线、车标、方向箭头、已走过的灰段）画在**主地图**上，也就是首页卡那一张；
   全屏导航页用的是 `AMapNaviView` **内部自己的地图**，我们的 `NaviMapOverlay` 一帧都不在上面 ——
   那一屏上的绿线、灰线、白箭头全是高德 SDK 自带的。验"我们画的东西"必须回首页卡。

结论与验证过程见 `docs/`。

### 9.1 关于在线音乐平台（评估中，**尚未合入**）

音乐页现在是**只读**的 —— 它读别的 App 在放什么（第四节）。"自己会播 + 能登录在线平台"
这条线（代码里叫**路线 B**）仍在评估与验证阶段，**没有任何一行进入正式构建**：

| 问题 | 现状 |
| --- | --- |
| 会连哪家 | 只考虑 **QQ 音乐**一家 |
| 接口性质 | **非官方接口**（`u.y.qq.com` 的 `musicu.fcg` 等）。平台没有公开 API |
| 许可证 | 协议层的开源参考实现是 **MIT**（已确认"取播放链接"这条路径用不到其中任何加密原语），**不构成 AGPL 传染** |
| 是否已实现 | **没有**。目前只有一个 debug-only 的探针，用来在真机上验证"技术上能不能走通"。工程分支 `spike/music-phase0-qq` **刻意不合入 master**；已核对 release 清单里探针出现 **0 次** |
| 需不需要自建服务器 | **不需要**。不走 WebView，由应用内的 HTTP 客户端直连，因此**没有第三方服务器、没有账号中转** |
| 会不会存账号信息 | 目标形态是**凭证只在本机内存与设备私有目录**处理，**不上传、不入库、不写进任何文档或日志**。本次验证阶段结束时连设备上的临时凭证文件也一并删除 |
| 合规边界 | **自用不触发**；**分发或商业化需要单独评估**（非官方接口、平台用户协议，以及平台自身的著作权与会员权益） |

**一条不论做到哪一步都成立的红线**：如果**合法凭证下依然拿不到会员歌曲，立刻停止** ——
**绝不尝试任何绕过付费或 DRM 限制的手段**。本次验证中该条件**没有触发**，
全程没有任何一步需要、也没有尝试过这类手段。

技术结论与全部实测数据见 `docs/2026-10-14-music-phase0-native-playback.md`。

---

## 十、自适应框架与效果档位（换机必读）

### 10.1 为什么有这一节

这个应用最早是按**一台机器**调出来的：常量里写着 64 dp、332 dp、380 dp、560 dp，
注释里写着「361 dp」「786 dp」「3200×1440」「5×64 + 4×6 + 2×6 = 356 刚好放得下」。
这些数字在换机当天立刻失效 —— 实测现形了两个 bug：地图页右侧一条纯黑、
导航页「开始导航」被顶出屏幕。

现在守一条铁律：

> **凡是会被屏幕尺寸影响的尺寸，都不许是常量，必须是 `可用空间 → 尺寸` 的函数。**

留在 `core:ui/DeskDimens.kt` 里的只允许两类：**与屏幕无关的观感常量**（触控下限、圆角、
描边、间距）与**上限**（字号上限之类，超出就夹住、不会挤坏布局）。其余全部进
`core:ui/AdaptiveLayout.kt`，运行时从 `core:platform/DeviceProfile.kt` 读设备信息。

新增尺寸前先问一句：**"它假设的可用空间是多少？"** —— 答得出来，它就该进 `AdaptiveLayout`。

### 10.2 效果档位与手机要求

| 档位 | 实际效果 | 手机要求 | 依据 |
|---|---|---|---|
| **高** | 真折射 + 真色散 + 真模糊 | **Android 13 / API 33+** | RuntimeShader（AGSL）是 Android 13 才有的系统能力 |
| **中** | 真模糊；无折射，彩色边缘用渐变描边近似 | **Android 12 / API 31+** | `RenderEffect` 是 API 31 引入的 |
| **低** | 半透明纯色底 + 高光 + 滑动/进度动画，全部自绘 | 无（API 26 起都能跑） | 不依赖任何系统渲染特效 |
| **关闭** | 不建玻璃层，也不建背景动效层 | 无 | — |

**门槛不是猜的**，是读 `backdrop 2.0.0` 的源码得到的
（`.verify/src/androidMain/com/kyant/backdrop/`）：

- `Platform.kt:7` `isRenderEffectSupported() = SDK_INT >= 31` —— 不满足时
  `effects/Blur.kt:13` 与 `DrawBackdropModifier.kt:363` **第一行就 `return`**：
  不是"模糊失效但玻璃还在"，而是**连玻璃背景层都不会被建**。
- `Platform.kt:10` `isRuntimeShaderSupported() = SDK_INT >= 33` —— 不满足时
  `effects/Lens.kt:22` 第一行 `return`，折射静默消失。

两条都是**静默**返回：不崩、不报错、效果凭空不见。所以判断只能由应用自己补上，
这就是档位要在应用里算、而不是"交给库去降级"的原因。

**默认是「自动」**：按设备能力给它能跑的最高档，运行中变热或卡顿再自动降档。

### 10.3 运行时降级（自动档）

| 信号 | 怎么读 | 怎么用 |
|---|---|---|
| 掉帧 | `Choreographer` **间歇**采样：每 6 秒采 500 ms（占空比约 8%） | 连续 2 个"坏窗口"（掉帧 ≥ 25%）降一档 |
| 升温恢复 | 同上 | 连续 4 个"好窗口"（掉帧 < 10%）且温度不高于 MODERATE 才升一档 |
| 温度 | `PowerManager.getCurrentThermalStatus()`（API 29+） | **硬信号**：`SEVERE` 立刻降一档，`CRITICAL` 立刻降到最低 |
| 省电模式 | `PowerManager.isPowerSaveMode` | 判据收紧（掉帧 ≥ 15% 即算坏），且省电期间不主动升档 |

**为什么用间歇采样而不是 `FrameMetricsAggregator`**：后者要逐帧常驻，
一台 24 小时亮屏的桌面设备耗不起。**"没有数据"不等于"很卡"**——
屏幕灭掉时不采样，此时保持原档不动。

**迟滞**：升档比降档难得多（4 个好窗口 vs 2 个坏窗口）且带 45 秒冷却，
避免在阈值附近来回跳档。

### 10.4 设备档案

`DeviceProfile` 运行时读出：API 等级、窗口像素与 dp 尺寸、密度、刷新率、
挖孔（四边各自独立）、WebView 版本、内存，以及一个**设备指纹**
（形如 `api30/Xiaomi/M2102K1C/mem11244`）——「效果等级」这类设置按指纹存 key，
**换机后自动回到「自动」**，不会把上一台机器的选择带过去。
指纹**刻意不含**屏幕宽高与刷新率：`wm size`、改刷新率都不是换机，不该冲掉用户的选择。

> **一个踩过两次的坑**（改这块之前务必读 `DeviceProfile` 的注释）：
> `Configuration.screenWidthDp/HeightDp` 会**少报**（基准机上 875×356，把已隐藏的
> 状态栏也扣了）；`WindowMetrics.bounds` 会**多报**（914×411，把画不到的一圈算进来）。
> 两个方向都错过一次：少报让侧栏条目只有 64 dp 白浪费 39 dp，多报让模拟 800×360 dp 时
> 第 5 项被屏幕底边切掉。现在档案只给**窗口范围**这个不含糊的上界，
> 需要"真实可用高度"的地方**自己用 `BoxWithConstraints` 量容器**（侧栏就是这么修的）。

### 10.5 侧栏：按可用高度均分 + 三级降级

5 个入口按**容器量出来的可用高度**均分条目边长（上限 76 dp），
**永不低于 64 dp 触控下限**。放不下时依次：

1. 缩小间距（6 → 4 → 2 dp）；
2. 仍放不下 → 只显示图标、不显示文字标签；
3. 仍放不下 → 按"触控下限能排几个"**减少入口**（宁可少一个入口，不做点不中的小按钮）。

实测（同一台机器上 `wm size` / `wm density` 模拟，横屏 dp）：

| 模拟尺寸 | 侧栏 | 说明 |
|---|---|---|
| 1219 × 549（density 420） | 5/5 | 条目封顶 76 dp |
| 914 × 411（该机原生） | 5/5 | |
| 794 × 361 | 5/5 | 与 PKR110 一致 |
| 800 × 360（density 640） | 5/5 | 修之前这里第 5 项被底边切掉 |
| 686 × 309 | 4/5 | 少「手机投屏」 |
| 914 × 320（只矮不窄） | 4/5 | 少「手机投屏」 |
| 571 × 257（极限） | 3/5 | 少「地图」「手机投屏」 |

**已知的能力边界**：矮到 571×257 dp 这种程度时，「地图」「手机投屏」两个入口在这一屏上
不显示（是**整项不显示**，不是被裁掉一半 —— 后者是不允许的）。真机上不会有这么矮的横屏，
这是边界而非遗留问题。

### 10.6 设置浮层里的「效果等级」

自动 / 高 / 中 / 低 / 关闭，默认**自动**，**按设备持久化**。
帮助行会说明当前档位为什么是这个，例如：`低（设备最高 低 · 掉帧 2% · 温度 正常）`。

### 10.7 调试页（仅 debug 构建）

```bash
adb shell am start -n com.qingke.desk/com.qingke.desk.shell.debug.AdaptiveDebugActivity
```

一页回答四件事，每件都能截图证伪：

1. **这台机器被识别成什么** —— API、可用宽高 dp、窗口像素、密度、刷新率、挖孔、WebView、内存、指纹；
2. **档位为什么是这个** —— 当前档位 / 基线档位（设备最高）/ 已降几档 / 掉帧率 / 帧率 / 热状态 / 省电模式 / 最近一次变更原因；
3. **强制指定档位** —— 五档 + 取消，用于"同一块界面在各档下各截一张"；
4. **应用内模拟尺寸** —— 914×411 / 794×361 / 720×340 / 640×320 / 560×300 / 480×280 六组，
   不改系统设置（`wm size` 要 adb、还容易忘了还原）。

模拟盒子会**连设备档案一起换**：只把内容塞进小盒子而不换档案的话，盒里的组件仍按真机尺寸
反推，测出来的是"盒子太小"，不是"这台机器太小"。

