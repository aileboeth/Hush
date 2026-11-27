package com.aileboeth.hush.engine

import com.aileboeth.hush.contract.CleanupAction
import com.aileboeth.hush.contract.CleanupEvent
import com.aileboeth.hush.contract.CleanupTrigger
import org.junit.Assert.assertEquals
import org.junit.Test

class RealGuardControllerLogMergeTest {

    @Test
    fun mergesFreshDaemonLogsWithPersistedHistoryAcrossRestarts() {
        val persisted = listOf(
            CleanupEvent(
                id = 2,
                timestampMs = 2_000L,
                packageName = "com.example.video",
                appLabel = "短视频",
                action = CleanupAction.FORCE_STOP,
                trigger = CleanupTrigger.BACKGROUND_TIMEOUT,
                freedKb = 400_000L,
            ),
            CleanupEvent(
                id = 1,
                timestampMs = 1_000L,
                packageName = "user:10",
                appLabel = "第二系统 · 空间",
                action = CleanupAction.STOP_USER,
                trigger = CleanupTrigger.MANUAL,
                freedKb = 250_000L,
            ),
        )

        // Simulated daemon restart: IDs start at 1 again, includes 1 new event and 1 overlapping event
        val incoming = listOf(
            CleanupEvent(
                id = 2,
                timestampMs = 3_000L,
                packageName = "com.example.shop",
                appLabel = "购物商城",
                action = CleanupAction.FORCE_STOP,
                trigger = CleanupTrigger.SCREEN_OFF,
                freedKb = 180_000L,
            ),
            CleanupEvent(
                id = 1,
                timestampMs = 2_000L,
                packageName = "com.example.video",
                appLabel = "com.example.video",
                action = CleanupAction.FORCE_STOP,
                trigger = CleanupTrigger.BACKGROUND_TIMEOUT,
                freedKb = 400_000L,
            ),
        )

        val merged = RealGuardController.mergeCleanupLogs(incoming, persisted, maxEntries = 200)
        assertEquals(3, merged.size)
        assertEquals(3_000L, merged[0].timestampMs)
        assertEquals("购物商城", merged[0].appLabel)
        // Duplicate at t=2000 must deduplicate AND preserve previously resolved friendly appLabel
        assertEquals(2_000L, merged[1].timestampMs)
        assertEquals("短视频", merged[1].appLabel)
        // Pre-restart event at t=1000 must survive
        assertEquals(1_000L, merged[2].timestampMs)
        assertEquals("user:10", merged[2].packageName)
    }

    @Test
    fun keepsPersistedHistoryWhenDaemonReturnsEmptyAfterRestart() {
        val persisted = listOf(
            CleanupEvent(
                id = 1,
                timestampMs = 1_500L,
                packageName = "com.example.reader",
                appLabel = "阅读",
                action = CleanupAction.FORCE_STOP,
                trigger = CleanupTrigger.MANUAL,
                freedKb = 90_000L,
            ),
        )
        val merged = RealGuardController.mergeCleanupLogs(emptyList(), persisted)
        assertEquals(1, merged.size)
        assertEquals("阅读", merged[0].appLabel)
    }
}
