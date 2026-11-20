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
