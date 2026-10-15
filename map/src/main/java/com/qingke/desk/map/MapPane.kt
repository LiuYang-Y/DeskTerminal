package com.qingke.desk.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingke.desk.core.data.GeoPoint
import com.qingke.desk.core.platform.Daylight
import com.qingke.desk.map.BuildConfig
import com.qingke.desk.map.navi.NaviShape
import kotlinx.coroutines.delay

/**
 * 地图面板 + 隐私闸门。
 *
 * 顺序是先弹隐私说明、用户同意之后才构造 `MapView` —— 反过来做在真机上就是
 * 先白屏一下再出图（官方原文："合规不通过地图将白屏"）。
 *
 * 拒绝的后果要说清楚：不同意也能继续用桌面终端的其它部分，只是这块位置没有地图。
 * 不写"不同意就退出" —— 那是在绑架用户，而且本项目也没有那个动机。
 *
 * @param host 单实例宿主（首页卡与全屏页共用同一个 MapView）。见 [MapViewHost]。
 * @param style 地图样式。**null（默认）= 日出日落自动切换**：白天 NORMAL、夜里 NIGHT。
 *              传具体样式则不自动切换（给调试预览页用）。
 * @param renderEnabled false = 停渲染（翻到别的页 / 别的屏时用）。
 * @param topInset 未同意隐私时，隐私卡顶部额外让出的高度（给悬浮齿轮让位）。
 * @param onConsentChanged 可选回调，让外层知道当前能不能显示地图（用于排布局）。
 * @param logoLeftMargin 高德 Logo 的左外边距。**全屏地图页专用**：地图铺满物理窗口后
 *                       Logo 在窗口左下角，会被常驻侧栏压住 —— 侧栏在左时传
 *                       "侧栏宽 + 间距"把它推出来。首页预览卡不传（0）。
 */
@Composable
fun MapPane(
    modifier: Modifier = Modifier,
    host: MapViewHost? = null,
    style: AMapStyle? = null,
    showMyLocation: Boolean = true,
    renderEnabled: Boolean = true,
    renderFps: Int = MAP_FULL_FPS,
    topInset: Dp = 0.dp,
    logoLeftMargin: Dp = 0.dp,
    onConsentChanged: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    // 同意状态走 AMapPrivacy 的流，而不是本地的 remember：
    // 用户可能在设置浮层里撤回同意，这边必须**立刻**把地图摘掉（见 AMapPrivacy.agreed）。
    val agreed by AMapPrivacy.agreed.collectAsState()
    LaunchedEffect(Unit) { AMapPrivacy.syncState(context) }

    val effectiveStyle = style ?: rememberAutoMapStyle()

    // Key 没配就别去构造 MapView：高德会返回 INVALID_USER_KEY，
    // 表现出来的就是一块白屏加一条只在 logcat 里的错误 —— 而本机 adb logcat 恒为空，
    // 等于没有任何线索。**把原因直接画在屏幕上**比什么都强。
    if (BuildConfig.AMAP_API_KEY.isBlank()) {
        AMapPlaceholder(
            modifier = modifier,
            text = "地图未配置 Key：在 local.properties 里填 amap.api.key 后重新编译",
        )
        return
    }

    if (agreed) {
        AMapCanvas(
            modifier = modifier,
            host = host,
            style = effectiveStyle,
            showMyLocation = showMyLocation,
            renderEnabled = renderEnabled,
            renderFps = renderFps,
            logoLeftMargin = logoLeftMargin,
        )
        return
    }

    AMapPrivacyCard(
        modifier = modifier,
        topInset = topInset,
        onAgree = {
            AMapPrivacy.agree(context)
            MapLog.i("privacy agreed at ${AMapPrivacy.agreedAt(context)}")
            onConsentChanged?.invoke(true)
        },
        onDecline = {
            MapLog.i("privacy declined")
            onConsentChanged?.invoke(false)
        },
    )
}

