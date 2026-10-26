package com.clrscreen.skipad

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 保活相关的系统设置辅助。
 *
 * 这里只做三件有实际意义的事：
 *  1. 申请忽略电池优化 —— 不加白名单时，Doze / 省电模式会冻结进程；
 *  2. 引导用户到厂商的「自启动 / 后台运行」管理页 —— 各家路径不同且无标准 API；
 *  3. 检测无障碍服务是否真的在运行 —— 用于主界面提示。
 *
 * 明确做不到的事：无法以编程方式给自己加自启动白名单（需要用户手动确认），
 * 也无法阻止用户手动「强行停止」。
 */
object KeepAliveHelper {

    /** 是否已在电池优化白名单中（minSdk 24 已高于 M，无需版本判断） */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 申请忽略电池优化。
     *
     * 会弹出系统确认框；用户拒绝则返回 false。
     * 注意：部分 ROM 把这个入口也改掉了，此时应回退到 [openBatterySettings]。
     *
     * 关于 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`：lint 会提示它可能违反
     * Play Store 的电池策略。本项目不通过 Play 分发，且用途正当（保证无障碍
     * 服务在后台持续工作），因此保留。如需上架 Play，应改为引导用户到系统设置页手动关闭优化。
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (isIgnoringBatteryOptimizations(context)) return true

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            // 部分 ROM 屏蔽了该 action，退回到电池优化列表页
            openBatterySettings(context)
        }
    }

    /** 打开系统电池优化设置列表 */
    fun openBatterySettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * 打开厂商的自启动 / 后台运行管理页。
     *
     * Android 没有统一 API，只能按厂商逐个尝试。全部失败时返回 false，
     * 由调用方提示用户手动前往。
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val candidates = buildList {
            when (Build.MANUFACTURER.lowercase()) {
                "xiaomi", "redmi" -> {
                    add(Intent().setClassName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    ))
                    add(Intent().setClassName(
                        "com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                    ))
                }
                "huawei", "honor" -> {
                    add(Intent().setClassName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    ))
                    add(Intent().setClassName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"
                    ))
                }
                "oppo", "realme", "oneplus" -> {
                    add(Intent().setClassName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    ))
                    add(Intent().setClassName(
                        "com.oplus.safecenter",
                        "com.oplus.safecenter.startupapp.StartupAppListActivity"
                    ))
                }
                "vivo", "iqoo" -> {
                    add(Intent().setClassName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    ))
                }
                "samsung" -> {
                    add(Intent().setClassName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity"
                    ))
                }
            }
            // 通用兜底：应用详情页
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            })
        }

        for (intent in candidates) {
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    /** 打开无障碍设置页 */
    fun openAccessibilitySettings(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        true
    }.getOrDefault(false)
}
