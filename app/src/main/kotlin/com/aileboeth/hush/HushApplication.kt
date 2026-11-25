package com.aileboeth.hush

import android.app.Application
import com.aileboeth.hush.contract.HushGraph
import com.aileboeth.hush.engine.FakeHushGraph
import com.aileboeth.hush.engine.RealHushGraph
import com.aileboeth.hush.state.HushStore

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
    }

    companion object {
        const val USE_FAKE_GRAPH: Boolean = BuildConfig.USE_FAKE_GRAPH
    }
}
