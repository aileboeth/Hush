package com.aileboeth.hush.engine

import com.aileboeth.hush.contract.HushLog
import com.aileboeth.hush.contract.HushProtocol
import com.aileboeth.hush.contract.MiniJson
import com.aileboeth.hush.contract.boolVal
import com.aileboeth.hush.contract.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * Authenticated loopback TCP RPC client communicating with `daemon-shell` on `127.0.0.1`.
 *
 * Automatically retries once over a fresh socket when an existing idle connection was closed
 * by the daemon's idle timeout (`IDLE_TIMEOUT_MS`), preventing false disconnects when returning
 * to the app after >2 minutes in background.
 */
class DaemonClient {
    private val mutex = Mutex()

    @Volatile
    private var session: DaemonSession? = null
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    val currentSession: DaemonSession?
        get() = session

    suspend fun connect(port: Int, token: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeLocked()
            session = null
            runCatching {
                openAndAuthLocked(port, token)
                session = DaemonSession(port, token)
                HushLog.i("DaemonClient", "Connected to loopback daemon port=$port")
            }.onFailure { err ->
                HushLog.w("DaemonClient", "Connect failed on port=$port", err)
                closeLocked()
                session = null
            }.recoverCatching { err ->
                throw friendlyError(err, port)
            }
        }
    }

    suspend fun call(method: String, params: Map<String, Any?> = emptyMap()): Result<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val s = session ?: return@withLock Result.failure(IllegalStateException("守护进程未连接，请先激活"))
                val hadOpenSocket = socket != null && socket?.isClosed == false

                if (!hadOpenSocket) {
                    val reconn = runCatching { openAndAuthLocked(s.port, s.token) }
                    if (reconn.isFailure) {
                        val err = reconn.exceptionOrNull()!!
                        HushLog.w("DaemonClient", "Reconnect before '$method' failed on port=${s.port}", err)
                        closeLocked()
                        session = null
                        return@withLock Result.failure(friendlyError(err, s.port))
                    }
                }

                val firstAttempt = executeRpcLocked(method, params)
                if (firstAttempt.isSuccess) return@withLock firstAttempt

                val firstErr = firstAttempt.exceptionOrNull()!!
                closeLocked()

                // Transparent 1-shot retry when an already-open socket went stale (e.g. server idle timeout)
                if (hadOpenSocket && firstErr is IOException) {
                    HushLog.i("DaemonClient", "Stale loopback socket on '$method'; retrying once on port=${s.port}")
                    val retryOpen = runCatching { openAndAuthLocked(s.port, s.token) }
                    if (retryOpen.isSuccess) {
                        val secondAttempt = executeRpcLocked(method, params)
                        if (secondAttempt.isSuccess) return@withLock secondAttempt
                        val secondErr = secondAttempt.exceptionOrNull()!!
                        HushLog.w("DaemonClient", "Retry RPC '$method' failed on port=${s.port}", secondErr)
                        closeLocked()
                        if (secondErr is IOException) session = null
                        return@withLock Result.failure(friendlyError(secondErr, s.port))
                    } else {
                        val openErr = retryOpen.exceptionOrNull()!!
                        HushLog.w("DaemonClient", "Transparent reconnect failed on port=${s.port}", openErr)
                        closeLocked()
                        session = null
                        return@withLock Result.failure(friendlyError(openErr, s.port))
                    }
                }

                HushLog.w("DaemonClient", "RPC call '$method' failed", firstErr)
                if (firstErr is IOException) session = null
                Result.failure(friendlyError(firstErr, s.port))
            }
        }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeLocked()
            session = null
        }
    }

    private fun executeRpcLocked(method: String, params: Map<String, Any?>): Result<Map<String, Any?>> =
        runCatching {
            val w = writer ?: throw IOException("连接写入通道不可用")
            val r = reader ?: throw IOException("连接读取通道不可用")
            val req = MiniJson.stringify(mapOf("method" to method, "params" to params))
            w.write(req)
            w.newLine()
            w.flush()
            val respLine = r.readLine() ?: throw IOException("守护进程已断开连接")
            val resp = MiniJson.parseObject(respLine)
            if (!resp.boolVal("ok")) {
                throw IllegalStateException(resp.str("error", "守护进程返回错误"))
            }
            resp
        }

    private fun openAndAuthLocked(port: Int, token: String) {
        val s = Socket()
        s.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
        s.soTimeout = READ_TIMEOUT_MS
        val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))
        val r = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))

        w.write(MiniJson.stringify(mapOf("token" to token, "protocol" to HushProtocol.PROTOCOL_VERSION)))
        w.newLine()
        w.flush()

        val line = r.readLine() ?: run {
            s.close()
            throw IOException("守护进程在握手阶段关闭了连接")
        }
        val resp = MiniJson.parseObject(line)
        if (!resp.boolVal("ok")) {
            s.close()
            throw SecurityException(resp.str("error", "守护进程拒绝了会话令牌"))
        }
        socket = s
        writer = w
        reader = r
    }

    private fun closeLocked() {
        runCatching { socket?.close() }
        socket = null
        reader = null
        writer = null
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 30_000

        internal fun friendlyError(err: Throwable, port: Int): Throwable {
            val msg = err.message.orEmpty()
            return when {
                err is ConnectException || msg.contains("ECONNREFUSED", ignoreCase = true) ->
                    IllegalStateException("守护进程已停止（端口 $port），请重新激活")
                err is SocketTimeoutException ->
                    IllegalStateException("连接守护进程超时，请稍后重试")
                err is IOException && msg.isNotBlank() && !msg.contains("守护") ->
                    IllegalStateException("与守护进程连接已中断，请点重连或重新激活")
                else -> err
            }
        }
    }
}
