# 「音乐」页改造：Mineradio / folia-major 只读源码评估

日期：2026-10-13
方式：**只读源码**（GitHub API + 网页抓取），未下载、未运行任何安装包，未安装依赖。
目标：把当前「音乐」页做成**全屏歌词 + 背景动效**。
基线：模式 A = 继续用 MediaSession 遥控 QQ 音乐；模式 B = 终端自己播放。

---

## 0. 先说结论

| | Mineradio（含 -paused） | folia-major |
| --- | --- | --- |
| 能否用 | **放弃** | **借鉴设计，Compose 自研**；Web 版整体搬 WebView 只在模式 B 下勉强值得 |
| 模式 A（遥控） | 不可行（动效要整首 PCM） | 视觉层可行，但**歌词拿不到** |
| 模式 B（自播） | 不可行（Windows-only Electron） | 可行，但要自建后端 + 接受 AGPL |

---

## 1. 一句话前提：我们的现状决定了天花板

`feature/media/MediaSessionMonitor.kt` 已经在用 `MediaSessionManager.getActiveSessions` + `MediaController`
（第 47-49、118-137、217-235 行），能拿到：

- `METADATA_KEY_TITLE / ARTIST / ALBUM / DURATION`、封面 `Bitmap`
- `PlaybackState`（`isPlaying`、position、actions）
- `controller.packageName`（能认出是不是 QQ 音乐）

**拿不到的两样东西，恰恰是这两个项目的卖点：**

1. **歌词** —— Android `MediaMetadata` 不带歌词。
2. **音频采样（PCM / 频谱）** —— 遥控模式下永远拿不到。

后面所有判断都围绕这两条展开。

---

## 2. 许可证

### 2.1 Mineradio —— GPL-3.0-only，代码可复用，但视觉主张原创

| 项 | 内容 | 依据 |
| --- | --- | --- |
| LICENSE | GNU GPL **v3 全文**（562 行 / 28529 B） | `LICENSE` |
| package.json | `"license": "GPL-3.0-only"` | `package.json` 第 9 行 |
| GitHub 识别 | `NOASSERTION` / `Other`（因 NOTICE.md 有附加声明） | API `license.spdx_id` |
| 第三方库 | Electron、Three.js、GSAP、music-tempo、NeteaseCloudMusicApi、mpg123-decoder | `NOTICE.md` → Third-party Libraries |
| 社区移植 | `SLYysl/cuefield-mineradio`(GPL-3.0)、`ww085213/Mineradio-LX-Music`(GPL-3.0)、`Wx2yZx/Mineradio-Qishui-QR-Login`(GPL-3.0-only) | `NOTICE.md` → Community Contributions（含 commit hash） |
| 与 GPL-3.0 兼容 | **是**，同协议，可直接复用（须保留 LICENSE + NOTICE、标注修改） | — |

**额外的、不是许可证条款但必须注意的主张**（`NOTICE.md` → Original Design）：

> 「Mineradio 名称、MR Logo、**界面视觉设计、启动动画方向、粒子视觉体验和电影镜头系统的产品表达**
> 属于作者原创设计。」

这是**权利声明，不是授权条款**——GPL 只授予代码，不授予视觉设计/Logo 的著作权。而"粒子视觉 + 电影镜头"
正是我们想借的部分。**照搬实现有权利风险，借鉴思路可以。**

另一条：`NOTICE.md` 明确 Qishui（汽水）侧的「bundled ByteDance/Qishui web security runtime resources
remain the property of their respective rights holders」→ **这部分不能碰**。

### 2.2 folia-major —— AGPL-3.0，可以搬，但会"染"整机

| 项 | 内容 | 依据 |
| --- | --- | --- |
| LICENSE | **AGPL-3.0**（34523 B） | `LICENSE`、`package.json` `"license": "AGPL-3.0"` |
| 第三方 | NeteaseCloudMusicApiEnhanced、KuGouMusicApi、@yakult-green-tea/qq-music-api、chenglou/pretext、chenmozhijin/LDDC、amll-ttml-db、paper-design/shaders | `README.md` → 致谢；`docs/technical.md` |
| 模组内嵌 | `mods/visualizer52hz/vendor/PIXI-LICENSE` + `pixi.min.mjs` | 文件树 |

