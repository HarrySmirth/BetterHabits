package app.betterhabits.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether any network currently offers validated internet.
 *
 * Each internet-capable network is tracked individually and removed when *it* is lost. (Following
 * only the "default network" missed going offline: turning on airplane mode drops Wi-Fi, the
 * default briefly switches to mobile data and reports it validated, and its loss isn't reported
 * to the default-network callback.)
 */
fun Context.onlineFlow(): Flow<Boolean> = callbackFlow {
    val manager = getSystemService(ConnectivityManager::class.java)
    val validated = mutableSetOf<Network>()

    fun NetworkCapabilities.isOnline() =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    fun publish() {
        trySend(synchronized(validated) { validated.isNotEmpty() })
    }

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            synchronized(validated) { if (capabilities.isOnline()) validated += network else validated -= network }
            publish()
        }

        override fun onLost(network: Network) {
            synchronized(validated) { validated -= network }
            publish()
        }
    }

    // Seed with the current state, then follow changes for every internet-capable network.
    manager.getNetworkCapabilities(manager.activeNetwork)?.let { caps ->
        manager.activeNetwork?.takeIf { caps.isOnline() }?.let { synchronized(validated) { validated += it } }
    }
    publish()
    manager.registerNetworkCallback(
        NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
        callback,
    )
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
