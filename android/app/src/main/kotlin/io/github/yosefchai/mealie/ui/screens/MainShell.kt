package io.github.yosefchai.mealie.ui.screens

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import io.github.yosefchai.mealie.MainActivity
import io.github.yosefchai.mealie.R
import io.github.yosefchai.mealie.ui.AppViewModel
import io.github.yosefchai.mealie.ui.Connection
import io.github.yosefchai.mealie.ui.UiEvent
import io.github.yosefchai.mealie.ui.components.CertificateDialog
import io.github.yosefchai.mealie.ui.components.ConnectionProblem
import io.github.yosefchai.mealie.ui.components.LoadingState
import io.github.yosefchai.mealie.ui.components.MoreSheet
import io.github.yosefchai.mealie.ui.components.UpdatePrompt
import io.github.yosefchai.mealie.web.Tab
import io.github.yosefchai.mealie.web.WebController
import kotlinx.coroutines.delay

private data class TabItem(val tab: Tab, val icon: ImageVector, val label: Int)

private val tabItems = listOf(
    TabItem(Tab.RECIPES, Icons.AutoMirrored.Rounded.MenuBook, R.string.mealie_recipes),
    TabItem(Tab.MEALPLAN, Icons.Rounded.CalendarMonth, R.string.mealie_meal_planner),
    TabItem(Tab.SHOPPING, Icons.Rounded.ShoppingCart, R.string.mealie_shopping_lists),
    TabItem(Tab.COOKBOOKS, Icons.Rounded.CollectionsBookmark, R.string.mealie_cookbooks),
    TabItem(Tab.MORE, Icons.Rounded.Menu, R.string.tab_more),
)

