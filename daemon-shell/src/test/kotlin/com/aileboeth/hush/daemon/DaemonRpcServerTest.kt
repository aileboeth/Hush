package com.aileboeth.hush.daemon

import com.aileboeth.hush.contract.AppRestriction
import com.aileboeth.hush.contract.AppRuntimeSample
import com.aileboeth.hush.contract.HushProtocol
import com.aileboeth.hush.contract.MemoryOverview
import com.aileboeth.hush.contract.MiniJson
import com.aileboeth.hush.contract.SecondaryUserInfo
import com.aileboeth.hush.contract.StandbyBucket
import com.aileboeth.hush.contract.boolVal
import com.aileboeth.hush.contract.intVal
import com.aileboeth.hush.contract.listOfObj
import com.aileboeth.hush.contract.longVal
import com.aileboeth.hush.contract.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

class DaemonRpcServerTest {

    private val stubBridge = object : SystemBridge {
        override fun nowMs() = 1_700_000_000_000L
        override fun currentPid() = 9999
        override fun currentUid() = 2000
        override fun getSelfApkPath() = "/data/app/base.apk"
        override fun readMemoryOverview() = MemoryOverview(memTotalKb = 12_000_000L, memAvailableKb = 5_000_000L)
        override fun getForegroundPackage() = "com.bbk.launcher2"
        override fun isScreenAwake() = true
        override fun getActiveAudioPackages() = emptySet<String>()
        override fun listUserPackages() = emptySet<String>()
        override fun samplePackage(packageName: String, userId: Int) = AppRuntimeSample(packageName, userId)
        override fun forceStop(packageName: String, userId: Int) = true
        override fun softTrim(packageName: String, userId: Int) = true
        override fun applyRestriction(packageName: String, restriction: AppRestriction, userId: Int) = true
        override fun readRestriction(packageName: String, userId: Int) = AppRestriction() to StandbyBucket.ACTIVE
        override fun listSecondaryUsers() = listOf(
            SecondaryUserInfo(userId = 10, name = "cys", running = true, processCount = 51, totalPssKb = 820_000L),
        )
        override fun stopUser(userId: Int) = userId == 10
    }

    @Test
    fun rejectsBadTokenAndServesAuthenticatedRpcOverLoopback() {
        val token = "0123456789abcdef0123456789abcdef"
        val server = DaemonRpcServer(
            engine = PolicyEngine(stubBridge),
            bridge = stubBridge,
            authToken = token,
            versionCode = 1,
            preferredPort = 0,
        )
        val port = server.start()
        try {
            // 1. Bad token must be rejected
            Socket("127.0.0.1", port).use { sock ->
                val w = BufferedWriter(OutputStreamWriter(sock.getOutputStream()))
                val r = BufferedReader(InputStreamReader(sock.getInputStream()))
                w.write(MiniJson.stringify(mapOf("token" to "wrong-token", "protocol" to 1)))
                w.newLine(); w.flush()
                val reply = MiniJson.parseObject(r.readLine())
                assertFalse(reply.boolVal("ok"))
            }

            // 2. Valid token authenticates and serves ping + status
            Socket("127.0.0.1", port).use { sock ->
                val w = BufferedWriter(OutputStreamWriter(sock.getOutputStream()))
                val r = BufferedReader(InputStreamReader(sock.getInputStream()))
                w.write(MiniJson.stringify(mapOf("token" to token, "protocol" to 1)))
                w.newLine(); w.flush()
                val authReply = MiniJson.parseObject(r.readLine())
                assertTrue(authReply.boolVal("ok"))

                w.write(MiniJson.stringify(mapOf("method" to HushProtocol.METHOD_STATUS)))
                w.newLine(); w.flush()
                val statusReply = MiniJson.parseObject(r.readLine())
                assertTrue(statusReply.boolVal("ok"))
                assertEquals(2000, statusReply.obj("data")?.intVal("uid"))
                assertEquals(9999, statusReply.obj("data")?.intVal("pid"))

                w.write(MiniJson.stringify(mapOf("method" to HushProtocol.METHOD_GET_SECONDARY_USERS)))
                w.newLine(); w.flush()
                val usersReply = MiniJson.parseObject(r.readLine())
                assertTrue(usersReply.boolVal("ok"))
                assertEquals(1, usersReply.obj("data")?.listOfObj("users")?.size)

                w.write(MiniJson.stringify(mapOf("method" to HushProtocol.METHOD_STOP_USER, "params" to mapOf("userId" to 10))))
                w.newLine(); w.flush()
                val stopReply = MiniJson.parseObject(r.readLine())
                assertTrue(stopReply.boolVal("ok"))
                assertEquals(820_000L, stopReply.obj("data")?.longVal("freedKb"))

                w.write(MiniJson.stringify(mapOf("method" to HushProtocol.METHOD_GET_DIAGNOSTICS)))
                w.newLine(); w.flush()
                val diagReply = MiniJson.parseObject(r.readLine())
                assertTrue(diagReply.boolVal("ok"))
                assertTrue((diagReply.obj("data")?.listOfObj("entries")?.size ?: 0) >= 1)
            }

            // 3. Abrupt client disconnect mid-stream must NOT crash server; next connection still succeeds
            Socket("127.0.0.1", port).use { abrupt ->
                abrupt.getOutputStream().write("{\"token\":\"$token\",\"protocol\":1}\n".toByteArray())
                abrupt.getOutputStream().flush()
            }
            Socket("127.0.0.1", port).use { sock ->
                val w = BufferedWriter(OutputStreamWriter(sock.getOutputStream()))
                val r = BufferedReader(InputStreamReader(sock.getInputStream()))
                w.write(MiniJson.stringify(mapOf("token" to token, "protocol" to 1)))
                w.newLine(); w.flush()
                assertTrue(MiniJson.parseObject(r.readLine()).boolVal("ok"))
                w.write(MiniJson.stringify(mapOf("method" to HushProtocol.METHOD_PING)))
                w.newLine(); w.flush()
                assertTrue(MiniJson.parseObject(r.readLine()).boolVal("ok"))
            }
        } finally {
            server.stop()
        }
    }
}
