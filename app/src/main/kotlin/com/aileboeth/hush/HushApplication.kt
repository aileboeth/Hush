package com.aileboeth.hush

import android.app.Application
import android.os.Build
import com.aileboeth.hush.contract.HushGraph
import com.aileboeth.hush.contract.HushLog
import com.aileboeth.hush.engine.FakeHushGraph
import com.aileboeth.hush.engine.RealHushGraph
import com.aileboeth.hush.state.HushStore
import java.io.File

/**
 * Application entry point holding the singleton [HushGraph] and application-scoped [HushStore].
 * Keeping the store here guarantees in-flight daemon actions survive activity recreation or rotation.
 */
class HushApplication : Application() {

    lateinit var graph: HushGraph
        private set

    lateinit var store: HushStore
        private set

    override fun onCreate() {
        super.onCreate()
        HushLog.configure(
            source = "app",
            file = File(filesDir, "logs/hush_app.log"),
        )
        installCrashLogger()
        HushLog.i(
            "App",
            "Started Hush v${BuildConfig.VERSION_NAME} (vc=${BuildConfig.VERSION_CODE}) on ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
        graph = if (USE_FAKE_GRAPH) {
            FakeHushGraph()
        } else {
            RealHushGraph(
                context = applicationContext,
                versionCode = BuildConfig.VERSION_CODE,
            )
        }
        store = HushStore(
            context = applicationContext,
            graph = graph,
        )
        if (!USE_FAKE_GRAPH) {
            HushAutoRecoverJobService.schedule(applicationContext)
        }
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                HushLog.e("Crash", "Uncaught exception on thread '${thread.name}'", throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val USE_FAKE_GRAPH: Boolean = BuildConfig.USE_FAKE_GRAPH
    }
}
