package io.github.yosefchai.mealie.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.yosefchai.mealie.R
import io.github.yosefchai.mealie.net.ProbeResult
import io.github.yosefchai.mealie.web.WebController
import io.github.yosefchai.mealie.web.WebError

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ContainedLoadingIndicator(Modifier.size(72.dp))
    }
}

/** אף כתובת לא ענתה כ-Mealie: הסבר לכל כתובת ופעולות מתאימות. */
@Composable
fun ConnectionProblem(
    results: List<ProbeResult>,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onTrust: (hostPort: String, fingerprint: String) -> Unit,
    needsLocalPermission: Boolean,
    onGrantLocal: () -> Unit,
) {
    var review by remember { mutableStateOf<ProbeResult.UntrustedCert?>(null) }
    review?.let { cert ->
        CertificateDialog(
            hostPort = cert.url.hostPort,
            fingerprint = cert.fingerprint,
            canTrust = cert.canPin,
            onTrust = {
                review = null
                onTrust(cert.url.hostPort, cert.fingerprint)
            },
            onDismiss = { review = null },
        )
    }
    StatePage(
        icon = Icons.Rounded.CloudOff,
        title = stringResource(R.string.connection_failed_title),
        body = stringResource(R.string.connection_failed_body),
    ) {
        results.forEach { ProbeRow(it) { r -> review = r } }
        if (needsLocalPermission) {
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onGrantLocal) {
                Icon(Icons.Rounded.WifiOff, null)
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.local_network_grant))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onSettings) {
                Icon(Icons.Rounded.Settings, null)
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.mealie_settings))
            }
            Button(onClick = onRetry) {
                Icon(Icons.Rounded.Refresh, null)
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

/** שגיאה בטעינת דף בתוך שרת שעובד (למשל ניתוק רגעי). */
@Composable
fun PageError(error: WebError, onRetry: () -> Unit) {
    val body = when (error.code) {
        WebController.BLOCKED_CLEARTEXT -> stringResource(R.string.cleartext_blocked, error.description)
        else -> stringResource(R.string.page_error_body)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        StatePage(icon = Icons.Rounded.ErrorOutline, title = stringResource(R.string.page_error_title), body = body) {
            Button(onClick = onRetry) {
                Icon(Icons.Rounded.Refresh, null)
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
fun ProbeRow(result: ProbeResult, onReview: (ProbeResult.UntrustedCert) -> Unit) {
    val (icon, text) = describe(result)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (result is ProbeResult.Ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(result.url.toString(), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (result is ProbeResult.UntrustedCert && result.canPin) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { onReview(result) }) {
                    Icon(Icons.Rounded.Lock, null)
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.cert_review))
                }
            }
        }
    }
}

@Composable
private fun describe(r: ProbeResult): Pair<ImageVector, String> = when (r) {
    is ProbeResult.Ok -> Icons.Rounded.CheckCircle to stringResource(R.string.probe_ok, r.about.version)
    is ProbeResult.AuthGate -> Icons.Rounded.Lock to stringResource(R.string.probe_gate)
    is ProbeResult.NotMealie -> Icons.Rounded.ErrorOutline to stringResource(R.string.probe_not_mealie, r.status.toString())
    is ProbeResult.UntrustedCert ->
        Icons.Rounded.GppMaybe to stringResource(if (r.canPin) R.string.probe_untrusted_private else R.string.probe_untrusted_public)
    is ProbeResult.CleartextBlocked -> Icons.Rounded.GppMaybe to stringResource(R.string.probe_cleartext)
    is ProbeResult.Unreachable -> Icons.Rounded.WifiOff to stringResource(R.string.probe_unreachable)
}

@Composable
fun StatePage(icon: ImageVector, title: String, body: String, actions: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(icon, null, Modifier.padding(20.dp).size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.height(20.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            actions()
        }
    }
}
