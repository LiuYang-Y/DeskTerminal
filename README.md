# DeskTerminal 智能桌面终端

把一台闲置的 Android 手机变成长时间摆在桌上的横屏终端：一屏里看到时间、待办、天气、音乐、地图，
需要时还能把手机画面投到车机、或把第三方应用托管进本应用显示。

Kotlin + Jetpack Compose，多模块（`:app` 是唯一的 app 模块），**非 root**，GPL-3.0。

基准机：OnePlus PKR110 / Android 16，横屏逻辑尺寸 **794 × 361 dp**（density 3.5，挖孔 140 px = 40 dp）。

---

## 一、用到的第三方 SDK

| SDK | 坐标 | 用途 | 数据去向 |
|---|---|---|---|
| 高德地图（含定位、搜索） | `com.amap.api:3dmap-location-search:10.1.300_loc6.4.9_sea9.7.4` | 地图屏的底图 + 定位小蓝点 | **回传高德服务器** |
| Shizuku | `dev.rikka.shizuku:{api,provider}:13.1.5` | 借 adb/root 身份创建虚拟屏，托管无接口的第三方应用 | **不出设备** |
| 通知使用权（系统能力，非 SDK） | `NotificationListenerService` | 读取播放器的 MediaSession，驱动首页音乐卡 | **不出设备** |
| Backdrop（玻璃效果） | `io.github.kyant0:backdrop:2.0.0` | 侧栏与卡片的液态玻璃材质 | 纯本地渲染 |
| Jetpack Compose / Material 3 | 见 `gradle/libs.versions.toml` | 全部界面 | 纯本地渲染 |

---

## 二、高德地图 SDK

**为什么需要**：桌面终端抬眼就要看到自己在哪、周边什么样。Google 地图在国内不可用，
高德是可行的厂商方案，且提供 Compose 可直接嵌入的 `MapView`。

**数据发往哪里**：北京高德图强科技有限公司。SDK 在本机采集并回传其服务器，官方列明的范围是：

- 经纬度、搜索词
- 设备信息：IP 地址、GNSS 信息、网络类型、WiFi 状态 / 参数 / 列表 / 信号强度 / 网关地址 / SSID / BSSID、
  基站信息、传感器信息（矢量、加速度、压力、方向、地磁、陀螺、温度、光敏、NEMA 信号）
- OAID、当前应用信息（应用名、版本号）、设备参数与系统信息（品牌型号、操作系统、运营商、屏幕分辨率）

官方隐私政策：<https://lbs.amap.com/pages/privacy/>

**如何关闭**

1. **从未同意**：地图屏只显示隐私说明卡，**在用户点"同意"之前不会构造 `MapView`**，
   SDK 不发生任何采集（这既是隐私要求，也是高德官方的合规要求 —— 不先声明就构造，地图会白屏）。
2. **同意后想关**：设置浮层（右上角齿轮）→「地图隐私」→「撤回同意」。撤回后地图立刻停用，
   下次进入地图屏重新回到说明卡。开关的实现见 `:map/AMapPrivacy.kt`。
3. **只想去掉定位**：系统设置 → 应用 → 本应用 → 权限 → 位置 → 拒绝。
   地图仍然可以显示，只是不画自己的位置。

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

**为什么需要**：要画首页那张音乐卡，就得知道别的播放器在放什么。
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

## 五、其余权限逐条说明

| 权限 | 为什么需要 | 如何关闭 |
|---|---|---|
| `INTERNET` | 地图瓦片、投屏发现、将来的天气 API | 卸载或断网 |
| `ACCESS_NETWORK_STATE` | 判断当前网络类型（地图网络定位、投屏链路前置检查） | — |
| `ACCESS_WIFI_STATE` | **只读** Wi-Fi 状态：投屏要确认手机连在自己的节点上 | — |
| `NEARBY_WIFI_DEVICES` | Android 13+ 扫描附近设备，用于投屏发现 | 系统设置里拒绝 |
| `BLUETOOTH` / `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` | 投屏的蓝牙引导链路 | 系统设置里关闭蓝牙权限 |
| `WAKE_LOCK` | 后台保活（前台服务用） | — |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 引导用户把本应用加入电池白名单，避免被系统杀掉 | 系统设置里撤销 |
| `FOREGROUND_SERVICE` (+`SPECIAL_USE`, `CONNECTED_DEVICE`) | 虚拟屏必须活在一个不受 Activity 重建影响的持有者里 | 停止 Shizuku 或不使用托管功能 |
| `POST_NOTIFICATIONS` | 投屏连接状态的通知 | 系统设置里关闭通知 |
| `READ_MEDIA_AUDIO` | 本地音乐（Android 13+） | 系统设置里拒绝 |

此外 `AndroidManifest.xml` 里有两处 `<queries>` 包可见性声明（`com.shihab.diplay`、
`com.autonavi.minimap`、`moe.shizuku.privileged.api`）：targetSdk ≥ 30 上不声明就查不到这些包，
会把"已安装"误判成"未安装"。**没有使用 `QUERY_ALL_PACKAGES` 这种宽口径。**

---

## 六、本应用明确不做的事

这几条都是踩过坑之后定下来的口径，改代码时请一并守住：

- **不碰用户的屏幕亮度**。曾有过"按小时分档设亮度"和"屏保时压暗"两版实现，都删了 ——
  前者导致每次进入应用都把用户自己调好的亮度拉走一档。现在只有"屏幕常亮"一个开关
  （设置浮层里，默认开），关掉就跟随系统息屏。
- **不改变 Wi-Fi 开关**。只读状态，开不开由用户在系统界面里决定；代码里连提示都是"去开启"而不是"帮你开启"。
- **不代用户改任何系统开关**。通知使用权、Shizuku 授权、电池白名单，一律只提供跳转，由用户自己按。
- **没有自建后端**。所有数据要么在设备上，要么发给上表列明的第三方。

---

## 七、构建与运行

```bash
# 需要 JDK 17 与 Android SDK 37
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- Key 未配置时**编译不会失败**，但装到机器上地图会显示"未配置 Key"的提示块而不是白屏 —— 这是刻意的。
- 本机 `adb logcat` 在本项目基准机上恒为空，因此模块内的诊断日志会落到
  `/sdcard/Android/data/com.qingke.desk/files/<模块>/`（仅 debug 构建），用 `adb pull` 取。
- `src/debug` 下有两个探针页（`MapPreviewActivity`、`NoticePreviewActivity` 等），
  只在 debug 变体存在，用于把"地图出不出图""提示页长什么样"这类事情单独验一遍。

## 八、当前状态

已完成：首页卡片栅格、侧栏（可左右切换，位置持久化）、地图屏（高德 SDK，按日出日落切换昼/夜样式）、
音乐卡、Shizuku 状态指示与虚拟屏托管链路、投屏页、设置浮层。

未完成 / 待接：真实天气数据源（`:feature:weather` 目前是假数据；接好后调用
`Daylight.publish(sunrise, sunset)` 即可接管地图昼夜切换，本地算法保留为离线兜底）、
智能家居（侧栏入口已预留 id 与分支）、地图的导航能力（尚未接入导航 SDK，详见下条）。

**关于导航**：目前的 `:map` 只做地图显示，不做路径规划与导航。若要接入高德导航 SDK 需注意：
官方导航开发包**自带 3D 地图 SDK**，与现有 `3dmap-location-search` 依赖会冲突，
必须整体换成导航合包而不是两个并存 —— 结论与验证见 `docs/`。
