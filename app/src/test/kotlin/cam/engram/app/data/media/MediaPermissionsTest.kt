package cam.engram.app.data.media

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MediaPermissionsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun grant(vararg permissions: String) =
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(*permissions)

    @Test
    fun deniedWhenNothingGranted() {
        assertEquals(MediaAccess.DENIED, MediaPermissions.state(context))
        assertFalse(MediaPermissions.hasFullAccess(context))
    }

    @Test
    fun partialWhenOnlyUserSelectedGranted() {
        grant(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertEquals(MediaAccess.PARTIAL, MediaPermissions.state(context))
        assertFalse(MediaPermissions.hasFullAccess(context))
    }

    @Test
    fun fullWhenImagesAndVideoGranted() {
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        assertEquals(MediaAccess.FULL, MediaPermissions.state(context))
        assertTrue(MediaPermissions.hasFullAccess(context))
    }
}
