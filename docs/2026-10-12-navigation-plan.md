# 导航怎么接？—— 方案（等你确认后再写代码）

日期：2026-10-12。**本文只写方案，不动代码。**

事实来源照旧：**能验的一律验过再说**。
下面每一处 API 签名都是从
`com.amap.api:navi-3dmap-location-search:11.3.100_3dmap11.3.100_loc11.3.000_sea9.8.1`
（`.verify/navi/navi.jar`，106 MB）里 `javap` 直读出来的，不是查文档猜的；
每条都注明了来源文件，后来的同学可以复跑一遍。

---

## 0. 一句话结论

| 问题 | 结论 |
|---|---|
| 它会自己开一个全屏 Activity 吗？ | **有一条路径会**（`AmapNaviPage.showRouteActivity`），但**存在另一条不需要的路径**（`AMapNaviView` 是个 `FrameLayout`，可以塞进我们的 Compose 屏）。推荐走后者。 |
| 横屏、侧栏、上滑逃生、怎么回来 | 走组件页的话**侧栏和上滑逃生一定没有**（不在我们的 Compose 里）；走内嵌的话三者**全部保住**，返回 = 切回地图页的状态切换，不是跨 Activity。 |
| 音乐能不能自动压低 | **能**，这是 SDK 自带的：`AMapNavi.setControlMusicVolumeMode(MUSIC_VOLUME_MODE_DEPRESS)`。SDK 内部自己也申请了音频焦点（jar 里能搜到 `requestAudioFocus` / `abandonAudioFocus` 字符串）。但要真机验：这条路依赖**内置语音**，且"压低后恢复"由 ROM 音频栈决定。 |
| 路线规划权限 | **已经验过**：`.verify/navi-regress/N1.log` 里 `onCalculateRouteSuccess code=0 ... path: 38467m / 2700s / lights=18`（钟楼→咸阳机场）。这把 Key 有路线规划能力，可以直接进功能开发。**但"能算路"≠"能导航"**，见 §5.4。 |

**推荐方案 B（内嵌 `AMapNaviView`）**，理由在 §2 展开 —— 一句话：我们这个项目最值钱的东西是
「横向货架 + 侧栏 + 逃生手势 + 常亮 + 防烧屏 + 挖孔避让」这一整套壳，
换成别人的 Activity 就整段失效；内嵌只多约 150 行生命周期代码就能全保住。

另外有 **三件必须先补的事**（与导航功能无关，但没有它们导航一定白屏或合规不过），列在 §5，等你一并拍板。

---

## 1. 逐条回答你的四个问题

### 1.1 导航组件会不会自己启动一个全屏 Activity？

**会，但不是唯一选项。** `javap` 直读 `com.amap.api.navi.AmapNaviPage`：

```
public void showRouteActivity(Context, AmapNaviParams, INaviInfoCallback)
public void showRouteActivity(Context, AmapNaviParams, INaviInfoCallback, Class<? extends AmapRouteActivity>)
public void exitRouteActivity()
public static synchronized void destroy()
```

第二条重载说明：**允许我们传自己的子类**。它的类关系是

```
AmapRouteActivity extends CheckPermissionsActivity extends android.app.Activity
    implements AMapNaviViewListener, AMapNaviListener, AMapNaviDriveListener, View.OnClickListener
```

也就是"组件页"这条路的样子：SDK 自带一个 Activity，里面已经铺好了
导航 View、路况条、路口放大、语音按钮，我们只需要把起终点塞进 `AmapNaviParams` 交给它。

另一条路的证据：`javap com.amap.api.navi.AMapNaviView`

```
public class AMapNaviView extends android.widget.FrameLayout
  public AMapNaviView(Context)
  public AMapNaviView(Context, AMapNaviViewOptions)
  public final void onCreate(Bundle)
  public final void onCreate(Bundle, Activity, AmapPageType)
  public final void onResume()   / onPause()   / onDestroy()   / onSaveInstanceState(Bundle)
  public boolean onBackPressed()
  public AMap getMap()
  public void setAMapNaviViewListener(AMapNaviViewListener)
  public boolean isOrientationLandscape()
```

**它是个 View，不是 Activity。** 所以它可以被放进我们的 `AndroidView`，
至于谁负责算路、谁负责开车，是我们自己拿 `AMapNavi` 来调：

