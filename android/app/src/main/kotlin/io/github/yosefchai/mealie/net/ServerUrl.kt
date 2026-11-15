package io.github.yosefchai.mealie.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * כתובת בסיס של שרת Mealie, כולל תת-נתיב (SUB_PATH). כל הנתיבים באפליקציה נבנים מכאן.
 * דוגמה: https://home.example.com/mealie → origin=https://home.example.com, basePath=/mealie
 */
@JvmInline
value class ServerUrl private constructor(val value: String) {

    val url: HttpUrl get() = value.toHttpUrlOrNull()!!
    val host: String get() = url.host
    val port: Int get() = url.port
    val isHttps: Boolean get() = url.isHttps
    val hostPort: String get() = "$host:$port"

    val origin: String
        get() = url.let { u ->
            val default = (u.isHttps && u.port == 443) || (!u.isHttps && u.port == 80)
            "${u.scheme}://${hostLiteral(u.host)}" + if (default) "" else ":${u.port}"
        }

    /** "" לשרת בשורש, או "/mealie" לשרת בתת-נתיב. */
    val basePath: String get() = url.encodedPath.trimEnd('/')

    /** נתיב יחסי לאפליקציה ("/shopping-lists") → כתובת מלאה. */
    fun resolve(path: String): String {
        val p = if (path.startsWith('/')) path else "/$path"
        return origin + basePath + p
    }

    /** כתובת מלאה → נתיב יחסי לבסיס, או null אם היא מחוץ לשרת הזה. */
    fun relativePath(full: String): String? {
        val u = full.toHttpUrlOrNull() ?: return null
        if (u.scheme != url.scheme || u.host != url.host || u.port != url.port) return null
        val path = u.encodedPath
        val base = basePath
        if (base.isNotEmpty() && path != base && !path.startsWith("$base/")) return null
        val rel = path.removePrefix(base).ifEmpty { "/" }
        return rel + (u.encodedQuery?.let { "?$it" } ?: "")
    }

    fun sameOrigin(full: String): Boolean = full.toHttpUrlOrNull()?.let {
        it.scheme == url.scheme && it.host == url.host && it.port == url.port
    } ?: false

    override fun toString(): String = value

    companion object {
        /**
         * מנרמל קלט של משתמש. בלי scheme: http לכתובת פרטית, https לכל השאר.
         * מחזיר null לקלט שאינו כתובת תקינה.
         */
        fun parse(input: String): ServerUrl? {
            var s = input.trim()
            if (s.isEmpty()) return null
            if (!s.contains("://")) {
                val host = s.substringBefore('/').substringBefore(':').trim('[', ']')
                s = (if (HostPolicy.isPrivateHost(host)) "http://" else "https://") + s
            }
            val u = s.toHttpUrlOrNull() ?: return null
            val clean = u.newBuilder().query(null).fragment(null).username("").password("").build()
            val path = clean.encodedPath.trimEnd('/')
            val normalized = clean.newBuilder().encodedPath(if (path.isEmpty()) "/" else "$path/").build()
            return ServerUrl(normalized.toString().trimEnd('/'))
        }

        private fun hostLiteral(host: String) = if (':' in host) "[$host]" else host
    }
}
