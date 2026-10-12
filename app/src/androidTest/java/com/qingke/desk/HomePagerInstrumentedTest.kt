package com.qingke.desk

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 首页分页与地图卡的真机（仪器化）验证。
 *
 * 为什么用 Compose UI 测试而不是 `adb shell input`：
 * 基准机是 production 构建，**adb 注入一律被系统拒绝** ——
 *   java.lang.SecurityException: Injecting to another application requires INJECT_EVENTS permission
 * 而 Compose 的测试框架在**应用进程内**做命中测试与事件分发，不经过系统 InputManager，
 * 所以它能真实地验证两件必须验的事：
 *
 *   1. 手指落在地图卡上横滑时，事件归 Pager（翻页），不归地图；
 *   2. 点地图卡时，进全屏地图页。
 *
 * 这两条正是"手势冲突"方案的核心（见 AMapCanvas 里的 TouchTransparentLayout 与
 * HomeScreen 的注释）—— 只靠肉眼点一下是验不出"事件到底被谁消费了"的。
 *
 * 运行：
 *   ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class HomePagerInstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mapCard_swipeLeft_pagesToSecondPage_and_swipeRight_back() {
        rule.awaitTag(HOME_MAP_CARD)
        rule.awaitSelected(PAGE_DOT_FIRST)

        // 关键：手指按在地图卡上往左滑 —— 必须翻到第 2 页（事件归 Pager）
        rule.onNodeWithTag(HOME_MAP_CARD).performTouchInput { swipeLeft() }
        rule.awaitSelected(PAGE_DOT_SECOND)

        // 再滑回来
        rule.onNodeWithTag(HOME_MAP_CARD).performTouchInput { swipeRight() }
        rule.awaitSelected(PAGE_DOT_FIRST)
    }

    @Test
    fun mapCard_click_opensFullscreenMap() {
        rule.awaitTag(HOME_MAP_CARD)

        rule.onNodeWithTag(HOME_MAP_CARD).performClick()
        rule.awaitTag(FULLSCREEN_MAP)

        // 回首页：点侧栏的「首页」项
        rule.onNodeWithText("首页").performClick()
        rule.awaitTag(HOME_MAP_CARD)
    }

    @Test
    fun pageDots_selectBothPages() {
        rule.awaitTag(HOME_MAP_CARD)

        rule.onNodeWithTag(PAGE_DOT_SECOND).performClick()
        rule.awaitSelected(PAGE_DOT_SECOND)
        // 第 2 页的时钟卡应该真的在屏幕上（不只是被组合出来）
        rule.onNodeWithTag(HOME_CLOCK_CARD).assertIsDisplayed()

        rule.onNodeWithTag(PAGE_DOT_FIRST).performClick()
        rule.awaitSelected(PAGE_DOT_FIRST)
        rule.onNodeWithTag(HOME_MAP_CARD).assertIsDisplayed()
    }

    /** 首页 ↔ 全屏地图页来回切 10 次：每一步都断言界面真的到位（地图重建与否看 map.log）。 */
    @Test
    fun mapCard_toFullscreen_andBack_tenTimes() {
        rule.awaitTag(HOME_MAP_CARD)

        repeat(10) { round ->
            rule.onNodeWithTag(HOME_MAP_CARD).performClick()
            rule.awaitTag(FULLSCREEN_MAP)

            rule.onNodeWithText("首页").performClick()
            rule.awaitTag(HOME_MAP_CARD)
        }
    }

    /** 翻页 20 次：交替点两个圆点，每次断言当前页正确（不卡、不误触地图）。 */
    @Test
    fun paging_twentyTimes() {
        rule.awaitTag(HOME_MAP_CARD)

        repeat(20) { index ->
            val target = if (index % 2 == 0) PAGE_DOT_SECOND else PAGE_DOT_FIRST
            rule.onNodeWithTag(target).performClick()
            rule.awaitSelected(target)
        }
    }
}

private const val HOME_MAP_CARD = "home-map-card"
private const val HOME_CLOCK_CARD = "home-clock-card"
private const val FULLSCREEN_MAP = "fullscreen-map"
private const val PAGE_DOT_FIRST = "page-dot-0"
private const val PAGE_DOT_SECOND = "page-dot-1"

private const val DEFAULT_TIMEOUT_MS = 10_000L

/** 等某个 tag 出现（组合完成）。 */
private fun AndroidComposeTestRule<*, *>.awaitTag(tag: String) {
    waitUntil(DEFAULT_TIMEOUT_MS) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
}

/** 等某个圆点变成选中态 —— 它是"当前在第几页"的唯一事实来源。 */
private fun AndroidComposeTestRule<*, *>.awaitSelected(tag: String) {
    waitUntil(DEFAULT_TIMEOUT_MS) {
        runCatching {
            onNodeWithTag(tag).assertIsSelected()
        }.isSuccess
    }
}
