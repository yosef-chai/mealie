package io.github.yosefchai.mealie.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yosefchai.mealie.MainActivity
import io.github.yosefchai.mealie.R
import io.github.yosefchai.mealie.ui.AppViewModel
import io.github.yosefchai.mealie.ui.Connection
import io.github.yosefchai.mealie.ui.Screen
import io.github.yosefchai.mealie.ui.UpdateState

/**
 * "עוד": התפריט המלא של Mealie (המגירה שלו), ופעולות האפליקציה: התחברות, החלפת שרת, הגדרות.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MoreSheet(vm: AppViewModel, activity: MainActivity, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val connection by vm.connection.collectAsState()
    val settings by vm.settings.collectAsState()
    val update by vm.update.collectAsState()
    var tokenDialog by remember { mutableStateOf(false) }
    val about = (connection as? Connection.Ready)?.about

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            (update as? UpdateState.Available)?.let { u ->
                ListGroup(title = null) {
                    row { shapes ->
                        SegmentedListItem(
                            onClick = { vm.installUpdate(u.info); onDismiss() },
                            shapes = shapes,
                            leadingContent = { Icon(Icons.Rounded.SystemUpdate, null) },
                            supportingContent = { Text(stringResource(R.string.update_available_body, u.info.versionName)) },
                            colors = ListItemDefaults.segmentedColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                leadingContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                supportingContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        ) { Text(stringResource(R.string.update_available_title)) }
                    }
                }
            }

            ListGroup(title = null) {
                row {
                    SheetItem(it, Icons.AutoMirrored.Rounded.MenuOpen, stringResource(R.string.more_mealie_menu), stringResource(R.string.more_mealie_menu_hint)) {
                        onDismiss()
                        activity.web.openDrawer()
                    }
                }
                if (about?.enableOidc == true) {
                    row {
                        val provider = about.oidcProviderName.ifBlank { "OIDC" }
                        SheetItem(it, Icons.AutoMirrored.Rounded.Login, stringResource(R.string.more_oidc, provider), stringResource(R.string.more_oidc_hint)) {
                            onDismiss()
                            activity.startOidc()
                        }
                    }
                }
                row {
                    SheetItem(it, Icons.Rounded.Key, stringResource(R.string.more_token), stringResource(R.string.more_token_hint)) {
                        tokenDialog = true
                    }
                }
            }

            val s = settings
            ListGroup(title = stringResource(R.string.settings_servers)) {
                s?.servers?.forEach { server ->
                    val active = server.id == s.activeServer?.id
                    row { shapes ->
                        SegmentedListItem(
                            selected = active,
                            onClick = {
                                onDismiss()
                                if (!active) vm.switchServer(server.id)
                            },
                            shapes = shapes,
                            leadingContent = { Icon(if (active) Icons.Rounded.CheckCircle else Icons.Rounded.Dns, null) },
                            supportingContent = {
                                Text(listOfNotNull(server.externalUrl, server.internalUrl).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                        ) { Text(server.displayName) }
                    }
                }
                row {
                    SheetItem(it, Icons.Rounded.Add, stringResource(R.string.server_add), null) {
                        onDismiss()
                        vm.openScreen(Screen.ADD_SERVER)
                    }
                }
            }

            ListGroup(title = null) {
                row {
                    SheetItem(it, Icons.Rounded.Settings, stringResource(R.string.app_settings), null) {
                        onDismiss()
                        vm.openScreen(Screen.SETTINGS)
                    }
                }
            }
        }
    }

    if (tokenDialog) {
        TokenDialog(
            onSubmit = {
                tokenDialog = false
                vm.useApiToken(it)
                onDismiss()
                activity.web.load("/")
            },
            onDismiss = { tokenDialog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SheetItem(shapes: ListItemShapes, icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        leadingContent = { Icon(icon, null) },
        supportingContent = subtitle?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
    ) { Text(title) }
}
