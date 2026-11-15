package io.github.yosefchai.mealie.share

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.yosefchai.mealie.MainActivity
import io.github.yosefchai.mealie.core.AppGraph
import io.github.yosefchai.mealie.core.AppSettings
import io.github.yosefchai.mealie.importer.ImportState
import io.github.yosefchai.mealie.importer.RecipeImporter
import io.github.yosefchai.mealie.ui.theme.MealieTheme
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/** ייבוא ברקע של הגיליון: שורד סיבוב מסך, ומתבטל כשהמשתמש סוגר. */
class ImportViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<ImportState>(ImportState.Connecting)
    val state: StateFlow<ImportState> = _state.asStateFlow()
    private var job: Job? = null
    var url: String? = null
        private set

    fun start(url: String, force: Boolean = false) {
        if (job?.isActive == true && !force && this.url == url) return
        this.url = url
        job?.cancel()
        job = viewModelScope.launch {
            RecipeImporter(getApplication()).run(url, force)
                .catch { e ->
                    // רשת שנפלה באמצע, טוקן שפג וכו': מציגים שגיאה עם אפשרות לנסות שוב
                    emit(ImportState.Failed(blocked = false, message = if (e is IOException) null else e.message))
                }
                .collect { _state.value = it }
        }
    }
}

/**
 * יעד שיתוף: דפדפנים ואפליקציות שולחים טקסט ("תראו איזה מתכון! https://…"). מחלצים את הקישור
 * ומייבאים בגיליון קטן מעל האפליקציה שממנה שיתפו, בלי לפתוח את Mealie.
 */
class ShareActivity : ComponentActivity() {

    private val vm: ImportViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val url = extractUrl(text) ?: extractUrl(subject) ?: intent?.data?.toString()?.takeIf { it.startsWith("http") }
        if (url == null) {
            openInApp(null)
            return
        }
        if (savedInstanceState == null || vm.url == null) vm.start(url)

        setContent {
            val settings by AppGraph.settings.settings.collectAsState(initial = AppSettings())
            val state by vm.state.collectAsState()
            MealieTheme(settings.theme, settings.dynamicColor) {
                ImportSheet(
                    state = state,
                    url = url,
                    onDismiss = { finish() },
                    onOpen = { path -> openPath(path) },
                    onRetry = { vm.start(url, force = false) },
                    onImportAgain = { vm.start(url, force = true) },
                    onOpenInApp = { openInApp(url) },
                )
            }
        }
    }

    /** אין שרת או התחברות: ממשיכים בדף הייבוא של Mealie, שיבקש להתחבר אם צריך. */
    private fun openInApp(url: String?) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_IMPORT)
                .putExtra(MainActivity.EXTRA_IMPORT_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    private fun openPath(path: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_PATH)
                .putExtra(MainActivity.EXTRA_PATH, path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    companion object {
        fun extractUrl(text: String): String? {
            val m = Patterns.WEB_URL.matcher(text)
            var bare: String? = null
            while (m.find()) {
                val candidate = m.group().trimEnd('.', ',', ')', '!', '?', '"', '\'')
                if (candidate.startsWith("http://", true) || candidate.startsWith("https://", true)) return candidate
                // כתובת בלי scheme ("example.com/recipe"): רק אם אין כתובת מלאה בטקסט.
                if (bare == null && '/' in candidate) bare = "https://$candidate"
            }
            return bare
        }
    }
}
