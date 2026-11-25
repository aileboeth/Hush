package com.aileboeth.hush.daemon

import com.aileboeth.hush.contract.StandbyBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSystemBridgeParserTest {

    @Test
    fun parsesOriginOsMeminfoSummaryWithSwapAndFrozenFlag() {
        val sample = """
            ** MEMINFO in pid 30079 [com.example.reader] **
             App Summary
                       TOTAL PSS:   396273            TOTAL RSS:   186040       TOTAL SWAP PSS:   387581
            ** APP: com.example.reader is frozen! **
            ** MEMINFO in pid 32024 [com.example.reader:push] **
                       TOTAL PSS:   131681            TOTAL RSS:   113892       TOTAL SWAP PSS:    90000
        """.trimIndent()

        val parsed = AndroidSystemBridge.parseMeminfoSummary("com.example.reader", 0, sample)
        assertTrue(parsed.running)
        assertTrue(parsed.isFrozen)
        assertEquals(2, parsed.processCount)
        assertEquals(396273L + 131681L, parsed.totalPssKb)
        assertEquals(387581L + 90000L, parsed.swapPssKb)
    }

    @Test
    fun parsesBatchMeminfoAggregatingSubprocessesAndFilteringDualUser() {
        val sample = """
            Total PSS by process:
              1,523,308K: com.example.video (pid 6000 / activities)                (  612,000K in swap)
                707,789K: com.example.chat (pid 23455 / activities)                (  200,000K in swap)
                643,705K: com.example.chat (pid 10472) (user 999)                  (  180,000K in swap)
                183,600K: com.example.chat:push (pid 12219)                        (   40,000K in swap)
                140,939K: com.example.video:sandboxed_process0 (pid 7422)          (   50,000K in swap)
            Total PSS by OOM adjustment:
        """.trimIndent()

        val user0 = AndroidSystemBridge.parseBatchMeminfo(sample, targetUserId = 0)
        assertEquals(1_523_308L + 140_939L, user0["com.example.video"]?.totalPssKb)
        assertEquals(612_000L + 50_000L, user0["com.example.video"]?.swapPssKb)
        assertEquals(2, user0["com.example.video"]?.processCount)

        // Must NOT merge (user 999) dual-space into user 0
        assertEquals(707_789L + 183_600L, user0["com.example.chat"]?.totalPssKb)
        assertEquals(2, user0["com.example.chat"]?.processCount)

        val user999 = AndroidSystemBridge.parseBatchMeminfo(sample, targetUserId = 999)
        assertEquals(643_705L, user999["com.example.chat"]?.totalPssKb)
        assertEquals(1, user999["com.example.chat"]?.processCount)
    }

    @Test
    fun parsesStandbyBucketsAndFrozenOomAndAudioState() {
        val buckets = AndroidSystemBridge.parseAllStandbyBuckets(
            """
            com.example.video: 45
            com.example.chat: 10
            com.example.shop: 40
            """.trimIndent()
        )
        assertEquals(StandbyBucket.RESTRICTED, buckets["com.example.video"])
        assertEquals(StandbyBucket.ACTIVE, buckets["com.example.chat"])
        assertEquals(StandbyBucket.RARE, buckets["com.example.shop"])

        val frozen = AndroidSystemBridge.parseFrozenPackagesFromProcesses(
            """
              *APP* UID 10315 ProcessRecord{730cc01 30079:com.example.reader/u0a315}
                hasPendingCompaction=false isFreezeExempt=false isPendingFreeze=false isFrozen=false
                isFrozen=true
              *APP* UID 99910326 ProcessRecord{f3874d0 10472:com.example.chat/u999a326}
                isFrozen=true
            """.trimIndent(),
            targetUserId = 0,
        )
        assertTrue("com.example.reader" in frozen)
        assertFalse("com.example.chat" in frozen)

        val actOut = "  ResumedActivity: ActivityRecord{70009256 u0 com.example.video/.MainActivity t6 d0}"
        assertEquals("com.example.video", AndroidSystemBridge.parseForegroundPackage(actOut))

        assertFalse(AndroidSystemBridge.parseScreenAwake("  mWakefulness=Asleep"))
        assertTrue(AndroidSystemBridge.parseScreenAwake("  mWakefulness=Awake"))

        val audioOut = """
            AudioPlaybackConfiguration piid:6687 deviceIds:[3] type:android.media.AudioTrack u/pid:10289/6000 state:paused attr:AudioAttributes: usage=USAGE_MEDIA mutedState:clientVolume
            AudioPlaybackConfiguration piid:6743 deviceIds:[3] type:android.media.AudioTrack u/pid:10315/30079 state:started attr:AudioAttributes: usage=USAGE_MEDIA mutedState:none
        """.trimIndent()
        assertEquals(setOf(10315), AndroidSystemBridge.parseStartedAudibleUids(audioOut))

        val mediaOut = """
              package=com.example.frozen
              active=true
              state=PlaybackState {state=PAUSED(2), position=1000}
              package=com.example.music
              active=true
              state=PlaybackState {state=PLAYING(3), position=2000}
        """.trimIndent()
        assertEquals(setOf("com.example.music"), AndroidSystemBridge.parsePlayingMediaSessions(mediaOut))

        assertTrue(AndroidSystemBridge.isPushProcess("com.example.chat:push"))
        assertTrue(AndroidSystemBridge.isPushProcess("com.example.chat:MSF"))
        assertFalse(AndroidSystemBridge.isPushProcess("com.example.chat:appbrand1"))
    }

    @Test
    fun parsesPmListUsersAndSecondaryUserMemoryTotals() {
        val pmOut = """
            Users:
            	UserInfo{0:机主:c13} running
            	UserInfo{10:cys:410} running
            	UserInfo{11:访客:414}
            	UserInfo{999:应用分身:1010} running
        """.trimIndent()
        val users = AndroidSystemBridge.parsePmListUsers(pmOut)
        assertEquals(4, users.size)
        assertEquals(0, users[0].userId)
        assertTrue(users[0].running)
        assertEquals(10, users[1].userId)
        assertEquals("cys", users[1].name)
        assertEquals(0x410, users[1].flags)
        assertTrue(users[1].running)
        assertEquals(11, users[2].userId)
        assertFalse(users[2].running)
        assertEquals(999, users[3].userId)

        val memOut = """
            Total PSS by process:
              500,000K: com.example.main (pid 1000)                         (  100,000K in swap)
              300,000K: com.bbk.launcher2 (pid 25145)                       (   80,000K in swap)
              250,000K: com.android.settings (pid 32666)                    (   40,000K in swap)
              180,000K: com.tencent.mm (pid 10472) (user 999)               (   30,000K in swap)
            Total PSS by OOM adjustment:
        """.trimIndent()
        val psOut = """
            USER           PID
            u0_a100       1000
            u10_a134     25145
            u10_system   32666
            u999_a210    10472
        """.trimIndent()
        val pidUsers = AndroidSystemBridge.parsePsPidUsers(psOut)
        assertFalse(pidUsers.containsKey(1000))
        assertEquals(10, pidUsers[25145])
        assertEquals(10, pidUsers[32666])
        assertEquals(999, pidUsers[10472])

        // OriginOS omits `(user 10)` in `dumpsys meminfo -S`: PID join resolves exact PSS + Swap!
        val memTotals = AndroidSystemBridge.parseMeminfoUserTotals(memOut, pidToUserId = pidUsers)
        assertFalse(memTotals.containsKey(0))
        assertEquals(550_000L, memTotals[10]?.totalPssKb)
        assertEquals(120_000L, memTotals[10]?.swapPssKb)
        assertEquals(2, memTotals[10]?.processCount)
        assertEquals(180_000L, memTotals[999]?.totalPssKb)

        // User 0 batch meminfo must ALSO exclude PID-joined user-10 processes!
        val user0Batch = AndroidSystemBridge.parseBatchMeminfo(memOut, targetUserId = 0, pidToUserId = pidUsers)
        assertEquals(500_000L, user0Batch["com.example.main"]?.totalPssKb)
        assertFalse(user0Batch.containsKey("com.bbk.launcher2"))
        assertFalse(user0Batch.containsKey("com.android.settings"))

        val disabled = AndroidSystemBridge.parsePmPackageLines(
            """
            package:com.vivo.magazine
            package:com.example.bloat
            """.trimIndent(),
        )
        assertEquals(setOf("com.vivo.magazine", "com.example.bloat"), disabled)
    }
}
