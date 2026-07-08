package cam.engram.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cam.engram.app.EngramApp
import cam.engram.app.notify.Notifier
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Daily digest: one notification at the user-set hour when un-annotated media
 * exists, silence otherwise (design D12). Implemented as one-time work that
 * re-arms itself for the next local occurrence of the hour after each run, so
 * it does not drift across DST the way a fixed 24h period would (review F17).
 */
class DigestWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? EngramApp ?: return Result.failure()
        val settings = app.container.settings.current()
        if (!settings.digestEnabled) return Result.success()
        return runCatching {
            app.container.reconciler.reconcile()
            val waiting =
                app.container.db
                    .media()
                    .unannotatedCount()
            if (waiting > 0) Notifier(applicationContext).showDigest(waiting)
            Result.success()
        }.getOrElse { Result.retry() }.also {
            // re-arm for tomorrow's local hour regardless of this run's outcome
            reschedule(applicationContext, settings.digestHour, settings.digestEnabled)
        }
    }

    companion object {
        private const val NAME = "digest-daily"

        fun reschedule(
            context: Context,
            hour: Int,
            enabled: Boolean,
            now: Calendar = Calendar.getInstance(),
        ) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request =
                OneTimeWorkRequestBuilder<DigestWorker>()
                    .setInitialDelay(delayToHour(hour, now), TimeUnit.MILLISECONDS)
                    .build()
            wm.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun delayToHour(
            hour: Int,
            now: Calendar,
        ): Long {
            val next = now.clone() as Calendar
            next.set(Calendar.HOUR_OF_DAY, hour)
            next.set(Calendar.MINUTE, 0)
            next.set(Calendar.SECOND, 0)
            next.set(Calendar.MILLISECOND, 0)
            if (next.timeInMillis <= now.timeInMillis) next.add(Calendar.DAY_OF_YEAR, 1)
            return next.timeInMillis - now.timeInMillis
        }
    }
}
