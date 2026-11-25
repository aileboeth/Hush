package com.aileboeth.hush.engine

import android.content.Context
import android.content.SharedPreferences
import com.aileboeth.hush.contract.AppConfigSnapshot
import com.aileboeth.hush.contract.HushProtocol
import com.aileboeth.hush.contract.MiniJson

/**
 * Lightweight local persistence for rules, restriction ledger, and last daemon session info.
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
        private const val KEY_SESSION_PORT = "daemon_session_port"
        private const val KEY_SESSION_TOKEN = "daemon_session_token"
    }
}

data class DaemonSession(
    val port: Int,
    val token: String,
)
