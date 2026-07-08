package photos.engram.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import photos.engram.app.EngramApp
import photos.engram.app.notify.Notifier
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Daily digest: one notification at the user-set hour when un-annotated media
 * exists, silence otherwise (design D12). Scheduled with an initial delay to
 * the next occurrence of that hour, then repeating daily.
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
        }.getOrElse { Result.retry() }
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
                PeriodicWorkRequestBuilder<DigestWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(delayToHour(hour, now), TimeUnit.MILLISECONDS)
                    .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
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
