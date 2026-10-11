# 高德导航 SDK 能不能接？—— 核实记录与分步方案

日期：2026-10-12。**本文只做核实与方案，不动代码**；结论等用户确认后再执行。

核实原则照旧：**每一条都注明来源**，官方文档写了就写"官方写明"，没写就写"官方未写明"，不用推测填空。

---

## 一、结论速览

可以接，但有**三件事必须先确认**，其中两件只有你能在控制台/商务层面确认：

1. **依赖会冲突**：导航 SDK 自带 3D 地图 SDK，与现有 `3dmap-location-search` 不能并存，
   必须二选一（换成导航合包）。这一条是硬事实。
2. **控制台是否需为导航单独开通**：官方文档**未写明**是否需要企业认证或单独申请。
   我这边只能核实到公开文档这一层，需要你在控制台实际点一遍。
3. **商用授权**：官方《开发者使用注意事项》明确"法人或非法人组织使用平台服务应事先购买技术服务许可"，
   未购买时 Key 与配额"仅供短期、少量的测试目的"。个人自用属于灰区，仍需你自核。

---

## 二、核实到的事实

### 2.1 导航包与地图包的关系（硬约束）

官方「相关下载」页（<https://lbs.amap.com/api/android-navi-sdk/download>，页面标注 2026-09-24 更新）
明确列出导航一键下载包的构成：

```
开发包 → 导航包：
  · 定位SDK V11.3.101（com.amap.api.location）
  · 3D地图SDK V11.3.100（com.amap.api.map3d）
  · 导航SDK V11.3.100（com.amap.api.navi）
  · 搜索SDK V9.8.1（com.amap.api.search）
```

同页还列出了「合包下载」，其中有专门的**导航合包**（"合包包含多个产品功能，自动合并代码，
大大减少了包体积"）。

同一事实在第三方资料里反复出现（官方文档中该句出现在历史版本的集成说明里）：
"navi 导航 SDK 5.0.0 以后版本包含了 3D 地图 SDK，所以请不要同时引入 map3d 和 navi SDK"。
实际表现是 `duplicate class`（两个包都带 `com.amap.api.maps.**`）。

**对我们的影响**：`:map` 现在依赖
`com.amap.api:3dmap-location-search:10.1.300_loc6.4.9_sea9.7.4`，
要接导航就得把它**换掉**（换成导航合包），而不是新增一个依赖。
换掉之后地图能力由导航包提供，我们要回归验证现有地图屏（昼/夜样式、定位小蓝点、手势）不受影响。

### 2.2 版本与 32 位

- 官方下载页：**Android 导航 SDK V11.2.000 起不支持 32 位**。
- Maven 上 `com.amap.api` 系列目前查到的固定版本仍是 `10.1.300` 档（无 11.x），
  要用 11.x 只能下官方 zip 手动放进 `libs` —— 这与当初选 Maven 的理由（可复现、不往仓库塞二进制）冲突，
  需要重新取舍。导航在 Maven 上的确切坐标与版本**我尚未核实**，列在"待确认"里。

### 2.3 Key

官方「获取Key」页（<https://lbs.amap.com/api/android-navi-sdk/guide/create-project/dev-sdk>）写的是通用流程：
控制台创建应用 → 添加 Key → 绑定服务选「Android平台SDK」→ 填发布/调试两个 SHA1 与 Package。
**与地图 SDK 是同一个平台类型**，页面上**没有**"导航需要单独申请/企业认证"的说法。

同页两条注意事项：
- "1 个 KEY 只能用于一个应用（多渠道安装包属于多个应用），1 个 Key 在多个应用上使用会出现服务调用失败。"
- package 必须与 `applicationId` 完全一致，否则 `INVALID_USER_SCODE`（我们踩过这个坑）。

### 2.4 权限（官方原文清单）

官方「其他配置注意事项」页
（<https://lbs.amap.com/api/android-navi-sdk/guide/create-project/configuration-considerations>，
标注 2025-05-12 更新）给出的清单里，相对现状**新增**的有：

| 权限 | 官方说明 |
|---|---|
| `CHANGE_WIFI_STATE` | 用于网络定位（现状：`:map` 已声明，`app` 模块刻意没声明） |
| `ACCESS_BACKGROUND_LOCATION` | 后台获取位置信息，**若需后台定位或持续导航则必选** |
| `ACCESS_LOCATION_EXTRA_COMMANDS` | 用于申请 A-GPS 模块，卫星定位加速（现状已声明） |
| `BLUETOOTH` | 导航组件页面的蓝牙连接提醒，连接后可用蓝牙通道做 TTS 播报 |
| `WAKE_LOCK` | 导航状态中保持屏幕常亮（现状已声明） |
| `WRITE_SETTINGS` | "允许写设备缓存，用于问题排查" —— 这条我们**不打算声明**，理由见下 |

`ACCESS_BACKGROUND_LOCATION` 是这里最重的一条：它是**运行时权限**，而且 Android 11+
必须引导用户去系统设置里选"始终允许"，不能在弹窗里一步拿到。对一个"摆桌上的桌面终端"来说，
要不要为了持续导航开口子，是个需要你拍板的产品决定。

