# debug-only 审计：验证驱动与测试专用代码

日期：2026-10-12 · 版本：debug / release 两个变体各构建一次 · 设备：小米 11 Ultra（3200×1440 @560dpi）

## 结论

| 检查项 | debug | release |
| --- | --- | --- |
| `com.qingke.desk.VALIDATION`（广播 action 字符串，dex） | 有 | **无** |
| `validation driver registered`（注册日志字符串，dex） | 有 | **无** |
| `MapPreviewActivity`（:map 调试页） | 有 | **无** |
| `NaviProbeActivity`（:map 导航探针页） | 有 | **无** |
| `ShizukuPreviewActivity` / `NoticePreviewActivity`（:hosting 调试页） | 有 | **无** |
| `androidx.compose.ui.tooling.PreviewActivity` | 有 | **无** |
| `androidx.activity.ComponentActivity`（ui-test-manifest 载体） | 有 | **无** |
| 运行时发验证广播 | 翻到第 2 页 | **不动**（停在第 1 页） |
| 对照：`AMapPrivacyCard` / `OpenMeteoWeatherRepository` / `Daylight` | 有 | 有（扫描方法有效） |

**没有在清单里声明任何自定义 receiver** —— 验证驱动是**运行时注册**的，
所以任务里问的"接收器不对外导出"要分两句答：

1. **release**：不存在这个接收器，也不存在注册它的代码 —— 谈不上导出与否；
2. **debug**：这个运行时接收器必须用 `RECEIVER_EXPORTED` 注册，
   否则 `adb shell am broadcast`（外部 uid）根本送不进来，验证驱动就失灵了。
   它只存在于 debug 变体，不构成 release 面的暴露。

## 方法

三条互相独立的证据，任何一条单独都可能有假阳性/假阴性，合起来才说明问题。

### 1. dex 字符串扫描（`.verify/tools/audit_release.py`）

两个 APK 解包后逐个 `classes*.dex` 搜字节串。类名与 action 都会以字符串形式留在
dex 的字符串表里（未开 minify），release 里搜不到就说明那段代码确实不在产物里，
而不是"在里面但没跑"。

```
debug:   app-debug.apk              205.2 MB
release: app-release-unsigned.apk   198.8 MB
```

```
$ python .verify/tools/audit_release.py
debug   [YES] com.qingke.desk.VALIDATION      <- classes3.dex
debug   [YES] MapPreviewActivity              <- classes6.dex
debug   [YES] AMapPrivacyCard                 <- classes3.dex,classes9.dex   ← 对照
release [no ] com.qingke.desk.VALIDATION
release [no ] MapPreviewActivity
release [YES] AMapPrivacyCard                 <- classes3.dex                ← 对照
```

`androidx.test.runner.AndroidJUnitRunner` 与 `androidx.compose.ui.test`
**两个 APK 里都没有**：androidTest 的代码与依赖进的是**独立的测试 APK**
（`app-debug-androidTest.apk`），本来就不会进应用包。

### 2. 合并后清单对比

`app/build/intermediates/merged_manifest/{debug,release}/.../AndroidManifest.xml`

debug 独有（release 完全没有这些条目）：

```
com.qingke.desk.map.debug.MapPreviewActivity
com.qingke.desk.map.debug.NaviProbeActivity
com.qingke.desk.hosting.debug.ShizukuPreviewActivity
com.qingke.desk.hosting.debug.NoticePreviewActivity
androidx.compose.ui.tooling.PreviewActivity
androidx.activity.ComponentActivity
```

两者共有（都是产品代码，不是测试代码）：
`MainActivity`、`VdHostService(exported=false)`、`MediaNotificationListener`
（`exported=true` 但受 `BIND_NOTIFICATION_LISTENER_SERVICE` 系统权限保护）、
`rikka.shizuku.ShizukuProvider`（Shizuku 库要求，从外部进程访问）、
`androidx.startup.InitializationProvider(exported=false)`、
`androidx.profileinstaller.ProfileInstallReceiver`、`com.amap.api.location.APSService`。

### 3. 运行时广播比对（决定性的一条）

release 包用 debug keystore 临时签名后装机（`apksigner sign`，只为验证，不进仓库）：

| 包 | `am broadcast -a com.qingke.desk.VALIDATION --es cmd page:1` 之后 |
| --- | --- |
| release（签名后，截图 `w13-rel-before/after.png`） | 圆点宽度 `[28, 21]` → `[28, 21]`，**仍第 1 页** |
| debug（截图 `w13-debug-broadcast.png`） | 圆点宽度 `[21, 28]`，**第 2 页** |

logcat 里 release 也没有 `validation driver registered` 这一行（只有产品代码的
`DeskShell: lifecycle=...`）。

## 实现方式（为什么会是这个样子）

原来是 `BuildConfig.DEBUG` 门控 + 一句 `if (!BuildConfig.DEBUG) return`。问题是：
**release 包里仍然带着完整的接收器实现**（`BroadcastReceiver` 子类、`IntentFilter`、
那条 action 字符串），只是不执行。"验证驱动只在 debug 存在"于是变成
"它存在但没跑" —— 反编译一看就有，不好核实也不好交代。

现在整份驱动按构建类型拆开：

```
shell/src/debug/java/.../ValidationDriver.kt     ← 真实实现（注册广播接收器）
shell/src/release/java/.../ValidationDriver.kt   ← 空实现（只有 commands + register 两个空壳）
shell/src/main/java/.../ValidationTypes.kt       ← PageRequest / FULLSCREEN_MAP_TAG（线上也在用）
```

同包同名的两个文件分属**不同的 buildType 源码集**，任一变体只会看到其中一个，
不存在重复类。`DeskRoot` 里的调用一行没改。

顺带去掉了 `:shell` 的 `buildFeatures.buildConfig`（不再需要 BuildConfig）。

## 复现

```bash
JAVA_HOME="D:/java" ./gradlew :app:assembleDebug :app:assembleRelease --no-daemon
python .verify/tools/audit_release.py
# release 装到真机（临时签名）：
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey \
  --out app-release-signed.apk app-release-unsigned.apk
adb install -r app-release-signed.apk
adb shell am broadcast -a com.qingke.desk.VALIDATION --es cmd page:1   # 应当毫无反应
```
