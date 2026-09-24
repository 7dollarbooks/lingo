package com.livetranslate.headphones.glasses.vendor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * HeyCyan album import: glasses control `02 01 04 01` brings up Wi-Fi, a device-notify
 * sub `0x08` carries the IPv4 in bytes 7–10, then HTTP `media.config` lists files and
 * `http://{ip}/files/{name}` (or the `:80/storage/sd0/C/DCIM/1/` form) is the JPEG.
 * Only the newest filename is downloaded. An older name than the one already kept is ignored.
 */
internal object GlassesWifiImport {
    private const val TAG = "GlassesCap"
    private const val LISTENER_ID = 2

    suspend fun importNewestJpeg(
        context: Context,
        deadlineEpochMs: Long,
        glassesName: String?,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (!hasP2pPermission(context)) {
            Log.e(TAG, "wifi import missing NEARBY_WIFI_DEVICES or location permission")
            return@withContext null
        }
        val app = context.applicationContext
        val bleIp = AtomicReference<String?>(null)
        val p2pName = AtomicReference<String?>(null)
        val listener = object : GlassesDeviceNotifyListener() {
            override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
                val load = response.loadData ?: return
                if (load.size <= 6) return
                val sub = load[6].toInt() and 0xFF
                Log.i(TAG, "wifi notify cmd=0x${"%02x".format(cmdType)} sub=0x${"%02x".format(sub)} size=${load.size}")
                when (sub) {
                    0x04 -> {
                        val name = load.drop(7)
                            .filter { it.toInt() in 0x20..0x7E }
                            .toByteArray()
                            .toString(Charsets.US_ASCII)
                            .trim()
                        if (name.length >= 4) {
                            p2pName.set(name)
                            Log.i(TAG, "wifi p2p name=$name")
                        }
                    }
                    0x08 -> if (load.size >= 11) {
                        val ip = (7..10).joinToString(".") { (load[it].toInt() and 0xFF).toString() }
                        bleIp.set(ip)
                        Log.i(TAG, "wifi ble ip=$ip")
                    }
                }
            }
        }
        runCatching { LargeDataHandler.getInstance().addOutDeviceListener(LISTENER_ID, listener) }
            .onFailure { Log.e(TAG, "wifi notify listener failed", it) }

