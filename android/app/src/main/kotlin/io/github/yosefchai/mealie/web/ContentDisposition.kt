package io.github.yosefchai.mealie.web

import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import java.net.URLDecoder

/** שם קובץ מ-Content-Disposition (RFC 6266), כולל filename*=UTF-8''… (RFC 5987). */
object ContentDisposition {

    private val extended = Regex("""filename\*\s*=\s*([\w-]+)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
    private val quoted = Regex("""filename\s*=\s*"((?:\\.|[^"\\])*)"""", RegexOption.IGNORE_CASE)
    private val token = Regex("""filename\s*=\s*([^;\s]+)""", RegexOption.IGNORE_CASE)

    fun filename(header: String?): String? {
        if (header.isNullOrBlank()) return null
        extended.find(header)?.let { m ->
            val charset = m.groupValues[1].ifBlank { "UTF-8" }
            runCatching { URLDecoder.decode(m.groupValues[2].trim().replace("+", "%2B"), charset) }
                .getOrNull()?.let { return sanitize(it) }
        }
        quoted.find(header)?.let { return sanitize(it.groupValues[1].replace(Regex("""\\(.)"""), "$1")) }
        token.find(header)?.let { return sanitize(it.groupValues[1]) }
        return null
    }

    /** שם סופי: מהכותרת, אחרת לפי ה-URL וה-MIME, ומוודא סיומת. */
    fun resolve(url: String, header: String?, mime: String?, suggested: String? = null): String {
        val base = suggested?.takeIf { it.isNotBlank() }?.let(::sanitize)
            ?: filename(header)
            ?: sanitize(URLUtil.guessFileName(url, header, mime))
        if (base.contains('.') || mime.isNullOrBlank()) return base
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime.substringBefore(';').trim())
        return if (ext != null) "$base.$ext" else base
    }

    private fun sanitize(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[\u0000-\u001f<>:"|?*]"""), "_")
            .trim().trimStart('.')
            .ifBlank { "download" }
            .take(200)
}
