# 首页分页 + 地图卡：真机验证报告

日期：2026-10-12
设备：小米 11 Ultra（1440×3200 @560dpi → 横屏 **914×411 dp**），Magisk root 可用
构建：debug（`app-debug.apk`）
依据：`docs/2026-10-12-home-pager-layout.md`（草案与用户 A/B/C/D 选择）

状态：**全部通过**。C 项（单实例重挂）一次通过，不需要退双实例。

---

## 0. 先说结论

- 首页两页横滑、页码圆点、点卡进全屏地图页、隐私撤回/再同意、侧栏左右镜像、设置浮层、防烧屏微位移 —— 全部正常。
- 地图**全程只创建一次**（`create=1`、`destroy=0`、瓦片只加载一次）。翻页 20 次 `MAD=0.0`，地图一像素没动。
- 修掉两个真机才暴露的缺陷（见第 3 节），都是"单实例跨容器重挂"这条路的坑。

### 本机实测尺寸（与草案的 794×361 基准机不同，按比例放大）

| 项 | 草案（基准机） | 小米 11 Ultra 实测 |
|---|---|---|
| 屏幕 | 794 × 361 dp | **914 × 411 dp** |
| 挖孔 / 侧栏 / 间距 | 40 / 76 / 12 | 40 / 76 / 12（实测 136px / 310px / 42px） |
| 内容区 | 666 × 361 | **786 × 411** |
| 首页卡片区 | 626 × 313 | **746 × 363** |
| 地图卡 | 409 × 313 | **489 × 363**（实测 1715×1272 px） |
| 全屏地图 | 666 × 361 | **786 × 411**（实测 2755×1440 px） |

地图在内容区里的实测左右边界：**左 136 px、右 2888 px**，与"挖孔 40 + 内容 786 + 间距 12 + 侧栏 76 = 914 dp"逐项吻合。
高德 Logo 画在地图左下角，完整可见。

---

## 1. 用户额外要求的 5 项

| # | 要求 | 结果 | 证据 |
|---|---|---|---|
| E1 | 点地图卡能进全屏页 **且** 横滑能正常翻页 | ✅ 两条并存 | 点卡 → `reuse existing MapView (attach=N)`；在卡上 `swipe 2100→700` → `render enabled=false fps=15`，翻页生效。同一张卡上两种手势互不吃掉 |
| E2 | 翻页 20 次不卡、不误触地图 | ✅ | 20 次（10 左 10 右）→ 日志出现 10 组 `disable/enable` 配对，**零** create/attach/detach；首页地图卡区域前后 **MAD=0.0、变化像素 0.00%**（对照组右侧列 MAD=10.9 / 4.92%，证明判据有分辨力） |
| E3 | 地图卡 ↔ 全屏页来回切，无闪烁、无重新加载 | ✅ | 10 次往返：`create=1`、`destroy=0`、`map loaded (tiles on screen)` **仅 1 次**（冷启动那次）。第 10 次进全屏页截图第 1 次像素级一致（同一视角、同一批瓦片、蓝点未动） |
| E4 | 撤回隐私再同意，首页卡与全屏页都正确 | ✅ | 撤回 → 首页卡变精简占位、日志 `released refs=0`（MapView 已解挂，其间**无** `MapView attached`）；点占位卡 → 全屏页显示完整隐私卡；点「同意并显示地图」→ `reuse existing (attach=28)`，无重建、无黑屏；回首页卡 luma **207.35**（基线 207.38） |
| E5 | 侧栏左右切换后分页/圆点/卡片布局镜像 | ✅ | 切左侧：侧栏贴挖孔侧、内容区右移、齿轮跑到右上、Shizuku 角标跟着翻边；两页卡片内部顺序不变、比例 2:1 不变；页码圆点中点 1513.5 px = 内容区中心 1512 px。切回右侧同样正确 |

---

## 2. 文档第 9 条清单

