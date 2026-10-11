# 首页分页 + 地图卡布局草案（待确认，未写代码）

日期：2026-10-12
状态：**草案 / 等用户确认**。本文件不含任何代码改动，只定尺寸、结构与风险点。

## 0. 结论先行

- 首页改 2 页横滑（CarPlay 仪表盘式），底部页码圆点。
- 第 1 页 = 左大地图卡 + 右上「回家/去公司」占位卡 + 右下音乐卡；第 2 页 = 时钟卡 + 天气卡。
- 首页删掉手机投屏卡 / 日程卡 / 待办卡（侧栏的「手机投屏」入口保留不动）。
- 地图页删掉浮在地图上的卡片面板与展开/收起手柄，地图铺满内容区；这一屏保留，作为以后搜索与导航的入口。
- 地图卡：**预览模式**（不响应拖动缩放，点击进全屏地图页）、居中当前位置、zoom 17（街道级）、沿用日夜自动切换、未同意隐私时显示占位卡且不构造 MapView。
- 翻页不重建 MapView：单实例 + `beyondViewportPageCount=1` + 不可见时停渲染。
- **已核实（javap 直接读合包里的 class，不是查文档猜的）**：`com.amap.api.maps.AMap` 存在
  `setRenderFps(int)`、`setDisableRender(boolean)`、`setRenderMode(int)` —— 帧率限制与停渲染都是官方公开方法。

---

## 1. 有效区域口径（基准机 OnePlus PKR110，横屏 794 × 361 dp）

```
| 挖孔 40 | 侧栏 76 | 间距 12 |            内容区 666                |
```

| 层级 | 宽 (dp) | 高 (dp) | 说明 |
|---|---|---|---|
| 屏幕（横屏逻辑） | 794 | 361 | 2780×1264 px @560dpi → ÷3.5 |
| 挖孔区 | 40 | 361 | 由 `WindowInsets.displayCutout` 实时读，**不写死左右**（倒转 180° 会翻边） |
| 侧栏玻璃块 | 76 | 356 | 5×64 + 4×6 + 2×6，上下各余 2.5 垂直居中 |
| 内容区 | 666 | 361 | 794 − 40 − 76 − 12 |
| 内容区内边距 | 左右各 20 | 上下各 12 | 沿用 `screenPadding` / `cardAreaTopPadding` |
| **每页可用（卡片区）** | **626** | **313** | 361 − 12 − 12 − 页码行 24 |

页码行单独占 24 dp（圆点 8 + 上下各 8 留白），不挤占卡片高度。

## 2. 各卡片尺寸

### 第 1 页（626 × 313，gap 12）

| 卡片 | 宽 (dp) | 高 (dp) | 占比 |
|---|---|---|---|
| 地图卡（左） | 409 | 313 | 626 的 65.4%（≈ 三分之二） |
| 右列 | 205 | 313 | weight 1 : 2 |
| ├ 回家/去公司（占位） | 205 | 150.5 | 右列两等分，gap 12 |
| └ 音乐卡 | 205 | 150.5 | 同上 |

- 快捷卡内部：卡内 padding 14 → 内容 177；两个按钮（回家 / 去公司）横排 gap 10 → **各约 83.5 × 122**。触控区 83.5×122 ≫ `minTouchTarget` 64 ✓
- 音乐卡在 205 宽下走 **无封面分支**（`canKeepArtwork`: 177 − 60 − 12 = 105 < 三键 120），三键 40 dp 不变，控制键不会被牺牲。

### 第 2 页（626 × 313，gap 12）

| 卡片 | 宽 (dp) | 高 (dp) |
|---|---|---|
| 时钟卡 | 307 | 313 |
| 天气卡 | 307 | 313 |

- 两卡等分（**备选**：沿用第一页 2:1 节奏 → 时钟 205 / 天气 409，见第 8 节待确认 A）。
- 时钟卡变大后，时钟字号上限要放开：现在 `DeskDimens.clockTime = 56.sp` 是按 88 dp 内容区定的；307×313 的卡内容区约 279×285，建议新增 `clockTimeLarge = 96.sp`（仍按可用高度/宽度反推，见 `ClockBody` 现有逻辑）。

