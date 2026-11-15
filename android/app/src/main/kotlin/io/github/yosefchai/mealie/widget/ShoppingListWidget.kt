package io.github.yosefchai.mealie.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.CheckboxDefaults
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ToggleableStateKey
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import io.github.yosefchai.mealie.R
import io.github.yosefchai.mealie.net.MealieJson

/**
 * רשימת קניות על מסך הבית: מה נשאר לקנות, וסימון מוצר בלחיצה בלי לפתוח את האפליקציה.
 * הסימון מוצג מיד ונשמר בשרת ברקע; אם השמירה נכשלת הוא מתבטל ומוצגת הודעה.
 */
class ShoppingListWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(WidgetSizes.all)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ctx = context.localized()
        // ווידג'ט חדש, או נתונים ישנים: מרעננים ברקע ומציגים את מה שיש
        val initial = WidgetData.decodeShopping(WidgetData.shoppingState(context, id))
        if (initial == null || System.currentTimeMillis() - maxOf(initial.updatedAt, initial.checkedAt) > STALE_MS) {
            WidgetSync.refreshSoon(context)
        }
        provideContent {
            val prefs = currentState<Preferences>()
            WidgetTheme { ShoppingContent(ctx, WidgetData.decodeShopping(prefs)) }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val ctx = context.localized()
        val sample = listOf(R.string.widget_preview_item_1, R.string.widget_preview_item_2, R.string.widget_preview_item_3, R.string.widget_preview_item_4).map(ctx::getString)
        val snapshot = ShoppingSnapshot(
            listId = "preview",
            listName = ctx.getString(R.string.mealie_shopping_lists),
            items = sample.mapIndexed { i, t -> ShoppingItem("p$i", t, checked = i == sample.lastIndex, position = i) },
            updatedAt = System.currentTimeMillis(),
        )
        provideContent { WidgetTheme { ShoppingContent(ctx, snapshot, preview = true) } }
    }

    companion object {
        const val STALE_MS = 15 * 60_000L
    }
}

class ShoppingListWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShoppingListWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetSync.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetSync.cancelIfUnused(context)
    }
}

private val ItemIdKey = ActionParameters.Key<String>("item_id")

/** סימון או ביטול סימון של מוצר. ToggleableStateKey מחזיק את המצב החדש של תיבת הסימון. */
class ToggleItemAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val itemId = parameters[ItemIdKey] ?: return
        val checked = parameters[ToggleableStateKey] ?: return
        val before = WidgetData.decodeShopping(WidgetData.shoppingState(context, glanceId)) ?: return
        fun save(s: ShoppingSnapshot) = MealieJson.encodeToString(ShoppingSnapshot.serializer(), s)
        val stamp = java.time.Instant.now().toString()
        val optimistic = before.copy(
            items = before.items.map { if (it.id == itemId) it.copy(checked = checked, updatedAt = stamp) else it },
            problem = null,
        )
        updateAppWidgetState(context, glanceId) { it[WidgetData.SHOPPING] = save(optimistic) }
        ShoppingListWidget().update(context, glanceId)

        if (!WidgetData.setChecked(context, itemId, checked)) {
            updateAppWidgetState(context, glanceId) { it[WidgetData.SHOPPING] = save(before.copy(problem = WidgetProblem.SYNC_FAILED)) }
            ShoppingListWidget().update(context, glanceId)
            return
        }
        // שאר המכשירים בבית עשויים לשנות את הרשימה; מושכים את המצב העדכני מהשרת
        WidgetData.refreshShopping(context, glanceId)
    }
}

class RefreshShoppingAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetData.refreshShopping(context, glanceId)
    }
}

@Composable
private fun ShoppingContent(ctx: Context, snapshot: ShoppingSnapshot?, preview: Boolean = false) {
    val size = LocalSize.current
    val compact = size.height < 100.dp
    val listPath = snapshot?.listId?.takeIf { !preview }?.let { "/shopping-lists/$it" } ?: "/shopping-lists"
    val open = openPath(LocalContext.current, listPath)
    val title = snapshot?.listName?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.mealie_shopping_lists)

    if (compact) {
        CompactRow(ctx, title, snapshot, open)
        return
    }
    Scaffold(
        titleBar = {
            TitleBar(
                startIcon = ImageProvider(R.drawable.ic_widget_cart),
                title = if (size.width >= 180.dp) title else "",
                iconColor = GlanceTheme.colors.primary,
                textColor = GlanceTheme.colors.onSurface,
                actions = {
                    CircleIconButton(
                        imageProvider = ImageProvider(R.drawable.ic_widget_refresh),
                        contentDescription = ctx.getString(R.string.widget_refresh),
                        onClick = actionRunCallback<RefreshShoppingAction>(),
                        backgroundColor = null,
                        contentColor = GlanceTheme.colors.onSurfaceVariant,
                    )
                    CircleIconButton(
                        imageProvider = ImageProvider(R.drawable.ic_widget_add),
                        contentDescription = ctx.getString(R.string.widget_open_list),
                        onClick = open,
                        backgroundColor = null,
                        contentColor = GlanceTheme.colors.onSurfaceVariant,
                    )
                },
            )
        },
        horizontalPadding = 12.dp,
        modifier = GlanceModifier.clickable(open),
    ) {
        when {
            snapshot == null -> WidgetMessage(R.drawable.ic_widget_cart, ctx.getString(R.string.mealie_loading), null, null, null, compact = false)
            snapshot.items.isEmpty() && snapshot.problem != null && snapshot.problem != WidgetProblem.SYNC_FAILED ->
                ProblemMessage(ctx, snapshot.problem, open)
            snapshot.unchecked.isEmpty() -> WidgetMessage(
                R.drawable.ic_widget_done,
                ctx.getString(R.string.widget_all_done),
                ctx.getString(R.string.widget_all_done_body),
                null,
                open,
                compact = false,
            )
            else -> ItemList(ctx, snapshot, preview)
        }
    }
}

