package cam.engram.app

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import cam.engram.app.audio.VoiceRecorder
import cam.engram.app.audio.VoiceRecorderFactory
import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.db.MemoryFts
import cam.engram.app.data.media.MediaSource
import cam.engram.app.data.media.SourceItem
import cam.engram.app.enrich.PlaceProvider
import cam.engram.app.enrich.WeatherProvider
import cam.engram.app.enrich.WeatherReading
import cam.engram.app.ui.LocalAppContainer
import cam.engram.app.ui.theme.EngramTheme
import cam.engram.format.jpeg.JpegEmbedder
import cam.engram.format.records.AudioPayload
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.testing.SyntheticMedia
import cam.engram.format.xmp.XmpCoreEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Shared harness for screen tests. `fakeContainer()` builds an AppContainer backed by an
 * in-memory Room DB and FakeContentAccess with `io = Unconfined` (so a screen's async
 * LaunchedEffect loads resolve eagerly); `setScreen` provides it via LocalAppContainer;
 * the seed helpers populate the DB and fake files a screen reads. A screen test is then:
 *
 *   val app = fakeContainer(); app.seedMemory(1, "sunrise")
 *   compose.setScreen(app) { MemoryDetailScreen(1, onAnnotate = {}, onBack = {}) }
 *   compose.onNodeWithText("sunrise").assertExists()
 */

private val noopRecorderFactory =
    object : VoiceRecorderFactory {
        override fun create(): VoiceRecorder =
            object : VoiceRecorder {
                override fun start(output: File) = Unit

                override fun stop(): Boolean = false
            }
    }

/** In-memory MediaSource so reconcile keeps seeded queue items instead of pruning them. */
class FakeMediaSource : MediaSource {
    val items = mutableListOf<SourceItem>()

    override suspend fun snapshot(includeScreenshots: Boolean): List<SourceItem> = items.toList()
}

// inert by default so no test accidentally reaches the device geocoder or the network
private val inertPlace =
    object : PlaceProvider {
        override suspend fun place(
            lat: Double,
            lon: Double,
        ): String? = null
    }

private val inertWeather =
    object : WeatherProvider {
        override suspend fun weather(
            lat: Double,
            lon: Double,
            atMillis: Long,
        ): WeatherReading? = null
    }

fun fakeContainer(
    context: Context = ApplicationProvider.getApplicationContext(),
    db: EngramDb = EngramDb.inMemory(context),
    access: FakeContentAccess = FakeContentAccess(),
    placeProvider: PlaceProvider = inertPlace,
    weatherProvider: WeatherProvider = inertWeather,
    // a test can park io work (e.g. an in-flight save) on a scheduler it controls
    io: CoroutineDispatcher = Dispatchers.Unconfined,
    recorderFactory: VoiceRecorderFactory = noopRecorderFactory,
): AppContainer =
    AppContainer(
        context = context,
        db = db,
        access = access,
        source = FakeMediaSource(),
        io = io,
        recorderFactory = recorderFactory,
        placeProvider = placeProvider,
        weatherProvider = weatherProvider,
    )

fun ComposeContentTestRule.setScreen(
    container: AppContainer,
    content: @Composable () -> Unit,
) = setContent {
    // give each screen its own ViewModelStore and clear it when the composition tears down, so a
    // screen's ViewModel (its viewModelScope coroutines and stateIn WhileSubscribed flows) is
    // cancelled between tests; a test that closes its db in teardown relies on this to unsubscribe
    // the screen's Room-backed flows before the pool closes (see QueueScreenTest's rule ordering)
    val vmOwner =
        remember {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }
    DisposableEffect(Unit) { onDispose { vmOwner.viewModelStore.clear() } }
    CompositionLocalProvider(
        LocalAppContainer provides container,
        LocalViewModelStoreOwner provides vmOwner,
    ) {
        EngramTheme { content() }
    }
}

/** QueueScreen gates on runtime media permissions, which Robolectric denies by default. */
fun grantMediaPermissions() =
    shadowOf(ApplicationProvider.getApplicationContext<Application>())
        .grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)

/** Android 14 "Select photos": only the user-selected subset is granted, i.e. partial access. */
fun grantPartialMediaAccess() =
    shadowOf(ApplicationProvider.getApplicationContext<Application>())
        .grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

private fun mediaItem(
    id: Long,
    isVideo: Boolean,
    mime: String,
    recordCount: Int,
) = MediaItemEntity(
    mediaId = id,
    uri = "content://media/$id",
    isVideo = isVideo,
    mime = mime,
    relativePath = "DCIM/Camera/",
    takenAtMillis = id,
    sizeBytes = 0,
    dateModified = id,
    recordCount = recordCount,
    payloadLength = 0,
    lastScanMillis = 0,
)

/** recordCount: 0 = waiting in the queue, >0 = annotated (timeline/browse). */
suspend fun AppContainer.seedItem(
    id: Long,
    recordCount: Int = 0,
    isVideo: Boolean = false,
    mime: String = "image/jpeg",
): MediaItemEntity {
    val item = mediaItem(id, isVideo, mime, recordCount)
    db.media().upsert(listOf(item))
    return item
}

/** Seeds a row plus note-bearing (and optionally audio-bearing) bytes so MemoryReader yields a populated Memory. */
suspend fun AppContainer.seedMemory(
    id: Long,
    note: String,
    withAudio: Boolean = true,
): MediaItemEntity {
    val records =
        buildList {
            add(EngramRecord(RecordKind.Note, 1, note.encodeToByteArray()))
            if (withAudio) add(EngramRecord(RecordKind.Audio, 2, AudioPayload.encode("audio/ogg", ByteArray(24) { 3 })))
        }
    val bytes = JpegEmbedder(XmpCoreEngine()).embed(SyntheticMedia.jpegPlain(), records, note)
    (access as FakeContentAccess).files["content://media/$id"] = bytes
    return seedItem(id, recordCount = records.size)
}

/** Seeds an annotated row plus its full-text index entry so it appears in browse/timeline and search. */
suspend fun AppContainer.seedBrowsable(
    id: Long,
    note: String,
): MediaItemEntity {
    val item = seedItem(id, recordCount = 1)
    db.search().upsert(MemoryFts(id, note))
    return item
}

/** Registers an unannotated item in the fake MediaSource so a QueueScreen reconcile surfaces it. */
fun AppContainer.seedQueue(id: Long) {
    (access as FakeContentAccess).files["content://media/$id"] = SyntheticMedia.jpegPlain()
    (source as FakeMediaSource).items +=
        SourceItem(id, "content://media/$id", false, "image/jpeg", "DCIM/Camera/", id, 10, id)
}
