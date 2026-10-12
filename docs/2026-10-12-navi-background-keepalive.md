# 导航后台保活方案（只出方案，本轮不实现）

2026-10-12。用户要求：先不做前台服务，但要把"这台设备上到底需要哪些权限、授权流程长什么样、
小米那边要手动开什么"核实清楚，并给出手动设置步骤。

---

## 0. 结论摘要

| 问题 | 结论 |
| --- | --- |
| 现在短时切后台会不会断导航？ | **不会**。实测压后台 12 秒 `guide` 持续输出，SDK 也一直持着音频焦点 |
| 长时间后台（投屏场景）会不会断？ | **会**。没有前台服务，Doze / App Standby 会把定位降频甚至停掉 |
| 要不要前台服务？ | 要（`foregroundServiceType="location"`），但它**不是本机跑通的必要条件**，是"投屏时仍然可靠"的必要条件 |
| 本机需要 `FOREGROUND_SERVICE_LOCATION` 吗？ | **不需要**。该权限 API 34 才有，本机是 API 30 |
| 本机需要 `POST_NOTIFICATIONS` 吗？ | **不需要**。该权限 API 33 才有，本机是 API 30 |
| 本机需要 `ACCESS_BACKGROUND_LOCATION` 吗？ | **要**，但只有在"应用已在后台时创建 location 前台服务"这条路径上才卡它（见 §2.3） |
| 权限弹窗里能直接选"始终允许"吗？ | **不能**。API 30 起弹窗里没有这个按钮，必须跳设置页 |

---

## 1. 本机环境（决定答案的前提）

```
ro.build.version.release = 11        # Android 11
ro.build.version.sdk     = 30        # API 30
ro.miui.ui.version.name  = V125      # MIUI 12.5
```

应用侧：`minSdk = 26` / `targetSdk = 36` / `compileSdk = 37`。

**两条口径必须先分清**：

- **权限在不在，看设备 API 级别。** 设备是 API 30，`POST_NOTIFICATIONS`(33)、
  `FOREGROUND_SERVICE_LOCATION`(34) 这两个权限在这台机器上**根本不存在**，
  写进 manifest 也不会生效（系统忽略未知权限）。
- **行为严不严，看 targetSdk。** 我们 target 36，所以"target ≥ 30 才强制"的那些规则
  （比如前后台定位必须分开请求）**对我们全部生效**，哪怕设备只是 API 30。

下面每一条都注明依据，便于复核。

---

## 2. 逐项核实

### 2.1 `ACCESS_BACKGROUND_LOCATION`

- **引入**：Android 10 / API 29。
- **本机**：存在，且是"后台还能拿定位"的唯一凭据。
- map 模块当前 manifest **只有** `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` /
  `ACCESS_EXTRA_COMMANDS`，**没有**后台定位权限。

### 2.2 前后台定位**不能同时请求**（Android 11 起强制）

官方原文（developer.android.com/about/versions/11/privacy/location）：

> If you request a foreground location permission and the background location permission
> at the same time, the system ignores the request and doesn't grant your app either permission.

后果很实在：**同时请求 = 两个都不给**，用户看到一个空弹窗或者干脆没弹窗。
所以必须拆成两步，且顺序固定：

1. 先请求 `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`，等用户选完；
2. 拿到前台定位之后，**再单独**请求 `ACCESS_BACKGROUND_LOCATION`。

### 2.3 后台定位的授权流程（和以前不一样）

官方原文（同上）：

> When a feature in your app requests background location on a device that runs Android 11
> or higher, the system dialog **doesn't include a button to enable background location access**.
> In order to enable background location access, users must set the **Allow all the time** option
> for your app's location permission on a **settings page**.

所以第 2 步不会弹一个"允许/拒绝"对话框，而是**直接把用户送到应用的权限设置页**，
让他自己选"始终允许"。这意味着：

- 代码上要准备好"用户从设置页回来"的处理（`onResume` 里复查权限）；
- 文案上要说清楚"请把位置权限改成**始终允许**"，否则用户不知道去那儿干嘛。

**另有一条容易被忽略的**：`location` 前台服务的运行时前提是"已授予 FINE 或 COARSE"；
但**当应用已经处于后台时才去创建 location 前台服务，还需要 `ACCESS_BACKGROUND_LOCATION`**
（"在使用时"权限限制）。我们的正常路径是用户在前台点"开始导航"→ 立刻起服务，
这条路径不需要后台定位；只有"后台被自动拉起并要恢复导航"才需要。

### 2.4 `FOREGROUND_SERVICE_LOCATION` —— 本机不需要

官方原文（developer.android.com/develop/background-work/services/fgs/service-types）：

> 从 Android 14（API 级别 34）开始，您必须为每项前台服务声明相应的服务类型……
> 并请求与该类型相符的前台服务权限。

- `location` 类型对应的权限是 `FOREGROUND_SERVICE_LOCATION`，**API 34 引入**；
- 本机 API 30，该权限不存在 → **本机不需要**；
- 但**建议照写**：将来设备升级到 Android 14+、或提交应用商店时会用到。
  声明一个当前系统不认识的权限是允许的，会被忽略。

`android:foregroundServiceType="location"` 这个**属性**本身 API 29 就有，
在 API 30 上声明它没有副作用；声明之后 `startForeground()` 要传对应类型常量。

### 2.5 `POST_NOTIFICATIONS` —— 本机不需要

官方原文（developer.android.com/guide/topics/ui/notifiers/notification-permission）：

> Android 13（API 级别 33）及更高版本支持用于从应用发送非豁免（包括前台服务 [FGS]）通知的
> 运行时权限：`POST_NOTIFICATIONS`。

