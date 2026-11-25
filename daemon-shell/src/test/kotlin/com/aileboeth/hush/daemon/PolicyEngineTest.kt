package com.aileboeth.hush.daemon

import com.aileboeth.hush.contract.AppConfigSnapshot
import com.aileboeth.hush.contract.AppGroup
import com.aileboeth.hush.contract.AppRestriction
import com.aileboeth.hush.contract.AppRuntimeSample
import com.aileboeth.hush.contract.CleanupAction
import com.aileboeth.hush.contract.CleanupTrigger
import com.aileboeth.hush.contract.MemoryOverview
import com.aileboeth.hush.contract.PolicyConfig
import com.aileboeth.hush.contract.SecondaryUserInfo
import com.aileboeth.hush.contract.StandbyBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineTest {

    private class FakeBridge : SystemBridge {
        var timeMs: Long = 1_700_000_000_000L
        var fgPkg: String? = "com.bbk.launcher2"
        var awake: Boolean = true
        var activeAudio: Set<String> = emptySet()
        var memory: MemoryOverview = MemoryOverview(
            memTotalKb = 11_500_000L,
            memAvailableKb = 4_000_000L,
            memFreeKb = 600_000L,
            swapTotalKb = 12_000_000L,
            swapFreeKb = 6_000_000L,
        )
        val runningPss = LinkedHashMap<String, Long>()
        val forceStopped = ArrayList<String>()
        val softTrimmed = ArrayList<String>()
        val restrictions = LinkedHashMap<String, AppRestriction>()
        var activeUserId: Int = 0
        val secondaryUsers = ArrayList<SecondaryUserInfo>()
        val stoppedUsers = ArrayList<Int>()

        override fun nowMs(): Long = timeMs
        override fun currentPid(): Int = 4242
        override fun currentUid(): Int = 2000
        override fun getSelfApkPath(): String = "/data/app/com.aileboeth.hush/base.apk"
        override fun readMemoryOverview(): MemoryOverview = memory
        override fun getForegroundPackage(): String? = fgPkg
        override fun isScreenAwake(): Boolean = awake
        override fun getActiveAudioPackages(): Set<String> = activeAudio
        override fun listUserPackages(): Set<String> = runningPss.keys.toSet()

        override fun samplePackage(packageName: String, userId: Int): AppRuntimeSample {
            val pss = runningPss[packageName] ?: 0L
            return AppRuntimeSample(
                packageName = packageName,
                userId = userId,
                running = pss > 0L,
                totalPssKb = pss,
                swapPssKb = pss / 2,
                processCount = if (pss > 0L) 2 else 0,
                restriction = restrictions[packageName] ?: AppRestriction(),
                standbyBucket = if (restrictions[packageName]?.restrictedBucket == true) {
                    StandbyBucket.RESTRICTED
                } else {
                    StandbyBucket.ACTIVE
                },
            )
        }

        override fun forceStop(packageName: String, userId: Int): Boolean {
            forceStopped.add(packageName)
            runningPss.remove(packageName)
            return true
        }

        override fun softTrim(packageName: String, userId: Int): Boolean {
            softTrimmed.add(packageName)
            val before = runningPss[packageName] ?: 0L
            runningPss[packageName] = (before * 60) / 100 // simulate 40% cache shed while staying alive
            return true
        }

        override fun applyRestriction(packageName: String, restriction: AppRestriction, userId: Int): Boolean {
            restrictions[packageName] = restriction
            return true
        }

        override fun readRestriction(packageName: String, userId: Int): Pair<AppRestriction, StandbyBucket> {
            val r = restrictions[packageName] ?: AppRestriction()
            val b = if (r.restrictedBucket) StandbyBucket.RESTRICTED else StandbyBucket.ACTIVE
            return r to b
        }

        override fun getCurrentUserId(): Int = activeUserId

        override fun listSecondaryUsers(): List<SecondaryUserInfo> =
            secondaryUsers.map { it.copy(isCurrent = it.userId == activeUserId) }

        override fun stopUser(userId: Int): Boolean {
            if (userId <= 0 || userId == activeUserId) return false
            stoppedUsers.add(userId)
            val idx = secondaryUsers.indexOfFirst { it.userId == userId }
            if (idx >= 0) {
                val u = secondaryUsers[idx]
                memory = memory.copy(memAvailableKb = memory.memAvailableKb + u.totalPssKb)
                secondaryUsers[idx] = u.copy(running = false, processCount = 0, totalPssKb = 0L, swapPssKb = 0L)
            }
            return true
        }
    }

    @Test
    fun backgroundTimeoutForceStopsManagedAndSoftTrimsMessagingWithoutKillingPush() {
        val bridge = FakeBridge()
        bridge.runningPss["com.example.video"] = 1_200_000L
        bridge.runningPss["com.example.chat"] = 700_000L
        bridge.runningPss["com.example.vault"] = 100_000L

        val engine = PolicyEngine(
            bridge = bridge,
            initialConfig = AppConfigSnapshot(
                appGroups = mapOf(
                    "com.example.video" to AppGroup.FORCE_STOP,
                    "com.example.chat" to AppGroup.SOFT_TRIM,
                    "com.example.vault" to AppGroup.PROTECTED,
                ),
                policy = PolicyConfig(
                    backgroundTimeoutMinutes = 3,
                    memoryPressureEnabled = false,
                ),
            ),
        )

        // User opens video app then switches away
        bridge.fgPkg = "com.example.video"
        engine.tick()
        bridge.fgPkg = "com.example.chat"
        bridge.timeMs += 10_000L
        engine.tick()
        bridge.fgPkg = "com.bbk.launcher2"
        bridge.timeMs += 10_000L
        engine.tick()

        // Only 1 minute elapsed -> nothing killed yet
        bridge.timeMs += 60_000L
        engine.tick()
        assertTrue(bridge.forceStopped.isEmpty())
        assertTrue(bridge.softTrimmed.isEmpty())

        // 4 minutes elapsed -> video force-stopped, chat ONLY soft-trimmed, vault untouched
        bridge.timeMs += 3 * 60_000L
        engine.tick()
        assertEquals(listOf("com.example.video"), bridge.forceStopped)
        assertEquals(listOf("com.example.chat"), bridge.softTrimmed)
        assertTrue(bridge.runningPss.containsKey("com.example.chat")) // still alive for push!
        assertTrue(bridge.runningPss.containsKey("com.example.vault"))

        val logs = engine.getLogs()
        assertEquals(2, logs.size)
        assertTrue(logs.any { it.packageName == "com.example.video" && it.action == CleanupAction.FORCE_STOP })
        assertTrue(logs.any { it.packageName == "com.example.chat" && it.action == CleanupAction.SOFT_TRIM && it.freedKb > 0 })
    }

    @Test
    fun activeAudioExemptionProtectsAudiobookAppDuringScreenOff() {
        val bridge = FakeBridge()
        bridge.runningPss["com.example.reader"] = 650_000L
        bridge.runningPss["com.example.shop"] = 700_000L
        bridge.activeAudio = setOf("com.example.reader")

        val engine = PolicyEngine(
            bridge = bridge,
            initialConfig = AppConfigSnapshot(
                appGroups = mapOf(
                    "com.example.reader" to AppGroup.FORCE_STOP,
                    "com.example.shop" to AppGroup.FORCE_STOP,
                ),
                policy = PolicyConfig(
                    exemptActiveAudio = true,
                    screenOffCleanupEnabled = true,
                    screenOffDelayMinutes = 2,
                    memoryPressureEnabled = false,
                ),
            ),
        )

        bridge.awake = false
        engine.tick()
        bridge.timeMs += 3 * 60_000L
        engine.tick()

        assertEquals(listOf("com.example.shop"), bridge.forceStopped)
        assertTrue(bridge.runningPss.containsKey("com.example.reader"))
        val skippedLog = engine.getLogs().firstOrNull { it.packageName == "com.example.reader" }
        assertTrue(skippedLog?.skippedReason != null)
        assertEquals(CleanupTrigger.SCREEN_OFF, skippedLog?.trigger)
    }

    @Test
    fun memoryPressureEvictsLeastRecentlyUsedManagedAppFirst() {
        val bridge = FakeBridge()
        bridge.runningPss["com.example.old"] = 800_000L
        bridge.runningPss["com.example.recent"] = 700_000L

        val engine = PolicyEngine(
            bridge = bridge,
            initialConfig = AppConfigSnapshot(
                appGroups = mapOf(
                    "com.example.old" to AppGroup.FORCE_STOP,
                    "com.example.recent" to AppGroup.FORCE_STOP,
                ),
                policy = PolicyConfig(
                    backgroundTimeoutMinutes = 30, // high so timeout does not trigger
                    memoryPressureEnabled = true,
                    managedBudgetMb = 1000, // 1500MB total exceeds 1000MB budget
                ),
            ),
        )

        bridge.fgPkg = "com.example.old"
        engine.tick()
        bridge.timeMs += 10_000L
        bridge.fgPkg = "com.example.recent"
        engine.tick()
        bridge.timeMs += 10_000L
        bridge.fgPkg = "com.bbk.launcher2"
        engine.tick()

        // Within 60s minimum grace window -> neither app should be insta-killed
        assertTrue(bridge.forceStopped.isEmpty())

        // After 65s grace window -> evicting `old` (800MB) drops remaining managed PSS to 700MB <= 1000MB budget, so `recent` stays!
        bridge.timeMs += 65_000L
        engine.tick()
        assertEquals(listOf("com.example.old"), bridge.forceStopped)
        assertTrue(bridge.runningPss.containsKey("com.example.recent"))
    }

    @Test
    fun restrictionsBlockedForSoftTrimAndRestoredCleanlyForForceStop() {
        val bridge = FakeBridge()
        val engine = PolicyEngine(
            bridge = bridge,
            initialConfig = AppConfigSnapshot(
                appGroups = mapOf(
                    "com.example.chat" to AppGroup.SOFT_TRIM,
                    "com.example.shop" to AppGroup.FORCE_STOP,
                ),
            ),
        )

        val blocked = engine.setRestriction(
            "com.example.chat",
            AppRestriction(restrictBackground = true),
        )
        assertTrue(blocked.isFailure)

        val allowed = engine.setRestriction(
            "com.example.shop",
            AppRestriction(restrictBackground = true, restrictWakeLock = true, restrictedBucket = true, disableApp = true),
        )
        assertTrue(allowed.isSuccess)
        assertTrue(bridge.restrictions["com.example.shop"]?.hasAnyRestriction == true)
        assertTrue(bridge.restrictions["com.example.shop"]?.disableApp == true)

        // Critical installer package must always be blocked from disabling/restricting
        assertTrue(
            engine.setRestriction(
                "com.android.packageinstaller",
                AppRestriction(disableApp = true),
            ).isFailure,
        )

        val restored = engine.restoreAllRestrictions()
        assertEquals(1, restored.getOrThrow())
        assertFalse(bridge.restrictions["com.example.shop"]?.hasAnyRestriction == true)
        assertFalse(bridge.restrictions["com.example.shop"]?.disableApp == true)
    }

    @Test
    fun autoStopsFullSecondarySystemAfterLeaveDelayWhileProtectingActiveAndCloneUsers() {
        val bridge = FakeBridge()
        bridge.secondaryUsers.addAll(
            listOf(
                SecondaryUserInfo(
                    userId = 10,
                    name = "cys",
                    running = true,
                    processCount = 51,
                    totalPssKb = 840_000L,
                    swapPssKb = 600_000L,
                ),
                SecondaryUserInfo(
                    userId = 999,
                    name = "应用分身",
                    running = true,
                    processCount = 4,
                    totalPssKb = 180_000L,
                    isCloneProfile = true,
                ),
            ),
        )
        val engine = PolicyEngine(
            bridge = bridge,
            initialConfig = AppConfigSnapshot(
                // Deliberately ZERO managed apps to prove secondary-user stop works standalone!
                appGroups = emptyMap(),
                policy = PolicyConfig(
                    enabled = true,
                    autoStopSecondaryUsers = true,
                    secondaryUserStopDelayMinutes = 2,
                    screenOffStopSecondaryUsers = true,
                ),
            ),
        )

        // While user is actively inside User 10 -> MUST NOT stop it
        bridge.activeUserId = 10
        engine.tick()
        bridge.timeMs += 5 * 60_000L
        engine.tick()
        assertTrue(bridge.stoppedUsers.isEmpty())
        assertTrue(engine.manualStopUser(10).isFailure)
        assertTrue(engine.manualStopUser(0).isFailure)

        // User switches back to primary (User 0) -> starts 2-minute countdown
        bridge.activeUserId = 0
        engine.tick()
        bridge.timeMs += 60_000L // only 1 minute -> still waiting
        engine.tick()
        assertTrue(bridge.stoppedUsers.isEmpty())

        // 2 minutes elapsed -> User 10 stopped cleanly, User 999 clone untouched!
        bridge.timeMs += 65_000L
        engine.tick()
        assertEquals(listOf(10), bridge.stoppedUsers)
        assertTrue(bridge.secondaryUsers.first { it.userId == 999 }.running)

        val log = engine.getLogs().firstOrNull()
        assertEquals(CleanupAction.STOP_USER, log?.action)
        assertEquals("第二系统 · cys", log?.appLabel)
        assertEquals(840_000L, log?.freedKb)
        val (todayCount, todayFreed) = engine.getTodayStats()
        assertEquals(1, todayCount)
        assertEquals(840_000L, todayFreed)
    }
}
