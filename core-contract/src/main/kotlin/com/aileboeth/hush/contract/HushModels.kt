package com.aileboeth.hush.contract

/**
 * Three-tier background management classification.
 *
 * - [FORCE_STOP]: Bloated apps without critical push needs (e.g., short video, reader, shopping).
 *   Fully stopped via `force-stop` after background timeout, screen-off, or memory pressure.
 * - [SOFT_TRIM]: Messaging/communication apps (e.g., WeChat, QQ) that must keep receiving pushes.
 *   NEVER force-stopped and NEVER appops-restricted; only receives `trim-memory COMPLETE` and
 *   `am kill` to shed cached UI/mini-program/WebView sub-processes while keeping push channels alive.
 * - [PROTECTED]: Always skipped by all automatic policies (e.g., password manager, personal tools).
 * - [UNSET]: Not assigned to an automatic group; still visible for inspection and manual actions.
 */
enum class AppGroup {
    FORCE_STOP,
    SOFT_TRIM,
    PROTECTED,
    UNSET,
}

enum class CleanupAction {
    FORCE_STOP,
    SOFT_TRIM,
    RESTRICT_CHANGED,
    RESTORE_ALL,
}

enum class CleanupTrigger {
    MANUAL,
    BACKGROUND_TIMEOUT,
    SCREEN_OFF,
    MEMORY_PRESSURE,
}

enum class StandbyBucket {
    ACTIVE,
    WORKING_SET,
    FREQUENT,
    RARE,
    RESTRICTED,
    UNKNOWN,
}

/**
 * Reversible per-app background constraints applied via `appops` and standby buckets.
 * Should only be applied to [AppGroup.FORCE_STOP] or [AppGroup.UNSET] apps—never messaging apps.
 */
data class AppRestriction(
    val restrictBackground: Boolean = false,
    val restrictWakeLock: Boolean = false,
    val restrictedBucket: Boolean = false,
) {
    val hasAnyRestriction: Boolean
        get() = restrictBackground || restrictWakeLock || restrictedBucket
}

data class ManagedAppInfo(
    val packageName: String,
    val label: String,
    val userId: Int = 0,
    val isSystemProtected: Boolean = false,
    val group: AppGroup = AppGroup.UNSET,
    val running: Boolean = false,
    val isFrozen: Boolean = false,
    val isPlayingAudio: Boolean = false,
    val totalPssKb: Long = 0L,
    val swapPssKb: Long = 0L,
    val processCount: Int = 0,
    val restriction: AppRestriction = AppRestriction(),
    val standbyBucket: StandbyBucket = StandbyBucket.UNKNOWN,
    val lastForegroundMs: Long = 0L,
) {
    val isDualSpace: Boolean
        get() = userId != 0
}

data class MemoryOverview(
    val memTotalKb: Long = 0L,
    val memAvailableKb: Long = 0L,
    val memFreeKb: Long = 0L,
    val swapTotalKb: Long = 0L,
    val swapFreeKb: Long = 0L,
    val swapInPages: Long = 0L,
    val swapOutPages: Long = 0L,
    val allocStalls: Long = 0L,
    val sampledAtMs: Long = 0L,
) {
    val memUsedKb: Long
        get() = (memTotalKb - memAvailableKb).coerceAtLeast(0L)

    val swapUsedKb: Long
        get() = (swapTotalKb - swapFreeKb).coerceAtLeast(0L)

    val memUsedFraction: Float
        get() = if (memTotalKb > 0L) (memUsedKb.toFloat() / memTotalKb.toFloat()).coerceIn(0f, 1f) else 0f

    val swapUsedFraction: Float
        get() = if (swapTotalKb > 0L) (swapUsedKb.toFloat() / swapTotalKb.toFloat()).coerceIn(0f, 1f) else 0f
}

data class PolicyConfig(
    val enabled: Boolean = true,
    val backgroundTimeoutMinutes: Int = 3,
    val exemptActiveAudio: Boolean = true,
    val screenOffCleanupEnabled: Boolean = true,
    val screenOffDelayMinutes: Int = 2,
    val memoryPressureEnabled: Boolean = true,
    val managedBudgetMb: Int = 1024,
    val minAvailableRamMb: Int = 1280,
    val pollIntervalSeconds: Int = 30,
    val softTrimIncludeDualSpace: Boolean = false,
)

data class CleanupEvent(
    val id: Long,
    val timestampMs: Long,
    val packageName: String,
    val appLabel: String,
    val action: CleanupAction,
    val trigger: CleanupTrigger,
    val freedKb: Long,
    val skippedReason: String? = null,
)

data class AppConfigSnapshot(
    val appGroups: Map<String, AppGroup> = emptyMap(),
    val policy: PolicyConfig = PolicyConfig(),
    val appliedRestrictions: Map<String, AppRestriction> = emptyMap(),
)

sealed interface DaemonStatus {
    data object Disconnected : DaemonStatus
    data object Connecting : DaemonStatus
    data class Running(
        val pid: Int,
        val uid: Int,
        val versionCode: Int,
        val startedAtMs: Long,
        val port: Int,
        val todayCleanupCount: Int = 0,
        val todayFreedKb: Long = 0L,
    ) : DaemonStatus

    data class Error(val message: String) : DaemonStatus
}

data class AdbEndpoint(
    val host: String,
    val port: Int,
    val serviceName: String,
)

sealed interface BootstrapState {
    data object Idle : BootstrapState
    data class Discovering(
        val pairingEndpoint: AdbEndpoint? = null,
        val connectEndpoint: AdbEndpoint? = null,
    ) : BootstrapState

    data class Pairing(val host: String, val port: Int) : BootstrapState
    data class Paired(val host: String) : BootstrapState
    data class Launching(val host: String, val port: Int) : BootstrapState
    data class Ready(
        val port: Int,
        val suggestDisableWirelessDebug: Boolean = true,
    ) : BootstrapState

    data class Error(
        val message: String,
        val causeDetail: String? = null,
    ) : BootstrapState
}
