package com.aileboeth.hush.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.aileboeth.hush.contract.HushLog
import com.aileboeth.hush.contract.MiniJson
import com.aileboeth.hush.contract.boolVal
import com.aileboeth.hush.contract.listOfObj
import com.aileboeth.hush.contract.longVal
import com.aileboeth.hush.contract.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val notes: String,
    val htmlUrl: String,
    val apkDownloadUrl: String?,
    val apkFileName: String?,
    val apkSizeBytes: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val currentVersion: String) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(
        val release: ReleaseInfo,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateState {
        val progress: Float
            get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else -1f
    }

    data class ReadyToInstall(val release: ReleaseInfo, val apkPath: String) : UpdateState
    data class Error(val message: String, val release: ReleaseInfo? = null) : UpdateState
}

/**
 * Zero-dependency GitHub Releases self-updater for `aileboeth/Hush`.
 * Uses `HttpURLConnection` + `MiniJson` + `FileProvider` so no extra runtime libraries are required.
 */
object AppUpdater {
    const val AUTHOR_NAME = "aileboeth"
    const val AUTHOR_GITHUB_URL = "https://github.com/aileboeth"
    const val REPO_GITHUB_URL = "https://github.com/aileboeth/Hush"
    const val RELEASES_PAGE_URL = "https://github.com/aileboeth/Hush/releases"
    private const val RELEASES_API_URL = "https://api.github.com/repos/aileboeth/Hush/releases/latest"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000

    suspend fun checkLatestRelease(currentVersion: String): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(RELEASES_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "Hush/$currentVersion (Android)")
            }
            try {
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                    return@runCatching null
                }
                if (code !in 200..299) {
                    throw IllegalStateException("GitHub 接口返回状态码 $code")
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val latest = parseLatestRelease(body) ?: return@runCatching null
                val newer = isRemoteNewer(latest.versionName, currentVersion)
                HushLog.i("Updater", "Checked GitHub latest=${latest.versionName} current=$currentVersion newer=$newer")
                if (newer) latest else null
            } finally {
                conn.disconnect()
            }
        }.onFailure { err ->
            HushLog.w("Updater", "Release check failed", err)
        }
    }

    suspend fun downloadApk(
        context: Context,
        release: ReleaseInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val urlStr = release.apkDownloadUrl
            ?: return@withContext Result.failure(IllegalStateException("该版本暂未附带可直接下载的 APK 文件"))

        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Clean stale cached APKs from older releases
        updateDir.listFiles()?.forEach { old ->
            if (old.name != release.apkFileName) old.delete()
        }

        val safeName = (release.apkFileName ?: "Hush-${release.versionName}.apk")
            .replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
        val finalFile = File(updateDir, safeName)
        val partFile = File(updateDir, "$safeName.part")

        try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "Hush-Updater/Android")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("下载失败（HTTP $code）")
                }
                val total = conn.contentLengthLong.takeIf { it > 0L } ?: release.apkSizeBytes
                var downloaded = 0L
                var lastReportMs = 0L
                onProgress(0L, total)

                conn.inputStream.use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            downloaded += n
                            val now = System.currentTimeMillis()
                            if (now - lastReportMs >= 120L || downloaded == total) {
                                lastReportMs = now
                                onProgress(downloaded, total)
                            }
                        }
                        output.flush()
                    }
                }
                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                onProgress(downloaded, if (total > 0L) total else downloaded)
                Result.success(finalFile)
            } finally {
                conn.disconnect()
            }
        } catch (ce: CancellationException) {
            partFile.delete()
            throw ce
        } catch (t: Throwable) {
            partFile.delete()
            Result.failure(t)
        }
    }

    /**
     * Launches Android's system package installer via [FileProvider].
     * Returns `true` if the installer opened directly, or `false` if the user was first sent to
     * Android's "Install unknown apps" permission screen for Hush.
     */
    fun launchApkInstaller(context: Context, apkFile: File): Result<Boolean> = runCatching {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            throw IllegalStateException("安装包文件不存在，请重新下载")
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(permIntent)
            return@runCatching false
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        true
    }

    internal fun parseLatestRelease(json: String): ReleaseInfo? {
        val root = runCatching { MiniJson.parseObject(json) }.getOrNull() ?: return null
        if (root.boolVal("draft", false)) return null
        val tag = root.str("tag_name").trim()
        if (tag.isEmpty()) return null
        val version = normalizeVersion(tag)
        val title = root.str("name").ifBlank { "Hush $version" }
        val notes = root.str("body").trim()
        val htmlUrl = root.str("html_url").ifBlank { RELEASES_PAGE_URL }

        val apkAsset = root.listOfObj("assets").firstOrNull { asset ->
            val name = asset.str("name").lowercase()
            name.endsWith(".apk") && !name.endsWith(".idsig")
        }
        return ReleaseInfo(
            tagName = tag,
            versionName = version,
            title = title,
            notes = notes,
            htmlUrl = htmlUrl,
            apkDownloadUrl = apkAsset?.str("browser_download_url")?.takeIf { it.isNotBlank() },
            apkFileName = apkAsset?.str("name")?.takeIf { it.isNotBlank() },
            apkSizeBytes = apkAsset?.longVal("size", 0L) ?: 0L,
        )
    }

    internal fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V")

    internal fun isRemoteNewer(remoteVersion: String, currentVersion: String): Boolean {
        val remoteParts = parseVersionParts(remoteVersion)
        val currentParts = parseVersionParts(currentVersion)
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }

    private fun parseVersionParts(raw: String): List<Int> {
        val clean = normalizeVersion(raw).substringBefore('-').substringBefore('+')
        return clean.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    }
}
