package cam.engram.app.data.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** How much of the user's photo/video library the app can currently read (finding H5). */
enum class MediaAccess { FULL, PARTIAL, DENIED }

/**
 * Media-read access state. On Android 14+ a "Select photos" grant yields only
 * READ_MEDIA_VISUAL_USER_SELECTED (PARTIAL): a temporary subset that lapses after the app is
 * backgrounded. Whole-library work (the reconcile prune, background ingestion) must gate on
 * FULL so a partial or lost grant never wipes the index, and the queue steers PARTIAL toward
 * "Allow all".
 */
object MediaPermissions {
    fun state(context: Context): MediaAccess =
        when {
            granted(context, Manifest.permission.READ_MEDIA_IMAGES) &&
                granted(context, Manifest.permission.READ_MEDIA_VIDEO) -> MediaAccess.FULL
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PARTIAL
            else -> MediaAccess.DENIED
        }

    fun hasFullAccess(context: Context): Boolean = state(context) == MediaAccess.FULL

    private fun granted(
        context: Context,
        permission: String,
    ): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
