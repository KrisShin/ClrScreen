package com.clrscreen.skipad.demo

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 单个广告场景的展示页。
 *
 * 通过 Intent extra [EXTRA_SCENARIO] 指定 [Scenario.id]。
 * 每个场景都会在 [TIMEOUT_MS] 后自动结束（记 TIMEOUT），
 * 期间若按钮被点击则立即记 CLICKED / FALSE_POSITIVE。
 */
class AdScenarioActivity : Activity() {

    companion object {
        const val EXTRA_SCENARIO = "scenario"
        private const val TIMEOUT_MS = 12_000L
    }

    private lateinit var scenario: Scenario
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private lateinit var statusLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scenario = Scenario.byId(intent.getStringExtra(EXTRA_SCENARIO)) ?: Scenario.TEXT_PLAIN

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF1A2233.toInt()) }
        statusLabel = TextView(this).apply {
            text = "${scenario.title}\n${scenario.desc}"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(48, 120, 48, 0)
        }
        root.addView(statusLabel)

        // 场景视图自带 layoutParams（例如右下角定位），这里直接 addView，
        // 不再传参覆盖，否则按钮会被撑满全屏
        root.addView(buildScenario())

        setContentView(root)
        ResultLog.shown(scenario)

        // 超时：没人点它，场景自然结束
        handler.postDelayed({
            finishWith("TIMEOUT")
        }, TIMEOUT_MS)
    }

    // ---------------------------------------------------------------- 场景构建

    private fun buildScenario(): View = when (scenario) {
        Scenario.TEXT_PLAIN -> countdownButton(text = "跳过 5")
        Scenario.TEXT_CORNER -> cornerSkip()
        Scenario.DESC_ONLY -> Button(this).apply {
            text = "关闭"
            contentDescription = "跳过"
            layoutParams = bottomEnd()
            setOnClickListener { onClicked() }
        }
        Scenario.VIEW_ID_SKIP -> Button(this).apply {
            id = R.id.btn_skip_ad
            text = "关闭"
            layoutParams = bottomEnd()
            setOnClickListener { onClicked() }
        }
        Scenario.CHILD_TEXT -> LinearLayout(this).apply {
            layoutParams = bottomEnd()
            gravity = Gravity.CENTER
            setOnClickListener { onClicked() }
            addView(TextView(this@AdScenarioActivity).apply {
                text = "跳过"
                textSize = 18f
                setTextColor(Color.WHITE)
            })
        }
        Scenario.TOUCH_ONLY -> TextView(this).apply {
            text = "跳过"
            textSize = 18f
            setTextColor(Color.WHITE)
            layoutParams = bottomEnd()
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_UP) onClicked()
                true
            }
        }
        Scenario.ENGLISH -> Button(this).apply {
            text = "Skip Ad"
            layoutParams = bottomEnd()
            setOnClickListener { onClicked() }
        }
        Scenario.DELAYED_3S -> FrameLayout(this).apply {
            // 注意：apply 块里直接写 handler 会解析到 View.getHandler()（此时为 null），
            // 必须显式限定到 Activity 的 handler
            this@AdScenarioActivity.handler.postDelayed({
                if (isFinishing) return@postDelayed
                addView(Button(this@AdScenarioActivity).apply {
                    text = "跳过"
                    setOnClickListener { onClicked() }
                }, bottomEnd())
            }, 3000)
        }
        Scenario.TIMER_COUNTDOWN -> timerCountdown()
        Scenario.TRANSPARENT_OVERLAY -> transparentOverlay()
        Scenario.DEEP_NESTED -> deepNested()
        Scenario.GUIDE_SKIP_STEP -> ignoreText("跳过此步骤")
        Scenario.GUIDE_SKIP_TUTORIAL -> ignoreText("跳过教程")
        Scenario.LONG_SENTENCE -> ignoreText("点击跳过可直接进入应用首页", size = 15f)
        Scenario.VIDEO_SEEK -> ignoreText("跳过片头")
        Scenario.CANVAS_DRAWN -> canvasDrawn()
        Scenario.IMAGE_BUTTON -> imageButton()
        Scenario.LATE_8S -> FrameLayout(this).apply {
            this@AdScenarioActivity.handler.postDelayed({
                if (isFinishing) return@postDelayed
                addView(Button(this@AdScenarioActivity).apply {
                    text = "跳过"
                    setOnClickListener { onClicked() }
                }, bottomEnd())
            }, 8000)
        }
        Scenario.CLOSE_AD_TEXT -> ignoreTextOrSkip("关闭")
        Scenario.COUNTDOWN_PREFIX -> countdownButton(text = "5s | 跳过")
        Scenario.SKIP_IN_WEBVIEW -> webViewSkip()
        Scenario.FULLSCREEN_CLICKABLE -> fullscreenClickable()
    }

    /** 底部右侧的通用布局参数 */
    private fun bottomEnd() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.END
        setMargins(0, 0, 48, 120)
    }

    /** 带倒计时的标准跳过按钮 */
    private fun countdownButton(text: String): View {
        var remain = 5
        val btn = Button(this).apply {
            this.text = text
            layoutParams = bottomEnd()
            setOnClickListener { onClicked() }
        }
        val tick = object : Runnable {
            override fun run() {
                remain--
                if (remain <= 0) return
                btn.text = text.replace(Regex("\\d+"), remain.toString())
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(tick, 1000)
        return btn
    }

    /** 右上角小字、无背景的跳过 */
    private fun cornerSkip(): View = TextView(this).apply {
        text = "跳过"
        textSize = 13f
        setTextColor(0xFFCCCCCC.toInt())
        setPadding(24, 12, 24, 12)
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, 100, 32, 0)
        }
        setOnClickListener { onClicked() }
    }

    /** 前 3 秒不可点，之后变为可点的「跳过」 */
    private fun timerCountdown(): View {
        val btn = Button(this).apply {
            text = "3s 后可跳过"
            isEnabled = false
            layoutParams = bottomEnd()
            setOnClickListener { onClicked() }
        }
        var remain = 3
        val tick = object : Runnable {
            override fun run() {
                remain--
                if (remain > 0) {
                    btn.text = "${remain}s 后可跳过"
                    handler.postDelayed(this, 1000)
                } else {
                    btn.text = "跳过"
                    btn.isEnabled = true
                }
            }
        }
        handler.postDelayed(tick, 1000)
        return btn
    }

    /** 全屏广告图 + 上层透明遮罩里的跳过 */
    private fun transparentOverlay(): View = FrameLayout(this).apply {
        addView(View(this@AdScenarioActivity).apply {
            setBackgroundColor(0xFF2E4A6B.toInt())
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        addView(FrameLayout(this@AdScenarioActivity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(Button(this@AdScenarioActivity).apply {
                text = "跳过 3"
                setOnClickListener { onClicked() }
            }, bottomEnd())
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    /** 6 层嵌套容器里的跳过按钮（返回最外层容器） */
    private fun deepNested(): View {
        val outermost = FrameLayout(this)
        var container: ViewGroup = outermost
        repeat(5) {
            val next = FrameLayout(this)
            container.addView(next, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            container = next
        }
        container.addView(Button(this).apply {
            text = "跳过"
            setOnClickListener { onClicked() }
        }, bottomEnd())
        // 必须返回最外层，内层已经挂在父节点上了
        return outermost
    }

    /** 应当被忽略的引导类文案 */
    private fun ignoreText(text: String, size: Float = 18f): View = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.WHITE)
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 200
        }
        setOnClickListener { onFalsePositive() }
    }

    /** 「关闭」这类变体：既可能是跳过控件，也可能是关闭弹窗，观察其行为 */
    private fun ignoreTextOrSkip(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(Color.WHITE)
        layoutParams = bottomEnd()
        setOnClickListener { onClicked() }
    }

    /** Canvas 自绘「跳过」 */
    private fun canvasDrawn(): View = object : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 48f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawText("跳过", 60f, 160f, paint)
        }
    }.apply {
        layoutParams = bottomEnd()
        setOnClickListener { onClicked() }
    }

    /** 纯图片按钮，无文字无描述 */
    private fun imageButton(): View = ImageView(this).apply {
        setImageDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF444444.toInt())
            cornerRadius = 12f
        })
        layoutParams = FrameLayout.LayoutParams(240, 100).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, 48, 120)
        }
        setOnClickListener { onClicked() }
    }

    /** WebView 内渲染的 H5 跳过控件 */
    private fun webViewSkip(): View = WebView(this).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(0xFF203040.toInt())
        settings.javaScriptEnabled = true

        // 用 URL 跳转判断点击是否真的生效：
        // 无障碍的 performAction(ACTION_CLICK) 打到 WebView 节点上时，
        // 并不会执行 HTML 的 onclick / JS 桥，但会走链接跳转，因此用 shouldOverrideUrlLoading 捕获
        webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: android.webkit.WebView?,
                request: android.webkit.WebResourceRequest?,
            ): Boolean {
                if (request?.url?.toString()?.contains("skip-clicked") == true) {
                    runOnUiThread { onClicked() }
                    return true
                }
                return false
            }
        }

        loadDataWithBaseURL(
            null,
            """
            <html><body style="margin:0;background:#203040;color:#fff;font-family:sans-serif">
              <div style="padding:120px 40px">
                <h2>H5 开屏广告</h2>
                <p>跳过按钮由网页渲染</p>
              </div>
              <a href="https://example.com/skip-clicked" id="skip"
                 style="position:fixed;right:40px;bottom:120px;padding:16px 32px;
                        background:rgba(255,255,255,0.15);color:#fff;text-decoration:none;
                        border-radius:8px;display:block">跳过 5</a>
            </body></html>
            """.trimIndent(),
            "text/html", "UTF-8", null
        )
    }

    /** 整块广告区域可点击，跳过按钮浮在角落 */
    private fun fullscreenClickable(): View = FrameLayout(this).apply {
        setOnClickListener { onFalsePositive() }  // 点广告本体 = 跳落地页，算误伤
        addView(Button(this@AdScenarioActivity).apply {
            text = "跳过"
            setOnClickListener { onClicked() }
        }, bottomEnd())
    }

    // ---------------------------------------------------------------- 结果

    private fun onClicked() {
        if (scenario.expectIgnore) onFalsePositive() else finishWith("CLICKED")
    }

    private fun onFalsePositive() {
        if (finished) return
        ResultLog.falsePositive(scenario)
        statusLabel.text = "误伤：${scenario.title}"
        handler.postDelayed({ finishWith("FALSE_POSITIVE") }, 300)
    }

    private fun finishWith(reason: String) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        if (reason != "FALSE_POSITIVE") {
            ResultLog.skipped(scenario, reason)
        }
        statusLabel.text = "${scenario.title}\n结果：$reason"
        // 停留一小会儿再关，方便观察
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 400)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
