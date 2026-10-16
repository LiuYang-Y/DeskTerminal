# 音乐模块 · 阶段一前置材料（待确认后开写 UI）

日期：2026-10-14
状态：**草案，等用户确认**。本文只做三件事 —— 歌词源命中率实测、`accompanist-lyrics` 核查、
音乐页布局与尺寸推导。**不含任何 UI 代码**。

> ⚠️ **历史文档（2026-10-16 注）**：本文写作时歌词源是 LRCLIB。**歌词源后来已改为 QQ 音乐在线取词**
> （2026-10-16，见 `docs/2026-10-14-music-phase0-qq-vip-check.md` 与 `AI-HANDOFF.md` §8）。
> §1 的 LRCLIB 命中率实测与 §5.2 的 LRCLIB 相关 bug 属**当时的历史记录**，保留备查；
> §2 依赖核查、§3-§4 布局与尺寸推导、§5.1 的 HUD/歌词/缓存/seek 验证**仍然有效**。

---

## 1. LRCLIB 命中率实测

### 1.1 方法

脚本：`tools/lyrics-probe/probe_lrclib.py`（可复现，20 首内置）
原始结果：`tools/lyrics-probe/lrclib-probe-result.json`
`User-Agent: DeskTerminal/0.1.0`，请求间隔 0.4s。

三级回退（与客户端实现保持一致）：

| 级 | 请求 | 说明 |
| --- | --- | --- |
| L1 | `GET /api/get?track_name&artist_name&album_name&duration` | 精确；专辑名/时长因发行版不同而失配是常态 |
| L2 | `GET /api/get?track_name&artist_name` | 松散；去掉专辑与时长 |
| L3 | `GET /api/search?track_name&artist_name` | 取 top1，并记录候选数 |

判据分三档，**刻意不合并**（防"能返回 JSON 就算命中"的假阳性）：

- `synced` —— `syncedLyrics` 非空且能解析出 `[mm:ss.xx]` 行级时间戳 → 可滚动 / 可拖动
- `word` —— 出现 `<mm:ss.xx>` 内联标签（enhanced LRC）→ 可逐字高亮
- `plain` —— 仅 `plainLyrics` 非空 → 只能静态展示

### 1.2 实测结果

| # | 歌名 | 歌手 | 命中级 | 行级 synced | 逐字 | 行数 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 不遗憾 | 李荣浩 | L2 | ✓ | – | 78 | L1 专辑名失配 |
| 2 | 晴天 | 周杰伦 | L1 | ✓ | – | 44 | |
| 3 | 七里香 | 周杰伦 | L1 | ✓ | – | 34 | |
| 4 | 夜曲 | 周杰伦 | L1 | ✓ | – | 47 | |
| 5 | 一路向北 | 周杰伦 | L1 | **✗** | – | 0 | ⚠️ **假命中**：接口 200，`syncedLyrics`/`plainLyrics` 全空 |
| 6 | 告白气球 | 周杰伦 | L1 | ✓ | – | 42 | |
| 7 | 富士山下 | 陈奕迅 | L1 | ✓ | – | 42 | 粤语 |
| 8 | 孤勇者 | 陈奕迅 | L1 | ✓ | – | 42 | |
| 9 | 年少有为 | 李荣浩 | L2 | ✓ | – | 52 | L1 专辑名失配 |
| 10 | 乌梅子酱 | 李荣浩 | **MISS** | – | – | – | L1/L2 404，L3 搜索返回 **0 条** |
| 11 | 修炼爱情 | 林俊杰 | L1 | ✓ | – | 107 | |
| 12 | 江南 | 林俊杰 | L1 | ✓ | – | 35 | |
| 13 | 泡沫 | G.E.M. 邓紫棋 | L1 | ✓ | – | 35 | |
| 14 | 光年之外 | G.E.M. 邓紫棋 | L1 | ✓ | – | 72 | |
| 15 | 起风了 | 买辣椒也用券 | L1 | ✓ | – | 46 | 多版本曲目，L1 直接命中本家版本 |
| 16 | 成都 | 赵雷 | L1 | ✓ | – | 30 | |
| 17 | 杀死那个石家庄人 | 万能青年旅店 | L1 | ✓ | – | 36 | |
| 18 | 悬溺 | 葛东琪 | L1 | ✓ | – | 27 | |
| 19 | Blinding Lights | The Weeknd | L1 | ✓ | – | 40 | |
| 20 | Cruel Summer | Taylor Swift | L1 | ✓ | – | 60 | |

