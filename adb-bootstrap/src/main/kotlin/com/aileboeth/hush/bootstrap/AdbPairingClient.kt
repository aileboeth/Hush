package com.aileboeth.hush.bootstrap

import android.net.ssl.SSLSockets
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

/**
 * Executes the Android 11–16 Wireless Debugging pairing handshake (`_adb-tls-pairing._tcp`)
 * using TLS 1.3 + RFC 5705 keying material export (`adb-label\0`) + [Spake25519] + AES-128-GCM `PeerInfo`.
 */
class AdbPairingClient(
    private val keyManager: AdbKeyManager,
) {
    fun pair(host: String, port: Int, pairingCode: String): Result<Unit> = runCatching {
        val cleanCode = pairingCode.trim()
        require(cleanCode.length == 6 && cleanCode.all { it.isDigit() }) {
            "配对码必须是 6 位数字"
        }

        val sslCtx = keyManager.createTls13Context()
        val rawSocket = Socket()
        rawSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        rawSocket.tcpNoDelay = true

        val sslSocket = sslCtx.socketFactory.createSocket(rawSocket, host, port, true) as SSLSocket
        sslSocket.soTimeout = IO_TIMEOUT_MS
        sslSocket.enabledProtocols = arrayOf("TLSv1.3")

        sslSocket.use { s ->
            s.startHandshake()

            // Public Android 10+ (API 29+) API for RFC 5705 TLS keying material export
            val exported = SSLSockets.exportKeyingMaterial(s, EXPORT_LABEL, null, EXPORT_KEY_SIZE)
                ?: throw IOException("无法导出 TLS 密钥材料")

            val codeBytes = cleanCode.toByteArray(Charsets.UTF_8)
            val password = ByteArray(codeBytes.size + exported.size)
            System.arraycopy(codeBytes, 0, password, 0, codeBytes.size)
            System.arraycopy(exported, 0, password, codeBytes.size, exported.size)

            val input = DataInputStream(s.inputStream)
            val output = DataOutputStream(s.outputStream)

            // 1. Exchange SPAKE2 messages
            val spake = Spake25519(
                role = Spake25519.Role.ALICE,
                myName = CLIENT_NAME,
                theirName = SERVER_NAME,
            )
            val ourSpakeMsg = spake.generateMessage(password)
            writePacket(output, TYPE_SPAKE2_MSG, ourSpakeMsg)

            val (peerType, peerSpakeMsg) = readPacket(input)
            if (peerType != TYPE_SPAKE2_MSG) {
                throw IOException("配对握手包类型异常（期望 SPAKE2，实际 $peerType）")
            }

            val sharedSecret = spake.processMessage(peerSpakeMsg)
            val cipher = AdbPairingCipher(sharedSecret)

            // 2. Encrypt & exchange fixed 8192-byte PeerInfo structs
            val ourPeerInfo = buildPeerInfo(PEER_INFO_RSA_PUB_KEY, keyManager.getAdbPublicKeyBytes())
            val encryptedOurInfo = cipher.encrypt(ourPeerInfo)
            writePacket(output, TYPE_PEER_INFO, encryptedOurInfo)

            val (infoType, encryptedPeerInfo) = readPacket(input)
            if (infoType != TYPE_PEER_INFO) {
                throw IOException("配对码不正确或服务端拒绝（包类型 $infoType）")
            }
            val decryptedPeerInfo = try {
                cipher.decrypt(encryptedPeerInfo)
            } catch (_: Exception) {
                throw SecurityException("配对码错误或校验失败，请重新核对 6 位配对码")
            }
            if (decryptedPeerInfo.size != PEER_INFO_SIZE) {
                throw IOException("对端 PeerInfo 长度异常：${decryptedPeerInfo.size}")
            }

            keyManager.markPaired(true)
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val IO_TIMEOUT_MS = 10_000
        private const val PROTOCOL_VERSION: Byte = 1
        private const val TYPE_SPAKE2_MSG: Byte = 0
        private const val TYPE_PEER_INFO: Byte = 1
        private const val PEER_INFO_RSA_PUB_KEY: Byte = 0
        private const val PEER_INFO_SIZE = 8192
        private const val EXPORT_LABEL = "adb-label\u0000"
        private const val EXPORT_KEY_SIZE = 64

        private val CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.UTF_8)
        private val SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.UTF_8)

        internal fun buildPeerInfo(type: Byte, data: ByteArray): ByteArray {
            require(data.size <= PEER_INFO_SIZE - 1) { "PeerInfo payload exceeds 8191 bytes" }
            val out = ByteArray(PEER_INFO_SIZE)
            out[0] = type
            System.arraycopy(data, 0, out, 1, data.size)
            return out
        }

        private fun writePacket(out: DataOutputStream, type: Byte, payload: ByteArray) {
            val header = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
                .put(PROTOCOL_VERSION)
                .put(type)
                .putInt(payload.size)
                .array()
            out.write(header)
            out.write(payload)
            out.flush()
        }

        private fun readPacket(input: DataInputStream): Pair<Byte, ByteArray> {
            val headerBytes = ByteArray(6)
            input.readFully(headerBytes)
            val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
            val version = buf.get()
            val type = buf.get()
            val size = buf.int
            if (version != PROTOCOL_VERSION) {
                throw IOException("Unsupported pairing protocol version: $version")
            }
            if (size <= 0 || size > 65536) {
                throw IOException("Invalid pairing payload size: $size")
            }
            val payload = ByteArray(size)
            input.readFully(payload)
            return type to payload
        }
    }
}
