package app.boltmail.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small app-wide settings, exposed as flows so the UI follows changes. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("boltmail", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(sp.getInt(THEME, THEME_SYSTEM))
    val theme: StateFlow<Int> = _theme

    private val _loadImages = MutableStateFlow(sp.getBoolean(IMAGES, false))
    val loadImages: StateFlow<Boolean> = _loadImages

    private val _pushHintSnoozed = MutableStateFlow(sp.getLong(PUSH_SNOOZE, 0L))
    val pushHintSnoozedUntil: StateFlow<Long> = _pushHintSnoozed

    fun setTheme(v: Int) {
        sp.edit().putInt(THEME, v).apply(); _theme.value = v
    }

    fun setLoadImages(v: Boolean) {
        sp.edit().putBoolean(IMAGES, v).apply(); _loadImages.value = v
    }

    fun snoozePushHint(until: Long) {
        sp.edit().putLong(PUSH_SNOOZE, until).apply(); _pushHintSnoozed.value = until
    }

    var askedNotifications: Boolean
        get() = sp.getBoolean(ASKED_NOTIF, false)
        set(v) = sp.edit().putBoolean(ASKED_NOTIF, v).apply()

    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        private const val THEME = "theme"
        private const val IMAGES = "load_images"
        private const val PUSH_SNOOZE = "push_hint_snooze"
        private const val ASKED_NOTIF = "asked_notifications"
    }
}
