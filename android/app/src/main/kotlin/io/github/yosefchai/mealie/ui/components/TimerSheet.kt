package io.github.yosefchai.mealie.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yosefchai.mealie.R
import io.github.yosefchai.mealie.web.TimerRequest
import io.github.yosefchai.mealie.web.Timers

/** "אופים 20-25 דקות": בוחרים לכמה זמן להפעיל את הטיימר. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerSheet(request: TimerRequest, onStart: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Timer, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.timer_choose_title), style = MaterialTheme.typography.titleLarge)
                    request.recipe?.let {
                        Text(
                            if (request.step != null) stringResource(R.string.timer_label_step, it, request.step) else it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.timer_choose_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { onStart(request.minSeconds) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text(Timers.format(request.minSeconds), style = MaterialTheme.typography.titleMedium)
                }
                Button(onClick = { onStart(request.maxSeconds) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text(Timers.format(request.maxSeconds), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
