package io.github.yosefchai.mealie.net

/**
 * מדיניות אחת לתעבורה לא מוצפנת ולאישור תעודה בחתימה עצמית:
 * מותר רק לכתובות פרטיות. שום בדיקה כאן לא פונה ל-DNS.
 */
object HostPolicy {

    private val privateSuffixes = listOf(".local", ".lan", ".home.arpa", ".internal", ".localdomain", ".ts.net")

    fun isPrivateHost(rawHost: String): Boolean {
        val host = rawHost.trim().trim('[', ']').lowercase().trimEnd('.')
        if (host.isEmpty()) return false
        if (host == "localhost") return true
        parseIpv4(host)?.let { return isPrivateIpv4(it) }
        if (':' in host) return isPrivateIpv6(host)
        // שם בלי נקודה (למשל "mealie") נפתר רק ב-DNS המקומי.
        if ('.' !in host) return true
        return privateSuffixes.any { host.endsWith(it) }
    }

    fun isCleartextAllowed(host: String): Boolean = isPrivateHost(host)

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0..3) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit)) return null
            out[i] = p.toInt().takeIf { it in 0..255 } ?: return null
        }
        return out
    }

    private fun isPrivateIpv4(ip: IntArray): Boolean {
        val (a, b) = ip[0] to ip[1]
        return a == 10 ||
            a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            // CGNAT, כולל Tailscale.
            (a == 100 && b in 64..127)
    }

    private fun isPrivateIpv6(host: String): Boolean {
        val h = host.substringBefore('%')
        if (h == "::1") return true
        val first = h.substringBefore(':').ifEmpty { "0" }
        val word = first.toIntOrNull(16) ?: return false
        return (word and 0xFE00) == 0xFC00 || // fc00::/7 (ULA)
            (word and 0xFFC0) == 0xFE80 // fe80::/10 (link-local)
    }
}
