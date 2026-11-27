package com.aileboeth.hush.daemon

import com.aileboeth.hush.contract.HushLog
import com.aileboeth.hush.contract.HushProtocol
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Entry point invoked via `/system/bin/app_process64 /system/bin com.aileboeth.hush.daemon.DaemonMain`.
 *
 * Arguments:
 * - `--port=<N>` (optional, defaults to `0` for OS-assigned ephemeral loopback port)
 * - `--version=<versionCode>` (optional, defaults to `1`)
 * - `--token-stdin` (recommended: reads auth token from first line of stdin so it never appears in `/proc/pid/cmdline`)
 * - `--token=<hex>` (fallback for manual USB one-liner launches)
 */
object DaemonMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val daemonLogFile = File("/data/local/tmp/hush_daemon.log").also { f ->
            runCatching {
                if (!f.exists()) f.createNewFile()
                f.setReadable(false, false)
                f.setReadable(true, true)
                f.setWritable(false, false)
                f.setWritable(true, true)
            }
        }
        HushLog.configure("daemon", daemonLogFile)
        // Guard against stray worker exceptions killing app_process64
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            HushLog.e("DaemonUncaught", "Thread '${thread.name}' crashed", throwable)
            System.err.println(
                "HUSH_DAEMON_UNCAUGHT[${thread.name}]: ${throwable.javaClass.simpleName}: ${throwable.message}"
            )
        }

        var port = 0
        var versionCode = 1
        var token: String? = null
        var tokenFromStdin = false

        for (arg in args) {
            when {
                arg.startsWith("--port=") -> port = arg.removePrefix("--port=").toIntOrNull() ?: 0
                arg.startsWith("--version=") -> versionCode = arg.removePrefix("--version=").toIntOrNull() ?: 1
                arg.startsWith("--token=") -> token = arg.removePrefix("--token=").trim()
                arg == "--token-stdin" -> tokenFromStdin = true
            }
        }

        if (tokenFromStdin || token.isNullOrBlank()) {
            token = runCatching {
                BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8)).readLine()?.trim()
            }.getOrNull()
        }

        val resolvedToken = token
        if (resolvedToken.isNullOrBlank() || resolvedToken.length < 16) {
            System.err.println("HUSH_DAEMON_ERROR: missing or too-short auth token")
            System.exit(2)
            return
        }

        val bridge = AndroidSystemBridge()
        val initialApkPath = bridge.getSelfApkPath()
        val engine = PolicyEngine(bridge)
        val shutdownLatch = CountDownLatch(1)

        val server = DaemonRpcServer(
            engine = engine,
            bridge = bridge,
            authToken = resolvedToken,
            versionCode = versionCode,
            preferredPort = port,
            onShutdownRequested = { shutdownLatch.countDown() },
        )

        val actualPort = server.start()
        val diagSummary = bridge.readSelfDiagnosticsSummary()
        val diagSuffix = if (diagSummary.isNotBlank()) " $diagSummary" else ""
        HushLog.i(
            "DaemonMain",
            "Daemon ready pid=${bridge.currentPid()} uid=${bridge.currentUid()} port=$actualPort vc=$versionCode$diagSuffix",
        )
        // Signal the bootstrapper on stdout with the bound loopback port
        println("${HushProtocol.READY_PREFIX}$actualPort")
        System.out.flush()

        val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "hush-policy-loop").apply { isDaemon = true }
        }
        scheduler.execute {
            runCatching { bridge.grantSelfSecureSettings() }
        }

        var nextTickAtMs = 0L
        var lastApkCheckMs = bridge.nowMs()

        scheduler.scheduleWithFixedDelay({
            runCatching {
                val now = bridge.nowMs()
                // Self-upgrade detection every 5 minutes: if host APK path changed, exit cleanly
                if (initialApkPath != null && now - lastApkCheckMs >= 300_000L) {
                    lastApkCheckMs = now
                    val latestApk = bridge.getSelfApkPath()
                    if (latestApk != null && latestApk != initialApkPath) {
                        HushLog.i("DaemonMain", "APK upgrade detected, shutting down cleanly")
                        server.stop()
                        shutdownLatch.countDown()
                        return@runCatching
                    }
                }
                val intervalMs = engine.getConfig().policy.pollIntervalSeconds.coerceIn(10, 300) * 1000L
                if (now >= nextTickAtMs) {
                    nextTickAtMs = now + intervalMs
                    engine.tick()
                }
            }.onFailure { err ->
                HushLog.e("PolicyTick", "Scheduler tick failed", err)
            }
        }, 5, 5, TimeUnit.SECONDS)

        Runtime.getRuntime().addShutdownHook(Thread {
            HushLog.i("DaemonMain", "Shutdown hook triggered (graceful stop or SIGTERM)")
            scheduler.shutdownNow()
            server.stop()
            shutdownLatch.countDown()
        })

        try {
            shutdownLatch.await()
        } catch (_: InterruptedException) {
            // graceful exit
        } finally {
            scheduler.shutdownNow()
            server.stop()
        }
    }
}
