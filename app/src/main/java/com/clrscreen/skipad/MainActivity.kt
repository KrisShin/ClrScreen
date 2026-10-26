package com.clrscreen.skipad

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_NOTIFICATION = 100
    }

    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var keepAliveSwitch: Switch
    private lateinit var toastSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        countText = findViewById(R.id.countText)
        batteryStatus = findViewById(R.id.batteryStatus)
        keepAliveSwitch = findViewById(R.id.switchKeepAlive)
        toastSwitch = findViewById(R.id.switchToast)

        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            KeepAliveHelper.openAccessibilitySettings(this)
        }

        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            val ok = KeepAliveHelper.requestIgnoreBatteryOptimizations(this)
            if (!ok) {
                Toast.makeText(this, R.string.battery_fallback, Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btnAutostart).setOnClickListener {
            val ok = KeepAliveHelper.openAutoStartSettings(this)
            if (!ok) {
                Toast.makeText(this, R.string.autostart_fallback, Toast.LENGTH_LONG).show()
            }
        }

        keepAliveSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setKeepAliveEnabled(this, checked)
            if (checked) {
                ensureNotificationPermission()
                KeepAliveService.start(this)
            } else {
                KeepAliveService.stop(this)
            }
        }

        toastSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setToastEnabled(this, checked)
            // 打开时立刻演示一次，让用户知道效果
            if (checked) {
                Toast.makeText(this, R.string.toast_skipped, Toast.LENGTH_SHORT).show()
            }
        }

        // 首次进入时，若后台守护为默认开启，主动拉起前台服务
        if (Prefs.isKeepAliveEnabled(this)) {
            ensureNotificationPermission()
            KeepAliveService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = isServiceEnabled()
        statusText.setText(if (enabled) R.string.service_on else R.string.service_off)
        statusText.setTextColor(
            getColor(if (enabled) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )
        countText.text = getString(R.string.skip_count, SkipAdAccessibilityService.skipCount)

        val batteryOk = KeepAliveHelper.isIgnoringBatteryOptimizations(this)
        batteryStatus.setText(if (batteryOk) R.string.battery_ok else R.string.battery_off)
        batteryStatus.setTextColor(
            getColor(if (batteryOk) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )

        keepAliveSwitch.isChecked = Prefs.isKeepAliveEnabled(this)
        toastSwitch.isChecked = Prefs.isToastEnabled(this)
    }

    /** Android 13+ 需要用户授予通知权限，否则前台服务的常驻通知不可见 */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
    }

    private fun isServiceEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabled.any { it.resolveInfo?.serviceInfo?.packageName == packageName }
    }
}
