package com.wbpxre150.unjumbleapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log

class NetworkManager private constructor(private val context: Context) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var onNetworkChangeListener: ((Boolean) -> Unit)? = null
    
    companion object {
        private const val TAG = "NetworkManager"
        
        @Volatile
        private var INSTANCE: NetworkManager? = null
        
        fun getInstance(context: Context): NetworkManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    fun isOnWiFi(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            
            val isWiFi = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val isEthernet = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            
            Log.d(TAG, "Network check - WiFi: $isWiFi, Ethernet: $isEthernet")
            
            isWiFi || isEthernet
        } catch (e: Exception) {
            Log.e(TAG, "Error checking network type", e)
            false
        }
    }
    
    fun isOnMobileData(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            
            val isCellular = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            
            Log.d(TAG, "Network check - Cellular: $isCellular")
            
            isCellular
        } catch (e: Exception) {
            Log.e(TAG, "Error checking mobile data", e)
            false
        }
    }
    
    fun getNetworkType(): NetworkType {
        return when {
            isOnWiFi() -> NetworkType.WIFI
            isOnMobileData() -> NetworkType.MOBILE_DATA
            else -> NetworkType.UNKNOWN
        }
    }
    
    fun startNetworkMonitoring(listener: (Boolean) -> Unit) {
        onNetworkChangeListener = listener
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.d(TAG, "Network available: $network")
                    checkAndNotifyNetworkChange()
                }
                
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    super.onCapabilitiesChanged(network, networkCapabilities)
                    Log.d(TAG, "Network capabilities changed: $network")
                    checkAndNotifyNetworkChange()
                }
                
                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.d(TAG, "Network lost: $network")
                    checkAndNotifyNetworkChange()
                }
            }
            
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            Log.d(TAG, "Network monitoring started")
        } else {
            Log.w(TAG, "Network monitoring not supported on API level ${Build.VERSION.SDK_INT}")
        }
    }
    
    fun stopNetworkMonitoring() {
        networkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
                Log.d(TAG, "Network monitoring stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping network monitoring", e)
            }
        }
        networkCallback = null
        onNetworkChangeListener = null
    }
    
    private fun checkAndNotifyNetworkChange() {
        val isOnWiFi = isOnWiFi()
        Log.d(TAG, "Network change detected - WiFi: $isOnWiFi")
        onNetworkChangeListener?.invoke(isOnWiFi)
    }
    
    enum class NetworkType {
        WIFI,
        MOBILE_DATA,
        UNKNOWN
    }
}