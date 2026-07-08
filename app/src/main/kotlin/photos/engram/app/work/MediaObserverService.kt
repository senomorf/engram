package photos.engram.app.work

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.MediaStore
import photos.engram.app.EngramApp

/**
 * Content-trigger job on the images collection: best-effort signal that new
 * media landed, which arms the burst nudge. The daily digest and periodic
 * reconcile remain the reliable paths; this is the timely-but-optional one.
 */
class MediaObserverService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext as? EngramApp
        val enabled = app != null
        if (enabled) BurstNudgeWorker.schedule(applicationContext)
        // reschedule so the trigger keeps firing for future changes
        schedule(applicationContext)
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val JOB_ID = 4201

        fun schedule(context: Context) {
            val job =
                JobInfo
                    .Builder(JOB_ID, ComponentName(context, MediaObserverService::class.java))
                    .addTriggerContentUri(
                        JobInfo.TriggerContentUri(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                        ),
                    ).setTriggerContentMaxDelay(TRIGGER_MAX_DELAY_MS)
                    .build()
            context.getSystemService(JobScheduler::class.java).schedule(job)
        }

        private const val TRIGGER_MAX_DELAY_MS = 60_000L
    }
}