**与我们的 GPL-3.0 是否兼容：**

GPL-3.0 §13 明确允许把 GPLv3 作品与 AGPLv3 作品**组合并分发**，GPL 部分仍按 GPL，
但 AGPL §13（"通过网络与用户交互时须提供源码"）适用于**组合体**。

→ 结论：**可以搬，但整机从此带着 AGPL §13 的义务**。我们是本地设备、不通过网络对外提供服务，
§13 实际不会被触发；但一旦做"手机投屏 / 远程 Web 访问 / 云端托管"这类网络形态，就会被触发。
这是**单向棘轮**：搬了就退不回纯 GPL-3.0。

**README 里的第二个风险点**（"法律与免责声明"）：

> 「本仓库及其源代码仅供个人学习、技术交流与**非营利测试**使用。请勿将其用于商业盈利用途。」

这与 AGPL 本身冲突（AGPL 允许商用），属于作者单方面表述、**不构成有效许可证限制**；
但它反映了作者态度，且对我们的商业化和答辩/演示口径是真实风险。

> ⚠️ 以上是法条阅读，不是法律意见。真要搬 folia 代码，建议走一次正式合规确认。

---

## 3. 界面技术：是不是纯网页？能不能塞进 WebView？

### 3.1 Mineradio —— 前端是纯 Web，但**胶水全是 Electron/Windows**

渲染栈：

- 3D/粒子：**three.js r128**（`public/vendor/three.r128.min.js`，603 KB）
  - 证据：`public/js/modules/02-visual/00-pointer-cover-particles.js:2-13`
    `new THREE.Vector3 / Raycaster / Plane / Quaternion`；:207 `new THREE.CanvasTexture(cv)`
- 歌词舞台：three.js + 自定义 shader，文字先 Canvas 2D 光栅化成纹理再上 mesh
  - 证据：`02-visual/12-lyrics-row-layers.js:135-165` `new THREE.ShaderMaterial({...})`、
    `THREE.DoubleSide`、`THREE.AdditiveBlending`
  - 证据：`02-visual/14-stage-lyrics-rendering.js:2581-2582`
    `document.createElement('canvas')` + `.getContext('2d')` 做 karaoke 逐字量测
- 单页结构：`public/index.html`（129 KB）+ `public/js/modules/**`
  （00-state / 01-scene / 02-visual / 03-beat / 04-shelf / 05-playback / 06-lyrics / 07-fx / 08-account / 10-shell）
  + `11-main-loop.js`（35 KB）+ `public/css/index.css`（436 KB）

**Electron / Windows 特有依赖（这些没有就不能跑）：**

- `window.desktopWindow.readLyricCache / writeLyricCache`
  —— `06-lyrics/00-lyrics-fetch-parse.js:49,56`，preload 桥。WebView 里没有。
- `desktop/wallpaper-engine-runtime.js`（184 KB）、`wallpaper-mode-runtime.js`（31 KB）、
  `full-desktop-mode-runtime.js`（91 KB）、`desktop-native-icon-layer-runtime.js`（56 KB）
  —— Windows **DWM / WorkerW / 桌面图标层**。
- `scripts/check-wallpaper-engine-*.ps1`、`quick-check.bat`、`start-mineradio.bat`
  —— PowerShell / 批处理。
- 打包：`electron-builder --win nsis`，`arch: ["x64"]`，**只有 Windows 一个 target**。

**判定：不能整体搬进 WebView。**
shader/粒子本身是纯 WebGL + Canvas，技术上能抽；但抽出来等于重写 80% 的胶水
（数据获取、歌词缓存、生命周期、进程管理全走 Electron 桥），且抽掉后剩下的正是它最贵、
也最被作者主张权利的部分。

