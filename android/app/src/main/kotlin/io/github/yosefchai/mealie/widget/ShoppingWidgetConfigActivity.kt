package io.github.yosefchai.mealie.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import io.github.yosefchai.mealie.MainActivity
import io.github.yosefchai.mealie.R
import io.github.yosefchai.mealie.core.AppGraph
import io.github.yosefchai.mealie.core.AppSettings
import io.github.yosefchai.mealie.ui.theme.MealieTheme
import kotlinx.coroutines.launch

/**
 * בחירת רשימת הקניות לווידג'ט (WS-4: הגדרה דרך המערכת, לא כפתור הגדרות בווידג'ט).
 * מוגדר כ-reconfigurable ו-configuration_optional: בלי בחירה, הווידג'ט מציג את הרשימה הראשונה.
 */
class ShoppingWidgetConfigActivity : AppCompatActivity() {

    private val widgetId by lazy {
        intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // ביטול (חזרה) לא מוסיף את הווידג'ט, לפי ההנחיות
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            val settings by AppGraph.settings.settings.collectAsState(initial = AppSettings())
            MealieTheme(settings.theme, settings.dynamicColor) { ConfigScreen() }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun ConfigScreen() {
        val scope = rememberCoroutineScope()
        var lists by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
        var failed by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            val glanceId = runCatching { GlanceAppWidgetManager(this@ShoppingWidgetConfigActivity).getGlanceIdBy(widgetId) }.getOrNull()
            selected = glanceId?.let { WidgetData.shoppingState(this@ShoppingWidgetConfigActivity, it)[WidgetData.LIST_ID] }
            val result = WidgetData.shoppingLists(this@ShoppingWidgetConfigActivity)
            failed = result == null
            lists = result.orEmpty()
            if (selected == null) selected = result?.firstOrNull()?.first
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.widget_choose_list)) },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.mealie_back))
                        }
                    },
                    actions = {
                        TextButton(enabled = selected != null, onClick = { scope.launch { save(selected!!) } }) {
                            Text(stringResource(R.string.mealie_save))
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val current = lists
                when {
                    current == null -> LoadingIndicator(Modifier.align(Alignment.Center))
                    failed || current.isEmpty() -> Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(if (failed) R.string.widget_sign_in else R.string.widget_no_lists),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        TextButton(onClick = {
                            startActivity(Intent(this@ShoppingWidgetConfigActivity, MainActivity::class.java))
                        }) { Text(stringResource(R.string.widget_open_app)) }
                    }
                    else -> LazyColumn(Modifier.fillMaxWidth()) {
                        items(current, key = { it.first }) { (id, name) ->
                            ListItem(
                                headlineContent = { Text(name.ifBlank { stringResource(R.string.mealie_shopping_lists) }) },
                                leadingContent = { Icon(Icons.Rounded.ShoppingCart, null) },
                                trailingContent = { RadioButton(selected = selected == id, onClick = null) },
                                modifier = Modifier.selectable(selected == id, role = Role.RadioButton) { selected = id },
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun save(listId: String) {
        val glanceId = runCatching { GlanceAppWidgetManager(this).getGlanceIdBy(widgetId) }.getOrNull()
        if (glanceId != null) {
            updateAppWidgetState(this, glanceId) {
                it[WidgetData.LIST_ID] = listId
                it.remove(WidgetData.SHOPPING)
            }
            ShoppingListWidget().update(this, glanceId)
            WidgetSync.schedule(this)
            WidgetData.refreshShopping(this, glanceId)
        }
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
