package com.qingke.desk.core.model

/**
 * 首页卡片。纯 Kotlin，不依赖 Compose —— 颜色以 ARGB 的 Long 传递，
 * 由 :core:ui 负责转成 Color，避免 model 层被 UI 框架污染。
 */
data class HomeCard(
    val id: String,
    val kind: HomeCardKind,
    val title: String,
    val subtitle: String = "",
    /** 卡片主体内容行。时钟卡不用，天气/日程/待办/智能家居用。 */
    val lines: List<CardLine> = emptyList(),
    /**
     * 卡面最底部的一行小字，用来放**署名**这类"必须显示但不能抢内容"的信息。
     *
     * 现在只有天气卡用（Open-Meteo 的 CC BY 4.0 要求署名）。
     * 空字符串 = 不显示这一行，卡片底部不留白。
     */
    val footnote: String = "",
    /** 卡片主色（ARGB）。为空则使用默认卡片底色。 */
    val accentArgb: Long? = null,
    /** 横向占几格。后续支持宽卡（如音乐播放器 2×1）时启用。 */
    val spanX: Int = 1,
)

data class CardLine(
    val text: String,
    val trailing: String = "",
    val checked: Boolean? = null,
)

enum class HomeCardKind {
    CLOCK,
    AGENDA,
    TODO,
    WEATHER,
    SMART_HOME,
    MUSIC,
    /**
     * 投屏入口。它既是信息卡也是操作卡 —— 点一下就进入投屏流程。
     * 桌面终端上"把 iPhone 投进来"是使用频率最高的动作，给它在栅格里留一张。
     */
    PROJECTION,
}
