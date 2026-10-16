# 音乐 Phase 0（路线 B）· 第一步：会员问题核查

日期：2026-10-14　分支：`spike/music-phase0-qq`（**严禁合入 master**）
范围：**只做核查与诊断**，不写 UI、不写播放器。结论给"要不要往第二步走"用。

> ### 凭证红线（本次遵守方式）
> 本文件**不含**任何凭证值：无 Cookie、无 `musickey`、无 UIN、无会话密钥、无签名 URL。
> 凭证只在**本机内存**里被读取一次（解密本机已有的 `.verify/` 下会话存档），
> 用完即弃，未落任何仓库/文档/日志。文中所有读数据只有三类：**布尔**（有/无）、
> **码率前缀**（M500/M800/F000）、**字节数**。

---

## 0. 结论速览

| # | 问题 | 结论 | 证据强度 |
| - | --- | --- | --- |
| 1 | 登录账号类型 | **QQ 通道**（修复 `/login/channels` 后用 QQ 扫码）；凭证 `loginType = 2`，与上游参考实现注释「`qq` 通道送 `tmeLoginType 6`、收回 `loginType 2`」一致 | 文档 + 会话实测 |
| 2 | 会员标志字段 | 凭证里**没有**可靠的会员标志（`isPrized = -1`，全项目零处消费）。**权威判据是"能不能取到 320k / 无损"**，不是任何单一标志位 | 实测 |
| 3 | `new_json=0` 打错了哪个字段 | **没有**。它改的是**搜索接口**（`c.y.qq.com/soso/...`），与取链接完全无关；且应用侧**根本没调用**那条路由。**它不可能是"会员歌/链接解析异常"的原因** | 三处证伪 |
| 4 | **会员歌能否拿到完整 CDN URL** | **能。** 付费歌（`paypay=1`）实测 **20/20** 取到非空链接，其中抽查的 8 首**实际字节数 = 接口声明的全曲大小**（非 0.92 MB 试听片段）；无损（严格 VIP）**4/4** 取到 | 实测（本机合法凭证） |
| 5 | 是否触发停止条件 | **未触发**。合法凭证下能拿到会员歌 → 可以继续第二步 | — |

**一句话**：会员歌这条路**是通的**，且通得比预期好（全曲 + 320k + 无损都能拿）。
之前怀疑的 `new_json=0` 是**冤枉**的 —— 它既不在取链接的链路上，应用侧甚至不调用它。

---

## 1. 登录账号类型与会员标志

### 1.1 通道 = QQ（不是微信）

先前记录（`.verify/` 下 2026-10-14 的登录修复报告）已定案：
"扫码跳微信"的根因是前端拿到的是**微信码**（`/login/channels` 落到短生命周期服务端、只声明 wechat），
把 `/login/channels` 改成声明 `qq` 之后，二维码由 `≈63 KB jpeg` 变为 `≈598 B png`（QQ 码），
登录成功、昵称 786。

本次从**本机会话**直接读到（只报非敏感判定）：

| 读据 | 值 |
| --- | --- |
| `loginType` | **2** |
| 账号 id | 10 位（值隐去） |
| `musickey` 长度 | 159 |
| `musickey` 有效期 | 约 **+59 天** |
| 会话 `expiresAt` | 约 **+2 天** |

`loginType = 2` 与上游参考实现的源码注释一致（`qqProvider.ts`）：
> `qq` 是 QQ 扫码登录通道的 canonical 名字（旧名 `mobile`）……协议本身没变：**仍送 `tmeLoginType 6`、收回 `loginType 2`**。

⚠️ 一条容易误读的前提（此处再钉一次）：
**"扫码通道" ≠ "用什么账号登录"。** 微信码 / QQ 码都只是 QQ 音乐的一次扫码授权，
扫完登录的仍是**同一个 QQ 音乐账号**；会员权益与歌单**跟账号走，不跟通道走**。

### 1.2 会员标志字段：没有单一权威位

凭证对象里唯一像会员位的字段是 `isPrized`，取值为 **-1**；
在 `@yakult-green-tea/qq-music-api` 全包内**零处被消费**（不是判据，只是透传字段）。

真正的会员判据是**能力型**的：拿得到 320k / 无损 = 有会员能力（见 §4）。
本次实测该凭证**无损档位可用**，因此这条凭证具备 VIP 级能力。

