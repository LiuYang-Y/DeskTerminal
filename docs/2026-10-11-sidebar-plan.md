# 侧边栏改造方案（草案，待确认后实施）

基准机 OnePlus PKR110 / Android 16，横屏逻辑 **794 × 361 dp**，density 3.5，fontScale 1.0，
左侧挖孔 140 px = 40 dp（`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`）。

本文件是**方案**，不是实现记录。确认后再动代码；动完把结论回写到
`core/ui/.../DeskDimens.kt` 的文件头纵向预算注释里。

---

## 0. 硬约束（三条，先记住）

1. **6 个 64 dp 竖排物理上放不下**：6 × 64 = 384 dp > 361 dp，差 23 dp，还没算间距和留白。
   361 ÷ 6 = 60.2 dp 是理论上限。所以只能在「减少入口」和「降低单格高度」之间取舍。
2. 底栏移走**释放纵向 82 dp**（`dockBarHeight`），顶栏若一起移走**再释放 38 dp**；
   横向则被侧栏吃掉 64 + 12 = 76 dp。
3. 日程卡之后会取消，**不为它做任何额外设计**，卡片区默认按 5 张卡算。

---

## 1. 侧栏位置可设置（左 / 右）

- **存储**：`SharedPreferences`。项目已有三处先例（`ShizukuBootPrompt`、`ShizukuDebugOverride`、
  `AMapPrivacy`），不引入 DataStore 依赖。存 `sidebar_side = LEFT | RIGHT`。
- **切换入口**：两个都做 —— 长按侧栏 500 ms 弹「移到左侧 / 移到右侧」（最快），
  设置页里再放一个开关（可发现性）。
- **镜像不能靠手改多处**：抽一个 `enum class DeskSide { LEFT, RIGHT }`，
  所有左右相关的量（`Arrangement`、padding 的 `start/end`、`Alignment`、手柄朝向、指示位置）
  全部从这一个枚举派生。**布局里不允许出现第二处独立的左右判断** ——
  这是「切完错位」的唯一根因。
- 收起手柄、卡片浮层、状态指示三者都跟着枚举走，不单独写死。

## 2. 挖孔区域填色

- **不假设左右**。运行时读 `WindowInsets.displayCutout.getLeft/Right(LocalDensity.current)`；
  手机倒转 180° 时它自己翻到另一侧，代码不用改。
  兜底：inset 为 0（无挖孔或模式为 NEVER）时带宽为 0，布局自动退化。
- 挖孔带 = 该 inset 宽 × 全屏高，**整条铺背景色 / 主题色，不放任何内容**。
- 侧栏与挖孔**同侧**：侧栏从 x = 0 起算，宽 64 dp > 40 dp 挖孔带，天然盖住 → 视觉上一整块。
- 侧栏与挖孔**异侧**：挖孔带单独铺色（与侧栏玻璃不同色，避免看起来像半截侧栏）。
- 现状 `IdleGuard` / `DockBar` 上的 `windowInsetsPadding(WindowInsets.displayCutout)`
  **整体避让要拆掉**，改成「挖孔带单画 + 内容区左右 padding = max(screenPadding, cutoutInset)」。
- 内容区可用宽：同侧 698 dp，异侧 678 dp。

## 3. 玻璃滑块（竖向）改写评估

**结论：难度中低。** 签名已用 `javap` 反查本机 `backdrop-android-2.0.0.aar` 核实，不是照抄旧示例。

| API | 核实到的签名 |
|---|---|
| `rememberLayerBackdrop` | `(GraphicsLayer?, ContentDrawScope.() -> Unit, Composer, Int, Int)` |
| `Modifier.layerBackdrop` | `(LayerBackdrop)` |
| `Modifier.drawBackdrop` | 含 `highlight / shadow / innerShadow / exportedBackdrop: LayerBackdrop` 等 |
| `lens` | `(scope, refractionHeight, refractionAmount, depthEffect, chromaticAberration)`；四角半径从 shape 取，`RoundedCornerShape(percent = 50)` 竖向胶囊合法 |

