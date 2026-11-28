package com.aileboeth.hush

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.aileboeth.hush.contract.HushLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Lightweight, zero-notification periodic Wi-Fi watchdog scheduled via Android's built-in [JobScheduler].
 *
 * Only runs when the device is connected to an unmetered (Wi-Fi) network. If the OS restarted `adbd`
 * and killed `hush_daemon` (e.g. after DHCP/IP changes, deep sleep, or leaving/returning home), this
 * silently re-enables wireless debugging (when `WRITE_SECURE_SETTINGS` is granted) and relaunches
 * `hush_daemon` without requiring the user to open Developer Options.
 */
class HushAutoRecoverJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runningJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as? HushApplication ?: return false
        runningJob?.cancel()
        runningJob = scope.launch {
            try {
                app.store.runBackgroundWatchdogCheck()
            } catch (t: Throwable) {
                HushLog.w("Watchdog", "Background auto-recover job failed", t)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningJob?.cancel()
        runningJob = null
        return true
    }

    companion object {
        private const val JOB_ID = 2026024
        private const val INTERVAL_MS = 15 * 60 * 1000L

        fun schedule(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            val info = JobInfo.Builder(
                JOB_ID,
                ComponentName(context.applicationContext, HushAutoRecoverJobService::class.java),
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)
                .build()
            val res = runCatching { js.schedule(info) }.getOrDefault(JobScheduler.RESULT_FAILURE)
            HushLog.i("Watchdog", "Scheduled periodic Wi-Fi auto-recover job result=$res")
        }
    }
}
