package io.github.yosefchai.mealie.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.yosefchai.mealie.MainActivity
import io.github.yosefchai.mealie.core.AppSettings
import io.github.yosefchai.mealie.ui.screens.MainShell
import io.github.yosefchai.mealie.ui.screens.OnboardingScreen
import io.github.yosefchai.mealie.ui.screens.SettingsScreen

/** שורש הממשק: הגדרה ראשונה, מסך ראשי או הגדרות, עם מעבר תנועה של Material. */
@Composable
fun MealieAppRoot(vm: AppViewModel, activity: MainActivity) {
    val settings by vm.settings.collectAsState()
    val connection by vm.connection.collectAsState()
    val screen by vm.screen.collectAsState()
    val s = settings ?: AppSettings()

    val target = when {
        settings == null -> Screen.MAIN
        connection is Connection.NeedsSetup || s.servers.isEmpty() -> Screen.ADD_SERVER
        else -> screen
    }

    BackHandler(enabled = target != Screen.MAIN && s.servers.isNotEmpty()) { vm.openScreen(Screen.MAIN) }

    // במסכים הנייטיב צבע הסמלים בשורת הסטטוס לפי ערכת הנושא; במסך הראשי MainShell קובע לפי Mealie.
    val view = LocalView.current
    val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    LaunchedEffect(target, lightSurface) {
        if (target != Screen.MAIN) {
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = lightSurface
                isAppearanceLightNavigationBars = lightSurface
            }
        }
    }

    AnimatedContent(
        targetState = target,
        transitionSpec = {
            val forward = targetState != Screen.MAIN
            (slideInHorizontally(tween(320)) { w -> if (forward) w / 4 else -w / 4 } + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(tween(320)) { w -> if (forward) -w / 6 else w / 6 } + fadeOut(tween(160)))
        },
        label = "screen",
    ) { current ->
        when (current) {
            Screen.MAIN -> MainShell(vm = vm, activity = activity)
            Screen.SETTINGS -> SettingsScreen(vm = vm, activity = activity)
            Screen.ADD_SERVER -> OnboardingScreen(
                vm = vm,
                activity = activity,
                firstRun = s.servers.isEmpty(),
                onClose = { vm.openScreen(Screen.SETTINGS) },
            )
        }
    }
}
