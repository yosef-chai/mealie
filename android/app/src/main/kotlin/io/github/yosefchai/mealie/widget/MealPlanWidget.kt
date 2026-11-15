package io.github.yosefchai.mealie.widget

import android.content.Context
import android.graphics.BitmapFactory
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
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
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
import androidx.glance.text.TextStyle
import io.github.yosefchai.mealie.R
import io.github.yosefchai.mealie.web.Tab
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "מה אוכלים היום": הארוחות המתוכננות להיום, עם תמונת המנה העיקרית ופתיחה של המתכון בלחיצה. */
class MealPlanWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(WidgetSizes.all)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ctx = context.localized()
        val initial = WidgetData.decodeMeals(androidx.glance.appwidget.state.getAppWidgetState(context, androidx.glance.state.PreferencesGlanceStateDefinition, id))
        // התוכן של אתמול לא רלוונטי: אחרי חצות, או כשהנתונים ישנים, מרעננים
        if (initial == null || (initial.date != LocalDate.now().toString() && System.currentTimeMillis() - initial.checkedAt > 60_000) || System.currentTimeMillis() - maxOf(initial.updatedAt, initial.checkedAt) > ShoppingListWidget.STALE_MS) {
            WidgetSync.refreshSoon(context)
        }
        provideContent {
            val prefs = currentState<Preferences>()
            WidgetTheme { MealsContent(ctx, WidgetData.decodeMeals(prefs)) }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val ctx = context.localized()
        val snapshot = MealsSnapshot(
            date = LocalDate.now().toString(),
            meals = listOf(
                PlannedMeal("breakfast", ctx.getString(R.string.widget_preview_meal_breakfast)),
                PlannedMeal("dinner", ctx.getString(R.string.widget_preview_meal)),
            ),
            updatedAt = System.currentTimeMillis(),
        )
        provideContent { WidgetTheme { MealsContent(ctx, snapshot) } }
    }
}

class MealPlanWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MealPlanWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetSync.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetSync.cancelIfUnused(context)
    }
}

class RefreshMealsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetData.refreshMeals(context, glanceId)
    }
}

internal fun mealTypeLabel(ctx: Context, type: String): String = ctx.getString(
    when (type) {
        "breakfast" -> R.string.mealie_breakfast
        "lunch" -> R.string.mealie_lunch
        "side" -> R.string.mealie_side
        "snack" -> R.string.mealie_snack
        "drink" -> R.string.mealie_drink
        "dessert" -> R.string.mealie_dessert
        else -> R.string.mealie_dinner
    },
)

@Composable
private fun MealsContent(ctx: Context, snapshot: MealsSnapshot?) {
    val size = LocalSize.current
    val context = LocalContext.current
    val planner = openPath(context, Tab.MEALPLAN.rootPath(null))
    val today = LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(ctx.resources.configuration.locales[0]))
    // נתונים של יום אחר לא מוצגים כ"היום"
    val meals = snapshot?.takeIf { it.date == LocalDate.now().toString() }?.meals

    if (size.height < 100.dp) {
        CompactMeal(ctx, meals, snapshot, planner)
        return
    }
    Scaffold(
        titleBar = {
            TitleBar(
                startIcon = ImageProvider(R.drawable.ic_widget_restaurant),
                title = if (size.width >= 180.dp) ctx.getString(R.string.widget_meals_title) else "",
                iconColor = GlanceTheme.colors.primary,
                textColor = GlanceTheme.colors.onSurface,
                actions = {
                    CircleIconButton(
                        imageProvider = ImageProvider(R.drawable.ic_widget_refresh),
                        contentDescription = ctx.getString(R.string.widget_refresh),
                        onClick = actionRunCallback<RefreshMealsAction>(),
                        backgroundColor = null,
                        contentColor = GlanceTheme.colors.onSurfaceVariant,
                    )
                    CircleIconButton(
                        imageProvider = ImageProvider(R.drawable.ic_widget_calendar),
                        contentDescription = ctx.getString(R.string.mealie_meal_planner),
                        onClick = planner,
                        backgroundColor = null,
                        contentColor = GlanceTheme.colors.onSurfaceVariant,
                    )
                },
            )
        },
        horizontalPadding = 12.dp,
    ) {
        when {
            snapshot == null -> WidgetMessage(R.drawable.ic_widget_restaurant, ctx.getString(R.string.mealie_loading), null, null, null, compact = false)
            meals.isNullOrEmpty() && snapshot.problem != null -> when (snapshot.problem) {
                WidgetProblem.SIGNED_OUT, WidgetProblem.NO_SERVER ->
                    WidgetMessage(R.drawable.ic_widget_restaurant, problemText(ctx, snapshot.problem), null, ctx.getString(R.string.widget_open_app), openApp(context), compact = false)
                else -> WidgetMessage(R.drawable.ic_widget_cloud_off, problemText(ctx, snapshot.problem), null, ctx.getString(R.string.action_retry), actionRunCallback<RefreshMealsAction>(), compact = false)
            }
            meals.isNullOrEmpty() -> WidgetMessage(
                R.drawable.ic_widget_calendar,
                ctx.getString(R.string.widget_nothing_planned),
                today,
                ctx.getString(R.string.widget_plan_meal),
                planner,
                compact = false,
            )
            else -> MealList(ctx, meals, snapshot.heroImage, today, showHero = size.height >= 220.dp)
        }
    }
}

