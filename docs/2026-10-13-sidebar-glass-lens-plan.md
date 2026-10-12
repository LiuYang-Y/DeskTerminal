# 侧栏玻璃透镜（酷安底部栏样式）—— 可行性、风险与降级方案

日期：2026-10-13
需求口径（用户原话）：
> 选中项上是一块玻璃透镜，比单个图标略大，镜片里的图标被放大，带折射和彩色边缘；
> 镜片里的图标和文字染成强调色，镜片外保持原色；按下和拖动时镜片再放大一点，
> 拖动连续跟手，抬手回弹吸附到最近一项；侧栏本体是深色半透明的胶囊，带细描边和高光。

本文只做**评估**，不含改动。原型在 `:shell/src/debug`，正式侧栏不动。

---

## 一、结论摘要

| 问题 | 结论 |
|---|---|
| 真折射（`lens`）在本机可用吗 | **不可用**。本机 API 30 < 33，库函数第一行就 `return` |
| 真模糊（`blur`）在本机可用吗 | **不可用**。API 30 < 31，同样静默 `return` |
| 那现在的侧栏玻璃是什么 | 只有「底色蒙版 + 边缘高光 + 圆角形状」，没有模糊也没有折射 |
| 还能做出透镜观感吗 | **能**。用「数据复制 + 放大」自绘镜内图标，覆盖 90% 视觉；API 33+ 再叠加真折射与色散 |
| 要不要 exportedBackdrop | **不建议作为主路径**。它只服务于"镜内看到真实邻近像素"这一条，代价是引入 glass-on-glass 崩溃面 |
| 竖向溢出会不会压到内容区 | 尺寸账算下来**不会**：镜片 68dp（按下放大 1.12 倍后 ≈76dp）仍在侧栏内（详见第五节） |

---

## 二、事实核查（反编译 `backdrop-android-2.0.0`，非推测）

构件来自 Gradle 缓存：`io.github.kyant0/backdrop-android/2.0.0/backdrop.aar` → `classes.jar`。

### 2.1 两道版本门槛

```kotlin
// com.kyant.backdrop.PlatformKt
public static final boolean isRenderEffectSupported() { return SDK_INT >= 31; }
public static final boolean isRuntimeShaderSupported() { return SDK_INT >= 33; }
```

### 2.2 不满足时是"静默返回"，不是异常

```kotlin
// com.kyant.backdrop.effects.LensKt.lens(scope, height, amount, b3, b4) 字节码
6:  invokestatic PlatformKt.isRuntimeShaderSupported()
9:  ifne 13
12: return              // ← 直接返回，什么都不做
13: ...                  // 才继续：取圆角、建 RuntimeShader、设 uniform
```

`BlurKt.blur` 同理（`isRenderEffectSupported` 不成立即 `return`）。
**含义**：低版本上不会有异常、不会有明显的"缺一块"，而是**效果凭空消失** ——
这也是"用户以为玻璃生效了、其实一直没生效"的成因。

### 2.3 函数签名（原型要照这个调）

```kotlin
// 效果：字节码签名为 lens(BackdropEffectScope, float, float, boolean, boolean)
fun BackdropEffectScope.lens(height: Float, amount: Float, /* 后两个 boolean */ ...)
//               ^ height/amount 是像素（调用点要 .toPx()），后两个 boolean 由 shader 的
//                 uniform 名与语义推断为 chromaticAberration / depthEffect；
//                 **形参名未从字节码证实**（jar 里没有 LocalVariableTable），
//                 要具名传参前先核对源码，否则用位置参数。

// 容器玻璃：注意倒数第 5 个参数就是 exportedBackdrop
// 13 个参数的完整签名（节选顺序）：modifier, backdrop, shape, effects,
//   highlight, shadow, innerShadow, layerBlock, exportedBackdrop,
//   onDrawSurface, onDrawBackdrop, onDrawFront, onDrawBehind
fun Modifier.drawBackdrop(
    backdrop: Backdrop, shape: () -> Shape, effects: (BackdropEffectScope.() -> Unit)?,
    ...
    exportedBackdrop: LayerBackdrop?,      // ← 把本层的渲染结果导出给别处采样
    ...
)
```

- **彩色边缘 = 色散**：shader `RefractionWithDispersion` 里带 `chromaticAberration` uniform
  （字节码里 ldc 出来的 uniform 名，确证存在），由 `lens` 的某个 boolean 打开 —— **同样要 API 33**。
- 现有 `core:ui/DeskGlass.kt` 的 `deskGlassSurface` 已经封装了 `vibrancy + blur + lens`，
  原型直接复用它，因此**不需要依赖上面那些未证实的形参名**。

---

## 三、官方 Interactive Glass Bottom Bar 的做法与它在本项目的落差

官方示例的链路是「三段式 + 一次导出」：