### 1.3 汇总

| 指标 | 结果 |
| --- | --- |
| 接口命中（HTTP 200） | **19/20（95.0%）** |
| **有效行级 synced** | **18/20（90.0%）** |
| 逐字 word-level | **0/20（0.0%）** |
| 仅 plain（无时间轴） | 0/20 |
| 完全未命中 | 1/20（5.0%）—— 乌梅子酱 |
| 命中来源 | L1 = 17、L2 = 2、L3 = 0、MISS = 1 |
| 中文 18 首 | 行级 **16/18（88.9%）**，逐字 0/18 |
| 英文 2 首 | 行级 **2/2（100%）**，逐字 0/2 |

### 1.4 结论（三条，都会改变架构）

1. **行级歌词可用，覆盖 90%**。LRCLIB 作为"零后端、零合规风险"的歌词源是成立的
   （公开 API、无需登录、无抓包）。
2. ⚠️ **逐字歌词覆盖为 0**。LRCLIB 只存标准 LRC / plain LRC，没有 enhanced LRC。
   → **"逐字歌词"这条链路在当前数据源下没有输入**。要做逐字只有两条路：
   (a) 用户本地导入 enhanced LRC / TTML / KRC；(b) 接 Apple Music 系 TTML 库（`amll-ttml-db`，
   需自建后端）。**主分支先按"行级 + 可选本地逐字"设计，不假装有逐字数据。**
3. ⚠️ **必须处理"假命中"**：`一路向北` 接口返回 200 但两个歌词字段全空。
   客户端的判据不能是 `HTTP 200`，必须是 **`syncedLyrics` 非空且含时间戳**；
   否则会出现"歌曲识别成功、歌词区一片空白且不报错"。这正是"它也在工作，但工作得不对"的典型。
4. ⚠️ **搜索回退必须支持"歌手名罗马字"**（2026-10-14 真机接入当天新增，§1.1 的 20 首歌单
   测不出来 —— 那份歌单的歌手名恰好都在库里是原样）：
   LRCLIB 对一批中文歌手存的是**罗马字**。实测 `麻雀 / 李荣浩`：

   ```
   /api/search?track_name=麻雀&artist_name=李荣浩  → []            （14 条全被歌手名滤光）
   /api/search?track_name=麻雀                     → 14 条，其中
       #0 Ronghao Li  / 麻雀   / 268s / syncedLyrics 759 字  ← 要找的就是它
       #2 Ronghao Li  / 麻雀   / 253s / syncedLyrics 759 字
   ```

   症状与 §1.4-3 同类但更隐蔽：接口全通、无报错、UI 显示"未找到同步歌词"，
   而歌词**就在库里**。`乌梅子酱` 的 MISS（§1.2 #10）**很可能也是同一根因**，
   当时只记了"搜索返回 0 条"没查歌手名形态 —— 修完这层后值得重测一遍 20 首歌单。

   落地（`LrcLibClient.search`）：搜索改**两段式**（先带 `artist_name`、空手而归再只按
   `track_name`），候选排序键 = **歌手相似度 > 专辑相似度 > 时长接近**。第二段的候选
   会混进同名别家的歌，专辑相似度（罗马字场景下唯一能对上的强信号）必须参与排序。

---

## 5. 真机取证记录（2026-10-14，小米 11 Ultra / Android 11 / API 30）

会话源：QQ音乐（`com.tencent.qqmusic`）。证据截图与缓存样本在 `.verify/music/`
（不入库），曲目：麻雀/李荣浩、稻香/周杰伦、多余的解释/许嵩、一程山路/毛不易。

### 5.1 验证通过