### 3.2 folia-major —— **可抽取性高，且作者自己验证过移动端**

栈（docs/technical.md）：React 19 + Vite 6 + TypeScript + Tailwind 4 + Framer Motion + Electron。

歌词渲染有 **14 个模式**（`src/components/visualizer/README.md` 的 mode registry），
按渲染技术分三档：

| 渲染技术 | 模式 |
| --- | --- |
| **纯 DOM / CSS / Framer Motion / RAF** | still、classic(Luminous)、cadenza(Mindscape)、partita(云阶)、fume(Fume)、cappella(群唱)、tilt(倾诉)、claddagh、monet |
| **PixiJS（WebGL）** | sonnet(商籁)、tempera(凝彩)、lumiere(绘光)、lattice 歌词 |
| **React Three Fiber（WebGL）** | diorama(镜台) |
| **Canvas2D ↔ WebGL2 双路径** | pendolo(时计)：「优先 WebGL2…不支持时退回 Canvas2D」 |

Pixi 侧的实锤（`src/components/app/lattice/lyrics/latticeLyricRaster.ts`）：
文字用 `canvas.getContext('2d')` + `fillText` 光栅化，再 `new pixi.CanvasSource({ resource: surface, resolution: 1 })`
→ `new pixi.Texture({ source })` 上传。

**为什么可抽取：**

- 模式通过 `src/components/visualizer/<mode>/entry.tsx` 注册，
  `registry.tsx` 用 Vite `import.meta.glob('./*/entry.tsx', { eager: true })` 自动发现。
- 共享契约集中在 `definition.ts` 的 `VisualizerSharedProps`：
  `MotionValue currentTime`、当前行/全部歌词、主题与字幕主题、音频分析值、背景/透明度、
  播放状态、seek 回调、模式 tuning。
  → **换掉"数据从哪来"这一层，渲染层能整体搬。**
- Electron 专有代码只在**可选层**：`electron/main.cjs`（252 KB）里的
  `neteaseBackend / qqBackend / kugouApiBridge / discordPresence / wallpaperController / ffmpeg`
  全是"音源与桌面集成"，**视觉层不依赖它们**。

**作者自己给出的移动端路径**（README）：

> 「目前推荐移动端用户部署 Web 版本/自托管版本之后，通过 chrome for android / iOS Safari
> 创建 PWA 应用来使用。……可以使用 capacitor 将本项目的 web 版本打包成为可安装的安卓 apk，
> 具体方法可参考示范仓库：`chthollyphile/folia-sonnet`」

→ **WebView 路线是通的**，有官方示范仓库。

**但要付的代价：** PWA/移动是"能用"，不是"为 794×361 dp 横屏终端设计"。
folia 的排版是为竖屏手机和桌面窗口做的，我们的横屏小尺寸要全部重排。

---

## 4. 数据来源：歌词 / 封面 / 歌曲信息从哪来？

### 4.1 Mineradio —— 全走内嵌 Node 服务，非官方接口，必须有后端

歌词路径（`public/js/modules/06-lyrics/00-lyrics-fetch-parse.js:18-36`）：

```
/api/qq/lyric?mid=..&id=..     /api/kugou/lyric?hash=..
/api/qishui/lyric?id=..        /api/spotify/lyric?id=..
/api/lyric?id=..               (netease 兜底)
```

**全部指向本地 Node 服务** `server.js`（6718 行 / 265 KB，`const PORT = process.env.PORT || 3000`，第 121 行）。

后端直连第三方（抓包复刻 + 客户端伪装）：

- 酷狗（`kugou-api.js:7-20`，硬编码 7 个域名）：
  `songsearch.kugou.com/song_search_v2`、`m.kugou.com/app/i/getSongInfo.php`、
  `wwwapi.kugou.com/play/songinfo`、`krcs.kugou.com/search|download`、`gateway.kugou.com`
