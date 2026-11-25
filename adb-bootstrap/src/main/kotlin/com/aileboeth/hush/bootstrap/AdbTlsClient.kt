package com.aileboeth.hush.bootstrap

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

/**
 * Pure-Kotlin ADB over TLS 1.3 (`STLS`) stream client for launching Hush's `daemon-shell`
 * on the local device via Wireless Debugging (`_adb-tls-connect._tcp`).
 */
class AdbTlsClient(
    private val keyManager: AdbKeyManager,
) {
    /**
     * Connects to local `adbd`, upgrades to TLS 1.3 using our paired client cert,
     * executes [shellCommand] with optional [stdinLine], and reads stdout until [readyPrefix]
     * is observed or stream closes.
     */
    fun runShellAndWaitForPrefix(
        host: String,
        port: Int,
        shellCommand: String,
        stdinLine: String? = null,
        readyPrefix: String? = null,
    ): Result<String> = runCatching {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        raw.soTimeout = IO_TIMEOUT_MS
        raw.tcpNoDelay = true

        var activeSocket: Socket = raw
        try {
            var input = DataInputStream(activeSocket.getInputStream())
            var output = DataOutputStream(activeSocket.getOutputStream())

            // 1. Send initial CNXN
            writePacket(output, A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray(Charsets.UTF_8))

            // 2. Expect STLS (TLS upgrade) or direct CNXN
            val first = readPacket(input)
            when (first.command) {
                A_STLS -> {
                    writePacket(output, A_STLS, STLS_VERSION, 0, ByteArray(0))
                    val sslCtx = keyManager.createTls13Context()
                    val tls = sslCtx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
                    tls.soTimeout = IO_TIMEOUT_MS
                    tls.enabledProtocols = arrayOf("TLSv1.3")
                    tls.startHandshake()
                    activeSocket = tls
                    input = DataInputStream(tls.getInputStream())
                    output = DataOutputStream(tls.getOutputStream())

                    val afterTls = readPacket(input)
                    if (afterTls.command != A_CNXN) {
                        throw IOException("无线调试 TLS 握手未返回 CNXN（响应 0x${afterTls.command.toString(16)}）")
                    }
                }
                A_CNXN -> Unit
                A_AUTH -> throw SecurityException("设备尚未信任此密钥，请先完成一次无线调试配对")
                else -> throw IOException("无线调试握手响应异常（0x${first.command.toString(16)}）")
            }

            // 3. Open non-PTY `exec:` stream (falls back to `shell:` if refused)
            val localId = 1
            val servicePayload = "exec:$shellCommand\u0000".toByteArray(Charsets.UTF_8)
            writePacket(output, A_OPEN, localId, 0, servicePayload)

            var remoteId = -1
            val collected = StringBuilder()
            var readyReceived = false

            while (true) {
                val pkt = try {
                    readPacket(input)
                } catch (e: java.net.SocketTimeoutException) {
                    if (readyReceived) break else throw e
                }
                when (pkt.command) {
                    A_OKAY -> {
                        if (remoteId == -1) {
                            remoteId = pkt.arg0
                            if (stdinLine != null) {
                                val bytes = "$stdinLine\n".toByteArray(Charsets.UTF_8)
                                writePacket(output, A_WRTE, localId, remoteId, bytes)
                            }
                        }
                    }
                    A_WRTE -> {
                        if (remoteId == -1) remoteId = pkt.arg0
                        writePacket(output, A_OKAY, localId, remoteId, ByteArray(0))
                        val chunk = String(pkt.payload, Charsets.UTF_8)
                        collected.append(chunk)
                        if (!readyReceived && readyPrefix != null && collected.contains(readyPrefix)) {
                            val idx = collected.indexOf(readyPrefix)
                            val tail = collected.substring(idx)
                            if (tail.contains('\n') || tail.contains('\r')) {
                                // Let `sh` finish `rm -f` and `exit 0` naturally so `adbd` sends clean
                                // A_CLSE without calling Subprocess::Kill(); cap wait at 1.5s.
                                readyReceived = true
                                runCatching { activeSocket.soTimeout = POST_READY_GRACE_MS }
                            }
                        }
                    }
                    A_CLSE -> break
                }
            }
            if (readyReceived) {
                runCatching { writePacket(output, A_CLSE, localId, remoteId, ByteArray(0)) }
            }
            collected.toString()
        } finally {
            runCatching { activeSocket.close() }
        }
    }

    internal data class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray,
    )

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val IO_TIMEOUT_MS = 20_000
        private const val POST_READY_GRACE_MS = 1_500

        const val A_CNXN = 0x4e584e43 // "CNXN"
        const val A_AUTH = 0x48545541 // "AUTH"
        const val A_OPEN = 0x4e45504f // "OPEN"
        const val A_OKAY = 0x59414b4f // "OKAY"
        const val A_CLSE = 0x45534c43 // "CLSE"
        const val A_WRTE = 0x45545257 // "WRTE"
        const val A_STLS = 0x534c5453 // "STLS"

        const val A_VERSION = 0x01000001
        const val STLS_VERSION = 0x01000000
        const val MAX_PAYLOAD = 1024 * 1024

        internal fun encodeHeader(command: Int, arg0: Int, arg1: Int, payload: ByteArray): ByteArray {
            var checksum = 0
            for (b in payload) checksum += (b.toInt() and 0xFF)
            return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command)
                .putInt(arg0)
                .putInt(arg1)
                .putInt(payload.size)
                .putInt(checksum)
                .putInt(command xor -1)
                .array()
        }

        private fun writePacket(out: DataOutputStream, command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
            out.write(encodeHeader(command, arg0, arg1, payload))
            if (payload.isNotEmpty()) out.write(payload)
            out.flush()
        }

        private fun readPacket(input: DataInputStream): AdbPacket {
            val hdr = ByteArray(24)
            input.readFully(hdr)
            val buf = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            val command = buf.int
            val arg0 = buf.int
            val arg1 = buf.int
            val dataLen = buf.int
            val _crc = buf.int
            val magic = buf.int
            if ((command xor -1) != magic) {
                throw IOException("Corrupt ADB packet magic")
            }
            if (dataLen < 0 || dataLen > MAX_PAYLOAD) {
                throw IOException("Invalid ADB payload length: $dataLen")
            }
            val payload = ByteArray(dataLen)
            if (dataLen > 0) input.readFully(payload)
            return AdbPacket(command, arg0, arg1, payload)
        }
    }
}
