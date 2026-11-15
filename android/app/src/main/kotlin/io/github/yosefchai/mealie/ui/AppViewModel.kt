package io.github.yosefchai.mealie.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.yosefchai.mealie.auth.NativeOidc
import io.github.yosefchai.mealie.auth.Pkce
import io.github.yosefchai.mealie.auth.SessionSync
import io.github.yosefchai.mealie.core.AppGraph
import io.github.yosefchai.mealie.core.AppSettings
import io.github.yosefchai.mealie.core.ServerConfig
import io.github.yosefchai.mealie.core.ThemeMode
import io.github.yosefchai.mealie.core.UpdateMode
import io.github.yosefchai.mealie.net.AboutInfo
import io.github.yosefchai.mealie.net.NetworkState
import io.github.yosefchai.mealie.net.ProbeResult
import io.github.yosefchai.mealie.net.Resolution
import io.github.yosefchai.mealie.net.ServerResolver
import io.github.yosefchai.mealie.net.ServerUrl
import io.github.yosefchai.mealie.update.ApkInstaller
import io.github.yosefchai.mealie.update.CheckResult
import io.github.yosefchai.mealie.update.UpdateChecker
import io.github.yosefchai.mealie.update.UpdateInfo
import io.github.yosefchai.mealie.web.Tab
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Connection {
    data object Loading : Connection

    data object NeedsSetup : Connection

    data class Ready(val server: ServerConfig, val url: ServerUrl, val about: AboutInfo?, val gate: Boolean) : Connection

    data class Offline(val server: ServerConfig, val url: ServerUrl?) : Connection

    data class Failed(val server: ServerConfig, val results: List<ProbeResult>) : Connection
}

sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data class Available(val info: UpdateInfo) : UpdateState

    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState

    data class NeedsPermission(val info: UpdateInfo) : UpdateState

    data class Error(val info: UpdateInfo?) : UpdateState
}

sealed interface UiEvent {
    data class Message(val text: Int, val arg: String? = null) : UiEvent
}

enum class Screen { MAIN, SETTINGS, ADD_SERVER }

/** בדיקת שרת במסך החיבור: תוצאה לכל כתובת. */
data class TestState(
    val running: Boolean = false,
    val internal: ProbeResult? = null,
    val external: ProbeResult? = null,
)

