package app.betterhabits.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Whether the device currently has a validated internet connection. */
fun Context.onlineFlow(): Flow<Boolean> = callbackFlow {
    val manager = getSystemService(ConnectivityManager::class.java)
    fun current(): Boolean = manager.getNetworkCapabilities(manager.activeNetwork)
        ?.let { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } == true

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(current()) }
        override fun onLost(network: Network) { trySend(current()) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(current()) }
    }
    trySend(current())
    manager.registerDefaultNetworkCallback(callback)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
