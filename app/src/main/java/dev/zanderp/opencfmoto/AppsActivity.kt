// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Andrés Hugo Quintana and contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

/** Parked-only launcher for apps that are not available in the standard Android Auto launcher. */
class AppsActivity : AppCompatActivity() {

    private val chooseAnyApp = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val picked = result.data
        val component = picked?.component ?: selectedIntent(picked)?.component
        if (component == null) {
            Toast.makeText(this, uiText("The selected app did not provide a launch activity"), Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        returnSelection(component, appLabel(component.packageName))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_apps)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.apps_root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val installedContainer = findViewById<LinearLayout>(R.id.apps_installed)
        val missing = ArrayList<String>()
        for (entry in AppsCatalog.popular) {
            val launch = packageManager.getLaunchIntentForPackage(entry.packageName)
            if (launch?.component == null) {
                missing += entry.label
                continue
            }
            installedContainer.addView(appButton(entry.label, launch.component!!))
        }
        if (installedContainer.childCount == 0) {
            findViewById<TextView>(R.id.apps_none).visibility = View.VISIBLE
        }
        findViewById<TextView>(R.id.apps_missing).text =
            if (missing.isEmpty()) "" else uiText("Not installed: ${missing.joinToString(" · ")}")

        findViewById<MaterialButton>(R.id.btn_choose_any_app).setOnClickListener {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val picker = Intent(Intent.ACTION_PICK_ACTIVITY)
                .putExtra(Intent.EXTRA_INTENT, launcherIntent)
                .putExtra(Intent.EXTRA_TITLE, uiText("Choose an app to show on the bike"))
            try {
                chooseAnyApp.launch(picker)
            } catch (e: Exception) {
                LogBus.log("app picker failed: $e")
                Toast.makeText(this, uiText("No compatible app picker is available"), Toast.LENGTH_LONG).show()
            }
        }
        findViewById<MaterialButton>(R.id.btn_apps_cancel).setOnClickListener { finish() }
    }

    private fun appButton(label: String, component: ComponentName): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            icon = runCatching {
                packageManager.getApplicationInfo(component.packageName, 0).loadIcon(packageManager)
            }.getOrNull()
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            setOnClickListener { returnSelection(component, label) }
        }

    private fun returnSelection(component: ComponentName, label: String) {
        if (component.packageName == packageName) {
            Toast.makeText(this, uiText("Choose an app other than OpenCfMoto"), Toast.LENGTH_SHORT).show()
            return
        }
        val info = runCatching { packageManager.getActivityInfo(component, 0) }.getOrNull()
        if (info == null || !info.enabled) {
            Toast.makeText(this, uiText("$label is no longer available"), Toast.LENGTH_LONG).show()
            return
        }
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_PACKAGE, component.packageName)
                .putExtra(EXTRA_CLASS, component.className)
                .putExtra(EXTRA_LABEL, label),
        )
        finish()
    }

    private fun appLabel(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    @Suppress("DEPRECATION")
    private fun selectedIntent(data: Intent?): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            data?.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            data?.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PACKAGE = "app_package"
        const val EXTRA_CLASS = "app_class"
        const val EXTRA_LABEL = "app_label"
    }
}
