package cam.engram.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import cam.engram.app.appContainer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the real doWork() of each background worker through work-testing. With
 * an empty MediaStore and default settings the reconcile succeeds and no digest
 * fires, so each worker reports success.
 */
@RunWith(RobolectricTestRunner::class)
class WorkersTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun reconcileWorkerSucceeds() =
        runBlocking {
            val worker = TestListenableWorkerBuilder<ReconcileWorker>(context).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
        }

    @Test
    fun digestWorkerSucceedsAndReschedules() =
        runBlocking {
            val worker = TestListenableWorkerBuilder<DigestWorker>(context).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
        }

    @Test
    fun burstNudgeWorkerSucceedsWhenDisabled() =
        runBlocking {
            // off: the worker short-circuits to success before reconciling
            context.appContainer().settings.setBurstNudge(false)
            val worker = TestListenableWorkerBuilder<BurstNudgeWorker>(context).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
        }

    @Test
    fun burstNudgeWorkerReconcilesWhenEnabled() =
        runBlocking {
            // on: the worker reconciles and, finding no waiting media, still succeeds
            context.appContainer().settings.setBurstNudge(true)
            val worker = TestListenableWorkerBuilder<BurstNudgeWorker>(context).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
        }

    @Test
    fun schedulersEnqueueAndCancelWork() {
        // exercises the companion WorkRequest builders + WorkManager enqueue/cancel glue
        ReconcileWorker.schedulePeriodic(context)
        ReconcileWorker.runOnce(context)
        BurstNudgeWorker.schedule(context)
        DigestWorker.reschedule(context, hour = 9, enabled = true)
        val wm = WorkManager.getInstance(context)
        assertTrue(wm.getWorkInfosByTag(ReconcileWorker::class.java.name).get().isNotEmpty())
        DigestWorker.reschedule(context, hour = 9, enabled = false)
    }
}