- 网易云：`server.js:56` `require('NeteaseCloudMusicApi')`；
  :2827、:6621 伪 `Referer: https://music.163.com/`
- QQ：`server.js:3869` 伪 `Referer: https://y.qq.com/portal/player.html`
- 汽水：`qishui-api.js`（135 KB）+ `qishui-auth-v6.js`（20 KB）+ `qishui-audio-decryptor/`
  （还要打包 ByteDance 安全运行时）

**判定：非官方公开接口，必须有后端/代理。** 搬到 Android 要么把整个 Node 后端搬过去，要么自建服务端。

### 4.2 folia-major —— 同样要自建后端，但变量都摊在明面上

| 来源 | 是否需要自建 | 依据 |
| --- | --- | --- |
| 网易云 | **必需**（`VITE_NETEASE_API_BASE`，表格标"是"） | `docs/technical.md` 环境变量表；依赖 `NeteaseCloudMusicApiEnhanced` |
| 酷狗 | Web 版需自建 `KuGouMusicApi`（**无默认公共实例**）；Electron 版主进程内置 | 同上 |
| QQ 音乐 | `@yakult-green-tea/qq-music-api`；可 serverless（`/api/qq` + `QQ_SESSION_SECRET`），但**播放前必须先登录**、默认仅微信扫码 | 同上 + `docs/qq-music-deployment.md` |
| Navidrome / 本地库 | 自托管 / 本地文件 | README 核心能力表 |
| 歌词代理 | `api/lyric-proxy.js`：白名单 `*.qq.com`、`y.gtimg.cn`、`*.kugou.com`；对 `u.y.qq.com` 注入 `Cookie: tmeLoginType=-1` 和 `User-Agent: okhttp/3.14.9` | `api/lyric-proxy.js` |
| 逐词歌词库 | 接 `amll-ttml-db`（Apple Music-like TTML 逐词歌词库，开源） | README 致谢 |

**判定：同样是抓包接口 + CORS 绕过代理，不是官方 API。** folia 的好处是把所有外部依赖都写成了
`.env.example` 里的变量，可审计性远好于 Mineradio。

**对我们模式 A 的直接推论：** MediaSession 不带歌词 → 要全屏歌词就**必须**接第三方歌词源
（自建网易云/QQ 代理，或 amll-ttml-db）。这是模式 A 唯一的外部缺口，也是最大的合规与稳定性风险。

---

## 5. 动效驱动：要不要音频频谱？模式 A 能不能用别的驱动？

### 5.1 Mineradio —— **必须拿到整首 PCM，模式 A 直接判死**

`public/js/modules/03-beat/01-audio-beat-analysis.js:17-33`：

```js
var TmpCtx = window.OfflineAudioContext || window.webkitOfflineAudioContext;
var DecodeCtx = window.AudioContext || window.webkitAudioContext;
dc.decodeAudioData(ab.slice(0), resolve, reject);
// 用 OfflineAudioContext 分离低频重鼓 / 中频鼓身 / 高频敲击感.
```

运行时侧 `03-beat/06-sonic-audio-monitor.js:87-97`：`fftSize`、`binHz = sampleRate / fftSize`。
另有 `dj-analyzer.js`（34 KB）、`03-beat/02-podcast-dj-analysis.js`（37 KB）同理。

→ "基于节奏的电影镜头视觉系统"（README 核心特性）**建立在对整首音频做离线解码之上**。
模式 A（遥控 QQ 音乐）拿不到任何音频字节，这套**完全无法工作**，
也不可能退化成"由歌名/进度/封面色驱动"——除非重写整个驱动层。

### 5.2 folia-major —— **音频是可选的逐模式能力，模式 A 可行**

实锤：

- `mods/visualizer52hz/beatDetector.mjs:57-58`
  ```js
  step(audio, time, dt, sensitivity) {
    const flux = audio ? lowFlux(audio) : 0;
  ```
  → **audio 为 null 时退化为 0，不崩**，模式照常渲染。
