package com.livetranslate.headphones.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetworkTransport { WIFI, CELLULAR, OTHER, NONE }

/** Where Lingo's cloud calls will actually go right now, given the home-Wi-Fi
 * restriction — shared by [LlmClient] (to route) and the UI (to display). */
enum class HomeNetworkStatus { HOME_WIFI, MOBILE_DATA, BLOCKED }

/**
 * Reports the current default network transport so the UI can show Wi-Fi/Mobile/Offline,
 * and lets [LlmClient] enforce "only use Wi-Fi at home" by exposing the current Wi-Fi SSID
 * plus a standby cellular [Network] handle it can bind requests to when away from home.
 */
class NetworkMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _transport = MutableStateFlow(currentTransport())
    val transport: StateFlow<NetworkTransport> = _transport.asStateFlow()

    // Bumped on every connectivity callback, purely so UI observers recompute even when
    // [transport] itself doesn't change value (e.g. roaming between two Wi-Fi networks
    // that are both plain TRANSPORT_WIFI — the SSID changed but the enum value didn't).
    private val _epoch = MutableStateFlow(0)
    val epoch: StateFlow<Int> = _epoch.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _transport.value = currentTransport()
            _epoch.value++
        }

        override fun onLost(network: Network) {
            _transport.value = currentTransport()
            _epoch.value++
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _transport.value = currentTransport()
            _epoch.value++
        }
    }

    // Kept warm for the app's whole lifetime so the first cloud call after leaving home
    // Wi-Fi doesn't pay the multi-second cost of the cellular radio spinning up.
    @Volatile
    private var cellularNetwork: Network? = null

    private val cellularCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            cellularNetwork = network
        }

        override fun onLost(network: Network) {
            if (cellularNetwork == network) cellularNetwork = null
        }
    }

    // Tracked independently of [ConnectivityManager.activeNetwork]: if the connected Wi-Fi
    // network hasn't (yet, or ever) passed Android's internet validation — common on flaky
    // home/marina routers — the system quietly makes cellular the *default* network while
    // Wi-Fi stays associated. Relying on activeNetwork would then see "cellular" and treat
    // the user as away from home even while sitting right next to their own router. Holding
    // a dedicated standby request for Wi-Fi lets us read the SSID (and later bind requests
    // to it) regardless of which network the OS currently treats as default.
    @Volatile
    private var wifiNetwork: Network? = null

    // Since Android 12, NetworkCapabilities strips location-sensitive TransportInfo (the
    // real SSID inside WifiInfo) by default — a fresh connectivityManager.getNetworkCapabilities()
    // call always comes back redacted, *regardless* of the app's location permission. The
    // un-redacted version is only ever delivered to a NetworkCallback registered with
    // FLAG_INCLUDE_LOCATION_INFO, so we cache it here instead of re-querying.
    @Volatile
    private var wifiCapabilities: NetworkCapabilities? = null

    private val wifiCallback = object : ConnectivityManager.NetworkCallback(
        ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO,
    ) {
        override fun onAvailable(network: Network) {
            wifiNetwork = network
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // Capabilities often arrive before onAvailable. Dropping them leaves the SSID
            // blank, so Lingo treats home Wi-Fi as "away" and binds Gemini to cellular.
            wifiNetwork = network
            wifiCapabilities = capabilities
        }

        override fun onLost(network: Network) {
            if (wifiNetwork == network) {
                wifiNetwork = null
                wifiCapabilities = null
            }
        }
    }

    init {
        runCatching {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                callback,
            )
        }
        runCatching {
            connectivityManager.requestNetwork(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cellularCallback,
            )
        }.onFailure { Log.w(TAG, "Couldn't request standby cellular network", it) }
        runCatching {
            connectivityManager.requestNetwork(
                // Keep the builder's default capability set (INTERNET + NOT_RESTRICTED +
                // TRUSTED + NOT_VPN) — dropping NOT_RESTRICTED via clearCapabilities() makes
                // ConnectivityService treat this as a privileged "restricted network"
                // request and reject it with a SecurityException for a normal app.
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                wifiCallback,
            )
        }.onFailure { Log.w(TAG, "Couldn't request standby Wi-Fi network", it) }
    }

    fun isOnline(): Boolean = currentTransport() != NetworkTransport.NONE

    fun currentTransport(): NetworkTransport {
        val network = connectivityManager.activeNetwork ?: return NetworkTransport.NONE
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return NetworkTransport.NONE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkTransport.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            else -> NetworkTransport.OTHER
        }
    }

    /** Whether Lingo currently holds location permission, required by Android to read the
     * real Wi-Fi SSID. If the user granted it as "Only this time", Android silently revokes
     * it soon after the app leaves the foreground — this will flip back to false and the UI
     * should prompt the user to re-grant it as "While using the app" instead. */
    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Standby Wi-Fi network to read the SSID from and (when it's a trusted home network)
     * bind cloud requests to. Null means Wi-Fi isn't associated at all right now. */
    fun wifiNetworkOrNull(): Network? = wifiNetwork

    /** Wi-Fi that can reach the internet. Skips the glasses peer-to-peer network. */
    fun internetWifiNetworkOrNull(): Network? {
        wifiNetwork?.let { candidate ->
            if (hasInternet(candidate)) return candidate
        }
        val active = connectivityManager.activeNetwork ?: return null
        return active.takeIf { hasInternet(it) && isWifi(it) }
    }

    private fun hasInternet(network: Network): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }

    private fun isWifi(network: Network): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /**
     * The current Wi-Fi network's SSID, or null if not on Wi-Fi — or if on Wi-Fi but the
     * SSID can't be read (missing/expired location permission, or Location is off
     * system-wide; both are Android platform requirements for reading real SSIDs, not
     * something this app can bypass).
     */
    fun currentWifiSsid(): String? {
        if (!hasLocationPermission()) return null
        // Must use the capabilities delivered to the FLAG_INCLUDE_LOCATION_INFO callback —
        // a fresh getNetworkCapabilities() call always redacts the SSID since Android 12.
        val info = wifiCapabilities?.transportInfo as? WifiInfo ?: return null
        val ssid = info.ssid?.trim()?.removeSurrounding("\"")
        if (ssid.isNullOrBlank() || ssid == WifiManager.UNKNOWN_SSID) return null
        return ssid
    }

    fun isConnectedToHomeSsid(homeSsids: Collection<String>): Boolean {
        val current = currentWifiSsid() ?: return false
        return homeSsids.any { it.trim().equals(current, ignoreCase = true) }
    }

    /** Standby cellular network to bind a request to. Null means cellular isn't available
     * right now (e.g. no SIM, airplane mode). */
    fun cellularNetworkOrNull(): Network? = cellularNetwork

    /**
     * Run [block] with the process routed over cellular. If the bind is refused and
     * cellular is already the default route, [block] still runs on that route. If the
     * bind is refused and the default is not cellular, this throws so the caller can
     * send on the default route instead.
     */
    fun <T> runOnCellular(block: () -> T): T {
        val cellular = cellularNetwork ?: throw java.io.IOException("No cellular network")
        val bound = runCatching { connectivityManager.bindProcessToNetwork(cellular) }.getOrDefault(false)
        if (!bound) {
            if (defaultIsCellular()) {
                Log.i(TAG, "cellular process bind refused; using the cell default route")
                return block()
            }
            throw java.io.IOException("Couldn't bind to the cellular network")
        }
        Log.i(TAG, "process bound to cellular")
        return try {
            block()
        } finally {
            runCatching { connectivityManager.bindProcessToNetwork(null) }
        }
    }

    private fun defaultIsCellular(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /** Single source of truth for "where will Lingo's next cloud call actually go" —
     * used by both [LlmClient] (to route the request) and the UI (to display status). */
    fun homeNetworkStatus(homeSsids: Collection<String>): HomeNetworkStatus = when {
        isConnectedToHomeSsid(homeSsids) -> HomeNetworkStatus.HOME_WIFI
        cellularNetworkOrNull() != null -> HomeNetworkStatus.MOBILE_DATA
        else -> HomeNetworkStatus.BLOCKED
    }

    fun shutdown() {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        runCatching { connectivityManager.unregisterNetworkCallback(cellularCallback) }
        runCatching { connectivityManager.unregisterNetworkCallback(wifiCallback) }
    }

    companion object {
        private const val TAG = "NetworkMonitor"
    }
}
