package io.github.yosefchai.mealie.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import io.github.yosefchai.mealie.MainActivity
import io.github.yosefchai.mealie.R
import io.github.yosefchai.mealie.net.HostPolicy
import io.github.yosefchai.mealie.net.ProbeResult
import io.github.yosefchai.mealie.net.ServerUrl
import io.github.yosefchai.mealie.ui.AppViewModel
import io.github.yosefchai.mealie.ui.components.CertificateDialog
import io.github.yosefchai.mealie.ui.components.ProbeRow
import io.github.yosefchai.mealie.ui.theme.MealieOrange

/**
 * חיבור לשרת: כתובת מרחוק ו/או כתובת ברשת הביתית, בדיקה של שתיהן ושמירה.
 * סריקת QR דרך Google Code Scanner (בלי הרשאת מצלמה לאפליקציה).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingScreen(vm: AppViewModel, activity: MainActivity, firstRun: Boolean, onClose: () -> Unit) {
    val test by vm.test.collectAsState()
    var external by rememberSaveable { mutableStateOf("") }
    var internal by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var review by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val externalUrl = ServerUrl.parse(external)
    val internalUrl = ServerUrl.parse(internal)
    val canTest = (externalUrl != null || internalUrl != null) && !test.running
    val anyOk = listOfNotNull(test.internal, test.external).any { it is ProbeResult.Ok || it is ProbeResult.AuthGate }
    val publicCleartext = externalUrl?.let { !it.isHttps && !HostPolicy.isCleartextAllowed(it.host) } == true

    fun scan(into: (String) -> Unit) {
        GmsBarcodeScanning.getClient(context).startScan()
            .addOnSuccessListener { code -> code.rawValue?.let { into(it); vm.resetTest() } }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface),
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!firstRun) {
                Row(Modifier.fillMaxWidth()) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.mealie_back))
                    }
                }
            } else {
                Spacer(Modifier.height(48.dp))
            }

            Surface(shape = RoundedCornerShape(32.dp), color = MealieOrange, shadowElevation = 6.dp) {
                Image(
                    painterResource(R.drawable.ic_mealie_logo),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(androidx.compose.ui.graphics.Color.White),
                    modifier = Modifier.padding(22.dp).size(64.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(if (firstRun) R.string.onboarding_title else R.string.server_add),
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            Surface(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 2.dp,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AddressField(
                        value = external,
                        onValue = { external = it; vm.resetTest() },
                        label = stringResource(R.string.address_external),
                        hint = stringResource(R.string.address_external_hint),
                        leading = { Icon(Icons.Rounded.Public, null) },
                        onScan = { scan { external = it } },
                        error = if (publicCleartext) stringResource(R.string.address_cleartext_public) else null,
                    )
                    AddressField(
                        value = internal,
                        onValue = { internal = it; vm.resetTest() },
                        label = stringResource(R.string.address_internal),
                        hint = stringResource(R.string.address_internal_hint),
                        leading = { Icon(Icons.Rounded.Home, null) },
                        onScan = { scan { internal = it } },
                        error = null,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.server_name)) },
                        placeholder = { Text(stringResource(R.string.server_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (activity.needsLocalNetworkPermission(listOf(internalUrl, externalUrl))) {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Column(Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.local_network_explain), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = activity::requestLocalNetworkIfNeeded) {
                                    Text(stringResource(R.string.local_network_grant))
                                }
                            }
                        }
                    }

                    listOfNotNull(test.external, test.internal).forEach { r ->
                        ProbeRow(r) { cert -> review = cert.url.hostPort + "|" + cert.fingerprint + "|" + cert.canPin }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            onClick = { vm.testServer(internal, external) },
                            enabled = canTest && !publicCleartext,
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) {
                            if (test.running) LoadingIndicator(Modifier.size(24.dp)) else Text(stringResource(R.string.action_test))
                        }
                        Button(
                            onClick = { vm.saveServer(name, internal, external) },
                            enabled = anyOk,
                            modifier = Modifier.weight(1f).height(56.dp),
                            contentPadding = ButtonDefaults.ContentPadding,
                        ) {
                            Text(stringResource(R.string.action_connect))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.onboarding_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 480.dp),
            )
            Spacer(Modifier.height(24.dp).navigationBarsPadding())
        }
    }

    review?.split('|')?.takeIf { it.size == 3 }?.let { (hostPort, fp, canPin) ->
        CertificateDialog(
            hostPort = hostPort,
            fingerprint = fp,
            canTrust = canPin.toBoolean(),
            onTrust = {
                review = null
                vm.trustCertificate(hostPort, fp)
                vm.testServer(internal, external)
            },
            onDismiss = { review = null },
        )
    }
}

@Composable
private fun AddressField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    hint: String,
    leading: @Composable () -> Unit,
    onScan: () -> Unit,
    error: String?,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = { Text(hint) },
        leadingIcon = leading,
        trailingIcon = {
            IconButton(onClick = onScan) { Icon(Icons.Rounded.QrCodeScanner, stringResource(R.string.action_scan_qr)) }
        },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
}
