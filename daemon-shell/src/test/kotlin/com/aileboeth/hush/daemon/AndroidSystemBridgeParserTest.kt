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
}
