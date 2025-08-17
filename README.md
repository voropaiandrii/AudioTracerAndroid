# AudioTracer

## Technical Stack

- **UI:** Jetpack Compose (Material 3), single-activity architecture
- **Dependency injection:** Hilt
- **Data/state holder:** ViewModel + StateFlow
- **JDK:** 17
- **Build:** Gradle 8.1.1

## Features

- **Dual Recording Modes:**
  - **Manual Recording (OnDemand):** Traditional start/stop/pause/resume controls
  - **Automatic Recording:** Voice Activity Detection (VAD) with automatic start/stop
- Recording control: Start, Pause/Resume, Stop buttons for manual mode
- Status display: Real-time "Recording", "Paused", "Listening for voice...", or "Auto Recording"
- Storage info: Shows free storage (bytes & HH:MM of audio left at 16 kbps), updates every 10 seconds
- **Seamless file rolling**: Automatic file splitting to avoid 2-4 GB container limits
  - 50 MB per file (configurable)
  - 1 hour per file (configurable) 
  - Near-zero gap recording on API 26+ using `setNextOutputFile`
  - Fallback support for older APIs
  - **Smart file naming**: 
    - Manual recordings: `timestamp_pN_OM.m4a` (OnDemand Mode)
    - Automatic recordings: `timestamp_pN_AM.m4a` (Automatic Mode)

## File Naming Convention

The app now uses intelligent file naming to distinguish between recording modes:

### Manual Recording (OnDemand Mode)
- **Format:** `YYYY-MM-DD-HH-mm-SS_pN_OM.m4a`
- **Example:** `2024-01-15-14-30-25_p0_OM.m4a`
- **Suffix:** `_OM` = OnDemand Mode

### Automatic Recording (Voice Detection Mode)
- **Format:** `YYYY-MM-DD-HH-mm-SS_pN_AM.m4a`
- **Example:** `2024-01-15-14-30-25_p0_AM.m4a`
- **Suffix:** `_AM` = Automatic Mode

### File Rolling
- When files exceed 50MB, they automatically roll to the next part
- **Example:** `2024-01-15-14-30-25_p0_OM.m4a` → `2024-01-15-14-30-25_p1_OM.m4a`

## How to Build

1. Ensure JDK 17 is installed and set as the project JDK.
2. Run:
   ```
   ./gradlew clean && ./gradlew :app:assemble --info
   ```
3. Open in Android Studio (Giraffe or newer recommended).

## Notes

- This is a Compose-only UI scaffold;
- All dependencies are managed via Gradle Version Catalog (`libs.versions.toml`);
- Hilt is used for dependency injection and ViewModel lifecycle.
- **New in this version:** Voice Activity Detection (VAD) for automatic recording
- **New in this version:** Smart file naming system for easy identification of recording types