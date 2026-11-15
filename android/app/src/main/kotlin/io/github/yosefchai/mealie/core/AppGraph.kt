package io.github.yosefchai.mealie.core

import android.content.Context
import io.github.yosefchai.mealie.net.NetworkMonitor
import io.github.yosefchai.mealie.net.ServerProbe

/** תלויות משותפות לכל האפליקציה (מספיק קטן כדי לוותר על ספריית DI). */
object AppGraph {
    lateinit var settings: SettingsRepository
        private set
    lateinit var network: NetworkMonitor
        private set
    val probe by lazy { ServerProbe() }

    fun init(context: Context) {
        settings = SettingsRepository(context.applicationContext)
        network = NetworkMonitor(context.applicationContext)
    }
}
