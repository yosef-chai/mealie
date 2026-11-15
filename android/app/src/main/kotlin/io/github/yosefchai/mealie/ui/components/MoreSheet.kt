package io.github.yosefchai.mealie.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSheet(vm: AppViewModel, activity: MainActivity, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val connection by vm.connection.collectAsState()
    val settings by vm.settings.collectAsState()
    val update by vm.update.collectAsState()
    var tokenDialog by remember { mutableStateOf(false) }
    val about = (connection as? Connection.Ready)?.about

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            (update as? UpdateState.Available)?.let { u ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    onClick = { vm.installUpdate(u.info); onDismiss() },
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.update_available_title)) },
                        supportingContent = { Text(stringResource(R.string.update_available_body, u.info.versionName)) },
                        leadingContent = { Icon(Icons.Rounded.SystemUpdate, null) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    )
                }
            }

            SheetItem(Icons.AutoMirrored.Rounded.MenuOpen, stringResource(R.string.more_mealie_menu), stringResource(R.string.more_mealie_menu_hint)) {
                onDismiss()
                activity.web.openDrawer()
            }
            if (about?.enableOidc == true) {
                val provider = about.oidcProviderName.ifBlank { "OIDC" }
                SheetItem(Icons.AutoMirrored.Rounded.Login, stringResource(R.string.more_oidc, provider), stringResource(R.string.more_oidc_hint)) {
                    onDismiss()
                    activity.startOidc()
                }
            }
            SheetItem(Icons.Rounded.Key, stringResource(R.string.more_token), stringResource(R.string.more_token_hint)) {
                tokenDialog = true
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp))
            Text(
                stringResource(R.string.settings_servers),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            val s = settings
            s?.servers?.forEach { server ->
                val active = server.id == s.activeServer?.id
                SheetItem(
                    icon = if (active) Icons.Rounded.CheckCircle else Icons.Rounded.Dns,
                    title = server.displayName,
                    subtitle = listOfNotNull(server.externalUrl, server.internalUrl).joinToString(" · "),
                ) {
                    onDismiss()
                    if (!active) vm.switchServer(server.id)
                }
            }
            SheetItem(Icons.Rounded.Add, stringResource(R.string.server_add), null) {
                onDismiss()
                vm.openScreen(Screen.ADD_SERVER)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp))
            SheetItem(Icons.Rounded.Settings, stringResource(R.string.app_settings), null) {
                onDismiss()
                vm.openScreen(Screen.SETTINGS)
            }
            Spacer(Modifier.height(8.dp))
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

@Composable
private fun SheetItem(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = subtitle?.let { { Text(it, maxLines = 2) } },
            leadingContent = { Icon(icon, null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        )
    }
}
