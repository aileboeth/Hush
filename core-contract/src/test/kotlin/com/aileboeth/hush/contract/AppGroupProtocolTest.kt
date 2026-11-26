package com.aileboeth.hush.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGroupProtocolTest {

    @Test
    fun appKeyEncodesDecodesBackwardCompatibly() {
        assertEquals("com.tencent.mm", AppKey.encode("com.tencent.mm", 0))
        assertEquals("com.tencent.mm@999", AppKey.encode("com.tencent.mm", 999))
        assertEquals(AppTarget("com.tencent.mm", 0), AppKey.decode("com.tencent.mm"))
        assertEquals(AppTarget("com.tencent.mm", 999), AppKey.decode("com.tencent.mm@999"))
    }

    @Test
    fun configSnapshotRoundTripsCustomGroupsAndAppliesSafetyPrecedence() {
        val snap = AppConfigSnapshot(
            appGroups = mapOf(
                "com.example.shop" to AppGroup.FORCE_STOP,
                "com.tencent.mm" to AppGroup.SOFT_TRIM,
            ),
            customGroups = listOf(
                CustomAppGroup(
                    id = "g_kill",
                    name = "强杀组",
                    action = AppGroup.FORCE_STOP,
                    appKeys = listOf("com.tencent.mm", "com.tencent.mm@999", "com.example.video"),
                ),
                CustomAppGroup(
                    id = "g_prot",
                    name = "核心",
                    action = AppGroup.PROTECTED,
                    appKeys = listOf("com.example.video"),
                ),
            ),
        )

        val encoded = MiniJson.stringify(HushProtocol.encodeConfigSnapshot(snap))
        val decoded = HushProtocol.decodeConfigSnapshot(MiniJson.parseObject(encoded))
        assertEquals(2, decoded.customGroups.size)
        assertEquals("强杀组", decoded.customGroups[0].name)
        assertEquals(listOf("com.tencent.mm", "com.tencent.mm@999", "com.example.video"), decoded.customGroups[0].appKeys)

        val effective = decoded.resolveEffectiveGroups()
        // Main WeChat stays SOFT_TRIM (higher safety rank than FORCE_STOP)
        assertEquals(AppGroup.SOFT_TRIM, effective[AppTarget("com.tencent.mm", 0)])
        // Dual WeChat inherits FORCE_STOP from custom group
        assertEquals(AppGroup.FORCE_STOP, effective[AppTarget("com.tencent.mm", 999)])
        // Video app is in both FORCE_STOP and PROTECTED custom groups -> PROTECTED wins
        assertEquals(AppGroup.PROTECTED, effective[AppTarget("com.example.video", 0)])
        assertTrue(AppTarget("com.example.shop", 0) in effective)
    }
}
