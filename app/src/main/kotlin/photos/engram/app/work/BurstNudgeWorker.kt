package photos.engram.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import photos.engram.app.EngramApp
import photos.engram.app.notify.Notifier
import java.util.concurrent.TimeUnit

/**
 * Post-burst nudge (design D12, default off): fires a few minutes after new
 * camera media appears, while the memory is fresh. Debounced by a unique work
 * name so a shooting burst collapses into one nudge.
 */
class BurstNudgeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? EngramApp ?: return Result.failure()
        val settings = app.container.settings.current()
        if (!settings.burstNudgeEnabled) return Result.success()
        return runCatching {
            app.container.reconciler.reconcile()
            val waiting =
                app.container.db
                    .media()
                    .unannotatedCount()
            if (waiting > 0) Notifier(applicationContext).showDigest(waiting)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val NAME = "burst-nudge"

        fun schedule(context: Context) {
            val request =
                OneTimeWorkRequestBuilder<BurstNudgeWorker>()
                    .setInitialDelay(BURST_DELAY_MINUTES, TimeUnit.MINUTES)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }

        private const val BURST_DELAY_MINUTES = 3L
    }
}
