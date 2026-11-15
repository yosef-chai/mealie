package io.github.yosefchai.mealie.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class NetworkState(
    val connected: Boolean,
    /** Wi-Fi, Ethernet או VPN: הכתובת הפנימית עשויה להיות זמינה. */
    val mayReachLocal: Boolean,
) {
    companion object {
        val Offline = NetworkState(connected = false, mayReachLocal = false)

        fun from(caps: NetworkCapabilities?): NetworkState {
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                // רשת ביתית בלי אינטרנט עדיין מאפשרת גישה לשרת המקומי.
                val local = caps?.let(::isLocalTransport) ?: false
                return NetworkState(connected = local, mayReachLocal = local)
            }
            return NetworkState(connected = true, mayReachLocal = isLocalTransport(caps))
        }

        private fun isLocalTransport(caps: NetworkCapabilities) =
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}

class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    fun current(): NetworkState = NetworkState.from(cm.getNetworkCapabilities(cm.activeNetwork))

    val state: Flow<NetworkState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(current())
            }

            override fun onLost(network: Network) {
                trySend(current())
            }

            override fun onAvailable(network: Network) {
                trySend(current())
            }
        }
        trySend(current())
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