| 项 | 证据与判据 |
| --- | --- |
| HUD 4 秒淡出 / 轻触唤醒 | 进页 12s 后截图无 HUD；tap 后 1s 内截图 HUD 完整；再等 5s 又淡出（`05/07/08` 三连） |
| 2 行聚焦歌词 | 高亮行 + 半透明预告行，行随播放推进自动换行（`06/08` 同一首歌两行不同） |
| 歌词拉取（当时走 LRCLIB） | 稻香：角标「歌词 · LRCLIB · 逐行 · 40 行」（/api/get 直接命中）；多余的解释：命中（缓存 `ec4ff.lrc` 首行 `[00:02.35] 多余的解释`，页面前奏态渲染首句占大格） |
| 本机缓存（**可证伪**） | `svc wifi/data disable`（`ping: unknown host` 证实断网）+ `force-stop` 杀进程重启 → 歌词照常显示且继续推进。只能来自磁盘 `.lrc`（`09`） |
| 点击跳转（tap-to-seek） | 点进度条 50% 处 → 会话 position 0 → **124197ms**（268s 曲目，理论 134s，偏差 3.7%） |
| 拖动 seek | 从中点向左拖 289px（条宽 34%）→ position **40838ms**（理论 124−0.34×268≈33s + 拖动期间继续播放，方向/量级/比例全对） |
| 控制键 | 「下一首」：曲目从 多余的解释 → 一程山路（dumpsys 证实）；播放键图标随会话 state 显示 ▶/⏸（会话卡在 state=6 时显示 ▶，**忠实反映**而非自说自话） |
| 换曲过渡 | 水印/背景取色/封面/歌词全部随 frameKey 重算（`11`：新曲深紫背景 + "歌词 · 获取中…"） |
| 可停算（"停"方向） | 会话 state=6（非 PLAYING）时 HIGH 档**无圆环**（`13/16`）—— active 判据生效 |
| 可停算（"跑"方向） | state=3 时间隔 1s 两帧 **MD5 不同**（`19/20`）—— 帧驱动确实在跑 |
| EffectTier 四档可操作 | 设置面板 高/中/低/关 逐档切换 + 截图（`13-16`）；OFF 档背景为均匀纯色、其余档有渐变层次 |

### 5.2 当天抓到并修掉的两个真 bug

1. **LRCLIB 歌手名罗马字**（§1.4-4）：`麻雀/李荣浩` 修复前"未找到"；修复后**同一把缓存键**
   从 `.none` 变成 `.lrc`，歌词正常渲染「山隔壁還是山 都有一個伴」（`22`）。
2. **负缓存固化瞬时失败**：`fetch` 返回 null 的两种原因（库里没有 / 网络失败）此前
   一律写 6 小时负缓存。修复 = `FetchResult`（Found/NotFound/Unreachable）三态 +
   负缓存分级 TTL（确认没有 6h / 网络失败 5min）+ 页面区分「网络暂时不可用」与「未找到」。

### 5.3 已知事项与未验项（如实记录）

- **LRCLIB 中文歌词多为繁体**（稻香「還記得」、麻雀「還是山」）。数据源属性，不是 bug；
  若在意可后续加"简繁转换"层，暂不做。
- **四档的视觉差异未逐档定格**：当天会话曲目封面色调单一（深紫/深绿），且圆环 alpha 低，
  截图上中/低两档与高的差别不直观。四档参数是纯函数（`waveSpec`/`backgroundSpec`，
  已被单测覆盖的只有布局部分），圆环相位协程的两方向（跑/停）有证据（§5.1 最后两行）。
  **后续换色彩丰富的封面重截一轮。**
- **QQ音乐的 MediaSession 实现很懒**：state 卡在 6（SKIPPING_TO_NEXT）不跟 PLAYING、
  metadata 落后实际曲目、position 长时间不动。第三方会话质量问题；我们的页面忠实反映会话，
  但意味着"非 VIP 试听（30s 预览）"场景下进度条会频繁跳。不修（修不了别人的会话）。
- **小米 logcat 恒空**，歌词链路（`DeskLrcLib`/`DeskLyricsRepo`/`DeskLyrics`）的
  `android.util.Log` 全部不可见，本次排查靠网络层复算与磁盘取证。后续若需要可观测性，
  走项目既有的文件日志方案（`files/map/map.log` 同款）。
- 单测 10 项全绿（`MusicLayoutTest`，含两条"理论边界钉死"断言：歌词/HUD 碰撞边界 301dp、
  左右 HUD 交叠边界 289dp）。


---

## 2. `accompanist-lyrics` 核查结论

### 2.1 先纠正一个前提