| # | 条目 | 结果 | 证据 |
|---|---|---|---|
| 1 | 两页横滑、圆点同步、点圆点能翻页 | ✅ | 横滑多次；圆点中心 1440 / 1587 px（相隔 42 dp = 32 dp 点击区 + 10 dp 间隙）。点圆点 2 → 选中宽 28px 变到第 2 个；点圆点 1 → 变回第 1 个 |
| 2 | 卡上横滑地图不动、页面翻；点卡进全屏 | ✅ | 见 E1 / E2（地图卡区域 MAD=0.0） |
| 3 | zoom 17 街道级、居中当前位置、日夜切换 | ✅（日夜沿用旧路径） | 日志 `initial camera -> 34.0891,108.3245 z=17.0`、`first fix -> centered … z=17.0`；截图可见楼栋/街道/商户名（街道级）。预览卡与全屏页都走同一个 `rememberAutoMapStyle()`（日出日落驱动），每次挂载打印 `daylight now: NORMAL SunTimes(sunrise=406, sunset=1094) next switch in 122 min`。**未做夜间实测**：要改系统时间，会影响地图 TLS 校时，且这条代码路径本轮没动 |
| 4 | 未同意 / 撤回 → 占位卡，日志无 `MapView attached` | ✅ | 撤回后到再同意之间（16:10:11 → 16:10:49）日志只有 `released refs=0`，无 attach |
| 5 | 来回翻页 20 次 → attach=1、dispose=0 | ✅（判据已修正） | 实测纯翻页**连一次 attach/detach 都没有**。注意：`MapView attached` 这行是每次 `AndroidView` 组合时打的，不能当计数用；**真正的计数是 `MapView created`** —— 本轮会话 `create=1` |
| 6 | 翻到第 2 页静置 5 分钟 → 回来地图正常 | ✅ | 16:11:41 `render enabled=false` → 16:27:57 `render enabled=true`，静置 **16 分钟**；期间无 attach/重挂/瓦片重载。回来截图地图卡区域 luma 207.35 |
| 7 | 侧栏左右切换全部正确镜像 | ✅ | 见 E5 |
| 8 | 防烧屏微位移仍在工作 | ✅ | 连续 7 帧（间隔 33s，共 205s）探针测「西安」文字顶端行号：**123→123→117→109→103→100→103**，左列 **2026→2037→…→2025**；亮像素数恒为 2020、底色中位亮度恒为 48（排除蒙层干扰）。峰峰值 23 px ≈ 6.6 dp，与 `range=6dp`、`y=x*0.6`（3.6dp，峰峰 7.2dp=25.2px）吻合——李萨如曲线的 y 半周期签名 |
| 9 | 首页/地图页来回切 10 次 → 不泄漏、不重复 onCreate | ✅ | 见 E3 |

---

## 3. 真机暴露的两个缺陷与修法

### 3.1 全屏地图页整块黑（阻塞项）

**现象**：从首页点地图卡进全屏页，地图区域全黑（内容区亮度 19，首页是 168），拖动也不刷新。

**排查**：日志显示 `reuse existing MapView (attach=2 detach=0)` → `released (attach=2 detach=1)`，
`create` 恒为 1 —— **不是重建**，是"重挂之后渲染没恢复"。

**根因**：切屏那一帧的顺序是「新使用点 `acquire`（挂到新容器）→ 旧使用点 `release`」。
而 `release()` 里有一句**无条件的** `detachFromParent(target)`：

```kotlin
fun release() {
    val target = view ?: return
    detachFromParent(target)      // ← 摘掉的其实是"刚挂好的新父容器"
    ...
}
```

于是 MapView 变成**没有父容器的游离 View**：不渲染、也收不到触摸 —— 全黑且拖不动。

> 第一次修复只加了引用计数、把 `onPause` 挪到 `refs==0` 才调，症状照旧。
> 因为那修的是"被暂停"，没修"被摘下来"。日志里 `detach=1` 就是没被注意到的线索。

**修法**（`MapViewHost.release()`）：只有真的没人再用时才摘。

```kotlin
refCount = (refCount - 1).coerceAtLeast(0)
if (refCount == 0) {          // 别人还持有 → 什么都不做，acquire 自己会先摘旧父
    detachFromParent(target)
    detachCount++
    runCatching { target.onPause() }
}
```

