package com.aileboeth.hush.bootstrap

import android.content.Context
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import java.net.Socket
import java.security.Principal

/**
 * Generates and persists the local RSA-2048 identity + self-signed X.509 certificate
 * used for Android 11–16 Wireless Debugging TLS 1.3 pairing and connection.
 */
class AdbKeyManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val privFile = File(appContext.filesDir, "hush_adb_priv.pk8")
    private val pubFile = File(appContext.filesDir, "hush_adb_pub.x509")
    private val certFile = File(appContext.filesDir, "hush_adb_cert.der")

    private val material: KeyMaterial by lazy { loadOrCreateKeyMaterial() }

    val hasPairedBefore: Boolean
        get() = prefs.getBoolean(KEY_PAIRED, false)

    fun markPaired(paired: Boolean) {
        prefs.edit().putBoolean(KEY_PAIRED, paired).apply()
    }

    /**
     * Returns the ADB public key string (`<base64(android_pubkey)> hush@android\0`)
     * embedded into `PeerInfo(ADB_RSA_PUB_KEY)` during wireless pairing.
     */
    fun getAdbPublicKeyBytes(userHost: String = "hush@android"): ByteArray {
        val raw = encodeAndroidRsaPublicKey(material.keyPair.public as RSAPublicKey)
        val b64 = Base64.encodeToString(raw, Base64.NO_WRAP)
        return "$b64 $userHost\u0000".toByteArray(Charsets.UTF_8)
    }

    fun createTls13Context(): SSLContext {
        val m = material
        val km = object : X509ExtendedKeyManager() {
            private val alias = "hush-adb"
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getCertificateChain(alias: String?) = arrayOf(m.certificate)
            override fun getPrivateKey(alias: String?): PrivateKey = m.keyPair.private
        }
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLSv1.3").apply {
            init(arrayOf<KeyManager>(km), arrayOf<TrustManager>(tm), SecureRandom())
        }
    }

    private fun loadOrCreateKeyMaterial(): KeyMaterial {
        if (privFile.exists() && pubFile.exists() && certFile.exists()) {
            val loaded = runCatching {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                val cf = CertificateFactory.getInstance("X.509")
                val cert = cf.generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
                KeyMaterial(KeyPair(pub, priv), cert)
            }.getOrNull()
            if (loaded != null) return loaded
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val pair = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Hush,O=Aileboeth")
        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()).abs(),
            Date(now - 86_400_000L),
            Date(now + 10L * 365L * 86_400_000L),
            subject,
            pair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
        val cert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

        privFile.writeBytes(pair.private.encoded)
        pubFile.writeBytes(pair.public.encoded)
        certFile.writeBytes(cert.encoded)
        return KeyMaterial(pair, cert)
    }

    private data class KeyMaterial(
        val keyPair: KeyPair,
        val certificate: X509Certificate,
    )

    companion object {
        private const val PREFS_NAME = "hush_adb_bootstrap_prefs"
        private const val KEY_PAIRED = "adb_paired_before"

        /**
         * Encodes an RSA-2048 public key into AOSP's 524-byte `RSAPublicKey` binary struct
         * (`len=64`, `n0inv`, `n[64]` LE, `rr[64]` LE, `exponent`).
         */
        internal fun encodeAndroidRsaPublicKey(pubKey: RSAPublicKey): ByteArray {
            val r32 = BigInteger.ZERO.setBit(32)
            val n = pubKey.modulus
            val r = BigInteger.ZERO.setBit(2048)
            val rr = r.modPow(BigInteger.valueOf(2), n)
            val rem = n.remainder(r32)
            val n0inv = rem.modInverse(r32).negate().and(BigInteger("FFFFFFFF", 16)).toInt()

            val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(64) // 64 uint32 words = 2048 bits
            buf.putInt(n0inv)

            var tempN = n
            for (i in 0 until 64) {
                val dr = tempN.divideAndRemainder(r32)
                tempN = dr[0]
                buf.putInt(dr[1].toInt())
            }
            var tempRR = rr
            for (i in 0 until 64) {
                val dr = tempRR.divideAndRemainder(r32)
                tempRR = dr[0]
                buf.putInt(dr[1].toInt())
            }
            buf.putInt(pubKey.publicExponent.toInt())
            return buf.array()
        }
    }
}
