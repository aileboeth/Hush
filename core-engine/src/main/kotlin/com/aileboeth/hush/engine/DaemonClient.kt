package com.aileboeth.hush.engine

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
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Authenticated loopback TCP RPC client communicating with `daemon-shell` on `127.0.0.1`.
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
            }.onFailure {
                closeLocked()
                session = null
            }
        }
    }

    suspend fun call(method: String, params: Map<String, Any?> = emptyMap()): Result<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val s = session ?: return@withLock Result.failure(IllegalStateException("守护进程未连接，请先激活"))
                if (socket == null || socket?.isClosed == true) {
                    val reconn = runCatching { openAndAuthLocked(s.port, s.token) }
                    if (reconn.isFailure) {
                        closeLocked()
                        session = null
                        return@withLock Result.failure(reconn.exceptionOrNull()!!)
                    }
                }
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
                }.onFailure {
                    closeLocked()
                }
            }
        }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeLocked()
            session = null
        }
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
    }
}
