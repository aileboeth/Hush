package com.aileboeth.hush.state

import com.aileboeth.hush.contract.AppGroup
import com.aileboeth.hush.contract.BootstrapState
import com.aileboeth.hush.contract.CleanupAction
import com.aileboeth.hush.contract.CleanupEvent
import com.aileboeth.hush.contract.CustomAppGroup
import com.aileboeth.hush.contract.DaemonStatus
import com.aileboeth.hush.contract.DiagnosticLogEntry
import com.aileboeth.hush.contract.ManagedAppInfo
import com.aileboeth.hush.contract.MemoryOverview
import com.aileboeth.hush.contract.PolicyConfig
import com.aileboeth.hush.contract.SecondaryUserInfo
import com.aileboeth.hush.update.UpdateState

enum class HushTab(val label: String) {
    OVERVIEW("总览"),
    APPS("应用"),
    POLICIES("策略"),
}

enum class ThemePreference(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("纯净浅色"),
    DARK("深邃深色"),
}

enum class AppFilterMode(val label: String) {
    ALL("全部"),
    RUNNING("运行中"),
    FORCE_STOP("强杀"),
    SOFT_TRIM("瘦身"),
    PROTECTED("保护"),
}

enum class AppSortMode(val label: String) {
    MEMORY_DESC("内存降序"),
    RUNNING_FIRST("活跃优先"),
    NAME_ASC("名称排序"),
}

enum class ActivityFilterMode(val label: String) {
    ALL("全部"),
    FORCE_STOP("强杀"),
    SOFT_TRIM("瘦身"),
    SKIPPED("已跳过"),
}

sealed interface GroupEditorTarget {
    data class BuiltInTier(val tier: AppGroup) : GroupEditorTarget
    data class Custom(
        val existing: CustomAppGroup? = null,
        val preselectedAppKeys: Set<String> = emptySet(),
    ) : GroupEditorTarget
}

data class MemorySamplePoint(
    val timestampMs: Long,
    val availableMb: Float,
    val usedMb: Float,
    val swapUsedMb: Float,
)