另加一行诊断，把"挂着没有、多大"打进日志，以后这类问题一眼可见：

```
host: resumed after attach (refs=1 size=2755x1440 parent=ViewFactoryHolder)
```

**结果**：`detach` 不再误摘（`attach=2 detach=0`），全屏页正常渲染。重挂两种顺序（新先旧后 / 旧先新后）都覆盖到了。

### 3.2 进全屏页会把视角拉回原点

`AMapCanvas` 里"初始视角"和"首次定位居中"两个 `LaunchedEffect` 的"只做一次"标志是**组合实例的局部变量**。
从首页点进全屏页会重建组合 → 再种一次视角 → 用户在全屏页拖到的位置被拉回。
那样"地图状态不丢"就成了空话（重挂没丢，但每次进屏都被覆盖，效果一样）。

**修法**：把"种过一次"记到宿主上（`MapViewHost.isCameraSeeded / markCameraSeeded`），宿主与 MapView 同寿命。
日志现在每次进屏打 `initial camera skipped (host already seeded)`；本轮 20 次重挂只种了 **1 次**视角。

---

## 4. 遗留 / 待办

1. **时钟卡与天气卡还是假数据**（`FakeHomeRepository`：固定 `08:37`、`10月10日 星期六`、`18° 多云`）。天气数据源等你选。
2. **全屏隐私卡左上角与「未安装 Shizuku」角标有轻微叠字**（截图 `v11-revoke-fullscreen.png`）。
   原因是隐私卡现在铺满内容区、内边距 20 dp，而 Shizuku 角标是浮层。要不要给隐私卡加一段顶部避让（或把角标挪开）？
3. 本轮未做夜间地图样式的实测（理由见第 2 节第 3 条）。
4. `app/src/androidTest` 的 5 个 Compose 用例没跑（root 注入已能覆盖同样的行为，真机注入的证据更硬）。

---

## 5. 截图索引（`.verify/pager/`）

| 文件 | 内容 |
|---|---|
| `final-01-home-page1.png` | 首页第 1 页：地图卡（489×363）+ 回家/去公司灰态占位 + 音乐卡 |
| `final-02-home-page2.png` | 首页第 2 页：大时钟卡 + 天气卡，圆点第 2 个高亮 |
| `final-03-fullscreen-map.png` | 全屏地图页：地图铺满内容区 786×411，Logo 完整 |
| `v11-revoke-home.png` | 撤回隐私后的首页精简占位卡 |
| `v11-revoke-fullscreen.png` | 全屏页完整隐私卡（注意 Shizuku 角标叠字） |
| `v12-sideleft-p1.png` / `v12-sideleft-p2.png` | 侧栏切左侧后的两页 |
| `v13-sideright-p1-t0.png` | **注意**：这一帧还压着设置浮层蒙层，不要当基线用 |
| `drift-0..6.png` | 防烧屏微位移连续采样帧 |
| `tools/touch.sh` | `su -c input` 注入（production 构建 + root 的唯一可行路径） |
| `tools/region.py` / `centroid.py` / `shift.py` / `probe.py` / `crop.py` | 亮度、质心、位移搜索、探针、裁剪（纯标准库） |

---

## 6. 取证环境备注（下次别重新踩）

- 本机是 **production 构建**，`adb shell input` 被拒（`INJECT_EVENTS permission`），`adb root` 也不可用；
  但 **Magisk root 存在**，`adb shell su -c "input tap X Y"` 有效。坐标是设备像素（截图 1:1，不用换算）。
- `/sdcard/Android/data/com.qingke.desk/...` 属主是 `u0_a242:ext_data_rw`、模式 `drwxrws---`，shell 读不了。
  MapLog 因此镜像了一份到内部目录，用 `adb exec-out run-as com.qingke.desk cat files/map/map.log` 读。
- 用截图判断"有没有被蒙层压暗"：量一块**静态底色区**的中位亮度。设置浮层的蒙层会让它从 48 掉到 31 ——
  这个坑让第一组漂移样本作废了一次。
