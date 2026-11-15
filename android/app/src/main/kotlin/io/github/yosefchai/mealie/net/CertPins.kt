package io.github.yosefchai.mealie.net

import android.annotation.SuppressLint
import android.net.http.SslCertificate
import android.os.Build
import java.io.ByteArrayInputStream
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * תעודה בחתימה עצמית ששרת פרטי מציג. מתקבלת רק אם המשתמש אישר מראש את טביעת האצבע (SHA-256)
 * של התעודה עבור host:port זה. אותה בדיקה משמשת את OkHttp ואת ה-WebView.
 */
object CertPins {

    /** host:port → SHA-256 (hex, אותיות קטנות). מתעדכן מ-PinStore. */
    @Volatile
    var pins: Map<String, String> = emptyMap()

    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    fun isPinned(host: String, port: Int, cert: X509Certificate): Boolean =
        HostPolicy.isPrivateHost(host) && pins["$host:$port"] == fingerprint(cert)

    /** פורמט קריא לאישור: AB:CD:… */
    fun pretty(fingerprint: String): String = fingerprint.uppercase().chunked(2).joinToString(":")

    /** SslCertificate של WebView → X509. מ-API 29 יש API ישיר; לפני כן דרך saveState. */
    @SuppressLint("NewApi")
    fun fromWebView(cert: SslCertificate): X509Certificate? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return cert.x509Certificate
        val bytes = SslCertificate.saveState(cert)?.getByteArray("x509-certificate") ?: return null
        return runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        }.getOrNull()
    }

    val platformTrustManager: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}

/** נזרק כשהתעודה לא מהימנה ולא מוצמדת; נושא את טביעת האצבע כדי להציע אישור למשתמש. */
class UntrustedCertificateException(
    val host: String,
    val port: Int,
    val fingerprint: String,
    cause: Throwable?,
) : CertificateException("Untrusted certificate for $host:$port", cause)

/** בודק קודם מול אמון המערכת (כולל network_security_config), ורק אם נכשל – מול הצמדה. */
class PinningTrustManager(private val delegate: X509TrustManager = CertPins.platformTrustManager) :
    X509ExtendedTrustManager() {

    // השם מגיע מה-handshake עצמו, בלי פנייה ל-DNS הפוך.
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
        val session = (socket as? SSLSocket)?.handshakeSession
        check(chain, authType, session?.peerHost, session?.peerPort ?: socket?.port ?: -1)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        check(chain, authType, engine?.peerHost, engine?.peerPort ?: -1)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        check(chain, authType, null, -1)

    private fun check(chain: Array<X509Certificate>, authType: String, host: String?, port: Int) {
        try {
            delegate.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            val leaf = chain.firstOrNull() ?: throw e
            if (host != null && CertPins.isPinned(host, port, leaf)) return
            if (host != null) throw UntrustedCertificateException(host, port, CertPins.fingerprint(leaf), e)
            throw e
        }
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        delegate.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        delegate.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        delegate.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}

/** תעודה מוצמדת לא חייבת להכיל את שם השרת (נפוץ בתעודות עצמיות על IP). */
object PinningHostnameVerifier : HostnameVerifier {
    private val platform: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

    override fun verify(hostname: String, session: SSLSession): Boolean {
        if (platform.verify(hostname, session)) return true
        val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
            ?: return false
        return CertPins.isPinned(hostname, session.peerPort, leaf)
    }
}
