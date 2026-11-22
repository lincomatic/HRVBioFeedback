# HRVBioFeedback Project Structure

## Overview

HRVBioFeedback is a single-module Android application for Heart Rate Variability biofeedback and resonance-frequency training with a Polar H10 sensor.

- **Language:** Kotlin
- **UI:** Jetpack Compose with Material3
- **Architecture:** MVVM plus layered/repository patterns
- **Dependency injection:** Hilt
- **Persistence:** Room
- **Device integration:** Polar BLE SDK 8.6.0
- **Build:** Android Gradle Plugin 9.5.2, Kotlin 2.3.0, Gradle 9.1
- **Minimum SDK:** 26
- **Compile/target SDK:** 36
- **Java target:** 17

## Module Layout

The project contains one Gradle module:

- `app` - Android application, UI, domain logic, data access, BLE integration, and tests
- `gradle/wrapper` - Gradle wrapper configuration

The root build files define the application plugins and repositories. JitPack is configured for the Polar BLE SDK.

## Main Source Areas

### Application entry points

- `app/src/main/java/com/hrv/biofeedback/app/HrvApp.kt`
  - Hilt application initialization
- `app/src/main/java/com/hrv/biofeedback/presentation/MainActivity.kt`
  - Compose host and main activity
- `app/src/main/AndroidManifest.xml`
  - Activity, foreground service, BLE/location permissions, and file provider

### Presentation

The `presentation` package contains Compose screens, ViewModels, navigation, shared UI components, and the app theme.

Main feature packages:

- `assessment` - resonance-frequency assessment
- `device` - Polar device discovery and connection
- `evaluation` - full evaluation reports and norm comparisons
- `freetraining` - advanced freeform training
- `history` - historical sessions
- `home` - application landing screen
- `live` - real-time metric display
- `morning` - five-minute morning baseline check
- `navigation` - navigation graph and routes
- `report` - individual session reports
- `settings` - user profile and app preferences
- `training` - guided resonance-frequency training
- `common` - breathing pacer, charts, overlays, indicators, and cards
- `theme` - Material3 colors and theme

`NavGraph.kt` is the central navigation table and starts at the home route.

### Domain

The `domain` package contains UI-independent models and business logic:

- `model` - BLE devices, connection state, HR/ECG samples, HRV metrics, breathing phases, session types, and assessment results
- `repository` - abstractions for HR data sources and persisted sessions
- `dsp` - artifact detection, interpolation, spectral analysis, HRV metrics, nonlinear analysis, respiratory extraction, coherence, phase analysis, signal quality, and processor orchestration
- `usecase/training` - breathing coaching
- `usecase/analysis` - HRV norms
- `usecase/export` - session export

`HrvProcessor` is the central DSP orchestrator. It accepts RR intervals and concurrently receives ECG and accelerometer samples, applies the processing pipeline, and exposes live `HrvMetrics` and derived signal state.

### Data

The `data` package contains external-system implementations:

- `ble/polar/PolarHrSource.kt` - Polar H10 scanning, connection, HR/RR, ECG, and accelerometer streams
- `ble/SessionForegroundService.kt` - keeps active sessions running in the background
- `local/db` - Room entities, DAOs, and database definition
- `local/preferences` - DataStore-backed user preferences
- `repository/SessionRepositoryImpl.kt` - Room-backed implementation of session persistence

The Room database is named `hrv_biofeedback.db`, uses schema version 3, and stores sessions, RR intervals, metrics snapshots, and RF assessment steps.

## Dependency Injection

Hilt modules are under `di`:

- `AppModule` - binds `PolarHrSource` to `HrDataSource`
- `DatabaseModule` - builds Room and provides DAOs
- `RepositoryModule` - binds `SessionRepositoryImpl` to `SessionRepository`
- `DspModule` - constructs `HrvProcessor` and wires its analyzers

## Core Data Flow

1. Compose screens expose actions and observe ViewModel state.
2. ViewModels call domain repository interfaces and combine sensor, session, and preference state.
3. `PolarHrSource` exposes HR/RR, ECG, and accelerometer data as Kotlin flows.
4. Feature ViewModels feed HR and biosensor samples into `HrvProcessor`.
5. `HrvProcessor` calculates live time-domain, frequency-domain, nonlinear, respiratory, and quality metrics.
6. `SessionRepositoryImpl` persists sessions, RR intervals, metrics snapshots, and RF assessment steps through Room.
7. History and report screens load persisted `SessionDetail` objects for review and export.

## DSP Pipeline

The processing system uses:

- RR artifact detection and correction
- Cubic-spline resampling at 4 Hz
- Autoregressive Burg spectral analysis
- LF/HF power and peak analysis
- Peak-to-trough amplitude and phase analysis
- Poincare metrics, DFA alpha1, and sample entropy
- ECG R-peak detection and ECG-derived respiration
- Accelerometer-based respiratory signal extraction
- Cross-spectral coherence and respiratory coupling
- Signal-quality and artifact monitoring

