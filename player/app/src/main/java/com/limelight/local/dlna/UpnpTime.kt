package com.limelight.local.dlna

import java.util.Locale

/**
 * AVTransport's clock values ("H+:MM:SS[.F+]"). Written with two digit hours,
 * which every sender reads; read leniently, since senders write whatever
 * their own player printed: "1:02:03", "01:02:03.500", "62:03", "3723",
 * "0:00:05.1/2".
 */
object UpnpTime {
    fun format(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    /** Milliseconds, or null when it is not a time at all ("NOT_IMPLEMENTED", empty). */
    fun parse(text: String?): Long? {
        val t = text?.trim()?.removePrefix("+") ?: return null
        if (t.isEmpty() || t.startsWith("-")) {
            return null
        }
        val parts = t.split(':')
        if (parts.size > 3) {
            return null
        }
        var total = 0.0
        for ((i, part) in parts.withIndex()) {
            val last = i == parts.size - 1
            val v = if (last) seconds(part) else part.toLongOrNull()?.toDouble()
            if (v == null || v < 0 || !v.isFinite()) {
                return null
            }
            // Only the field before the seconds is bounded; hours run on
            if (!last && i > 0 && v >= 60) {
                return null
            }
            total = total * 60 + v
        }
        return Math.round(total * 1000)
    }

    // "05", "05.5", "05.1/2" (the spec's F0/F1 fraction)
    private fun seconds(part: String): Double? {
        val dot = part.indexOf('.')
        if (dot < 0) {
            return part.toDoubleOrNull()
        }
        val whole = part.substring(0, dot).ifEmpty { "0" }.toLongOrNull() ?: return null
        val frac = part.substring(dot + 1)
        val slash = frac.indexOf('/')
        if (slash >= 0) {
            val num = frac.substring(0, slash).toLongOrNull() ?: return null
            val den = frac.substring(slash + 1).toLongOrNull() ?: return null
            return if (den <= 0 || num >= den) null else whole + num.toDouble() / den
        }
        if (frac.isEmpty() || !frac.all { it.isDigit() }) {
            return null
        }
        return ("$whole.$frac").toDouble()
    }
}
