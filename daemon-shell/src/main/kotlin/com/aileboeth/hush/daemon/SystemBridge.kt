package com.aileboeth.hush.daemon

import com.aileboeth.hush.contract.AppRestriction
import com.aileboeth.hush.contract.AppRuntimeSample
import com.aileboeth.hush.contract.MemoryOverview
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
    fun isScreenAwake(): Boolean

    /**
     * Returns packages genuinely playing audible media right now.
     * Must exclude frozen processes and stale `active=true` MediaSessions without PLAYING state.
     */
    fun getActiveAudioPackages(): Set<String>

    fun listUserPackages(): Set<String>

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

    fun forceStop(packageName: String, userId: Int = 0): Boolean

    /**
     * Push-safe soft trim for messaging apps (`SOFT_TRIM` tier).
     * Sends `trim-memory COMPLETE` strictly to non-push processes (`:push`/`:msf` skipped).
     * NEVER calls `force-stop` and NEVER calls package-wide `am kill` that could kill `:push`.
     */
    fun softTrim(packageName: String, userId: Int = 0): Boolean

    fun applyRestriction(packageName: String, restriction: AppRestriction, userId: Int = 0): Boolean
    fun readRestriction(packageName: String, userId: Int = 0): Pair<AppRestriction, StandbyBucket>
}
