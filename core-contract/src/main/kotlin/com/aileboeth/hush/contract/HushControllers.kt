package com.aileboeth.hush.contract

import kotlinx.coroutines.flow.StateFlow

/**
 * Primary facade consumed by the Compose UI layer (`:app`).
 * All state streams are hot [StateFlow]s safe to collect with `collectAsStateWithLifecycle()`.
 */
interface GuardController {
    val daemonStatus: StateFlow<DaemonStatus>
    val memoryOverview: StateFlow<MemoryOverview>
    val managedApps: StateFlow<List<ManagedAppInfo>>
    val policyConfig: StateFlow<PolicyConfig>
    val cleanupLogs: StateFlow<List<CleanupEvent>>
    val secondaryUsers: StateFlow<List<SecondaryUserInfo>>
    val customGroups: StateFlow<List<CustomAppGroup>>

    /** Attempt to reconnect to a running daemon using persisted session credentials. */
    suspend fun reconnect(): Result<Unit>

    /** Refresh system RAM/ZRAM snapshot, running app memory footprints, and recent logs. */
    suspend fun refreshSnapshot(): Result<Unit>

    /** Assign an app instance to [AppGroup.FORCE_STOP], [AppGroup.SOFT_TRIM], [AppGroup.PROTECTED], or [AppGroup.UNSET]. */
    suspend fun setAppGroup(packageName: String, group: AppGroup, userId: Int = 0): Result<Unit>

    /** Batch-assign multiple app keys (`pkg` or `pkg@userId`) to target tiers in one atomic sync. */
    suspend fun setAppGroupsBatch(updates: Map<String, AppGroup>): Result<Unit>

    /** Create or update a user-defined custom app group. */
    suspend fun saveCustomGroup(group: CustomAppGroup): Result<Unit>

    /** Delete a user-defined custom app group by ID. */
    suspend fun deleteCustomGroup(groupId: String): Result<Unit>

    /** Update global automated policy settings and immediately push them to the running daemon. */
    suspend fun updatePolicy(config: PolicyConfig): Result<Unit>

    /** Immediately `force-stop` an app instance (rejected for system-protected packages). Returns freed KB. */
    suspend fun forceStopNow(packageName: String, userId: Int = 0): Result<Long>

    /** Immediately `force-stop` a batch of app targets (e.g. a whole group). */
    suspend fun forceStopBatch(targets: List<AppTarget>): Result<BatchActionResult>

    /** Immediately run push-safe soft trim (`trim-memory`). Returns freed KB. */
    suspend fun softTrimNow(packageName: String, userId: Int = 0): Result<Long>

    /** Immediately run push-safe soft trim across a batch of app targets. */
    suspend fun softTrimBatch(targets: List<AppTarget>): Result<BatchActionResult>

    /** Toggle reversible appops/standby-bucket restrictions on a single app instance. */
    suspend fun setAppRestriction(packageName: String, restriction: AppRestriction, userId: Int = 0): Result<Unit>

    /** Revert every appops/standby-bucket modification recorded in the ledger. Returns restored count. */
    suspend fun restoreAllRestrictions(): Result<Int>

    /** Clear the local cleanup history log. */
    suspend fun clearLogs(): Result<Unit>

    /** Immediately stop a background secondary Android user (`am stop-user -w -f <userId>`). Returns freed KB. */
    suspend fun stopSecondaryUser(userId: Int): Result<Long>

    /** Fetch merged diagnostic logs from both local app process and running daemon (if online). */
    suspend fun fetchDiagnostics(): Result<List<DiagnosticLogEntry>>

    /** Clear both local app diagnostic logs and running daemon diagnostic buffer. */
    suspend fun clearDiagnostics(): Result<Unit>

    /** Gracefully terminate the running `uid=2000` daemon process. */
    suspend fun stopDaemon(): Result<Unit>
}

/**
 * Wireless ADB + USB fallback bootstrap facade consumed by the Activation UI screen.
 */
interface BootstrapController {
    val state: StateFlow<BootstrapState>

    /** Copy-pasteable `adb shell` one-liner for USB activation fallback when Wi-Fi is unavailable. */
    val usbFallbackCommand: String

    /** True if this device already holds a persisted RSA keypair previously paired with `adbd`. */
    val hasPairedBefore: StateFlow<Boolean>

    /** Start mDNS discovery (`_adb-tls-pairing._tcp` and `_adb-tls-connect._tcp`). */
    fun startDiscovery()

    /** Stop mDNS discovery. */
    fun stopDiscovery()

    /**
     * Show a notification with inline RemoteInput so the user can enter the 6-digit pairing code
     * while viewing Developer Options -> Wireless Debugging without leaving the screen.
     */
    fun showPairingNotification()

    /** Dismiss the pairing helper notification. */
    fun dismissPairingNotification()

    /** Pair with local `adbd` over TLS 1.3 + SPAKE2 using the 6-digit pairing code. */
    suspend fun pairWithCode(host: String, port: Int, pairingCode: String): Result<Unit>

    /** Connect to local `adbd` over TLS 1.3 (auto-discovered or manual endpoint) and launch the daemon. */
    suspend fun launchDaemon(host: String? = null, port: Int? = null): Result<Unit>

    /** Attach to a daemon manually launched via the USB fallback command. */
    suspend fun attachManualDaemon(port: Int, token: String): Result<Unit>

    /**
     * Best-effort silent recovery when already paired and connected to Wi-Fi:
     * re-enables `adb_wifi_enabled` if `WRITE_SECURE_SETTINGS` was granted, discovers the local
     * wireless ADB connect port over mDNS, and relaunches `daemon-shell`.
     */
    suspend fun autoRecoverOnWifi(timeoutMs: Long = 5_500L): Result<Unit> =
        Result.failure(UnsupportedOperationException("不支持自动恢复"))
}

/**
 * Lightweight dependency graph entry point exposed to `:app`.
 */
interface HushGraph {
    val guardController: GuardController
    val bootstrapController: BootstrapController
    val isPreviewMode: Boolean
}