@Composable
private fun MealList(ctx: Context, meals: List<PlannedMeal>, heroFile: String?, today: String, showHero: Boolean) {
    val context = LocalContext.current
    val hero = if (showHero) heroFile?.let { f -> runCatching { BitmapFactory.decodeFile(File(f).absolutePath) }.getOrNull() } else null
    val heroMeal = meals.firstOrNull { it.type == "dinner" && it.recipeId != null } ?: meals.firstOrNull { it.recipeId != null }
    LazyColumn(GlanceModifier.fillMaxSize()) {
        item(itemId = -1L) {
            Text(today, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1, modifier = GlanceModifier.padding(start = 4.dp, bottom = 6.dp))
        }
        if (hero != null && heroMeal != null) {
            item(itemId = -2L) {
                val action = heroMeal.recipePath?.let { openPath(context, it) }
                Box(
                    GlanceModifier.fillMaxWidth().height(120.dp).cornerRadius(16.dp).let { m -> action?.let { m.clickable(it) } ?: m },
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Image(ImageProvider(hero), contentDescription = heroMeal.title, contentScale = ContentScale.Crop, modifier = GlanceModifier.fillMaxSize())
                }
            }
            item(itemId = -3L) { Spacer(GlanceModifier.height(6.dp)) }
        }
        items(meals, itemId = { (it.type + it.title).hashCode().toLong() }) { meal -> MealRow(ctx, meal) }
    }
}

@Composable
private fun MealRow(ctx: Context, meal: PlannedMeal) {
    val context = LocalContext.current
    val action: Action? = meal.recipePath?.let { openPath(context, it) }
    Row(
        GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).let { m -> action?.let { m.clickable(it) } ?: m },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            GlanceModifier.size(36.dp).background(GlanceTheme.colors.secondaryContainer).cornerRadius(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(ImageProvider(R.drawable.ic_widget_restaurant), null, colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer), modifier = GlanceModifier.size(18.dp))
        }
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(mealTypeLabel(ctx, meal.type), style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            Text(meal.title, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp), maxLines = 2)
        }
    }
}

/** 2x1: הארוחה העיקרית של היום בשורה אחת. */
@Composable
private fun CompactMeal(ctx: Context, meals: List<PlannedMeal>?, snapshot: MealsSnapshot?, planner: Action) {
    val context = LocalContext.current
    val main = meals?.firstOrNull { it.type == "dinner" } ?: meals?.firstOrNull()
    val action = main?.recipePath?.let { openPath(context, it) } ?: planner
    Row(
        GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).systemCorners()
            .padding(horizontal = 14.dp).clickable(action),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(GlanceModifier.size(32.dp).background(GlanceTheme.colors.primaryContainer).cornerRadius(16.dp), contentAlignment = Alignment.Center) {
            Image(ImageProvider(R.drawable.ic_widget_restaurant), null, colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer), modifier = GlanceModifier.size(18.dp))
        }
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            val top = main?.let { mealTypeLabel(ctx, it.type) } ?: ctx.getString(R.string.widget_meals_title)
            val bottom = when {
                main != null -> main.title
                snapshot == null -> ctx.getString(R.string.mealie_loading)
                snapshot.problem != null -> problemText(ctx, snapshot.problem)
                else -> ctx.getString(R.string.widget_nothing_planned)
            }
            Text(top, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
            Text(bottom, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        }
    }
}
