package com.qingke.desk.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingke.desk.core.data.NaviPlace
import com.qingke.desk.core.data.ShellSettings
import com.qingke.desk.core.ui.AdaptiveDimens
import com.qingke.desk.core.ui.DeskDimens
import com.qingke.desk.core.ui.DeskGlass
import com.qingke.desk.core.ui.LocalDeviceProfile
import com.qingke.desk.core.ui.deskGlassOverlay
// 扩展属性要单独 import：`import DeskGlass` 只把那个 object 带进来，
// 挂在它上面的 `val DeskGlass.xxx` 仍在 core:ui 包里（SideBar.kt 同样是显式导入的）。
import com.qingke.desk.core.ui.mapScrimAlpha
import com.qingke.desk.core.ui.overlayCardAlpha
import com.qingke.desk.map.navi.NaviSession
import com.qingke.desk.map.navi.PlaceSearch
import com.qingke.desk.map.navi.PlaceSuggestion
import com.qingke.desk.map.navi.RouteOption
import com.qingke.desk.map.navi.RouteState
import kotlinx.coroutines.delay

/**
 * 全屏地图页里的导航交互状态。
 *
 * 为什么单独抽一个类、而不是散在 [DeskRoot] 里的一堆 `remember`：
 * 它要被**两个屏**读到 —— 地图屏（搜索 + 预览）和导航屏（显示目的地、退出后回到同一份状态）。
 * 屏之间传值用参数或 rememberSaveable 都可以，但真正的需求是"一整块状态共享"，
 * 抽出来最省事，也避免出现两份 destination 各说各话。
 *
 * 注意**这里不含 destination**：目的地属于导航会话（[NaviSession]），
 * 而不是"这个搜索框现在长什么样"。两者混在一起的话，切一次屏就得把目的地
 * 从界面状态搬到持有它的那一层，搬漏一处就成了"界面说有、会话说没有"。
 */
class NaviUiState {

    /** 输入框里的字。 */
    var query by mutableStateOf("")

    /** 联想列表是否展开。 */
    var expanded by mutableStateOf(false)

    /** 联想结果。 */
    var suggestions by mutableStateOf<List<PlaceSuggestion>>(emptyList())

    /** 正在请求（搜索或补坐标）。驱动转圈与"禁止重复点"。 */
    var busy by mutableStateOf(false)

    /** 给人看的一行提示（失败原因、"这个结果暂无坐标"等）。null = 不显示。 */
    var notice by mutableStateOf<String?>(null)

    /** 选中的目的地在列表里的位置，用于高亮。 */
    var highlightIndex by mutableStateOf(-1)

    fun reset() {
        query = ""
        expanded = false
        suggestions = emptyList()
        busy = false
        notice = null
        highlightIndex = -1
    }
}

/**
 * 地图屏上的导航搜索层。
 *
 * 三个交互块，都在内容区（786×411 这一级预算）里：
 *   1. **底部搜索条**：一行，点它展开输入；展开时键盘弹上来会盖住下半屏，
 *      所以列表放在**屏幕中上部**、并且限高（[LIST_MAX_HEIGHT]）；
 *   2. **联想列表**：每行 48 dp，显示"名称 + 行政区/地址"；
 *   3. **路线预览卡**：选中目的地后出现，只放三个数（距离 / 时长 / 红绿灯）+ 两个动作。
 *
 * 搜索请求带 300 ms 防抖：这台机器是触摸屏上的软键盘输入，逐字母发请求既费流量也会闪回。
 */
