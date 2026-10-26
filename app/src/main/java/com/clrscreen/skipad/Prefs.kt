package com.clrscreen.skipad

import android.content.Context

/**
 * 轻量设置存储。
 *
 * 保存两个布尔开关，用 SharedPreferences 足够：
 *  - 后台守护（常驻通知）
 *  - 跳过时是否弹出轻提示
 *
 * 注意：这里保存的是用户偏好，不涉及任何界面内容或个人信息。
 */
object Prefs {

    private const val FILE = "clrscreen_prefs"
    private const val KEY_KEEP_ALIVE = "keep_alive_enabled"
    private const val KEY_TOAST = "toast_enabled"

    /** 后台守护默认开启：开箱即用的体验更重要，用户可随时关闭 */
    private const val DEFAULT_KEEP_ALIVE = true

    /** 轻提示默认开启：便于用户确认识别已生效 */
    private const val DEFAULT_TOAST = true

    fun isKeepAliveEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEP_ALIVE, DEFAULT_KEEP_ALIVE)

    fun setKeepAliveEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply()
    }

    fun isToastEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_TOAST, DEFAULT_TOAST)

    fun setToastEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TOAST, enabled).apply()
    }
}
