package com.qingke.desk.map.navi

import com.qingke.desk.core.data.GeoPoint

/**
 * 一条"地点候选"。搜索联想与精确检索共用同一个模型。
 *
 * [point] 是**可空的**，这不是设计偷懒，是 SDK 的真实行为：
 * `Inputtips` 的联想结果常常只给名字与地址、不给坐标（纯文本猜词尤其如此）。
 * 所以调用方**必须**处理 null —— 要么走 [PlaceSearch.resolve] 补一次坐标，
 * 要么明确告诉用户"这条用不了"。绝不能默认它一定有坐标。
 */
data class PlaceSuggestion(
    val name: String,
    val address: String = "",
    val district: String = "",
    val point: GeoPoint? = null,
    val poiId: String? = null,
) {
    /** 展示用副标题：行政区 + 地址，空的自动省掉，不留"·"这种孤零零的分隔符。 */
    val subtitle: String
        get() = listOf(district, address).filter { it.isNotBlank() }.joinToString(" · ")
}