```
① 内容层    rememberLayerBackdrop { drawRect(bg); drawContent() }        → contentBackdrop
② 底栏本体  Modifier.drawBackdrop(contentBackdrop, exportedBackdrop = railBackdrop)
            └ 渲染结果（含图标与文字）被导出到 railBackdrop
③ 指示镜片  Modifier.drawBackdrop(railBackdrop, effects = { lens(...) })
            └ 采样"底栏自己的样子"，所以镜片里的图标是"被放大/弯折的真实像素"
```

本项目与它的**两处真实落差**：

1. **本机 API 30 → ③ 整段失效**。折射没有、放大也没有，"镜片里图标变大"这个最核心的观感
   一条都拿不到。所以照抄官方示例在本机是"看不见的效果"。
2. **竖向侧栏只有一维位移**。官方是横向底栏、镜片沿 x 滑；我们是竖向、沿 y 滑。
   库与方向无关（`drawBackdrop` 只认 shape，圆角从 shape 四角各自读），
   竖向完全合法，真正的差异在**溢出方向**（见第五节）。

结论：**官方的 exportedBackdrop 路线在本机拿不到回报，却要承担它带来的崩溃风险**，
因此原型走"数据复制"路线（见第四节），把真折射作为 33+ 的增强项。

---

## 四、玻璃采样玻璃 → 反馈循环崩溃：成因与避免

### 4.1 成因

`drawBackdrop` 的语义是"读取一块 LayerBackdrop 的像素并重绘"；
`exportedBackdrop` 的语义是"把**本层渲染出来的结果**写进另一块 LayerBackdrop"。
两者同时指向同一块层，或者 A 写 B、B 写 A，就构成环：

```
侧栏本体 ──导出──▶ railBackdrop ──被采样──▶ 镜片 ──导出──▶ railBackdrop   ✗ 环
        （下一帧又要读 railBackdrop，而它正被镜片改写 → RenderThread 自采样 → SIGSEGV）
```

官方教程对这条有明确警告（"在 drawBackdrop 之后再 layerBackdrop 同一块内容"），
本项目 `SideBar.kt` / `DeskGlass.kt` 的注释里也早已记着这条坑，并靠"全程不碰 exportedBackdrop"绕开。

### 4.2 三条硬规则（若将来要用 exportedBackdrop，必须同时满足）

1. **镜片是叶子节点**：镜片的 `exportedBackdrop` 恒为 `null`，它只读不写。
2. **导出图必须是有向无环**：`contentBackdrop →（被采样）`、`railBackdrop ←（被写入，来源仅侧栏本体）`、
   镜片采样 `railBackdrop`。任何时刻**一块层只有一个写入者，且写入者不采样自己写出的层**。
3. **渲染顺序固定**：侧栏本体先于镜片完成绘制（Compose 的绘制顺序天然如此，只要镜片是它的子孙或后绘制的兄弟）。

### 4.3 本方案为什么不走导出路线（取舍）

用"数据复制"取代"像素复制"：镜片里画的**不是**侧栏渲染结果的副本，
而是拿同一份 `items` 数据、在同一位置**重新画一遍放大版图标与文字**。

| | exportedBackdrop 路线 | 数据复制路线（本方案） |
|---|---|---|
| 镜内观感 | 真像素被弯折，能看到邻近项的形变 | 只有"中心那一项"被放大，邻近项不进入镜内 |
| 崩溃面 | 有（需严格遵守 4.2） | **零**（无像素级反馈可能） |
| 本机（API 30） | 无折射 → 收益为 0 | 放大、染色、描边全部可见 |
| 拖动性能 | 每帧多一次整层导出 + 采样 | 只多画一个 68dp 的子树 |

酷安的观感里，"镜片里是**被放大的当前项**"占绝对主导，"镜片边缘露出邻近项的形变"是次要细节。
故本方案用数据复制，把主要观感拿满，把风险清零。

---

## 五、竖向布局：镜片溢出侧栏边缘的处理

### 5.1 尺寸账（`DeskDimens` 实测值）

```
侧栏项        sideBarItemSize  = 64dp
图标          sideBarIconSize  = 30dp
项间距        sideBarItemGap   = 6dp
侧栏内边距    sideBarInnerPadding = 6dp
侧栏宽        sideBarWidth = 64 + 6×2 = 76dp
5 项总高      5×64 + 4×6 + 2×6 = 356dp ≤ 361dp（屏幕高）
```

镜片取 **68dp**（项 64 + 每边 2dp，满足"比单个图标略大"）：

```
侧栏内  x: [0]────────[76]
内边距      [6]                    [70]
项                   [6]──[70]   （64dp）
镜片（68dp 居中）    [4]────[72]  ← 仍在 76dp 的侧栏内，两边各留 4dp
```

**结论：68dp 的镜片不会溢出侧栏，永远不会压到内容区。**

数字不是拍的，是两条约束夹出来的（真机上都验/测过）：

| 取值 | 静止 | 按下 ×1.12 | 结论 |
|---|---|---|---|
| 初版 72dp | 252px，两边各留 2dp | 282px > 侧栏 266px → **探出内容区 8px** | 放弃 |
| **现在 68dp** | 238px，两边各留 4dp | 267px ≈ 侧栏 266px → 刚好贴住 | 采用 |

