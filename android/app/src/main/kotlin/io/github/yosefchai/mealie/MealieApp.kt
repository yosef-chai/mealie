package io.github.yosefchai.mealie

import android.app.Application
import io.github.yosefchai.mealie.core.AppGraph
import io.github.yosefchai.mealie.core.Notifications
import io.github.yosefchai.mealie.update.UpdateWorker
import io.github.yosefchai.mealie.web.WebStartup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MealieApp : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        Notifications.createChannels(this)
        // אתחול Chromium ברקע מוקדם ככל האפשר, כדי שה-WebView הראשון ייפתח מהר.
        WebStartup.start(this)
        scope.launch { AppGraph.settings.loadPins() }
        UpdateWorker.schedule(this)
    }
}
