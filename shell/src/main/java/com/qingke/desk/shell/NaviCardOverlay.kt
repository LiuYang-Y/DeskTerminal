package com.qingke.desk.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qingke.desk.core.ui.deskGlassOverlay
import com.qingke.desk.map.navi.NaviGuidance

/**
 * 首页地图卡上的**导航引导浮层**：下一转向 + 距离 + 路名 + 剩余里程与预计时间。
 *
 * ## 为什么字号是"算出来"的
 *
 * 这张卡在不同机器上宽度不一样（本机 460 dp，换台机器按权重 2:1 分完可能是别的数），
 * 而同一块屏上它还可能因为侧栏左右切换而变宽变窄。写死字号的结果只有两种：
 * 小屏上"133 米"挤出卡片，大屏上又小得像注脚。
 *
 * 所以所有尺寸都从 `maxWidth` 反推（见下面的 `k` 系数），并且**用 `Dp.toSp()` 而不是 `sp`**
 * —— 后者会跟着系统字体缩放再放大一次，这张卡本来就不大，用户调大字体时最先挤爆的就是它。
 * 这与项目里"卡片内容按可用宽高反推字号，不假设卡片有多大"是同一条约束。
 *
 * ## 它不吃触摸
 *
 * 浮层铺满整张卡，但**不挂任何 `clickable` / `pointerInput`** —— 事件因此继续往下走到
 * 卡片自己的点击处理，"点卡片回全屏导航页"照常可用。这里刻意不消费，跟
 * `MapPreviewCard` 在地图上装那层"拦下但不消费"的容器是同一个道理。
 *
 * @param guidance 实时引导数据（[com.qingke.desk.map.navi.NaviSession.guidance]）。
 */
@Composable
internal fun NaviCardOverlay(
    guidance: NaviGuidance,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 以卡宽为基准的比例尺。0.75 下限保证再窄也读得清；
        // 1.25 上限避免在超宽屏上把字放到喧宾夺主（卡片本身已经不大了）。
        val k = (maxWidth.value / NAVI_CARD_REF_WIDTH_DP).coerceIn(0.75f, 1.25f)
        val density = LocalDensity.current

        // 由 dp 反推 sp：`Dp.toSp()` 不经过系统字体缩放，字号只跟着卡片尺寸走。
        fun size(dp: Dp) = with(density) { dp.toSp() }

        val edge = (NAVI_CARD_EDGE_DP * k).dp
        val barShape = RoundedCornerShape((NAVI_CARD_BAR_RADIUS_DP * k).dp)

        Box(modifier = Modifier.fillMaxSize()) {
            // ---- 左上：下一转向 --------------------------------------------
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(edge)
                    .deskGlassOverlay(barShape)
                    .padding(
                        horizontal = (NAVI_CARD_BAR_PAD_H_DP * k).dp,
                        vertical = (NAVI_CARD_BAR_PAD_V_DP * k).dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((NAVI_CARD_ICON_GAP_DP * k).dp),
            ) {
                // SDK 直接给的转向图标（`NaviInfo.getIconBitmap()`）。它和 SDK 自己的语音
                // 是同一套，不会出现"图标指左、语音说右"。拿不到就不画图标，只留文字。
                guidance.icon?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size((NAVI_CARD_ICON_DP * k).dp),
                    )
                }

                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = guidance.turnDistanceText,
                            fontSize = size((NAVI_CARD_TURN_DP * k).dp),
                            fontWeight = FontWeight.SemiBold,
                            // **颜色必须显式给**：Text 不写 color 时取 LocalContentColor，
                            // 它的默认值是黑色 —— 深色玻璃蒙版上就是"黑字压黑底"。
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = NAVI_CARD_TURN_LABEL,
                            fontSize = size((NAVI_CARD_ROAD_DP * k).dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            modifier = Modifier.padding(start = (4 * k).dp, bottom = (2 * k).dp),
                        )
                    }
                    Text(
                        // 优先"进入 XX 路"（这是导航真正要告诉用户的下一步）；
                        // 拿不到下一段路名时退回当前路名，总之不留空行。
                        text = guidance.nextRoad.ifBlank { guidance.currentRoad },
                        fontSize = size((NAVI_CARD_ROAD_DP * k).dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }

            // ---- 左下：剩余里程与预计时间 ----------------------------------
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(edge)
                    .deskGlassOverlay(barShape)
                    .padding(
                        horizontal = (NAVI_CARD_BAR_PAD_H_DP * k).dp,
                        vertical = (NAVI_CARD_BAR_PAD_V_DP * k).dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((6 * k).dp),
            ) {
                Text(
                    text = NAVI_CARD_REMAIN_LABEL,
                    fontSize = size((NAVI_CARD_META_DP * k).dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
                Text(
                    text = guidance.remainDistanceText,
                    fontSize = size((NAVI_CARD_META_DP * k).dp),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = guidance.remainTimeText,
                    fontSize = size((NAVI_CARD_META_DP * k).dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                )
            }
        }
    }
}

/**
 * 比例尺的参考宽度。**它是"一档字号"的锚点，不是卡片尺寸的假设** ——
 * 卡比它宽就按比例放大（到上限封顶），比它窄就缩小。本机这张卡是 460 dp。
 */
private const val NAVI_CARD_REF_WIDTH_DP: Float = 460f

private const val NAVI_CARD_EDGE_DP: Float = 12f
private const val NAVI_CARD_BAR_RADIUS_DP: Float = 16f
private const val NAVI_CARD_BAR_PAD_H_DP: Float = 14f
private const val NAVI_CARD_BAR_PAD_V_DP: Float = 10f
private const val NAVI_CARD_ICON_DP: Float = 36f
private const val NAVI_CARD_ICON_GAP_DP: Float = 12f
private const val NAVI_CARD_TURN_DP: Float = 26f
private const val NAVI_CARD_ROAD_DP: Float = 16f
private const val NAVI_CARD_META_DP: Float = 15f

private const val NAVI_CARD_TURN_LABEL: String = "后进入"
private const val NAVI_CARD_REMAIN_LABEL: String = "剩余"
