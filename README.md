# AudioTracer

## Technical Stack

- **UI:** Jetpack Compose (Material 3), single-activity architecture
- **Dependency injection:** Hilt
- **Data/state holder:** ViewModel + StateFlow
- **JDK:** 17
- **Build:** Gradle 8.1.1

## Features

- Recording control: Start, Pause/Resume, Stop buttons
- Status display: Real-time “Recording”, “Paused”, or “Stopped”
- Storage info: Shows free storage (bytes & HH:MM of audio left at 128 kbps), updates every 10 seconds

## How to Build

1. Ensure JDK 17 is installed and set as the project JDK.
2. Run:
   ```
   ./gradlew clean && ./gradlew :app:assemble --info
   ```
3. Open in Android Studio (Giraffe or newer recommended).

## Notes

- This is a Compose-only UI scaffold. Actual audio recording logic is not implemented.
- All dependencies are managed via Gradle Version Catalog (`libs.versions.toml`).
- Hilt is used for dependency injection and ViewModel lifecycle. 