- `src/components/visualizer/README.md` 对 tempera 明确写：
  「**Tempera 渲染层不消费音频**（`audioPower`/`audioBands` 只传给共享背景层）」
- lumiere 是明确吃音频的那个：`lumiereAudio.ts` 把 `audioBands.bass/treble/audioPower`
  归一化到 0..1（主播放器 0..255、预览 0..1）并做起音/释放平滑。
- 大量模式是**纯时间/歌词驱动**：
  - partita：`partitaLayout.ts` 分栏布局 + `PartitaChunk` 逐字动画
  - cadenza：`cadenzaEnvelopes.ts` 时序包络
  - sonnet/tempera：`create*PixiRuntime.ts`「scene cache ±1、**绝对时间驱动**」；
    `temperaProgram.ts` 编译期分镜（121 种 shot kind）
- 封面颜色本来就是一等输入：`worker/generate-theme.ts`「AI 主题生成：基于歌曲情绪与歌词内容
  生成沉浸式背景与视觉参数」。

→ **模式 A 结论：可行。** 选时间/歌词/主题驱动的模式
（partita / cadenza / tempera / sonnet / monet / claddagh / tilt），
跳过 lumiere 的音频响应与共享背景层的频谱响应即可。**歌名+进度+封面主色完全能驱动。**

⚠️ 一个容易搞错的点：`src/hooks/useMediaSessionBridge.ts` 是**出站**的
（`publishMediaSessionTrack` + `setActionHandler('play'/'pause'/'previoustrack'/'nexttrack')`，
把**自己**的播放状态广播给系统），**不是读取外部会话**。对模式 A 不能直接复用。

（顺带：folia 的"Now Playing 接入"是接 `Widdit/now-playing-service` 这个**本机 HTTP 服务**，
靠它抓外部播放器的歌曲+时间轴+歌词。Android 上没有等价物，我们已有的 MediaSessionMonitor 就是我们的对应物——
但它不带歌词。）

### 5.3 模式 B

模式 B 才有 PCM，folia 全部 14 个模式 + 频谱背景才完整可用。

---

## 6. 性能：全屏 WebGL 常亮的开销与开关

### 6.1 Mineradio —— 文档很硬核，但**是为低配 Windows 设计的**

`docs/LOW_SPEC_OPTIMIZATION_DOCTRINE.md`（17 KB）摘要：

- 帧率：「`45 / 60 / 75 / 90 / 120` 只作为用户在高级性能设置里主动选择的**固定上限**，
  不得静默替代默认 VSync」
- 后台：「后台/最小化进入深度降载，未播放和不可见任务及时暂停或释放」
- 每帧预算：「每个歌词更新帧最多放行 **1 个**新文字/可读/辉光纹理」
- **可停算**（2026-08-03）：「关闭状态不能只把透明度或强度设为零，**必须绕过投影、候选构建
  或 shader 增强分支**」
- UI 档位：`07-fx/05-fx-panel-performance.js` 有 `foregroundFpsMode` / `wallpaperFps` /
  `desktopLyricsFps` 三组

**能直接抄的只有两条**：可停算（关闭必须绕过分支）+ 后台/不可见降载。
帧率档位（45~120）对 60 Hz 手机屏没意义，需要自己加 30 fps 档。
其余（Electron 多进程、UAC、Mem Reduct、DWM、NVIDIA 40 系）与移动端无关。

### 6.2 folia-major —— 有真实投入，但同样缺"低档"和"可见性暂停"

- `src/utils/frameRateLimiter.ts`：全局 RAF 节流
  `VISUALIZER_FRAME_RATE_OPTIONS = [60, 90, 120]`，另有 `'off'`（不限）
  → **只有上限档，没有 30/低档**，手机常亮要自己加。
- 有画质分级：`lumiereRuntimeTuning.ts`「full / balanced / low
  **只降图形组 filter 分辨率、封顶烟雾倍频，文字保持满分辨率**」
  → 这个"降级不牺牲文字"的思路非常适合我们。
