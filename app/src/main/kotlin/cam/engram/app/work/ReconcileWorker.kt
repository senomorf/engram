package cam.engram.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cam.engram.app.EngramApp
import java.util.concurrent.TimeUnit

class ReconcileWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? EngramApp ?: return Result.failure()
        // crash-safety first: restore any interrupted write before rescanning
        runCatching { app.container.writeBack.recoverPending() }
        return runCatching { app.container.reconciler.reconcile() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        private const val PERIODIC = "reconcile-periodic"
        private const val ONCE = "reconcile-once"

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReconcileWorker>(6, TimeUnit.HOURS).build(),
            )
        }

        fun runOnce(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReconcileWorker>().build(),
            )
        }
    }
}
