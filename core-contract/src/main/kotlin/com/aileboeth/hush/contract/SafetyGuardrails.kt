package com.aileboeth.hush.contract

/**
 * Hard-coded safety net shared between UI and `daemon-shell`.
 * The daemon unconditionally enforces these rules even if misconfigured by a client.
 */
object SafetyGuardrails {
    const val SELF_PACKAGE = "com.aileboeth.hush"

    private val criticalPackages: Set<String> = setOf(
        SELF_PACKAGE,
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.settings",
        "com.android.shell",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.server.telecom",
        "com.android.incallui",
        "com.android.mms.service",
        "com.android.keychain",
        "com.android.externalstorage",
        "com.android.documentsui",
        "com.android.webview",
        "com.google.android.webview",
        "com.android.captiveportallogin",
        "com.android.dynsystem",
        "com.android.se",
        "com.android.providers.telephony",
        "com.android.providers.contacts",
        "com.android.providers.media",
        "com.android.providers.settings",
        "com.android.permissioncontroller",
        "com.vivo.permissionmanager",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.networkstack",
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",
        "com.sohu.inputmethod.sogou.vivo",
        "com.bbk.launcher2",
        "com.vivo.upslide",
        "com.vivo.pushservice",
        "com.vivo.daemonService",
        "com.vivo.sps",
        "com.vivo.pem",
        "com.vivo.abe",
        "com.vivo.biometrics",
        "com.vivo.fingerprintui",
        "com.iqoo.secure",
    )

    private val criticalPrefixes: List<String> = listOf(
        "com.android.internal.",
        "com.android.providers.",
        "com.mediatek.",
        "vendor.",
    )

    /**
     * Returns true if the package MUST NEVER be force-stopped or appops-restricted.
     */
    fun isSystemProtected(packageName: String, uid: Int = -1): Boolean {
        if (uid in 0..9999) return true
        val normalized = packageName.trim()
        if (normalized.isEmpty()) return true
        if (normalized in criticalPackages) return true
        if (criticalPrefixes.any { normalized.startsWith(it) }) return true
        return false
    }

    /**
     * Returns true if applying destructive `appops` / `standby-bucket` restrictions should be blocked
     * to avoid breaking real-time push delivery or system stability.
     */
    fun isRestrictionBlocked(packageName: String, group: AppGroup, uid: Int = -1): Boolean {
        if (isSystemProtected(packageName, uid)) return true
        if (group == AppGroup.PROTECTED || group == AppGroup.SOFT_TRIM) return true
        return false
    }
}