```
AMapNavi.getInstance(Context) throws AMapException          // 静态方法
  void calculateDriveRoute(NaviPoi start, NaviPoi end, List<NaviPoi> ways, int strategy)
  boolean startNavi(int)
  void stopNavi()
  void setUseInnerVoice(boolean) / setUseInnerVoice(boolean, boolean)
  void setControlMusicVolumeMode(int) / int getControlMusicVolumeMode()
  static synchronized void destroy()                        // 注意：静态
```

`NaviProbeActivity`（现有的 debug 探针）已经证明这条路走得通：`getInstance` OK →
`calculateDriveRoute accepted=true` → `onCalculateRouteSuccess code=0` → 拿到 38.4 km 的路径对象。

### 1.2 横屏下表现如何？

**这一条没法靠看 API 下结论，只能真机验**（见 §4 的 Step 4 清单）。已知的两点事实：

1. `AMapNaviView` 里有 `onConfigurationChanged(Configuration)` 和 `isOrientationLandscape()`，
   说明 SDK **知道自己在横屏**并按横屏铺过内部版式 —— 至少不是"竖屏版式硬拉宽"。
   它还有 `setLayoutVisible(boolean)` / `setFullScreenMode(boolean)` 可用。
2. 组件页是独立 Activity，横屏由 **manifest 的 `screenOrientation`** 决定，
   而那个 Activity 的 manifest 是我们自己写的（jar 里 0 个 `AndroidManifest.xml`
   —— 5931 个条目里没有清单，所以 `AmapRouteActivity` 必须在我们的 app 清单里声明，见 §5.2）。
   我们可以照抄 `MainActivity` 的 `sensorLandscape` + `configChanges` 一行不差。

内嵌方案在这件事上占便宜：**MainActivity 的窗口配置已经全部配好了**
（`sensorLandscape` + `shortEdges` 吃挖孔 + 沉浸式 + `isNavigationBarContrastEnforced=false`），
导航 View 直接继承这套窗口，不需要为它再配一遍，也不会出现
"导航 Activity 与桌面 Activity 朝向/沉浸模式不一致，切换时闪一下"。

### 1.3 侧栏和边缘上滑逃生手势在导航中还能用吗？怎么用怎么回来？

先把**现状**讲清楚（这两条是 codebase 里的事实，不是推测）：

- 上滑逃生 `edgeSwipeUp` 现在**只挂在投屏页**
  （`ProjectionScreen.kt:75`，`escapeEnabled = renderMode == EMBEDDED_SURFACE`）。
  地图页、首页**都没挂** —— 它从来不是一个"全局"手势，而是投屏页专用的。
- 侧栏只在 `chromeScreen`（`HOME` / `MAP`）显示（`DeskRoot.kt:430`），
  它是 `DeskRoot` 里的一块 Compose 内容。

于是两条路的差别非常干净：

| | 组件页 `AmapRouteActivity`（A） | 内嵌 `AMapNaviView`（B） |
|---|---|---|
| 侧栏 | ❌ **没有**。那是另一个 Activity，和我们的 Compose 树没有半点关系 | ✅ 由我们决定。建议导航时**不显示**（挡地图），改成导航 View 自带按钮 + 我们的"退出导航"按钮 |
| 上滑逃生 | ❌ **没有**（同上） | ✅ 可以挂。起手势落在导航屏底部 → `stopNavi()` → 切回地图页 |
| 怎么回来 | 三条路（都要真机逐个验）：① SDK 自带的返回/退出（`onNaviBackClick()` / `onNaviCancel()` / `onAMapNaviViewExit()`）；② 系统返回键（它自己实现了 `onBackPressed()`）；③ 我们的回调 `INaviInfoCallback.onExitPage(int)` 里收尾 | 一条路，且完全可控：`DeskScreen` 状态切回 `MAP`。不存在"回不来"这种可能 |
| 常亮 / 防烧屏 / 挖孔 | ❌ 全部失效（那些都活在 `MainActivity` 的窗口 flag 与 Compose 里），要为这个 Activity 重做一遍 | ✅ 原样保留 |

`INaviInfoCallback` 的完整能力（javap 直读），其中 `onExitPage(int)` 是
"用户从组件页退出"的唯一确定性钩子 —— 走 A 方案时它就是我们的收尾点：