---

## 2. 凭证字段 与 歌曲付费字段

### 2.1 凭证字段（只在哪、是什么名字，不含值）

登录态在本机共三处（`.verify/` 下 2026-10-14 已审计，本次复核仍在）：

| 位置 | 字段名 | 本次用途 |
| --- | --- | --- |
| `.auth-state/qq-session.json`（AES-256-GCM 加密） | `credential.openid / refresh_token / access_token / musickey / musicid / str_musicid / loginType / refresh_key / expired_at …` + `device.{qimei,qimei36,androidId,openUdid,imei,model,sessionUid,sessionSid,sessionVkey}` + `token` / `expiresAt` | 解密后取 `token` 交给包内的会话解析器 |
| `.env.local` | `QQ_SESSION_SECRET` | 解密上面那份 |
| WebView localStorage | `online_provider:qq:cookie` = `qqmusic_session=…` | 本次未使用（设备是小米，登录是在一加上做的） |

发出请求时的凭证形态（包内 `credentialCookieHeader`），字段名固定为四个：
`uin` / `qqmusic_uin` / `qm_keyst` / `qqmusic_key`（后两个同为 `musickey`）。

### 2.2 歌曲付费字段：在**搜索**响应里，判据是 `pay.payplay`

匿名搜索 `client_search_cp`（`new_json=0`）单条结果的**完整**付费/试听相关字段（实测原样）：

```
songmid      = '000lv3Zi13dSVA'
media_mid    = '003LQxuJ10jo46'      # ← 注意：snake_case
strMediaMid  = '003LQxuJ10jo46'      # ← 同一个值的另一个名字
size128      = 4045199
size320      = 10112712
sizeape      = 0
sizeflac     = 55648896
interval     = 252                    # 秒
pay.payplay       = 1                 # ← 1 = 付费/需会员整曲；0 = 免费
pay.paydownload   = 1
pay.payalbum      = 0
pay.payinfo       = 1
pay.paytrackmouth = 1
pay.paytrackprice = 200
preview.trybegin  = 67212
preview.tryend    = 96033
preview.trysize   = 960887            # ← 30 秒试听片段的字节数（0.92 MB）
```

**判据用法**：`pay.payplay == 1` 就是"会员/付费歌"的机器可读标志；
`preview.trysize` 是**试听片段**的大小 —— 拿到链接后用它做"全曲还是试听"的对照尺。

---

## 3. `new_json=0` 补丁到底是干什么的（三处证伪）

用户问的"`new_json=0` 读错了哪个字段" —— 核查结论是：**它没有读错任何字段，也够不到链接解析。**

### 证伪一：它只改搜索，且改动只有一行

`@yakult-green-tea/qq-music-api/dist/src/services/search/getSearchByKey.js:15`：

```js
new_json: 0, /* upstream-regression: new_json=1 makes QQ search return 0 results. */
```

### 证伪二：它连"应用搜索"都不是 —— 应用侧根本没走这条路由

- 全树检索（排除 `node_modules` / `dist` / `dev-dist`）：`getSearchByKey` 只出现在
  `dev/feasibility/qqApiPlugin.mjs` 的**注释**里，`.ts/.tsx` 源码**零调用**。
- 上游参考实现的真实搜索走的是另一个端点（`qqLyricProvider.ts:searchQQLyrics`）：
  `POST music.search.SearchCgiService / DoSearchForQQMusicLite`，**没有 `new_json` 参数**。
- 也就是说：`new_json=0` 补丁只对"直接 `curl /getSearchByKey`"这条**诊断路径**有意义。

### 证伪三：取链接的链路里根本没有 `new_json`

两条取链接路径（都实测读过源码）：

| 路径 | 触发条件 | 端点 / 方法 | 有没有 `new_json` |
| --- | --- | --- | --- |
| 带凭证 | 有会话 | `musicu.fcg` · `music.vkey.GetVkey / UrlGetVkey` | **无** |
| 匿名 | 无会话 | `musicu.fcg` · `vkey.GetVkeyServer / CgiGetVkey` | **无** |

两边拼 `filename` 的规则一样：`prefix + songmid + (media_mid ‖ songmid) + ext`；
码率前缀 `M500 / M800 / F000 / C400 / A000`（128k / 320k / flac / m4a / ape）。

### 我另外自己验了一遍 `new_json` 的真实效果（匿名只读）

