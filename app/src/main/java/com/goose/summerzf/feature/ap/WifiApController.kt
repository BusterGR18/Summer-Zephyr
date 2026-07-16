package com.goose.summerzf.feature.ap

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.goose.summerzf.core.qr.QrContent

class WifiApController internal constructor (
    private val context: Context,
    private val onHudConnected: () -> Unit,
    private val onHudDisconnected: (WifiApController) -> Unit
) {
    private val connectivityManager = context.getSystemService(
        ConnectivityManager::class.java
    )

    private var targetSsid: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    var isHudConnected by mutableStateOf(false)
        private set

    fun suggestHudNetwork(hudQr: QrContent.HudWifi): Int {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.removeNetworkSuggestions(emptyList())

        targetSsid = hudQr.ssid
        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(hudQr.ssid)
            .setWpa2Passphrase(hudQr.password ?: "")
            .setPriority(1000)
            .setIsUserInteractionRequired(false)
            .build()

        return wifiManager.addNetworkSuggestions(listOf(suggestion))
    }

    fun startWatching() {
        if (networkCallback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                if (!isWifi && isHudConnected) {
                    isHudConnected = false
                    onHudDisconnected(this@WifiApController)
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                // Extract all IP addresses assigned to this network
                val addresses = linkProperties.linkAddresses.map { it.address.hostAddress }

                Log.d("WifiApController", "onLinkPropertiesChanged: addresses=$addresses")

                // Detect HUD subnet
                val hudSubnetMatch = addresses.any { it!!.startsWith("192.168.0.") }

                if (hudSubnetMatch && !isHudConnected) {
                    isHudConnected = true
                    onHudConnected()
                } else if (!hudSubnetMatch && isHudConnected) {
                    isHudConnected = false
                    onHudDisconnected(this@WifiApController)
                }
            }

            // What will happen when it disconnects
            override fun onLost(network: Network) {
                if (isHudConnected) {
                    isHudConnected = false
                    onHudDisconnected(this@WifiApController)
                }
            }
        }

        connectivityManager.registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    fun stopWatching() {
        networkCallback?.let {
            connectivityManager.unregisterNetworkCallback(it)
        }
        networkCallback = null
        isHudConnected = false
    }

    fun clearAllHudSuggestions(): Int {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.removeNetworkSuggestions(emptyList())
    }
}