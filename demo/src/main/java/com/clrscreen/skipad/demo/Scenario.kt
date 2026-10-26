package com.clrscreen.skipad.demo

/**
 * 靶场场景定义。
 *
 * 每个场景对应一种真实世界出现过的开屏广告 / 易误伤形态。
 * [expectSkip] 表示「理想情况下 ClrScreen 应该点掉它」，
 * [expectIgnore] 表示「理想情况下不该点」。
 *
 * 两者都为空表示已知无解（例如纯 Canvas 绘制），仅作为观察项。
 */
enum class Scenario(
    val id: String,
    val title: String,
    val desc: String,
    val expectSkip: Boolean = false,
    val expectIgnore: Boolean = false,
    val knownLimitation: String? = null,
) {

    // ===== 应当成功跳过 =====

    TEXT_PLAIN(
        id = "text_plain",
        title = "标准文字按钮",
        desc = "最常见的形态：Button 上写「跳过 5」，每秒刷新倒计时。",
        expectSkip = true,
    ),
    TEXT_CORNER(
        id = "text_corner",
        title = "右上角小字跳过",
        desc = "文案只有「跳过」，字号小、位于右上角，无边框无背景。",
        expectSkip = true,
    ),
    DESC_ONLY(
        id = "desc_only",
        title = "仅无障碍描述",
        desc = "按钮上写「关闭」，但 contentDescription 为「跳过广告」。",
        expectSkip = true,
    ),
    VIEW_ID_SKIP(
        id = "view_id_skip",
        title = "靠控件 ID 识别",
        desc = "文案是「关闭」，但 viewId 为 btn_skip_ad，只能靠 ID 信号命中。",
        expectSkip = true,
    ),
    CHILD_TEXT(
        id = "child_text",
        title = "文字在子节点",
        desc = "「跳过」在不可点击的 TextView 上，点击事件挂在父容器，需要祖先回溯。",
        expectSkip = true,
    ),
    TOUCH_ONLY(
        id = "touch_only",
        title = "仅触摸监听",
        desc = "节点不可点击、祖先也不可点击，只有 setOnTouchListener，只能靠手势兜底。",
        expectSkip = true,
    ),
    ENGLISH(
        id = "english",
        title = "英文 Skip",
        desc = "文案「Skip Ad」，验证英文匹配。",
        expectSkip = true,
    ),
    DELAYED_3S(
        id = "delayed_3s",
        title = "延迟 3 秒出现",
        desc = "广告先加载 3 秒，跳过按钮后才渲染出来，验证延迟重扫。",
        expectSkip = true,
    ),
    TIMER_COUNTDOWN(
        id = "timer_countdown",
        title = "倒计时结束后才可点",
        desc = "前 3 秒显示「3s 后可跳过」且不可点，之后变成可点的「跳过」。",
        expectSkip = true,
    ),
    TRANSPARENT_OVERLAY(
        id = "transparent_overlay",
        title = "透明遮罩上的跳过",
        desc = "跳过按钮叠在全屏广告图上层，层级较深，验证深度遍历。",
        expectSkip = true,
    ),
    DEEP_NESTED(
        id = "deep_nested",
        title = "深层嵌套节点",
        desc = "跳过按钮嵌在 6 层容器里，验证 BFS 不限深度。",
        expectSkip = true,
    ),

    // ===== 应当忽略（防误伤）=====

    GUIDE_SKIP_STEP(
        id = "guide_skip_step",
        title = "引导页「跳过此步骤」",
        desc = "正常引导页里的「跳过此步骤」，点了会跳过用户需要的教程。",
        expectIgnore = true,
    ),
    GUIDE_SKIP_TUTORIAL(
        id = "guide_skip_tutorial",
        title = "「跳过教程」",
        desc = "设置向导中的「跳过教程」链接。",
        expectIgnore = true,
    ),
    LONG_SENTENCE(
        id = "long_sentence",
        title = "长句含「跳过」",
        desc = "说明性文字「点击跳过可直接进入应用首页」，超过长度上限。",
        expectIgnore = true,
    ),
    VIDEO_SEEK(
        id = "video_seek",
        title = "视频「跳过片头」",
        desc = "播放器里的「跳过片头」按钮，属正常功能，不该自动点。",
        expectIgnore = true,
    ),

    // ===== 已知无解（观察项）=====

    CANVAS_DRAWN(
        id = "canvas_drawn",
        title = "Canvas 自绘跳过",
        desc = "用 Canvas 画出「跳过」二字，无障碍树里没有文字、描述和 ID。",
        knownLimitation = "任何基于无障碍的方案都读不到，需图像识别才能解决。",
    ),
    IMAGE_BUTTON(
        id = "image_button",
        title = "图片按钮",
        desc = "跳过按钮是一张图片，没有文字也没有描述。",
        knownLimitation = "无障碍树中无文本信息，无法识别。",
    ),
    LATE_8S(
        id = "late_8s",
        title = "延迟 8 秒出现",
        desc = "跳过按钮 8 秒后才渲染，用于观察监听窗口的边界。",
        knownLimitation = "超出 6 秒监听窗口。",
    ),

    // ===== 新增：更贴近真实广告的形态 =====

    CLOSE_AD_TEXT(
        id = "close_ad_text",
        title = "「关闭广告」按钮",
        desc = "部分广告不写「跳过」，而是「关闭广告」或「关闭」配 X 图标。",
        expectSkip = true,
    ),
    COUNTDOWN_PREFIX(
        id = "countdown_prefix",
        title = "「5s | 跳过」组合文案",
        desc = "倒计时与跳过合并在同一控件里，中间带竖线分隔。",
        expectSkip = true,
    ),
    SKIP_IN_WEBVIEW(
        id = "skip_in_webview",
        title = "WebView 内的跳过",
        desc = "H5 广告页，跳过按钮由网页渲染，节点层级与原生不同。",
        expectSkip = true,
    ),
    FULLSCREEN_CLICKABLE(
        id = "fullscreen_clickable",
        title = "全屏可点区域",
        desc = "整块广告区域都可点击（跳转落地页），跳过按钮浮在角落。",
        expectSkip = true,
    ),
    ;

    companion object {
        fun byId(id: String?): Scenario? = entries.firstOrNull { it.id == id }

        /** 按分类分组，供主界面列表展示 */
        fun grouped(): List<Pair<String, List<Scenario>>> = listOf(
            "应当跳过" to entries.filter { it.expectSkip },
            "应当忽略（防误伤）" to entries.filter { it.expectIgnore },
            "已知无解（观察项）" to entries.filter { it.knownLimitation != null },
        )
    }
}