- 本机 API 30，该权限不存在 → 通知**默认允许**，不用申请；
- 同样**建议照写**（升到 13+ 的设备上才需要运行时申请）。

### 2.6 前台服务必须有常驻通知（这一条 API 26 起就成立）

不是权限问题，是硬要求：`startForeground()` 必须带一个 `Notification`。
所以：

- 要先建**通知渠道**（API 26+ 必须，否则通知不显示）；
- 通知优先级要 `PRIORITY_LOW` 或更高；
- 想一下这条常驻通知在"桌面终端 24 小时常亮"的场景里长什么样 —— 它会一直挂在下拉栏里。

---

## 3. 要写的代码清单（本轮**不做**，列出来是为了估范围）

| # | 位置 | 内容 |
| --- | --- | --- |
| 1 | `map/src/main/AndroidManifest.xml` | 加 `ACCESS_BACKGROUND_LOCATION`；加 `FOREGROUND_SERVICE_LOCATION`（本机忽略、为后续设备准备） |
| 2 | `app/src/main/AndroidManifest.xml` | 声明服务：`<service android:name=".NaviKeepAliveService" android:foregroundServiceType="location" android:exported="false"/>`（现在那里只有一条 `TODO(step-2)`，没有 service） |
| 3 | 新建 `NaviKeepAliveService` | `onStartCommand` → 建通知渠道 → `ServiceCompat.startForeground(1, notification, FOREGROUND_SERVICE_TYPE_LOCATION)`；`onDestroy` → `stopForeground(STOP_FOREGROUND_REMOVE)` |
| 4 | `NaviSession` | 进入 `NAVIGATING` 时 `startForegroundService`，离开时 `stopService`。**放会话里而不是界面里** —— 切屏、翻页本来就不该影响它 |
| 5 | 权限流程 | 复用 `AMapCanvas` 已有的前台定位请求；**新增**第二步单独请求 `ACCESS_BACKGROUND_LOCATION`，并处理"跳到设置页 → 返回后复查" |
| 6 | 文案 | 请求后台定位之前要有一屏说明（"切到手机投屏时导航仍要继续，需要把位置权限改为始终允许"） |

**不建议**用 `shortService` 类型绕开权限：它限时约 3 分钟，导航动不动就是一小时。

---

## 4. 小米（MIUI 12.5 / Android 11）需要你手动设置的项

系统权限只是第一层；MIUI 还有自己的一套后台管理，**默认配置下它会杀掉长时间后台的应用**。
下面这些我改不了（都是系统设置，需要你在设备上点），路径按 MIUI 12.5 的菜单写：

| # | 步骤 | 为什么要做 |
| --- | --- | --- |
| 1 | 设置 → 应用设置 → 应用管理 → 桌面终端 → **省电策略** → 选 **无限制** | 这是最关键的一项。默认"智能省电"会在后台限制进程 |
| 2 | 设置 → 应用设置 → 权限管理 → 桌面终端 → **自启动** → 打开 | 否则重启后被系统拉起的路径走不通 |
| 3 | 设置 → 应用设置 → 权限管理 → 桌面终端 → **定位** → 选 **始终允许** | 这就是 §2.3 说的那一步，弹窗里没有这个选项 |
| 4 | 设置 → 电池与性能 → 右上角齿轮（高级设置）→ **应用智能省电** → 桌面终端 → **无限制** | 与第 1 项是两处入口，MIUI 版本不同生效的可能是不同那一处，两处都设最稳 |
| 5 | 多任务界面下拉应用卡片 → 点**挂锁**图标 | 防止"一键清理"把它清掉 |
| 6 | 设置 → 应用设置 → 应用管理 → 桌面终端 → **通知管理** → 允许 | 前台服务的常驻通知必须能显示 |

---

## 5. 怎么证明它真的生效（做完之后）

- **服务在不在前台**：`adb shell dumpsys activity services com.qingke.desk`
  看 `isForeground=true` 与 `foregroundServiceType=location`（API 30 上字段名可能是
  `mForegroundServiceType`）。
- **导航有没有被降频**：压后台 5 分钟以上，再读应用日志
  `adb exec-out run-as com.qingke.desk cat files/map/map.log`，看 `navi: guide` 的
  时间戳有没有断档。**这一次只压了 12 秒，不足以证伪"长时间后台会断"** ——
  这正是要补前台服务的原因。
- **主动制造 Doze**：`adb shell dumpsys deviceidle force-idle`，
  然后看 guide 是否还在流（这是比"压后台"更狠的判据）。

---

## 6. 风险与取舍

- **常驻通知**：这台设备是桌面终端，下拉栏里长期挂一条"导航进行中"。可以接受，
  但要确认这是你想要的形态。
- **耗电**：前台服务本身不贵，贵的是"后台持续定位"。导航中本来就要持续定位。
- **小米的省电策略即使设成"无限制"，极端低电时仍可能被杀**。前台服务能大幅降低概率，
  但不能保证 100%。真正的兜底是"被杀之后能恢复"（`START_STICKY` + 状态落盘），
  那是另一个话题，本轮不展开。

---

## 7. 参考出处

- Android 11 位置更新（前后台分开请求、弹窗无"始终允许"）：
  https://developer.android.com/about/versions/11/privacy/location
- 前台服务类型与所需权限（location → `FOREGROUND_SERVICE_LOCATION`，API 34 起强制）：
  https://developer.android.com/develop/background-work/services/fgs/service-types
- 启动前台服务（API 34 起缺权限抛 `SecurityException`；通知优先级要求）：
  https://developer.android.com/develop/background-work/services/fgs/launch
- 通知运行时权限（`POST_NOTIFICATIONS` 为 API 33 引入）：
  https://developer.android.com/guide/topics/ui/notifiers/notification-permission
