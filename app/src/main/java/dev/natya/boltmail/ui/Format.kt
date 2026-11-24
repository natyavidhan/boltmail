package dev.natya.boltmail.ui

import android.content.Context
import android.text.format.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Date and size formatting shared by lists, headers and notifications. */
object Fmt {
    enum class Bucket { TODAY, YESTERDAY, WEEK, OLDER }

    private fun startOfDay(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun bucket(ts: Long, now: Long = System.currentTimeMillis()): Bucket {
        val today = startOfDay(now)
        val day = 24 * 60 * 60_000L
        return when {
            ts >= today -> Bucket.TODAY
            ts >= today - day -> Bucket.YESTERDAY
            ts >= today - 6 * day -> Bucket.WEEK
            else -> Bucket.OLDER
        }
    }

    /** Section label for a list grouped by recency. */
    fun groupLabel(ts: Long, now: Long = System.currentTimeMillis()): String = when (bucket(ts, now)) {
        Bucket.TODAY -> "Today"
        Bucket.YESTERDAY -> "Yesterday"
        Bucket.WEEK -> "Last 7 days"
        Bucket.OLDER -> {
            val cal = Calendar.getInstance().apply { timeInMillis = ts }
            val year = cal.get(Calendar.YEAR)
            val thisYear = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
            val month = cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault()) ?: ""
            if (year == thisYear) month else "$month $year"
        }
    }

    fun time(ctx: Context, ts: Long): String = DateFormat.getTimeFormat(ctx).format(Date(ts))

    /** The short time shown on a list row. */
    fun rowTime(ctx: Context, ts: Long, now: Long = System.currentTimeMillis()): String = when (bucket(ts, now)) {
        Bucket.TODAY, Bucket.YESTERDAY -> time(ctx, ts)
        Bucket.WEEK -> pattern("EEE", ts)
        Bucket.OLDER -> pattern("d MMM", ts)
    }

    /** "Tue, 24 Nov 2026, 9:41" */
    fun full(ctx: Context, ts: Long): String = pattern("EEE d MMM yyyy", ts) + ", " + time(ctx, ts)

    /** Attribution line for quoted replies. */
    fun attribution(ctx: Context, ts: Long, who: String): String =
        "On ${pattern("EEE, d MMM yyyy", ts)} at ${time(ctx, ts)}, $who wrote:"

    private fun pattern(skeleton: String, ts: Long): String {
        val p = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
        return java.text.SimpleDateFormat(p, Locale.getDefault()).format(Date(ts))
    }

    fun size(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
        else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
