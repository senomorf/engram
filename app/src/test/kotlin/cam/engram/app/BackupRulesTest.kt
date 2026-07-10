package cam.engram.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import kotlin.test.assertTrue

/**
 * The no-cloud promise (finding 5): the record cache (notes + audio in engram.db), voice
 * drafts and the write-back backups must be excluded from cloud backup so private memories
 * never leave the phone. Guards against the cloud-backup section going empty again.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRulesTest {
    @Test
    fun cloudBackupExcludesSensitiveStores() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        var section = ""
        val cloudExcludes = mutableSetOf<String?>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "cloud-backup", "device-transfer" -> section = parser.name
                    "exclude" -> if (section == "cloud-backup") cloudExcludes += parser.getAttributeValue(null, "path")
                }
            }
            event = parser.next()
        }
        assertTrue("engram.db" in cloudExcludes, "the record cache DB must be excluded from cloud backup")
        assertTrue("writeback/" in cloudExcludes, "the write-back backups must be excluded from cloud backup")
        assertTrue("drafts/" in cloudExcludes, "voice drafts must be excluded from cloud backup")
    }
}
