package com.aileboeth.hush.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractTests {

    @Test
    fun miniJsonRoundTripHandlesNestedStructuresAndEscapes() {
        val original = mapOf(
            "str" to "hello \"hush\"\nline2\\path\t中文",
            "num" to 1234567890123L,
            "flag" to true,
            "empty" to null,
            "list" to listOf(1L, "two", false),
            "nested" to mapOf("a" to 42L, "b" to "ok"),
        )
        val encoded = MiniJson.stringify(original)
        val decoded = MiniJson.parseObject(encoded)
        assertEquals(original["str"], decoded["str"])
        assertEquals(1234567890123L, decoded.longVal("num"))
        assertEquals(true, decoded.boolVal("flag"))
        assertEquals(null, decoded["empty"])
        assertEquals(42, decoded.obj("nested")?.intVal("a"))
        assertEquals("ok", decoded.obj("nested")?.str("b"))
    }

    @Test
    fun protocolConfigSnapshotRoundTrip() {
        val snapshot = AppConfigSnapshot(
            appGroups = mapOf(
                "com.example.video" to AppGroup.FORCE_STOP,
                "com.example.chat" to AppGroup.SOFT_TRIM,
                "com.example.vault" to AppGroup.PROTECTED,
            ),
            policy = PolicyConfig(
                enabled = true,
                backgroundTimeoutMinutes = 5,
                exemptActiveAudio = true,
                screenOffCleanupEnabled = true,
                screenOffDelayMinutes = 3,
                memoryPressureEnabled = true,
                managedBudgetMb = 900,
                minAvailableRamMb = 2048,
                pollIntervalSeconds = 20,
                softTrimIncludeDualSpace = false,
            ),
            appliedRestrictions = mapOf(
                "com.example.video" to AppRestriction(
                    restrictBackground = true,
                    restrictWakeLock = true,
                    restrictedBucket = true,
                )
            ),
        )
        val json = MiniJson.stringify(HushProtocol.encodeConfigSnapshot(snapshot))
        val decoded = HushProtocol.decodeConfigSnapshot(MiniJson.parseObject(json))
        assertEquals(snapshot, decoded)
    }

    @Test
    fun safetyGuardrailsBlockCriticalAndMessagingRestrictions() {
        assertTrue(SafetyGuardrails.isSystemProtected("com.android.systemui"))
        assertTrue(SafetyGuardrails.isSystemProtected("com.aileboeth.hush"))
        assertTrue(SafetyGuardrails.isSystemProtected("com.bbk.launcher2"))
        assertTrue(SafetyGuardrails.isSystemProtected("com.example.any", uid = 1000))
        assertFalse(SafetyGuardrails.isSystemProtected("com.example.video", uid = 10289))

        assertTrue(SafetyGuardrails.isRestrictionBlocked("com.example.chat", AppGroup.SOFT_TRIM, 10326))
        assertTrue(SafetyGuardrails.isRestrictionBlocked("com.example.vault", AppGroup.PROTECTED, 10267))
        assertFalse(SafetyGuardrails.isRestrictionBlocked("com.example.video", AppGroup.FORCE_STOP, 10289))
    }
}
