// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Andrés Hugo Quintana and contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

/** Popular apps shown as one-tap shortcuts in the parked-app picker. */
object AppsCatalog {
    data class Entry(
        val label: String,
        val packageName: String,
        val protectedVideoLikely: Boolean = false,
    )

    val popular = listOf(
        Entry("YouTube", "com.google.android.youtube", protectedVideoLikely = true),
        Entry("YouTube Music", "com.google.android.apps.youtube.music"),
        Entry("VLC", "org.videolan.vlc"),
        Entry("Spotify", "com.spotify.music"),
        Entry("Plex", "com.plexapp.android", protectedVideoLikely = true),
        Entry("Chrome", "com.android.chrome"),
        Entry("Firefox", "org.mozilla.firefox"),
        Entry("Google Photos", "com.google.android.apps.photos"),
    )

    fun mayUseProtectedVideo(packageName: String): Boolean =
        popular.firstOrNull { it.packageName == packageName }?.protectedVideoLikely == true
}
