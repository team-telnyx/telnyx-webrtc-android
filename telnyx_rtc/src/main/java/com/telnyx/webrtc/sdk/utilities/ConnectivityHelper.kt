/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.utilities

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.telnyx.webrtc.sdk.model.Region
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/**
 * Helper for connectivity statuses.
 */
object ConnectivityHelper {

    /**
     * Unregister network state change callback.
     *
     * @param context the context
     * @param callback the network state callback
     *
     * @see [ConnectivityManager]
     */
    fun unregisterNetworkStatusCallback(context: Context, callback: NetworkCallback) {
        try {
            val manager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            manager.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Timber.e(
                e,
                "unregisterNetworkCallback [%s]",
                this@ConnectivityHelper.javaClass.simpleName
            )
        }
    }

    /**
     * Register network state change callback.
     *
     * @param context the context
     * @param callback the network state callback
     *
     * @see [ConnectivityManager]
     * @see [NetworkCapabilities]
     * @see [NetworkRequest]
     */
    fun registerNetworkStatusCallback(context: Context, callback: NetworkCallback) {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()
            val manager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            manager.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Timber.e(
                e,
                "registerNetworkStatusCallback [%s]",
                this@ConnectivityHelper.javaClass.simpleName
            )
        }
    }

    /**
     * Get network enabled status.
     *
     * @return current network status
     *
     */
    fun isNetworkEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork: Network? = manager.activeNetwork
        val capabilities: NetworkCapabilities? = manager.getNetworkCapabilities(activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false
    }

    /**
     * Check if a socket is reachable.
     * If not, try to resolve the host by removing the region from the host.
     *
     * @param host the reachable host
     */
    suspend fun resolveReachableHost(host: String, port: Int, timeoutMillis: Int = 1000): String {
        try {
            withContext(Dispatchers.IO) {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMillis)
                }
                host
            }
        } catch (e: Exception) {
            if (e is UnknownHostException) {
                Timber.e(e, "Socket not reachable $host")
                return prepareHostFallback(host)
            }
        }

        return host
    }

    /**
     * Prepare host by removing the region from the host.
     */
    private fun prepareHostFallback(host: String): String {
        val region = host.substringBefore(".")
        return Region.fromValue(region)?.let {
            host.replace("$region.", "")
        } ?: host
    }

    /**
     * Abstract network state change callback.
     *
     * @see [ConnectivityManager.NetworkCallback]
     */
    abstract class NetworkCallback : ConnectivityManager.NetworkCallback() {

        /**
         * Called when network is available.
         */
        abstract fun onNetworkAvailable()

        /**
         * Called when network is unavailable or lost.
         */
        abstract fun onNetworkUnavailable()

        override fun onAvailable(network: Network) {
            onNetworkAvailable()
        }

        override fun onUnavailable() {
            onNetworkUnavailable()
        }

        override fun onLost(network: Network) {
            onNetworkUnavailable()
        }
    }
}
