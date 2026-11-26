package com.aileboeth.hush.engine

import com.aileboeth.hush.contract.HushProtocol
import com.aileboeth.hush.contract.MiniJson
import com.aileboeth.hush.contract.boolVal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

class DaemonClientTest {

    @Test
    fun transparentlyReconnectsOnceWhenServerClosedIdleSocket() = runBlocking {
        val accepted = AtomicInteger(0)
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val token = "0123456789abcdef0123456789abcdef"

        val serverThread = Thread {
            try {
                // Connection 1: authenticate, reply once, then immediately close (simulating idle timeout)
                server.accept().use { sock ->
                    accepted.incrementAndGet()
                    val r = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                    val w = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8))
                    r.readLine() // handshake
                    w.write(MiniJson.stringify(mapOf("ok" to true, "protocol" to 1)))
                    w.newLine(); w.flush()
                    // Close immediately right after handshake so the next RPC sees a stale half-closed socket
                }
                // Connection 2: transparent retry must authenticate again and serve ping
                server.accept().use { sock ->
                    accepted.incrementAndGet()
                    val r = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                    val w = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8))
                    r.readLine() // handshake
                    w.write(MiniJson.stringify(mapOf("ok" to true, "protocol" to 1)))
                    w.newLine(); w.flush()
                    r.readLine() // ping
                    w.write(MiniJson.stringify(mapOf("ok" to true, "pong" to true)))
                    w.newLine(); w.flush()
                }
            } catch (_: Throwable) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        try {
            val client = DaemonClient()
            assertTrue(client.connect(port, token).isSuccess)
            // Small pause so server closes connection 1 before ping
            Thread.sleep(80)
            val ping = client.call(HushProtocol.METHOD_PING)
            assertTrue("Expected transparent retry to succeed, got: ${ping.exceptionOrNull()}", ping.isSuccess)
            assertTrue(ping.getOrThrow().boolVal("pong"))
            assertTrue(accepted.get() >= 2)
            client.disconnect()
        } finally {
            runCatching { server.close() }
        }
    }

    @Test
    fun translatesConnectionRefusedToFriendlyChineseMessage() {
        val translated = DaemonClient.friendlyError(
            ConnectException("failed to connect to /127.0.0.1 (port 40205): ECONNREFUSED"),
            port = 40205,
        )
        assertTrue(translated.message.orEmpty().contains("守护进程已停止"))
        assertTrue(translated.message.orEmpty().contains("40205"))
    }
}
