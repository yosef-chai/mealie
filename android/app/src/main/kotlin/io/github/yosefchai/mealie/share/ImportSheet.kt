package io.github.yosefchai.mealie.share

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yosefchai.mealie.R
import io.github.yosefchai.mealie.importer.ImportState
import io.github.yosefchai.mealie.importer.ImportedRecipe
import io.github.yosefchai.mealie.importer.RecipeImporter
import io.github.yosefchai.mealie.net.Images

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(
    state: ImportState,
    url: String,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
    onImportAgain: () -> Unit,
    onOpenInApp: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    if (state is ImportState.NeedsApp) {
        LaunchedEffect(Unit) { onOpenInApp() }
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            SourceRow(url)
            Spacer(Modifier.height(20.dp))
            AnimatedContent(
                targetState = state,
                contentKey = { it::class },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "import",
            ) { s ->
                when (s) {
                    is ImportState.Done -> DoneContent(s.recipe, onOpen, onDismiss)
                    is ImportState.Exists -> ExistsContent(s, onOpen, onImportAgain)
                    is ImportState.Failed -> FailedContent(s, onRetry, onOpenInApp)
                    else -> ProgressContent(s)
                }
            }
        }
    }
}

@Composable
private fun SourceRow(url: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                Icons.Rounded.Restaurant,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(10.dp).size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.import_title), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Language, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(
                    RecipeImporter.hostOf(url),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProgressContent(state: ImportState) {
    val label = when (state) {
        ImportState.Connecting -> stringResource(R.string.import_connecting)
        is ImportState.Reading -> stringResource(R.string.import_reading)
        ImportState.Saving -> stringResource(R.string.import_saving)
        ImportState.Finishing -> stringResource(R.string.import_finishing)
        else -> ""
    }
    val progress = when (state) {
        ImportState.Connecting -> 0.15f
        is ImportState.Reading -> 0.4f
        ImportState.Saving -> 0.7f
        else -> 0.9f
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DoneContent(recipe: ImportedRecipe, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        val bitmap by produceState<android.graphics.Bitmap?>(null, recipe.imageUrl) {
            value = recipe.imageUrl?.let { Images.load(it, null, 900) }
        }
        Surface(shape = MaterialTheme.shapes.largeIncreased, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    bitmap?.let {
                        Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    } ?: Icon(Icons.Rounded.Restaurant, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.mealie_recipe_created), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(recipe.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.import_counts, recipe.ingredients, recipe.steps),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (recipe.partial) {
            Spacer(Modifier.height(12.dp))
            Notice(Icons.Rounded.WarningAmber, stringResource(R.string.import_partial))
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.mealie_close)) }
            if (recipe.partial) {
                FilledTonalButton(onClick = { onOpen(recipe.path + "?edit=true") }) { Text(stringResource(R.string.mealie_edit)) }
            }
            Button(onClick = { onOpen(recipe.path) }) { Text(stringResource(R.string.import_open)) }
        }
    }
}

@Composable
private fun ExistsContent(state: ImportState.Exists, onOpen: (String) -> Unit, onImportAgain: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Notice(Icons.Rounded.Info, stringResource(R.string.import_exists_body, state.name), title = stringResource(R.string.import_exists_title))
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            TextButton(onClick = onImportAgain) { Text(stringResource(R.string.import_again)) }
            Button(onClick = { onOpen(state.path) }) { Text(stringResource(R.string.import_open_existing)) }
        }
    }
}

@Composable
private fun FailedContent(state: ImportState.Failed, onRetry: () -> Unit, onOpenInApp: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        val body = when {
            state.blocked -> stringResource(R.string.import_blocked_body)
            else -> stringResource(R.string.import_failed_body)
        }
        Notice(Icons.Rounded.ErrorOutline, body, title = stringResource(R.string.import_failed_title), error = true)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            Button(onClick = onOpenInApp) { Text(stringResource(R.string.import_open_in_app)) }
        }
    }
}

@Composable
private fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, body: String, title: String? = null, error: Boolean = false) {
    val container = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(shape = MaterialTheme.shapes.large, color = container, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = content, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                title?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, color = content)
                    Spacer(Modifier.height(2.dp))
                }
                Text(body, style = MaterialTheme.typography.bodyMedium, color = content)
            }
        }
    }
}
