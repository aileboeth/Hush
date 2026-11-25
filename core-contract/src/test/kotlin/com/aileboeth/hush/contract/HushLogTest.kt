package com.aileboeth.hush.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HushLogTest {

    @Test
    fun redactsHexTokensAndPersistsAcrossReconfigure() {
        val tmp = File.createTempFile("hush_log_test", ".log")
        try {
            tmp.writeText("")
            HushLog.configure("app", tmp)
            HushLog.clear()

            HushLog.i("Bootstrap", "Connected with token 7a501e3830ab9546f1230ce0c120e268631bddedf1b1ab906aa541665af59e7e on port 46117")
            HushLog.e("Daemon", "Failed to stop user 10")

            val snap = HushLog.snapshot()
            assertEquals(2, snap.size)
            assertFalse(snap[0].message.contains("7a501e3830ab9546"))
            assertTrue(snap[0].message.contains("<redacted-token>"))
            assertEquals("E", snap[1].level)

            // Re-initializing from the same file must reload persisted entries
            HushLog.configure("app", tmp)
            val reloaded = HushLog.snapshot()
            assertEquals(2, reloaded.size)
            assertEquals("Daemon", reloaded[1].tag)
        } finally {
            HushLog.configure("app", null)
            HushLog.clear()
            tmp.delete()
        }
    }
}