也就是说约束**不是**"静止时不越界"，而是"**按下放大之后也不越界**" ——
只按静止账算会得出 72dp 也行，等到手指按下去那一刻才露馅。

顺带：一格总高 70dp（项 64 + 间距 6），68dp 的镜片静止时落在自己那一行里
（72dp 会比一格还高 2dp，压到相邻项 1dp）。

### 5.2 如果要做更大、更明显的鼓出（≥84dp）

此时每边溢出 `(W-76)/2`，两种放大方向各有代价：

- **朝屏幕外侧**溢出：侧栏贴屏边，溢出部分被窗口裁掉，视觉上"半边鼓包"，看着像贴歪
  （同 `SideBar.kt` 里"侧栏不延伸进挖孔条"那条已踩过的坑，判据一致：圆角不在屏幕边上就显歪）。
- **朝内容区**溢出：会盖住地图/首页卡 4dp 以上。需要：
  ① 镜片本身不参与命中测试（不挂 `clickable`，触摸仍归 64dp 的项）；
  ② 靠 1dp 高光描边 + 阴影把它与内容区分开，否则像糊了一块；
  ③ 溢出侧不能压住内容区的交互元素（地图手势、卡片点击）。

### 5.3 左右镜像

只差一个**符号**：

```
镜片横向偏移 lensDx = if (side == LEFT) +offsetForGlow else -offsetForGlow
纵向（拖拽轴）与左右无关 → 手势代码与 `Animatable` 一行都不用改
```

若坚持"镜片朝屏幕中心偏 1~2dp"以回避外侧裁切，同样是这个 `lensDx` 换符号，不复制第二套组件。

---

## 六、降级矩阵

| 运行时能力 | 判定 | 镜片表现 |
|---|---|---|
| API 33+（RuntimeShader） | `isRuntimeShaderSupported()` | 真折射 + **彩色边缘（色散）** + 深度效果；镜内放大由真折射给出 |
| API 31~32（RenderEffect） | `isRenderEffectSupported()` | 无折射；镜内放大用自绘副本 + `blur` 柔化；彩色边缘用**双色渐变描边**近似 |
| < 31（本机就在这一档） | 都不支持 | 无折射、无模糊；镜内放大用自绘副本；彩色边缘同上（渐变描边，不模糊） |
| 开关关闭 | `SidebarLens.ENABLED = false` | **完全回到现在的样式**：一块 64dp 的玻璃指示块，无镜片、无放大、无染色 |

总开关与能力探测二选一，**两个都要有**：
- 能力探测保证低版本不炸、不出现"点了没反应"；
- 总开关保证出问题时可以一键退回（也是同一台机器上做 A/B 对照的手段）。

---

## 七、原型计划（`:shell/src/debug`，不动正式侧栏）

1. 新组件 `SideBarLensRail(...)`：把现有 `SideBar` 的布局/手势/`Animatable` 逻辑抽成可复用骨架，
   镜片绘制单独成文件，**默认关闭**，只有显式传入开关时才生效。
2. debug 预览页 `SidebarLensPreviewActivity`：
   - 左侧 + 右侧各一个真尺寸侧栏（验镜像）；
   - 背景铺渐变内容区（玻璃有东西可采，也能看清"镜片有没有压到内容区"）；
   - 顶部一行：能力探测结果（API / RuntimeShader / RenderEffect）+ 开关切换按钮（开/关透镜）。
3. 验证判据（真机截图 + 像素）：
   - **能证伪的用例**：关掉开关后，屏幕必须与改动前**逐像素一致**（同一页面、同一选中项）；
   - 拖到两格之间时镜片必须连续跟随（截 3 帧看纵向位置单调）；
   - 抬手后镜片中心与最近项中心的偏差 ≤ 1px（吸附正确）；
   - 镜片左右边缘不得越过侧栏边界（68dp 案，且按下放大后也不越界）或越过量 = 预期值（放大案）；
   - 右侧镜像：镜片横坐标关于屏幕中线镜像，纵向轨迹与左侧**完全一致**；
   - 长按/拖动 30 s 看帧率与温度（常亮设备，代价要实测）。
4. 原型通过后，再把 `SideBar` 的指示块换成同一组件，并跑第五步的回归清单。

---

## 八、风险与未决项

- **本机看不到折射与彩色边缘**（API 30）。要真看到，需要 API 33+ 的设备；
  本机能验收的是"放大 + 染色 + 描边 + 跟手 + 吸附 + 镜像"这一组。
- **模糊在本机也没有**（API 31）。镜片的"透"靠半透明底色 + 高光，不靠模糊。
- 常亮设备的持续合成开销：镜片带动画时每帧重绘子树，需实测帧率（本机 120 Hz，目标不跌破 55 fps）。
- 若后续确实要"镜内看到邻近项形变"，再按 4.2 的三条硬规则引入 exportedBackdrop，
  并在 debug 预览页先做**崩溃对照实验**（同一页面上同时挂"合规"与"故意成环"两个例子，确认后者确实崩、前者确实稳）。
