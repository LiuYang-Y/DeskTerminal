package com.qingke.desk.map.navi

import android.content.Context
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.help.Tip
import com.amap.api.services.poisearch.PoiSearch
import com.qingke.desk.core.data.GeoPoint
import com.qingke.desk.core.data.ShellSettings
import com.qingke.desk.map.MapLog

/**
 * 目的地搜索。用**合包自带**的搜索能力（`com.amap.api.services.*`），
 * 不引第三个依赖 —— 换导航合包时这些类就已经在包里了（见 map/build.gradle.kts）。
 *
 * 两步走的理由（这是这个文件存在的全部原因）：
 *   1. `Inputtips` 负责**猜词**（"钟楼"→ 一串候选），但它常常**不给坐标**；
 *   2. `PoiSearch` 负责**定坐标**（按名字 + 城市检索，取第一条的 `LatLonPoint`）。
 * 直接把第 1 步的结果当目的地，会得到一个 (0,0) 附近的目标点 —— 那是几内亚湾，不是西安。
 *
 * 线程：SDK 的回调都在**主线程**，所以回调里更新 Compose state 是安全的，
 * 不需要再切线程（这与 [NaviSession] 一致）。
 *
 * 竞争：输入框每敲一个字都会发一次请求，返回顺序**不保证**与发出顺序一致。
 * 这里用 [seq] 做序号，只接受**最后一次**请求的回调，否则会出现
 * "打完字了，列表却跳回上一个字的结果"这种闪回。
 */
class PlaceSearch(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var seq = 0

    /**
     * 输入联想。[keyword] 少于 1 个字符时直接回空列表（SDK 会返回错误码，没必要发）。
     *
     * @param onResult 成功时的候选列表；空列表表示"没有匹配"。
     * @param onFailure 失败原因（给人看的中文短语）。
     */
    fun suggest(
        keyword: String,
        city: String,
        onResult: (List<PlaceSuggestion>) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) {
            onResult(emptyList())
            return
        }
        val mySeq = ++seq

        val query = InputtipsQuery(trimmed, city).apply {
            // **不限定城市**（2026-10-12 用户口径：搜索不该被当前市级限制）。
            //
            // 最初这里写的是 cityLimit = true + 城市写死"西安"，理由是"机器固定在西安，
            // 限定城市结果更准"。代价是**跨城市的目的地根本搜不出来**（搜"故宫"一个是
            // 都没有），而导航本来就是要去别的地方 —— 这个限制从产品逻辑上就说不通。
            //
            // 现在改成：不限城市 + 带上当前位置。高德会按"离我近"排序，
            // 于是"近的仍然排前面、远的也能搜到"，两头都成立。
            cityLimit = city.isNotBlank()
            ShellSettings.lastLocation(appContext)?.let {
                location = LatLonPoint(it.latitude, it.longitude)
            }
        }

        val tips = Inputtips(appContext, query)
        tips.setInputtipsListener { list, code ->
            // 迟到的响应直接丢：用户已经敲了别的字，这批结果属于上一个词。
            if (mySeq != seq) {
                MapLog.i("search: drop stale suggest (seq=$mySeq now=$seq)")
                return@setInputtipsListener
            }
            if (code != AMAP_CODE_OK) {
                MapLog.i("search: suggest failed keyword=$trimmed code=$code")
                onFailure("搜索失败（$code）")
                return@setInputtipsListener
            }
            val result = list.orEmpty().map { it.toSuggestion() }
            MapLog.i(
                "search: suggest keyword=$trimmed -> ${result.size} 条, " +
                    "带坐标=${result.count { it.point != null }} 条",
            )
            onResult(result)
        }
        tips.requestInputtipsAsyn()
    }

    /**
     * 把一条缺坐标的候选**补成带坐标**的。
     *
     * 已经有坐标的直接原样回调（省一次网络）。补不到就回调 null ——
     * 由界面去说"这条用不了"，而不是我们悄悄塞一个近似值。
     *
     * 检索策略：优先用名字 + 城市做关键字检索，取第一条有坐标的结果。
     * 不用 `searchPOIId`：联想结果的 `poiId` 在"没有坐标"的那些条目里往往也是空的，
     * 拿一个空 ID 去查只会再失败一次。
     */
    fun resolve(
        suggestion: PlaceSuggestion,
        city: String,
        onResult: (PlaceSuggestion?) -> Unit,
    ) {
        if (suggestion.point != null) {
            onResult(suggestion)
            return
        }

        val query = PoiSearch.Query(suggestion.name, "", city).apply {
            // 与上面同一个口径：给了城市才限定，没给就是全国检索。
            cityLimit = city.isNotBlank()
            pageSize = 1
            // 不要子 POI：我们要的是"能到的地方"，不是"某个门脸里的某个柜台"。
            requireSubPois(false)
        }

        val search = try {
            PoiSearch(appContext, query)
        } catch (t: Throwable) {
            MapLog.i("search: resolve construct failed ${t.javaClass.simpleName}")
            onResult(null)
            return
        }

        search.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
            override fun onPoiSearched(result: com.amap.api.services.poisearch.PoiResult?, code: Int) {
                val first = result?.pois?.firstOrNull()
                val latLon = first?.latLonPoint
                if (code != AMAP_CODE_OK || latLon == null) {
                    MapLog.i("search: resolve miss name=${suggestion.name} code=$code")
                    onResult(null)
                    return
                }
                MapLog.i("search: resolve ok name=${suggestion.name} -> ${latLon.latitude},${latLon.longitude}")
                onResult(
                    suggestion.copy(
                        address = suggestion.address.ifBlank { first.snippet.orEmpty() },
                        point = GeoPoint(latLon.latitude, latLon.longitude),
                        poiId = first.poiId ?: suggestion.poiId,
                    ),
                )
            }

            /** 单条 POI 检索的回调（我们没走这条），留空实现避免编译期抽象方法不全。 */
            override fun onPoiItemSearched(item: com.amap.api.services.core.PoiItem?, code: Int) = Unit
        })
        search.searchPOIAsyn()
    }

    private fun Tip.toSuggestion(): PlaceSuggestion {
        val p = point
        return PlaceSuggestion(
            name = name.orEmpty().ifBlank { address.orEmpty() },
            address = address.orEmpty(),
            district = district.orEmpty(),
            point = p?.let { GeoPoint(it.latitude, it.longitude) },
            poiId = poiID,
        )
    }

    private companion object {
        /** 高德的成功码，服务码与 POI 检索共用这一个值（1000）。 */
        const val AMAP_CODE_OK = 1000
    }
}