@OptIn(FlowPreview::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AppGraph.settings
    private val sessions = SessionSync()
    private val oidc = NativeOidc()
    private val installer = ApkInstaller(app)
    private val resolver = ServerResolver(probe = { url, timeout -> AppGraph.probe.probe(url, timeout) })

    val settings: StateFlow<AppSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val network: StateFlow<NetworkState> =
        AppGraph.network.state.stateIn(viewModelScope, SharingStarted.Eagerly, AppGraph.network.current())

    private val _connection = MutableStateFlow<Connection>(Connection.Loading)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    private val _screen = MutableStateFlow(Screen.MAIN)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _test = MutableStateFlow(TestState())
    val test: StateFlow<TestState> = _test.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events

    /** בקשת OIDC native שממתינה לחזרה מהדפדפן. */
    var pendingOidc: NativeOidc.Pending? = null
        private set

    private var lastPath: String? = null
    private var resolveJob: Job? = null

    init {
        // כל שינוי בשרת הפעיל או ברשת מפעיל בחירת כתובת מחדש.
        viewModelScope.launch {
            combine(
                repo.settings.map { it.activeServer }.distinctUntilChanged { a, b ->
                    a?.id == b?.id && a?.internalUrl == b?.internalUrl && a?.externalUrl == b?.externalUrl
                },
                AppGraph.network.state,
            ) { server, net -> server to net }
                .debounce(250)
                .collect { (server, net) -> resolve(server, net) }
        }
        checkForUpdate(manual = false)
    }

    fun retry() {
        viewModelScope.launch { resolve(repo.current().activeServer, network.value) }
    }

    private fun resolve(server: ServerConfig?, net: NetworkState) {
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            if (server == null) {
                _connection.value = Connection.NeedsSetup
                return@launch
            }
            if (_connection.value !is Connection.Ready) _connection.value = Connection.Loading
            _connection.value = when (val r = resolver.resolve(server, net)) {
                is Resolution.Ready -> {
                    val about = (r.result as? ProbeResult.Ok)?.about
                    repo.updateServer(server.id) { it.copy(lastUsedUrl = r.url.toString()) }
                    Connection.Ready(server, r.url, about, gate = r.result is ProbeResult.AuthGate)
                }
                is Resolution.Offline -> Connection.Offline(server, r.url)
                is Resolution.Failed -> Connection.Failed(server, r.results)
            }
        }
    }

    // ---- ניווט ושכפול התחברות ----

    /** נקרא מה-WebView בכל שינוי נתיב. */
    fun onRoute(path: String) {
        val ready = _connection.value as? Connection.Ready ?: return
        val previous = lastPath
        lastPath = path
        Tab.groupSlugOf(path)?.let { slug ->
            if (slug != ready.server.groupSlug) viewModelScope.launch { repo.updateServer(ready.server.id) { it.copy(groupSlug = slug) } }
        }
        val maxAge = (ready.about?.tokenTime ?: 48).toLong() * 3600
        when {
            // כניסה בטופס: fetch ואחריו ניווט פנימי, בלי onPageFinished. משכפלים מיד ביציאה מ-/login.
            previous != null && Tab.isLogin(previous) && !Tab.isLogin(path) ->
                sessions.propagate(ready.url, ready.server.all, maxAge)
            // יציאה מהחשבון: ממסך פנימי ל-/login, והטוקן נמחק בכתובת הפעילה.
            previous != null && !Tab.isLogin(previous) && Tab.isLogin(path) && sessions.tokenAt(ready.url) == null ->
                sessions.clearAll(ready.server.all)
            !Tab.isLogin(path) -> sessions.propagate(ready.url, ready.server.all, maxAge)
        }
    }

    /** גיבוי: לפני שהאפליקציה עוברת לרקע. */
    fun syncSession() {
        val ready = _connection.value as? Connection.Ready ?: return
        sessions.propagate(ready.url, ready.server.all, (ready.about?.tokenTime ?: 48).toLong() * 3600)
    }

    // ---- הגדרת שרתים ----

    fun testServer(internalInput: String, externalInput: String) {
        val internal = ServerUrl.parse(internalInput)
        val external = ServerUrl.parse(externalInput)
        viewModelScope.launch {
            _test.value = TestState(running = true)
            val i = internal?.let { AppGraph.probe.probe(it) }
            val e = external?.let { AppGraph.probe.probe(it) }
            _test.value = TestState(running = false, internal = i, external = e)
        }
    }

    fun resetTest() {
        _test.value = TestState()
    }

    fun saveServer(name: String, internalInput: String, externalInput: String) {
        val internal = ServerUrl.parse(internalInput)?.toString()
        val external = ServerUrl.parse(externalInput)?.toString()
        if (internal == null && external == null) return
        viewModelScope.launch {
            val config = ServerConfig(name = name.trim(), internalUrl = internal, externalUrl = external)
            repo.update { it.copy(servers = it.servers + config, activeServerId = config.id) }
            _test.value = TestState()
            _screen.value = Screen.MAIN
        }
    }

    fun switchServer(id: String) {
        viewModelScope.launch { repo.update { it.copy(activeServerId = id) } }
        _screen.value = Screen.MAIN
    }

    fun removeServer(id: String) {
        viewModelScope.launch {
            val server = repo.current().servers.firstOrNull { it.id == id }
            server?.let { sessions.clearAll(it.all) }
            repo.update { s ->
                val rest = s.servers.filterNot { it.id == id }
                s.copy(servers = rest, activeServerId = if (s.activeServerId == id) rest.firstOrNull()?.id else s.activeServerId)
            }
        }
    }

    fun trustCertificate(hostPort: String, fingerprint: String) {
        viewModelScope.launch {
            repo.pin(hostPort, fingerprint)
            _test.value.let { t ->
                if (t.internal != null || t.external != null) _test.value = TestState()
            }
            retry()
        }
    }

    // ---- התחברות ----

    suspend fun prepareOidc(): Uri? {
        val ready = _connection.value as? Connection.Ready ?: return null
        val config = oidc.config(ready.url) ?: return null
        val pkce = Pkce.create()
        pendingOidc = NativeOidc.Pending(ready.url, pkce)
        return oidc.authorizationUri(config, pkce)
    }

    /** חזרה מהדפדפן: מחליף קוד בטוקן ומכניס אותו לשתי הכתובות. מחזיר true בהצלחה. */
    suspend fun completeOidc(redirect: Uri?): Boolean {
        val pending = pendingOidc ?: return false
        pendingOidc = null
        if (redirect == null) return false
        return when (val outcome = oidc.complete(pending, redirect)) {
            is NativeOidc.Outcome.Success -> {
                val ready = _connection.value as? Connection.Ready
                val all = ready?.server?.all ?: listOf(pending.server)
                sessions.setForAll(all, outcome.token.accessToken, outcome.token.expiresIn.coerceAtLeast(3600))
                true
            }
            NativeOidc.Outcome.Cancelled -> false
            is NativeOidc.Outcome.Failed -> {
                _events.tryEmit(UiEvent.Message(io.github.yosefchai.mealie.R.string.oidc_failed, outcome.reason))
                false
            }
        }
    }

    fun useApiToken(token: String) {
        val ready = _connection.value as? Connection.Ready ?: return
        val clean = token.trim().removePrefix("Bearer ").trim()
        if (clean.isEmpty()) return
        // טוקן API ארוך-טווח: עוגייה לשנה; השרת יקבע את התוקף בפועל.
        sessions.setForAll(ready.server.all, clean, 365L * 24 * 3600)
    }

    fun signOut() {
        val ready = _connection.value as? Connection.Ready ?: return
        sessions.clearAll(ready.server.all)
    }

    // ---- הגדרות ----

    fun openScreen(screen: Screen) {
        _screen.value = screen
        if (screen == Screen.ADD_SERVER) _test.value = TestState()
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { repo.update { it.copy(theme = mode) } }

    fun setDynamicColor(on: Boolean) = viewModelScope.launch { repo.update { it.copy(dynamicColor = on) } }

    fun setUpdateMode(mode: UpdateMode) = viewModelScope.launch { repo.update { it.copy(updateMode = mode) } }

    fun setRawWebView(on: Boolean) = viewModelScope.launch { repo.update { it.copy(rawWebView = on) } }

    fun setCompressImages(on: Boolean) = viewModelScope.launch { repo.update { it.copy(compressCameraImages = on) } }

    // ---- עדכונים ----

    fun checkForUpdate(manual: Boolean) {
        viewModelScope.launch {
            if (!manual && repo.current().updateMode == UpdateMode.OFF) return@launch
            if (manual) _update.value = UpdateState.Checking
            _update.value = when (val r = UpdateChecker().check()) {
                is CheckResult.Available ->
                    if (!manual && repo.current().skippedUpdateTag == r.info.tag) UpdateState.Idle else UpdateState.Available(r.info)
                CheckResult.UpToDate -> if (manual) UpdateState.UpToDate else UpdateState.Idle
                is CheckResult.Error -> if (manual) UpdateState.Error(null) else UpdateState.Idle
            }
        }
    }

    fun installUpdate(info: UpdateInfo) {
        if (!installer.canInstall()) {
            _update.value = UpdateState.NeedsPermission(info)
            return
        }
        viewModelScope.launch {
            _update.value = UpdateState.Downloading(info, 0f)
            runCatching {
                val apk = installer.download(info) { p -> _update.value = UpdateState.Downloading(info, p) }
                installer.install(apk)
            }.onFailure {
                installer.cleanup()
                _update.value = UpdateState.Error(info)
            }
        }
    }

    fun skipUpdate(info: UpdateInfo) {
        viewModelScope.launch { repo.update { it.copy(skippedUpdateTag = info.tag) } }
        _update.value = UpdateState.Idle
    }

    fun dismissUpdate() {
        _update.value = UpdateState.Idle
    }
}
