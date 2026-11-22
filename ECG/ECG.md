# ECG Streaming Mode Specification

## 1. Purpose

Add a dedicated ECG streaming mode for viewing and optionally recording raw
Polar H10 ECG data. ECG mode is not an HRV session and must not calculate,
store, or display HRV or other derived biofeedback metrics.

The only non-ECG value displayed is the heart rate reported natively by the
H10, received from its approximately 1 Hz heart-rate stream.

## 2. Entry Point

- Add an **ECG** button on the application's main screen.
- Reorganize the affected home-screen rows as follows:
  - current Find RF row: **Find RF** on the left and **Live Metrics** on the
    right
  - next row: **Full Report** on the left and **ECG** on the right
- The two rows use equal-width action cards with the existing home-screen
  styling, spacing, icon, and color conventions.
- Pressing **ECG** opens the dedicated ECG screen.
- ECG mode requires an available Polar H10 connection.
- If the sensor is unavailable or disconnected, the screen must present an
  actionable connection/stream error and must not display stale data as live.

Home-screen arrangement reference: `home-screen-mockup.svg`.

ECG-screen presentation references: `ecg-screen-landscape-mockup.svg` and
`ecg-screen-portrait-mockup.svg`.

## 3. Data Acquisition

- Stream raw ECG samples at the maximum Polar H10 ECG rate of 130 Hz.
- Do not feed ECG-mode samples into `HrvProcessor` and do not calculate:
  - HRV metrics
  - RR interval statistics
  - respiration
  - coherence
  - signal-quality metrics derived by the existing HRV processing pipeline
- Obtain displayed heart rate directly from the H10 heart-rate stream. Do not
  calculate heart rate from R peaks or ECG samples.
- Acquisition continues while the plot is paused, while the app is in the
  background, and while the phone is locked.
- A single ECG acquisition owner must survive ECG-screen recomposition and
  orientation changes. Rotation must not create duplicate Polar ECG stream
  subscriptions.

## 4. ECG Circular Buffer

### 4.1 Capacity

- Store ECG samples in a fixed-capacity circular buffer.
- The buffer duration must be configurable as a variable.
- Initial buffer duration: 60 seconds.
- At 130 Hz, the nominal 60-second capacity is 7,800 samples.
- The buffer stores integer sample values without an individual timestamp for
  every sample.

### 4.2 Time indexing

- Maintain compact timing metadata for each continuous data segment rather
  than timestamps for every sample.
- A segment contains, at minimum:
  - timestamp of its first retained sample
  - starting sample/sequence index
  - expected sample rate
- Calculate a sample timestamp from the segment start timestamp and its
  offset at the nominal 130 Hz rate.
- Preserve sufficient continuity metadata to locate samples and plot time
  gaps correctly as the circular buffer wraps.

### 4.3 Packet loss and discontinuities

- Compare incoming Polar sample timestamps/sequence progression with the
  expected 130 Hz progression.
- A mismatch that indicates lost samples creates a discontinuity record.
- Do not compress time across a discontinuity and do not draw a connecting
  line across the gap.
- Keep discontinuity information while any related samples remain in the
  buffer.
- If a gap extends beyond retained-buffer history, only the retained portion
  and its timing boundary need to be represented.

## 5. Plot

### 5.1 Visual design

The attached reference image is inspiration for the presentation:

- A large line graph is the dominant element, nearly edge-to-edge.
- Use a light plotting background.
- Use a fine, low-contrast ECG grid and sufficient axis/grid labeling to
  orient the viewer without clutter.
- Draw the ECG waveform in bright blue.
- Display the latest H10 heart-rate value prominently at the top-left of the
  plot in large red text.
- Do not include HRV cards or other analytics.
- Ignore the star and pencil seen in the reference image.
- A vertical-axis value scale may be fixed or autoscaled; the initial
  implementation target is a stable fixed scale unless later UX testing calls
  for autoscaling.

### 5.2 Live drawing and scrolling

- Initially draw incoming samples from left to right until the trace fills the
  allotted graph area.
- Once the plot is approximately 80% full, advance the plot in
  heartbeat-sized steps:
  1. shift the waveform left by one heartbeat;
  2. draw the next heartbeat's incoming samples;
  3. repeat for subsequent heartbeat boundaries.
- Heartbeat boundaries and scroll duration may use the native H10 HR/RR
  timing, but must not invoke the HRV processing pipeline.
- When incoming ECG data stops, continue advancing the plot in time while
  leaving the affected interval blank.
- Never fabricate waveform samples during a stream gap.
- Never connect valid samples across a detected discontinuity.

### 5.3 Lifecycle behavior

- Plot rendering stops when the app is in the background or the phone is
  locked.
