package io.github.yosefchai.mealie.web

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.github.yosefchai.mealie.net.Http
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.Request

sealed interface DownloadEvent {
    data class Started(val name: String) : DownloadEvent

    data class Finished(val name: String, val uri: Uri, val mime: String) : DownloadEvent

    data class Failed(val name: String) : DownloadEvent
}

/**
 * הורדות בלי DownloadManager (שרץ בתהליך נפרד ולא רואה את העוגיות, את הצמדת התעודה או blob:).
 * - blob/data: מגיעים מה-JS במקטעים (ArrayBuffer עם כותרת 16 בתים, או base64 ב-WebView ישן).
 * - http(s): דרך OkHttp של האפליקציה.
 * היעד: MediaStore.Downloads (API 29+, בלי הרשאות), אחרת תיקיית Downloads הציבורית או של האפליקציה.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Downloads(private val context: Context, private val scope: CoroutineScope) {

    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<DownloadEvent> = _events

    /** כתיבה סדרתית: המקטעים נכתבים בדיוק בסדר שבו הגיעו. */
    private val io = Dispatchers.IO.limitedParallelism(1)

    private class Transfer(val name: String, val mime: String, val total: Int, val target: Target) {
        var next = 0
    }

    private val transfers = HashMap<Int, Transfer>()

    fun begin(id: Int, name: String, mime: String, chunks: Int) {
        scope.launch(io) {
            val safeName = ContentDisposition.resolve("", null, mime, name)
            val target = runCatching { Target.open(context, safeName, mime) }.getOrNull()
            if (target == null) {
                _events.tryEmit(DownloadEvent.Failed(safeName))
                return@launch
            }
            transfers[id] = Transfer(safeName, mime, chunks, target)
            _events.tryEmit(DownloadEvent.Started(safeName))
        }
    }

    /** מקטע בינארי: uint32 transferId, chunkIndex, totalChunks, flags (big-endian), ואז הנתונים. */
    fun binaryChunk(frame: ByteArray) {
        if (frame.size < HEADER) return
        val header = ByteBuffer.wrap(frame, 0, HEADER)
        val id = header.int
        val index = header.int
        header.int // totalChunks: נבדק מול begin
        scope.launch(io) { write(id, index, frame, HEADER, frame.size - HEADER) }
    }

    fun base64Chunk(id: Int, index: Int, data: String) {
        scope.launch(io) {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            write(id, index, bytes, 0, bytes.size)
        }
    }

    fun fail(id: Int) {
        scope.launch(io) { transfers.remove(id)?.let { it.target.abort(); _events.tryEmit(DownloadEvent.Failed(it.name)) } }
    }

    private fun write(id: Int, index: Int, bytes: ByteArray, offset: Int, length: Int) {
        val t = transfers[id] ?: return
        if (index != t.next) {
            // מקטע חסר או כפול: לא סוגרים קובץ חלקי.
            transfers.remove(id)
            t.target.abort()
            _events.tryEmit(DownloadEvent.Failed(t.name))
            return
        }
        runCatching { t.target.stream.write(bytes, offset, length) }.onFailure {
            transfers.remove(id)
            t.target.abort()
            _events.tryEmit(DownloadEvent.Failed(t.name))
            return
        }
        t.next++
        if (t.next == t.total) {
            transfers.remove(id)
            val uri = t.target.finish()
            _events.tryEmit(DownloadEvent.Finished(t.name, uri, t.mime))
        }
    }

    /** קישור http(s): עם העוגיות וההצמדה של האפליקציה; שם הקובץ מ-Content-Disposition. */
    fun fetch(url: String, contentDisposition: String?, mime: String?, suggested: String? = null) {
        scope.launch(Dispatchers.IO) {
            var name = ContentDisposition.resolve(url, contentDisposition, mime, suggested)
            try {
                Http.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val type = resp.header("Content-Type")?.substringBefore(';')?.trim() ?: mime ?: "application/octet-stream"
                    name = ContentDisposition.resolve(url, resp.header("Content-Disposition") ?: contentDisposition, type, suggested)
                    _events.tryEmit(DownloadEvent.Started(name))
                    val target = Target.open(context, name, type)
                    try {
                        resp.body.byteStream().use { input -> input.copyTo(target.stream) }
                        _events.tryEmit(DownloadEvent.Finished(name, target.finish(), type))
                    } catch (e: Exception) {
                        target.abort()
                        throw e
                    }
                }
            } catch (e: Exception) {
                _events.tryEmit(DownloadEvent.Failed(name))
            }
        }
    }

    private class Target(val stream: OutputStream, private val onFinish: () -> Uri, private val onAbort: () -> Unit) {
        fun finish(): Uri {
            stream.close()
            return onFinish()
        }

        fun abort() {
            runCatching { stream.close() }
            onAbort()
        }

        companion object {
            fun open(context: Context, name: String, mime: String): Target =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) mediaStore(context, name, mime) else legacy(context, name)

            @RequiresApi(Build.VERSION_CODES.Q)
            private fun mediaStore(context: Context, name: String, mime: String): Target {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Mealie")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore insert failed")
                val stream = resolver.openOutputStream(uri) ?: error("No output stream")
                return Target(
                    stream,
                    onFinish = {
                        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                        uri
                    },
                    onAbort = { resolver.delete(uri, null, null) },
                )
            }

            @Suppress("DEPRECATION")
            private fun legacy(context: Context, name: String): Target {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
                val dir = if (granted) {
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Mealie")
                } else {
                    File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Mealie")
                }
                dir.mkdirs()
                val file = uniqueFile(dir, name)
                return Target(
                    file.outputStream(),
                    onFinish = { FileProvider.getUriForFile(context, "${context.packageName}.files", file) },
                    onAbort = { file.delete() },
                )
            }

            private fun uniqueFile(dir: File, name: String): File {
                var f = File(dir, name)
                var n = 1
                val stem = name.substringBeforeLast('.')
                val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
                while (f.exists()) f = File(dir, "$stem (${n++})$ext")
                return f
            }
        }
    }

    companion object {
        const val HEADER = 16
    }
}
