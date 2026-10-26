package com.clrscreen.skipad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 开屏广告自动跳过服务。
 *
 * 原理：监听前台窗口变化，在窗口出现后的短时间内扫描界面节点树，
 * 找到“跳过 / 跳過 / skip”类控件后自动点击。纯本机运行，无任何网络行为。
 */
class SkipAdAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SkipAdAccessibilityService? = null
            private set

        /** 累计成功跳过的次数，供主界面展示 */
        @Volatile
        var skipCount: Int = 0
            private set

        /** “跳过”文案的最大长度：超过的大概率是说明文字而不是按钮 */
        private const val MAX_TEXT_LENGTH = 12

        /** 点击成功后的冷却时间，避免同一界面被重复点击 */
        private const val CLICK_COOLDOWN_MS = 1500L

        /** TYPE_WINDOW_CONTENT_CHANGED 触发极频繁，需要节流 */
        private const val CONTENT_EVENT_THROTTLE_MS = 250L

        /** 仅在窗口切换后该时间内响应内容变化：开屏广告只出现在启动瞬间，
         *  限时扫描可以把误点正常界面中“跳过”文案（如教程里的“跳过此步骤”）的概率降到最低 */
        private const val SPLASH_WATCH_WINDOW_MS = 6000L

        /** 窗口切换后的多次扫描时间点（毫秒）：跳过按钮常常随广告异步渲染，延迟出现 */
        private val SCAN_DELAYS_MS = longArrayOf(0, 300, 700, 1200, 1800, 2600)

        /** 系统包：这些包里的“跳过”属于系统流程（开机向导等），绝不能自动点击 */
        private val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.provision",
            "com.android.packageinstaller",
            "com.google.android.settings",
            "com.google.android.setupwizard",
            "com.google.android.packageinstaller",
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pendingScans = ArrayList<Runnable>()
    private var lastClickAt = 0L
    private var lastContentScanAt = 0L
    private var lastWindowChangeAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onInterrupt() {
        cancelPendingScans()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in SYSTEM_PACKAGES) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 新窗口（Activity 或对话框）出现，开始一轮限时扫描
                lastWindowChangeAt = SystemClock.elapsedRealtime()
                scheduleScans(pkg)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastWindowChangeAt > SPLASH_WATCH_WINDOW_MS) return
                if (now - lastContentScanAt < CONTENT_EVENT_THROTTLE_MS) return
                lastContentScanAt = now
                tryScanAndClick(pkg)
            }

            else -> {
                // 服务配置只订阅了窗口切换与内容变化两类事件，这里显式忽略其余类型
            }
        }
    }

    private fun scheduleScans(pkg: String) {
        cancelPendingScans()
        for (delay in SCAN_DELAYS_MS) {
            val task = Runnable {
                val root = rootInActiveWindow ?: return@Runnable
                if (root.packageName?.toString() == pkg) tryScanAndClick(pkg)
            }
            pendingScans.add(task)
            handler.postDelayed(task, delay)
        }
    }

    private fun tryScanAndClick(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickAt < CLICK_COOLDOWN_MS) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return

        val target = findSkipNode(root) ?: return
        if (clickNode(target)) {
            lastClickAt = SystemClock.elapsedRealtime()
            skipCount += 1
            cancelPendingScans()
        }
    }

    /** 广度优先遍历节点树，优先返回自身可点击的“跳过”节点 */
    private fun findSkipNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var fallback: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (matchesSkip(node)) {
                if (node.isClickable) return node
                if (fallback == null) fallback = node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return fallback
    }

    private fun matchesSkip(node: AccessibilityNodeInfo): Boolean {
        // 控件 id 含 skip（如 tv_skip / btn_skip）是很强的信号
        val viewId = node.viewIdResourceName?.substringAfterLast('/')?.lowercase()
        if (!viewId.isNullOrEmpty() && viewId.contains("skip")) return true

        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty() && isSkipText(text)) return true
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (desc.isNotEmpty() && isSkipText(desc)) return true
        return false
    }

    /** 匹配 “跳过 / 跳过广告 / 3s后跳过 / Skip 5” 等文案 */
    private fun isSkipText(raw: String): Boolean {
        if (raw.length > MAX_TEXT_LENGTH) return false
        val collapsed = raw.replace("\\s".toRegex(), "")
        return collapsed.contains("跳过") ||
            collapsed.contains("跳過") ||
            collapsed.contains("skip", ignoreCase = true)
    }

    /** 依次尝试：自身 performAction 点击 → 向上找可点击祖先 → 模拟手势点击坐标 */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }
            current = current.parent
        }
        // 部分广告页用 WebView/自绘控件，performAction 无效，用手势兜底
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        return dispatchTap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun cancelPendingScans() {
        for (task in pendingScans) handler.removeCallbacks(task)
        pendingScans.clear()
    }

    private fun teardown() {
        cancelPendingScans()
        if (instance === this) instance = null
    }
}