`WRITE_SETTINGS` 官方标注是"问题排查"用的可选权限，属于宽口径权限，
我们的口径是"能不用就不用"（同 `QUERY_ALL_PACKAGES`），计划不声明。

### 2.5 隐私合规（与地图不同，容易踩）

导航 8.1.0+ 起要求：

```java
NaviSetting.updatePrivacyShow(context, true, true);   // 必须在 AMapNavi.getInstance 之前
NaviSetting.updatePrivacyAgree(context, true);        // 同上
```

并且：

```java
try { mAMapNavi = AMapNavi.getInstance(getApplicationContext()); }
catch (Exception e) { e.printStackTrace(); }           // 官方要求捕获异常
if (mAMapNavi != null) { /* TODO */ }                  // 并做空判断
```

注意类名是 **`NaviSetting`**，不是地图那边的 `MapsInitializer`。我们已有的
`:map/AMapPrivacy.kt` 是地图那一套，接导航时要**合并成一个统一的合规闸门**，
而不是两处各弹一次窗、各存一份状态。

### 2.6 语音播报

导航 SDK **自带语音播报**，不需要额外接 TTS：

- 导航组件参数里有 `params.setUseInnerVoice(true)`（官方示例原文）。
- 混淆规则里要求保留 `com.alibaba.idst.nui.*` / `com.alibaba.mit.alitts.*`（阿里语音包），
  说明语音能力是随导航 SDK 一起分发的。

蓝牙通道播报另需 `BLUETOOTH` 权限（官方说明如上）。

### 2.7 两种接入形态（对我们都可行）

官方概述页给了两个入口，正好对应两种产品取舍：

1. **导航组件**：`AmapNaviPage.getInstance().showRouteActivity(context, params, activity)`
   —— 拉起一个**自带完整导航界面**的 Activity，路径规划、转向提示、语音全包。
   我们只需要传起终点。**代价**：它是高德自己的界面，不是我们的暗色横屏布局。
2. **`AMapNaviView` 完全自定义 UI 和图面** —— 可以嵌进 Compose，
   但转向箭头、车道线、路况条、实景图/模型图、自车位置、摄像头提示这些
   **全都要自己画**（官方 Demo 里这些组件是开源的，但那是参考实现，不是拿来即用）。

---

## 三、待你确认的三件事

| # | 事项 | 为什么需要你确认 |
|---|---|---|
| 1 | 控制台里是否要为本应用**单独开通导航服务**、是否要求企业认证 | 官方文档未写明，我无法从公开资料证明；你在控制台点一遍最准 |
| 2 | 商用授权（技术服务许可） | 官方条款写"法人或非法人组织应事先购买"，个人自用属灰区。此前的"高德车载场景条款"也还在等你核实 |
| 3 | 导航形态选哪种 | 组件（自带界面、最快可用）vs 自定义 `AMapNaviView`（能进我们的横屏布局、工作量在整个导航 UI 上） |

---

## 四、确认后的分步方案（尚未执行）

**Step 1 — 换依赖，先保回归。**
把 `:map` 的 `3dmap-location-search` 换成导航合包（含 3D 地图 + 导航 + 定位 + 搜索）。
编译期先解决 `duplicate class`。**这一步结束必须先回归现有地图屏**：昼/夜样式、定位小蓝点、
手势缩放拖动、息屏回来看得到 —— 因为底图换了来源，这些都可能静默变化。

**Step 2 — 合规闸门合并。**
把 `NaviSetting.updatePrivacyShow/updatePrivacyAgree` 并进现有 `AMapPrivacy`，
保证"同意一次、两处生效、撤回一次、两处都停"，并且都在 `getInstance` 之前。

**Step 3 — 补权限。**
只补真正用到的。`ACCESS_BACKGROUND_LOCATION` 若要开，需要在应用内提供
"去系统设置选始终允许"的引导（照现有 `MediaPermission` 的做法：只跳转、不代按）。

**Step 4 — 先用导航组件跑通端到端。**
用 `AmapNaviPage.showRouteActivity` 从地图屏拉起一次真实导航，
验证：Key 是否够用、语音是否出声、返回后本应用是否正常恢复。
这一步不改我们自己的布局，能用最小代价回答"到底能不能用"。

**Step 5 — 再决定要不要自定义。**
如果 Step 4 的界面形态可以接受，就停在那里；
如果要嵌进 361 dp 横屏布局，再评估 `AMapNaviView` —— 那时的工作量主要在导航 UI 本身
（转向图标、车道线、路况条、路口大图），需要单独排期，不是"加个 View"。

**Step 6 — 文档与合规回归。**
导航 SDK 收集的个人信息范围与地图 SDK **不是同一份清单**（官方下载页列的是导航的版本，
包含"搜索词"等），README 的隐私章节要相应更新。

---

## 五、明确尚未核实的东西（不猜）

- 导航合包在 Maven Central 上的确切坐标与最新版本号（官方下载页只给 zip）。
- 导航是否需要在控制台单独开通、是否限企业认证（公开文档未写）。
- 11.x 导航包在我们这台机器（Android 16 / 64 位）上的实际运行表现。
- 导航过程的流量/配额消耗量级（关系到是否需要商用授权）。
