package com.qingke.desk.map

import android.content.Context
import com.amap.api.maps.MapsInitializer

/**
 * 高德 SDK 的隐私合规闸门。
 *
 * 官方《开发注意事项》的原文要求：
 *   · `MapsInitializer.updatePrivacyShow(context, isContains, isShow)`
 *   · `MapsInitializer.updatePrivacyAgree(context, isAgree)`
 *   · "在构造 MapView 之前必须进行合规检查"
 *   · "合规不通过地图将白屏"
 *
 * 所以本文件的调用顺序是硬约束，不能挪：**两个接口都调完之前，不许 new MapView**。
 * 界面侧由 [MapPane] 把这件事做成"先弹隐私政策、同意后才挂地图"，
 * 而不是"先挂地图、再补一个同意按钮" —— 后者在真机上就是先白屏一下。
 *
 * 状态存 SharedPreferences。这里用 `apply()` 就够（不像 Shizuku 那个开机提示：
 * 那个要在冷启动后立刻 force-stop 的场景下不丢，必须 commit；这个没有那个约束）。
 */
object AMapPrivacy {

    private const val PREFS = "desk_amap_privacy"
    private const val KEY_AGREED = "agreed"
    private const val KEY_AGREED_AT = "agreed_at"

    /** 用户是否已同意。 */
    fun isAgreed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AGREED, false)

    /** 同意时间戳，没同意过返回 0。用于日后"政策更新需要重新同意"的判断。 */
    fun agreedAt(context: Context): Long =
        prefs(context).getLong(KEY_AGREED_AT, 0L)

    /**
     * 用户点了"同意"：先落盘，再立刻把合规状态同步给 SDK。
     *
     * 顺序是"先落盘"——万一 updatePrivacy* 抛异常（SDK 版本差异、so 没加载上），
     * 至少不会出现"界面以为同意了、重进又弹一次"的循环。
     */
    fun agree(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_AGREED, true)
            .putLong(KEY_AGREED_AT, System.currentTimeMillis())
            .apply()
        push(context)
    }

    /** 撤回同意（设置页用得上；撤回后界面必须立刻把地图摘掉）。 */
    fun revoke(context: Context) {
        prefs(context).edit().putBoolean(KEY_AGREED, false).apply()
        runCatching { MapsInitializer.updatePrivacyAgree(context.applicationContext, false) }
    }

    /**
     * 进程启动时补一次合规声明。
     *
     * 为什么需要：updatePrivacy* 的状态由 SDK 自己存在**进程内**，
     * 每次冷启动都得重新告诉它一遍。用户上次已经同意过（SharedPreferences 里有记录），
     * 但 SDK 不知道 —— 不补这一下，冷启动后第一帧地图仍然白屏。
     */
    fun applyIfAgreed(context: Context) {
        if (isAgreed(context)) push(context)
    }

    /**
     * isContains = true：我们的隐私政策里确实写明了会包含高德的隐私权政策；
     * isShow = true：确实弹窗告知过用户。
     * 两个 true 都必须是事实 —— 它们是给 SDK 的声明，不是给 SDK 的开关。
     */
    private fun push(context: Context) {
        val app = context.applicationContext
        runCatching {
            MapsInitializer.updatePrivacyShow(app, true, true)
            MapsInitializer.updatePrivacyAgree(app, true)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
