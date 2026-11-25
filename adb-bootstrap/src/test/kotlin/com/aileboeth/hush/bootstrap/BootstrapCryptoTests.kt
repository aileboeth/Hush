package com.aileboeth.hush.bootstrap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

class BootstrapCryptoTests {

    @Test
    fun boringSslPointsMAndNMatchOfficialCoordinatesAndPrecompTable() {
        val m = Spake25519.POINT_M
        val n = Spake25519.POINT_N

        // Exact coordinates documented in google/boringssl crypto/curve25519/spake25519.cc
        assertEquals(
            BigInteger("31406539342727633121250288103050113562375374900226415211311216773867585644232"),
            m.affineX(),
        )
        assertEquals(
            BigInteger("21177308356423958466833845032658859666296341766942662650232962324899758529114"),
            m.affineY(),
        )
        assertEquals(
            BigInteger("49918732221787544735331783592030787422991506689877079631459872391322455579424"),
            n.affineX(),
        )
        assertEquals(
            BigInteger("54629554431565467720832445949441049581317094546788069926228343916274969994000"),
            n.affineY(),
        )

        // Encoded round-trip matches BoringSSL hex exactly
        assertArrayEquals(Spake25519.hexToBytes(Spake25519.POINT_M_HEX), m.encode())
        assertArrayEquals(Spake25519.hexToBytes(Spake25519.POINT_N_HEX), n.encode())

        // Verify 2^64 * N against BoringSSL's hardcoded kSpakeNSmallPrecomp entry i=2 (x_le || y_le)!
        val n2Pow64 = Ed25519.scalarMult(n, BigInteger.ONE.shiftLeft(64))
        val expectedXLe = Spake25519.hexToBytes(
            "93f157976ef06e45374af40b1851f54f673cdcec84edd0ebcafbdbff7feba823"
        )
        val expectedYLe = Spake25519.hexToBytes(
            "688713646a10f745e00f3221597c0e50ad56d712697b58f8b93ba5bb4d1b871c"
        )
        assertArrayEquals(expectedXLe, Spake25519.toLittleEndian32(n2Pow64.affineX()))
        assertArrayEquals(expectedYLe, Spake25519.toLittleEndian32(n2Pow64.affineY()))

        // Verify 8 * L * N == IDENTITY (full group order 8*L)
        assertArrayEquals(
            Ed25519.IDENTITY.encode(),
            Ed25519.scalarMult(n, Ed25519.L.shiftLeft(3)).encode(),
        )
    }

    @Test
    fun spake25519MatchesBoringSslIncludingOldAliceAndOldBobCofactorInterop() {
        val clientId = "adb pair client\u0000".toByteArray(Charsets.UTF_8)
        val serverId = "adb pair server\u0000".toByteArray(Charsets.UTF_8)
        val password = "123456_tls_exported_keying_material".toByteArray(Charsets.UTF_8)

        // 1. Standard BoringSSL Alice <-> Standard BoringSSL Bob
        val alice = Spake25519(Spake25519.Role.ALICE, clientId, serverId)
        val bob = Spake25519(Spake25519.Role.BOB, serverId, clientId)
        val msgA = alice.generateMessage(password)
        val msgB = bob.generateMessage(password)
        val keyA = alice.processMessage(msgB)
        val keyB = bob.processMessage(msgA)
        assertEquals(64, keyA.size)
        assertArrayEquals(keyA, keyB)

        // 2. Standard Alice <-> OldBob (disablePasswordScalarHack = true): mirrors BoringSSL's OldBob test!
        //    Only passes if scalarMult does NOT reduce mod L and private_key clears cofactor 8!
        val alice2 = Spake25519(Spake25519.Role.ALICE, clientId, serverId, disablePasswordScalarHack = false)
        val oldBob = Spake25519(Spake25519.Role.BOB, serverId, clientId, disablePasswordScalarHack = true)
        val mA2 = alice2.generateMessage(password)
        val mB2 = oldBob.generateMessage(password)
        assertArrayEquals(alice2.processMessage(mB2), oldBob.processMessage(mA2))

        // 3. OldAlice <-> Standard Bob: mirrors BoringSSL's OldAlice test!
        val oldAlice = Spake25519(Spake25519.Role.ALICE, clientId, serverId, disablePasswordScalarHack = true)
        val bob3 = Spake25519(Spake25519.Role.BOB, serverId, clientId, disablePasswordScalarHack = false)
        val mA3 = oldAlice.generateMessage(password)
        val mB3 = bob3.generateMessage(password)
        assertArrayEquals(oldAlice.processMessage(mB3), bob3.processMessage(mA3))

        // 4. Wrong password must NOT match
        val wrongBob = Spake25519(Spake25519.Role.BOB, serverId, clientId)
        val wrongMsg = wrongBob.generateMessage("999999_tls_exported_keying_material".toByteArray(Charsets.UTF_8))
        val wrongAlice = Spake25519(Spake25519.Role.ALICE, clientId, serverId)
        wrongAlice.generateMessage(password)
        val wrongKey = wrongAlice.processMessage(wrongMsg)
        assertTrue(!keyA.contentEquals(wrongKey))

        // 5. AES-128-GCM PeerInfo encryption/decryption round trip
        val encCipher = AdbPairingCipher(keyA)
        val decCipher = AdbPairingCipher(keyB)
        val peerInfo = AdbPairingClient.buildPeerInfo(0, "test-pub-key hush@android\u0000".toByteArray())
        assertEquals(8192, peerInfo.size)
        val sealed = encCipher.encrypt(peerInfo)
        val opened = decCipher.decrypt(sealed)
        assertArrayEquals(peerInfo, opened)
    }

    @Test
    fun androidRsaPublicKeyEncodingHasExact524ByteLayout() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pub = kpg.generateKeyPair().public as RSAPublicKey
        val encoded = AdbKeyManager.encodeAndroidRsaPublicKey(pub)
        assertEquals(524, encoded.size)
        assertEquals(64, encoded[0].toInt() and 0xFF)
    }

    @Test
    fun parsesDaemonReadyPortFromShellOutput() {
        assertEquals(45123, WirelessBootstrapController.parseReadyPort("HUSH_DAEMON_READY:45123\n"))
        assertNotNull(WirelessBootstrapController.parseReadyPort("noise\nHUSH_DAEMON_READY:9999\r\n"))
    }
}
