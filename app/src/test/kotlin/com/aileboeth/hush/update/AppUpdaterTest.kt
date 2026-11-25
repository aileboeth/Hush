package com.aileboeth.hush.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun comparesSemanticVersionsAccurately() {
        assertTrue(AppUpdater.isRemoteNewer("v0.2.0", "0.1.1"))
        assertTrue(AppUpdater.isRemoteNewer("0.2.1", "0.2.0"))
        assertTrue(AppUpdater.isRemoteNewer("1.0.0", "0.9.9"))
        assertFalse(AppUpdater.isRemoteNewer("v0.2.0", "0.2.0"))
        assertFalse(AppUpdater.isRemoteNewer("0.1.1", "0.2.0"))
        assertFalse(AppUpdater.isRemoteNewer("v0.2.0-beta.1", "0.2.0"))
    }

    @Test
    fun parsesGitHubReleaseSelectingApkOverIdsigAndChecksums() {
        val sampleJson = """
            {
              "tag_name": "v0.2.0",
              "name": "Hush 0.2.0",
              "draft": false,
              "html_url": "https://github.com/aileboeth/Hush/releases/tag/v0.2.0",
              "body": "## 更新亮点\n- 支持第二系统自动停机",
              "assets": [
                {
                  "name": "Hush-0.2.0.apk.idsig",
                  "size": 58881,
                  "browser_download_url": "https://github.com/aileboeth/Hush/releases/download/v0.2.0/Hush-0.2.0.apk.idsig"
                },
                {
                  "name": "Hush-0.2.0.apk",
                  "size": 6500000,
                  "browser_download_url": "https://github.com/aileboeth/Hush/releases/download/v0.2.0/Hush-0.2.0.apk"
                },
                {
                  "name": "SHA256SUMS.txt",
                  "size": 81,
                  "browser_download_url": "https://github.com/aileboeth/Hush/releases/download/v0.2.0/SHA256SUMS.txt"
                }
              ]
            }
        """.trimIndent()

        val parsed = AppUpdater.parseLatestRelease(sampleJson)
        assertNotNull(parsed)
        assertEquals("0.2.0", parsed?.versionName)
        assertEquals("Hush-0.2.0.apk", parsed?.apkFileName)
        assertEquals(6_500_000L, parsed?.apkSizeBytes)
        assertTrue(parsed?.apkDownloadUrl?.endsWith("Hush-0.2.0.apk") == true)
    }
}
