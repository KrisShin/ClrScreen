package com.clrscreen.skipad.demo

import android.util.Log

/**
 * 靶场结果埋点。
 *
 * 所有「跳过按钮被点击」和「误伤按钮被点击」都通过这里打日志，
 * 便于用 `adb logcat -s ClrScreenDemo:I` 做自动化断言。
 */
object ResultLog {
    const val TAG = "ClrScreenDemo"

    /** 场景开始展示 */
    fun shown(scenario: Scenario) {
        Log.i(TAG, "SHOWN id=${scenario.id}")
    }

    /**
     * 跳过按钮被点击（或场景自行超时结束）。
     * @param reason CLICKED 表示被点掉；TIMEOUT 表示无人点击、倒计时自然结束
     */
    fun skipped(scenario: Scenario, reason: String) {
        Log.i(TAG, "SKIPPED id=${scenario.id} reason=$reason")
    }

    /** 不该被点的控件被点了 —— 误伤 */
    fun falsePositive(scenario: Scenario) {
        Log.i(TAG, "FALSE_POSITIVE id=${scenario.id}")
    }
}
