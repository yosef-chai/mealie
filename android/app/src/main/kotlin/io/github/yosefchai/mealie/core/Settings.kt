package io.github.yosefchai.mealie.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.yosefchai.mealie.net.CertPins
import io.github.yosefchai.mealie.net.MealieJson
import io.github.yosefchai.mealie.net.ServerUrl
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
enum class UpdateMode { AUTO, NOTIFY, OFF }

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val internalUrl: String? = null,
    val externalUrl: String? = null,
    /** הכתובת האחרונה שעבדה; משמשת כשאין רשת בכלל. */
    val lastUsedUrl: String? = null,
    val groupSlug: String? = null,
) {
    val internal: ServerUrl? get() = internalUrl?.let(ServerUrl::parse)
    val external: ServerUrl? get() = externalUrl?.let(ServerUrl::parse)
    val all: List<ServerUrl> get() = listOfNotNull(internal, external)
    val displayName: String get() = name.ifBlank { (external ?: internal)?.host.orEmpty() }
}

@Serializable
data class AppSettings(
    val servers: List<ServerConfig> = emptyList(),
    val activeServerId: String? = null,
    val updateMode: UpdateMode = UpdateMode.AUTO,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val compressCameraImages: Boolean = true,
    /** "תצוגת ווב מקורית": מכבה את כל שכבת ההתאמה למובייל. */
    val rawWebView: Boolean = false,
    val skippedUpdateTag: String? = null,
    /** ייבוא מתכון: מילות המפתח של האתר כתגיות, והקטגוריות שלו (כמו בתיבות הסימון של Mealie). */
    val importTags: Boolean = false,
    val importCategories: Boolean = false,
    /** טיימרים בשלבי ההכנה. */
    val stepTimers: Boolean = true,
) {
    val activeServer: ServerConfig? get() = servers.firstOrNull { it.id == activeServerId } ?: servers.firstOrNull()
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** אישורי תעודות בקובץ נפרד, שלא נכלל בגיבוי (data_extraction_rules). */
private val Context.pinStore: DataStore<Preferences> by preferencesDataStore(name = "pins")

class SettingsRepository(private val context: Context) {
    private val key = stringPreferencesKey("app_settings")
    private val pinsKey = stringPreferencesKey("pins")

    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        prefs[key]?.let { runCatching { MealieJson.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { prefs ->
            val now = prefs[key]?.let { runCatching { MealieJson.decodeFromString<AppSettings>(it) }.getOrNull() }
                ?: AppSettings()
            prefs[key] = MealieJson.encodeToString(AppSettings.serializer(), transform(now))
        }
    }

    suspend fun updateServer(id: String, transform: (ServerConfig) -> ServerConfig) = update { s ->
        s.copy(servers = s.servers.map { if (it.id == id) transform(it) else it })
    }

    val pins: Flow<Map<String, String>> = context.pinStore.data.map { prefs ->
        prefs[pinsKey]?.let { runCatching { MealieJson.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            ?: emptyMap()
    }

    suspend fun loadPins() {
        CertPins.pins = pins.first()
    }

    suspend fun pin(hostPort: String, fingerprint: String) {
        context.pinStore.edit { prefs ->
            val now = prefs[pinsKey]?.let { MealieJson.decodeFromString<Map<String, String>>(it) } ?: emptyMap()
            val next = now + (hostPort to fingerprint)
            prefs[pinsKey] = MealieJson.encodeToString(next)
            CertPins.pins = next
        }
    }
}
