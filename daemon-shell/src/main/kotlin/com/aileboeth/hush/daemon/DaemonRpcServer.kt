package com.aileboeth.hush.daemon

import com.aileboeth.hush.contract.HushProtocol
import com.aileboeth.hush.contract.MiniJson
import com.aileboeth.hush.contract.obj
import com.aileboeth.hush.contract.str
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local loopback (`127.0.0.1`) newline-JSON RPC server authenticated with a 256-bit session token.
 *
 * All socket read/write timeouts and disconnects are caught cleanly so an idle or disconnected
 * client can NEVER throw an uncaught exception into `app_process64`.
 */
class DaemonRpcServer(
    private val engine: PolicyEngine,
    private val bridge: SystemBridge,
    private val authToken: String,
    private val versionCode: Int,
    private val preferredPort: Int = 0,
    private val onShutdownRequested: () -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private val workerPool = Executors.newCachedThreadPool { r ->
        Thread(r, "hush-rpc-worker").apply { isDaemon = true }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    val startedAtMs: Long = bridge.nowMs()

    val boundPort: Int
        get() = serverSocket?.localPort ?: -1

    fun start(): Int {
        val socket = ServerSocket(preferredPort, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        running.set(true)

        val acceptThread = Thread({
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    if (!running.get()) break
                    continue
                }
                try {
                    workerPool.execute { handleClientSafe(client) }
                } catch (_: Throwable) {
                    runCatching { client.close() }
                }
            }
        }, "hush-rpc-accept")
        acceptThread.isDaemon = false
        acceptThread.start()
        return socket.localPort
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        workerPool.shutdownNow()
    }

    private fun handleClientSafe(socket: Socket) {
        try {
            handleClient(socket)
        } catch (_: SocketTimeoutException) {
            // Normal idle timeout; client reconnects lazily on next call
        } catch (_: IOException) {
            // Normal client disconnect / broken pipe
        } catch (t: Throwable) {
            System.err.println("HUSH_DAEMON_WARN: rpc client error: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))

        // Line 1 MUST be auth handshake: {"token":"...","protocol":1}
        val authLine = reader.readLine() ?: return
        val authObj = runCatching { MiniJson.parseObject(authLine) }.getOrNull() ?: return
        val provided = authObj.str("token")
        if (!constantTimeEquals(authToken, provided)) {
            writeLine(writer, mapOf("ok" to false, "error" to "鉴权失败：无效的会话令牌"))
            return
        }
        writeLine(writer, mapOf("ok" to true, "protocol" to HushProtocol.PROTOCOL_VERSION))

        socket.soTimeout = IDLE_TIMEOUT_MS
        while (running.get()) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val response = dispatch(line)
            writeLine(writer, response)
            if (response["__shutdown__"] == true) {
                stop()
                onShutdownRequested()
                break
            }
        }
    }

    internal fun dispatch(rawLine: String): Map<String, Any?> {
        val req = runCatching { MiniJson.parseObject(rawLine) }.getOrElse {
            return mapOf("ok" to false, "error" to "请求格式无效：${it.message}")
        }
        val method = req.str("method")
        val params = req.obj("params").orEmpty()

        return try {
            when (method) {
                HushProtocol.METHOD_PING -> mapOf("ok" to true, "pong" to true)

                HushProtocol.METHOD_STATUS -> {
                    val (todayCount, todayFreedKb) = engine.getTodayStats()
                    mapOf(
                        "ok" to true,
                        "data" to mapOf(
                            "pid" to bridge.currentPid(),
                            "uid" to bridge.currentUid(),
                            "versionCode" to versionCode,
                            "startedAtMs" to startedAtMs,
                            "port" to boundPort,
                            "todayCleanupCount" to todayCount,
                            "todayFreedKb" to todayFreedKb,
                        ),
                    )
                }

                HushProtocol.METHOD_MEMORY -> mapOf(
                    "ok" to true,
                    "data" to HushProtocol.encodeMemory(engine.readMemory()),
                )

                HushProtocol.METHOD_SAMPLE_APPS -> {
                    val pkgs = (params["packages"] as? List<*>)?.mapNotNull { it as? String }
                        ?: bridge.listUserPackages().toList()
                    val samples = engine.samplePackages(pkgs)
                    mapOf(
                        "ok" to true,
                        "data" to mapOf("samples" to samples.map(HushProtocol::encodeAppSample)),
                    )
                }

                HushProtocol.METHOD_SYNC_CONFIG -> {
                    val snapshot = HushProtocol.decodeConfigSnapshot(params)
                    engine.updateConfig(snapshot)
                    mapOf("ok" to true)
                }

                HushProtocol.METHOD_FORCE_STOP -> {
                    val pkg = params.str("packageName")
                    val res = engine.manualForceStop(pkg)
                    res.fold(
                        onSuccess = { freed -> mapOf("ok" to true, "data" to mapOf("freedKb" to freed)) },
                        onFailure = { err -> mapOf("ok" to false, "error" to (err.message ?: "强杀失败")) },
                    )
                }

                HushProtocol.METHOD_SOFT_TRIM -> {
                    val pkg = params.str("packageName")
                    val res = engine.manualSoftTrim(pkg)
                    res.fold(
                        onSuccess = { freed -> mapOf("ok" to true, "data" to mapOf("freedKb" to freed)) },
                        onFailure = { err -> mapOf("ok" to false, "error" to (err.message ?: "温和瘦身失败")) },
                    )
                }

                HushProtocol.METHOD_SET_RESTRICTION -> {
                    val pkg = params.str("packageName")
                    val r = HushProtocol.decodeRestriction(params.obj("restriction").orEmpty())
                    val res = engine.setRestriction(pkg, r)
                    res.fold(
                        onSuccess = { mapOf("ok" to true) },
                        onFailure = { err -> mapOf("ok" to false, "error" to (err.message ?: "修改后台限制失败")) },
                    )
                }

                HushProtocol.METHOD_RESTORE_ALL -> {
                    val res = engine.restoreAllRestrictions()
                    res.fold(
                        onSuccess = { count -> mapOf("ok" to true, "data" to mapOf("restoredCount" to count)) },
                        onFailure = { err -> mapOf("ok" to false, "error" to (err.message ?: "还原后台限制失败")) },
                    )
                }

                HushProtocol.METHOD_GET_LOGS -> mapOf(
                    "ok" to true,
                    "data" to mapOf("logs" to engine.getLogs().map(HushProtocol::encodeCleanupEvent)),
                )

                HushProtocol.METHOD_CLEAR_LOGS -> {
                    engine.clearLogs()
                    mapOf("ok" to true)
                }

                HushProtocol.METHOD_SHUTDOWN -> mapOf("ok" to true, "__shutdown__" to true)

                else -> mapOf("ok" to false, "error" to "未知指令：$method")
            }
        } catch (t: Throwable) {
            mapOf("ok" to false, "error" to "执行异常：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun writeLine(writer: BufferedWriter, payload: Map<String, Any?>) {
        val clean = if (payload.containsKey("__shutdown__")) {
            payload.filterKeys { it != "__shutdown__" }
        } else {
            payload
        }
        writer.write(MiniJson.stringify(clean))
        writer.newLine()
        writer.flush()
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val IDLE_TIMEOUT_MS = 120_000

        internal fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.isEmpty() || b.isEmpty()) return false
            val ab = a.toByteArray(StandardCharsets.UTF_8)
            val bb = b.toByteArray(StandardCharsets.UTF_8)
            return MessageDigest.isEqual(ab, bb)
        }
    }
}
