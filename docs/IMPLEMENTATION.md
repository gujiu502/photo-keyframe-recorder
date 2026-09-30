# Implementation and validation

Base: `tuuhin/RecorderApp`, stable tag `v1.4.4`, MIT. Minimum Android 10 / API 29; compile and target API 36. Existing Material 3, Hilt, MediaRecorder, Media3, notification and Room architecture are retained.

The upstream recording offset used wall-clock deltas in `RecorderStopWatch`. The new `SessionClock` uses Android elapsed realtime and excludes pauses. New text and photo timeline items use `Long` milliseconds; legacy bookmarks retain their compatible table and convert to milliseconds at the export boundary.

Room 6 → 7 adds `recording_sessions` and `timeline_items`, leaving existing recording, category and bookmark rows intact. Session IDs are UUIDs. Audio working files move from purgeable cache to private files. Session metadata is stored before recording begins; the audio path is persisted before MediaRecorder writes. The foreground notification starts before asynchronous recorder initialization.

CameraX stays in the Compose/Activity lifecycle. Shutter events read the engine clock before file I/O. Photo metadata starts as WRITING, CameraX writes a `.tmp`, the image is validated and synced, then renamed and marked READY. Stop associates pending timeline items in a Room transaction. A stable session-based MediaStore filename and persisted URI make retrying audio publication reuse the same destination.

Recovery scans interrupted sessions and validates unfinished photo writes. It never deletes unfinished audio automatically. Interrupted AAC/MP4 can lack a final container index; those files are preserved and can be exported for external repair rather than claimed to be playable. Playback uses the original player seek API. Export streams audio, original JPEGs, versioned JSON and Markdown into a user-selected ZIP.

Trash retains metadata and photos. Permanent deletion clears attachments. Android 10 restoration remaps the original timeline to the new MediaStore ID. Auto Backup is disabled; explicit lecture export is the backup path. No Internet permission, telemetry, advertising or cloud service is added.

Room 7 → 8 adds the chosen file name. Saving asks for a name and appends local date/time (`yyyy-MM-dd_HH-mm-ss`) and the encoder extension. MediaStore uses the UUID name only while reserving a destination; the URI is persisted before the file is renamed to the chosen name. Notification Stop opens the same naming prompt. UI resources, accessibility labels, notifications and widgets use Chinese; Bengali/Hindi resources are excluded from the APK.

Automated checks:

Verified locally on 2026-09-30: debug build; two JVM clock/naming tests and five Android 11 instrumentation tests. The instrumentation persistence test validates a ZIP export with 101 photo entries and JSON millisecond offsets. A real CameraX recording with three photos (including two while paused) was saved with the requested name and timestamp. Its ZIP was exported from the player through the Android document picker: ZIP CRCs, 3 JPEGs, 3 sorted timeline items, UTF-8 Markdown and audio SHA-256 identical to the original all passed. Signed release and lint are also checked before publication.

- JVM: monotonic clock, repeated pause/resume, multiple pauses, two-hour and beyond-one-day offsets.
- Android instrumentation: 6 → 7 and 7 → 8 migrations preserving legacy bookmarks and keyframes; 100 photos plus interrupted writes; invalid images; stable ordering; repeated finalization; attachment cleanup.
- Device smoke script: actual CameraX capture, paused capture, background/reconnect, stop, filename validation and timestamp, ZIP export through the document picker with byte-for-byte audio comparison, force-stop recovery.
- Release build, lint and APK signature verification.

The first public APK is a preview. Two continuous hours of real microphone recording with 100 CameraX captures, physical-device interruptions, calls, headsets and low-storage fault injection require additional validation. Simulated two-hour offsets and 100 image-file writes do not prove the continuous-recording gate.