@Composable
fun NaviSearchLayer(
    state: NaviUiState,
    /**
     * 当前目的地（已带坐标），来自 [NaviSession]。null = 还没选，预览卡不显示。
     */
    destination: PlaceSuggestion?,
    city: String,
    homePlace: NaviPlace?,
    workPlace: NaviPlace?,
    /**
     * 选中了一个**已经带坐标**的目的地。
     *
     * 注意是"带坐标"才回调 —— 补坐标失败的那条路不会走到这里，
     * 调用方因此可以放心地把它直接当算路终点，不用再判空。
     */
    onPicked: (PlaceSuggestion) -> Unit,
    onSaveHome: (PlaceSuggestion) -> Unit,
    onSaveWork: (PlaceSuggestion) -> Unit,
    onStartNavi: () -> Unit,
    /**
     * 用户点了"算了"：把这次的目的地连同算路结果一起丢掉。
     * 由调用方落到 [NaviSession.clearRoute]（界面不直接改会话状态）。
     */
    onClearDestination: () -> Unit,
    /**
     * 算路失败后点"重试"。落到 [NaviSession.retryCalculate] ——
     * 界面不自己再算一遍（起终点在会话里，界面拿不到）。
     */
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val searcher = remember { PlaceSearch(context) }
    // 会被屏幕尺寸影响的尺寸，全部在这里算一次，再往下传。
    val metrics = rememberNaviMetrics()
    val routeState by NaviSession.route.collectAsState()
    // 策略是**会话**的状态而不是界面的：换策略会触发重算，界面只负责"点哪个"。
    val strategy by NaviSession.strategy.collectAsState()

    // ---- 输入防抖 → 联想 ----
    LaunchedEffect(state.query, state.expanded) {
        if (!state.expanded || state.query.isBlank()) {
            state.suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MILLIS)
        state.busy = true
        state.notice = null
        searcher.suggest(
            keyword = state.query,
            city = city,
            onResult = { list ->
                state.busy = false
                state.suggestions = list
                state.notice = if (list.isEmpty()) "没有匹配的地点" else null
            },
            onFailure = { reason ->
                state.busy = false
                state.suggestions = emptyList()
                // 失败就要说失败。**不保留上一次的结果** ——
                // 那会让人以为"搜到了"，其实网络早断了（天气卡同一个口径）。
                state.notice = reason
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ---- 0. 地图压暗遮罩 ----
        //
        // 只在"需要读小字号数字"的时刻压暗：展开搜索面板、或者路线卡在屏上。
        // 两个条件互斥（卡片在展开态下不显示），所以不会叠出双倍遮罩。
        //
        // 为什么非要有它：卡片的透出量可以靠提高不透明度压掉（见
        // [DeskGlass.overlayCardAlpha]），但卡片**周围**那张高对比地图还在抢注意力 ——
        // 亮底 + 带白描边的 POI 字与卡片里的小字号数字同处一个视野，眼睛会一直往亮处跑，
        // 数字就"看不进去"。地图产品在选路线时都会压暗底图，是同一个道理。
        //
        // **故意不挂 clickable**：遮罩只是视觉层，不能吃掉触摸。否则点着地图想拖开看看
        // 旁边那条路，会变成点不动 —— 遮罩把地图手势也一起遮掉了。
        if (state.expanded || (destination != null && routeState !is RouteState.Idle)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = DeskGlass.mapScrimAlpha)),
            )
        }

        // ---- 1. 展开态：贴顶部一整块「输入框 + 结果」面板 ----
        //
        // **输入框必须待在这一块的顶行**，不能留在底部的搜索条里。原因只有一条，
        // 但是致命的：这是 411 dp 高的横屏窗口，Gboard 一弹就吃掉约 300 dp
        // （`.verify/nav/M5-suggest.png` 实测键盘顶边在 1440 px 里的第 400 px）。
        // 底部的输入框和结果列表于是**同时**被盖住 —— 现象是"能点开、能弹键盘，
        // 但看不见自己打了什么、也永远选不到结果"，功能等于不存在。
        //
        // 把输入框搬到可见区顶部后：输入看得见、结果在它下面按剩余高度滚动。
        if (state.expanded) {
            val panelShape = RoundedCornerShape(18.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 20.dp, top = 10.dp, end = 20.dp)
                    .fillMaxWidth()
                    .widthIn(max = metrics.panelMaxWidth)
                    .heightIn(max = metrics.listMaxHeight)
                    .shadow(6.dp, panelShape)
                    // 用**文字面板**那一档不透明度（0.90）而不是搜索条那档（0.72）：
                    // 这面板里全是地点名与地址，底下地图的 POI 标注穿进来就成了"两张字叠一起"。
                    .deskGlassOverlay(panelShape, alpha = DeskGlass.overlayCardAlpha),
            ) {
                Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    SearchFieldRow(
                        query = state.query,
                        onQueryChange = { state.query = it },
                        onCollapse = {
                            state.expanded = false
                            state.notice = null
                        },
                    )

                    // 「回家 / 去公司」快捷**只在还没输入时占位**。
                    // 一旦开始打字，用户要的是结果，不是快捷入口 —— 而且键盘之上
                    // 总共只有约 110 dp，这一行 44 dp 是"能不能看见结果"的关键预算。
                    if (state.query.isBlank()) {
                        CommuteRow(
                            homePlace = homePlace,
                            workPlace = workPlace,
                            onHome = { if (homePlace != null) state.query = homePlace.title },
                            onWork = { if (workPlace != null) state.query = workPlace.title },
                        )
                    }

                    when {
                        state.busy -> ListNotice("搜索中…", showSpinner = true)
                        state.suggestions.isEmpty() -> ListNotice(state.notice ?: "输入地点名称")
                        else -> LazyColumn(
                            // weight + fill=false：列表吃掉面板的剩余高度，内容不足时
                            // 面板就收缩，不去占满整个可见区。
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                        ) {
                            items(state.suggestions) { s ->
                                SuggestionRow(
                                    suggestion = s,
                                    onClick = {
                                        state.busy = true
                                        state.notice = null
                                        // 补坐标这一步**必须有**：联想结果常常没有坐标，
                                        // 直接拿它当目的地，算出来的是几内亚湾的 (0,0)。
                                        searcher.resolve(s, city) { resolved ->
                                            state.busy = false
                                            if (resolved == null) {
                                                state.notice = "这个结果暂无坐标，换一个试试"
                                            } else {
                                                state.expanded = false
                                                state.highlightIndex = -1
                                                // 选完就把关键词清掉。
                                                //
                                                // 不清的后果是真机拍下来的：上次搜的 "xianyangjichang"
                                                // 一直留在框里，下次展开再输 "zhonglou"，框里变成
                                                // **"zhonglouxianyangjichang"** —— 拼在一起当然搜不到任何东西，
                                                // 而用户以为是自己打错了。地图应用里"选完即清空"也是通行做法：
                                                // 你再打开搜索框，是要找**下一个**地方。
                                                state.query = ""
                                                // 选完即算路。**这一步以前漏了** ——
                                                // 真机上表现为"点了结果，面板关了、什么也没发生"
                                                // （destination 存下了，但没人去算路）。
                                                onPicked(resolved)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- 2. 收起态的底部搜索条（展开后它让位给顶部的面板，不再重复出现）----
        if (!state.expanded) {
            SearchBar(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, bottom = 14.dp)
                    .widthIn(max = metrics.searchBarMaxWidth),
                onToggle = { state.expanded = true },
            )
        }

        // ---- 3. 路线预览卡 ----
        //
        // 只在「没展开联想面板」时显示：面板从顶部往下最多 [NaviMetrics.listMaxHeight]，
        // 卡片从底部往上也有三百出头，两者同时出现会在屏幕中段撞在一起
        // （横屏纵向预算就那么紧，411 dp 那台机器上是这样，361 dp 那台更紧）。
        // 用户点开搜索条的那一刻，他要的是重新选目的地，卡片留着也没用。
        if (destination != null && !state.expanded && routeState !is RouteState.Idle) {
            RoutePreviewCard(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // 抬高到搜索条上方：搜索条最宽 320、卡片最宽 460，
                    // 两者同一行放不下 —— 靠纵向错开而不是靠"祈祷宽度不超"。
                    // （原来注释里写的"内容区 786"是某台机器的宽度，换机即失效；
                    //   现在这个判断不再依赖任何具体数字。）
                    .padding(end = 20.dp, bottom = 14.dp + SEARCH_BAR_HEIGHT + 10.dp),
                metrics = metrics,
                destination = destination,
                routeState = routeState,
                strategy = strategy,
                homePlace = homePlace,
                workPlace = workPlace,
                onStrategyChange = { NaviSession.switchStrategy(context, it) },
                onSelectRoute = { NaviSession.select(it) },
                onSaveHome = { onSaveHome(destination) },
                onSaveWork = { onSaveWork(destination) },
                onStartNavi = onStartNavi,
                onRetry = onRetry,
                onDismiss = {
                    // "算了"就是"当我没说"：关键词一起清掉，下次打开是干净的输入框，
                    // 而不是又看到刚放弃的那次搜索（同 [onPicked] 那条的理由）。
                    state.query = ""
                    onClearDestination()
                },
            )
        }
    }
}

/** 收起态的搜索条：整条就是一个按钮，点了展开成顶部的搜索面板。 */
@Composable
private fun SearchBar(
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val barShape = RoundedCornerShape(SEARCH_BAR_HEIGHT / 2)
    Box(
        modifier = modifier
            .height(SEARCH_BAR_HEIGHT)
            .shadow(4.dp, barShape)
            .deskGlassOverlay(barShape)
            .clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "搜索目的地",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * 展开态的输入行 —— **面板的第一行**，不是屏幕底部那一行（原因见调用处注释）。
 *
 * 两个真机踩出来的细节都留在这里：
 *   1. **进面板就自动聚焦 + 唤起输入法**。最早只做了"展开"，面板出来了但输入框
 *      没有焦点，打字一个字符都不进去（连 `input text` 都进不去）——
 *      现象是"打开了但搜不了"，非常容易被当成搜索功能坏了；
 *   2. 用 `BasicTextField` 而不是 `TextField`：后者自带 56 dp 最小高度与下划线装饰，
 *      在这个 44 dp 的行里会撑高并露出装饰线，与玻璃风格不搭。
 */
@Composable
private fun SearchFieldRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onCollapse: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    // 收起时把键盘放下：否则"面板没了、键盘还杵着"，会把地图挤掉一大截。
    DisposableEffect(Unit) {
        onDispose { keyboard?.hide() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SEARCH_BAR_HEIGHT)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        text = "搜索目的地",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
                inner()
            },
        )
        TextButton(onClick = onCollapse, contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text("收起", fontSize = 13.sp)
        }
    }
}

/** 一行联想结果。**行高 ≥ 48 dp**：这是"手臂距离外一次点中"的口径。 */
@Composable
private fun SuggestionRow(suggestion: PlaceSuggestion, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.name,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (suggestion.subtitle.isNotBlank()) {
                Text(
                    text = suggestion.subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    maxLines = 1,
                )
            }
        }
        // 没坐标的结果给一个视觉标记：点它大概率要去补坐标，补不到会明说。
        if (suggestion.point == null) {
            Text(
                text = "需确认",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
    }
}

/** 列表里的提示行（搜索中 / 无结果 / 没输入）。 */
@Composable
private fun ListNotice(text: String, showSpinner: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.height(16.dp).width(16.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

/** 「回家 / 去公司」快捷行。已设置 → 可点；未设置 → 明确写"未设置"。 */
@Composable
private fun CommuteRow(
    homePlace: NaviPlace?,
    workPlace: NaviPlace?,
    onHome: () -> Unit,
    onWork: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CommuteChip(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Home,
            label = "回家",
            place = homePlace,
            onClick = onHome,
        )
        CommuteChip(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Work,
            label = "去公司",
            place = workPlace,
            onClick = onWork,
        )
    }
}

@Composable
private fun CommuteChip(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    place: NaviPlace?,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(enabled = place != null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (place != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            },
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = place?.title ?: "未设置",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
    }
}

/**
 * 路线预览卡：**策略切换 + 多条路线列表 + 动作行**。
 *
 * 数字**只在 [RouteState.Ready] 时出现**，其余状态显示进度或错误原文 ——
 * 不做"先显示 0 公里、等回调到了再改"这种事：那会让人以为路线只有 0 米。
 *
 * 两处刻意的设计：
 *   1. **策略行恒显示**（算路中也显示）。正算着的时候按钮变灰但看得见，
 *      用户知道"还有别的走法"，而不是以为只能等这一条；
 *   2. **路线列表限高 + 内部滚动**（[ROUTE_LIST_MAX_HEIGHT]）。
 *      内容区纵向只有 411 dp，卡片已经抬到搜索条上方，再把 3 条以上路线全铺开
 *      就会顶到屏幕中段 —— 宁可滚，也不要把整块内容区吃满。
 */
@Composable
private fun RoutePreviewCard(
    modifier: Modifier = Modifier,
    metrics: NaviMetrics,
    destination: PlaceSuggestion,
    routeState: RouteState,
    strategy: NaviSession.Strategy,
    homePlace: NaviPlace?,
    workPlace: NaviPlace?,
    onStrategyChange: (NaviSession.Strategy) -> Unit,
    onSelectRoute: (Int) -> Unit,
    onSaveHome: () -> Unit,
    onSaveWork: () -> Unit,
    onStartNavi: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cardShape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .widthIn(max = metrics.previewMaxWidth)
            .heightIn(max = metrics.previewMaxHeight)
            .shadow(6.dp, cardShape)
            // 文字面板那一档（0.90）。这张卡是全屏地图上**唯一**要求逐位读数的地方：
            // 3 行 × 3 个数字要横向比较，任何一块透出来的白色 POI 标注都会毁掉其中一行。
            // 实测证据见 [DeskGlass.overlayCardAlpha] 的注释（0.72 时用户二次反馈看不清）。
            .deskGlassOverlay(cardShape, alpha = DeskGlass.overlayCardAlpha),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = destination.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (destination.subtitle.isNotBlank()) {
                Text(
                    text = destination.subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            StrategyRow(
                current = strategy,
                // 算路中**不锁**按钮：换策略本来就是"重新算"的意思，
                // 让用户在算的过程中改主意，比让他等一条不想要的路算完更省时间。
                onSelect = onStrategyChange,
            )

            Spacer(modifier = Modifier.height(8.dp))

            when (routeState) {
                RouteState.Idle -> Unit

                RouteState.Calculating -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(14.dp).width(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("正在算路…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                }

                // 失败必须同时给出"为什么"和"下一步做什么"。只留一行红字的话，
                // 用户唯一能做的就是把卡片关掉重来 —— 而这颗"重试"走的**正是同一条算路链路**
                // （起点终点都在会话里），不必再搜一次目的地。
                is RouteState.Failed -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = routeState.message,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Text("重试", fontSize = 13.sp)
                    }
                }

                is RouteState.Ready -> RouteList(
                    options = routeState.options,
                    selectedIndex = routeState.selectedIndex,
                    onSelect = onSelectRoute,
                    routeListMaxHeight = metrics.routeListMaxHeight,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            // 一道极淡的分隔线：上面是"可滚动的路线列表"，下面是"固定动作"。
            // 路线超过三条时限高处会切出半行，这道线让"被切的是列表"这件事看得出来，
            // 而不是像个渲染错位。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 设为家 / 设为公司：把**已经补好坐标**的目的地存下来。
                // 没设置过才给这个入口，已设置显示"改" —— 免得两个按钮看起来没区别。
                TextButton(onClick = onSaveHome, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (homePlace == null) "设为家" else "改家", fontSize = 12.sp)
                }
                TextButton(onClick = onSaveWork, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (workPlace == null) "设为公司" else "改公司", fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text("算了", fontSize = 13.sp)
                }
                TextButton(
                    onClick = onStartNavi,
                    enabled = (routeState as? RouteState.Ready)?.selected != null,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text(
                        text = "开始导航",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if ((routeState as? RouteState.Ready)?.selected != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 策略切换行：推荐 / 高速优先 / 不走高速 / 躲避拥堵。
 *
 * 四项**全部来自 [NaviSession.Strategy]**，不在界面里另写一份文案 ——
 * 否则改引擎忘了改界面，按钮写着"高速优先"实际传的是别的常量，这种错最难查。
 */
@Composable
private fun StrategyRow(
    current: NaviSession.Strategy,
    onSelect: (NaviSession.Strategy) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NaviSession.Strategy.entries.forEach { s ->
            StrategyChip(
                modifier = Modifier.weight(1f),
                label = s.label,
                selected = s == current,
                onClick = { onSelect(s) },
            )
        }
    }
}

@Composable
private fun StrategyChip(
    modifier: Modifier = Modifier,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val chipShape = RoundedCornerShape(15.dp)
    Box(
        modifier = modifier
            .height(STRATEGY_CHIP_HEIGHT)
            .clip(chipShape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            },
            maxLines = 1,
        )
    }
}

/**
 * 多条路线的列表。选中项高亮，点任意一条即切换当前路线（[NaviSession.select]）。
 *
 * 为什么不做"卡片式三选一"：路线之间的差异主要在**数字**上（多远、多久、几个灯），
 * 纵向列表能让同一列的数字对齐、一眼比出来；横向卡片反而要来回扫视。
 */
@Composable
private fun RouteList(
    options: List<RouteOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    routeListMaxHeight: Dp,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            // 限高 + 内部滚动：见 [RoutePreviewCard] 的说明。
            // 高度由 [rememberNaviMetrics] 按可用高反推，**只给整行**
            // （原来写死 164 = 52×3+4×2，矮屏上这一块会把动作行顶出卡片）。
            .heightIn(max = routeListMaxHeight),
        // 行间距用 `spacedBy` 而不是每行后面塞一个 Spacer：
        // 行高必须能被整除成整数行 —— 否则最后一行会被切掉半行，
        // 半行里露出的"11 个红绿灯"正好贴在动作行上，看起来像两个控件叠在一起
        // （`.verify/nav/R5-routes.png` 就是这样）。
        verticalArrangement = Arrangement.spacedBy(ROUTE_ROW_GAP),
    ) {
        itemsIndexed(options) { index, option ->
            RouteOptionRow(
                index = index,
                option = option,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
            )
        }
    }
}

@Composable
private fun RouteOptionRow(
    index: Int,
    option: RouteOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val rowShape = RoundedCornerShape(12.dp)
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROUTE_ROW_HEIGHT)
            .clip(rowShape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                } else {
                    // 0.06 → 0.09：底色变实之后，太淡的行底衬等于没有，
                    // 三行会糊成一整块，反而不好"一行一行比"。
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 序号圆点兼选中指示：不用复选框 —— 这不是"多选"，是"挑一条"。
        Box(
            modifier = Modifier
                .height(20.dp)
                .width(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        onSurface.copy(alpha = 0.18f)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${index + 1}",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    onSurface.copy(alpha = 0.7f)
                },
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = option.summary.distanceText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = onSurface,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = option.summary.durationText,
                    fontSize = 13.sp,
                    color = onSurface.copy(alpha = 0.85f),
                    maxLines = 1,
                )
            }
            // 标签原样展示（高德给的"红绿灯少""收费"之类），不自己编。
            val tail = buildList {
                add(option.summary.trafficLightText)
                if (option.labels.isNotBlank()) add(option.labels)
            }.joinToString(" · ")
            Text(
                text = tail,
                fontSize = 11.sp,
                // 0.6 → 0.75：这条是"26 个红绿灯 · 推荐"，是选路的主要依据之一，
                // 不能当附注糊过去。卡片底已经接近实心，提高文字不透明度就是纯收益。
                color = onSurface.copy(alpha = 0.75f),
                maxLines = 1,
            )
        }
    }
}

// ---------- 尺寸 ----------
//
// **凡会被屏幕尺寸影响的，一律按可用宽高反推**（2026-10-13 改）。
//
// 改的直接原因：这一段原来写着"内容区在基准机上是 786×411 dp"，而 786 根本不是
// 当前基准机的数（它是另一台机器的宽度），411 是另一台的高度。照着这组数字定出来的
// `PREVIEW_MAX_HEIGHT = 332.dp`，换到 361 dp 高的机器上就放不下了 ——
// 真机症状是"选了路线却看不到『开始导航』按钮"。
//
// 现在只有两类值还留成常量：
//   · **组件自身的可读性下限**（行高、搜索条高、策略 chip 高）—— 它们与屏幕无关，
//     是"这行文字/这个按钮自己需要多高"；
//   · 会被屏幕影响的，全部挪进 [NaviMetrics]，由 [rememberNaviMetrics] 算。

private val SEARCH_BAR_HEIGHT = 44.dp
private val ROW_HEIGHT = 48.dp
private val ROUTE_ROW_HEIGHT = 52.dp

/** 路线行间距。 */
private val ROUTE_ROW_GAP = 4.dp

/**
 * 策略按钮高度。
 *
 * **诚实标注：30 dp 低于本工程的触控下限 64 dp**（[DeskDimens.minTouchTarget]）。
 * 它是"推荐/最快/躲避拥堵"这类**次要**选择：主操作是下面那条"开始导航"（40+ dp），
 * 策略行是选路时的微调。历史上它没被放大的原因是纵向预算实在太紧 —— 三档策略
 * 各加到 64 dp 会吃掉 102 dp，在矮屏上直接把动作行挤没。
 *
 * 这条是**已知的例外，不是被遗漏的 bug**：真要改，得先解决"矮屏上预览卡放不下"
 * 这个更根本的问题（现在靠 [NaviMetrics.previewMaxHeight] 按可用高收缩解决）。
 */
private val STRATEGY_CHIP_HEIGHT = 30.dp

/**
 * 会被屏幕尺寸影响的那一批尺寸。
 *
 * 由 [rememberNaviMetrics] 从**设备档案**（[LocalDeviceProfile]）算出来，
 * 不再引用任何一台具体机器的宽度或高度。
 *
 * 内容区 = 整屏 − 挖孔 − 侧栏。侧栏宽度用 [DeskDimens.sideBarItemSize] 的上限估，
 * 差几个 dp 不影响这里的结论（每个值都有上下限夹取）。
 */
private data class NaviMetrics(
    val panelMaxWidth: Dp,
    val listMaxHeight: Dp,
    val searchBarMaxWidth: Dp,
    val previewMaxWidth: Dp,
    val previewMaxHeight: Dp,
    val routeListMaxHeight: Dp,
)

@Composable
private fun rememberNaviMetrics(): NaviMetrics {
    val profile = LocalDeviceProfile.current
    val contentWidthDp = (
        profile.widthDp -
            (DeskDimens.sideBarItemSize.value + DeskDimens.sideBarInnerPadding.value * 2f)
        ).coerceAtLeast(240f)
    // 内容区宽高都由 [DeviceProfile] 给出（系统栏与挖孔已扣），这里不再减挖孔。
    val contentHeightDp = profile.heightDp.toFloat().coerceAtLeast(200f)

    // 预览卡限高之后，剩下的才是路线列表能用的高度。
    // 先按"预览卡内固定占掉的部分"估一个列表可用高：
    // 28(内边距) + 19(目的地) + 15(副标题) + 8 + 30(策略行) + 8 + 8 + 1 + 4 + 40(动作行)
    // = 161 dp 是卡片里不随列表变化的那一段。
    val previewHeight = AdaptiveDimens.previewMaxHeight(contentHeightDp)
    val routeListBudget = (previewHeight.value - PREVIEW_FIXED_PART_DP).coerceAtLeast(ROUTE_ROW_HEIGHT.value)

    return NaviMetrics(
        panelMaxWidth = AdaptiveDimens.searchPanelWidth(contentWidthDp),
        listMaxHeight = AdaptiveDimens.searchListHeight(contentHeightDp),
        searchBarMaxWidth = AdaptiveDimens.searchBarWidth(contentWidthDp),
        previewMaxWidth = AdaptiveDimens.previewWidth(contentWidthDp),
        previewMaxHeight = previewHeight,
        routeListMaxHeight = AdaptiveDimens.routeListHeight(
            availableHeightDp = routeListBudget,
            rowHeightDp = ROUTE_ROW_HEIGHT.value,
            rowGapDp = ROUTE_ROW_GAP.value,
            maxRows = 3,
        ),
    )
}

/** 预览卡里不随路线列表变化的那一段高度（见 [rememberNaviMetrics] 里的算式）。 */
private const val PREVIEW_FIXED_PART_DP: Float = 161f

private const val SEARCH_DEBOUNCE_MILLIS = 300L

/**
 * 搜索用的城市。**空串 = 全国**，不给搜索设市级边界。
 *
 * 曾写死"西安"（理由：机器固定在西安、限定城市结果更准）。用户否掉了这个限制 ——
 * 导航的目的地常常就在别的城市，搜不出来等于功能不存在。
 *
 * 不限城市之后靠**当前位置排序**保证体验：搜索请求会带上 `lastLocation`，
 * 高德按距离排序，近的仍然排在最前。
 *
 * 若日后要恢复城市限定，只需在这里填城市名（如"西安"）即可：
 * `PlaceSearch` 里是 `cityLimit = city.isNotBlank()`，给了城市才限定。
 */
internal const val NAVI_CITY = ""
