package com.qingke.desk.feature.media

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 通知监听"绑定状态变化"的广播点。
 *
 * 为什么需要一个额外的桥：用户去设置页授权、或者系统回收服务，这两件事
 * 不发生在我们的调用栈里，[MediaSessionMonitor] 无从感知。
 * 而服务自身又拿不到 monitor 的实例（它被系统以独立组件身份绑定）。
 * 所以让服务在这里喊一声，monitor 注册进来听。
 *
 * 用 CopyOnWriteArrayList 而不是普通 List：回调可能来自 binder 线程，
 * 而注册/注销发生在主线程，两边会并发改同一个集合。
 */
internal object MediaListenerBridge {

    private val callbacks = CopyOnWriteArrayList<() -> Unit>()

    fun register(callback: () -> Unit) {
        callbacks.add(callback)
    }

    fun unregister(callback: () -> Unit) {
        callbacks.remove(callback)
    }

    fun dispatch() {
        callbacks.forEach { runCatching { it.invoke() } }
    }
}
