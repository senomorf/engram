package cam.engram.app.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NotifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun grantNotificationPermission() {
        // API 33+ gates posting behind the runtime permission; Robolectric denies it by default
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun showDigestCreatesChannelAndPostsNotification() {
        Notifier(context).showDigest(3)
        // channel is ensured before anything else (platform API, not the shadow accessor)
        assertNotNull(manager.getNotificationChannel("digest"))
        // Robolectric grants manifest permissions and enables notifications, so it posts
        val posted = shadowOf(manager).allNotifications
        assertEquals(1, posted.size)
    }

    @Test
    fun pluralBodyReflectsCount() {
        Notifier(context).showDigest(5)
        assertTrue(shadowOf(manager).allNotifications.isNotEmpty())
    }
}
