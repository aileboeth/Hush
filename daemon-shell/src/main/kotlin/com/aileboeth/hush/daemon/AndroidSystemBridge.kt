package com.aileboeth.hush.daemon

import com.aileboeth.hush.contract.AppRestriction
import com.aileboeth.hush.contract.AppRuntimeSample
import com.aileboeth.hush.contract.AppTarget
import com.aileboeth.hush.contract.HushLog
import com.aileboeth.hush.contract.MemoryOverview
import com.aileboeth.hush.contract.SafetyGuardrails
import com.aileboeth.hush.contract.SecondaryUserInfo
import com.aileboeth.hush.contract.StandbyBucket
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Production [SystemBridge] running inside `app_process64` as `uid=2000 (shell)`.
 *
 * Key design choices verified for Android 12–16 / OriginOS 6:
 * - Zero-fork `/proc/meminfo` & `/proc/vmstat` reads (< 2 ms).
 * - O(1) batch sampling (`samplePackagesBatch`) runs only ~5 system commands total for 100+ apps
 *   instead of 400+ forks, preventing RPC timeouts.
 * - Single-package meminfo uses `dumpsys meminfo -S -s --package <pkg>` (NO `--user` flag,
 *   which AOSP `dumpsys meminfo` rejects, and WITH `--package` + `-S` to include sub-processes & Swap PSS).
 * - Push-safe `softTrim` strictly sends `am send-trim-memory COMPLETE` to non-push processes only
 *   and does NOT invoke package-wide `am kill` (which could kill WeChat's `:push` at SERVICE_ADJ).
 */
class AndroidSystemBridge : SystemBridge {

    private val uidToPkgCache = HashMap<Int, String>()
    private var uidCacheUpdatedMs = 0L

    @Volatile
    private var lastApplyRestrictionError: String? = null

    override fun lastRestrictionError(): String? = lastApplyRestrictionError

    override fun nowMs(): Long = System.currentTimeMillis()

    override fun currentPid(): Int = runCatching {
        android.os.Process.myPid()
    }.getOrDefault(-1)

    override fun currentUid(): Int = runCatching {
        android.os.Process.myUid()
    }.getOrDefault(-1)

    override fun getSelfApkPath(): String? {
        val out = execCommand(listOf("pm", "path", SafetyGuardrails.SELF_PACKAGE), timeoutMs = 4000)
        return out.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("package:") }
            ?.removePrefix("package:")
    }

    override fun readMemoryOverview(): MemoryOverview {
        val memMap = parseKeyValueKbFile("/proc/meminfo")
        val vmMap = parseKeyValueLongFile("/proc/vmstat")
        val allocNormal = vmMap["allocstall_normal"] ?: 0L
        val allocMovable = vmMap["allocstall_movable"] ?: 0L
        val allocDma = vmMap["allocstall_dma32"] ?: 0L
        return MemoryOverview(
            memTotalKb = memMap["MemTotal"] ?: 0L,
            memAvailableKb = memMap["MemAvailable"] ?: 0L,
            memFreeKb = memMap["MemFree"] ?: 0L,
            swapTotalKb = memMap["SwapTotal"] ?: 0L,
            swapFreeKb = memMap["SwapFree"] ?: 0L,
            swapInPages = vmMap["pswpin"] ?: 0L,
            swapOutPages = vmMap["pswpout"] ?: 0L,
            allocStalls = allocNormal + allocMovable + allocDma,
            sampledAtMs = nowMs(),
        )
    }

    override fun getForegroundPackage(): String? = getForegroundTarget()?.packageName

    override fun getForegroundTarget(): AppTarget? {
        val out = execCommand(listOf("dumpsys", "activity", "activities"), timeoutMs = 4000)
        return parseForegroundTarget(out)
    }

    override fun isScreenAwake(): Boolean {
        val out = execCommand(listOf("dumpsys", "power"), timeoutMs = 3000)
        return parseScreenAwake(out)
    }

    override fun getActiveAudioPackages(): Set<String> =
        getActiveAudioTargets().mapTo(LinkedHashSet()) { it.packageName }

    override fun getActiveAudioTargets(): Set<AppTarget> {
        refreshUidCacheIfNeeded()
        val audioOut = execCommand(listOf("dumpsys", "audio"), timeoutMs = 4000)
        val mediaOut = execCommand(listOf("dumpsys", "media_session"), timeoutMs = 4000)

        val audioTargets = parseStartedAudibleUids(audioOut).mapNotNull { fullUid ->
            val userId = fullUid / PER_USER_RANGE
            val appId = fullUid % PER_USER_RANGE
            val pkg = uidToPkgCache[fullUid] ?: uidToPkgCache[appId] ?: return@mapNotNull null
            AppTarget(pkg, userId)
        }.toSet()
        val playingSessionPkgs = parsePlayingMediaSessions(mediaOut)

        val candidates = LinkedHashSet<AppTarget>()
        if (playingSessionPkgs.isNotEmpty() && audioTargets.isNotEmpty()) {
            val matched = audioTargets.filter { it.packageName in playingSessionPkgs }
            if (matched.isNotEmpty()) {
                candidates.addAll(matched)
            } else {
                candidates.addAll(audioTargets)
                playingSessionPkgs.forEach { candidates.add(AppTarget(it, 0)) }
            }
        } else {
            candidates.addAll(audioTargets)
            playingSessionPkgs.forEach { candidates.add(AppTarget(it, 0)) }
        }
        if (candidates.isEmpty()) return emptySet()

        val awake = isScreenAwake()
        val activeNonFrozen = LinkedHashSet<AppTarget>()
        for (target in candidates) {
            val sample = samplePackageMemOnly(target.packageName, target.userId)
            if (sample.running && !sample.isFrozen) {
                if (target.packageName in playingSessionPkgs || awake) {
                    activeNonFrozen.add(target)
                }
            }
        }
        return activeNonFrozen
    }

    override fun listUserPackages(): Set<String> {
        val out = execCommand(listOf("pm", "list", "packages", "-3", "--user", "0"), timeoutMs = 5000)
        val result = LinkedHashSet<String>()
        for (line in out.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("package:")) {
                val pkg = trimmed.removePrefix("package:").trim()
                if (pkg.isNotEmpty()) result.add(pkg)
            }
        }
        return result
    }

    override fun listClonePackages(): List<AppTarget> {
        val rawUsers = parsePmListUsers(execCommand(listOf("pm", "list", "users"), timeoutMs = 4_000))
        val cloneUserIds = rawUsers.filter { u ->
            if (u.userId <= 0) return@filter false
            val isManaged = (u.flags and FLAG_MANAGED_PROFILE) != 0
            isCloneUser(u.userId, u.name, u.flags, isManaged)
        }.map { it.userId }
        if (cloneUserIds.isEmpty()) return emptyList()

        val targets = LinkedHashSet<AppTarget>()
        val runningByCloneUser = parsePsPackagesByUser(
            execCommand(listOf("ps", "-A", "-o", "USER,NAME"), timeoutMs = 4_000),
            targetUsers = cloneUserIds.toSet(),
        )
        for (uid in cloneUserIds) {
            val thirdParty = parsePmPackageLines(
                execCommand(listOf("pm", "list", "packages", "-3", "--user", uid.toString()), timeoutMs = 5_000),
            )
            for (pkg in thirdParty) {
                if (pkg != SafetyGuardrails.SELF_PACKAGE && !SafetyGuardrails.isSystemProtected(pkg)) {
                    targets.add(AppTarget(pkg, uid))
                }
            }
            for (pkg in runningByCloneUser[uid].orEmpty()) {
                if (pkg != SafetyGuardrails.SELF_PACKAGE && !SafetyGuardrails.isSystemProtected(pkg)) {
                    targets.add(AppTarget(pkg, uid))
                }
            }
        }
        return targets.toList()
    }

    override fun samplePackage(packageName: String, userId: Int): AppRuntimeSample {
        val base = samplePackageMemOnly(packageName, userId)
        val (restriction, bucket) = readRestriction(packageName, userId)
        return base.copy(
            restriction = restriction,
            standbyBucket = bucket,
        )
    }

    override fun samplePackagesBatch(packages: List<String>, userId: Int): Map<String, AppRuntimeSample> {
        val targets = packages.distinct().map { AppTarget(it, userId) }
        val batch = sampleTargetsBatch(targets)
        val result = LinkedHashMap<String, AppRuntimeSample>(packages.size)
        for (pkg in packages) {
            result[pkg] = batch[AppTarget(pkg, userId)] ?: AppRuntimeSample(packageName = pkg, userId = userId)
        }
        return result
    }

    override fun sampleTargetsBatch(targets: List<AppTarget>): Map<AppTarget, AppRuntimeSample> {
        if (targets.isEmpty()) return emptyMap()
        // 1. Single device-wide `dumpsys meminfo -S`, `ps`, and `dumpsys activity processes` shared across all users
        val memOut = execCommand(listOf("dumpsys", "meminfo", "-S"), timeoutMs = 15_000)
        val pidToUser = parsePsPidUsers(execCommand(listOf("ps", "-A", "-o", "USER,PID"), timeoutMs = 4_000))
        val procOut = execCommand(listOf("dumpsys", "activity", "processes"), timeoutMs = 6_000)

        val result = LinkedHashMap<AppTarget, AppRuntimeSample>(targets.size)
        for ((uid, group) in targets.groupBy { it.userId }) {
            val memByPkg = parseBatchMeminfo(memOut, targetUserId = uid, pidToUserId = pidToUser)
            val bgRestricted = queryRestrictedAppOps("RUN_ANY_IN_BACKGROUND", uid)
            val wlRestricted = queryRestrictedAppOps("WAKE_LOCK", uid)
            val buckets = queryAllStandbyBuckets(uid)
            val disabledPkgs = queryDisabledPackages(uid)
            val frozenPkgs = parseFrozenPackagesFromProcesses(procOut, targetUserId = uid)

            for (target in group) {
                val pkg = target.packageName
                val agg = memByPkg[pkg]
                val bucket = buckets[pkg] ?: StandbyBucket.UNKNOWN
                val restriction = AppRestriction(
                    restrictBackground = pkg in bgRestricted,
                    restrictWakeLock = pkg in wlRestricted,
                    restrictedBucket = bucket == StandbyBucket.RESTRICTED,
                    disableApp = pkg in disabledPkgs,
                )
                result[target] = AppRuntimeSample(
                    packageName = pkg,
                    userId = uid,
                    running = agg != null && agg.processCount > 0,
                    isFrozen = pkg in frozenPkgs,
                    totalPssKb = agg?.totalPssKb ?: 0L,
                    swapPssKb = agg?.swapPssKb ?: 0L,
                    processCount = agg?.processCount ?: 0,
                    restriction = restriction,
                    standbyBucket = bucket,
                )
            }
        }
        return result
    }

    private fun samplePackageMemOnly(packageName: String, userId: Int = 0): AppRuntimeSample {
        // IMPORTANT: AOSP `dumpsys meminfo` does NOT support `--user`, so it outputs blocks for
        // BOTH user 0 and clone users (e.g. user 999). Join each block's PID against `ps` to isolate [userId].
        val out = execCommand(
            listOf("dumpsys", "meminfo", "-S", "-s", "--package", packageName),
            timeoutMs = 5_000,
        )
        val pidToUser = parsePsPidUsers(execCommand(listOf("ps", "-A", "-o", "USER,PID"), timeoutMs = 3_000))
        return parseMeminfoSummary(packageName, userId, out, pidToUserId = pidToUser)
    }

    private fun queryRestrictedAppOps(op: String, userId: Int): Set<String> {
        val out = execCommand(
            listOf("cmd", "appops", "query-op", "--user", userId.toString(), op, "ignore"),
            timeoutMs = 4_000,
        )
        return parsePackageLines(out)
    }

    private fun queryAllStandbyBuckets(userId: Int): Map<String, StandbyBucket> {
        val out = execCommand(
            listOf("am", "get-standby-bucket", "--user", userId.toString()),
            timeoutMs = 4_000,
        )
        return parseAllStandbyBuckets(out)
    }

    private fun queryDisabledPackages(userId: Int): Set<String> {
        val out = execCommand(
            listOf("pm", "list", "packages", "-d", "--user", userId.toString()),
            timeoutMs = 4_000,
        )
        return parsePmPackageLines(out)
    }

    override fun forceStop(packageName: String, userId: Int): Boolean {
        if (SafetyGuardrails.isSystemProtected(packageName)) return false
        if (tryBinderForceStop(packageName, userId)) return true
        val res = execCommandWithCode(
            listOf("am", "force-stop", "--user", userId.toString(), packageName),
            timeoutMs = 5_000,
        )
        return res.first == 0
    }

    override fun softTrim(packageName: String, userId: Int): Boolean {
        if (SafetyGuardrails.isSystemProtected(packageName)) return false
        // Push-safe mode: send `trim-memory COMPLETE` ONLY to main & non-push sub-processes
        // (such as `:appbrand*`, `:tools*`, `:sandboxed_process*`), strictly skipping `:push` / `:msf`.
        // We intentionally do NOT call package-wide `am kill`, because `am kill` kills SERVICE_ADJ
        // processes and could terminate WeChat/QQ's `:push` daemon.
        val procs = listPackageProcesses(packageName, userId)
        var anyTrimmed = false
        for (proc in procs) {
            if (isPushProcess(proc)) continue
            val (code, _) = execCommandWithCode(
                listOf("am", "send-trim-memory", "--user", userId.toString(), proc, "COMPLETE"),
                timeoutMs = 3_000,
            )
            if (code == 0) anyTrimmed = true
        }
        return anyTrimmed || procs.isEmpty()
    }

    override fun applyRestriction(packageName: String, restriction: AppRestriction, userId: Int): Boolean {
        lastApplyRestrictionError = null
        if (SafetyGuardrails.isSystemProtected(packageName)) {
            lastApplyRestrictionError = "系统核心组件受保护，禁止修改后台限制"
            return false
        }

        // Apply `pm disable-user / enable` FIRST so an OEM SecurityException never leaves partial appops edits
        if (restriction.disableApp) {
            val (code, out) = execCommandWithCode(
                listOf("pm", "disable-user", "--user", userId.toString(), packageName),
                timeoutMs = 5_000,
            )
            if (code != 0) {
                lastApplyRestrictionError = if (out.contains("no root permission", ignoreCase = true) ||
                    out.contains("SecurityException", ignoreCase = true)
                ) {
                    "当前厂商系统限制免 Root 停用此预装组件（第三方应用仍可正常停用）"
                } else {
                    "停用 $packageName 失败：${out.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "错误码 $code"}"
                }
                HushLog.w("Bridge", "pm disable-user failed pkg=$packageName user=$userId rc=$code detail=${out.lineSequence().firstOrNull { it.isNotBlank() }?.take(120)}")
                return false
            }
            HushLog.i("Bridge", "pm disable-user succeeded pkg=$packageName user=$userId")
            forceStop(packageName, userId)
        } else {
            val currentlyDisabled = packageName in queryDisabledPackages(userId)
            if (currentlyDisabled) {
                val (code, out) = execCommandWithCode(
                    listOf("pm", "enable", "--user", userId.toString(), packageName),
                    timeoutMs = 5_000,
                )
                if (code != 0) {
                    lastApplyRestrictionError = "恢复启用 $packageName 失败：${out.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "错误码 $code"}"
                    return false
                }
            }
        }

        val bgMode = if (restriction.restrictBackground) "ignore" else "allow"
        val wlMode = if (restriction.restrictWakeLock) "ignore" else "allow"
        val bucket = if (restriction.restrictedBucket) "restricted" else "active"

        val r1 = execCommandWithCode(
            listOf("cmd", "appops", "set", "--user", userId.toString(), packageName, "RUN_ANY_IN_BACKGROUND", bgMode),
            timeoutMs = 4_000,
        ).first == 0
        val r2 = execCommandWithCode(
            listOf("cmd", "appops", "set", "--user", userId.toString(), packageName, "WAKE_LOCK", wlMode),
            timeoutMs = 4_000,
        ).first == 0
        val r3 = execCommandWithCode(
            listOf("am", "set-standby-bucket", "--user", userId.toString(), packageName, bucket),
            timeoutMs = 4_000,
        ).first == 0
        return r1 && r2 && r3
    }

    override fun readRestriction(packageName: String, userId: Int): Pair<AppRestriction, StandbyBucket> {
        val bgOut = execCommand(
            listOf("cmd", "appops", "get", "--user", userId.toString(), packageName, "RUN_ANY_IN_BACKGROUND"),
            timeoutMs = 3_000,
        )
        val wlOut = execCommand(
            listOf("cmd", "appops", "get", "--user", userId.toString(), packageName, "WAKE_LOCK"),
            timeoutMs = 3_000,
        )
        val bucketOut = execCommand(
            listOf("am", "get-standby-bucket", "--user", userId.toString(), packageName),
            timeoutMs = 3_000,
        ).trim()
        val disabled = packageName in queryDisabledPackages(userId)

        val bucket = parseStandbyBucket(bucketOut)
        val restriction = AppRestriction(
            restrictBackground = bgOut.contains(": ignore") || bgOut.contains(": deny"),
            restrictWakeLock = wlOut.contains(": ignore") || wlOut.contains(": deny"),
            restrictedBucket = bucket == StandbyBucket.RESTRICTED,
            disableApp = disabled,
        )
        return restriction to bucket
    }

    override fun getCurrentUserId(): Int {
        val out = execCommand(listOf("am", "get-current-user"), timeoutMs = 3_000).trim()
        return out.lineSequence().firstOrNull()?.trim()?.toIntOrNull() ?: 0
    }

    override fun listSecondaryUsers(): List<SecondaryUserInfo> {
        val rawUsers = parsePmListUsers(execCommand(listOf("pm", "list", "users"), timeoutMs = 4_000))
            .filter { it.userId > 0 }
        if (rawUsers.isEmpty()) return emptyList()

        val currentUser = getCurrentUserId()
        val anyRunning = rawUsers.any { it.running }
        val pidToUser = if (anyRunning) {
            parsePsPidUsers(execCommand(listOf("ps", "-A", "-o", "USER,PID"), timeoutMs = 4_000))
        } else {
            emptyMap()
        }
        val psProcCounts = if (anyRunning) {
            pidToUser.values.groupingBy { it }.eachCount()
        } else {
            emptyMap()
        }
        val memTotals = if (anyRunning) {
            parseMeminfoUserTotals(
                output = execCommand(listOf("dumpsys", "meminfo", "-S"), timeoutMs = 15_000),
                pidToUserId = pidToUser,
            )
        } else {
            emptyMap()
        }

        return rawUsers.map { u ->
            val memAgg = memTotals[u.userId]
            val isManaged = (u.flags and FLAG_MANAGED_PROFILE) != 0
            val isClone = isCloneUser(u.userId, u.name, u.flags, isManaged)
            val procs = maxOf(memAgg?.processCount ?: 0, psProcCounts[u.userId] ?: 0)
            SecondaryUserInfo(
                userId = u.userId,
                name = u.name.ifBlank { "User ${u.userId}" },
                running = u.running,
                isCurrent = u.userId == currentUser,
                processCount = if (u.running) procs else 0,
                totalPssKb = if (u.running) (memAgg?.totalPssKb ?: 0L) else 0L,
                swapPssKb = if (u.running) (memAgg?.swapPssKb ?: 0L) else 0L,
                isManagedProfile = isManaged,
                isCloneProfile = isClone,
            )
        }.sortedWith(
            compareByDescending<SecondaryUserInfo> { it.isFullSecondarySystem }
                .thenByDescending { it.running }
                .thenBy { it.userId }
        )
    }

    override fun stopUser(userId: Int): Boolean {
        if (userId <= 0) return false
        if (getCurrentUserId() == userId) return false
        val (code, out) = execCommandWithCode(
            listOf("am", "stop-user", "-w", "-f", userId.toString()),
            timeoutMs = 15_000,
        )
        if (code == 0) {
            HushLog.i("Bridge", "am stop-user -w -f $userId succeeded")
        } else {
            HushLog.w("Bridge", "am stop-user -w -f $userId failed rc=$code out=${out.take(120)}")
        }
        return code == 0
    }

    private fun listPackageProcesses(packageName: String, userId: Int): List<String> {
        val out = execCommand(listOf("ps", "-A", "-o", "USER,NAME"), timeoutMs = 3_000)
        val result = LinkedHashSet<String>()
        for (line in out.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("USER")) continue
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < 2) continue
            val procUser = extractPsUserId(parts[0]) ?: continue
            if (procUser != userId) continue
            val name = parts[1]
            if (name == packageName || name.startsWith("$packageName:")) {
                result.add(name)
            }
        }
        return result.toList()
    }

    private fun refreshUidCacheIfNeeded() {
        val now = nowMs()
        if (uidToPkgCache.isNotEmpty() && now - uidCacheUpdatedMs < 60_000L) return
        val out = execCommand(listOf("pm", "list", "packages", "-U", "--user", "0"), timeoutMs = 5_000)
        for (line in out.lineSequence()) {
            val m = UID_PKG_REGEX.find(line.trim()) ?: continue
            val pkg = m.groupValues[1]
            val uid = m.groupValues[2].toIntOrNull() ?: continue
            uidToPkgCache[uid] = pkg
        }
        uidCacheUpdatedMs = now
    }

    private fun tryBinderForceStop(packageName: String, userId: Int): Boolean {
        return runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "activity") as? android.os.IBinder ?: return false
            val stubClass = Class.forName("android.app.IActivityManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
            val iam = asInterface.invoke(null, binder) ?: return false
            val forceStop = iam.javaClass.getMethod("forceStopPackage", String::class.java, Int::class.javaPrimitiveType)
            forceStop.invoke(iam, packageName, userId)
            true
        }.getOrDefault(false)
    }

    private fun execCommand(args: List<String>, timeoutMs: Long): String =
        execCommandWithCode(args, timeoutMs).second

    private fun execCommandWithCode(args: List<String>, timeoutMs: Long): Pair<Int, String> {
        return runCatching {
            val proc = ProcessBuilder(args).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                HushLog.w("Bridge", "Shell timeout (${timeoutMs}ms): ${args.take(4).joinToString(" ")}")
                -1 to output
            } else {
                proc.exitValue() to output
            }
        }.getOrDefault(-1 to "")
    }

    internal data class BatchMemAggregate(
        var totalPssKb: Long = 0L,
        var swapPssKb: Long = 0L,
        var processCount: Int = 0,
    )

    internal data class ParsedPmUser(
        val userId: Int,
        val name: String,
        val flags: Int,
        val running: Boolean,
    )

    companion object {
        private const val FLAG_MANAGED_PROFILE = 0x20
        private const val FLAG_FULL = 0x400
        private const val FLAG_PROFILE = 0x1000
        private const val PER_USER_RANGE = 100_000

        private val PM_USER_LINE_REGEX = Regex("""UserInfo\{(\d+):([^:]*):([0-9a-fA-F]+)\}(?:\s+(running))?""")
        private val PS_USER_PREFIX_REGEX = Regex("""^u(\d+)_""")
        private val UID_PKG_REGEX = Regex("""^package:(\S+)\s+uid:(\d+)""")
        private val RESUMED_ACTIVITY_TARGET_REGEX = Regex("""(?:topResumedActivity|mResumedActivity|ResumedActivity)[=:][^\n]*?\bu(\d+)\s+([a-zA-Z0-9_.]+)/""")
        private val RESUMED_ACTIVITY_REGEX = Regex("""(?:topResumedActivity|mResumedActivity|ResumedActivity)[=:][^\n]*?\s+([a-zA-Z0-9_.]+)/""")
        private val TOTAL_PSS_REGEX = Regex("""TOTAL PSS:\s+(\d+)""")
        private val TOTAL_SWAP_PSS_REGEX = Regex("""TOTAL SWAP PSS:\s+(\d+)""")
        private val MEMINFO_PID_REGEX = Regex("""\*\* MEMINFO in pid (\d+) \[([^\]]+)\] \*\*""")
        private val BLOCK_PID_PREFIX_REGEX = Regex("""^(\d+)\s+\[([^\]]+)\]""")
        private val AUDIO_PLAYER_REGEX = Regex("""AudioPlaybackConfiguration[^\n]*?u/pid:(\d+)/(\d+)[^\n]*?state:(\w+)[^\n]*?mutedState:(\w+)""")
        private val MEDIA_SESSION_PKG_REGEX = Regex("""^\s*package=([a-zA-Z0-9_.]+)""")
        private val MEDIA_PLAYBACK_STATE_REGEX = Regex("""state=PlaybackState\s*\{state=(\w+|3)\b""")

        // Matches lines inside `Total PSS by process:` of `dumpsys meminfo -S`:
        // e.g. "  1,523,308K: com.example.video (pid 6000 / activities) (   612,000K in swap)"
        // e.g. "    643,705K: com.example.chat (pid 10472) (user 999) (  180,000K in swap)"
        private val BATCH_PSS_LINE_REGEX = Regex("""^\s*([\d,]+)K:\s+([a-zA-Z0-9_.:-]+)\s+\(pid\s+(\d+)""")
        private val SWAP_IN_LINE_REGEX = Regex("""\(\s*([\d,]+)K in swap\)""")
        private val USER_IN_LINE_REGEX = Regex("""\(user (\d+)\)""")
        private val STANDBY_LINE_REGEX = Regex("""^\s*([a-zA-Z0-9_.]+)\s*[:=]\s*(\d+)""")
        private val PROC_RECORD_REGEX = Regex("""\b\d+:([a-zA-Z0-9_.:-]+)/u(\d+)a\d+\b""")

        internal fun isCloneUser(userId: Int, name: String, flags: Int, isManaged: Boolean): Boolean {
            if (userId <= 0 || isManaged) return false
            if (userId == 999) return true
            if ((flags and FLAG_PROFILE) != 0 && (flags and FLAG_FULL) == 0) return true
            val lower = name.trim().lowercase()
            return lower.contains("xspace") ||
                lower.contains("multiapp") ||
                lower.contains("multi_app") ||
                lower.contains("dualapp") ||
                lower.contains("dual_app") ||
                lower.contains("parallel") ||
                lower.contains("clone") ||
                lower.contains("应用双开") ||
                lower.contains("应用分身")
        }

        internal fun extractPsUserId(psUserColumn: String): Int? =
            PS_USER_PREFIX_REGEX.find(psUserColumn)?.groupValues?.get(1)?.toIntOrNull()

        internal fun parsePsPackagesByUser(
            output: String,
            targetUsers: Set<Int>,
        ): Map<Int, Set<String>> {
            if (targetUsers.isEmpty()) return emptyMap()
            val result = HashMap<Int, MutableSet<String>>()
            for (line in output.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("USER")) continue
                val parts = trimmed.split(Regex("\\s+"))
                if (parts.size < 2) continue
                val uid = extractPsUserId(parts[0]) ?: continue
                if (uid !in targetUsers) continue
                val basePkg = parts[1].substringBefore(':')
                if (basePkg.contains('.') && !basePkg.startsWith("[")) {
                    result.getOrPut(uid) { LinkedHashSet() }.add(basePkg)
                }
            }
            return result
        }

        internal fun isPushProcess(processName: String): Boolean {
            val lower = processName.lowercase()
            return lower.endsWith(":push") ||
                lower.endsWith(":msf") ||
                lower.endsWith(":pushservice") ||
                lower.contains(":push_")
        }

        internal fun parseBatchMeminfo(
            output: String,
            targetUserId: Int = 0,
            pidToUserId: Map<Int, Int> = emptyMap(),
        ): Map<String, BatchMemAggregate> {
            val result = HashMap<String, BatchMemAggregate>()
            var inPssSection = false
            for (line in output.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("Total PSS by process:")) {
                    inPssSection = true
                    continue
                }
                if (inPssSection) {
                    if (trimmed.startsWith("Total PSS by OOM") || trimmed.startsWith("Total PSS by category")) {
                        break
                    }
                    val m = BATCH_PSS_LINE_REGEX.find(line) ?: continue
                    val pid = m.groupValues[3].toIntOrNull() ?: -1
                    val lineUser = USER_IN_LINE_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull()
                        ?: pidToUserId[pid]
                        ?: 0
                    if (lineUser != targetUserId) continue

                    val pssKb = m.groupValues[1].replace(",", "").toLongOrNull() ?: 0L
                    val procName = m.groupValues[2]
                    val basePkg = procName.substringBefore(':')
                    val swapKb = SWAP_IN_LINE_REGEX.find(line)?.groupValues?.get(1)
                        ?.replace(",", "")?.toLongOrNull() ?: 0L

                    val agg = result.getOrPut(basePkg) { BatchMemAggregate() }
                    agg.totalPssKb += pssKb
                    agg.swapPssKb += swapKb
                    agg.processCount++
                }
            }
            return result
        }

        internal fun parsePmListUsers(output: String): List<ParsedPmUser> {
            val list = ArrayList<ParsedPmUser>()
            for (line in output.lineSequence()) {
                val m = PM_USER_LINE_REGEX.find(line) ?: continue
                val id = m.groupValues[1].toIntOrNull() ?: continue
                val name = m.groupValues[2].trim()
                val flags = m.groupValues[3].toIntOrNull(16) ?: 0
                val running = m.groupValues[4].equals("running", ignoreCase = true)
                list.add(ParsedPmUser(id, name, flags, running))
            }
            return list
        }

        internal fun parsePsPidUsers(output: String): Map<Int, Int> {
            val result = HashMap<Int, Int>()
            for (line in output.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("USER")) continue
                val parts = trimmed.split(Regex("\\s+"))
                if (parts.size < 2) continue
                val m = PS_USER_PREFIX_REGEX.find(parts[0]) ?: continue
                val userId = m.groupValues[1].toIntOrNull() ?: continue
                val pid = parts[1].toIntOrNull() ?: continue
                if (userId > 0) {
                    result[pid] = userId
                }
            }
            return result
        }

        internal fun parseMeminfoUserTotals(
            output: String,
            pidToUserId: Map<Int, Int> = emptyMap(),
        ): Map<Int, BatchMemAggregate> {
            val result = HashMap<Int, BatchMemAggregate>()
            var inPssSection = false
            for (line in output.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("Total PSS by process:")) {
                    inPssSection = true
                    continue
                }
                if (inPssSection) {
                    if (trimmed.startsWith("Total PSS by OOM") || trimmed.startsWith("Total PSS by category")) {
                        break
                    }
                    val m = BATCH_PSS_LINE_REGEX.find(line) ?: continue
                    val pid = m.groupValues[3].toIntOrNull() ?: -1
                    val lineUser = USER_IN_LINE_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull()
                        ?: pidToUserId[pid]
                        ?: 0
                    if (lineUser <= 0) continue

                    val pssKb = m.groupValues[1].replace(",", "").toLongOrNull() ?: 0L
                    val swapKb = SWAP_IN_LINE_REGEX.find(line)?.groupValues?.get(1)
                        ?.replace(",", "")?.toLongOrNull() ?: 0L
                    val agg = result.getOrPut(lineUser) { BatchMemAggregate() }
                    agg.totalPssKb += pssKb
                    agg.swapPssKb += swapKb
                    agg.processCount++
                }
            }
            return result
        }

        internal fun parsePackageLines(output: String): Set<String> {
            val set = HashSet<String>()
            for (line in output.lineSequence()) {
                val t = line.trim()
                if (t.isNotEmpty() && !t.contains(' ') && t.contains('.')) {
                    set.add(t)
                }
            }
            return set
        }

        internal fun parsePmPackageLines(output: String): Set<String> {
            val set = HashSet<String>()
            for (line in output.lineSequence()) {
                val t = line.trim()
                if (t.startsWith("package:")) {
                    val pkg = t.removePrefix("package:").trim()
                    if (pkg.isNotEmpty()) set.add(pkg)
                }
            }
            return set
        }

        internal fun parseAllStandbyBuckets(output: String): Map<String, StandbyBucket> {
            val map = HashMap<String, StandbyBucket>()
            for (line in output.lineSequence()) {
                val m = STANDBY_LINE_REGEX.find(line) ?: continue
                map[m.groupValues[1]] = parseStandbyBucket(m.groupValues[2])
            }
            return map
        }

        internal fun parseFrozenPackagesFromProcesses(output: String, targetUserId: Int = 0): Set<String> {
            val frozen = HashSet<String>()
            var lastPkg: String? = null
            var lastUser = 0
            for (line in output.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("*APP*") || trimmed.startsWith("Proc #")) {
                    val pm = PROC_RECORD_REGEX.find(line)
                    if (pm != null) {
                        lastPkg = pm.groupValues[1].substringBefore(':')
                        lastUser = pm.groupValues[2].toIntOrNull() ?: 0
                        if (lastUser == targetUserId && containsFrozenTrue(line)) {
                            frozen.add(lastPkg)
                        }
                    } else {
                        lastPkg = null
                    }
                } else if (lastPkg != null && lastUser == targetUserId && containsFrozenTrue(line)) {
                    frozen.add(lastPkg)
                }
            }
            return frozen
        }

        private fun containsFrozenTrue(line: String): Boolean =
            line.contains("isFrozen=true") ||
                line.contains("mFrozen=true") ||
                line.contains("frozen=true")

        internal fun parseKeyValueKbFile(path: String): Map<String, Long> {
            val file = File(path)
            if (!file.canRead()) return emptyMap()
            return parseMeminfoText(file.readText())
        }

        internal fun parseMeminfoText(text: String): Map<String, Long> {
            val map = HashMap<String, Long>()
            for (line in text.lineSequence()) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val key = line.substring(0, idx).trim()
                val rest = line.substring(idx + 1).trim()
                val numToken = rest.takeWhile { it.isDigit() }
                val value = numToken.toLongOrNull() ?: continue
                map[key] = value
            }
            return map
        }

        internal fun parseKeyValueLongFile(path: String): Map<String, Long> {
            val file = File(path)
            if (!file.canRead()) return emptyMap()
            return parseVmstatText(file.readText())
        }

        internal fun parseVmstatText(text: String): Map<String, Long> {
            val map = HashMap<String, Long>()
            for (line in text.lineSequence()) {
                val parts = line.trim().split(' ')
                if (parts.size >= 2) {
                    val v = parts[1].toLongOrNull() ?: continue
                    map[parts[0]] = v
                }
            }
            return map
        }

        internal fun parseForegroundPackage(dumpsysActivitiesOutput: String): String? =
            parseForegroundTarget(dumpsysActivitiesOutput)?.packageName

        internal fun parseForegroundTarget(dumpsysActivitiesOutput: String): AppTarget? {
            for (line in dumpsysActivitiesOutput.lineSequence()) {
                if (!line.contains("ResumedActivity")) continue
                val um = RESUMED_ACTIVITY_TARGET_REGEX.find(line)
                if (um != null) {
                    val uid = um.groupValues[1].toIntOrNull() ?: 0
                    return AppTarget(um.groupValues[2], uid)
                }
                val m = RESUMED_ACTIVITY_REGEX.find(line)
                if (m != null) return AppTarget(m.groupValues[1], 0)
            }
            return null
        }

        internal fun parseScreenAwake(dumpsysPowerOutput: String): Boolean {
            for (line in dumpsysPowerOutput.lineSequence()) {
                val t = line.trim()
                if (t.startsWith("mWakefulness=")) {
                    val value = t.removePrefix("mWakefulness=").trim()
                    return value.equals("Awake", ignoreCase = true)
                }
            }
            return true
        }

        internal fun parseStartedAudibleUids(dumpsysAudioOutput: String): Set<Int> {
            val uids = LinkedHashSet<Int>()
            for (m in AUDIO_PLAYER_REGEX.findAll(dumpsysAudioOutput)) {
                val uid = m.groupValues[1].toIntOrNull() ?: continue
                val state = m.groupValues[3]
                val muted = m.groupValues[4]
                if (state.equals("started", ignoreCase = true) && muted.equals("none", ignoreCase = true)) {
                    uids.add(uid)
                }
            }
            return uids
        }

        internal fun parsePlayingMediaSessions(dumpsysMediaSessionOutput: String): Set<String> {
            val playing = LinkedHashSet<String>()
            var currentPkg: String? = null
            for (line in dumpsysMediaSessionOutput.lineSequence()) {
                val pkgMatch = MEDIA_SESSION_PKG_REGEX.find(line)
                if (pkgMatch != null) {
                    currentPkg = pkgMatch.groupValues[1]
                    continue
                }
                val stateMatch = MEDIA_PLAYBACK_STATE_REGEX.find(line)
                if (stateMatch != null && currentPkg != null) {
                    val s = stateMatch.groupValues[1]
                    if (s == "3" || s.equals("PLAYING", ignoreCase = true)) {
                        playing.add(currentPkg)
                    }
                }
            }
            return playing
        }

        internal fun parseMeminfoSummary(
            packageName: String,
            userId: Int,
            output: String,
            pidToUserId: Map<Int, Int> = emptyMap(),
        ): AppRuntimeSample {
            if (output.contains("No process found for:")) {
                return AppRuntimeSample(packageName = packageName, userId = userId, running = false)
            }
            var totalPss = 0L
            var totalSwap = 0L
            var processCount = 0
            var anyFrozen = false
            var allFrozen = true

            val blocks = output.split("** MEMINFO in pid ")
            if (blocks.size <= 1) {
                val pss = TOTAL_PSS_REGEX.find(output)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val swap = TOTAL_SWAP_PSS_REGEX.find(output)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val running = pss > 0L || MEMINFO_PID_REGEX.containsMatchIn(output)
                val frozen = output.contains("is frozen!")
                return AppRuntimeSample(
                    packageName = packageName,
                    userId = userId,
                    running = running,
                    isFrozen = frozen,
                    totalPssKb = pss,
                    swapPssKb = swap,
                    processCount = if (running) 1 else 0,
                )
            }

            for (i in 1 until blocks.size) {
                val block = blocks[i]
                if (pidToUserId.isNotEmpty()) {
                    val pid = BLOCK_PID_PREFIX_REGEX.find(block)?.groupValues?.get(1)?.toIntOrNull()
                    if (pid != null) {
                        val blockUser = pidToUserId[pid] ?: 0
                        if (blockUser != userId) continue
                    }
                }
                processCount++
                totalPss += TOTAL_PSS_REGEX.find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                totalSwap += TOTAL_SWAP_PSS_REGEX.find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val blockFrozen = block.contains("is frozen!")
                if (blockFrozen) anyFrozen = true else allFrozen = false
            }

            return AppRuntimeSample(
                packageName = packageName,
                userId = userId,
                running = processCount > 0,
                isFrozen = processCount > 0 && (allFrozen || anyFrozen),
                totalPssKb = totalPss,
                swapPssKb = totalSwap,
                processCount = processCount,
            )
        }

        internal fun parseStandbyBucket(raw: String): StandbyBucket {
            val num = raw.trim().toIntOrNull()
            return when (num) {
                5, 10 -> StandbyBucket.ACTIVE
                20 -> StandbyBucket.WORKING_SET
                30 -> StandbyBucket.FREQUENT
                40 -> StandbyBucket.RARE
                45 -> StandbyBucket.RESTRICTED
                else -> StandbyBucket.UNKNOWN
            }
        }
    }
}
