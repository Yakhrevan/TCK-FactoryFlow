package com.tck.factoryflow.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Cloud-only write policy:
 * - Internet required for every write.
 * - Offline users may read cached data.
 * - Offline writes must never be queued.
 */
object CloudConnection {

    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager

        val network = manager.activeNetwork ?: return false
        val capabilities =
            manager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        ) && capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_VALIDATED
        )
    }

    fun requireOnline(context: Context) {
        check(isOnline(context)) {
            "Internet connection required. Please connect and try again."
        }
    }
}