        val manager = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            Log.e(TAG, "wifi p2p manager missing")
            removeListener()
            return@withContext null
        }
        val channel = withContext(Dispatchers.Main) {
            manager.initialize(app, Looper.getMainLooper(), null)
        }
        if (channel == null) {
            Log.e(TAG, "wifi p2p channel missing")
            removeListener()
            return@withContext null
        }

        val groupOwnerIp = AtomicReference<String?>(null)
        val connected = AtomicReference(false)
        val connectSent = AtomicReference(false)
        val p2pNetwork = AtomicReference<Network?>(null)
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        manager.requestPeers(channel) { list ->
                            val peers = list.deviceList
                            Log.i(TAG, "wifi peers=${peers.joinToString { it.deviceName }}")
                            val target = pickGlasses(peers, glassesName, p2pName.get()) ?: return@requestPeers
                            if (connected.get() || !connectSent.compareAndSet(false, true)) return@requestPeers
                            Log.i(TAG, "wifi connect ${target.deviceName} ${target.deviceAddress}")
                            val config = WifiP2pConfig.Builder()
                                .setDeviceAddress(MacAddress.fromString(target.deviceAddress))
                                .build()
                            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    Log.i(TAG, "wifi connect request sent")
                                }
                                override fun onFailure(reason: Int) {
                                    connectSent.set(false)
                                    Log.w(TAG, "wifi connect request failed reason=$reason")
                                }
                            })
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val info = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                        }
                        if (info != null && info.groupFormed) {
                            connected.set(true)
                            groupOwnerIp.set(info.groupOwnerAddress?.hostAddress)
                            Log.i(TAG, "wifi p2p up groupOwner=${groupOwnerIp.get()}")
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        var jpeg: ByteArray? = null
        try {
            // HeyCyan album sync LE2/t1.y1: glasses control 02 01 04 01.
            val payload = byteArrayOf(0x02, 0x01, 0x04, 0x01)
            Log.i(TAG, "wifi arm CALL payload=02010401")
            LargeDataHandler.getInstance().glassesControl(payload) { _, resp ->
                val code = runCatching { resp.errorCode }.getOrNull()
                Log.i(TAG, "wifi arm ACK errorCode=$code")
            }
            delay(3_000)
            if (System.currentTimeMillis() < deadlineEpochMs) {
                manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.i(TAG, "wifi discovery started")
                    }
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "wifi discovery failed reason=$reason")
                    }
                })
            }

            val ip = waitForIp(
                deadlineEpochMs,
                bleIp,
                groupOwnerIp,
                connected,
                app,
                manager,
                channel,
                p2pNetwork,
            )
            if (ip != null && System.currentTimeMillis() < deadlineEpochMs) {
                jpeg = downloadNewestJpeg(app, ip, p2pNetwork.get())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "wifi import failed", t)
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
            runCatching { manager.cancelConnect(channel, null) }
            runCatching { manager.removeGroup(channel, null) }
            runCatching { cm.bindProcessToNetwork(null) }
            removeListener()
        }
        jpeg
    }

    private suspend fun waitForIp(
        deadlineEpochMs: Long,
        bleIp: AtomicReference<String?>,
        groupOwnerIp: AtomicReference<String?>,
        connected: AtomicReference<Boolean>,
        context: Context,
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        p2pNetwork: AtomicReference<Network?>,
    ): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var lastDiscover = System.currentTimeMillis()
        val connectedAt = AtomicReference(0L)
        while (System.currentTimeMillis() < deadlineEpochMs) {
            val now = System.currentTimeMillis()
            if (!connected.get() && now - lastDiscover > 10_000) {
                lastDiscover = now
                manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.i(TAG, "wifi discovery restarted")
                    }
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "wifi rediscovery failed reason=$reason")
                    }
                })
            }
            if (connected.get()) {
                if (connectedAt.get() == 0L) connectedAt.set(now)
                // A normal app cannot bind to the p2p Network (Android treats it as
                // restricted). Leave the socket unbound so the 192.168.49.0/24 route is used.
                // Prefer the BLE-reported glasses IP. Fallbacks only after it is known
                // or after a short wait — probing over home Wi-Fi never reaches the glasses.
                val reported = bleIp.get()
                val waitForBleMs = 8_000L
                val allowFallbacks = reported != null ||
                    (now - connectedAt.get()) >= waitForBleMs
                val candidates = LinkedHashSet<String>()
                reported?.let { candidates.add(it) }
                if (allowFallbacks) {
                    groupOwnerIp.get()?.let { candidates.add(it) }
                    candidates.add("192.168.49.80")
                    candidates.add("192.168.49.79")
                    candidates.add("192.168.49.2")
                    candidates.add("192.168.49.3")
                }
                for (ip in candidates) {
                    if (ip.isBlank() || ip == "192.168.49.1") continue
                    if (fileListOk(context, ip, p2pNetwork.get())) {
                        Log.i(TAG, "wifi file list reachable at $ip")
                        return ip
                    }
                }
            }
            delay(500)
        }
        Log.w(TAG, "wifi import timed out before a file list bleIp=${bleIp.get()} p2p=${connected.get()}")
        return null
    }

    private fun fileListOk(context: Context, ip: String, network: Network?): Boolean {
        for (url in listUrls(ip)) {
            val conn = open(context, url, network) ?: continue
            try {
                conn.connectTimeout = 2_500
                conn.readTimeout = 2_500
                conn.requestMethod = "GET"
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    if (text.contains(".jpg", ignoreCase = true)) return true
                }
            } catch (t: Throwable) {
                Log.d(TAG, "wifi probe $url ${t.message}")
            } finally {
                conn.disconnect()
            }
        }
        return false
    }

    private fun downloadNewestJpeg(context: Context, ip: String, network: Network?): ByteArray? {
        val list = listUrls(ip).firstNotNullOfOrNull { url ->
            val text = httpText(context, url, network)
            text?.takeIf { it.contains(".jpg", ignoreCase = true) }
        } ?: return null
        val names = jpegNames(list)
        val listed = names.maxOrNull() ?: return null
        val kept = readKept(context)
        if (kept != null && listed <= kept.first) {
            Log.i(TAG, "wifi keep ${kept.first} ignoring listed=$listed of ${names.size}")
            return kept.second
        }
        Log.i(TAG, "wifi newest jpeg=$listed of ${names.size}")
        for (base in fileBases(ip)) {
            val bytes = httpBytes(context, base + listed, network)
            if (bytes != null && bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
                Log.i(TAG, "wifi downloaded $listed ${bytes.size}B from $base")
                writeKept(context, listed, bytes)
                return bytes
            }
        }
        return null
    }

    /** Timestamp names sort in capture order. One name per line, or the last token on the line. */
    private fun jpegNames(list: String): List<String> =
        list.lineSequence().mapNotNull { line ->
            val token = line.trim().split(Regex("\\s+")).lastOrNull().orEmpty()
            token.takeIf { it.endsWith(".jpg", ignoreCase = true) || it.endsWith(".jpeg", ignoreCase = true) }
        }.distinct().toList()

    private fun newestDir(context: Context): File =
        File(context.cacheDir, "glasses_newest").apply { mkdirs() }

    private fun readKept(context: Context): Pair<String, ByteArray>? {
        val dir = newestDir(context)
        val name = File(dir, "name.txt").takeIf { it.isFile }?.readText()?.trim().orEmpty()
        val jpeg = File(dir, "newest.jpg").takeIf { it.isFile }?.readBytes() ?: return null
        if (name.isEmpty() || jpeg.size < 3) return null
        if (jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        return name to jpeg
    }

    private fun writeKept(context: Context, name: String, jpeg: ByteArray) {
        val dir = newestDir(context)
        File(dir, "newest.jpg").writeBytes(jpeg)
        File(dir, "name.txt").writeText(name)
        runCatching { saveOwnCopy(context, name, jpeg) }
            .onFailure { Log.w(TAG, "wifi photo copy failed $name", it) }
        runCatching { saveInPhotoDir(context, name, jpeg) }
            .onFailure { Log.w(TAG, "wifi photo store failed $name", it) }
    }

    /** App-owned copy, one file per glasses name. Not the cache used to pick the newest. */
    private fun saveOwnCopy(context: Context, name: String, jpeg: ByteArray) {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, "Pictures")
        dir.mkdirs()
        val file = File(dir, safe)
        if (file.isFile && file.length() == jpeg.size.toLong()) return
        file.writeBytes(jpeg)
        Log.i(TAG, "wifi photo saved ${file.absolutePath}")
    }

    /** Gallery album Pictures/Lingo, so the photo can be opened and saved later. */
    private fun saveInPhotoDir(context: Context, name: String, jpeg: ByteArray) {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relative = "Pictures/Lingo/"
        resolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=?",
            arrayOf(name, relative),
            null,
        )?.use { if (it.moveToFirst()) return }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relative)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return
        try {
            resolver.openOutputStream(uri)?.use { it.write(jpeg) }
                ?: throw java.io.IOException("no output stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "wifi photo stored $relative$name")
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    private fun listUrls(ip: String): List<String> = listOf(
        "http://$ip/files/media.config",
        "http://$ip/files/photo.txt",
        "http://$ip:80/storage/sd0/C/DCIM/1/media.config",
    )

    private fun fileBases(ip: String): List<String> = listOf(
        "http://$ip/files/",
        "http://$ip:80/storage/sd0/C/DCIM/1/",
    )

    private fun httpText(context: Context, url: String, network: Network?): String? {
        val conn = open(context, url, network) ?: return null
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            Log.w(TAG, "wifi GET $url ${t.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun httpBytes(context: Context, url: String, network: Network?): ByteArray? {
        val conn = open(context, url, network) ?: return null
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 60_000
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null
            else conn.inputStream.use { it.readBytes() }
        } catch (t: Throwable) {
            Log.w(TAG, "wifi GET $url ${t.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun open(context: Context, url: String, network: Network?): HttpURLConnection? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn
    }.getOrNull()

    private fun pickGlasses(
        peers: Collection<WifiP2pDevice>,
        glassesName: String?,
        reportedName: String?,
    ): WifiP2pDevice? {
        if (!reportedName.isNullOrBlank()) {
            peers.firstOrNull {
                it.deviceName.equals(reportedName, ignoreCase = true) ||
                    it.deviceName.contains(reportedName.take(8), ignoreCase = true)
            }?.let { return it }
        }
        val hints = listOfNotNull(glassesName, "DM-01", "DM01", "HeyCyan")
        peers.firstOrNull { device ->
            hints.any { hint -> device.deviceName.contains(hint, ignoreCase = true) }
        }?.let { return it }
        return null
    }

    private fun hasP2pPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val nearby = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED
        return fine && nearby
    }

    private fun removeListener() {
        runCatching {
            LargeDataHandler.getInstance().removeOutDeviceListener(LISTENER_ID)
        }
    }
}