```
onInitNaviFailure() / onGetNavigationText(String) / onLocationChange(AMapNaviLocation)
onArriveDestination() / onStartNavi(int) / onCalculateRouteSuccess(int[]) / onCalculateRouteFailure(int)
onReCalculateRoute(int) / onStopSpeaking() / onExitPage(int)
```

### 1.4 音频焦点：音乐能不能自动压低？

**能，而且是现成的。** 三组证据：

```
com.amap.api.navi.enums.AMapNaviControlMusicVolumeMode
  public static final int MUSIC_VOLUME_MODE_DEPRESS;   // 压低（ducking）
  public static final int MUSIC_VOLUME_MODE_PAUSE;     // 暂停

AMapNavi
  public void setControlMusicVolumeMode(int)
  public int getControlMusicVolumeMode()

AMapNaviViewOptions$VoiceAssistSettings
          // View 侧也有一份，构造参数第三个就是 int
  public VoiceAssistSettings(boolean, boolean, int)
  public void setControlMusicVolumeMode(int)
  public int getControlMusicVolumeMode()
  public boolean isEnabled() / setEnabled(boolean)
  public boolean isListenToVoiceDuringCall() / setListenToVoiceDuringCall(boolean)
```

另外，合包内部确实管了音频焦点 —— 在 jar 里能搜到 `requestAudioFocus` /
`abandonAudioFocus` / `AudioFocus` 字节串（`com.amap.api.col.3nsl.m7` / `q7`）。
**所以不需要我们自己申请音频焦点**，只需要告诉它用哪种策略。

三个必须说明的前提：

1. **依赖内置语音**：`ducking` 是"导航说话时才压低音乐"，不说话就不压。
   所以必须开 `setUseInnerVoice(true)`（SDK 自带 TTS，不用我们自己接语音合成）。
   `AmapNaviParams.setUseInnerVoice(boolean)` 也有同名开关。
2. **压低多少、能不能恢复，是 ROM 音频栈说了算**。高德给的是"策略"，
   最终效果要在真机上用**正在播放的音乐 + 一次真实播报**来验（见 Step 4 清单）。
3. **选哪个值我建议先 DEPRESS**：PAUSE 是"一说话就把音乐停掉"，
   对长时间导航太粗暴；先试压低，实测压不动再降级成 PAUSE。

写代码时打算两处都设（`AMapNavi` + `VoiceAssistSettings`），因为我不确定
组件模式下到底哪一处生效 —— 两处设同一个值不会有冲突，漏一处可能整段沉默。

---

## 2. 两条候选架构（请你选一个）

### 方案 A —— 组件页（`AmapNaviPage.showRouteActivity`）

```kotlin
val params = AmapNaviParams(start, ways, end, AmapNaviType.DRIVER, AmapPageType.NAVI)
params.setUseInnerVoice(true)
AmapNaviPage.getInstance().showRouteActivity(context, params, callback)   // 或加第 4 个参数传我们的子类
```

- ✅ 代码最少，导航 UI（路况条、路口放大图、车道线、剩余红绿灯）全是现成的；
- ✅ 生命周期、权限、算路 SDK 自己全包；
- ❌ 侧栏 / 上滑逃生 / 常亮 / 挖孔避让全部失效，要为它重做一遍壳 —— 那是把现有代码抄一份；
- ❌ 返回依赖它的 `onBackPressed`，而我们的 `MainActivity` 把返回键**吞掉了**
  （`MainActivity.kt:44`，HOME 应用不能让返回键退出）。跨 Activity 的返回语义要实测才知道稳不稳；
- ❌ 横屏/沉浸式/挖孔要在它的 manifest 里重配，等于两处配置各写各的，容易漂。

### 方案 B —— 内嵌 `AMapNaviView`（**推荐**）

新开一个 `DeskScreen.NAVI`（或在全屏地图页里叠一层），用 `AndroidView` 挂 `AMapNaviView`，
我们自己管生命周期、自己算路、自己开内置语音：

```kotlin
AndroidView(
    factory = { ctx -> AMapNaviView(ctx, options).apply { onCreate(null, activity, AmapPageType.NAVI) } },
    update = { it.onResume() },
)   // 退到 DisposableEffect 里按顺序 onPause() → onDestroy()
```

配一条明确的执行顺序（这也是 `NaviProbeActivity` 已经跑通的顺序）：