**它不是 Google 官方的 Accompanist。** Google 的 Accompanist 里没有歌词组件，且该仓库已归档。
本项目是第三方 **MochaRealm / `6xingyv`** 的作品（仓库 `github.com/6xingyv/Accompanist`，
站点 `mocharealm.com`）。**引入前必须按第三方库对待**。

### 2.2 两个构件分开评估（关键）

| 项 | `lyrics-core` | `lyrics-ui` |
| --- | --- | --- |
| 坐标 | `com.mocharealm.accompanist:lyrics-core` | `com.mocharealm.accompanist:lyrics-ui` |
| 最新版 | **0.5.0**（2026-10-08） | 1.0.19（稳定）/ 2.0.0-rc.2（RC） |
| 形态 | 纯 Kotlin/JVM（KMP），`lyrics-core-jvm-0.5.0.jar` | Compose Multiplatform，Android 变体 `lyrics-ui-android-*.aar` |
| 许可证 | **Apache-2.0** ✓ | **Apache-2.0** ✓ |
| 依赖 | 仅 `kotlin-stdlib` + `kotlinx-serialization-json:1.10.0` | **8 个 `org.jetbrains.compose.*`** + `com.mocharealm.gaze:capsule-android:2.1.1-patch2` + `lyrics-core-jvm` |
| AAR minSdk | 不适用（非 Android 库） | **29**（1.0.19）/ 21（2.0.0-rc.2） |
| 字节码 | major 65（**Java 21**） | major 65（**Java 21**） |
| 逐字支持 | ✓ 解析 Enhanced LRC / TTML / KRC / Lyricify Syllable，**音节级时间戳** | ✓ 音节级渐变填充、CJK 弹跳、伴唱行、呼吸点 |

### 2.3 与本项目的兼容性判定

本项目现状：`minSdk 26` / `Java 17` / `Compose BOM 2026.09.00` / Kotlin 2.4.20 / AGP 9.4.1。

| 冲突点 | `lyrics-core` | `lyrics-ui` |
| --- | --- | --- |
| minSdk | 无要求 ✓ | **29 > 26 ✗**（1.0.19 会直接 build 失败：manifest merger 报 minSdk 冲突） |
| Compose 依赖 | 无 ✓ | **拉入 8 个 `org.jetbrains.compose.*`** ✗ —— `:core:ui` 已因 `kyant-backdrop` 明确**排除过 `org.jetbrains.compose`**，混入会重新打开那个坑 |
| 额外第三方 | 无 ✓ | **`com.mocharealm.gaze:capsule-android`** ✗ —— 又一个需单独审许可证的未知库 |
| Java 21 字节码 | 需实测（D8 大概率可处理） | 同 |
| 集成成本 | 一行依赖 | 还要 apply Compose Multiplatform 插件 + 处理依赖排除 |
| 与我们的设计契合 | **高**（只要解析结果） | **低** —— 它是一整套成品 `KaraokeLyricsView`，**不是可组合的零件**；我们要的"中央双层景深大衬字"它给不了 |

### 2.4 结论

> **采纳 `lyrics-core`（0.5.0），不采纳 `lyrics-ui`。**

理由：
1. `lyrics-ui` 三条硬冲突（minSdk、CMP 依赖、额外第三方库），且**样式不可定制为我们的设计**。
2. `lyrics-core` 是纯解析库、无 Android/Compose 依赖、Apache-2.0、解析器齐全 —— 正是我们要的
   "别重复造 LRC/TTML 解析轮子"的那部分。
3. 渲染层**自研**：双层景深大衬字、封面取色、声波圆环都是本项目特有的观感，
   抄不了也不该抄。

**待实测项（写代码前先验，不许假设）**：
- [ ] `lyrics-core-jvm` 的 Java 21 字节码能否被 AGP 9.4.1 的 D8 正常 dex（`minSdk 26` 下 desugar）。
      若不行，退路 = 上游提 issue / 用 0.3.x 旧版（需查其字节码版本）/ 自己写 LRC 解析（~200 行）。
- [ ] Kotlin 2.4.20 消费 Kotlin 2.3.x 编译的库（JVM metadata 向前兼容，预期通过，但要真编一次）。

---

## 3. 音乐页布局草案与尺寸推导

### 3.1 分层（横屏沉浸，三层叠加）