data class HushUiState(
    val activeTab: HushTab = HushTab.OVERVIEW,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val showStatusPopover: Boolean = false,
    val showActivationSheet: Boolean = false,
    val showActivitySheet: Boolean = false,
    val isPreviewMode: Boolean = false,
    val isRefreshing: Boolean = false,
    val isReconnecting: Boolean = false,
    val daemonStatus: DaemonStatus = DaemonStatus.Disconnected,
    val memoryOverview: MemoryOverview = MemoryOverview(),
    val memoryHistory: List<MemorySamplePoint> = emptyList(),
    val managedApps: List<ManagedAppInfo> = emptyList(),
    val secondaryUsers: List<SecondaryUserInfo> = emptyList(),
    val customGroups: List<CustomAppGroup> = emptyList(),
    val policyConfig: PolicyConfig = PolicyConfig(),
    val cleanupLogs: List<CleanupEvent> = emptyList(),
    val bootstrapState: BootstrapState = BootstrapState.Idle,
    val hasPairedBefore: Boolean = false,
    val usbFallbackCommand: String = "",
    val busyPackages: Set<String> = emptySet(),
    val busyUserIds: Set<Int> = emptySet(),
    val isGlobalActionBusy: Boolean = false,
    // Apps screen state
    val appFilter: AppFilterMode = AppFilterMode.ALL,
    val selectedCustomGroupId: String? = null,
    val appSort: AppSortMode = AppSortMode.MEMORY_DESC,
    val appSearchQuery: String = "",
    val selectedAppKey: String? = null,
    val groupEditorTarget: GroupEditorTarget? = null,
    // Activity bottom sheet filter
    val activityFilter: ActivityFilterMode = ActivityFilterMode.ALL,
    // Self-updater, disclaimer & diagnostic logs state
    val updateState: UpdateState = UpdateState.Idle,
    val showUpdateDialog: Boolean = false,
    val showDisclaimerDialog: Boolean = false,
    val showDiagnosticsSheet: Boolean = false,
    val isDiagnosticsLoading: Boolean = false,
    val diagnosticLogs: List<DiagnosticLogEntry> = emptyList(),
) {
    /** Only true full secondary Android users (never clone/Xspace dual-app spaces or Work Profiles). */
    val fullSecondaryUsers: List<SecondaryUserInfo>
        get() = secondaryUsers.filter { it.isFullSecondarySystem }

    val selectedApp: ManagedAppInfo?
        get() = selectedAppKey?.let { key -> managedApps.firstOrNull { it.appKey == key } }

    val selectedCustomGroup: CustomAppGroup?
        get() = selectedCustomGroupId?.let { id -> customGroups.firstOrNull { it.id == id } }

    val runningAppCount: Int
        get() = managedApps.count { it.running }

    val dualSpaceAppCount: Int
        get() = managedApps.count { it.isDualSpace }

    val restrictedAppCount: Int
        get() = managedApps.count { it.restriction.hasAnyRestriction }

    val tierCounts: Map<AppGroup, Int>
        get() = managedApps.groupingBy { it.group }.eachCount()

    fun customGroupsFor(appKey: String): List<CustomAppGroup> =
        customGroups.filter { appKey in it.appKeys }

    fun appsInCustomGroup(group: CustomAppGroup): List<ManagedAppInfo> {
        val keySet = group.appKeys.toHashSet()
        return managedApps.filter { it.appKey in keySet }
    }

    fun appsInTier(tier: AppGroup): List<ManagedAppInfo> =
        managedApps.filter { it.group == tier }

    val filteredApps: List<ManagedAppInfo>
        get() {
            val q = appSearchQuery.trim().lowercase()
            val activeCustom = selectedCustomGroup
            val customKeySet = activeCustom?.appKeys?.toHashSet()
            val filtered = managedApps.filter { app ->
                val matchesFilter = if (customKeySet != null) {
                    app.appKey in customKeySet
                } else {
                    when (appFilter) {
                        AppFilterMode.ALL -> true
                        AppFilterMode.RUNNING -> app.running || app.totalPssKb > 0L
                        AppFilterMode.FORCE_STOP -> app.group == AppGroup.FORCE_STOP
                        AppFilterMode.SOFT_TRIM -> app.group == AppGroup.SOFT_TRIM
                        AppFilterMode.PROTECTED -> app.group == AppGroup.PROTECTED
                    }
                }
                val matchesQuery = q.isEmpty() ||
                    app.label.lowercase().contains(q) ||
                    app.packageName.lowercase().contains(q) ||
                    (app.isDualSpace && ("双开".contains(q) || "分身".contains(q) || "xspace".contains(q)))
                matchesFilter && matchesQuery
            }
            return when (appSort) {
                AppSortMode.MEMORY_DESC -> filtered.sortedWith(
                    compareByDescending<ManagedAppInfo> { it.totalPssKb }
                        .thenByDescending { it.running }
                        .thenBy { it.label.lowercase() }
                        .thenBy { it.userId },
                )
                AppSortMode.RUNNING_FIRST -> filtered.sortedWith(
                    compareByDescending<ManagedAppInfo> { it.running }
                        .thenByDescending { it.isPlayingAudio }
                        .thenByDescending { it.totalPssKb }
                        .thenBy { it.label.lowercase() }
                        .thenBy { it.userId },
                )
                AppSortMode.NAME_ASC -> filtered.sortedWith(
                    compareBy<ManagedAppInfo> { it.label.lowercase() }.thenBy { it.userId },
                )
            }
        }

    val filteredLogs: List<CleanupEvent>
        get() = cleanupLogs.filter { ev ->
            when (activityFilter) {
                ActivityFilterMode.ALL -> true
                ActivityFilterMode.FORCE_STOP ->
                    (ev.action == CleanupAction.FORCE_STOP || ev.action == CleanupAction.STOP_USER) &&
                        ev.skippedReason == null
                ActivityFilterMode.SOFT_TRIM -> ev.action == CleanupAction.SOFT_TRIM && ev.skippedReason == null
                ActivityFilterMode.SKIPPED -> !ev.skippedReason.isNullOrBlank()
            }
        }

    val persistedTodayFreedKb: Long
        get() {
            if (cleanupLogs.isEmpty()) return 0L
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val dayStart = cal.timeInMillis
            return cleanupLogs.sumOf { ev ->
                if (ev.timestampMs >= dayStart &&
                    ev.skippedReason == null &&
                    (ev.action == CleanupAction.FORCE_STOP ||
                        ev.action == CleanupAction.SOFT_TRIM ||
                        ev.action == CleanupAction.STOP_USER)
                ) ev.freedKb else 0L
            }
        }
}

data class HushNotice(
    val message: String,
    val isError: Boolean = false,
)
