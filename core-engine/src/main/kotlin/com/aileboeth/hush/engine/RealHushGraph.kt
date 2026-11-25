package com.aileboeth.hush.engine

import android.content.Context
import com.aileboeth.hush.bootstrap.WirelessBootstrapController
import com.aileboeth.hush.contract.BootstrapController
import com.aileboeth.hush.contract.GuardController
import com.aileboeth.hush.contract.HushGraph

/**
 * Production lightweight manual DI graph (no Hilt/Dagger runtime overhead).
 */
class RealHushGraph(
    context: Context,
    versionCode: Int,
) : HushGraph {
    private val store = HushConfigStore(context)
    private val client = DaemonClient()
    private val realGuard = RealGuardController(context, store, client)

    override val guardController: GuardController = realGuard
    override val bootstrapController: BootstrapController = WirelessBootstrapController(
        context = context,
        versionCode = versionCode,
        onDaemonReady = { port, token -> realGuard.attachSession(port, token) },
    )
    override val isPreviewMode: Boolean = false
}