```
┌──────────────────────────────────────────────────────────────┐  ← 内容区 W × H
│  L0  背景层：封面取色渐变 + 缩小后 CPU 模糊的底图（每首只算一次）      │
│  L1  声波圆环：左右对称，相位呼吸（绘制期读状态，不触发重组）           │
│                                                              │
│                  ┌────────────────────┐                      │
│                  │  底层大衬字（景深）  │  ← 与主歌词同一时间轴    │
│                  │   当前歌词行（主）   │                      │
│                  │     下一行歌词       │                      │
│                  └────────────────────┘                      │
│                                                              │
│  L2  HUD（轻触唤醒，4s 无操作淡出）                              │
│  ┌────────────┐                        ┌──────────────┐      │
│  │ 封面 │ 歌名   │                        │ ⏮  ⏯  ⏭      │      │
│  │      │ 歌手   │                        │ ▁▁▁●▁▁▁▁▁▁   │      │
│  └────────────┘                        └──────────────┘      │
└──────────────────────────────────────────────────────────────┘
   ← pad →                                                   ← pad →
```

### 3.2 反推公式（全部以 `BoxWithConstraints` 的 `maxWidth` / `maxHeight` 为唯一输入）

```kotlin
// 所有值单位 dp。W = maxWidth.value, H = maxHeight.value
val pad        = (H * 0.055f).coerceIn(12f, 24f)              // 内容区外边距
val coverSide  = (H * 0.26f).coerceIn(80f, 132f)              // 封面正方形边长
val coverGap   = (W * 0.018f).coerceIn(8f, 18f)               // 封面与歌名文字、按钮之间
val hudBand    = pad + coverSide + coverGap * 0.8f             // 底部 HUD 占带（左下块高=封面）

// 歌词区：外层已经用 padding 让出上下两条带，内部垂直居中
val safetyGap  = 8f
val lyricArea  = H - pad - hudBand - safetyGap
val visibleLines = 5

// 行高**显式声明**，字号从行高反推（不是反过来）
var lyricLineH = lyricArea / visibleLines
var lyricSize  = (lyricLineH / 1.55f).coerceIn(16f, 40f)      // 1.55 = 中文实测行高系数
lyricLineH     = lyricSize * 1.55f                            // ★ 夹取后**回算**，两者恒同源
val shadowSize = lyricSize * 1.9f                             // 底层大衬字

val titleSize  = (coverSide * 0.22f).coerceIn(14f, 26f)
val artistSize = titleSize * 0.72f

val btnSize    = maxOf(DeskDimens.minTouchTarget.value, H * 0.16f)   // 64 是触控下限
val controlW   = 3 * btnSize + 2 * coverGap
val progressW  = controlW
val ringR      = minOf(W, H) * 0.34f                          // 仅绘制期，不占布局
```

> ★ **夹取后回算**这一条是必需的，不是洁癖：若 `lyricSize` 被下限 16sp 夹住而
> `lyricLineH` 仍用 `lyricArea/5`，两者就不再同源，5 行必然溢出歌词区压到底部 HUD。
> 这正是"两套阈值互相打架"的类型 —— 所以行高必须由**夹取后的字号**回算。

### 3.3 两台机器的具体数值

| 元素 | 公式 | 一加 666×361dp | 小米 786×411dp |
| --- | --- | --- | --- |
| 外边距 `pad` | `H*0.055` | **19.9** | **22.6** |
| 封面边长 `coverSide` | `H*0.26` | **93.9** | **106.9** |
| 封面-文字/按钮间距 `coverGap` | `W*0.018` | **12.0** | **14.1** |
| 底部 HUD 带高 `hudBand` | `pad+coverSide+coverGap*0.8` | **129.7** | **147.6** |
| 歌词可用高 `lyricArea` | `H-pad-hudBand-8` | **203.4** | **232.8** |
| 歌词行高（显式） | `lyricArea/5` | **40.7** | **46.6** |
| **主歌词字号** | `行高/1.55` | **26.3sp** | **30.0sp** |
| 底层大衬字号 | `主×1.9` | **50.0sp** | **57.0sp** |
| 歌名字号 | `coverSide*0.22` | **20.7sp** | **23.5sp** |
| 歌手字号 | `歌名×0.72` | **14.9sp** | **16.9sp** |
| 控制按钮直径 | `max(64, H*0.16)` | **64.0** | **65.8** |
| 三按钮组总宽 `controlW` | `3*btn+2*gap` | **216.0** | **225.6** |
| 进度条轨道宽 | `= controlW` | **216.0** | **225.6** |
| 进度条滑块直径 | `max(14, H*0.045)` | **16.2** | **18.5** |
| 进度条触摸热区高 | 固定（Material 下限） | **48.0** | **48.0** |
| 声波圆环半径 | `min(W,H)*0.34` | **122.7** | **139.7** |
| 圆环描边 | `半径*0.032` | **3.9** | **4.5** |
| 歌词区 y 范围 | `[pad, pad+lyricArea]` | **[19.9, 223.3]** | **[22.6, 255.4]** |
| HUD 顶边 | `H-hudBand` | **231.3** | **263.4** |

