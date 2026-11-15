package io.github.yosefchai.mealie.net

import io.github.yosefchai.mealie.core.ServerConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

sealed interface Resolution {
    data class Ready(val url: ServerUrl, val result: ProbeResult) : Resolution

    /** אין רשת: נטען מהמטמון את הכתובת האחרונה שעבדה. */
    data class Offline(val url: ServerUrl?) : Resolution

    /** אף כתובת לא ענתה כ-Mealie; מחזיר את התוצאה המשמעותית ביותר להצגה. */
    data class Failed(val results: List<ProbeResult>) : Resolution
}

/**
 * בוחר בין הכתובת הפנימית לחיצונית:
 * - בלי רשת: הכתובת האחרונה.
 * - סלולרי בלי VPN: ישר לחיצונית.
 * - Wi-Fi / Ethernet / VPN: בדיקה מקבילית; לפנימית עדיפות מוחלטת אם היא עונה תוך 1,000ms.
 */
class ServerResolver(
    private val probe: suspend (ServerUrl, Long?) -> ProbeResult,
    private val internalTimeoutMs: Long = 1_000,
) {
    suspend fun resolve(server: ServerConfig, network: NetworkState): Resolution {
        val internal = server.internal
        val external = server.external

        if (!network.connected) {
            return Resolution.Offline(server.lastUsedUrl?.let(ServerUrl::parse) ?: external ?: internal)
        }
        if (internal == null || external == null || !network.mayReachLocal) {
            val only = if (network.mayReachLocal) internal ?: external else external ?: internal
            only ?: return Resolution.Failed(emptyList())
            val r = probe(only, null)
            return if (r.isUsable()) Resolution.Ready(only, r) else Resolution.Failed(listOf(r))
        }

        return coroutineScope {
            val inner = async { probe(internal, internalTimeoutMs) }
            val outer = async { probe(external, null) }
            // הפנימית קודמת: אם ענתה תקין, היא נבחרת גם אם החיצונית מהירה יותר.
            val first = select {
                inner.onAwait { it }
                outer.onAwait { it }
            }
            val innerResult = if (first.url == internal) first else inner.await()
            if (innerResult.isUsable()) {
                outer.cancel()
                return@coroutineScope Resolution.Ready(internal, innerResult)
            }
            val outerResult = outer.await()
            if (outerResult.isUsable()) Resolution.Ready(external, outerResult)
            else Resolution.Failed(listOf(innerResult, outerResult))
        }
    }

    companion object {
        /** תוצאה שמאפשרת לטעון את ה-WebView (גם שער אימות נטען, כדי שהמשתמש ישלים אותו). */
        fun ProbeResult.isUsable() = this is ProbeResult.Ok || this is ProbeResult.AuthGate
    }
}