/**
 * 按日出日落选地图样式，并在翻转时刻自动更新。
 *
 * 为什么不做每分钟轮询：这台设备是 24 小时常亮的，而夜间↔白天一天只翻两次，
 * 所以协程直接睡到下一个翻转点（日出或日落），醒了再重新判定。
 * 只有结论真的变了才写 state —— 换一次 mapType 整层地图要重画，白翻一次很显眼。
 *
 * 天气 API 后来注入的 sunrise/sunset 会替换本地算出来的值（见 [Daylight.publish]），
 * 所以把 [Daylight.external] 当作 key：数据一到，协程重启，底色立刻跟着换。
 */
@Composable
private fun rememberAutoMapStyle(): AMapStyle {
    val external by Daylight.external.collectAsState()
    var style by remember { mutableStateOf(mapStyleForDaylight()) }

    LaunchedEffect(external) {
        // 每次进来先报一句当前判定（含日出日落与下一次翻转还要多久），
        // 这样在真机上不用猜"为什么是这个底色"。
        //
        // `source=` 是 2026-10-12 加的：本地算法与天气 API 给的日出日落只差一两分钟，
        // 光看分钟数分不清那一份到底是谁算的（真机排查时确实卡在这儿），
        // 于是把来源直接写进日志。探针注入（daylight:night/day/auto）也走同一条路。
        MapLog.i(
            "daylight now: ${style.name} ${Daylight.sunTimes()}, " +
                "source=${if (external == null) "local" else "api"}, " +
                "next switch in ${Daylight.millisUntilNextSwitch() / 60_000} min",
        )
        while (true) {
            val wanted = mapStyleForDaylight()
            if (wanted != style) {
                style = wanted
                val times = Daylight.sunTimes()
                MapLog.i(
                    "daylight -> ${wanted.name} " +
                        "(sunrise=${times.sunriseMinuteOfDay} sunset=${times.sunsetMinuteOfDay})",
                )
            }
            delay(Daylight.millisUntilNextSwitch())
        }
    }

    return style
}

/** 昼夜结论 → 具体地图样式。这张映射放在 :map 里，[Daylight] 只管日夜、不管地图。 */
private fun mapStyleForDaylight(): AMapStyle =
    if (Daylight.isDaylight()) AMapStyle.NORMAL else AMapStyle.NIGHT

/**
 * 隐私说明卡。
 *
 * 文字按高德官方《SDK 合规使用方案》里要求开发者向用户说明的口径写：
 * SDK 名称、第三方主体、使用目的、收集的信息类型、隐私政策链接。
 * 少写哪一项都属于披露不完整。
 *
 * @param topInset 顶部**额外**让出的高度。全屏页传"设置齿轮占掉的那条带"
 *                 （[com.qingke.desk.core.ui.DeskDimens.overlayChipBandHeight]）：
 *                 齿轮是常驻悬浮元素，压在标题上两边都读不清。
 *                 它与卡片自己的内边距取**大者**而不是叠加 —— 内边距 20 dp、
 *                 齿轮带 60 dp 的情况下叠加成 80 dp，只是白扔掉一截版面。
 */