```
NaviSetting.updatePrivacyShow / updatePrivacyAgree   ← 合规闸门，必须在 getInstance 之前
AMapNavi.getInstance(context)
navi.setUseInnerVoice(true)
navi.setControlMusicVolumeMode(MUSIC_VOLUME_MODE_DEPRESS)
navi.calculateDriveRoute(start, end, ways, strategy)
   └─ onCalculateRouteSuccess → navi.startNavi(NaviType.GPS)
退出：navi.stopNavi() → 切 DeskScreen.MAP → AMapNavi.destroy()
```

- ✅ 壳层全部保住：侧栏（可选显示/隐藏）、上滑逃生、常亮、防烧屏、沉浸式与挖孔避让一行不改；
- ✅ 返回是我们自己的状态切换，**不存在回不来**；
- ✅ 之后要加"导航时卡片暂停刷新 / 熄屏策略"这类联动，都是现成的位置；
- ❌ 多约 150 行生命周期代码，且必须自己写"退出导航"按钮（建议做成地图页原有齿轮旁边的一颗）；
- ❌ 一旦 SDK 内部对 Activity 有隐含依赖（`onCreate(Bundle, Activity, AmapPageType)` 这个签名
  暗示它可能要 activity 句柄），我们要把 `LocalContext` 里的 Activity 传进去 —— 可行，但要验。

**建议**：直接 B。如果 Step 4 实测发现内嵌 View 出不来完整 3D 效果或生命周期有硬伤，
再退到 A —— 那时已经有一份可跑的搜索/预览（Step 2、3 与架构无关，两个方案共用）。

---

## 3. 分步实施方案

每一步都能单独提交、单独验证、单独回退。
**Step 2 + Step 3 做完就已经能用**（能搜目的地、能看路线和耗时），不导航也有价值；
所以即使 Step 4 卡住，也不至于白干。

### Step 0 —— 合规闸门补全（前置，不涉及任何导航 UI）

`javap com.amap.api.navi.NaviSetting`：

```
public static void updatePrivacyShow(Context, boolean, boolean)
public static void updatePrivacyAgree(Context, boolean)
```

**导航 SDK 有它自己的一套合规闸门，与 `MapsInitializer` 不是同一个。**
当前 `AMapPrivacy.push()` 只调了 `MapsInitializer.updatePrivacy*`（`AMapPrivacy.kt:100`），
没覆盖 `NaviSetting`。改法：在 `AMapPrivacy.push()` / `revoke()` 里并行补两行，
语义完全一样（读的是同一份"用户同意没有"），不新增第二个开关。

> 顺序是硬约束：`getInstance()` 之前必须调用 —— `NaviProbeActivity` 的注释里已经写了这条，
> 照它做即可。

**单独验证**：见 §4 的 Step 0 清单（补一行日志 + 地图屏回归）。

### Step 1 —— "家 / 公司"落本地

扩展 `core/data/.../ShellSettings.kt`（里面已经有 `GeoPoint`，复用即可）：

```kotlin
data class NaviPlace(val title: String, val point: GeoPoint, val address: String = "")

// prefs 里新增四条：navi_home_title / navi_home_lat / navi_home_lon / navi_home_addr（公司同理）
fun naviHome(context: Context): NaviPlace?
fun setNaviHome(context: Context, place: NaviPlace?)
fun naviWork(context: Context): NaviPlace?
fun setNaviWork(context: Context, place: NaviPlace?)
```

存标题+坐标+地址三项就够了。**不存 POI ID**：ID 是服务端给的，跨版本/跨 Key 不保证还能解析，
坐标是"过了今天也能用"的东西，口径与现有 `lastLocation` 一致。

### Step 2 —— 搜索目的地（合包里的搜索能力，入口在全屏地图页）

合包自带的类（`javap` 确认存在）：

```
com.amap.api.services.help.Inputtips       → 输入联想（打字框）
   Inputtips(Context, InputtipsQuery)      // 不抛异常
   InputtipsQuery(String keyword, String city)
       void setCityLimit(boolean)
       void setLocation(LatLonPoint)
   void requestInputtipsAsyn()
   interface Inputtips$InputtipsListener { void onGetInputtips(List<Tip>, int) }

com.amap.api.services.help.Tip
   String getPoiID(); String getName(); String getAddress(); String getDistrict();
   LatLonPoint getPoint()   ← ⚠️ 可能是 null

com.amap.api.services.poisearch.PoiSearch  → 关键字/ID 精确检索
   PoiSearch(Context, PoiSearch$Query) throws AMapException   ← 构造就可能抛，要 catch
   void searchPOIAsyn(); void setOnPoiSearchListener(...)
   PoiItem searchPOIId(String) / void searchPOIIdAsyn(String) ← 有 ID 时优先走这条
```

