package com.qingke.desk.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingke.desk.core.model.CardLine
import com.qingke.desk.core.model.HomeCard
import com.qingke.desk.core.model.HomeCardKind
import com.qingke.desk.core.ui.DeskDimens

/**
 * 卡片配色。放在 feature 层而不是 model 层，model 保持纯数据。
 *
 * TODO(adb) [待校正]：这些颜色需要在真机 OLED 上核一遍对比度，
 *           OLED 的暗部表现和 LCD 差异明显。
 */
internal object HomeCardPalette {

    fun accentFor(kind: HomeCardKind): Long = when (kind) {
        HomeCardKind.CLOCK -> 0xFF2E3350
        HomeCardKind.PROJECTION -> 0xFF1F4FA8
        HomeCardKind.AGENDA -> 0xFF3A5BA0
        HomeCardKind.TODO -> 0xFF2F7A63
        HomeCardKind.WEATHER -> 0xFF44679E
        HomeCardKind.SMART_HOME -> 0xFF87579A
        HomeCardKind.MUSIC -> 0xFF9A4E4E
    }
}

/**
 * 单张卡片。尺寸完全由外部栅格决定（fillMaxSize），自身不写死宽高。
 *
 * @param drift 防烧屏微位移，由 :shell 统一计算下发，卡片本身不感知时间。
 * @param maxClockSp 时钟卡的字号**上限**。默认 [DeskDimens.clockTime]（56.sp，按
 *                   88 dp 内容区定的）；第二页时钟卡是 409×313 的大卡，传
 *                   [DeskDimens.clockTimeLarge]（96.sp）才不显得字小得莫名其妙。
 *                   它只是上限，实际字号仍按可用高度/宽度反推。
 */
@Composable
fun HomeCardTile(
    card: HomeCard,
    modifier: Modifier = Modifier,
    driftX: androidx.compose.ui.unit.Dp = 0.dp,
    driftY: androidx.compose.ui.unit.Dp = 0.dp,
    maxClockSp: androidx.compose.ui.unit.TextUnit = DeskDimens.clockTime,
    /** 点击整张卡。不传则不可点（信息卡）。 */
    onClick: (() -> Unit)? = null,
) {
    val container = Color(card.accentArgb ?: HomeCardPalette.accentFor(card.kind))

    Card(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier
            .fillMaxSize()
            .offset(x = driftX, y = driftY),
        shape = RoundedCornerShape(DeskDimens.cardCorner),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(DeskDimens.cardPadding),
        ) {
            when (card.kind) {
                HomeCardKind.CLOCK -> ClockBody(card, maxClockSp)
                HomeCardKind.MUSIC -> MusicBody(card)
                HomeCardKind.WEATHER -> WeatherBody(card)
                HomeCardKind.PROJECTION -> ProjectionBody(card)
                HomeCardKind.AGENDA, HomeCardKind.TODO, HomeCardKind.SMART_HOME -> LinesBody(card)
            }
        }
    }
}

/**
 * 投屏入口卡。
 *
 * 刻意做成"大按钮而不是另一个入口"：卡片本身高度由栅格反推（见 :shell/HomeScreen），
 * 布局不能假设有多高，所以这里只用"图标 + 主副标题"三行，且整体居中，
 * 高度被压到 110 dp 左右也不会挤。
 *
 * 2026-10-11 改排版：原来是「图标 | 标题/副标题」两列，副标题只分到图标右边
 * 那一小条 —— 基准机首页 198 dp 时够用，但地图布局把卡片压到 170 dp 后，
 * 那一小条只剩约 79 dp，而 "iPhone · CarPlay" 在 13 sp 下要 129 dp，
 * 真机上直接显示成 "iPhon…"。现在图标与标题同行、副标题**独享整卡宽度**，
 * 再按宽度反推字号兜底，两种布局下都能完整显示。
 */