@Composable
fun AMapPrivacyCard(
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    onAgree: () -> Unit,
    onDecline: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // 面板可能很窄（原来 3:4 那块只有 230 dp 宽），横向预算得先看一眼。
            val narrow = maxWidth < NARROW_PANEL_WIDTH
            val body = if (narrow) 12.sp else 14.sp
            val edge = if (narrow) 14.dp else 20.dp
            val topPadding = if (topInset > edge) topInset else edge

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = edge, end = edge, bottom = edge, top = topPadding),
            ) {
                Text(
                    text = "使用地图前请先同意隐私政策",
                    fontSize = if (narrow) 15.sp else 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(10.dp))

                // 正文可滚动；**按钮钉在滚动区之外**。
                // 这条是本项目踩过的坑：内容超高时，滚动区会把自己的最后一个孩子
                // 压成 0 高度，按钮直接消失（真机截图为证，见 :hosting 的 HostingNotice）。
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "地图由高德开放平台地图 SDK 提供，" +
                            "第三方为北京高德图强科技有限公司。",
                        fontSize = body,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "为了显示地图和定位，SDK 会收集：经纬度、设备信息" +
                            "（IP 地址、网络与 WiFi 状态、基站信息、传感器信息、OAID）、" +
                            "应用信息与设备参数、屏幕分辨率。信息由 SDK 在本机采集。",
                        fontSize = body,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "除此之外，桌面终端的其它功能不依赖地图 SDK，" +
                            "不同意也可以继续使用。",
                        fontSize = body,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "完整条款：https://lbs.amap.com/pages/privacy/",
                        // TextUnit 之间不能直接相减，先回到数值再换算。
                        fontSize = (body.value - 1f).sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (narrow) Arrangement.SpaceBetween else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDecline,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text("先不用", fontSize = body)
                    }
                    Button(
                        onClick = onAgree,
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        Text("同意并显示地图", fontSize = body)
                    }
                }
            }
        }
    }
}

/**
 * **首页**的地图预览卡（2026-10-12 新增）。
 *
 * 与全屏页 [MapPane] 的三点区别：
 *  1. 走 [AMapCanvas] 的预览模式：不响应拖动缩放、View 层吞掉触摸，
 *     点整卡是"进全屏页"而不是"操作地图" —— 否则和横滑翻页抢手势；
 *  2. 帧率锁在 [MAP_PREVIEW_FPS]（15），不可见时 [renderEnabled] 直接停渲染；
 *  3. 未同意隐私时渲染的是**精简占位**（[AMapPrivacyPlaceholder]），不构造 MapView，
 *     点它跳到全屏页看完整的隐私说明 —— 首页卡只有 409×313，塞不下整篇政策文本。
 *
 * @param onOpenFull 无论当前是地图还是占位，点击都走它：进全屏地图页。
 *                   **导航中它指向的就是导航画面**（地图屏在导航中画的是导航），
 *                   所以"点卡片回到全屏导航页"不需要额外的分支。
 * @param naviShape 导航中的整条路线几何。非 null 时卡片变成"跟着车走的导航预览"。
 * @param naviCar 车当前的位置。相机跟着它走。
 * @param naviCarBearing 车的行进方向，地图按它转。
 */