| 参数 | `song.totalnum` | `song.curnum` | 返回条数 |
| --- | ---: | ---: | ---: |
| `new_json=0` | 346 | 6 | 5 |
| `new_json=1` | 0 | 0 | 0 |

→ 上游回归属实、补丁有效；但**它是"搜索返回空"，不是"链接解析异常"，而且应用侧用不到它**。

### 顺手证伪掉我自己的一个假设（`media_mid` 漏读）

我在上游参考实现里发现一处**真实的字段错配**：`searchQQLyrics` 把搜索结果映射成 `SongResult` 时
**没带 `mediaMid`**，而 `normalizeQqSong` 读的是 `file.media_mid` / `item.mediaMid` /
`providerData.mediaMid` —— 全都取不到，于是 `mediaId` 为空，`filename` 第二段退回 `songmid`。

按包内注释"只送一段会 CDN 403"，我原本怀疑这就是"链接解析异常"的真身。**实测把它推翻了**：

| `mediaId` 取值 | 128k | 320k |
| --- | --- | --- |
| `media_mid`（正确） | 非空 206 / 4.12 MB | 非空 206 / 10.29 MB |
| `""`（上游参考实现的实际行为） | 非空 206 / 4.12 MB | 非空 206 / 10.29 MB |
| `songmid`（另一种兜底） | 非空 206 / 4.12 MB | 非空 206 / 10.29 MB |

三种取值**结果完全相同**（含字节数）。合理解释：QQ 服务端**返回的是规范化后的 purl**，
并不照抄请求里的 `filename`。所以 `media_mid` 漏读是一条**代码卫生问题，不是 bug** ——
记下来是为了将来重写时别照抄这段绕路，**不能**当成"链接异常的根因"。

---

## 4. 会员歌能否拿到完整可播的 CDN URL（核心，实测）

**方法**：用本机已有会话（`qrLoginService.getMusicPlay(token, songmid, quality, mediaId)`，
即包内 `music.vkey.GetVkey/UrlGetVkey` 那条**标准带凭证接口**），对付费歌取链接；
再用 `Range: bytes=0-0` 读回 `Content-Range` 拿**真实总字节数**，与搜索声明的
`size128` / `size320` / `preview.trysize` 对照。

### 4.1 抽查：`payplay=1` 的 8 首，逐首对字节数

| 歌名 | 声明 size128 | 128k 链接 | 128k 实际 | 声明 size320 | 320k 链接 | 320k 实际 | 试听片段 |
| --- | ---: | --- | ---: | ---: | --- | ---: | ---: |
| 晴天 | 4.12 MB | M500 | **4.12 MB** | 10.29 MB | M800 | **10.29 MB** | 0.92 MB |
| 晴天 (Live) | 3.81 MB | M500 | **3.81 MB** | 9.53 MB | M800 | **9.53 MB** | 0.92 MB |
| 晴天 (Live) | 4.57 MB | M500 | **4.57 MB** | 11.42 MB | M800 | **11.42 MB** | 0.92 MB |
| 晴天 | 4.10 MB | M500 | **4.10 MB** | （无 320k 源） | 空 | — | 0.92 MB |
| 晴天 (Live) | 3.65 MB | M500 | **3.65 MB** | （无 320k 源） | 空 | — | 0.92 MB |
| 稻香 | 3.41 MB | M500 | **3.41 MB** | 8.53 MB | M800 | **8.53 MB** | 0.92 MB |
| 青花瓷 | 3.65 MB | M500 | **3.65 MB** | 9.13 MB | M800 | **9.13 MB** | 0.92 MB |
| 稻香 (Remix摇滚版) | 2.18 MB | M500 | **2.18 MB** | 5.46 MB | M800 | **5.46 MB** | 0.92 MB |

**读法**：
- **128k：8/8 的"实际字节数"与"声明的全曲大小"逐字节吻合**（差值 < 4 KB）；
- **没有任何一首返回 0.92 MB 的试听片段**（8/8 都远大于 1.5× 试听片段）；
- 320k：**凡声明有 320k 源的全部拿到 M800 全曲**；余下 2 首的 320k 为空，是因为
  **该条目根本没有 320k 源**（`size320` 缺），不是会员不足。

### 4.2 大样本：付费歌链接可用率

对 10 个关键词下所有 `payplay=1` 的歌逐首取 128k 链接：