### 页码圆点

- 未选中：直径 6 dp，白色 @40%；选中：直径 8 dp，白色 @90%；间距 10 dp；整行居中。
- 可点击切换（点击区 32 × 24），与滑动并存。

---

## 3. 地图卡规格

| 项 | 取值 | 依据 |
|---|---|---|
| 交互 | 预览模式：`isScrollGesturesEnabled / isZoomGesturesEnabled / isRotateGesturesEnabled / isTiltGesturesEnabled` 全关 | 用户要求"不响应拖动缩放" |
| 点击 | 整卡点击 → `DeskScreen.MAP`（全屏地图页） | 避免与翻页手势冲突 |
| 缩放级别 | zoom **17**（街道级） | 高德 zoom 3–20；16 是街区、17 是街道 |
| 居中 | 首次拿到定位后 `animateCamera(newLatLngZoom(loc, 17f))` 一次，**之后不跟随** | 常驻终端不需要持续跟随，省电 |
| 定位前 | 用上次已知位置（持久化到 `ShellSettings`）作 `initialCenter`；仍没有则显示"定位中"占位 | 避免冷启动先闪一下全国/整城视角 |
| 蓝点 | `MyLocationStyle.LOCATION_TYPE_SHOW`（0，只显示不移动地图），拿到首帧后由我们手动 moveCamera | `MyLocationStyle` 常量已 javap 核实：SHOW=0 / LOCATE=1 / FOLLOW=2 |
| 日夜 | 沿用 `MapPane` 的 `rememberAutoMapStyle()`（日出日落，西安坐标，天气 API 注入优先） | 已实现、已真机验证 |
| 隐私 | 未同意 / 已撤回 → 渲染占位卡，**不构造 MapView**；`AMapPrivacy.agreed` 是可观察流，撤回立即生效 | 已实现 |
| 控件 | 缩放按钮、比例尺关；**罗盘关**（旋转手势已关，罗盘不会出现，留着只是多一个可点 view）；高德 logo 保留 | 合规：logo 不可遮挡 |

渲染尺寸变化：首页卡 409×313 ↔ 全屏页 666×361（见第 5 节保活）。

---

## 4. 手势冲突：三层处理

横滑翻页（Pager）与地图触摸天生打架，处理分三层，逐层收紧：

1. **SDK 层**：`uiSettings` 四类手势全关 → 地图自己不再解释任何手势。
2. **View 层**：`mapView.setOnTouchListener { _, _ -> true }` —— View 侧直接吞掉事件，MapView 的
   `onTouchEvent` 根本不会被调用。Compose 侧不受影响：触摸先经 `AndroidComposeView` 做 hit test
   分发给 `PointerInputModifier`，未消费才下沉到子 View。
3. **Compose 层**：地图卡外包一层透明 `clickable` → 点击进全屏页。`clickable` 只在确认为 tap 时
   consume，横向拖动超过 `touchSlop` 时不消费，滑动照常冒泡给 Pager → **点击与翻页共存**。

兜底预案（若真机上第 2 层导致 Compose 侧也收不到事件）：改为 `pointerInput(Unit) { detectTapGestures { ... } }`
放在 AndroidView 的 **父** Box 上，并把 MapView 的 `isClickable` 设为 false。真机验证项见第 9 节。

其它手势边界：
- 页码圆点、快捷卡两个按钮、音乐卡三键都在 Pager 内部，靠 `clickable` 的"只消费 tap"规则与滑动共存。
- 侧栏、设置浮层、Shizuku 角标是浮层，不在 Pager 内，不受影响。

---

## 5. 地图保活：不销毁、不重建，不可见就停渲染

四件事一起做，缺一不可：

### 5.1 单实例宿主（核心）

把 `MapView` 的持有者从"某个组合内"提升到 **Activity 作用域**（`MapViewHost`，在 `DeskRoot` 层 `remember`）：

