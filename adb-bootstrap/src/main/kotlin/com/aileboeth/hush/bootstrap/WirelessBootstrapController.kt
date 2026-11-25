package com.aileboeth.hush.bootstrap

import android.content.Context
import com.aileboeth.hush.contract.BootstrapController
import com.aileboeth.hush.contract.BootstrapState
import com.aileboeth.hush.contract.HushProtocol
import com.aileboeth.hush.contract.SafetyGuardrails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Production implementation of [BootstrapController].
 * Handles mDNS discovery, TLS 1.3 + SPAKE2 pairing, launching `daemon-shell` via `app_process64`,
 * and providing a USB one-liner fallback.
 */
class WirelessBootstrapController(
    context: Context,
    private val versionCode: Int,
    private val onDaemonReady: suspend (port: Int, token: String) -> Result<Unit>,
) : BootstrapController {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyManager = AdbKeyManager(appContext)
    private val pairingClient = AdbPairingClient(keyManager)
    private val adbTlsClient = AdbTlsClient(keyManager)
    private val mdns = AdbMdnsDiscovery(appContext)

    private val manualPort = 38921
    private val manualToken: String = generateRandomHexToken()
    private var discoveryJob: Job? = null

    @Volatile
    private var pairingNotificationActive: Boolean = false
    private var lastAlertedPairingPort: Int = -1

    private val _state = MutableStateFlow<BootstrapState>(BootstrapState.Idle)
    override val state: StateFlow<BootstrapState> = _state.asStateFlow()

    private val _hasPairedBefore = MutableStateFlow(keyManager.hasPairedBefore)
    override val hasPairedBefore: StateFlow<Boolean> = _hasPairedBefore.asStateFlow()

    override val usbFallbackCommand: String
        get() = "adb shell 'APK=\$(pm path ${SafetyGuardrails.SELF_PACKAGE} | head -n1 | cut -d: -f2); " +
            "CLASSPATH=\"\$APK\" setsid /system/bin/app_process64 /system/bin --nice-name=hush_daemon " +
            "${HushProtocol.DAEMON_MAIN_CLASS} --port=$manualPort --version=$versionCode --token=$manualToken " +
            "</dev/null >/dev/null 2>&1 &'"

    val manualFallbackPort: Int get() = manualPort
    val manualFallbackToken: String get() = manualToken

    init {
        PairingNotificationReceiver.onPairCodeSubmitted = { code ->
            val ep = mdns.endpoints.value.pairing
            if (ep != null) {
                pairWithCode(ep.host, ep.port, code)
            } else {
                val msg = "未发现无线调试配对端口，请确认已点开「使用配对码配对设备」"
                _state.value = BootstrapState.Error(msg)
                // Re-post notification to clear Android's RemoteInput spinning indicator
                PairingNotificationReceiver.showPairingPrompt(
                    context = appContext,
                    title = "未发现配对服务",
                    subtitle = "$msg，可重试",
                    alertHeadsUp = true,
                )
            }
        }
    }

    override fun startDiscovery() {
        mdns.start()
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            mdns.endpoints.collect { result ->
                val cur = _state.value
                if (cur is BootstrapState.Idle || cur is BootstrapState.Discovering || cur is BootstrapState.Error) {
                    _state.value = BootstrapState.Discovering(
                        pairingEndpoint = result.pairing,
                        connectEndpoint = result.connect,
                    )
                }
                // Auto-popup heads-up notification the moment `_adb-tls-pairing._tcp` appears!
                val pairEp = result.pairing
                if (pairEp != null && pairEp.port != lastAlertedPairingPort &&
                    (pairingNotificationActive || !_hasPairedBefore.value)
                ) {
                    lastAlertedPairingPort = pairEp.port
                    pairingNotificationActive = true
                    PairingNotificationReceiver.showPairingPrompt(
                        context = appContext,
                        title = "已发现无线调试配对服务",
                        subtitle = "端口 ${pairEp.port} · 点「输入配对码」填入 6 位数字",
                        alertHeadsUp = true,
                    )
                }
            }
        }
    }

    override fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        lastAlertedPairingPort = -1
        mdns.stop()
        if (_state.value is BootstrapState.Discovering) {
            _state.value = BootstrapState.Idle
        }
    }

    override fun showPairingNotification() {
        pairingNotificationActive = true
        val ep = mdns.endpoints.value.pairing
        val (title, subtitle) = if (ep != null) {
            lastAlertedPairingPort = ep.port
            "已发现无线调试配对服务" to "端口 ${ep.port} · 点「输入配对码」填入 6 位数字"
        } else {
            "Hush 等待无线调试配对" to "请切到无线调试 → 点「使用配对码配对设备」，出现后会自动提醒"
        }
        PairingNotificationReceiver.showPairingPrompt(
            context = appContext,
            title = title,
            subtitle = subtitle,
            alertHeadsUp = true,
        )
    }

    override fun dismissPairingNotification() {
        pairingNotificationActive = false
        lastAlertedPairingPort = -1
        PairingNotificationReceiver.dismiss(appContext)
    }

    override suspend fun pairWithCode(host: String, port: Int, pairingCode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val resolvedHost = host.trim().ifBlank { mdns.endpoints.value.pairing?.host ?: "127.0.0.1" }
            _state.value = BootstrapState.Pairing(resolvedHost, port)
            var res = pairingClient.pair(resolvedHost, port, pairingCode)
            if (res.isFailure && resolvedHost != "127.0.0.1") {
                val fallback = pairingClient.pair("127.0.0.1", port, pairingCode)
                if (fallback.isSuccess) res = fallback
            }
            res.fold(
                onSuccess = {
                    _hasPairedBefore.value = true
                    _state.value = BootstrapState.Paired(resolvedHost)
                    pairingNotificationActive = false
                    lastAlertedPairingPort = -1
                    PairingNotificationReceiver.dismiss(appContext)
                    Result.success(Unit)
                },
                onFailure = { err ->
                    val errMsg = "配对失败：${err.message ?: "请检查配对码是否正确"}"
                    _state.value = BootstrapState.Error(errMsg, err.stackTraceToString())
                    PairingNotificationReceiver.showPairingPrompt(
                        context = appContext,
                        title = "Hush 配对未成功",
                        subtitle = "$errMsg，可重新输入",
                        alertHeadsUp = true,
                    )
                    Result.failure(err)
                },
            )
        }

    override suspend fun launchDaemon(host: String?, port: Int?): Result<Unit> =
        withContext(Dispatchers.IO) {
            val targetHost = host?.trim()?.takeIf { it.isNotEmpty() }
                ?: mdns.endpoints.value.connect?.host
                ?: "127.0.0.1"
            val targetPort = port ?: mdns.endpoints.value.connect?.port
                ?: return@withContext Result.failure<Unit>(
                    IllegalStateException("未发现无线调试端口，请先开启无线调试或手动填写端口")
                ).also {
                    _state.value = BootstrapState.Error("未发现无线调试端口，请先开启无线调试")
                }

            _state.value = BootstrapState.Launching(targetHost, targetPort)

            val sessionToken = generateRandomHexToken()
            // Bulletproof detached launch over ADB `exec:` (no PTY):
            // 1. Read token from stdin, write to mode-0600 temp file (deleted right after boot) so token
            //    NEVER appears in `/proc/<pid>/cmdline` and no lingering subshell holds stdin/stdout pipes.
            // 2. Launch via `trap '' HUP` + `nohup setsid -d` so `SigIgn` permanently ignores SIGHUP,
            //    `tty=0`, new session (`SID = PGID = PID`), and `PPID` reparents to `init` (PID 1).
            // 3. Keep foreground shell polling `$OUT` until `HUSH_DAEMON_READY:<port>` appears, print it,
            //    clean up both temp files, and `exit 0` cleanly.
            val cmd = "read -r HUSH_TOK; " +
                "HUSH_TOK=\$(printf '%s' \"\$HUSH_TOK\" | tr -d '\\r'); " +
                "APK=\$(pm path ${SafetyGuardrails.SELF_PACKAGE} | head -n1 | cut -d: -f2); " +
                "OUT=\"/data/local/tmp/hush_boot_\$\$.out\"; " +
                "TOK=\"/data/local/tmp/hush_tok_\$\$\"; " +
                "rm -f \"\$OUT\" \"\$TOK\"; " +
                "umask 077; printf '%s\\n' \"\$HUSH_TOK\" >\"\$TOK\"; " +
                "trap '' HUP; " +
                "CLASSPATH=\"\$APK\" nohup setsid -d /system/bin/app_process64 /system/bin " +
                "--nice-name=hush_daemon ${HushProtocol.DAEMON_MAIN_CLASS} --port=0 --version=$versionCode --token-stdin " +
                "<\"\$TOK\" >\"\$OUT\" 2>&1 & " +
                "i=0; while [ \$i -lt 50 ]; do " +
                "if grep -q '[H]USH_DAEMON_READY:' \"\$OUT\" 2>/dev/null; then cat \"\$OUT\"; rm -f \"\$OUT\" \"\$TOK\"; exit 0; fi; " +
                "sleep 0.2; i=\$((i + 1)); done; " +
                "cat \"\$OUT\" 2>/dev/null; rm -f \"\$OUT\" \"\$TOK\""

            var shellRes = adbTlsClient.runShellAndWaitForPrefix(
                host = targetHost,
                port = targetPort,
                shellCommand = cmd,
                stdinLine = sessionToken,
                readyPrefix = HushProtocol.READY_PREFIX,
            )
            if (shellRes.isFailure && targetHost != "127.0.0.1") {
                val fallback = adbTlsClient.runShellAndWaitForPrefix(
                    host = "127.0.0.1",
                    port = targetPort,
                    shellCommand = cmd,
                    stdinLine = sessionToken,
                    readyPrefix = HushProtocol.READY_PREFIX,
                )
                if (fallback.isSuccess) shellRes = fallback
            }

            shellRes.fold(
                onSuccess = { output ->
                    val boundPort = parseReadyPort(output)
                        ?: return@withContext Result.failure<Unit>(
                            IllegalStateException("守护进程未返回监听端口：${output.trim().take(200)}")
                        ).also {
                            _state.value = BootstrapState.Error("守护进程启动异常", output.trim().take(300))
                        }

                    var attach = onDaemonReady(boundPort, sessionToken)
                    if (attach.isFailure) {
                        for (delayMs in longArrayOf(200L, 400L)) {
                            kotlinx.coroutines.delay(delayMs)
                            attach = onDaemonReady(boundPort, sessionToken)
                            if (attach.isSuccess) break
                        }
                    }
                    attach.fold(
                        onSuccess = {
                            stopDiscovery()
                            _state.value = BootstrapState.Ready(
                                port = boundPort,
                                suggestDisableWirelessDebug = true,
                            )
                            Result.success(Unit)
                        },
                        onFailure = { err ->
                            _state.value = BootstrapState.Error("守护进程已启动但连接失败：${err.message}")
                            Result.failure(err)
                        },
                    )
                },
                onFailure = { err ->
                    _state.value = BootstrapState.Error("连接无线调试失败：${err.message}", err.stackTraceToString())
                    Result.failure(err)
                },
            )
        }

    override suspend fun attachManualDaemon(port: Int, token: String): Result<Unit> {
        val resolvedPort = if (port > 0) port else manualPort
        val resolvedToken = token.ifBlank { manualToken }
        return onDaemonReady(resolvedPort, resolvedToken).onSuccess {
            _state.value = BootstrapState.Ready(
                port = resolvedPort,
                suggestDisableWirelessDebug = false,
            )
        }.onFailure { err ->
            _state.value = BootstrapState.Error("手动连接失败：${err.message}")
        }
    }

    companion object {
        private fun generateRandomHexToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            val sb = StringBuilder(64)
            for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xFF))
            return sb.toString()
        }

        internal fun parseReadyPort(output: String): Int? {
            val idx = output.indexOf(HushProtocol.READY_PREFIX)
            if (idx < 0) return null
            val tail = output.substring(idx + HushProtocol.READY_PREFIX.length)
            return tail.takeWhile { it.isDigit() }.toIntOrNull()
        }
    }
}
