# Engram format specification, v0 (draft, private)

Status: draft describing the implemented reality of :core-format as of this
commit. Open under CC BY 4.0 (design D18, see spec/LICENSE). SpecVersion property: `0.1`.
Wire version byte: `1`. Any change to the wire frame or a binding requires
bumping these and a design-doc decision (AGENTS.md rule).

Identity:

- XMP namespace: `https://ns.engram.cam/1.0/` (prefix `engram`), frozen.
- Binary record magic: ASCII `EGRM`.
- MP4 uuid box usertype: `7a0b5c4d-9e2f-4a31-8b6d-0c3e5f719246`.

## 1. Logical model

A media file carries an append-only log of records. Records are never
rewritten or deleted by writers; new state is appended (versioning by
accumulation). Readers derive the current memory as: latest Note by
timestamp + all Audio + latest Enrichment. Every record is independently
addressable by a 16-byte id and attributable via a writer id string.

Record kinds (wire codes): Note 1, Audio 2, Enrichment 3, Transcript 4.
Unknown kinds must be preserved by rewriters and surfaced as unknown by
readers.

## 2. Record wire frame

All integers big-endian. One record:

| offset | size | field |
|--------|------|-------|
| 0 | 4 | magic `EGRM` |
| 4 | 1 | wire version = 1 |
| 5 | 1 | kind code |
| 6 | 2 | flags, reserved, must be 0 |
| 8 | 16 | record id (random) |
| 24 | 8 | timestamp, unix millis |
| 32 | 1 | writer length W (bytes) |
| 33 | W | writer id, UTF-8 |
| 33+W | 4 | payload length P |
| 37+W | P | payload |
| 37+W+P | 4 | CRC32 over bytes [0, 37+W+P) |

Records are self-delimiting: a carver scanning for `EGRM` and validating CRC
can recover records from damaged files without any container index.

Payloads:

- Note: UTF-8 text.
- Audio: u16be mime length, mime (UTF-8), then raw audio bytes. Default codec
  Opus in Ogg (`audio/ogg`); AAC (`audio/mp4`) supported; field open.
- Transcript: UTF-8 text; links to its Audio record by sharing context
  (explicit linkage field: reserved for v0.2, uses flags or payload prefix).
- Enrichment (v0.2): `version u8 | fieldCount u8 | repeated [keyLen u16be, key,
  valLen u16be, val]`, all UTF-8. Stable keys: place, weather, temp_c,
  calendar, source, fetched_at. source and fetched_at carry provenance so a
  reader knows where a datum came from and when. Unknown keys are ignored.

## 3. XMP properties (all bindings)

Written into the standard XMP packet on every write session:

- `engram:SpecVersion` = `0.1`
- `engram:PayloadLength` = total byte length of engram records in the file
- `engram:RecordCount` = number of engram records
- `dc:description["x-default"]` = latest note text (dual-write, design D9)
- `xmpNote:HasExtendedXMP` = md5 guid, only when ExtendedXMP is present

Writers must merge into the existing packet, preserving all foreign
properties. An unparseable existing packet aborts the write (fail closed):
destroying camera metadata is never acceptable.

## 4. JPEG binding

- Standard XMP in an APP1 segment (`http://ns.adobe.com/xap/1.0/` + NUL
  header). When the merged packet exceeds one segment, it is split per Adobe
  ExtendedXMP: essentials stay in the standard packet (dc:description, engram
  properties, HasExtendedXMP guid), everything else moves to the extended
  packet stored across APP1 segments with header
  `http://ns.adobe.com/xmp/extension/` + NUL, 32-char uppercase md5 guid,
  u32be full length, u32be offset per segment.
- IPTC caption mirror: APP13 `Photoshop 3.0` resource 0x0404, IIM datasets
  1:90 (UTF-8 marker), 2:00 (version 4), 2:120 (caption, capped 2000 bytes).
  Other Photoshop resources are preserved byte-exact, and other IIM datasets
  inside 0x0404 (keywords, by-line, copyright, and so on) are carried forward
  unchanged; only 2:120 is replaced.
- Binary records: appended after the last existing post-EOI byte (after any
  vendor trailer), concatenated frames, no container wrapper.
- Insertion safety rules (normative): all segment insertions and replacements
  happen before the MPF APP2 segment so MPF relative offsets keep meaning; the
  primary MP-entry Individual Image Size is rewritten to the new SOI..EOI span on
  any insertion that grows the primary; writes are re-validated with an MPF
  inspector afterwards (which checks both the offset and that size); layouts that
  would require MPF offset rewriting are refused; files whose XMP carries
  MotionPhoto or MicroVideo markers are refused until coexistence rules are
  verified (plan track A); EXIF is never rewritten in v0 (maker note safety,
  design D9).