@Composable
private fun ProblemMessage(ctx: Context, problem: WidgetProblem, open: androidx.glance.action.Action) {
    val retry = actionRunCallback<RefreshShoppingAction>()
    when (problem) {
        WidgetProblem.SIGNED_OUT, WidgetProblem.NO_SERVER ->
            WidgetMessage(R.drawable.ic_widget_cart, problemText(ctx, problem), null, ctx.getString(R.string.widget_open_app), openApp(LocalContext.current), compact = false)
        WidgetProblem.NO_LISTS ->
            WidgetMessage(R.drawable.ic_widget_cart, problemText(ctx, problem), null, ctx.getString(R.string.widget_open_list), open, compact = false)
        else -> WidgetMessage(R.drawable.ic_widget_cloud_off, problemText(ctx, problem), null, ctx.getString(R.string.action_retry), retry, compact = false)
    }
}

@Composable
private fun ItemList(ctx: Context, snapshot: ShoppingSnapshot, preview: Boolean) {
    val unchecked = snapshot.unchecked
    val checked = snapshot.checked.take(MAX_CHECKED)
    Column(GlanceModifier.fillMaxSize()) {
        // הודעה קטנה כשהשמירה נכשלה או שאין חיבור, בלי להסתיר את הרשימה
        snapshot.problem?.let { p ->
            Text(
                problemText(ctx, p),
                style = TextStyle(color = GlanceTheme.colors.error, fontSize = 12.sp),
                maxLines = 1,
                modifier = GlanceModifier.padding(horizontal = 4.dp, vertical = 2.dp).clickable(actionRunCallback<RefreshShoppingAction>()),
            )
        }
        LazyColumn(GlanceModifier.fillMaxSize()) {
            items(unchecked, itemId = { it.id.hashCode().toLong() }) { item -> ItemRow(item, preview) }
            if (checked.isNotEmpty()) {
                item(itemId = -1L) {
                    Text(
                        ctx.getString(R.string.widget_checked_count, snapshot.checked.size),
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        modifier = GlanceModifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
                    )
                }
                items(checked, itemId = { it.id.hashCode().toLong() xor 0x5f5f }) { item -> ItemRow(item, preview) }
            }
            item(itemId = -2L) { Spacer(GlanceModifier.height(8.dp)) }
        }
    }
}

@Composable
private fun ItemRow(item: ShoppingItem, preview: Boolean) {
    CheckBox(
        checked = item.checked,
        onCheckedChange = if (preview) null else actionRunCallback<ToggleItemAction>(actionParametersOf(ItemIdKey to item.id)),
        text = item.text,
        style = TextStyle(
            color = if (item.checked) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
            fontSize = 15.sp,
            textDecoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None,
        ),
        // ברירת המחדל לוקחת את הצבעים מ-GlanceTheme (כולל צבעים דינמיים); צבעי משאב לא מתקבלים כאן
        colors = CheckboxDefaults.colors(),
        maxLines = 2,
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

/** 2x1: שם הרשימה וכמה מוצרים נשארו. */
@Composable
private fun CompactRow(ctx: Context, title: String, snapshot: ShoppingSnapshot?, open: androidx.glance.action.Action) {
    Row(
        modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).systemCorners()
            .padding(horizontal = 14.dp).clickable(open),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            GlanceModifier.size(32.dp).background(GlanceTheme.colors.primaryContainer).cornerRadius(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(ImageProvider(R.drawable.ic_widget_cart), null, colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer), modifier = GlanceModifier.size(18.dp))
        }
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(title, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            val sub = when {
                snapshot == null -> ctx.getString(R.string.mealie_loading)
                snapshot.items.isEmpty() && snapshot.problem != null -> problemText(ctx, snapshot.problem)
                snapshot.unchecked.isEmpty() -> ctx.getString(R.string.widget_all_done)
                else -> ctx.getString(R.string.widget_items_left, snapshot.unchecked.size)
            }
            Text(sub, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
        }
    }
}

private const val MAX_CHECKED = 15
