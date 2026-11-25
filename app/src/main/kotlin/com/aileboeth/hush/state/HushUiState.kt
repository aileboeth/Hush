package com.aileboeth.hush.state

import com.aileboeth.hush.contract.AppGroup
import com.aileboeth.hush.contract.BootstrapState
import com.aileboeth.hush.contract.CleanupAction
import com.aileboeth.hush.contract.CleanupEvent
import com.aileboeth.hush.contract.DaemonStatus
import com.aileboeth.hush.contract.ManagedAppInfo
import com.aileboeth.hush.contract.MemoryOverview
import com.aileboeth.hush.contract.PolicyConfig

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

data class MemorySamplePoint(
    val timestampMs: Long,
    val availableMb: Float,
    val usedMb: Float,
    val swapUsedMb: Float,
)

data class HushUiState(
    val activeTab: HushTab = HushTab.OVERVIEW,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val showActivationPanel: Boolean = false,
    val showActivitySheet: Boolean = false,
    val isPreviewMode: Boolean = false,
    val isRefreshing: Boolean = false,
    val isReconnecting: Boolean = false,
    val daemonStatus: DaemonStatus = DaemonStatus.Disconnected,
    val memoryOverview: MemoryOverview = MemoryOverview(),
    val memoryHistory: List<MemorySamplePoint> = emptyList(),
    val managedApps: List<ManagedAppInfo> = emptyList(),
    val policyConfig: PolicyConfig = PolicyConfig(),
    val cleanupLogs: List<CleanupEvent> = emptyList(),
    val bootstrapState: BootstrapState = BootstrapState.Idle,
    val hasPairedBefore: Boolean = false,
    val usbFallbackCommand: String = "",
    val busyPackages: Set<String> = emptySet(),
    val isGlobalActionBusy: Boolean = false,
    // Apps screen state
    val appFilter: AppFilterMode = AppFilterMode.ALL,
    val appSort: AppSortMode = AppSortMode.MEMORY_DESC,
    val appSearchQuery: String = "",
    val selectedAppPackage: String? = null,
    // Activity bottom sheet filter
    val activityFilter: ActivityFilterMode = ActivityFilterMode.ALL,
) {
    val selectedApp: ManagedAppInfo?
        get() = selectedAppPackage?.let { pkg -> managedApps.firstOrNull { it.packageName == pkg } }

    val runningAppCount: Int
        get() = managedApps.count { it.running }

    val restrictedAppCount: Int
        get() = managedApps.count { it.restriction.hasAnyRestriction }

    val tierCounts: Map<AppGroup, Int>
        get() = managedApps.groupingBy { it.group }.eachCount()

    val filteredApps: List<ManagedAppInfo>
        get() {
            val q = appSearchQuery.trim().lowercase()
            val filtered = managedApps.filter { app ->
                val matchesFilter = when (appFilter) {
                    AppFilterMode.ALL -> true
                    AppFilterMode.RUNNING -> app.running || app.totalPssKb > 0L
                    AppFilterMode.FORCE_STOP -> app.group == AppGroup.FORCE_STOP
                    AppFilterMode.SOFT_TRIM -> app.group == AppGroup.SOFT_TRIM
                    AppFilterMode.PROTECTED -> app.group == AppGroup.PROTECTED
                }
                val matchesQuery = q.isEmpty() ||
                    app.label.lowercase().contains(q) ||
                    app.packageName.lowercase().contains(q)
                matchesFilter && matchesQuery
            }
            return when (appSort) {
                AppSortMode.MEMORY_DESC -> filtered.sortedWith(
                    compareByDescending<ManagedAppInfo> { it.totalPssKb }
                        .thenByDescending { it.running }
                        .thenBy { it.label.lowercase() },
                )
                AppSortMode.RUNNING_FIRST -> filtered.sortedWith(
                    compareByDescending<ManagedAppInfo> { it.running }
                        .thenByDescending { it.isPlayingAudio }
                        .thenByDescending { it.totalPssKb }
                        .thenBy { it.label.lowercase() },
                )
                AppSortMode.NAME_ASC -> filtered.sortedBy { it.label.lowercase() }
            }
        }

    val filteredLogs: List<CleanupEvent>
        get() = cleanupLogs.filter { ev ->
            when (activityFilter) {
                ActivityFilterMode.ALL -> true
                ActivityFilterMode.FORCE_STOP -> ev.action == CleanupAction.FORCE_STOP && ev.skippedReason == null
                ActivityFilterMode.SOFT_TRIM -> ev.action == CleanupAction.SOFT_TRIM && ev.skippedReason == null
                ActivityFilterMode.SKIPPED -> !ev.skippedReason.isNullOrBlank()
            }
        }
}

data class HushNotice(
    val message: String,
    val isError: Boolean = false,
)