**⚠️ 必须处理的一个坑**：联想结果 `Tip.getPoint()` 常常为 `null`
（"西安+钟楼"这种猜词是有坐标的，纯文本联想常常没有）。
所以不能拿 `Tip` 直接当目的地 —— 落点前要用 `PoiSearch`（优先带 ID 检索，
没有 ID 就用关键字 + 城市检索）补一次坐标。补不到就在 UI 上写"这个结果没有坐标，请换一个"，
**不要偷偷传一个近似坐标给用户**。

UI 放在**全屏地图页**：底部一条搜索条 + 展开后的联想列表。

> ⚠️ **横向预算先别信任何旧数字**：仓库里两套口径并存 ——
> `DeskDimens` 的注释以 OnePlus PKR110（794×361 dp，内容宽 678）为基准，
> `MainActivity` 的注释与这一轮真机截图用的是 Xiaomi M2102K1C（914×411 dp，左侧挖孔 137 px）。
> 无论哪台，**纵向都很紧、横向很宽**，所以搜索条做成"底部一行 + 向上展开"而不是侧边抽屉。
> Step 2 开工第一步我会 `uiautomator dump` 复核当前连的是哪台，并把实测尺寸回填到本节。

展开后的列表行高 ≥ 48 dp（手臂距离外一次点中的口径，`SideBar` 的类注释里踩过这条坑）。

### Step 3 —— 路线预览（搜索 → 预览，不进导航）

`AMapNavi.calculateDriveRoute(start, end, ways, strategy)` → `onCalculateRouteSuccess`
拿到 `AMapCalcRouteResult`，从 `getNaviPaths()` 里取路径，**只解出三个数**：

- 距离（m）、预计时长（s）、红绿灯数 —— `NaviProbeActivity` 已经打出过这三个：
  `path: 38467m / 2700s / lights=18`；
- 三条备选路线（ `AmapNaviParams.setRouteStrategy(...)` 可选）；
- 一个「开始导航」按钮和一个「算了」按钮。

自用路线的**起点**直接用 `ShellSettings.lastLocation()` ——
桌面终端是**固定**的：`ShellSettings` 的注释里已经写明"它不是当前位置 ——
设备固定在桌上，昨天的坐标今天照样能用"，所以对这台机器来说它就是当前起点，
等一次实时定位再算路只会让用户多等几秒。

代价是要**讲清楚这句话从哪来**：预览卡上标注起点为"上次定位"，而不是含糊地写"我的位置"。
一个都没有（从没定位过）时才显示"等待定位"并开始申请实时定位。

### Step 4 —— 开始导航 + 音乐压制 + 退出回来

按 §2 方案 B 的顺序执行，三件事同时做：

1. `navi.setUseInnerVoice(true)` + `navi.setControlMusicVolumeMode(MUSIC_VOLUME_MODE_DEPRESS)`；
2. 「退出导航」按钮：`AMapNaviView.onBackPressed()` → `stopNavi()` → 切回 `DeskScreen.MAP`；
3. 上滑逃生挂到导航屏（`escapeEnabled = true`，onSwipeUp 同上），理由与投屏页一致：
   **一旦 HOME 应用卡在别人的画面里，除了 adb 卸载就没有别的出口**。

是否保留侧栏：建议**导航时不显示**（挡地图）。改法很小：`chromeScreen` 的判断里排除 `NAVI`。
这条也在等你的确认（§6）。

### Step 5 —— 定位权限弹窗：你自己点

`CheckPermissionsActivity` 自己实现了 `onRequestPermissionsResult(...)`，
组件页路线下是它在管；**内嵌路线下没有它参与**，权限要我们自己申请，
现有 `AMapCanvas` 已经在用 `rememberLauncherForActivityResult` 做这件事
  （`showMyLocation` 那条链路），复用同一套写法即可。

清单里已经有了 `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` /
`ACCESS_LOCATION_EXTRA_COMMANDS`（合入后的 release 清单已确认）。
验证时我会**不预先 `pm grant`**，让弹窗出来你自己点 —— 你说过这一步你自己来。

