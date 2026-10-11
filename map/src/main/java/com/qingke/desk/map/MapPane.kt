package com.qingke.desk.map

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingke.desk.map.BuildConfig

/**
 * 地图面板 + 隐私闸门。
 *
 * 顺序是先弹隐私说明、用户同意之后才构造 `MapView` —— 反过来做在真机上就是
 * 先白屏一下再出图（官方原文："合规不通过地图将白屏"）。
 *
 * 拒绝的后果要说清楚：不同意也能继续用桌面终端的其它部分，只是这块位置没有地图。
 * 不写"不同意就退出" —— 那是在绑架用户，而且本项目也没有那个动机。
 *
 * @param onConsentChanged 可选回调，让外层知道当前能不能显示地图（用于排布局）。
 */
@Composable
fun MapPane(
    modifier: Modifier = Modifier,
    style: AMapStyle = AMapStyle.NORMAL,
    showMyLocation: Boolean = true,
    onConsentChanged: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    var agreed by remember { mutableStateOf(AMapPrivacy.isAgreed(context)) }

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
            style = style,
            showMyLocation = showMyLocation,
        )
        return
    }

    AMapPrivacyCard(
        modifier = modifier,
        onAgree = {
            AMapPrivacy.agree(context)
            MapLog.i("privacy agreed at ${AMapPrivacy.agreedAt(context)}")
            agreed = true
            onConsentChanged?.invoke(true)
        },
        onDecline = {
            MapLog.i("privacy declined")
            onConsentChanged?.invoke(false)
        },
    )
}

/**
 * 隐私说明卡。
 *
 * 文字按高德官方《SDK 合规使用方案》里要求开发者向用户说明的口径写：
 * SDK 名称、第三方主体、使用目的、收集的信息类型、隐私政策链接。
 * 少写哪一项都属于披露不完整。
 */
@Composable
fun AMapPrivacyCard(
    modifier: Modifier = Modifier,
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
            val narrow = maxWidth < 300.dp
            val body = if (narrow) 12.sp else 14.sp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (narrow) 14.dp else 20.dp),
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