- **库与方向无关**，竖向不需要改库，只把滑移轴从 x 换成 y。
- **唯一真正的风险是 glass-on-glass 自采样**：官方教程明确警告在 `drawBackdrop`
  之后再 `layerBackdrop` 会形成反馈循环 → RenderThread SIGSEGV（`exportedBackdrop` 就是为此存在）。
  **本项目可以完全绕开**：让侧栏和指示块**共用同一个 `LayerBackdrop` 源**（内容区那块），
  两者都从原始内容取样，指示块用更亮的 surface 蒙版 + `highlight` 拉层次，不做二次采样。
  循环不存在，这个坑就消掉了。
- **真实工作量在手势和动画**：
  - `Modifier.offset { IntOffset(0, y.roundToPx()) }` —— lambda 版走 placement 阶段，动画不触发重组；
  - `Animatable` 驱动切换，拖拽跟手用 `snapTo`，松手按 y 反算 index 再 `animateTo`；
  - 手势用 `pointerInput` + `detectVerticalDragGestures`。
- **其余风险**：拖拽期间每帧重采样 + 模糊 + 折射（采样区约 196 × 182 px，量级可控，
  但常亮设备必须实测帧率与温度）；`blur` 需 API 31、`lens` 需 API 33，低版本静默失效；
  指示块传给 lens 的 shape 必须与实际绘制形状同参，否则折射边缘错位；
  `EdgeSwipeUp` 在底部、侧栏在左右边缘，无冲突。
- **降级路径**：指示块退化为纯色圆角块（`background(color.copy(alpha))`），
  保留滑动动画，去掉 blur / lens。视觉损失可接受。
- 玻璃仍只允许 `DeskGlass.kt` 一个文件 import 第三方库。

## 4. 侧栏高度方案（触控目标尺寸）

公式：`n × S + (n-1) × g + 上下留白 ≤ 361`

| 方案 | 项数 | 触控目标 | 间距 | 上下留白 | 占用 | 图标 | 标签 |
|---|---|---|---|---|---|---|---|
| 现状 | 6 | 64 × 64 | 12 | 0 | **384 dp** | 32 | 12sp |
| — | — | — | — | — | 超出 23 dp | — | — |
| **A** | 6 | 54 × 54 | 2 | 10 | 354 dp | 28 | 无 |
| **B** | 6 | 52 × 52 | 5 | 8 | 353 dp | 26 | 无 |
| **C** | 6 | 48 × 48 | 8 | 12 | 352 dp | 24 | 无 |
| **D** | 5 | 64 × 64 | 4 | 10 | 356 dp | 32 | 12sp 可留 |

- A / B / C 都必须**去掉文字标签改成图标 only** —— 64 dp 见方里塞图标 + 12sp 标签是今天底栏的做法，
  缩到 48–54 dp 就塞不下了。
- C 的 48 dp 是 Material 官方最小触控尺寸，也是最宽松的一档。
- D 是**唯一能保住 64 dp + 文字标签**的方案，代价是少一个入口。
  该砍谁：「智能家居」（尚未实现）>「时钟」（卡片区已有独立时钟卡）>「音乐」（卡片区已有音乐卡）。
- 备选（未列入表）：不等高分组 4 × 56 + 2 × 40 = 304 dp + 间距 30 + 留白 12 = 346 dp。
- **不推荐**：侧栏可滚动（6 × 64 需滚 23 dp，手感割裂）。

## 5. 底栏移走后的布局草案

### 5.1 首页卡片网格

纵向可用 = 361 − `gridTopPadding` 6 − 底部留白 12 = **343 dp**（顶栏移走后）。
保留顶栏则为 305 dp。

| 草案 | 列 × 行 | 单卡尺寸 | 说明 |
|---|---|---|---|
| 现状 | 3 × 2 | 230 × 109.5 | 底栏 82 + 顶栏 38 |
| **H1** | 3 × 2 | **211 × 165.5** | 高度 +51%，6 张（现 5 张）全见，最舒展 |
| **H2** | 3 × 3 | 211 × 106 | 9 格；需把 `cardMinHeight` 108 下调到 **104** 才反推出 3 行 |
| **H3** | 4 × 2 | 155 × 165.5 | 8 格，5 张卡占 1.5 行，留 3 格给后续 |
| H4（若留顶栏） | 3 × 2 | 211 × 146.5 | 折中 |

