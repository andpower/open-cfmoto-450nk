// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Andrés Hugo Quintana and contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Temporarily releases exclusive Android Auto button capture while an external app is mirrored.
 * This lets normal Bluetooth AVRCP events reach media apps (play/pause/previous/next). It does not
 * pretend that a non-touch 450NK can drive arbitrary touch-only application interfaces.
 */
object AppMirrorSession {
    private const val PREF = "app_mirror_session"
    private const val KEY_RESTORE_AA = "restore_aa_buttons"

    fun useMediaButtons(context: Context): Boolean {
        val wasControlAa = ButtonMode.isControlAa(context)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RESTORE_AA, wasControlAa)
            .apply()
        if (wasControlAa) {
            ButtonMode.set(context, false)
            MediaButtonBridge.instance?.setCaptureActive(false)
        }
        return wasControlAa
    }

    fun restoreButtons(context: Context) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_RESTORE_AA, false)) {
            ButtonMode.set(context, true)
            MediaButtonBridge.instance?.setCaptureActive(true)
        }
        prefs.edit().remove(KEY_RESTORE_AA).apply()
    }
}