- `onCreate(Bundle())` 只调一次；
- `AndroidView` 的 `factory` 返回**同一个实例**（attach 前先 `(view.parent as? ViewGroup)?.removeView(view)`，
  否则抛 "already has a parent"）；
- `onDispose` **只解除挂载，不调 `onDestroy()`**；
- `onDestroy()` 只在 Activity 真正销毁时调一次（现有代码在 `DisposableEffect.onDispose` 里调，翻页/切屏就会触发，这正是要改掉的地方）；
- `onResume / onPause` 继续由 Activity 生命周期转发（现有 `LifecycleEventObserver` 逻辑保留）。

### 5.2 Pager 组合窗口

`HorizontalPager(pageCount = 2, beyondViewportPageCount = 1)`：两页始终都在组合窗口内，
翻到第 2 页时第 1 页**不会**被 dispose，AndroidView 不会走 `onDispose`，MapView 只是滑到屏幕外。
（默认 `beyondViewportPageCount = 0` 时，离屏页会被 dispose —— 这就是"翻页重建"的根因。）

### 5.3 可见性驱动的渲染开关（省电关键）

`AMap` 上有两个官方方法（javap 已核实存在于 11.3.100 合包）：

- `setRenderFps(int fps)` —— 限制渲染帧率；
- `setDisableRender(boolean)` —— 直接停渲染。

策略：

| 状态 | 动作 |
|---|---|
| 第 1 页可见 / 全屏地图页在前台 | `setDisableRender(false)` + `setRenderFps(15)`（预览卡）或 30（全屏页） |
| 翻到第 2 页、切到其它屏、应用进后台 | `setDisableRender(true)` |

帧率取值参考：高德车机 SDK 的 `RenderFpsConstants` 官方常量 `NORMAL = 15`、`NAVI = 10`、
`ANIMATION = 30`、`GESTURE = 40`。预览卡没有动画，15 fps 足够且不刺眼。

**兜底**：若 `setDisableRender(true)` 之后回来画面变白/黑/残影（真机验证项 6），退回只用
`setRenderFps(1)`（近乎停帧但保持引擎运行），不再用 disable。

### 5.4 跨屏重挂（首页卡 ↔ 全屏页）

共享同一实例意味着从首页点进全屏页时，MapView 会被 detach 再 attach 到更大的容器（409×313 → 666×361），
尺寸变化会触发重新布局 —— **属于"不重建但重挂"**，地图引擎状态（camera、图层）不丢。

风险与兜底：若重挂后出现黑屏/白屏/渲染卡死，退回 **双实例**（首页一个、全屏页一个，各自独立生命周期）。
代价是首次进全屏页有一次构造开销（约 1 秒），导航功能本来就走全屏页，可接受。

---

## 6. 删除清单

| 对象 | 处理 |
|---|---|
| 首页：手机投屏卡 / 日程卡 / 待办卡 | `FakeHomeRepository.cards()` 里去掉这三条数据 |
| 侧栏「手机投屏」入口 | **保留**（`defaultShellItems` 不动） |
| `shell/MapCardPane.kt` | 整个文件删除 |
| `DeskRoot` 的 `mapPaneCollapsed` 状态与其调用 | 删除 |
| `DeskDimens` 的 `mapPaneWidth / mapPaneCollapsedWidth / mapPaneHandleHeight / MAP_PANE_COLUMNS` | 删除 |
| `HomeCardKind.PROJECTION / AGENDA / TODO` 枚举值 | **保留**（以后可能加回，删了要连带改 `HomeCardTile` 的 when） |

---

## 7. 预计改动的文件

| 文件 | 改动 |
|---|---|
| `core/ui/DeskDimens.kt` | 删 mapPane*；新增页码行尺寸、圆点尺寸、地图卡与大时钟字号上限 |
| `core/data/HomeRepository.kt` | 假数据去掉三张卡 |
| `core/data/ShellSettings.kt` | 新增"上次已知位置"（lat/lon）持久化 |
| `map/AMapCanvas.kt` | 新增预览模式参数（关手势 / 屏蔽 touch）、`renderFps`、`renderEnabled`、`initialCenter/zoom`、首次定位回调居中 |
| `map/MapPane.kt` | 新增"预览卡"入口（沿用现有的隐私闸门与日夜切换） |
| `shell/HomeScreen.kt` | 改分页：`HorizontalPager` + 页码行 + 两页布局 |
| `shell/DeskRoot.kt` | MAP 屏只挂地图（铺满内容区）；删浮层与手柄；MapView 宿主提升到本层 |
| `shell/MapCardPane.kt` | 删除 |
| `feature/home/HomeCards.kt` | 新增「回家/去公司」占位卡；时钟卡支持大字号档 |

