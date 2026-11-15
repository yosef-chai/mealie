package io.github.yosefchai.mealie.web

import android.content.Context
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import java.util.concurrent.Executors

/**
 * אתחול מנוע Chromium ברקע מ-Application.onCreate (Jetpack WebKit, startUpWebView).
 * חוסך את 300–600ms של היצירה הראשונה של WebView על ה-UI thread.
 */
object WebStartup {
    fun start(context: Context) {
        val config = WebViewStartUpConfig.Builder(Executors.newSingleThreadExecutor()).build()
        runCatching {
            WebViewCompat.startUpWebView(
                context,
                config,
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) = Unit

                    override fun onError(error: WebViewStartupException) = Unit
                },
            )
        }
    }
}
