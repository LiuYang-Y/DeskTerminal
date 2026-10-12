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
 *
 * 2026-10-12 起只有三张：时钟、天气、音乐。
 * 手机投屏 / 日程 / 待办三张已删除 —— 投屏在侧栏有入口（那才是它的位置，
 * 首页上它只是一张"点了去投屏"的重复入口），日程和待办没有真实数据源，
 * 与其放三条写死的假条目，不如等真数据接进来再放回第二页。
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