- Acquisition continues in that state, and an active recording also
  continues.
- On return to the foreground, resume rendering with the latest realtime
  data.
- If plotting was paused or acquisition was stopped, foregrounding displays
  the latest retained data for that mode.

## 6. Controls and States

### 6.1 Plot pause/resume

- Provide a **Pause/Resume** control.
- Pausing freezes only the plot.
- ECG acquisition and active recording continue while paused.
- Samples acquired during a pause remain in the circular buffer.
- Resuming initially moves the viewport to the latest realtime data.

### 6.2 Stop

- Provide a **Stop** control next to Pause/Resume.
- Stop:
  - ends ECG acquisition;
  - finalizes an active recording;
  - retains the circular buffer for inspection until the ECG session is
    discarded;
  - leaves the latest retained waveform visible.
- Stop is distinct from pausing and is distinct from stopping recording with
  the Record control.

### 6.3 Record

- Provide a toggle-style **Record** control.
- While not recording, pressing Record:
  - starts a new recording file;
  - retroactively writes the existing circular buffer, beginning with its
    oldest retained sample;
  - continues writing new samples as they arrive.
- Pressing Record while recording:
  - stops and safely finalizes the current file;
  - does not stop ECG acquisition or plotting.
- Pressing Record again after recording has stopped:
  - creates a new timestamped file;
  - again begins with the currently retained buffer contents;
  - does not append to a prior recording.
- Recording remains active while the plot is paused.
- Recording continues in the background and while the phone is locked.

## 7. Recording File

### 7.1 Location and naming

- Store recordings in the app-specific external-storage directory:

  `Android/data/com.hrv.biofeedback/files/ecg_data/`

- Programmatically, this is the application's external files directory with
  the `ecg_data` child directory.
- Create the directory if necessary.
- Filename format: `yyyymmdd-hhmmss.bin`.
- Generate the filename using the device-local date and time when Record is
  pressed.
- Files are separate recordings and are never reopened for append after
  recording is stopped.

### 7.2 Binary format

Each recording contains:

1. An 8-byte signed Unix epoch timestamp in milliseconds, little-endian.
2. Consecutive 3-byte signed, two's-complement ECG samples, little-endian.

The timestamp identifies the actual timestamp of the first sample written,
which is the oldest available buffered sample when retroactive recording
begins. It is not necessarily the time when Record was pressed.

The implicit file format constants are:

- sample rate: 130 Hz
- sample type: signed 24-bit integer
- sample byte order: little-endian
- no per-sample timestamp

### 7.3 Missing data

- When writing retroactive or live data, preserve elapsed time.
- Replace every expected-but-missing sample caused by a detected
  discontinuity with a 24-bit signed zero sample.
- Large gaps therefore produce the required number of zero samples rather
  than shortening the file.
- Write and flush data incrementally rather than waiting for recording to
  finish.
- Safely finalize the file on recording stop, ECG-mode stop, controlled
  navigation away, or normal app/process shutdown where Android permits it.

## 8. Paused and Stopped Plot Inspection

Chart gestures are enabled only while plotting is paused or acquisition is
stopped.

- A two-finger pinch zooms/unzooms the X-axis.
- A single-finger left/right drag pans horizontally through retained data.
- Gestures affect only the viewport, not the acquisition stream, buffer, or
  recording.
- Live plotting ignores these gestures.
- Panning is bounded by the data currently retained in the circular buffer.
- Rotation must preserve pause state, stop state, zoom, and the selected
  viewport where practical.

## 9. Orientation and Layout

- The ECG screen automatically follows portrait and landscape phone
  orientation.
- Portrait and landscape layouts share the same ECG session, acquisition
  stream, circular buffer, and recording state.
- The chart receives the dominant screen area in both orientations.
- In landscape, maximize chart width and height while keeping Pause/Resume,
  Stop, Record, and heart rate accessible.
- Orientation changes must not restart acquisition, create duplicate stream
  collectors, or interrupt recording.

## 10. Foreground Service and Notifications

- Extend the existing foreground-service architecture or provide an equivalent
  ECG-specific lifecycle owner.
- While ECG acquisition is active in the background, show an ongoing
  notification identifying active ECG streaming.
- The notification should distinguish whether recording is active.
- The notification must return the user to the ECG screen.
- The service owns background continuity; the UI observes data and renders it
  only while foregrounded.

## 11. State Model

| State | ECG acquisition | Recording | Plot rendering | Pan/zoom |
|---|---:|---:|---:|---:|
| Live | Running | As toggled | Realtime | Disabled |
| Paused | Running | As toggled | Frozen | Enabled |
| Stopped | Stopped | Finalized/stopped | Latest retained data | Enabled |

