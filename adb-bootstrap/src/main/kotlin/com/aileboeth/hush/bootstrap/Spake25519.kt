package com.aileboeth.hush.bootstrap

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-Kotlin BoringSSL-compatible SPAKE2 over Edwards25519 (`crypto/curve25519/spake25519.cc`)
 * + HKDF-SHA256 + AES-128-GCM for Android 11–16 Wireless Debugging (`adb pair`) without NDK/JNI.
 *
 * Matches BoringSSL `spake25519.cc` bit-for-bit:
 * - BoringSSL's own M and N points (NOT RFC 9382's M/N points)
 * - `private_key = (sc_reduce(rand64) << 3)` (multiple of cofactor 8)
 * - `password_scalar` cofactor hack (`w = sc_reduce(SHA512(pwd))`, adding L/2L/4L until divisible by 8)
 * - Full 256-bit unreduced scalar multiplication (preserving BoringSSL cofactor clearing on non-prime-order M/N)
 * - 64-byte raw `SHA-512(password)` (`password_hash`) in the final SHA-512 transcript hash
 */
class Spake25519(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    private val random: SecureRandom = SecureRandom(),
    internal val disablePasswordScalarHack: Boolean = false,
) {
    enum class Role { ALICE, BOB }

    private var privateScalar: BigInteger? = null
    private var passwordScalar: BigInteger? = null
    private var passwordHashBytes: ByteArray? = null
    private var myMsgBytes: ByteArray? = null

    fun generateMessage(password: ByteArray): ByteArray {
        // 1. Ephemeral private key: sc_reduce(RAND_bytes(64)) * 8 (exact mirror of left_shift_3)
        val privRand = ByteArray(64).also { random.nextBytes(it) }
        val privReduced = fromLittleEndian(privRand).mod(Ed25519.L)
        val priv = privReduced.shiftLeft(3) // < 8 * L < 2^256, divisible by cofactor 8
        privateScalar = priv

        // 2. Password hash (64 bytes, kept for final transcript) and reduced scalar w in [0, L-1]
        val pwdHash = MessageDigest.getInstance("SHA-512").digest(password)
        passwordHashBytes = pwdHash
        var pwdScalar = fromLittleEndian(pwdHash).mod(Ed25519.L)

        // 3. BoringSSL password_scalar hack: add L, 2*L, 4*L so bottom 3 bits are 0 (divisible by 8).
        //    Because M and N in BoringSSL have cofactor 8 (order 8*L), adding multiples of L
        //    clears the 8-torsion component of mask = pwdScalar * (M or N).
        if (!disablePasswordScalarHack) {
            if (pwdScalar.testBit(0)) pwdScalar = pwdScalar.add(Ed25519.L)
            if (pwdScalar.testBit(1)) pwdScalar = pwdScalar.add(Ed25519.L.shiftLeft(1))
            if (pwdScalar.testBit(2)) pwdScalar = pwdScalar.add(Ed25519.L.shiftLeft(2))
        }
        passwordScalar = pwdScalar

        val blindPoint = if (role == Role.ALICE) POINT_M else POINT_N
        val pub = Ed25519.scalarMultBase(priv).add(Ed25519.scalarMult(blindPoint, pwdScalar))
        val encoded = pub.encode()
        myMsgBytes = encoded
        return encoded
    }

    fun processMessage(theirMsg: ByteArray): ByteArray {
        require(theirMsg.size == 32) { "SPAKE2 消息长度必须为 32 字节" }
        val priv = checkNotNull(privateScalar) { "必须先调用 generateMessage()" }
        val pwdScalar = checkNotNull(passwordScalar)
        val pwdHash = checkNotNull(passwordHashBytes)
        val myMsg = checkNotNull(myMsgBytes)

        val theirPoint = Ed25519.decode(theirMsg)
            ?: throw IllegalArgumentException("对端发送了无效的 Edwards25519 点")

        val peerBlindPoint = if (role == Role.ALICE) POINT_N else POINT_M
        val unmasked = theirPoint.add(Ed25519.scalarMult(peerBlindPoint, pwdScalar).negate())
        val sharedPoint = Ed25519.scalarMult(unmasked, priv)
        val kBytes = sharedPoint.encode()

        // BoringSSL spake25519.cc transcript hash:
        // SHA-512(len64_le(A) || A || len64_le(B) || B || len64_le(X) || X || len64_le(Y) || Y || len64_le(K) || K || len64_le(password_hash) || password_hash)
        val md = MessageDigest.getInstance("SHA-512")
        fun updateWithLen(data: ByteArray) {
            val lenBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(data.size.toLong()).array()
            md.update(lenBuf)
            md.update(data)
        }

        if (role == Role.ALICE) {
            updateWithLen(myName)
            updateWithLen(theirName)
            updateWithLen(myMsg)
            updateWithLen(theirMsg)
        } else {
            updateWithLen(theirName)
            updateWithLen(myName)
            updateWithLen(theirMsg)
            updateWithLen(myMsg)
        }
        updateWithLen(kBytes)
        updateWithLen(pwdHash) // 64-byte raw SHA-512(password), matching BoringSSL!
        return md.digest()
    }

    companion object {
        // BoringSSL crypto/curve25519/spake25519.cc exact compressed Edwards25519 points M and N
        const val POINT_M_HEX = "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"
        const val POINT_N_HEX = "10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"

        internal val POINT_M: Ed25519.Point by lazy {
            checkNotNull(Ed25519.decode(hexToBytes(POINT_M_HEX))) { "Failed to decode BoringSSL SPAKE2 M" }
        }
        internal val POINT_N: Ed25519.Point by lazy {
            checkNotNull(Ed25519.decode(hexToBytes(POINT_N_HEX))) { "Failed to decode BoringSSL SPAKE2 N" }
        }

        internal fun hexToBytes(hex: String): ByteArray {
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return out
        }

        internal fun fromLittleEndian(bytes: ByteArray): BigInteger {
            val reversed = ByteArray(bytes.size)
            for (i in bytes.indices) reversed[bytes.size - 1 - i] = bytes[i]
            return BigInteger(1, reversed)
        }

        internal fun toLittleEndian32(v: BigInteger): ByteArray {
            val be = v.toByteArray()
            val out = ByteArray(32)
            var outIdx = 0
            for (i in be.size - 1 downTo 0) {
                if (outIdx >= 32) break
                out[outIdx++] = be[i]
            }
            return out
        }
    }
}

