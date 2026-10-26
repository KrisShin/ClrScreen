package com.clrscreen.skipad

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 应用更新后的自恢复。
 *
 * 需要说清楚「开机自启」到底做了什么：
 *  - **无障碍服务本身不需要它**。系统会记住 enabled_accessibility_services，
 *    重启后自动重新绑定，这一点已在模拟器上实测确认。
 *  - 本接收器的作用是恢复**前台常驻通知**，让进程优先级回到前台级别，
 *    降低开机后被 LMK 优先回收的概率。
 *
 * 因此即使用户在系统里禁用了本应用的自启动，无障碍跳过功能依然能用，
 * 只是进程更容易被清理。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                // 只有用户开启过「后台守护」开关时才恢复，避免强推常驻通知
                if (Prefs.isKeepAliveEnabled(context)) {
                    runCatching { KeepAliveService.start(context) }
                }
            }
        }
    }
}