---

## 4. 每步的真机验证清单

### 通用手法（本机约束）

> **本机 `adb logcat` 恒为空**，一条都捞不出来。
> 所以每一步的结论都必须满足三选二：①**写在屏幕上**（像 `NaviProbeActivity` 那样把结论打印出来）；
> ②**落盘到 `MapLog`**；③**截图留证**。这也是前面几轮一直在用的取证方式。

```bash
cd /d/Administrator/Desktop/home/DeskTerminal
export JAVA_HOME="D:/java"

# 编译安装
./gradlew :app:assembleDebug && \
  adb install -r app/build/outputs/apk/debug/app-debug.apk

# 取日志（debug 包 + run-as 才读得到；/sdcard 那条路径本机 shell 没权限）
adb exec-out run-as com.qingke.desk cat files/map/map.log | tail -60

# 截图与 UI 树
adb exec-out screencap -p > .verify/nav/stepX.png
adb shell uiautomator dump /sdcard/w.xml >/dev/null && adb pull /sdcard/w.xml .verify/nav/stepX.xml

# 注入触摸（本机有 root）
adb shell su -c "input tap 900 300"
adb shell su -c "input swipe 900 1400 900 700 300"

# 当前前台 Activity（判断"有没有自启别人的 Activity / 回来没回来"）
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumed"
```

### Step 0 验证
- [ ] `map.log` 里能看到 `"navi privacy pushed"`（新增一行日志），且**没有**崩；
- [ ] 地图屏回归：冷启动地图仍有图（证明补的这两行没把地图的闸门搞坏）；
- [ ] 撤回同意 → 再同意 → 地图回来（现有回归项，不能破）。

### Step 1 验证
- [ ] 录入"家"→ 杀进程 → 重进 → 家还在（`run-as` 直接读 prefs 是最硬的证据）：
      `adb exec-out run-as com.qingke.desk cat shared_prefs/desk_shell.xml | grep navi_home`；
- [ ] 录入非法值（比如只有标题没有坐标）时不崩，UI 显示"未设置"。

### Step 2 验证
- [ ] 全屏地图页底部搜索条可见、齿轮没压住它（截图）；
- [ ] 输入"钟楼"→ 联想列表 3 条内出结果，每项显示名称 + 区/地址；
- [ ] **挑一条 `getPoint()` 为 null 的结果**（log 里我会打印 `point=null`），
      选中后走 `PoiSearch` 补坐标；补到就用，补不到就显示"这个结果暂无坐标"—— **不能崩、不能静默用一个假坐标**；
- [ ] 断网（ `svc wifi disable` + `svc data disable` ）→ 显示"搜索失败"，不显示上一次的旧结果
      （口径和天气卡一致：**没数据就写没数据，不要显示旧的**）。

### Step 3 验证
- [ ] 选中一个有坐标的目的地 → 预览卡出现距离 / 时长 / 红绿灯数；
- [ ] 与预期对得上：钟楼→咸阳机场应当接近 **38.4 km / 45 min / 18 灯**
      （基准值来自 `.verify/navi-regress/N1.log`，允许因实时路况浮动 ±30%，
      浮动更大要写明原因，不要默默接受）；
- [ ] 预览卡上标着"起点：上次定位"，不冒充"当前位置"；从未定位过时显示"等待定位"，
      而不是拿一个默认值算路。

### Step 4 验证（最关键，也是最可能出问题的一步）
- [ ] 点"开始导航" → 出 3D 导航画面，**不是黑屏/只有地图没有导航 UI**；
- [ ] **横屏**：画面铺满整屏（当前机 914×411 dp / 另一台 794×361 dp，**先看 `uiautomator dump` 的实测值**），
      没有被压成竖屏版式，左边没有被挖孔切掉（截图）；
- [ ] 旋转 180°（`adb shell settings put system accelerometer_rotation 1` 或直接转手机）→
      画面跟着翻且不崩；
- [ ] **音乐压制**：QQ 音乐/网易云放歌 → 等一次导航播报 →
      ①说话瞬间音乐音量明显下降 ②播报完恢复原音量 ③导航结束后彻底恢复。
      同时抓 `adb shell dumpsys audio` 看焦点栈归属；
      压不下去就记下来，退化到 `MUSIC_VOLUME_MODE_PAUSE` 再验一次；