/**
 * Minimal pure-Kotlin Edwards25519 group arithmetic (`-x^2 + y^2 = 1 + d*x^2*y^2 mod p`)
 * using extended projective coordinates `(X : Y : Z : T)`.
 *
 * IMPORTANT: [scalarMult] intentionally does NOT reduce `scalar` modulo `L`, because BoringSSL's
 * M/N points carry a non-trivial 8-torsion component (order `8*L`), and BoringSSL relies on
 * multiplying by unreduced 256-bit scalars divisible by 8 to clear the cofactor.
 */
internal object Ed25519 {
    val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    val L: BigInteger = BigInteger.ONE.shiftLeft(252)
        .add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val TWO_D: BigInteger = D.shiftLeft(1).mod(P)
    private val SQRT_M1: BigInteger = BigInteger.valueOf(2)
        .modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
    private val EXP_P3_8: BigInteger = P.add(BigInteger.valueOf(3)).shiftRight(3)

    val IDENTITY: Point = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    val BASE: Point by lazy {
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        val x = recoverX(y, signBit = 0)!!
        Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    data class Point(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val t: BigInteger,
    ) {
        fun affineX(): BigInteger = x.multiply(z.modInverse(P)).mod(P)
        fun affineY(): BigInteger = y.multiply(z.modInverse(P)).mod(P)

        fun add(other: Point): Point {
            val a = y.subtract(x).multiply(other.y.subtract(other.x)).mod(P)
            val b = y.add(x).multiply(other.y.add(other.x)).mod(P)
            val c = t.multiply(TWO_D).multiply(other.t).mod(P)
            val d = z.shiftLeft(1).multiply(other.z).mod(P)
            val e = b.subtract(a).mod(P)
            val f = d.subtract(c).mod(P)
            val g = d.add(c).mod(P)
            val h = b.add(a).mod(P)
            val x3 = e.multiply(f).mod(P)
            val y3 = g.multiply(h).mod(P)
            val t3 = e.multiply(h).mod(P)
            val z3 = f.multiply(g).mod(P)
            return Point(x3, y3, z3, t3)
        }

        fun negate(): Point = Point(P.subtract(x).mod(P), y, z, P.subtract(t).mod(P))

        fun encode(): ByteArray {
            val zInv = z.modInverse(P)
            val ax = x.multiply(zInv).mod(P)
            val ay = y.multiply(zInv).mod(P)
            val out = Spake25519.toLittleEndian32(ay)
            if (ax.testBit(0)) {
                out[31] = (out[31].toInt() or 0x80).toByte()
            }
            return out
        }
    }

    fun scalarMultBase(scalar: BigInteger): Point = scalarMult(BASE, scalar)

    fun scalarMult(point: Point, scalar: BigInteger): Point {
        require(scalar.signum() >= 0) { "Scalar must be non-negative" }
        var r0 = IDENTITY
        var r1 = point
        val bitLen = maxOf(scalar.bitLength(), 256)
        for (i in (bitLen - 1) downTo 0) {
            if (scalar.testBit(i)) {
                r0 = r0.add(r1)
                r1 = r1.add(r1)
            } else {
                r1 = r0.add(r1)
                r0 = r0.add(r0)
            }
        }
        return r0
    }

    fun decode(encoded: ByteArray): Point? {
        if (encoded.size != 32) return null
        val signBit = (encoded[31].toInt() ushr 7) and 1
        val copy = encoded.copyOf()
        copy[31] = (copy[31].toInt() and 0x7F).toByte()
        val y = Spake25519.fromLittleEndian(copy)
        if (y >= P) return null
        val x = recoverX(y, signBit) ?: return null
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun recoverX(y: BigInteger, signBit: Int): BigInteger? {
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
        val x2 = u.multiply(v.modInverse(P)).mod(P)
        var x = x2.modPow(EXP_P3_8, P)
        if (x.multiply(x).mod(P) != x2) {
            x = x.multiply(SQRT_M1).mod(P)
            if (x.multiply(x).mod(P) != x2) return null
        }
        if (x == BigInteger.ZERO && signBit == 1) return null
        if ((x.testBit(0) && signBit == 0) || (!x.testBit(0) && signBit == 1)) {
            x = P.subtract(x).mod(P)
        }
        return x
    }
}

/**
 * HKDF-SHA256 + AES-128-GCM stream cipher matching AOSP `adb/pairing_auth/aes_128_gcm.cpp`.
 */
class AdbPairingCipher(sharedSecret: ByteArray) {
    private val keySpec: SecretKeySpec
    private var encSeq: Long = 0L
    private var decSeq: Long = 0L

    init {
        val derived = hkdfSha256(
            ikm = sharedSecret,
            salt = ByteArray(0),
            info = HKDF_INFO.toByteArray(Charsets.UTF_8),
            length = 16,
        )
        keySpec = SecretKeySpec(derived, "AES")
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val nonce = buildNonce(encSeq++)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
        return cipher.doFinal(plaintext)
    }

    fun decrypt(ciphertext: ByteArray): ByteArray {
        val nonce = buildNonce(decSeq++)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
        return cipher.doFinal(ciphertext)
    }

    private fun buildNonce(seq: Long): ByteArray {
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(seq)
            .putInt(0)
            .array()
    }

    companion object {
        const val HKDF_INFO = "adb pairing_auth aes-128-gcm key"

        internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
            val prk = hmacSha256(actualSalt, ikm)
            val out = ByteArray(length)
            var t = ByteArray(0)
            var offset = 0
            var counter = 1
            while (offset < length) {
                val input = ByteArray(t.size + info.size + 1)
                System.arraycopy(t, 0, input, 0, t.size)
                System.arraycopy(info, 0, input, t.size, info.size)
                input[input.size - 1] = counter.toByte()
                t = hmacSha256(prk, input)
                val toCopy = minOf(t.size, length - offset)
                System.arraycopy(t, 0, out, offset, toCopy)
                offset += toCopy
                counter++
            }
            return out
        }

        private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }
    }
}
