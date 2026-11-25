package com.aileboeth.hush.contract

/**
 * Shared RPC protocol constants and JSON codecs used between `core-engine` and `daemon-shell`.
 * Wire framing: newline-delimited UTF-8 JSON over `127.0.0.1` loopback TCP.
 */
object HushProtocol {
    const val PROTOCOL_VERSION = 1
    const val READY_PREFIX = "HUSH_DAEMON_READY:"
    const val DAEMON_MAIN_CLASS = "com.aileboeth.hush.daemon.DaemonMain"

    // RPC method names
    const val METHOD_PING = "ping"
    const val METHOD_STATUS = "get_status"
    const val METHOD_MEMORY = "get_memory"
    const val METHOD_SAMPLE_APPS = "sample_apps"
    const val METHOD_SYNC_CONFIG = "sync_config"
    const val METHOD_FORCE_STOP = "force_stop"
    const val METHOD_SOFT_TRIM = "soft_trim"
    const val METHOD_SET_RESTRICTION = "set_restriction"
    const val METHOD_RESTORE_ALL = "restore_all"
    const val METHOD_GET_LOGS = "get_logs"
    const val METHOD_CLEAR_LOGS = "clear_logs"
    const val METHOD_GET_SECONDARY_USERS = "get_secondary_users"
    const val METHOD_STOP_USER = "stop_user"
    const val METHOD_GET_DIAGNOSTICS = "get_diagnostics"
    const val METHOD_CLEAR_DIAGNOSTICS = "clear_diagnostics"
    const val METHOD_SHUTDOWN = "shutdown"

    fun encodePolicy(p: PolicyConfig): Map<String, Any?> = mapOf(
        "enabled" to p.enabled,
        "backgroundTimeoutMinutes" to p.backgroundTimeoutMinutes,
        "exemptActiveAudio" to p.exemptActiveAudio,
        "screenOffCleanupEnabled" to p.screenOffCleanupEnabled,
        "screenOffDelayMinutes" to p.screenOffDelayMinutes,
        "memoryPressureEnabled" to p.memoryPressureEnabled,
        "managedBudgetMb" to p.managedBudgetMb,
        "minAvailableRamMb" to p.minAvailableRamMb,
        "pollIntervalSeconds" to p.pollIntervalSeconds,
        "softTrimIncludeDualSpace" to p.softTrimIncludeDualSpace,
        "autoStopSecondaryUsers" to p.autoStopSecondaryUsers,
        "secondaryUserStopDelayMinutes" to p.secondaryUserStopDelayMinutes,
        "screenOffStopSecondaryUsers" to p.screenOffStopSecondaryUsers,
    )

    fun decodePolicy(m: Map<String, Any?>): PolicyConfig = PolicyConfig(
        enabled = m.boolVal("enabled", true),
        backgroundTimeoutMinutes = m.intVal("backgroundTimeoutMinutes", 3).coerceIn(1, 120),
        exemptActiveAudio = m.boolVal("exemptActiveAudio", true),
        screenOffCleanupEnabled = m.boolVal("screenOffCleanupEnabled", true),
        screenOffDelayMinutes = m.intVal("screenOffDelayMinutes", 2).coerceIn(0, 60),
        memoryPressureEnabled = m.boolVal("memoryPressureEnabled", true),
        managedBudgetMb = m.intVal("managedBudgetMb", 1024).coerceIn(256, 8192),
        minAvailableRamMb = m.intVal("minAvailableRamMb", 1280).coerceIn(512, 8192),
        pollIntervalSeconds = m.intVal("pollIntervalSeconds", 30).coerceIn(10, 300),
        softTrimIncludeDualSpace = m.boolVal("softTrimIncludeDualSpace", false),
        autoStopSecondaryUsers = m.boolVal("autoStopSecondaryUsers", true),
        secondaryUserStopDelayMinutes = m.intVal("secondaryUserStopDelayMinutes", 1).coerceIn(0, 30),
        screenOffStopSecondaryUsers = m.boolVal("screenOffStopSecondaryUsers", true),
    )

