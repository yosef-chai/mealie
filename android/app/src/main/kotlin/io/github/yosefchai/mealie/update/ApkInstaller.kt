package io.github.yosefchai.mealie.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import io.github.yosefchai.mealie.net.Http
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * הורדה, אימות SHA-256 מול checksums.txt, והתקנה דרך PackageInstaller.
 * מ-API 31: USER_ACTION_NOT_REQUIRED עם UPDATE_PACKAGES_WITHOUT_USER_ACTION, ולכן עדכון עצמי מותקן בלי מסך.
 * מערכת ההפעלה מוודאת שהחתימה זהה לגרסה המותקנת.
 */
class ApkInstaller(private val context: Context) {

    private val dir get() = File(context.cacheDir, "updates").apply { mkdirs() }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        cleanup()
        val expected = expectedSha(info)
        val file = File(dir, "${info.tag}.apk")
        Http.publicClient.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("APK HTTP ${r.code}")
            val total = r.body.contentLength().takeIf { it > 0 }
            val digest = MessageDigest.getInstance("SHA-256")
            r.body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        total?.let { onProgress(read.toFloat() / it) }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) {
                file.delete()
                throw IOException("Checksum mismatch")
            }
        }
        file
    }

    private fun expectedSha(info: UpdateInfo): String {
        Http.publicClient.newCall(Request.Builder().url(info.checksumsUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("checksums HTTP ${r.code}")
            // פורמט sha256sum: "<hash>  <file>"
            return r.body.string().lineSequence()
                .map { it.trim().split(Regex("\\s+"), limit = 2) }
                .firstOrNull { it.size == 2 && it[1].trimStart('*') == UpdateChecker.APK_NAME }
                ?.get(0)
                ?: throw IOException("No checksum for ${UpdateChecker.APK_NAME}")
        }
    }

    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallStatusReceiver::class.java)
                .setPackage(context.packageName)
                .putExtra(InstallStatusReceiver.EXTRA_APK, apk.absolutePath)
            // FLAG_MUTABLE חובה: המערכת מוסיפה ל-Intent את EXTRA_STATUS ואת מסך האישור.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    fun cleanup() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
