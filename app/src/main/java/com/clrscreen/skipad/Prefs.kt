package com.clrscreen.skipad

import android.content.Context

/**
 * 轻量设置存储。
 *
 * 只保存「是否启用后台守护」这一个开关，用 SharedPreferences 足够。
 * 注意：这里保存的是用户偏好，不涉及任何界面内容或个人信息。
 */
object Prefs {

    private const val FILE = "clrscreen_prefs"
    private const val KEY_KEEP_ALIVE = "keep_alive_enabled"

    /** 后台守护默认开启：开箱即用的体验更重要，用户可随时关闭 */
    private const val DEFAULT_KEEP_ALIVE = true

    fun isKeepAliveEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEP_ALIVE, DEFAULT_KEEP_ALIVE)

    fun setKeepAliveEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply()
    }
}
