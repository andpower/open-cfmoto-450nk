// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
// Handlebar gesture → action mapping, ported from the ionutradu252/open-cfmoto fork.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Something a handlebar gesture can be made to do. Everything here is reachable with a live Android
 * Auto session and no touchscreen.
 */
enum class ButtonAction(val id: String, val label: String) {
    NONE("none", "Do nothing"),
    KNOB_FORWARD("knobFwd", "Knob forward (next item)"),
    KNOB_BACK("knobBack", "Knob back (previous item)"),
    SELECT("select", "Select / OK"),
    BACK("back", "Back"),
    HOME("home", "Home (app list)"),
    ASSISTANT("assistant", "Assistant (voice)"),
    DPAD_UP("up", "D-pad up"),
    DPAD_DOWN("down", "D-pad down"),
    DPAD_LEFT("left", "D-pad left"),
    DPAD_RIGHT("right", "D-pad right"),
    NAV_1("nav1", "Navigate to saved place 1"),
    NAV_2("nav2", "Navigate to saved place 2"),
    NAV_3("nav3", "Navigate to saved place 3");

    /** Nav actions read better as the place's own name once one is saved. */
    fun displayLabel(context: Context): String = when (this) {
        NAV_1 -> SavedPlaces.actionLabel(context, 0)
        NAV_2 -> SavedPlaces.actionLabel(context, 1)
        NAV_3 -> SavedPlaces.actionLabel(context, 2)
        else -> label
    }

    companion object {
        fun byId(id: String?): ButtonAction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The **semantic** gestures a CFMoto handlebar can produce, regardless of which physical buttons a
 * given bike uses. Different dashes send navigation on different buttons, but they all collapse to a
 * "previous / next / select" trio, so we map by meaning and let [MediaButtonBridge] route whatever
 * the bike sends into the matching gesture — no per-bike layout config needed:
 *   • **Backward / Forward** — the CFDL16 dashes (450SR etc.) send these as ▲/▼ *volume* writes;
 *     the 800MT's 5-way sends them as ◀/▶ *previous-/next-track*. Either way ◀/▲ = backward and
 *     ▶/▼ = forward.
 *   • **Backward/Forward ×2** — a double-tap. Only the volume dashes can signal it (one coalesced
 *     volume write with a bigger jump); the 800MT's discrete track keys just repeat the single step,
 *     so these rows are effectively non-touch-dash only.
 *   • **Select** — the OK / ★ (start) button, which every dash sends as an AVRCP play/pause. A quick
 *     tap is [SELECT_PRESS]; holding it past [MediaButtonBridge]'s long-press threshold is
 *     [SELECT_LONG]. The two are told apart by the gap between the button's key-down and key-up on the
 *     media-button path, so long-press only works on dashes that send a distinct release (the ▲/▼
 *     volume path has no release event, so it has no long-press).
 *
 * [label] is the semantic name (with the physical buttons that trigger it); [hint] explains it.
 */
enum class ButtonGesture(
    val id: String,
    val label: String,
    val hint: String,
    val default: ButtonAction,
) {
    NAV_BACK("navBack", "Backward  ◀ / ▲", "◀ left, or the ▲ volume press on non-touch dashes", ButtonAction.KNOB_BACK),
    NAV_FWD("navFwd", "Forward  ▶ / ▼", "▶ right, or the ▼ volume press on non-touch dashes", ButtonAction.KNOB_FORWARD),
    SELECT_PRESS("selectPress", "Select  Enter / ★", "a quick tap of the OK / ★ start button (dash sends play-pause)", ButtonAction.SELECT),
    SELECT_LONG("selectLong", "Select (hold)  Enter / ★", "press and hold the OK / ★ button", ButtonAction.ASSISTANT),
    NAV_BACK_DOUBLE("navBackDouble", "Backward ×2", "double-tap backward — non-touch dashes only", ButtonAction.HOME),
    NAV_FWD_DOUBLE("navFwdDouble", "Forward ×2", "double-tap forward — non-touch dashes only", ButtonAction.BACK),
}

/**
 * What each handlebar gesture does. Unset gestures fall back to [ButtonGesture.default], so
 * "reset to defaults" is just clearing the store.
 */
object ButtonMap {
    private const val PREF = "button_map"

    fun get(context: Context, gesture: ButtonGesture): ButtonAction =
        ButtonAction.byId(prefs(context).getString(gesture.id, null)) ?: gesture.default

    fun set(context: Context, gesture: ButtonGesture, action: ButtonAction) {
        prefs(context).edit().putString(gesture.id, action.id).apply()
    }

    fun resetAll(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** True when every gesture is still on its default — used to enable/disable the reset button. */
    fun isAllDefault(context: Context): Boolean =
        ButtonGesture.entries.all { get(context, it) == it.default }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
