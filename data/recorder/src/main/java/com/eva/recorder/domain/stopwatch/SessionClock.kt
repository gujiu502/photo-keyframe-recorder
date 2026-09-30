package com.eva.recorder.domain.stopwatch

/** Monotonic media offset; pauses never advance the timeline. */
class SessionClock(private val now: () -> Long) {
    private var started: Long? = null
    private var pausedAt: Long? = null
    private var pausedMs = 0L

    @Synchronized fun start() { started = now(); pausedAt = null; pausedMs = 0 }
    @Synchronized fun pause() { if (started != null && pausedAt == null) pausedAt = now() }
    @Synchronized fun resume() { pausedAt?.let { pausedMs += now() - it }; pausedAt = null }
    @Synchronized fun positionMs(): Long = started?.let { ((pausedAt ?: now()) - it - pausedMs).coerceAtLeast(0) } ?: 0
}
