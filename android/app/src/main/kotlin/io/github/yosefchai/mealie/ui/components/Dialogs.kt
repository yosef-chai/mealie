package io.github.yosefchai.mealie.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.yosefchai.mealie.MainActivity
import io.github.yosefchai.mealie.R
import io.github.yosefchai.mealie.net.CertPins
import io.github.yosefchai.mealie.ui.AppViewModel
import io.github.yosefchai.mealie.ui.UpdateState

/** אישור תעודה בחתימה עצמית: רק לכתובת פרטית, אחרי הצגת טביעת האצבע המלאה. */
@Composable
fun CertificateDialog(hostPort: String, fingerprint: String, canTrust: Boolean, onTrust: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.GppMaybe, null) },
        title = { Text(stringResource(R.string.cert_title)) },
        text = {
            Column {
                Text(stringResource(if (canTrust) R.string.cert_body_private else R.string.cert_body_public, hostPort))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.cert_fingerprint), style = MaterialTheme.typography.labelLarge)
                Text(CertPins.pretty(fingerprint), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.cert_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (canTrust) TextButton(onClick = onTrust) { Text(stringResource(R.string.cert_trust)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mealie_cancel)) } },
    )
}

@Composable
fun TokenDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Key, null) },
        title = { Text(stringResource(R.string.mealie_api_token)) },
        text = {
            Column {
                Text(stringResource(R.string.token_body))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.mealie_api_token)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(token) }, enabled = token.isNotBlank()) { Text(stringResource(R.string.mealie_login)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mealie_cancel)) } },
    )
}

/** הודעת עדכון בתוך האפליקציה: עדכון / אחר כך / דלג על הגרסה, עם התקדמות ההורדה. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdatePrompt(vm: AppViewModel, activity: MainActivity) {
    val state by vm.update.collectAsState()
    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = vm::dismissUpdate,
            icon = { Icon(Icons.Rounded.SystemUpdate, null) },
            title = { Text(stringResource(R.string.update_available_title)) },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.update_available_body, s.info.versionName))
                    s.info.notes?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it.take(4000), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.installUpdate(s.info) }) { Text(stringResource(R.string.mealie_update)) } },
            dismissButton = {
                TextButton(onClick = { vm.skipUpdate(s.info) }) { Text(stringResource(R.string.update_skip)) }
            },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Rounded.SystemUpdate, null) },
            title = { Text(stringResource(R.string.update_downloading)) },
            text = {
                LinearWavyProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {},
        )
        is UpdateState.NeedsPermission -> AlertDialog(
            onDismissRequest = vm::dismissUpdate,
            icon = { Icon(Icons.Rounded.SystemUpdate, null) },
            title = { Text(stringResource(R.string.update_permission_title)) },
            text = { Text(stringResource(R.string.update_permission_body)) },
            confirmButton = { TextButton(onClick = activity::openInstallPermission) { Text(stringResource(R.string.mealie_settings)) } },
            dismissButton = { TextButton(onClick = vm::dismissUpdate) { Text(stringResource(R.string.mealie_cancel)) } },
        )
        is UpdateState.Error -> AlertDialog(
            onDismissRequest = vm::dismissUpdate,
            title = { Text(stringResource(R.string.update_failed_title)) },
            text = { Text(stringResource(R.string.update_failed_body)) },
            confirmButton = {
                s.info?.let { info -> TextButton(onClick = { vm.installUpdate(info) }) { Text(stringResource(R.string.action_retry)) } }
            },
            dismissButton = { TextButton(onClick = vm::dismissUpdate) { Text(stringResource(R.string.mealie_close)) } },
        )
        else -> Unit
    }
}