Background and phone-lock behavior is independent of the plot state:
acquisition and active recording continue, while rendering remains stopped.

## 12. Error Handling

- Surface ECG stream start failures and disconnects in the ECG UI.
- Continue advancing the plot's time axis after a data interruption, without
  drawing line segments through missing time.
- Surface recording creation/write/finalization failures to the user.
- A recording failure must not silently leave the user believing a valid file
  is still being written.
- Recovery and resubscription must create a discontinuity boundary rather than
  concatenate pre-disconnect and post-reconnect samples as continuous data.

## 13. Acceptance Criteria

- Pressing **ECG** opens a dedicated ECG screen.
- ECG-mode processing does not execute the HRV metrics pipeline.
- Raw ECG is consumed at 130 Hz and the displayed HR comes from the H10's
  native HR stream.
- The blue waveform, grid, and large red HR presentation resemble the supplied
  reference.
- Live scrolling advances in heartbeat-sized steps after approximately 80%
  of the plot width is occupied.
- Stream interruptions produce visible gaps and preserve elapsed-time
  alignment.
- Pause freezes plotting but not acquisition or recording.
- Stop ends acquisition and preserves buffered data for inspection.
- Pinch zoom and horizontal pan work while paused or stopped.
- The 60-second circular buffer requires no per-sample timestamp and correctly
  survives wraparound and discontinuities.
- Starting recording writes the current buffer before live samples.
- Stopping recording does not stop acquisition or plotting.
- A later recording starts a new file and includes the then-current buffer.
- Files use the specified app-specific external-storage path, name, timestamp,
  24-bit sample encoding, and zero-filled gaps.
- Acquisition and active recording continue in the background/lock state.
- Returning to the foreground shows the latest data without replay lag.
- Portrait/landscape rotation preserves state and does not duplicate Polar
  subscriptions.

## 14. Staged Implementation and Test Plan

Implement and validate the feature in the following order. Each stage should
be merged or checkpointed only after its stage-specific tests pass, so later
work does not depend on an unreliable acquisition or persistence foundation.

### Stage 1 — Core ECG models and timing primitives

Implement the ECG-only domain types and pure-Kotlin time/indexing logic:

- ECG stream state and error state
- plot state: Live, Paused, Stopped
- recording state: Idle, Recording, Error
- configurable ECG buffer duration and 130 Hz sample-rate constants
- continuity segment and discontinuity metadata
- sample-index-to-timestamp and timestamp-to-index calculations

Tests:

- nominal timestamp progression at 130 Hz
- sample timing before and after a discontinuity
- large time gaps
- configuration boundaries and invalid/zero durations
- 24-bit signed value range and encoding boundaries

### Stage 2 — Circular ECG buffer

Implement a fixed-capacity circular buffer without per-sample timestamps:

- efficient append and wraparound
- compact segment metadata
- oldest/latest sample access
- immutable viewport snapshots
- retention of discontinuities intersecting buffered data
- eviction behavior when old segments or gaps leave the window

Tests:

- buffer sizes below, equal to, and above 60 seconds
- append/read correctness across multiple wraparounds
- timestamp lookup at first, middle, last, and segment-boundary samples
- gap separation and no synthetic samples for missing intervals
- memory/structure checks proving timestamps are not stored per sample

### Stage 3 — Polar ECG acquisition owner

Add the ECG-session owner independently of existing `HrvProcessor` usage:

- obtain one 130 Hz ECG stream from the Polar data source
- obtain H10-native HR separately
- feed incoming samples into the circular buffer
- detect packet/sample discontinuities
- publish throttled snapshots suitable for Compose
- stop and restart without joining disconnected periods
- expose explicit acquisition and stream-health errors

Tests:

- fake `HrDataSource` streams deliver the expected state and samples
- no call path reaches `HrvProcessor` from ECG mode
- H10 HR values are passed through unchanged
- stream completion/error enters the correct error state
- repeated start calls do not create duplicate collectors
- reconnect/restart creates a discontinuity

### Stage 4 — Recording encoder and file writer

Implement the ECG binary writer before connecting it to the foreground
screen:

- create app-specific external `ecg_data` directory
- generate `yyyymmdd-hhmmss.bin`
- write 8-byte little-endian initial timestamp
- write 3-byte little-endian signed samples
- zero-fill known missing sample slots
- copy buffered history before live appends
- flush incrementally and finalize safely
- report file creation/write/finalization errors explicitly

Tests:

- verify complete binary layout byte-for-byte
- verify positive and negative 24-bit sign encoding
- verify initial timestamp equals the oldest written sample
- verify retroactive buffer is written in chronological order
- verify live append follows retroactive output without duplication
- verify zero-fill count for packet gaps
- verify stop/start Record creates a new file
- verify failures produce Recording/Error state and an incomplete-recording
  cannot appear successful