## 5. PNG binding

- XMP in the standard iTXt chunk, keyword `XML:com.adobe.xmp`, uncompressed.
- Records: one `egRm` chunk per record (ancillary, private, safe-to-copy).
  A record must span its chunk exactly; trailing bytes mark the chunk
  corrupt. No ExtendedXMP (chunks have no 64KB limit).

## 6. MP4 binding

- Records: one `uuid` box with the engram usertype, appended as the trailing
  top-level box, payload = concatenated record frames. Rewriters merge into
  a single tail box; an engram box found elsewhere aborts the write (moving
  it would shift mdat and break moov chunk offsets). Size-zero last boxes are
  materialized to explicit sizes before appending.
- Caption mirror: iTunes-style `moov/udta/meta[hdlr=mdir]/ilst/(c)cmt/data`
  (type 1, UTF-8). Written only when moov is the trailing content box;
  moov-first (faststart) layouts are declined because growing moov breaks
  stco/co64 offsets. A meta box owned by another handler is not touched.

## 7. Expectation sidecar (`.engram-expect`)

Line-based `key=value`, written by `generate` next to its output, consumed by
`verify` for survivability judgment:

    engram-expect=1
    container=jpeg|png|mp4
    records=N
    note.b64=<base64 of note text>     (optional)
    note.id=<hex record id>            (optional)
    audio.id=<hex>  audio.mime=<mime>  audio.sha256=<hex of raw audio bytes>
    mpf=valid|absent
    extended=true|false

## 8. Verify semantics

Per planted payload: `exact` (record present, id and content match),
`degraded` (record gone but a caption mirror still carries the note text),
`corrupted` (record present but CRC or content mismatch), `gone`. File
verdict: `intact` (all exact), `degraded`, `damaged`; process exit codes
0 / 3 / 4. `--json` emits one object with records, checks, xmp, mpf,
extendedXmp and caption fields; the schema is append-only.

## 9. Size budget

Product-level soft cap ~10MB of embedded records per file (design D13):
warn, never silently drop. The format itself only bounds ExtendedXMP
reassembly at 64MB as an anti-bomb guard.

## 10. Reserved / future

- Frame envelope freeze (normative): the frame field layout of section 2,
  `magic | version u8 | kind u8 | flags u16be | id (16) | tsMillis u64be |
  writerLen u8 | writer | payloadLen u32be | payload | crc32 u32be`, is frozen
  across wire versions. Future versions may add record kinds, reinterpret
  payload semantics, and assign `flags` bits, but must not move or resize
  envelope fields. A reader therefore treats a structurally valid frame of any
  other wire version as opaque: surfaced as unknown, never parsed into a typed
  record, and preserved byte-exact by rewriters exactly like an unknown kind.
- Frame `flags` bits: all reserved, writers write 0, readers ignore.
- Transcript-to-Audio linkage, Enrichment payload schema: v0.2.
- Compaction (rewriting history into a snapshot): explicitly out of scope
  until versioning UX exists; append-only is the invariant until then.
- HEIF/HEIC binding: deferred; must not assume a JPEG-like tail.

## 11. Engram Archive

The disaster-recovery serialization (design D14, D28): one directory holding,
per media item, a byte-exact record log, a readable JSON view, and any audio
blobs, plus a manifest inventory. All fields are append-only.

- `manifest.json`: `{"archive":"engram","manifestVersion":3,"itemCount":N,
  "files":[{"name":...,"sha256":...},...]}`. The `files` array inventories every
  written file with its sha-256, so a validator can prove the archive complete.
  Version 2 inventoried with md5; version 1 (itemCount only) had no inventory.
- `<contentHash>.records`: the authoritative record log: the item's CRC-valid
  frames concatenated byte-exact in log order, exactly the wire format of
  section 2. Opaque frames (unknown kinds, future wire versions) are carried
  unmodified, so any conforming reader recovers everything with the ordinary
  frame decoder and nothing is lost in translation.
- `<contentHash>.json`: a readable view (current note, note history,
  transcripts, latest enrichment, audio file names, `recordLog` file name,
  `frameCount`). The view may lose detail; the record log never does.
- `<contentHash>_<n>.<ext>`: audio payloads extracted for direct playback.
- `<contentHash>` is the sha-256 of the source media file: it names the entry
  and doubles as the import identity key. Archives written before manifest v3
  used md5 names; they stay valid within their own directories.
