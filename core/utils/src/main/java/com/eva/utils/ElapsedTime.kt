package com.eva.utils

import java.util.Locale
import kotlin.time.Duration

fun formatElapsed(duration: Duration): String {
    val seconds = if (duration.isFinite()) duration.inWholeSeconds.coerceAtLeast(0L) else 0L
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
