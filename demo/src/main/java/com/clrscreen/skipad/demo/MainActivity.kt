package com.clrscreen.skipad.demo

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 靶场首页：列出全部场景，点击任意一项进入对应广告形态。 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            setBackgroundColor(0xFF121212.toInt())
        }

        root.addView(TextView(this).apply {
            text = "ClrScreen 靶场"
            setTextColor(Color.WHITE)
            textSize = 26f
        })

        root.addView(TextView(this).apply {
            text = "逐个进入场景，观察 ClrScreen 是否按预期点击。\n" +
                "查看日志：adb logcat -s ClrScreenDemo:I"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 13f
            setPadding(0, 24, 0, 24)
        })

        for ((groupTitle, scenarios) in Scenario.grouped()) {
            root.addView(TextView(this).apply {
                text = groupTitle
                setTextColor(0xFF64B5F6.toInt())
                textSize = 16f
                setPadding(0, 40, 0, 12)
            })
            for (s in scenarios) {
                root.addView(Button(this).apply {
                    text = s.title
                    isAllCaps = false
                    setOnClickListener { open(s) }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun open(s: Scenario) {
        startActivity(
            Intent(this, AdScenarioActivity::class.java)
                .putExtra(AdScenarioActivity.EXTRA_SCENARIO, s.id)
        )
    }
}
