package com.aileboeth.hush.daemon

import com.aileboeth.hush.contract.AppRestriction
import com.aileboeth.hush.contract.AppRuntimeSample
import com.aileboeth.hush.contract.AppTarget
import com.aileboeth.hush.contract.MemoryOverview
import com.aileboeth.hush.contract.SecondaryUserInfo
import com.aileboeth.hush.contract.StandbyBucket

/**
 * Abstraction over privileged Android `uid=2000` (`shell`) operations.
 * Implemented by [AndroidSystemBridge] on device and faked in unit tests.
 */
interface SystemBridge {
    fun nowMs(): Long
    fun currentPid(): Int
    fun currentUid(): Int
    fun getSelfApkPath(): String?

    fun readMemoryOverview(): MemoryOverview
    fun getForegroundPackage(): String?
    fun getForegroundTarget(): AppTarget? = getForegroundPackage()?.let { AppTarget(it, 0) }
    fun isScreenAwake(): Boolean

    /**
     * Returns packages genuinely playing audible media right now.
     * Must exclude frozen processes and stale `active=true` MediaSessions without PLAYING state.
     */
    fun getActiveAudioPackages(): Set<String>
    fun getActiveAudioTargets(): Set<AppTarget> = getActiveAudioPackages().mapTo(LinkedHashSet()) { AppTarget(it, 0) }

    fun listUserPackages(): Set<String>

    /** Lists cloned/dual-space app instances installed in clone profiles (e.g., Xspace / MultiApp user 999). */
    fun listClonePackages(): List<AppTarget> = emptyList()

    /**
     * Single-package sample (used for before/after measurements on manual/single actions).
     */
    fun samplePackage(packageName: String, userId: Int = 0): AppRuntimeSample

    /**
     * O(1) batch snapshot across all requested packages (avoids N*4 shell forks for 100+ apps).
     * Default implementation delegates to [samplePackage] for simple test fakes.
     */
    fun samplePackagesBatch(packages: List<String>, userId: Int = 0): Map<String, AppRuntimeSample> {
        val out = LinkedHashMap<String, AppRuntimeSample>(packages.size)
        for (pkg in packages) {
            out[pkg] = samplePackage(pkg, userId)
        }
        return out
    }

    /**
     * O(1) multi-user batch snapshot across both primary (`user 0`) and clone (`user 999`, etc.) targets.
     */
    fun sampleTargetsBatch(targets: List<AppTarget>): Map<AppTarget, AppRuntimeSample> {
        val out = LinkedHashMap<AppTarget, AppRuntimeSample>(targets.size)
        for ((uid, group) in targets.groupBy { it.userId }) {
            val batch = samplePackagesBatch(group.map { it.packageName }.distinct(), userId = uid)
            for (t in group) {
                out[t] = batch[t.packageName] ?: AppRuntimeSample(packageName = t.packageName, userId = uid)
            }
        }
        return out
    }

    fun forceStop(packageName: String, userId: Int = 0): Boolean

    /**
     * Push-safe soft trim for messaging apps (`SOFT_TRIM` tier).
     * Sends `trim-memory COMPLETE` strictly to non-push processes (`:push`/`:msf` skipped).
     * NEVER calls `force-stop` and NEVER calls package-wide `am kill` that could kill `:push`.
     */
    fun softTrim(packageName: String, userId: Int = 0): Boolean

    fun applyRestriction(packageName: String, restriction: AppRestriction, userId: Int = 0): Boolean
    fun lastRestrictionError(): String? = null
    fun readRestriction(packageName: String, userId: Int = 0): Pair<AppRestriction, StandbyBucket>

    /** Active foreground Android user ID (`0` for primary owner, `10+` for secondary spaces). */
    fun getCurrentUserId(): Int = 0

    /** Lists all secondary Android users (`userId > 0`) with live running state and memory footprint. */
    fun listSecondaryUsers(): List<SecondaryUserInfo> = emptyList()

    /**
     * Stops an entire background secondary Android user via `am stop-user -w -f <userId>`.
     * MUST reject `userId <= 0` and the currently active foreground user.
     */
    fun stopUser(userId: Int): Boolean = false
}