### 5.2 地图布局（沿用方案 C：地图铺满 + 卡片浮层 + 可收起）

地图铺满整屏（挖孔带压在其上），侧栏常驻。浮层高 309 dp（361 − 上下各 26）。

| 草案 | 浮层宽 | 列 × 行 | 单卡 | 可见卡片 | 地图露出 |
|---|---|---|---|---|---|
| **M1** | 240 | 1 × 2 | 212 × 148.5 | 2 张 | **458 dp** |
| **M2** | 348 | 2 × 2 | 154 × 148.5 | 4 张 | 350 dp |
| **M3** | 460 | 3 × 2 | 136 × 148.5 | 6 张全见 | 238 dp |

收起态：浮层滑出，只剩**手柄 24 × 72 dp** 贴边垂直居中，地图露出 690 dp。

## 6. Shizuku 状态指示重新安排

| 方案 | 位置 | 高度成本 | 代价 |
|---|---|---|---|
| S1 | 侧栏顶部独占一格 | 占 36 dp | 侧栏可用高 361 → 325 dp，6 项被迫从 52 再降到 48 dp |
| **S2** | 右上角悬浮胶囊 | **0**（不占实位） | 压住右上角内容一角（地图布局下与地图自带控件同区） |
| **S3** | 并入侧栏某一项做角标 | **0** | 需新增一个「系统」入口，或替换现有某项 |

指示的语义是「常驻预警」——藏进设置页就失去意义，所以只在这三档里选。

## 7. 日程卡

后续取消。**不做任何额外设计**，不为它留位置、不为它调字号、不为它写分支。

---

## 待确认清单

1. 侧栏高度：A（6 项 54dp）/ B（6 项 52dp）/ C（6 项 48dp）/ D（5 项 64dp，砍哪个入口）
2. 首页网格：H1（3×2 大卡）/ H2（3×3，需改 cardMinHeight）/ H3（4×2）
3. 地图浮层：M1（240）/ M2（348）/ M3（460）
4. Shizuku 指示：S1（侧栏顶）/ S2（右上悬浮）/ S3（侧栏项角标）

---

## 附：高德 Android SDK 官方核实结论（2026-10-11）

- **版本与坐标**：`com.amap.api:3dmap-location-search`，Maven Central 最新固定版
  **10.1.300_loc6.4.9_sea9.7.4**（地图 10.1.300 / 定位 6.4.9 / 搜索 9.7.4）。
  官方「相关下载」页列的 3D 地图 V11.3.100 是**离线 SDK 包版本号，Maven 上没有 11.x**，
  要用只能下 zip 手动放 libs。
- **Key**：控制台创建应用 → 添加 Key → 服务平台「Android 平台 SDK」→ 填
  **包名（必须与 applicationId 完全一致，否则 INVALID_USER_SCODE）** + 发布版 SHA1 + 调试版 SHA1。
  一个 Key 只能绑一个应用。本机调试签名 SHA1：
  `32:81:66:DC:F3:8B:F8:CB:1C:11:F0:5E:37:85:10:B3:06:8F:40:A9`
- **个人开发者免费额度**：基础 LBS 服务 150,000 / 月，基础地图定位 1,500,000 / 月，
  基础搜索服务 5,000 / 月。官方 FAQ：个人认证开发者可用除「智能硬件定位服务」外所有产品。
- **导航 SDK**：官方未明文禁止个人开发者；但**服务协议 3.4 条禁止将服务用于车载场景
  （含集成在车载设备或用于投射至车载设备的应用），需另行书面许可** ——
  本项目是桌面终端 / CarPlay 式形态，**这条是实质风险**，上导航前必须确认。
- **隐私合规**：`MapsInitializer.updatePrivacyShow` / `updatePrivacyAgree` 必须在
  **构造 MapView 之前**调用，否则白屏；SDK 只在**进程内**记忆该状态，冷启动需补报
  （已在 `AMapPrivacy.applyIfAgreed()` 里处理）。
- `:map` 模块已建好并通过编译，so（`arm64-v8a` 29.6 MB / `armeabi-v7a` 22.1 MB）与
  `assets/map_assets/**` 均已并入 APK（74.1 MB）。**等 Key 才能出图。**
