package com.alysson.offlineai

import android.os.Handler
import android.os.Looper
import android.widget.TextView

/**
 * Coalesces token-level updates into one UI mutation per frame-ish interval.
 * Native inference can emit many tiny chunks per second; appending each chunk directly to a TextView
 * creates excessive layout passes and can make the UI look frozen even when inference is healthy.
 */
class StreamingUiBatcher(
    private val target: TextView,
    private val intervalMs: Long = 32L,
    private val afterFlush: () -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val pending = StringBuilder()
    private var scheduled = false

    fun append(text: String) {
        if (text.isEmpty()) return
        synchronized(lock) {
            pending.append(text)
            if (scheduled) return
            scheduled = true
        }
        handler.postDelayed(flushRunnable, intervalMs)
    }

    fun flushNow() {
        if (Looper.myLooper() == Looper.getMainLooper()) flush() else handler.post { flush() }
    }

    fun cancel() {
        handler.removeCallbacks(flushRunnable)
        synchronized(lock) {
            pending.setLength(0)
            scheduled = false
        }
    }

    private val flushRunnable = Runnable { flush() }

    private fun flush() {
        val chunk = synchronized(lock) {
            scheduled = false
            if (pending.isEmpty()) return
            pending.toString().also { pending.setLength(0) }
        }
        target.append(chunk)
        afterFlush()
    }
}