/**
 * המסך הראשי: ממשק Mealie ב-WebView, עם סרגל ניווט נייטיב.
 * טלפון: Short Navigation Bar בתחתית. טאבלט / מתקפל פתוח (600dp ומעלה): Wide Navigation Rail בצד.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainShell(vm: AppViewModel, activity: MainActivity) {
    val web = activity.web
    val st by web.state.collectAsState()
    val connection by vm.connection.collectAsState()
    val settings by vm.settings.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    val groupSlug = settings?.activeServer?.groupSlug

    LaunchedEffect(Unit) { activity.snackbar.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is UiEvent.Message -> snackbar.showSnackbar(activity.getString(e.text, e.arg.orEmpty()))
                UiEvent.Reload -> web.reload()
            }
        }
    }

    // צבעי המעטפת לפי הממשק בפועל, כדי שהמעבר בין הנייטיב לווב יהיה רציף.
    val webBackground = st.backgroundColor?.let(::Color) ?: MaterialTheme.colorScheme.surface
    val barColor = st.barColor?.let(::Color) ?: webBackground
    val view = LocalView.current
    LaunchedEffect(barColor, webBackground) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = barColor.luminance() > 0.5f
        controller.isAppearanceLightNavigationBars = webBackground.luminance() > 0.5f
    }

    val currentTab = Tab.forPath(st.path)
    val imeVisible = WindowInsets.isImeVisible
    val showBar = connection is Connection.Ready || connection is Connection.Offline
    val barVisible = showBar && !Tab.hidesBar(st.path) && !imeVisible

    // חזרה: פעיל רק כשיש לאן לחזור בתוך האפליקציה. בשורש המתכונים הוא כבוי,
    // ואז המערכת מציגה את אנימציית החזרה למסך הבית (predictive back).
    val atRecipesRoot = Tab.RECIPES.isRoot(st.path)
    val tabRoot = currentTab?.isRoot(st.path) == true
    BackHandler(enabled = moreOpen || (showBar && !atRecipesRoot && (st.canGoBack || currentTab != Tab.RECIPES))) {
        when {
            moreOpen -> moreOpen = false
            tabRoot && currentTab != Tab.RECIPES -> web.navigate(Tab.RECIPES.rootPath(groupSlug))
            web.goBack() -> Unit
            // אין היסטוריה (או שהקודם הוא דף הכניסה): לשורש הלשונית הנוכחית, או למתכונים.
            else -> web.navigate((currentTab?.takeIf { it != Tab.MORE } ?: Tab.RECIPES).rootPath(groupSlug))
        }
    }

    fun select(tab: Tab) {
        when {
            tab == Tab.MORE -> moreOpen = true
            // לחיצה חוזרת על הלשונית הפעילה: לראש הדף, ומשם לשורש הלשונית.
            tab == currentTab -> web.scrollToTopOrRoot(tab.rootPath(groupSlug))
            else -> web.navigate(tab.rootPath(groupSlug))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(webBackground)) {
        val wide = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (wide && barVisible) {
                WideNavigationRail(
                    colors = WideNavigationRailDefaults.colors(containerColor = webBackground),
                    arrangement = Arrangement.Center,
                ) {
                    tabItems.forEach { item ->
                        WideNavigationRailItem(
                            selected = item.tab == currentTab,
                            onClick = { select(item.tab) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { NavLabel(stringResource(item.label)) },
                            railExpanded = false,
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxSize()) {
                // אזור שורת הסטטוס בצבע הכותרת של Mealie.
                Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(barColor))
                Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
                    when (val c = connection) {
                        is Connection.Ready, is Connection.Offline -> WebContent(web)
                        is Connection.Failed -> ConnectionProblem(
                            results = c.results,
                            onRetry = vm::retry,
                            onSettings = { vm.openScreen(io.github.yosefchai.mealie.ui.Screen.SETTINGS) },
                            onTrust = { hp, fp -> vm.trustCertificate(hp, fp) },
                            needsLocalPermission = activity.needsLocalNetworkPermission(c.server.all),
                            onGrantLocal = activity::requestLocalNetworkIfNeeded,
                        )
                        else -> LoadingState()
                    }
                    // WebView חדש (חיבור ראשון, תצוגה מקורית, החלפת שרת) לבן עד ש-Mealie מצייר; מציגים טעינה במקום.
                    // הסימן: דיווח ה-theme מהדף (יש .v-application). דף שלא ידווח (שער אימות, WebView בלי הגשר,
                    // דף שגיאה של השרת) משתחרר אחרי זמן חסד מסוף הטעינה: קצר בדף זר, ארוך יותר ב-Mealie עצמו.
                    var settled by remember { mutableStateOf(false) }
                    LaunchedEffect(st.firstPaint, st.loading, st.foreignPage) {
                        settled = false
                        if (st.firstPaint && !st.loading) {
                            delay(if (st.foreignPage || !web.bridgeSupported) 1_000 else 8_000)
                            settled = true
                        }
                    }
                    val contentShown = st.dark != null || settled
                    androidx.compose.animation.AnimatedVisibility(
                        visible = (connection is Connection.Ready || connection is Connection.Offline) &&
                            !contentShown && st.error == null && st.untrusted == null,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Box(Modifier.fillMaxSize().background(webBackground)) { LoadingState() }
                    }
                    if (st.loading && (connection is Connection.Ready)) {
                        LinearWavyProgressIndicator(
                            progress = { st.progress / 100f },
                            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = connection is Connection.Offline,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        OfflinePill()
                    }
                    st.error?.let { err ->
                        if (connection is Connection.Ready) {
                            io.github.yosefchai.mealie.ui.components.PageError(err, onRetry = web::reload)
                        }
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
                }
                AnimatedVisibility(
                    visible = !wide && barVisible,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    ShortNavigationBar(containerColor = webBackground) {
                        tabItems.forEach { item ->
                            ShortNavigationBarItem(
                                selected = item.tab == currentTab,
                                onClick = { select(item.tab) },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { NavLabel(stringResource(item.label)) },
                            )
                        }
                    }
                }
            }
        }
    }

    st.untrusted?.let { cert ->
        CertificateDialog(
            hostPort = cert.hostPort,
            fingerprint = cert.fingerprint,
            canTrust = cert.canPin,
            onTrust = {
                web.clearUntrusted()
                vm.trustCertificate(cert.hostPort, cert.fingerprint)
                web.reload()
            },
            onDismiss = web::clearUntrusted,
        )
    }

    if (moreOpen) {
        MoreSheet(vm = vm, activity = activity, onDismiss = { moreOpen = false })
    }
    UpdatePrompt(vm = vm, activity = activity)
}

/**
 * תווית לשונית: שורה אחת ממורכזת. תווית ארוכה ("רשימות קניות") קטנה עד שהיא נכנסת,
 * במקום לגלוש לשורה שנייה שנחתכת ומשאירה מילה אחת צמודה לצד.
 */
@Composable
private fun NavLabel(text: String) {
    val style = LocalTextStyle.current
    Text(
        text = text,
        // רווח בין תוויות שכנות; בלעדיו "תכנון ארוחות רשימות קניות" נקרא כשורה אחת במסך צר
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}

@Composable
private fun WebContent(web: WebController) {
    AndroidView(
        factory = {
            (web.container.parent as? ViewGroup)?.removeView(web.container)
            web.container
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun OfflinePill() {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CloudOff, contentDescription = null)
            Spacer(Modifier.padding(start = 8.dp))
            Text(stringResource(R.string.offline_banner), style = MaterialTheme.typography.labelLarge)
        }
    }
}
