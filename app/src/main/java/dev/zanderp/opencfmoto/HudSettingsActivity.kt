// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

/**
 * Dedicated settings screen for the on-dash rider HUD ([HudModel] / [AaCompositor]): master on/off,
 * km/h·mph, and a per-element show/hide list. Every change is saved (per bike, via [VideoPrefs]) and
 * applied live through [HudBus], so toggling reflects on the dash without a reconnect.
 */
class HudSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hud_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.hud_settings_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, b.top, v.paddingRight, b.bottom)
            insets
        }

        findViewById<MaterialButton>(R.id.hud_master_on).setOnClickListener { setEnabled(true) }
        findViewById<MaterialButton>(R.id.hud_master_off).setOnClickListener { setEnabled(false) }
        findViewById<MaterialButton>(R.id.unit_kmh).setOnClickListener { setUnit(SpeedUnit.KMH) }
        findViewById<MaterialButton>(R.id.unit_mph).setOnClickListener { setUnit(SpeedUnit.MPH) }

        buildElementRows()
        refresh()
    }

    private fun setEnabled(on: Boolean) {
        VideoPrefs.setHudEnabled(this, on)
        HudBus.enabled = on
        refresh()
    }

    private fun setUnit(unit: SpeedUnit) {
        VideoPrefs.setSpeedUnit(this, unit)
        HudBus.unit = unit
        refresh()
    }

    /** One switch row per [HudElement], reflecting and updating the saved set + [HudBus] live. */
    private fun buildElementRows() {
        val container = findViewById<LinearLayout>(R.id.hud_elements_container)
        container.removeAllViews()
        val enabled = VideoPrefs.hudElements(this)
        for (el in HudElement.values()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
            }
            val label = TextView(this).apply {
                text = el.label
                setTextColor(ContextCompat.getColor(this@HudSettingsActivity, R.color.text_primary))
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            val sw = SwitchCompat(this).apply {
                isChecked = el in enabled
                setOnCheckedChangeListener { _, checked ->
                    VideoPrefs.setHudElement(this@HudSettingsActivity, el, checked)
                    HudBus.elements = VideoPrefs.hudElements(this@HudSettingsActivity)
                }
            }
            row.addView(label)
            row.addView(sw)
            container.addView(row)
        }
    }

    private fun refresh() {
        highlight(VideoPrefs.hudEnabled(this),
            R.id.hud_master_on to true,
            R.id.hud_master_off to false)
        highlight(VideoPrefs.speedUnit(this),
            R.id.unit_kmh to SpeedUnit.KMH,
            R.id.unit_mph to SpeedUnit.MPH)
    }

    /** Paint the segment matching [selected] in brand color; the rest stay neutral (mirrors Setup). */
    private fun <T> highlight(selected: T, vararg pairs: Pair<Int, T>) {
        val onColor = ContextCompat.getColor(this, R.color.brand_orange)
        val onText = ContextCompat.getColor(this, R.color.on_brand)
        val offColor = ContextCompat.getColor(this, R.color.surface_high)
        val offText = ContextCompat.getColor(this, R.color.text_primary)
        for ((id, value) in pairs) {
            val btn = findViewById<MaterialButton>(id)
            val on = value == selected
            btn.backgroundTintList = ColorStateList.valueOf(if (on) onColor else offColor)
            btn.setTextColor(if (on) onText else offText)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, HudSettingsActivity::class.java))
    }
}
