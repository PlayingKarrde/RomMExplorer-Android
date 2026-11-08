package com.playingkarrde.rommexplorer.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

fun formatBytes(b: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return when {
        i == 0 -> "$b B"
        v >= 100 -> String.format(Locale.US, "%.0f %s", v, units[i])
        else -> String.format(Locale.US, "%.1f %s", v, units[i])
    }
}

fun formatDuration(secs: Long): String {
    val s = secs.coerceAtLeast(0)
    return when {
        s >= 3600 -> String.format(Locale.US, "%dh %02dm", s / 3600, (s % 3600) / 60)
        s >= 60 -> String.format(Locale.US, "%dm %02ds", s / 60, s % 60)
        else -> "${s}s"
    }
}

fun formatDate(unixSeconds: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(unixSeconds * 1000))

fun yearOf(unixSeconds: Long): String =
    SimpleDateFormat("yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(unixSeconds * 1000))
