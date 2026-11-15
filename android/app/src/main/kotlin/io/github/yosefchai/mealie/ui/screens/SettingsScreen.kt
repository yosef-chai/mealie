package io.github.yosefchai.mealie.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import io.github.yosefchai.mealie.BuildConfig
import io.github.yosefchai.mealie.MainActivity
import io.github.yosefchai.mealie.R
import io.github.yosefchai.mealie.core.AppSettings
import io.github.yosefchai.mealie.core.ServerConfig
import io.github.yosefchai.mealie.core.ThemeMode
import io.github.yosefchai.mealie.core.UpdateMode
import io.github.yosefchai.mealie.ui.AppViewModel
import io.github.yosefchai.mealie.ui.Connection
import io.github.yosefchai.mealie.ui.Screen
import io.github.yosefchai.mealie.ui.UpdateState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, activity: MainActivity) {
    val settings by vm.settings.collectAsState()
    val connection by vm.connection.collectAsState()
    val update by vm.update.collectAsState()
    val s = settings ?: AppSettings()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var removing by remember { mutableStateOf<ServerConfig?>(null) }
    var languages by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.app_settings)) },
                navigationIcon = {
                    IconButton(onClick = { vm.openScreen(Screen.MAIN) }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.mealie_back))
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Section(stringResource(R.string.settings_servers)) {
                    s.servers.forEach { server ->
                        val active = server.id == s.activeServer?.id
                        Row(
                            icon = if (active) Icons.Rounded.CheckCircle else Icons.Rounded.Dns,
                            title = server.displayName,
                            subtitle = listOfNotNull(server.externalUrl, server.internalUrl).joinToString("\n"),
                            onClick = { if (!active) vm.switchServer(server.id) },
                            trailing = {
                                IconButton(onClick = { removing = server }) {
                                    Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.mealie_delete))
                                }
                            },
                        )
                    }
                    Row(Icons.Rounded.Add, stringResource(R.string.server_add), null, onClick = { vm.openScreen(Screen.ADD_SERVER) })
                }
            }

            item {
                Section(stringResource(R.string.settings_appearance)) {
                    Row(Icons.Rounded.Palette, stringResource(R.string.settings_theme), null)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        val modes = listOf(
                            ThemeMode.SYSTEM to R.string.theme_system,
                            ThemeMode.LIGHT to R.string.theme_light,
                            ThemeMode.DARK to R.string.theme_dark,
                        )
                        modes.forEachIndexed { i, (mode, label) ->
                            SegmentedButton(
                                selected = s.theme == mode,
                                onClick = { vm.setTheme(mode) },
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            ) { Text(stringResource(label)) }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SwitchRow(Icons.Rounded.Palette, stringResource(R.string.settings_dynamic_color), stringResource(R.string.settings_dynamic_color_hint), s.dynamicColor, vm::setDynamicColor)
                    }
                    Row(
                        Icons.Rounded.Language,
                        stringResource(R.string.mealie_language),
                        currentLanguageName(),
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                // רשימת השפות של המערכת לאפליקציה (נוצרת מ-generateLocaleConfig).
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${context.packageName}")),
                                )
                            } else {
                                languages = true
                            }
                        },
                    )
                }
            }

            item {
                Section(stringResource(R.string.settings_web)) {
                    SwitchRow(Icons.Rounded.Web, stringResource(R.string.settings_raw), stringResource(R.string.settings_raw_hint), s.rawWebView, vm::setRawWebView)
                    SwitchRow(Icons.Rounded.PhotoCamera, stringResource(R.string.settings_compress), stringResource(R.string.settings_compress_hint), s.compressCameraImages, vm::setCompressImages)
                }
            }

            item {
                Section(stringResource(R.string.settings_updates)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        val modes = listOf(
                            UpdateMode.AUTO to R.string.update_mode_auto,
                            UpdateMode.NOTIFY to R.string.update_mode_notify,
                            UpdateMode.OFF to R.string.update_mode_off,
                        )
                        modes.forEachIndexed { i, (mode, label) ->
                            SegmentedButton(
                                selected = s.updateMode == mode,
                                onClick = { vm.setUpdateMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            ) { Text(stringResource(label)) }
                        }
                    }
                    val status = when (update) {
                        UpdateState.Checking -> stringResource(R.string.update_checking)
                        UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
                        is UpdateState.Available -> stringResource(R.string.update_available_body, (update as UpdateState.Available).info.versionName)
                        else -> stringResource(R.string.update_check_hint)
                    }
                    Row(Icons.Rounded.SystemUpdate, stringResource(R.string.update_check_now), status, onClick = { vm.checkForUpdate(manual = true) })
                }
            }

            item {
                Section(stringResource(R.string.settings_about)) {
                    val server = (connection as? Connection.Ready)?.about?.version
                    Row(Icons.Rounded.Info, stringResource(R.string.about_app_version), BuildConfig.VERSION_NAME)
                    if (server != null) Row(Icons.Rounded.Dns, stringResource(R.string.about_server_version), server)
                    Row(
                        Icons.AutoMirrored.Rounded.OpenInNew,
                        stringResource(R.string.about_source),
                        "github.com/${BuildConfig.UPDATE_REPO}",
                        onClick = { activity.openExternal(Uri.parse("https://github.com/${BuildConfig.UPDATE_REPO}")) },
                    )
                }
            }
        }
    }

    removing?.let { server ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.server_remove_title)) },
            text = { Text(stringResource(R.string.server_remove_body, server.displayName)) },
            confirmButton = {
                TextButton(onClick = { vm.removeServer(server.id); removing = null }) { Text(stringResource(R.string.mealie_delete)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.mealie_cancel)) } },
        )
    }

    if (languages) LanguageDialog(onDismiss = { languages = false })
}

@Composable
private fun currentLanguageName(): String {
    val tags = AppCompatDelegate.getApplicationLocales()
    if (tags.isEmpty) return stringResource(R.string.language_system)
    val l = tags[0] ?: return stringResource(R.string.language_system)
    return l.getDisplayName(l).replaceFirstChar { it.titlecase(l) }
}

/** Android 12 ומטה: בורר שפה בתוך האפליקציה (ב-13 ומעלה משתמשים במסך המערכת). */
@Composable
private fun LanguageDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val current = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    // הרשימה נוצרת בזמן הבנייה מאותן שפות של Mealie (GenerateStringsTask).
    val supported = remember {
        context.resources.getStringArray(R.array.supported_locales)
            .map { Locale.forLanguageTag(it) }
            .sortedBy { it.getDisplayName(it).lowercase() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mealie_language)) },
        text = {
            LazyColumn {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.language_system)) },
                        modifier = Modifier.clickableRow {
                            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                            onDismiss()
                        },
                    )
                }
                supported.forEach { locale ->
                    item {
                        ListItem(
                            headlineContent = { Text(locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }) },
                            supportingContent = { Text(locale.getDisplayName(current)) },
                            modifier = Modifier.clickableRow {
                                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale.toLanguageTag()))
                                onDismiss()
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mealie_close)) } },
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit) = this.clickable(onClick = onClick)

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(vertical = 8.dp)) { content() }
        }
    }
}

@Composable
private fun Row(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val item = @Composable {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = subtitle?.let { { Text(it) } },
            leadingContent = { Icon(icon, null) },
            trailingContent = trailing,
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
    }
    if (onClick != null) Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainer) { item() } else item()
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(onClick = { onChange(!checked) }, color = MaterialTheme.colorScheme.surfaceContainer) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            leadingContent = { Icon(icon, null) },
            trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
    }
}