### 3.4 不重叠论证（可证伪）

**断言：歌词块下边界 ≤ HUD 顶边 − 8dp。**

- 一加：歌词块底边 `19.9 + 203.4 = 223.3` ≤ `231.3 − 8 = 223.3` ✓（恰好相切，含 8dp 间隙）
- 小米：歌词块底边 `22.6 + 232.8 = 255.4` ≤ `263.4 − 8 = 255.4` ✓

**水平方向断言：左下块右边界 < 右下控制组左边界。**

| | 左下块宽 = `W*0.42` | 左下右边界 | 控制组左边界 = `W-pad-controlW` | 间隙 |
| --- | --- | --- | --- | --- |
| 一加 | 279.7 | **299.6** | **430.1** | 130.5 ✓ |
| 小米 | 330.1 | **352.7** | **537.7** | 185.0 ✓ |

由构造保证：`lyricArea = H - pad - hudBand - safetyGap` 中的 `hudBand` 直接来自 `coverSide`，
两者**同源**，不存在"歌词按 A 算、HUD 按 B 算"的打架空间。

### 3.5 三条实现约定（写代码时不可省）

1. **文字行高一律显式**：`Text(..., lineHeight = …)`。主歌词的 `lineHeight` 就是 `lyricLineH`，
   歌名/歌手为 `字号 × 1.55`。**不许**再把 `TEXT_LINE_HEIGHT_RATIO`(1.25) 当行高用 ——
   那是"反推字号"的系数（2026-10-14 真机已抓过一次）。
2. **HUD 淡出只改 alpha，不改布局**：歌词区始终按"HUD 可见"预留 `hudBand`，
   否则每次唤醒/淡出歌词都会上下跳一次。
3. **可停算**：`播放暂停` / 页面不可见 / `EffectTier.OFF` 三者任一成立时，
   动画协程必须**不在跑**（而不是"跑了但 alpha=0"）。声波圆环用 `Canvas` 绘制期读状态，
   **不进组合** —— 与侧栏透镜同一个套路（`DeskGlass` / `LensSideBar` 已验证）。

### 3.6 效果分档落点（复用 `EffectTier` / `LocalEffectTier`）

| 档位 | 背景层 | 声波圆环 | 说明 |
| --- | --- | --- | --- |
| HIGH | 封面取色渐变 + 模糊底图 + 呼吸 | 相位呼吸 + 内外双环 + 微光 | |
| MEDIUM | 渐变 + 模糊底图 | 单环呼吸 | 去掉第二环与微光 |
| LOW | 渐变（无模糊底图） | 静态环，不呼吸 | 省掉采样/模糊 |
| OFF | 纯色（封面主色暗化） | **不绘制** | 与侧栏 OFF 同口径：不建层 |

（档位权威仍是 `EffectTier`；音乐页只读 `LocalEffectTier.current`，不按 `SDK_INT` 自行分档。）

---

## 4. 下一步（等确认）

- [ ] 用户确认 §3 布局草案（尤其是"5 行可见 + 主歌词 26.3/30.0sp"这两处取值）
- [ ] 实测 `lyrics-core` 在本工程的编译（Java 21 字节码 + Kotlin 2.4.20 消费）
- [ ] 建 `third_party/` 许可证清单（`lyrics-core` Apache-2.0 入库同批写入）
- [ ] 补 `PlaybackSource` 接口与 `MediaSessionMonitor` 的 `canSeek` / `seekTo`
- [ ] Phase 0（路线 B）另起分支，不与主分支合流
