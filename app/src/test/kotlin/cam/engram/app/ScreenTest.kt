package cam.engram.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.rules.ExternalResource

/**
 * Base for Robolectric screen tests. The compose rule (order 1, inner) disposes the composition on
 * teardown, clearing the per-composition ViewModelStore (setScreen) so each screen's viewModelScope is
 * cancelled and its Room-backed flows unsubscribe BEFORE the outer rule (order 0) closes the registered
 * databases. Without that order a screen that mutates an observed table while a flow is subscribed (e.g.
 * QueueScreen's reconcile) leaves a queued re-query mid-flight when the pool closes, throwing from the
 * Flow observer (SQLiteConnectionPool ISE; the race EngramDb.inMemory's inline executor prevents under v1,
 * reintroduced by the v2 queued clock, issue #99). Build each container as `fakeContainer().closingDb()`.
 */
abstract class ScreenTest {
    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val databases = mutableListOf<AppContainer>()

    /** Registers this container's in-memory db to be closed after the composition disposes; returns it. */
    protected fun AppContainer.closingDb(): AppContainer = also { databases += it }

    @get:Rule(order = 0)
    val databaseTeardown =
        object : ExternalResource() {
            override fun after() {
                databases.forEach { it.db.close() }
            }
        }
}
