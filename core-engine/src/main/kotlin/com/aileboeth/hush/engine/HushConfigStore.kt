package com.aileboeth.hush.engine

import android.content.Context
import android.content.SharedPreferences
import com.aileboeth.hush.contract.AppConfigSnapshot
import com.aileboeth.hush.contract.CleanupEvent
import com.aileboeth.hush.contract.HushProtocol
import com.aileboeth.hush.contract.MiniJson
import com.aileboeth.hush.contract.listOfObj

/**
 * Lightweight local persistence for rules, restriction ledger, cleanup history, and last daemon session info.
 * Uses private SharedPreferences + [MiniJson] to keep APK size and RAM overhead minimal.
 */
class HushConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadConfig(): AppConfigSnapshot {
        val raw = prefs.getString(KEY_CONFIG_JSON, null) ?: return AppConfigSnapshot()
        return runCatching {
            HushProtocol.decodeConfigSnapshot(MiniJson.parseObject(raw))
        }.getOrDefault(AppConfigSnapshot())
    }

    fun saveConfig(snapshot: AppConfigSnapshot) {
        val json = MiniJson.stringify(HushProtocol.encodeConfigSnapshot(snapshot))
        prefs.edit().putString(KEY_CONFIG_JSON, json).apply()
    }

    fun loadCleanupLogs(): List<CleanupEvent> {
        val raw = prefs.getString(KEY_CLEANUP_LOGS_JSON, null) ?: return emptyList()
        return runCatching {
            MiniJson.parseObject(raw)
                .listOfObj("logs")
                .map(HushProtocol::decodeCleanupEvent)
                .take(MAX_PERSISTED_LOGS)
        }.getOrDefault(emptyList())
    }

    fun saveCleanupLogs(logs: List<CleanupEvent>) {
        val trimmed = logs.take(MAX_PERSISTED_LOGS)
        val json = MiniJson.stringify(
            mapOf("logs" to trimmed.map(HushProtocol::encodeCleanupEvent)),
        )
        prefs.edit().putString(KEY_CLEANUP_LOGS_JSON, json).apply()
    }

    fun clearCleanupLogs() {
        prefs.edit().remove(KEY_CLEANUP_LOGS_JSON).apply()
    }

    fun loadSession(): DaemonSession? {
        val port = prefs.getInt(KEY_SESSION_PORT, -1)
        val token = prefs.getString(KEY_SESSION_TOKEN, null)
        if (port <= 0 || token.isNullOrBlank()) return null
        return DaemonSession(port = port, token = token)
    }

    fun saveSession(session: DaemonSession) {
        prefs.edit()
            .putInt(KEY_SESSION_PORT, session.port)
            .putString(KEY_SESSION_TOKEN, session.token)
            .apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_SESSION_PORT)
            .remove(KEY_SESSION_TOKEN)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "hush_engine_prefs"
        private const val KEY_CONFIG_JSON = "config_snapshot_json"
        private const val KEY_CLEANUP_LOGS_JSON = "cleanup_logs_json"
        private const val KEY_SESSION_PORT = "daemon_session_port"
        private const val KEY_SESSION_TOKEN = "daemon_session_token"
        const val MAX_PERSISTED_LOGS = 200
    }
}

data class DaemonSession(
    val port: Int,
    val token: String,
)