    fun encodeRestriction(r: AppRestriction): Map<String, Any?> = mapOf(
        "restrictBackground" to r.restrictBackground,
        "restrictWakeLock" to r.restrictWakeLock,
        "restrictedBucket" to r.restrictedBucket,
        "disableApp" to r.disableApp,
    )

    fun decodeRestriction(m: Map<String, Any?>): AppRestriction = AppRestriction(
        restrictBackground = m.boolVal("restrictBackground", false),
        restrictWakeLock = m.boolVal("restrictWakeLock", false),
        restrictedBucket = m.boolVal("restrictedBucket", false),
        disableApp = m.boolVal("disableApp", false),
    )

    fun encodeMemory(m: MemoryOverview): Map<String, Any?> = mapOf(
        "memTotalKb" to m.memTotalKb,
        "memAvailableKb" to m.memAvailableKb,
        "memFreeKb" to m.memFreeKb,
        "swapTotalKb" to m.swapTotalKb,
        "swapFreeKb" to m.swapFreeKb,
        "swapInPages" to m.swapInPages,
        "swapOutPages" to m.swapOutPages,
        "allocStalls" to m.allocStalls,
        "sampledAtMs" to m.sampledAtMs,
    )

    fun decodeMemory(m: Map<String, Any?>): MemoryOverview = MemoryOverview(
        memTotalKb = m.longVal("memTotalKb"),
        memAvailableKb = m.longVal("memAvailableKb"),
        memFreeKb = m.longVal("memFreeKb"),
        swapTotalKb = m.longVal("swapTotalKb"),
        swapFreeKb = m.longVal("swapFreeKb"),
        swapInPages = m.longVal("swapInPages"),
        swapOutPages = m.longVal("swapOutPages"),
        allocStalls = m.longVal("allocStalls"),
        sampledAtMs = m.longVal("sampledAtMs"),
    )

    fun encodeCleanupEvent(e: CleanupEvent): Map<String, Any?> = mapOf(
        "id" to e.id,
        "timestampMs" to e.timestampMs,
        "packageName" to e.packageName,
        "appLabel" to e.appLabel,
        "action" to e.action.name,
        "trigger" to e.trigger.name,
        "freedKb" to e.freedKb,
        "skippedReason" to e.skippedReason,
    )

    fun decodeCleanupEvent(m: Map<String, Any?>): CleanupEvent = CleanupEvent(
        id = m.longVal("id"),
        timestampMs = m.longVal("timestampMs"),
        packageName = m.str("packageName"),
        appLabel = m.str("appLabel"),
        action = runCatching { CleanupAction.valueOf(m.str("action")) }.getOrDefault(CleanupAction.FORCE_STOP),
        trigger = runCatching { CleanupTrigger.valueOf(m.str("trigger")) }.getOrDefault(CleanupTrigger.MANUAL),
        freedKb = m.longVal("freedKb"),
        skippedReason = m.strOrNull("skippedReason"),
    )

    fun encodeConfigSnapshot(c: AppConfigSnapshot): Map<String, Any?> = mapOf(
        "appGroups" to c.appGroups.mapValues { it.value.name },
        "policy" to encodePolicy(c.policy),
        "appliedRestrictions" to c.appliedRestrictions.mapValues { encodeRestriction(it.value) },
    )

    fun decodeConfigSnapshot(m: Map<String, Any?>): AppConfigSnapshot {
        val groups = m.obj("appGroups")?.mapNotNull { (pkg, raw) ->
            val name = raw as? String ?: return@mapNotNull null
            runCatching { pkg to AppGroup.valueOf(name) }.getOrNull()
        }?.toMap().orEmpty()

        val policy = m.obj("policy")?.let(::decodePolicy) ?: PolicyConfig()

        val restrictions = m.obj("appliedRestrictions")?.mapNotNull { (pkg, raw) ->
            @Suppress("UNCHECKED_CAST")
            val objMap = raw as? Map<String, Any?> ?: return@mapNotNull null
            pkg to decodeRestriction(objMap)
        }?.toMap().orEmpty()

        return AppConfigSnapshot(
            appGroups = groups,
            policy = policy,
            appliedRestrictions = restrictions,
        )
    }

