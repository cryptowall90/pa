package com.pictureperfectx.app.data

import android.content.Context

/**
 * Small persistent UI preferences — the kind of "don't show me this again" flags that shouldn't
 * live in the photo index. Room stays reserved for the gallery.
 */
class UserPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("picture_perfect_prefs", Context.MODE_PRIVATE)

    /** Set once the user dismisses the "looks don't apply to RAW" notice for good. */
    var rawFilterNoticeDismissed: Boolean
        get() = prefs.getBoolean(KEY_RAW_FILTER_NOTICE, false)
        set(value) = prefs.edit().putBoolean(KEY_RAW_FILTER_NOTICE, value).apply()

    private companion object {
        const val KEY_RAW_FILTER_NOTICE = "raw_filter_notice_dismissed"
    }
}
