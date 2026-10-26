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
import android.widget.Toast

/**
 * 开屏广告自动跳过服务。
 *
 * 原理：监听前台窗口变化，在窗口出现后的短时间内扫描界面节点树，
 * 对候选节点做**评分**，达到阈值才点击。纯本机运行，无任何网络行为。
 *
 * 相比早期版本（命中任一关键词就点），现在的判定是评分制：
 * 多个弱信号可以叠加命中，单个强信号遇到排除词会被直接否决。
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

        /** 点击成功后的冷却时间，避免同一界面被重复点击 */
        private const val CLICK_COOLDOWN_MS = 1200L

        /** TYPE_WINDOW_CONTENT_CHANGED 触发极频繁，需要节流 */
        private const val CONTENT_EVENT_THROTTLE_MS = 200L

        /** 窗口切换后持续观察的时长。开屏广告只在启动瞬间出现，
         *  限时观察可以把误点正常界面中「跳过」文案的概率降到最低 */
        private const val SPLASH_WATCH_WINDOW_MS = 8000L

        /** 窗口切换后的多次扫描时间点（毫秒）：跳过按钮常随广告异步渲染，延迟出现。
         *  覆盖到 6.5s，兼顾慢速广告与 WebView 加载 */
        private val SCAN_DELAYS_MS = longArrayOf(0, 250, 600, 1000, 1500, 2100, 2800, 3600, 4500, 5600, 6500)

        /** 系统包：这些包里的「跳过」属于系统流程（开机向导等），绝不能自动点击 */
        private val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.provision",
            "com.android.packageinstaller",
            "com.google.android.settings",
            "com.google.android.setupwizard",
            "com.google.android.packageinstaller",
        )

        /** 命中阈值，与 [SkipRuleEngine.CLICK_THRESHOLD] 保持一致 */
        private val CLICK_THRESHOLD = SkipRuleEngine.CLICK_THRESHOLD
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pendingScans = ArrayList<Runnable>()
    private var lastClickAt = 0L
    private var lastContentScanAt = 0L
    private var lastWindowChangeAt = 0L
    private var screenWidth = 0
    private var screenHeight = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
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
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(TAG, "窗口切换 pkg=$pkg cls=${event.className}")
                }
                scheduleScans(pkg)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastWindowChangeAt > SPLASH_WATCH_WINDOW_MS) return
                if (now - lastContentScanAt < CONTENT_EVENT_THROTTLE_MS) return
                lastContentScanAt = now
                tryScanAndClick(pkg)
            }

            else -> Unit
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

        val candidate = findBestCandidate(pkg)
        if (candidate == null) {
            // 诊断用：没有候选时，输出本轮扫描看到的最高分节点（不点击）
            if (BuildConfig.DEBUG) logTopCandidate(pkg)
            return
        }
        if (clickNode(candidate.node)) {
            lastClickAt = SystemClock.elapsedRealtime()
            skipCount += 1
            cancelPendingScans()
            showSkipToast()
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    TAG,
                    "已点击 pkg=$pkg score=${candidate.verdict.score} ${candidate.verdict.reasons}"
                )
            }
        }
    }

    /**
     * 跳过成功时弹一条轻提示，便于用户确认功能已生效。
     *
     * 用 Toast 而不是通知：不打断操作、不占通知栏、几秒后自动消失。
     * 可在主界面关闭。
     */
    private fun showSkipToast() {
        if (!Prefs.isToastEnabled(this)) return
        handler.post {
            runCatching {
                Toast.makeText(this, R.string.toast_skipped, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 诊断辅助：未命中时输出得分最高的节点摘要。
     *
     * 只用于排查「为什么没点」，不参与判定。仅在 debug 构建生效。
     */
    private fun logTopCandidate(pkg: String) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return

        var bestScore = Int.MIN_VALUE
        var bestDesc = ""
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < 400) {
            val node = queue.removeFirst()
            visited++
            val snap = toSnapshot(node)
            val verdict = SkipRuleEngine.evaluate(snap)
            val score = when (verdict) {
                is Verdict.Hit -> verdict.score
                is Verdict.Miss -> verdict.score
            }
            // 只关心有文字/描述/ID 的节点，过滤纯容器
            val label = snap.text.ifBlank { snap.contentDescription }
            if (label.isNotBlank() && score > bestScore) {
                bestScore = score
                bestDesc = "text='${snap.text.take(24)}' desc='${snap.contentDescription.take(24)}' " +
                    "id='${snap.viewId}' cls='${snap.className.substringAfterLast('.')}' " +
                    "clickable=${snap.clickable} score=$score"
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        if (bestDesc.isNotEmpty()) {
            android.util.Log.d(TAG, "未命中 pkg=$pkg 最高分节点: $bestDesc")
        }
    }

    /**
     * 在所有窗口中寻找得分最高的候选节点。
     *
     * 之所以遍历 [getWindows] 而不只是 [rootInActiveWindow]：
     * 开屏广告常以独立窗口 / 悬浮层形式存在，活动窗口的根节点可能取不到它。
     */
    private fun findBestCandidate(pkg: String): Candidate? {
        var best: Candidate? = null

        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        runCatching {
            windows?.forEach { w ->
                w.root?.let { r -> if (roots.none { it == r }) roots.add(r) }
            }
        }

        for (root in roots) {
            val rootPkg = root.packageName?.toString() ?: continue
            // 只扫描当前前台应用的窗口；系统窗口与其它应用不参与，
            // 避免在切换过程中误点别的界面
            if (rootPkg != pkg) continue
            collectCandidates(root, best)?.let { best = it }
        }
        return best
    }

    /** BFS 遍历，返回本轮更优的候选 */
    private fun collectCandidates(
        root: AccessibilityNodeInfo,
        currentBest: Candidate?,
    ): Candidate? {
        var best = currentBest
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val verdict = SkipRuleEngine.evaluate(toSnapshot(node))
            if (verdict is Verdict.Hit) {
                if (best == null || verdict.score > best.verdict.score) {
                    best = Candidate(node, verdict)
                }
                // 高分候选已足够可靠，无需继续遍历
                if (verdict.score >= 90) return best
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return best
    }

    /** 把 AccessibilityNodeInfo 转成不依赖框架的快照 */
    private fun toSnapshot(node: AccessibilityNodeInfo): NodeSnapshot {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        return NodeSnapshot(
            text = node.text?.toString().orEmpty(),
            contentDescription = node.contentDescription?.toString().orEmpty(),
            viewId = node.viewIdResourceName?.substringAfterLast('/')?.lowercase().orEmpty(),
            className = node.className?.toString().orEmpty(),
            clickable = node.isClickable,
            hasClickableAncestor = hasClickableAncestor(node),
            enabled = node.isEnabled,
            visible = node.isVisibleToUser,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
    }

    /** 向上最多找 4 层，判断是否存在可点击祖先 */
    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            if (parent.isClickable) return true
            parent = parent.parent
            depth++
        }
        return false
    }

    /**
     * 点击目标节点。
     *
     * 顺序上有讲究：`performAction(ACTION_CLICK)` 在标准 Android 控件上可靠，
     * 但在 **WebView / 自绘控件** 上会返回 true 却什么都不做（实测确认：
     * WebView 里的 H5 按钮，performAction 返回成功但网页没有任何反应，
     * 而同样坐标的手势点击可以正常触发）。
     *
     * 因此对 WebView 内的节点优先用手势，避免「以为点成功了、其实没点」，
     * 这会导致计数虚增且广告没跳过。
     */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (isInsideWebView(node)) {
            // WebView 内优先手势，失败再退回 performAction
            if (tapCenter(node)) return true
            return performClickUpwards(node)
        }

        if (performClickUpwards(node)) return true
        // 自绘控件兜底：手势点击坐标
        return tapCenter(node)
    }

    /** performAction 沿祖先链向上尝试 */
    private fun performClickUpwards(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    /** 手势点击节点中心 */
    private fun tapCenter(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        return dispatchTap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    /** 判断节点是否位于 WebView 内部（沿祖先链最多查 8 层） */
    private fun isInsideWebView(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 8) {
            if (current.className?.toString()?.contains("WebView") == true) return true
            current = current.parent
            depth++
        }
        return false
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

    private data class Candidate(
        val node: AccessibilityNodeInfo,
        val verdict: Verdict.Hit,
    )
}

/** 日志 TAG，与 [SkipAdAccessibilityService] 共用 */
private const val TAG = "ClrScreen"