    fun encodeAppSample(s: AppRuntimeSample): Map<String, Any?> = mapOf(
        "packageName" to s.packageName,
        "userId" to s.userId,
        "running" to s.running,
        "isFrozen" to s.isFrozen,
        "isPlayingAudio" to s.isPlayingAudio,
        "totalPssKb" to s.totalPssKb,
        "swapPssKb" to s.swapPssKb,
        "processCount" to s.processCount,
        "restriction" to encodeRestriction(s.restriction),
        "standbyBucket" to s.standbyBucket.name,
        "lastForegroundMs" to s.lastForegroundMs,
    )

    fun decodeAppSample(m: Map<String, Any?>): AppRuntimeSample = AppRuntimeSample(
        packageName = m.str("packageName"),
        userId = m.intVal("userId", 0),
        running = m.boolVal("running", false),
        isFrozen = m.boolVal("isFrozen", false),
        isPlayingAudio = m.boolVal("isPlayingAudio", false),
        totalPssKb = m.longVal("totalPssKb"),
        swapPssKb = m.longVal("swapPssKb"),
        processCount = m.intVal("processCount"),
        restriction = m.obj("restriction")?.let(::decodeRestriction) ?: AppRestriction(),
        standbyBucket = runCatching { StandbyBucket.valueOf(m.str("standbyBucket")) }
            .getOrDefault(StandbyBucket.UNKNOWN),
        lastForegroundMs = m.longVal("lastForegroundMs"),
    )

    fun encodeSecondaryUser(u: SecondaryUserInfo): Map<String, Any?> = mapOf(
        "userId" to u.userId,
        "name" to u.name,
        "running" to u.running,
        "isCurrent" to u.isCurrent,
        "processCount" to u.processCount,
        "totalPssKb" to u.totalPssKb,
        "swapPssKb" to u.swapPssKb,
        "isManagedProfile" to u.isManagedProfile,
        "isCloneProfile" to u.isCloneProfile,
    )

    fun decodeSecondaryUser(m: Map<String, Any?>): SecondaryUserInfo = SecondaryUserInfo(
        userId = m.intVal("userId", 0),
        name = m.str("name"),
        running = m.boolVal("running", false),
        isCurrent = m.boolVal("isCurrent", false),
        processCount = m.intVal("processCount", 0),
        totalPssKb = m.longVal("totalPssKb"),
        swapPssKb = m.longVal("swapPssKb"),
        isManagedProfile = m.boolVal("isManagedProfile", false),
        isCloneProfile = m.boolVal("isCloneProfile", false),
    )

    fun encodeDiagnosticEntry(e: DiagnosticLogEntry): Map<String, Any?> = mapOf(
        "timestampMs" to e.timestampMs,
        "level" to e.level,
        "source" to e.source,
        "tag" to e.tag,
        "message" to e.message,
    )

    fun decodeDiagnosticEntry(m: Map<String, Any?>): DiagnosticLogEntry = DiagnosticLogEntry(
        timestampMs = m.longVal("timestampMs"),
        level = m.str("level", "I"),
        source = m.str("source", "daemon"),
        tag = m.str("tag", "Hush"),
        message = m.str("message"),
    )
}

/**
 * Runtime snapshot reported by `daemon-shell` for a package.
 * Merged with PackageManager metadata (app label, icon) on the host app side.
 */
data class AppRuntimeSample(
    val packageName: String,
    val userId: Int = 0,
    val running: Boolean = false,
    val isFrozen: Boolean = false,
    val isPlayingAudio: Boolean = false,
    val totalPssKb: Long = 0L,
    val swapPssKb: Long = 0L,
    val processCount: Int = 0,
    val restriction: AppRestriction = AppRestriction(),
    val standbyBucket: StandbyBucket = StandbyBucket.UNKNOWN,
    val lastForegroundMs: Long = 0L,
)