Most metrics are intended to update from the current sliding session window, while morning checks support a definitive full-recording analysis.

## Persistence Model

Room entities represent:

- Training, assessment, and morning-check sessions
- Raw RR intervals
- Saved metrics timeline snapshots
- Individual resonance-frequency assessment steps

The repository saves every fifth metrics snapshot to limit database growth while preserving raw RR interval data and session-level aggregates.

## Tests

`app/src/test/java/com/hrv/biofeedback/domain/dsp/SyntheticValidatorTest.kt` provides synthetic unit validation for the DSP modules.

The README describes the suite as 26 validation tests covering signal frequency, amplitude, artifact correction, nonlinear metric identities, edge cases, and numerical stability.

## Sensor Stream Reliability TODO

### Analysis

The Polar H10 connection can remain active while the application data display appears to freeze or stop updating. Current code has several throughput and lifecycle risks:

- ViewModels launch HR, ECG, and accelerometer collectors in `viewModelScope`, whose default dispatcher is the main thread.
- Every HR packet calls the full `HrvProcessor.processRrInterval()` pipeline synchronously. After enough data is collected, this includes resampling, spectral analysis, nonlinear metrics, respiratory extraction, and cross-spectral coherence.
- ACC data may arrive at up to 200 Hz. Once the accelerometer buffer is full, each sample removes the first item from a 12,000-item `ArrayList`, repeatedly shifting the remaining items.
- Training screens copy and publish the complete breathing waveform for every ACC sample, adding allocation and UI-state work at sensor frequency.
- ECG ingestion periodically performs R-peak detection across a substantial buffer.
- Most stream failures use silent `.catch { }` handlers and do not expose state, log the failure, restart the stream, or reconnect.
- `SessionForegroundService` exists, but no active-session code currently starts it.
- A short debugger observation confirmed Polar delivered one RR interval in an observed HR packet, but did not run long enough to measure sustained packet loss. The synchronous main-thread workload remains capable of blocking packet processing and UI updates.

### Prioritized TODO

- [ ] **Move sensor processing off the main thread.** Collect and process raw sensor streams on `Dispatchers.Default`, while publishing only final state changes on the UI-visible flows.
- [ ] **Preserve every incoming sample.** Stream collection, RR timestamps, artifact decisions, and raw RR persistence must remain lossless before any throttling is introduced.
- [ ] **Add sustained stream-cadence instrumentation.** Track HR packet timestamps, packet gaps, RR count, processing duration, and the expected-versus-observed sample count during a long session.
- [ ] **Throttle expensive derived calculations.**
  - Keep artifact detection, raw RR storage, and inexpensive time-domain updates per heartbeat.
  - Recalculate spectral and respiratory metrics at roughly 1 Hz rather than once for every duplicate RR in an incoming packet.
  - Recalculate nonlinear metrics less frequently, initially every 5 seconds.
  - Continue publishing a regular metric timeline while retaining the latest calculated values between expensive recomputations.
  - Run uncached definitive analysis from the complete RR series at session completion.
  - Keep final RF assessment step analysis uncached and based on the complete step data.
- [ ] **Replace mutable-list shifting in the sensor buffers.** Use fixed-size ring buffers for ACC and ECG data, avoiding repeated `removeAt(0)` operations on large lists.
- [ ] **Downsample ACC data before UI publication.** Publish only the reduced waveform needed by the chart, rather than copying and emitting a full list at 200 Hz.
- [ ] **Reduce ECG work on the ingestion path.** Queue recent ECG samples and perform R-peak detection at a controlled cadence on a background dispatcher.
- [ ] **Add watchdog and recovery behavior.** Detect stale HR/ACC/ECG streams independently, log failures, expose stream health, and restart the affected Polar stream without discarding the existing device connection.
- [ ] **Replace silent stream-error handlers.** Record and surface errors in a consistent state rather than swallowing exceptions in `.catch { }`.
- [ ] **Start and stop the foreground service with active sessions.** Tie `SessionForegroundService` to session lifecycle so Android keeps BLE collection active while the screen is off or the app is backgrounded.
- [ ] **Validate output equivalence after throttling.** Compare live throttled metrics against full per-beat recomputation and verify that final definitive results are unchanged.
- [ ] **Add stream reliability tests.** Exercise a simulated 1 Hz HR stream alongside 130 Hz ECG and 200 Hz ACC input, asserting no dropped RR samples, bounded processing latency, and recovery after a stream failure.

### Throttling Correctness Constraint

Throttling is safe only when it limits the frequency of **derived calculations**. It must not discard, skip, merge, or delay storage of sensor samples. The expected behavior is:

1. Ingest every HR/RR, ECG, and ACC sample.
2. Store every RR interval and its timestamp.
3. Update inexpensive metrics immediately.
4. Refresh expensive derived metrics on a controlled schedule.
5. Reuse the latest derived values between refreshes.
6. Compute final session metrics from all collected RR intervals.