- 有持续投入（Realeco v0.7.18 release notes）：
  「歌词可视化更省资源：商籁、凝彩、绘光、心象、回环、莫奈、时计与 Latent 背景等模式
  减少每帧重复计算；时计齿轮装饰改用 WebGL2 绘制；**切换模式时及时释放图形上下文**」
- 有 guardrail（`src/components/visualizer/README.md` → Runtime guardrails）：
  「连续播放时间优先使用 MotionValue、ref、CSS/Motion、canvas 或 Pixi draw loop；
  **不要每帧写 React state/store**」
  → 这正是我们在 Compose 侧踩过的坑（组合期读动画状态 → 每帧重组 → 崩溃）的同源问题，
  说明对方踩过同一类雷。
- **缺口：没找到按页面可见性暂停渲染的统一开关** —— `frameRateLimiter` 只做帧率门控，
  不接 `visibilitychange`。常亮场景要我们自己加。

---

## 7. 维护状态与体积

| | Mineradio（-paused） | folia-major |
| --- | --- | --- |
| stars | 11074 | 5493 |
| 主语言 | JavaScript | TypeScript |
| 创建 | 2026-06-02 | 2025-11-28 |
| 最近 push | **2026-09-21**（"发布 Mineradio 长期停更声明"） | **2026-10-12**（昨天） |
| 停更声明 | README 顶部「**项目状态：长期停更**」 | 无，活跃 |
| 贡献者 | commit 列表基本只有 XxHuberrr | all-contributors **35 人**；有 PR 流程（#611/#612/#613/#619）、CI（github-actions[bot] 同步代码地图） |
| 版本 | 2.2.0（历史正式版，不再更新） | v0.7.18，Realeco / Limo / Cielo 三通道 |
| 仓库体积 | **6.9 MB**（API `size`） | **167 MB**（含 img/ 与 docs/；`package-lock.json` 627 KB） |
| 大型前端资产 | `three.r128.min.js` 603 KB、`index.css` 436 KB、`index.html` 129 KB、`skull-decimation-points.bin` 1.05 MB、`gsap.min.js` 73 KB | `mods/visualizer52hz/vendor/pixi.min.mjs` 819 KB、`img/visualizer.png` 3.6 MB |
| 运行时形态 | **Electron，Windows-only**（nsis，arch 仅 x64；`.bat`/`.ps1` 脚本） | Web（Vercel / Cloudflare / Docker）+ Electron（Win/macOS/Linux）+ PWA + Capacitor APK |
| Node 要求 | — | **>= 24**（`package.json` engines） |
| 安装包体积 | ⚠️ **未取得**：Release 页与 API 均未列出资产体积 | ⚠️ **未取得**：同上 |

体积的诚实说明：两个项目的 Release 页都没有给出资产体积，API 也被限流，
**没有拿到精确数字**。上表只给可核实的代理指标（仓库 size、vendored 依赖体积、Node 依赖规模）。
按 Electron 应用的常规量级，两者安装包都在百 MB 级——这一点是估计，不是实测。

---

## 8. 最终建议

### 8.1 总判断

> **放弃 Mineradio；folia 只借鉴设计、架构与性能原则，用 Compose 自己实现；
> 整体搬 WebView 只作为模式 B 下的备选，不做主干。**

### 8.2 模式 A（继续 MediaSession 遥控 QQ 音乐）—— 推荐 **Compose 自研**

理由：

1. folia 里能直接搬的只有"视觉"，而 folia 视觉里**最贵的那一半（频谱驱动）在模式 A 下拿不到输入**。
   剩下能用的都是"时间 + 歌词 + 主题"驱动的模式，而这些恰恰是纯 DOM/CSS/Motion 或 Pixi 的最简用法
   ——用 Compose 写成本更低，还省掉 WebView 的常驻内存与发热。
