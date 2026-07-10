package cam.engram.app.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/** recordCount semantics: -1 not yet scanned, 0 scanned and empty, >0 annotated. */
@Entity(tableName = "media_items")
data class MediaItemEntity(
    @PrimaryKey val mediaId: Long,
    val uri: String,
    val isVideo: Boolean,
    val mime: String,
    val relativePath: String,
    val takenAtMillis: Long,
    val sizeBytes: Long,
    // MediaStore DATE_MODIFIED: catches metadata-only rewrites that keep the
    // same byte size (change detection, review F11)
    val dateModified: Long,
    val recordCount: Int,
    val payloadLength: Long,
    val lastScanMillis: Long,
    // MediaStore DISPLAY_NAME captured at reconcile: names archive entries (D28)
    val displayName: String = "",
)

/**
 * Strip-recovery cache (design D3): last successfully parsed records per item.
 * The one table not rebuildable from files; exported with the Engram Archive.
 * identityTakenAt pins the cached records to a specific capture, so a reused
 * MediaStore id cannot graft one photo's memories onto another (review F6).
 */
@Entity(tableName = "record_cache")
data class RecordCacheEntity(
    @PrimaryKey val mediaId: Long,
    val identityTakenAt: Long,
    val sizeBytesAtScan: Long,
    val recordsBlob: ByteArray,
    val recordCount: Int,
    val updatedMillis: Long,
    // captured at scan/write time so a cache orphan (media file moved or deleted) still exports:
    // originalName names the archive entry, contentHash content-addresses it (finding 9)
    val originalName: String = "",
    val contentHash: String = "",
)

/**
 * Pre-fetched enrichment record per item (review F5). Filled by a background
 * worker so the user save path never waits on the network; the write-back
 * reads it instantly and includes it when present.
 */
@Entity(tableName = "enrichment_cache")
data class EnrichmentCacheEntity(
    @PrimaryKey val mediaId: Long,
    val recordBlob: ByteArray,
    val updatedMillis: Long,
)

/** Crash-safe annotation drafts: a note survives even if write-back never ran. */
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val mediaId: Long,
    val text: String?,
    val audioPath: String?,
    val updatedMillis: Long,
)

data class Counts(
    val total: Int,
    val annotated: Int,
    val waiting: Int,
    val unscanned: Int,
)

/**
 * Full-text index over note and transcript text (design: search across notes
 * and transcripts). Rebuilt from files during scan, so it is not a source of
 * truth. docid ties a row back to its media item.
 */
@Fts4
@Entity(tableName = "memory_fts")
data class MemoryFts(
    @PrimaryKey(autoGenerate = false)
    val rowid: Long,
    val text: String,
)
