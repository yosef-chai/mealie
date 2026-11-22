package io.github.yosefchai.mealie.ui.screens

import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Scale
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalResources
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
import io.github.yosefchai.mealie.ui.ScaleFixState
import io.github.yosefchai.mealie.ui.Screen
import io.github.yosefchai.mealie.ui.UpdateState
import io.github.yosefchai.mealie.ui.components.ListGroup
import io.github.yosefchai.mealie.ui.components.groupItemColors
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel, activity: MainActivity) {
    val settings by vm.settings.collectAsState()
    val connection by vm.connection.collectAsState()
    val update by vm.update.collectAsState()
    val s = settings ?: AppSettings()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var removing by remember { mutableStateOf<ServerConfig?>(null) }
    var languages by remember { mutableStateOf(false) }
    val scaleFix by vm.scaleFix.collectAsState()
    var confirmScale by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
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
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                ListGroup(stringResource(R.string.settings_servers)) {
                    s.servers.forEach { server ->
                        val active = server.id == s.activeServer?.id
                        row { shapes ->
                            SettingRow(
                                shapes = shapes,
                                icon = if (active) Icons.Rounded.CheckCircle else Icons.Rounded.Dns,
                                title = server.displayName,
                                subtitle = listOfNotNull(server.externalUrl, server.internalUrl).joinToString("\n"),
                                onClick = { if (!active) vm.switchServer(server.id) },
                                highlighted = active,
                                trailing = {
                                    IconButton(onClick = { removing = server }) {
                                        Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.mealie_delete))
                                    }
                                },
                            )
                        }
                    }
                    row { shapes ->
                        SettingRow(shapes, Icons.Rounded.Add, stringResource(R.string.server_add), null, onClick = { vm.openScreen(Screen.ADD_SERVER) })
                    }
                }
            }

            item {
                ListGroup(stringResource(R.string.settings_appearance)) {
                    row { shapes ->
                        SegmentedListItem(
                            shapes = shapes,
                            colors = groupItemColors(),
                            leadingContent = { Icon(Icons.Rounded.Palette, null) },
                            supportingContent = {
                                ConnectedChoice(
                                    options = listOf(
                                        ThemeMode.SYSTEM to R.string.theme_system,
                                        ThemeMode.LIGHT to R.string.theme_light,
                                        ThemeMode.DARK to R.string.theme_dark,
                                    ),
                                    selected = s.theme,
                                    onSelect = vm::setTheme,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            },
                        ) { Text(stringResource(R.string.settings_theme)) }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        row { shapes ->
                            SwitchRow(shapes, Icons.Rounded.Colorize, stringResource(R.string.settings_dynamic_color), stringResource(R.string.settings_dynamic_color_hint), s.dynamicColor, vm::setDynamicColor)
                        }
                    }
                    row { shapes ->
                        SettingRow(
                            shapes,
                            Icons.Rounded.Language,
                            stringResource(R.string.mealie_language),
                            currentLanguageName(),
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    // רשימת השפות של המערכת לאפליקציה (נוצרת מ-generateLocaleConfig).
                                    val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, "package:${context.packageName}".toUri())
                                    // יש יצרנים שהסירו את המסך הזה; אז הבורר הפנימי.
                                    runCatching { context.startActivity(intent) }.onFailure { languages = true }
                                } else {
                                    languages = true
                                }
                            },
                        )
                    }
                }
            }

            item {
                ListGroup(stringResource(R.string.settings_web)) {
                    row { SwitchRow(it, Icons.Rounded.Web, stringResource(R.string.settings_raw), stringResource(R.string.settings_raw_hint), s.rawWebView, vm::setRawWebView) }
                    row { SwitchRow(it, Icons.Rounded.PhotoCamera, stringResource(R.string.settings_compress), stringResource(R.string.settings_compress_hint), s.compressCameraImages, vm::setCompressImages) }
                    row { SwitchRow(it, Icons.Rounded.Timer, stringResource(R.string.settings_timers), stringResource(R.string.settings_timers_hint), s.stepTimers, vm::setStepTimers) }
                }
            }

            item {
                ListGroup(stringResource(R.string.settings_import)) {
                    row { SwitchRow(it, Icons.Rounded.Sell, stringResource(R.string.settings_import_tags), stringResource(R.string.settings_import_tags_hint), s.importTags, vm::setImportTags) }
                    row { SwitchRow(it, Icons.Rounded.Category, stringResource(R.string.settings_import_categories), stringResource(R.string.settings_import_categories_hint), s.importCategories, vm::setImportCategories) }
                    row { shapes ->
                        val running = scaleFix as? ScaleFixState.Running
                        SettingRow(
                            shapes = shapes,
                            icon = Icons.Rounded.Scale,
                            title = stringResource(R.string.scale_fix_title),
                            subtitle = scaleFixStatus(scaleFix),
                            onClick = if (running == null && connection is Connection.Ready) ({ confirmScale = true }) else null,
                            below = running?.let { r ->
                                {
                                    val modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                                    if (r.total > 0) {
                                        LinearWavyProgressIndicator(progress = { r.done.toFloat() / r.total }, modifier = modifier)
                                    } else {
                                        LinearWavyProgressIndicator(modifier = modifier)
                                    }
                                }
                            },
                        )
                    }
                }
            }

            item {
                ListGroup(stringResource(R.string.settings_updates)) {
                    row { shapes ->
                        SegmentedListItem(
                            shapes = shapes,
                            colors = groupItemColors(),
                            leadingContent = { Icon(Icons.Rounded.Update, null) },
                        ) {
                            ConnectedChoice(
                                options = listOf(
                                    UpdateMode.AUTO to R.string.update_mode_auto,
                                    UpdateMode.NOTIFY to R.string.update_mode_notify,
                                    UpdateMode.OFF to R.string.update_mode_off,
                                ),
                                selected = s.updateMode,
                                onSelect = vm::setUpdateMode,
                            )
                        }
                    }
                    row { shapes ->
                        val current = update
                        val checking = current == UpdateState.Checking
                        val status = when (current) {
                            UpdateState.Checking -> stringResource(R.string.update_checking)
                            UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
                            is UpdateState.Available -> stringResource(R.string.update_available_body, current.info.versionName)
                            else -> stringResource(R.string.update_check_hint)
                        }
                        SettingRow(
                            shapes = shapes,
                            icon = Icons.Rounded.SystemUpdate,
                            title = stringResource(R.string.update_check_now),
                            subtitle = status,
                            // בזמן בדיקה השורה לא לחיצה, כדי שלא ייפתחו כמה בדיקות במקביל.
                            onClick = if (checking) null else ({ vm.checkForUpdate(manual = true) }),
                            trailing = if (checking) ({ LoadingIndicator(Modifier.size(32.dp)) }) else null,
                        )
                    }
                }
            }

            item {
                ListGroup(stringResource(R.string.settings_about)) {
                    val server = (connection as? Connection.Ready)?.about?.version
                    row { SettingRow(it, Icons.Rounded.Info, stringResource(R.string.about_app_version), BuildConfig.VERSION_NAME) }
                    if (server != null) row { SettingRow(it, Icons.Rounded.Dns, stringResource(R.string.about_server_version), server) }
                    row { shapes ->
                        SettingRow(
                            shapes,
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            stringResource(R.string.about_source),
                            "github.com/${BuildConfig.UPDATE_REPO}",
                            onClick = { activity.openExternal("https://github.com/${BuildConfig.UPDATE_REPO}".toUri()) },
                        )
                    }
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

    if (confirmScale) {
        AlertDialog(
            onDismissRequest = { confirmScale = false },
            icon = { Icon(Icons.Rounded.Scale, null) },
            title = { Text(stringResource(R.string.scale_fix_confirm_title)) },
            text = { Text(stringResource(R.string.scale_fix_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmScale = false; vm.fixAllRecipes() }) { Text(stringResource(R.string.scale_fix_start)) }
            },
            dismissButton = { TextButton(onClick = { confirmScale = false }) { Text(stringResource(R.string.mealie_cancel)) } },
        )
    }
}

@Composable
private fun scaleFixStatus(state: ScaleFixState): String = when (state) {
    ScaleFixState.Idle -> stringResource(R.string.scale_fix_hint)
    is ScaleFixState.Running ->
        if (state.total == 0) stringResource(R.string.scale_fix_starting) else stringResource(R.string.scale_fix_running, state.done, state.total)
    is ScaleFixState.Done -> listOfNotNull(
        if (state.updated == 0) stringResource(R.string.scale_fix_nothing) else stringResource(R.string.scale_fix_done, state.updated, state.checked),
        if (state.failed > 0) stringResource(R.string.scale_fix_some_failed, state.failed) else null,
    ).joinToString("\n")
    ScaleFixState.Failed -> stringResource(R.string.scale_fix_error)
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
    val resources = LocalResources.current
    val current = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    // הרשימה נוצרת בזמן הבנייה מאותן שפות של Mealie (GenerateStringsTask).
    val supported = remember(resources) {
        resources.getStringArray(R.array.supported_locales)
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
                        onClick = {
                            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.language_system)) }
                }
                supported.forEach { locale ->
                    item {
                        ListItem(
                            onClick = {
                                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale.toLanguageTag()))
                                onDismiss()
                            },
                            supportingContent = { Text(locale.getDisplayName(current)) },
                        ) { Text(locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mealie_close)) } },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingRow(
    shapes: ListItemShapes,
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)? = null,
    highlighted: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
    val leading = @Composable {
        Icon(icon, null, tint = if (highlighted) MaterialTheme.colorScheme.primary else LocalContentColor.current)
    }
    val supporting: (@Composable () -> Unit)? = if (subtitle == null && below == null) {
        null
    } else {
        {
            Column {
                subtitle?.let { Text(it) }
                below?.invoke()
            }
        }
    }
    if (onClick != null) {
        SegmentedListItem(
            onClick = onClick,
            shapes = shapes,
            colors = groupItemColors(),
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supporting,
        ) { Text(title) }
    } else {
        SegmentedListItem(
            shapes = shapes,
            colors = groupItemColors(),
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supporting,
        ) { Text(title) }
    }
}

/** כל השורה מחליפה את המתג (toggleable), כך שקורא מסך מכריז על פריט אחד עם מצב. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SwitchRow(shapes: ListItemShapes, icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onChange,
        shapes = shapes,
        colors = groupItemColors(),
        leadingContent = { Icon(icon, null) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                thumbContent = if (checked) ({ Icon(Icons.Rounded.Check, null, Modifier.size(SwitchDefaults.IconSize)) }) else null,
            )
        },
    ) { Text(title) }
}

/** בחירה אחת מכמה: כפתורים מחוברים של Material 3 Expressive, במקום Segmented Button. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ConnectedChoice(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { i, (value, label) ->
            val checked = value == selected
            ToggleButton(
                checked = checked,
                // לחיצה על האפשרות הפעילה לא מבטלת אותה.
                onCheckedChange = { if (it) onSelect(value) },
                modifier = Modifier.weight(1f),
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                // הכפתורים יושבים על מיכל surfaceContainer, אז הלא-נבחרים צריכים גוון בולט יותר.
                colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                if (checked) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                }
                Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