@Composable
private fun ProjectionBody(card: HomeCard) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val subtitleSize = minOf(
            DeskDimens.cardCaption.value,
            fitByWidth(card.subtitle, maxWidth.value),
        ).coerceAtLeast(11f).sp

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Cast,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(DeskDimens.cardIconSize * 1.4f),
                )
                Text(
                    text = card.title,
                    fontSize = DeskDimens.cardTitle,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = card.subtitle,
                fontSize = subtitleSize,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 时钟卡。字号**按可用高度反推**，不写死。
 *
 * 为什么：栅格行数由可用高度反推（见 :shell/HomeScreen），单卡实际高度会随机器变。
 * 本机实测单卡内容区只有约 88.5 dp，而 56.sp 时钟 + 副标题需要约 95 dp ——
 * 写死字号会把日期裁掉一半（真机截图确认过的现象）。
 * 这里改成"副标题先占位，剩下的给时钟"，任何机器都不会再被裁。
 *
 * **宽度也要一起算**（2026-10-11 补）：地图布局把卡片区从 3×230 dp 压到 3×150 dp，
 * 只按高度反推会让"08:37"直接被右边裁成"08:3"（真机截图确认过，不是估算）。
 * 时间位数是已知的（HH:mm，5 个字符），数字字宽约为字号的 0.62 倍，
 * 据此反推一个宽度上限，取两者的小值。
 */
@Composable
private fun ClockBody(card: HomeCard, maxClockSp: androidx.compose.ui.unit.TextUnit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gapDp = 6f
        val captionBlock = DeskDimens.cardBody.value * DeskDimens.TEXT_LINE_HEIGHT_RATIO
        val maxTimeSize = (maxHeight.value - gapDp - captionBlock) /
            DeskDimens.TEXT_LINE_HEIGHT_RATIO
        val timeSize = minOf(maxTimeSize, fitByWidth(card.title, maxWidth.value))
            .coerceAtMost(maxClockSp.value)
            .coerceAtLeast(24f)
            .sp
        // 副标题（"10月10日 星期六"）在窄卡里同样会溢出被裁，
        // 同一个道理：能缩就缩，缩到 11 sp 还不下才轮到省略号。
        // 副标题（"10月10日 星期六"）跟着时钟一起放大，但不跟着无限放大：
        // 取时钟字号的 22%，夹在 11~22 sp 之间，再按宽度反推兜底。
        val subtitleSize = minOf(
            (maxClockSp.value * 0.22f).coerceIn(11f, 22f),
            fitByWidth(card.subtitle, maxWidth.value),
        ).coerceAtLeast(11f)
            .sp

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = card.title,
                fontSize = timeSize,
                color = Color.White,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(gapDp.dp))
            Text(
                text = card.subtitle,
                fontSize = subtitleSize,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MusicBody(card: HomeCard) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(DeskDimens.cardIconSize),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(card.title, fontSize = DeskDimens.cardTitle, color = Color.White)
            Text(
                card.subtitle,
                fontSize = DeskDimens.cardCaption,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    LinearProgressIndicator(
        progress = { 0.32f },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.PlayArrow, contentDescription = "播放", tint = Color.White)
        Icon(Icons.Default.MusicNote, contentDescription = "歌单", tint = Color.White.copy(alpha = 0.5f))
        Icon(Icons.Default.Cloud, contentDescription = "设备", tint = Color.White.copy(alpha = 0.5f))
    }
}

/**
 * 非中日韩字符的宽度约为字号的多少倍。
 *
 * 只用于"反推字号上限"这一个用途，所以取一个偏保守的值：
 * 数字实际约 0.55~0.58 em，冒号更窄，取 0.62 留出安全余量。
 */
private const val CLOCK_CHAR_WIDTH_RATIO = 0.62f

/**
 * 反推"这段文字在 [availableDp] 宽里完整显示的最大字号"。
 *
 * 中日韩字符按 1 em 宽、其余按 [CLOCK_CHAR_WIDTH_RATIO] em 估。
 * 只是个估算，不做精确排版 —— 它的用途是"别把字裁掉"，而不是对齐像素。
 * 真机（560 dpi，fontScale 1.0）核过两个样本：
 * "08:37" 与 "10月10日 星期六" 在约 150 dp 宽的卡里都能完整显示。
 */
private fun fitByWidth(text: String, availableDp: Float): Float {
    if (text.isEmpty()) return Float.MAX_VALUE
    val em = text.sumOf { if (it.code > 0x2E80) 1.0 else CLOCK_CHAR_WIDTH_RATIO.toDouble() }
    if (em <= 0.0) return Float.MAX_VALUE
    return (availableDp / em).toFloat()
}

/**
 * 天气卡：城市 / 天气（上下两段，字号按宽度反推）+ 明细行。
 *
 * 2026-10-11 改：城市与天气也用 [fitByWidth] 兜底（原先是写死字号），
 * 明细行不再复用 [LineRow]。真机截图里明细行只剩一个「…」——
 * [LineRow] 给主文字 weight(1f)、右侧 trailing 是固有宽度，窄卡里主文字被挤没了。
 * 明细行两段同等重要，改为各分一半、字号统一反推。
 */
@Composable
private fun WeatherBody(card: HomeCard) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val available = maxWidth.value
        val citySize = minOf(DeskDimens.cardCaption.value, fitByWidth(card.title, available))
            .coerceAtLeast(10f).sp
        val tempSize = minOf(DeskDimens.cardTitle.value, fitByWidth(card.subtitle, available))
            .coerceAtMost(24f).coerceAtLeast(15f).sp

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = card.title,
                    fontSize = citySize,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = card.subtitle,
                    fontSize = tempSize,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column {
                card.lines.firstOrNull()?.let { WeatherDetailRow(it) }
                // 署名行（现在只有天气卡有：Open-Meteo 的 CC BY 4.0 要求）。
                // 放在卡片最底部的小字里，跟着数据一起出现 —— 只写在 README 里等于没署。
                if (card.footnote.isNotBlank()) {
                    if (card.lines.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Text(
                        text = card.footnote,
                        fontSize = minOf(
                            DeskDimens.cardFootnote.value,
                            fitByWidth(card.footnote, available),
                        ).coerceAtLeast(9f).sp,
                        color = Color.White.copy(alpha = 0.45f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 天气明细行：左侧图标 + 两段等分。 */
@Composable
private fun WeatherDetailRow(line: CardLine) {
    val iconBlock = 20f + 10f
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // 两段各分"去掉图标后的一半"，再取两者能完整显示的字号的较小值。
        val share = ((maxWidth.value - iconBlock) / 2f).coerceAtLeast(1f)
        val size = minOf(
            DeskDimens.cardBody.value,
            fitByWidth(line.text, share),
            fitByWidth(line.trailing, share),
        ).coerceAtLeast(11f).sp

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = iconFor(line.text),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(20.dp),
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = line.text,
                    fontSize = size,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (line.trailing.isNotBlank()) {
                    Text(
                        text = line.trailing,
                        fontSize = size,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 「回家 / 去公司」快捷卡的**占位版本**（2026-10-12）。
 *
 * 用户口径：导航做完之前先占位，并且是**灰态的「设置地址」**、点击不触发任何功能。
 * 所以这里连 `clickable` 都不加 —— 不是"点了弹个 toast 说还没做"，
 * 而是它现在就不是一个可操作的东西，灰态是它的真实状态。
 *
 * 两个槽位（回家 / 去公司）都留着：等导航接进来，这里换成真实地址 + 一键算路，
 * 布局不用动（卡片尺寸、两槽位等分都是定好的）。
 */
@Composable
fun CommutePlaceholderCard(
    modifier: Modifier = Modifier,
    driftX: androidx.compose.ui.unit.Dp = 0.dp,
    driftY: androidx.compose.ui.unit.Dp = 0.dp,
) {
    Card(
        modifier = modifier
            .fillMaxSize()
            .offset(x = driftX, y = driftY),
        shape = RoundedCornerShape(DeskDimens.cardCorner),
        colors = CardDefaults.cardColors(containerColor = COMMUTE_PLACEHOLDER_COLOR),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(DeskDimens.cardPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CommuteSlot(label = "回家", icon = Icons.Default.Home)
            CommuteSlot(label = "去公司", icon = Icons.Default.Work)
        }
    }
}

/** 单个槽位：图标 + 目的地名 + 灰态的「设置地址」。 */
@Composable
private fun RowScope.CommuteSlot(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.28f),
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.4f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "设置地址",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.28f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 占位卡底色：比卡片色再灰一档。
 *
 * 刻意不用 `Color.Gray` 之类的纯灰：整屏是暗色，纯灰会显得像"加载失败的洞"。
 * 这个值是在时钟卡蓝灰（#2E3350）基础上再降饱和度得到的，放在旁边不会跳出来。
 */
private val COMMUTE_PLACEHOLDER_COLOR = Color(0xFF33363D)

@Composable
private fun LinesBody(card: HomeCard) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        card.lines.forEach { line -> LineRow(line) }
    }
}

@Composable
private fun LineRow(line: CardLine) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (line.checked != null) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (line.checked == true) Color.White else Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(20.dp),
            )
        } else {
            Icon(
                imageVector = iconFor(line.text),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = line.text,
            modifier = Modifier.weight(1f),
            fontSize = DeskDimens.cardBody,
            color = if (line.checked == true) Color.White.copy(alpha = 0.55f) else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (line.trailing.isNotBlank()) {
            Text(
                text = line.trailing,
                fontSize = DeskDimens.cardCaption,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

private fun iconFor(text: String) = when {
    text.contains("灯") || text.contains("空调") -> Icons.Default.Lightbulb
    text.contains("课") || text.contains("评审") || text.contains("英语") -> Icons.Default.Schedule
    else -> Icons.Default.Cloud
}
