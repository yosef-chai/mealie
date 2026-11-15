package io.github.yosefchai.mealie.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** הורדה ופענוח של תמונה בגודל מוגבל (לגיליון הייבוא ולווידג'ט). */
object Images {
    suspend fun load(url: String, token: String?, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).apply { token?.let { header("Authorization", "Bearer $it") } }.build()
            val bytes = Http.client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@runCatching null
                res.body.bytes()
            }
            decode(bytes, maxPx)
        }.getOrNull()
    }

    fun decode(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = maxPx.toFloat() / maxOf(decoded.width, decoded.height)
        if (scale >= 1f) return decoded
        return decoded.scale((decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1))
            .also { if (it !== decoded) decoded.recycle() }
    }
}