@Composable
fun MapPreviewCard(
    modifier: Modifier = Modifier,
    host: MapViewHost? = null,
    renderEnabled: Boolean = true,
    onOpenFull: () -> Unit = {},
    naviShape: NaviShape? = null,
    naviCar: GeoPoint? = null,
    naviCarBearing: Float? = null,
) {
    val context = LocalContext.current
    val agreed by AMapPrivacy.agreed.collectAsState()
    LaunchedEffect(Unit) { AMapPrivacy.syncState(context) }
    val style = rememberAutoMapStyle()
    // 20 dp 与首页卡片圆角（DeskDimens.cardCorner）一致；这里不引 :core:ui，
    // 免得给 :map 多加一条依赖只为取一个常量。
    val shape = RoundedCornerShape(20.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .clickable { onOpenFull() },
    ) {
        when {
            BuildConfig.AMAP_API_KEY.isBlank() -> AMapPlaceholder(
                modifier = Modifier.fillMaxSize(),
                text = "地图未配置 Key：在 local.properties 里填 amap.api.key 后重新编译",
            )

            !agreed -> AMapPrivacyPlaceholder(modifier = Modifier.fillMaxSize())

            else -> AMapCanvas(
                modifier = Modifier.fillMaxSize(),
                host = host,
                style = style,
                preview = true,
                renderEnabled = renderEnabled,
                // 跟车时把帧率提到 30。15 fps 下相机每秒挪一次、中间只有十几帧，
                // 车是"一跳一跳"地往前蹦，看着比不动还难受。
                //
                // 代价写在前面：卡片会持续加载瓦片，比 15 fps 的静态预览费电。
                // 这笔账该怎么算由"卡片是不是真的在动"决定 —— 不在导航中（naviCar
                // 为 null）就回到 [MAP_PREVIEW_FPS]；翻页 / 息屏时 [renderEnabled]
                // 仍会把整块渲染停掉，不是一直烧。
                renderFps = if (naviCar != null) MAP_FULL_FPS else MAP_PREVIEW_FPS,
                // 导航中**关掉 SDK 的定位层**（蓝点 + 三角形朝向）。
                //
                // 2026-10-12 真机反馈：卡片上同时出现"我的实际位置"和"车的位置"两个点，
                // 一眼看上去像是"导航在按我的真实位置跑"。实际上模拟导航的起点就是
                // 当前定位，两点当然重合，但界面上给出两个点就等于逼用户去猜哪个算数。
                //
                // 导航中的位置只应该有一个来源：NaviSession 给的车点（由 NaviMapOverlay 画）。
                // 它才是"引擎认为你在哪" —— 模拟导航里它沿路线走，真导航里它跟着 GPS，
                // 两者都不是"手机定位蓝点"。退出导航（naviShape == null）后这里恢复 true，
                // 卡片回到普通地图的样子。
                showMyLocation = naviShape == null,
                naviShape = naviShape,
                naviCar = naviCar,
                naviCarBearing = naviCarBearing,
            )
        }
    }
}

/**
 * 未同意隐私政策时的**精简占位**。
 *
 * 完整版是 [AMapPrivacyCard]（带整篇政策文本与同意/拒绝两个按钮），它放在全屏地图页 ——
 * 那里有 666×361 的位置。首页这张卡只有 409×313，塞进去会挤成一团小字。
 *
 * 合规上没有任何折扣：**不构造 MapView**（这才是关键，官方要求的是"构造前取得同意"），
 * 并且占位上写明了"地图由高德提供"与一句"点击查看完整说明"，点它去全屏页看完整政策。
 */
@Composable
fun AMapPrivacyPlaceholder(
    modifier: Modifier = Modifier,
    compact: Boolean = true,
) {
    Card(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val narrow = maxWidth < NARROW_PANEL_WIDTH
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (narrow) 14.dp else 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "地图由高德开放平台提供",
                    fontSize = if (compact) 15.sp else 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "尚未同意隐私政策，点击查看详情",
                    fontSize = if (compact) 12.sp else 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * 地图区域的占位（未同意 / 加载中）。给"这块地方现在没有地图"一个体面的样子，
 * 而不是留一块黑。
 */
@Composable
fun AMapPlaceholder(
    modifier: Modifier = Modifier,
    text: String = "地图未启用",
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

/**
 * 判"窄面板"的宽度阈值。
 *
 * **这一条刻意不改成按屏幕比例**（2026-10-13 自查结论）：它不是"屏幕有多宽"的判断，
 * 而是"这块面板自己需要多宽"的判断 —— 窄于 300 dp 时两栏并排会把文字压成两三个字
 * 一行，必须改成上下堆叠。这类"组件自身的可读性下限"与屏幕尺寸无关，写死是对的。
 *
 * 从原来散在两处的 `maxWidth < 300.dp` 提到这里并写清理由，是为了不再出现
 * "同一个魔法数字在两处各写一遍、改了一处忘了另一处"。
 *
 * 没有挪到 `core:ui` 的 `AdaptiveDimens` 里，是因为 :map 刻意不依赖 :core:ui
 * （见 map/build.gradle.kts：**这个模块不画自家的设计语言**），为这一个阈值
 * 破坏那条依赖约束不划算。
 */
private val NARROW_PANEL_WIDTH = 300.dp
