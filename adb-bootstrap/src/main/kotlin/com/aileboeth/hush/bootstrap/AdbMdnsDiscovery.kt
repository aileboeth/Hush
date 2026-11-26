package com.aileboeth.hush.bootstrap

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.aileboeth.hush.contract.AdbEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.util.concurrent.Executors

/**
 * Discovers local Android Wireless Debugging mDNS endpoints:
 * - `_adb-tls-pairing._tcp.` (for initial 6-digit pairing)
 * - `_adb-tls-connect._tcp.` (for launching `daemon-shell` after reboot)
 *
 * Serializes `NsdManager.resolveService()` on Android 12–13 (API < 34) via a FIFO queue,
 * because legacy `NsdManager` rejects concurrent resolves with `FAILURE_ALREADY_ACTIVE`.
 */
class AdbMdnsDiscovery(context: Context) {

    data class DiscoveryResult(
        val pairing: AdbEndpoint? = null,
        val connect: AdbEndpoint? = null,
    )

    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val resolveExecutor = Executors.newSingleThreadExecutor()
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _endpoints = MutableStateFlow(DiscoveryResult())
    val endpoints: StateFlow<DiscoveryResult> = _endpoints.asStateFlow()

    private var pairingListener: NsdManager.DiscoveryListener? = null
    private var connectListener: NsdManager.DiscoveryListener? = null

    private val resolveLock = Any()
    private val pendingResolves = ArrayDeque<Pair<NsdServiceInfo, (AdbEndpoint?) -> Unit>>()
    private var resolvingLegacy = false

    fun start() {
        stop()
        runCatching {
            val lock = wifiManager?.createMulticastLock("hush-adb-mdns")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
        }
        pairingListener = createDiscoveryListener(SERVICE_PAIRING) { ep ->
            Log.i(TAG, "pairing endpoint updated: $ep")
            _endpoints.value = _endpoints.value.copy(pairing = ep)
        }.also {
            runCatching { nsdManager.discoverServices(SERVICE_PAIRING, NsdManager.PROTOCOL_DNS_SD, it) }
        }

        connectListener = createDiscoveryListener(SERVICE_CONNECT) { ep ->
            Log.i(TAG, "connect endpoint updated: $ep")
            _endpoints.value = _endpoints.value.copy(connect = ep)
        }.also {
            runCatching { nsdManager.discoverServices(SERVICE_CONNECT, NsdManager.PROTOCOL_DNS_SD, it) }
        }
    }

    fun stop() {
        pairingListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        connectListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        pairingListener = null
        connectListener = null
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
        synchronized(resolveLock) {
            pendingResolves.clear()
            resolvingLegacy = false
        }
        _endpoints.value = DiscoveryResult()
    }

    private fun createDiscoveryListener(
        serviceType: String,
        onResolved: (AdbEndpoint?) -> Unit,
    ): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String?, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(type: String?, errorCode: Int) = Unit
            override fun onDiscoveryStarted(type: String?) = Unit
            override fun onDiscoveryStopped(type: String?) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveServiceCompat(serviceInfo, onResolved)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val cur = if (serviceType == SERVICE_PAIRING) _endpoints.value.pairing else _endpoints.value.connect
                if (cur?.serviceName == serviceInfo.serviceName) {
                    onResolved(null)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveServiceCompat(
        serviceInfo: NsdServiceInfo,
        onResolved: (AdbEndpoint?) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                nsdManager.registerServiceInfoCallback(
                    serviceInfo,
                    resolveExecutor,
                    object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                        override fun onServiceUpdated(info: NsdServiceInfo) {
                            val host = info.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress
                                ?: info.hostAddresses.firstOrNull()?.hostAddress
                                ?: return
                            if (info.port > 0) {
                                onResolved(AdbEndpoint(host = host, port = info.port, serviceName = info.serviceName))
                                runCatching { nsdManager.unregisterServiceInfoCallback(this) }
                            }
                        }

                        override fun onServiceLost() = Unit
                        override fun onServiceInfoCallbackUnregistered() = Unit
                    },
                )
            }
        } else {
            synchronized(resolveLock) {
                pendingResolves.addLast(serviceInfo to onResolved)
                if (!resolvingLegacy) {
                    resolvingLegacy = true
                    drainLegacyQueueLocked()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun drainLegacyQueueLocked() {
        val next = pendingResolves.removeFirstOrNull()
        if (next == null) {
            resolvingLegacy = false
            return
        }
        val (serviceInfo, onResolved) = next
        val started = runCatching {
            nsdManager.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                        synchronized(resolveLock) { drainLegacyQueueLocked() }
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress
                        if (host != null && info.port > 0) {
                            onResolved(AdbEndpoint(host = host, port = info.port, serviceName = info.serviceName))
                        }
                        synchronized(resolveLock) { drainLegacyQueueLocked() }
                    }
                },
            )
        }.isSuccess
        if (!started) {
            drainLegacyQueueLocked()
        }
    }

    companion object {
        private const val TAG = "HushMdns"
        const val SERVICE_PAIRING = "_adb-tls-pairing._tcp"
        const val SERVICE_CONNECT = "_adb-tls-connect._tcp"
    }
}
