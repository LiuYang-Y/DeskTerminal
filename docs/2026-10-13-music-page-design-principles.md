# 「音乐」页·全屏歌词舞台：设计原则与约束

日期：2026-10-13（2026-10-16 重写：删去对外部项目的逐条评估，只留对本项目仍有效的原则）

目标：把「音乐」页做成**全屏歌词 + 背景动效**，长期常亮。
本文只记录**落地约束**——它们来自一次「要不要基于第三方 Web 播放器重写」的评估，
评估本身已作废（结论：用 Compose 自研，见 `AI-HANDOFF.md` §8/§9），但下面几条原则仍然有效。

---

## 1. 前提：数据源决定天花板

`feature/media/MediaSessionMonitor.kt` 通过 `MediaSessionManager.getActiveSessions` + `MediaController`
能拿到：`TITLE / ARTIST / ALBUM / DURATION`、封面 `Bitmap`、`PlaybackState`（isPlaying / position / actions）、
`controller.packageName`。

**拿不到的两样东西**：

1. **歌词** —— Android `MediaMetadata` 不带歌词，必须自己接歌词源。
2. **音频采样（PCM / 频谱）** —— 跟随系统会话时永远拿不到。

这两条决定了所有后续判断：**凡是依赖整首 PCM 解码的动效（离线节拍分析那一类）在当前形态下做不了**，
只能做"时间 + 歌词 + 主题色"驱动的动效。

---

## 2. 落地约束（写代码时按这几条做）

### 2.1 降级只降背景，不降文字

画质档位只允许降**背景层**的分辨率 / 粒子密度 / 模糊半径；**歌词文字必须保持满分辨率**。
理由：常亮场景里文字是唯一"必须看清"的信息，背景只是氛围。

### 2.2 "关闭"必须绕过分支，不能只把强度设成 0

效果开关关闭时，要**跳过整个计算与绘制分支**（不构建候选、不进 shader 增强路径），
不能只把透明度 / 强度设成 0 后照常每帧计算。
—— 这条已在 `EffectTier`（= `LocalEffectTier`）里落实：OFF 的定义是"不建离屏层"，
与 LOW 共用一条静态路径，而不是"画出来但看不见"。参见 `MEMORY.md` 的效果档位约束。

### 2.3 帧率档位自己做 30 / 60

上游同类实现普遍是 60 起步（60 / 90 / 120）。**60 Hz 手机屏上 60 已是上限**，
常亮场景必须有 **30 fps** 档，否则发热与耗电不可接受。

### 2.4 页面不可见必须暂停渲染

要有统一的"页面不可见 / 息屏 → 暂停渲染循环"开关。
—— 常见缺口：帧率限制器只做帧率门控，不接 `visibilitychange`（Android 侧对应 `ON_PAUSE` / `Lifecycle`）。

### 2.5 不要每帧写状态

动画期间**不要每写一帧就推一次状态**（Compose 里就是"每帧重组"）。
长播动画优先用 `Animatable` / `withInfiniteAnimationFrameNanos` + 只读 `State` / `derivedStateOf`，
或直接画在 `Canvas` / `drawWithCache` 里。
—— 这条与 `topics/compose-glass-pitfalls.md` 记录的是同一类雷。

---

## 3. 与外部项目的关系

本页**不再引用任何外部播放器项目的源码**。曾评估过的项目（AGPL-3.0，上层应用照搬即传染整仓）
已明确不带入本仓库，评估细节不再留存。结论：**Compose 自研**，协议层与视觉层都是自己写的，
不依赖任何 AGPL 资产，无传染面。

WebView/PWA 承载第三方 Web 播放器的方案**已按实测否决**（播放中单核 CPU 71.4% vs 原生 5.4%，
详见 `AI-HANDOFF.md` §9 与 `docs/2026-10-14-music-phase0-native-playback.md`）。
