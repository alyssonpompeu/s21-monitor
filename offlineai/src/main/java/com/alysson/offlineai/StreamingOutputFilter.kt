package com.alysson.offlineai

/**
 * Incremental filter for local-model reasoning tags. It deliberately withholds only a tiny suffix
 * that could be the beginning of a <think> tag, so normal answer text still appears token-by-token.
 */
class StreamingOutputFilter {
    private val pending = StringBuilder()
    private var insideThink = false

    fun push(chunk: String): String {
        if (chunk.isEmpty()) return ""
        pending.append(chunk)
        val visible = StringBuilder()

        while (pending.isNotEmpty()) {
            if (insideThink) {
                val close = pending.indexOf(CLOSE_TAG)
                if (close >= 0) {
                    pending.delete(0, close + CLOSE_TAG.length)
                    insideThink = false
                    continue
                }
                val keep = suffixPrefixLength(pending, CLOSE_TAG)
                if (pending.length > keep) pending.delete(0, pending.length - keep)
                break
            }

            val open = pending.indexOf(OPEN_TAG)
            if (open >= 0) {
                if (open > 0) visible.append(pending.substring(0, open))
                pending.delete(0, open + OPEN_TAG.length)
                insideThink = true
                continue
            }

            val strayClose = pending.indexOf(CLOSE_TAG)
            if (strayClose >= 0) {
                if (strayClose > 0) visible.append(pending.substring(0, strayClose))
                pending.delete(0, strayClose + CLOSE_TAG.length)
                continue
            }

            val keep = suffixPrefixLength(pending, OPEN_TAG)
            val emit = pending.length - keep
            if (emit > 0) {
                visible.append(pending.substring(0, emit))
                pending.delete(0, emit)
            }
            break
        }
        return visible.toString()
    }

    fun finish(): String {
        if (insideThink) {
            pending.clear()
            return ""
        }
        val value = pending.toString()
            .replace(OPEN_TAG, "")
            .replace(CLOSE_TAG, "")
        pending.clear()
        return value
    }

    private fun suffixPrefixLength(buffer: CharSequence, tag: String): Int {
        val max = minOf(buffer.length, tag.length - 1)
        for (length in max downTo 1) {
            var matches = true
            for (i in 0 until length) {
                if (buffer[buffer.length - length + i] != tag[i]) {
                    matches = false
                    break
                }
            }
            if (matches) return length
        }
        return 0
    }

    companion object {
        private const val OPEN_TAG = "<think>"
        private const val CLOSE_TAG = "</think>"
    }
}