```
n = 20    非空 = 20    空 = 0    抛错 = 0
```

→ **20/20 全部拿到非空链接，零空链接。**

### 4.3 无损（严格 VIP）档位

无损是**只有会员才拿得到**的档位。挑声明有 `sizeflac` 源的 4 首：

```
晴天 52.8MB → F000 非空      晴天(Live) 51.9MB → F000 非空
晴天(Live) 36.3MB → F000 非空   夜曲 25.5MB → F000 非空
n = 4，拿到链接 4 首
```

→ 凭证具备**无损级**能力（严格 VIP 判据）。

### 4.4 结论

> **在拥有合法凭证的前提下，会员（付费）歌曲经标准客户端接口可以正常拿到完整可播的
> CDN 直链** —— 既不是试听片段，也不是空链接。而"免费歌"的 320k 反而可能为空
> （因为该条目没有 320k 源）。这恰好说明：**"空链接"不能反向推断成"会员不足"。**

---

## 5. 真正的"字段陷阱"：空链接被后端抹平了原因

这才是取链接链路上唯一真实的"字段读错"：**后端把空链接的原因写死成一个字符串。**

- 带凭证路径（`qrLogin.js:489-492`）：
  `playUrl[mid] = { url: purl ? domain+purl : '', error: purl ? false : '暂无播放链接' }`
- 匿名路径（`controllers/getMusicPlay.js:117-121`）：
  `error: !item.purl && '暂无播放链接'`
- 上游参考实现也在 `qqProvider.ts:305-310` 留了更正注释：
  > 后端把每个文件的原因**替换成一个固定字符串**，所以空 `purl` 只能证明"这次没链接"，
  > **绝不能读成会员 / 地区 / 下架**。

**含义**：`purl` 为空时，我们**无法**从响应里区分"没会员""该音质不存在""地区限制""被下架"。
上游只有两个信息位：`purl` 有没有、`midurlinfo` 里那一坨。要细究只能靠**交叉验证**
（换音质再试 / 换歌试），不能靠那个字符串。

---

## 6. 对原生重写（路线 D / Phase 0）的可操作结论

1. **`new_json` 与取链接无关** —— 原生实现里，搜索用哪个端点、取链接用 `UrlGetVkey`，
   两件事别混。原生若自己写搜索，请**直接对齐应用真正用的**
   `music.search.SearchCgiService`，而不是 `new_json=0/1` 那条老接口。
2. **付费判定读 `pay.payplay`**（1=付费），并在拿到链接后用 `preview.trysize` 与
   实际字节数对照，作为"全曲 / 试听"的自动判据 —— 这条能做成**能证伪的自检**。
3. **不要依赖 `media_mid` 一定存在**：它漏了也不影响（§3 实测），但重写时读取应
   「`media_mid` → `strMediaMid` → 兜底 songmid」三级，别照抄那个绕路分支。
4. **空链接要按"未知"处理**，不要显示成"需要会员"（后端把原因抹平了）。
5. **会员能力判据用 `320k` / `flac` 是否可用**，不要找某个布尔标志位（`isPrized` 不可信）。

---

## 7. 未做 / 风险 / 下一步

**本次未做**（有意）：
- 未做本地/设备侧的**音频播放**（那是第二步：ExoPlayer 真机播免费歌 + 会员歌）。
- 未测"**明确要求单曲购买**（非会员可购、非 VIP 直接可听）"的数字专辑极个别曲目；
  本次样本覆盖的是"付费/会员曲"（含周杰伦多首），已足以回答闸门问题。
- 未复用 WebView localStorage 那份 cookie（登录在一加上做的，当前只连着小米）。

**风险**：
- 会话 `expiresAt` 约 **+2 天**。第二步（ExoPlayer 真机播放）应趁会话有效期内做，
  否则需要重新扫码登录。
- `loginType=2` 的"2 是否等于 QQ"是**跟随上游参考实现注释**的判断；若要 100%
  自证，需在第二步用 App 内展示的昵称/账号页核对一次。

**下一步（待你拍板）**：第二步 —— 真机 ExoPlayer 播**免费歌 + 会员歌**，跑 Phase 0 五条断言
（`purl` 非空 / 能打开 / **`currentPosition` 单调推进且出声** / CPU<20% / 换歌复现），
外加 30 分钟不插线功耗测试。