---

## 8. 待确认的四个选择点

**A. 第 2 页两卡比例**：等分 307×313（对称，时钟字最大）／ 2:1 → 时钟 205 + 天气 409（与第一页节奏一致）。
草案默认**等分**。

**B. 地图页地图铺到哪里**：铺满内容区 666×361（挖孔那 40 dp 露底色，与首页一致）／ 铺满整屏 794×361
（含挖孔下，视觉更沉浸，但挖孔会压在地图上）。草案默认**内容区**。

**C. 地图实例**：单实例重挂（保活最强）／ 双实例（最稳，首次进全屏页有一次构造）。草案默认**单实例**，
真机验不过就退双实例。

**D. 未同意隐私时首页地图卡的样子**：精简占位（一行说明 + 「查看隐私政策并开启」，点了跳全屏页看完整政策）／
卡内直接渲染完整 `AMapPrivacyCard`（409×313 放得下，少一步跳转）。草案默认**精简占位**。

---

## 9. 真机验证清单（写完代码逐条打勾）

1. 两页横滑正常，页码圆点同步；点圆点能翻页。
2. 在地图卡上横滑 → 地图不动、页面翻了；点地图卡 → 进全屏地图页。
3. 地图卡 zoom 17 街道级、居中当前位置；日夜切换（等日落或改系统时间验证 NORMAL/NIGHT）。
4. 未同意 / 撤回同意 → 首页与地图页都是占位卡，`MapLog` 中**无** `MapView attached`。
5. 来回翻页 20 次 → `MapLog` 中 `MapView attached` 计数 = 1、`dispose` 计数 = 0。
6. 翻到第 2 页静置 5 分钟 → 画面恢复后地图正常（验证 `setDisableRender`）；对比开关前后的截图像素变化。
7. 侧栏左右切换 → 分页、页码行、齿轮、Shizuku 角标、设置浮层全部正确镜像。
8. 防烧屏微位移仍在工作（静置 10 分钟截图像素变化 > 0）。
9. 首页/地图页来回切 10 次 → MapView 不泄漏、不重复 `onCreate`。

---

## 10. 本次核实的原始证据

```
$ javap -classpath navi-3dmap-location-search-11.3.100_...jar com.amap.api.maps.AMap | grep -iE "fps|render"
  public void runOnDrawFrame();
  public void setCustomRenderer(com.amap.api.maps.CustomRenderer);
  public void setRenderFps(int);
  public void setRenderMode(int);
  public void setDisableRender(boolean);

$ javap -constants ... com.amap.api.maps.model.MyLocationStyle | head
  public static final int LOCATION_TYPE_SHOW = 0;
  public static final int LOCATION_TYPE_LOCATE = 1;
  public static final int LOCATION_TYPE_FOLLOW = 2;
  ...

$ javap ... com.amap.api.maps.AMap$OnMyLocationChangeListener
  public abstract void onMyLocationChange(android.location.Location);

$ javap ... com.amap.api.maps.TextureMapView     # 备选：与 MapView 同签名，内部用 TextureView
  public com.amap.api.maps.TextureMapView(android.content.Context);
  public com.amap.api.maps.AMap getMap();  onCreate/onResume/onPause/onDestroy/onLowMemory/onSaveInstanceState
```

`TextureMapView` 的存在是一个备选：如果 `MapView` 在 Compose / Pager 里出现 SurfaceView 的层级或黑屏问题，
可以整体换成它（`getMap()` 与生命周期方法签名完全一致，上层代码不用改）。
