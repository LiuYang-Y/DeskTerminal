package com.qingke.desk.hosting

import android.content.Context

/**
 * 一个被托管到虚拟屏上的第三方应用。
 *
 * 本轮只托管高德地图一个，所以这里只放一个常量；
 * 后续要加别的应用时，往 [HostTargets] 里加一项，其余代码不动。
 */
data class HostTarget(
    /** 包名。同时用于包可见性声明与"是否已安装"判定。 */
    val packageName: String,
    /** 显式入口 Activity。**不能**只给包名：没有 launcher 入口的应用（如系统拨号）
     *  按包名启动会落空，这一点在可行性实验里踩过。 */
    val activityName: String,
    val label: String,
) {
    /** `am start -n` 需要的 "pkg/activity" 形式。 */
    val component: String get() = "$packageName/$activityName"
}

object HostTargets {

    /**
     * 高德地图。
     *
     * 入口不是猜的：`cmd package resolve-activity --brief com.autonavi.minimap`
     * 在真机上解析出来的就是 SplashActivity。
     */
    val AMAP = HostTarget(
        packageName = "com.autonavi.minimap",
        activityName = "com.autonavi.map.activity.SplashActivity",
        label = "高德地图",
    )
}

/**
 * 目标应用是否可用。
 *
 * 只判"装了没装"，**不判**"停用没停用"：被 `pm disable-user` 停掉的包依然查得到，
 * 那种情况要在 `am start` 的返回里才能看出来，交给启动流程报错。
 */
@Suppress("DEPRECATION")
private fun HostTarget.info(context: Context) =
    context.packageManager.getPackageInfo(packageName, 0)

fun HostTarget.isInstalled(context: Context): Boolean = runCatching {
    info(context)
    true
}.getOrDefault(false)

/** 版本名，用于提示页上"到底装的是哪个版本"这类自助信息。 */
fun HostTarget.versionName(context: Context): String? = runCatching {
    info(context).versionName
}.getOrNull()