- [ ] **退出**：点 SDK 返回 → 回到全屏地图页；跑一段后再用**底部上滑**逃生 → 也能回来；
- [ ] 回来**两次以上**仍正常（第二次最容易挂：`AMapNavi` 是进程级单例，
      `destroy()` 是**静态方法**，少调一次下次进来就是半死状态）；
- [ ] 导航中系统来电/切后台再回来（`adb shell input keyevent KEYCODE_HOME` 或直接 `am start` 回来）。

### Step 5 验证
- [ ] 卸载重装后第一次点"开始导航" → **系统定位弹窗出现**（我不代 grant），你手动允许 / 拒绝各验一次；
      拒绝时应显示"没有定位权限，无法导航"，且能返回，不卡死；
- [ ] 拒绝后再在系统设置里打开 → 回来能继续（现有地图的 `hasLocationPermission` 检查已经这么做了）。

---

## 5. 三件必须先处理的事（与导航无关的债，但没有它们会卡住或踩雷）

### 5.1 `NaviSetting` 是第二道合规闸门，当前没覆盖
见 Step 0。这不是"要不要做"，是"不做导航起不来"。**推荐做，与 Step 1 一起提。**

### 5.2 `AmapRouteActivity` 得我们在 app 清单里声明（走 A 方案时）
jar **不带 `AndroidManifest.xml`**（5931 个条目里 0 个清单），所以 SDK 的组件不会自动合进来。
走 A 方案必须在 `app/src/main/AndroidManifest.xml` 里手写：

```xml
<activity android:name="com.qingke.desk.navi.DeskRouteActivity"
    android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|density|keyboardHidden|uiMode"
    android:resizeableActivity="false"
    android:screenOrientation="sensorLandscape"
    android:exported="false" />
```

`exported="false"` 是必须的：这是个内部组件，没有理由对外。
（走推荐的 B 方案就不需要这段 —— 又一个 B 更省事的理由。）

### 5.3 `CHANGE_WIFI_STATE` 与 README 的口径对不上（**来源是我们自己的 :map 清单**）
已经查清：合并后的 release 清单里确实有 `CHANGE_WIFI_STATE`，
但**不是合包 SDK 带进来的** —— 是 `map/src/main/AndroidManifest.xml` 里我们自己照高德官方清单抄的那一行。
`app` 清单里出现这个词的地方只是一句**注释**（写着"不保留 CHANGE_WIFI_STATE"），
于是形成了一处"文档说不保留、产物里有"的自相矛盾。

处置建议：删掉 `:map` 清单里那一行（定位/地图不需要它），让口径与 app 清单的注释一致。
这属于独立变更，我会单独提一次，不混进导航的提交里 —— 理由和之前处理 ABI 裁剪一样：
混在一起，"回归通过/不通过"的结论就说不清了。

> 顺带修正一条我早先的错误说法：上一轮我在日志里把这条归给"合包带入"，不准确，特此更正。

### 5.4 "能算路"不等于"能导航"
`.verify/navi-regress/N1.log` 只证明了**路线规划**。真正开始导航还依赖：
语音包（so 里的 `libnui` / `libneonui_shared`）能加载、
`AMapNavi.destroy()` 的释放时序、内置 TTS 在没有网络时的表现。
**这些都是必须在 Step 4 用一次真实导航（哪怕只是在楼下开 2 分钟）验证的东西，不能靠推断。**

---

## 6. 需要你拍板的三件事

1. **架构**：方案 B（内嵌，推荐）/ 方案 A（组件页）。
   我建议 B；若选 A，请把 §5.2 的 manifest 声明一起确认。
2. **导航时是否保留侧栏**：建议**不保留**（挡地图），改为导航 View 自带按钮 + 上滑逃生回来。
   你也可以要"保留但半透明"，那就得多一轮 Overlay 排版的活。
3. **"家 / 公司"怎么录入**：
   - (a) 在搜索结果里长按/点星星设为家或公司（推荐，一步到位）；
   - (b) 只在设置面板里录入（要先填地址再搜，两步）；
   - (c) 两者都要。

确认后我按 Step 0 → 5 顺序推进，**每步做完真机验证、单独提交**，验收口径与前面四轮一致。
定位权限弹窗我会留给你自己点。