### Stage 5 — Foreground service and lifecycle ownership

Move acquisition and recording ownership behind a lifecycle-safe foreground
service/session coordinator:

- ECG-specific notification and return-to-screen action
- stream, record, pause, and stop commands
- background/lock continuation
- foreground observer for plot rendering only
- service shutdown/finalization behavior
- restore UI state after process/activity recreation where Android permits

Tests:

- acquisition continues after `onStop`
- rendering updates cease in the background
- foreground return publishes the latest buffered data
- recording continues in background
- stop finalizes recording and terminates collection
- configuration/rotation changes do not duplicate streams

Device checks:

- phone lock/unlock
- app background/foreground
- Android notification behavior
- battery-saving/background restriction behavior on the target test phone

### Stage 6 — ECG chart renderer

Add a performant ECG chart over immutable buffer snapshots:

- light grid and bright-blue line
- red H10 HR overlay
- disconnected segments rendered as separate paths
- blank time interval during stream loss
- left-to-right initial fill
- approximately 80% live threshold
- heartbeat-sized left-scroll increments
- viewport scaling and coordinate conversion
- throttled rendering at a stable frame rate

Tests:

- x-coordinate timing is stable across discontinuities
- no connecting path across gaps
- first samples begin on the left and latest samples approach the right
- shift occurs only after the selected 80% threshold
- one scroll step consumes one heartbeat interval
- no waveform line is rendered for missing stream time
- HR overlay displays the native HR without ECG analysis

### Stage 7 — Pause, stop, zoom, and pan

Connect chart interaction state:

- Pause/Resume changes plotting only
- Resume jumps to latest samples
- Stop freezes acquisition and leaves retained data inspectable
- gestures activate only while paused or stopped
- horizontal X-axis pan is bounded by buffer history
- two-finger pinch changes X-axis scale only
- preserve viewport/zoom through rotation

Tests:

- acquisition and recording counters continue during pause
- paused chart snapshot remains unchanged
- resume selects the latest data
- Stop does not erase the buffer
- pan cannot exceed retained bounds
- pinch enforces defined minimum/maximum time windows
- drag/pinch are ignored in live mode

### Stage 8 — Home navigation and ECG screen layouts

Wire the feature into the application UI:

- add the ECG route and screen
- reorganize home cards into:
  - Find RF | Live Metrics
  - Full Report | ECG
- implement the 9:18 portrait design with controls below the plot
- implement the 18:9 landscape design with controls on the left
- preserve informational text at the bottom
- enable automatic orientation handling
- add connection-required and stream-error UI
- return to the ECG screen from its foreground notification

Tests:

- home action labels and ordering match the documented mockups
- ECG route opens only from the ECG button
- portrait control placement matches the portrait mockup
- landscape controls move to the left
- rotation preserves session state and recording
- error UI distinguishes sensor, stream, and recording failures

### Stage 9 — End-to-end integration and physical-device validation

Run the complete feature against a real Polar H10:

- sustained 130 Hz ECG acquisition
- native 1 Hz HR display
- no HRV/DSP pipeline execution
- heartbeat scrolling
- 60-second rollover
- simulated and natural packet loss
- record-on/record-off/new-record cycles
- backgrounding and lock/unlock
- portrait/landscape transitions
- chart pause, resume, zoom, and pan

File validation:

- export or access the generated test files using Android tooling
- verify directory, filename, header timestamp, 3-byte samples, and zero gaps
- compare known generated sample/gap sequences with decoder output
- confirm file duration corresponds to the acquired timeline

Performance validation:

- sustained streaming for at least 15 minutes without UI jank
- no unbounded memory growth
- no duplicate sample collectors after repeated rotations
- recording does not cause dropped plot or acquisition samples
- foreground service remains active through lock/background transitions

### Stage 10 — Final hardening and release checks

- run targeted unit/integration tests from all stages
- run the existing regression test suite
- build debug and benchmark/release variants
- inspect Android permissions and notification requirements
- verify no unrelated session export/storage behavior regressed
- remove temporary diagnostics and dead experimental code
- update project documentation if implementation choices refine this
  specification

## 15. Implementation Dependencies

- Stages 1-4 are prerequisites for the end-to-end recording feature.
- Stage 5 depends on a working acquisition and recording coordinator.
- Stage 6 depends on buffer snapshots and discontinuity metadata.
- Stage 7 depends on the chart viewport abstraction from Stage 6.
- Stage 8 can begin with presentational scaffolding, but final wiring depends on
  Stages 3, 5, and 7.
- Stage 9 must use a real Polar H10; fake streams can validate protocol logic
  but cannot validate sustained BLE behavior.