2. **"全屏歌词"在模式 A 下前提不成立**：MediaSession 不带歌词，必须额外接第三方歌词源
   （自建网易云/QQ 代理，或 amll-ttml-db）。这是合规 + 稳定性 + 网络依赖三重风险。
3. 模式 A 的现实目标应是：**全屏封面 + 封面取色 + 进度/播放状态驱动的背景动效**，
   歌词作为可选叠加层（先支持手动/本地 LRC）。

风险：视觉自研工作量；"歌词页"可能名不副实（建议先把"封面+进度驱动"做扎实，歌词后置）。

### 8.3 模式 B（终端自己播放）—— Web 版整体搬 WebView **才有性价比**，但仍倾向自研

搬运形态：folia 的 `vite build` 静态产物 + 自部署的网易云/QQ API → Android WebView 加载。
作者已给出 Capacitor 打 APK 的示范仓库 `folia-sonnet`，链路是通的。

代价与风险：

1. **AGPL 染整机**（GPL-3.0 §13 允许组合，但 AGPL 部分永久保持 AGPL；且 README 有"非营利"表述）。
2. **必须自建/自托管音乐 API**，否则没有音源。
3. **横屏适配全部重做** —— folia 是为竖屏手机与桌面窗口设计的，我们是 794×361 dp 横屏。
4. WebView 常驻 + 全屏 WebGL 的**发热与内存**，在车机/桌面终端常亮场景下是硬问题。
5. Electron 专有能力（壁纸模式、ffmpeg 导出、Folium 模组系统）在 WebView 里全部不可用。

### 8.4 明确不建议

- ❌ **整体搬 Mineradio 进 WebView**：数据是 Electron/Windows 专属（Wallpaper Engine、DWM、桌面图标层、
  PowerShell/UAC），歌词与播放全走内嵌 Node 服务，节拍分析必须拿到整首 PCM（模式 A 不可能），且项目已停更。
- ❌ **直接抄 Mineradio 的视觉实现**：`NOTICE.md` 明确主张"粒子视觉体验和电影镜头系统的产品表达
  属于作者原创设计"。借鉴思路可以，照搬有权利风险。
- ❌ 在当前阶段为模式 A 引入第三方歌词 API：收益（多一行歌词）< 成本（合规 + 运维 + 网络依赖）。

### 8.5 若采纳 Compose 自研，落地顺序

1. 把 `feature/media` 已有的 MediaSession 数据（title/artist/album/cover/duration/position/playing）
   接成"全屏页"的输入，先做**封面主色 + 进度驱动**的最小动效，**优先验证常亮功耗**。
2. 歌词作为可选第二层：先做本地/手动 LRC；第三方歌词 API 后置。
3. 把两条经验直接写成实现约束：
   - folia `lumiereRuntimeTuning`：**降级只降背景分辨率，文字保持满分辨率**
   - Mineradio 2026-08-03：**"可停算"——关闭必须绕过分支，不能只是把强度设成 0**
4. **帧率档位自己做 30/60**。两个项目的档位都是 60 起步（folia 是 60/90/120，Mineradio 是 45~120），
   都不适合手机常亮。
5. 加 `visibilitychange` / 页面不可见暂停渲染——**两个项目都没有这个统一开关**，我们不能没有。

---

## 9. 我没验证到的（诚实清单）

- 未取得两个项目的**精确安装包体积**（Release 页与 API 都未列出；API 已限流）。
- 未逐行读完 folia 的 14 个歌词模式，只核验了 README 代码地图 + 关键文件（raster / frameRateLimiter /
  MediaSession bridge / lyric-proxy / beatDetector）。
- 未实际在 Android WebView 里跑过 folia Web 版，**帧率与发热是推断，不是实测**。
- AGPL/GPL 组合的判断是**法条阅读，不是法律意见**。
- folia 的 `FluidBackground.tsx`（共享背景层）抓取失败，未确认它是 WebGL 还是 Canvas——
  若后续要借鉴背景实现，需要补这一份。
