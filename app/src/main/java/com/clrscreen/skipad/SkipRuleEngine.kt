package com.clrscreen.skipad

/**
 * 节点快照。
 *
 * 把 [android.view.accessibility.AccessibilityNodeInfo] 中与判定相关的字段抽出来，
 * 使打分逻辑不依赖 Android 框架，便于单独推演与验证。
 */
data class NodeSnapshot(
    val text: String = "",
    val contentDescription: String = "",
    /** 已取 '/' 之后的部分并转小写，例如 tv_skip */
    val viewId: String = "",
    val className: String = "",
    val clickable: Boolean = false,
    val hasClickableAncestor: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height
    val screenArea: Long get() = screenWidth.toLong() * screenHeight

    /** 面积的百分比（0~100），屏幕尺寸未知时返回 -1 */
    val areaPercent: Int
        get() = if (screenArea <= 0) -1 else ((area * 100) / screenArea).toInt()
}

/** 判定结果 */
sealed interface Verdict {
    /** 达到阈值，应当点击 */
    data class Hit(val score: Int, val reasons: List<String>) : Verdict

    /** 未达阈值或被否决，不应点击 */
    data class Miss(val score: Int, val reasons: List<String>) : Verdict
}

/**
 * 评分制判定引擎。
 *
 * 相比原来的「命中任一关键词就点」，评分制把每个信号折算成分值，
 * 只有总分达到阈值才点击。这样：
 *  - 多个弱信号叠加（例如文案一般但位置在右下角、控件很小）也能命中；
 *  - 单个强信号遇到排除词会被直接否决，避免误伤；
 *  - 每条判定的得分与理由都可追溯，便于排查「为什么点了 / 为什么没点」。
 */
object SkipRuleEngine {

    /** 达到该分数才允许点击 */
    const val CLICK_THRESHOLD = 50

    /** 文案长度上限：超过这个长度几乎不可能是按钮 */
    const val MAX_TEXT_LENGTH = 12

    /**
     * 排除词：命中即直接否决。
     *
     * 这些词都含「跳过」，但语义是「跳过某个正常流程」，点了会破坏用户体验。
     */
    private val EXCLUDE_PHRASES = listOf(
        "跳过此步骤",
        "跳过本步骤",
        "跳过教程",
        "跳过引导",
        "跳过设置",
        "跳过验证",
        "跳过登录",
        "跳过片头",
        "跳过片尾",
        "跳过介绍",
        "跳过说明",
    )

    /** 完全等于这些文案时，视为强跳过信号 */
    private val EXACT_SKIP_TEXTS = listOf(
        "跳过", "跳過", "skip", "skipad", "跳过广告", "跳過廣告",
        "关闭广告", "关闭广告页", "closead",
    )

    /** 关键词：包含即视为弱跳过信号 */
    private val SKIP_KEYWORDS = listOf("跳过", "跳過", "skip")

    fun evaluate(node: NodeSnapshot): Verdict {
        val reasons = mutableListOf<String>()

        // ---------- 前置否决 ----------
        if (!node.enabled) return Verdict.Miss(-1000, listOf("节点被禁用(disabled)"))
        if (!node.visible) return Verdict.Miss(-1000, listOf("节点不可见"))
        if (node.width <= 0 || node.height <= 0) return Verdict.Miss(-1000, listOf("节点无有效尺寸"))

        val text = node.text.trim()
        val desc = node.contentDescription.trim()
        val id = node.viewId.lowercase()

        for (phrase in EXCLUDE_PHRASES) {
            if (text.contains(phrase) || desc.contains(phrase)) {
                return Verdict.Miss(-1000, listOf("命中排除词「$phrase」"))
            }
        }

        var score = 0

        // ---------- 文案信号 ----------
        val textScore = scoreText(text)
        if (textScore > 0) {
            score += textScore
            reasons += "文案「${text.take(20)}」+$textScore"
        }
        val descScore = scoreText(desc)
        if (descScore > 0) {
            score += descScore
            reasons += "无障碍描述「${desc.take(20)}」+$descScore"
        }

        // ---------- 控件 ID 信号 ----------
        if (id.contains("skip")) {
            score += 40
            reasons += "控件ID含 skip +40"
        } else if (id.contains("close") && id.contains("ad")) {
            score += 30
            reasons += "控件ID含 close+ad +30"
        }

        // ---------- 位置信号 ----------
        if (node.screenWidth > 0 && node.screenHeight > 0) {
            val rightRatio = node.right.toFloat() / node.screenWidth
            val topRatio = node.top.toFloat() / node.screenHeight
            val bottomRatio = node.bottom.toFloat() / node.screenHeight
            when {
                rightRatio >= 0.55f && topRatio >= 0.60f -> {
                    score += 15
                    reasons += "位于右下角 +15"
                }
                rightRatio >= 0.55f && bottomRatio <= 0.40f -> {
                    score += 12
                    reasons += "位于右上角 +12"
                }
                else -> Unit
            }
        }

        // ---------- 尺寸信号 ----------
        val percent = node.areaPercent
        when {
            percent in 0..8 -> {
                score += 10
                reasons += "控件面积小($percent%) +10"
            }
            percent >= 40 -> {
                score -= 20
                reasons += "控件几乎占满屏幕($percent%) -20"
            }
        }

        // ---------- 可点击性 ----------
        when {
            node.clickable -> {
                score += 10
                reasons += "自身可点击 +10"
            }
            node.hasClickableAncestor -> {
                score += 5
                reasons += "可点击祖先 +5"
            }
        }

        // ---------- 控件类型 ----------
        if (node.className.endsWith("Button") ||
            node.className.endsWith("ImageButton") ||
            node.className.endsWith("CompoundButton")
        ) {
            score += 8
            reasons += "按钮类控件 +8"
        }

        return if (score >= CLICK_THRESHOLD) {
            Verdict.Hit(score, reasons)
        } else {
            Verdict.Miss(score, reasons)
        }
    }

    /** 文案打分：完全匹配给高分，包含关键词给低分，超长或无关键词返回 0 */
    private fun scoreText(raw: String): Int {
        if (raw.isEmpty()) return 0
        if (raw.length > MAX_TEXT_LENGTH) return 0

        val collapsed = raw.replace("\\s".toRegex(), "").lowercase()
        if (collapsed.isEmpty()) return 0

        if (EXACT_SKIP_TEXTS.any { it.lowercase() == collapsed }) return 45
        if (SKIP_KEYWORDS.any { collapsed.contains(it.lowercase()) }) return 30
        return 0
    }
}
