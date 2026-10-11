package com.qingke.desk.core.data

import com.qingke.desk.core.model.CardLine
import com.qingke.desk.core.model.HomeCard
import com.qingke.desk.core.model.HomeCardKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 首页卡片数据源。先不接任何真实后端。
 */
interface HomeRepository {

    /** 卡片列表。真实实现换成 Room / 天气 API 时签名不变。 */
    fun observeCards(): Flow<List<HomeCard>>
}

/**
 * 假数据实现。时钟卡片每 30s 走一步，用来验证栅格里的动态内容不会引起重排抖动。
 */
class FakeHomeRepository : HomeRepository {

    override fun observeCards(): Flow<List<HomeCard>> = flow {
        var clock = "08:37"
        while (true) {
            emit(cards(clock))
            delay(30_000L)
            clock = nextMinute(clock)
        }
    }

    private fun cards(clock: String): List<HomeCard> = listOf(
        HomeCard(
            id = "clock",
            kind = HomeCardKind.CLOCK,
            title = clock,
            subtitle = "10月10日 星期六",
        ),
        HomeCard(
            id = "projection",
            kind = HomeCardKind.PROJECTION,
            title = "手机投屏",
            subtitle = "iPhone · CarPlay",
        ),
        HomeCard(
            id = "agenda",
            kind = HomeCardKind.AGENDA,
            title = "今日日程",
            lines = listOf(
                CardLine("数据结构", "10:00"),
                CardLine("实习日志评审", "14:30"),
                CardLine("专升本英语", "19:00"),
            ),
        ),
        HomeCard(
            id = "todo",
            kind = HomeCardKind.TODO,
            title = "待办",
            lines = listOf(
                CardLine("整理投屏模块接口", checked = false),
                CardLine("提交 tcamp 作品文案", checked = true),
                CardLine("羽毛球打卡", checked = false),
            ),
        ),
        HomeCard(
            id = "weather",
            kind = HomeCardKind.WEATHER,
            title = "西安",
            subtitle = "18° 多云",
            lines = listOf(CardLine("体感 17°", "湿度 62%")),
        ),
        HomeCard(
            id = "music",
            kind = HomeCardKind.MUSIC,
            title = "音乐",
            subtitle = "本地音频 · Media3",
        ),
    )

    private fun nextMinute(value: String): String {
        val parts = value.split(":")
        val hour = parts[0].toInt()
        val minute = parts[1].toInt() + 1
        return if (minute >= 60) {
            "%02d:00".format((hour + 1) % 24)
        } else {
            "%02d:%02d".format(hour, minute)
        }
    }
}
