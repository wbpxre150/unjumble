# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build/Test Commands
- `./gradlew build` - Build the entire project
- `./gradlew assembleRelease` - Build release APK with keystore signing
- `./copy_apk.sh` - Copy built APK to downloads for installation
- `./gradlew test` - Run unit tests
- `./gradlew androidTest` - Run instrumented tests
- `./gradlew lint` - Run Android lint checks
- `./gradlew :app:testDebugUnitTest --tests "com.wbpxre150.unjumbleapp.ExampleUnitTest"` - Run a single test

## App Architecture
This is a word puzzle game where players unscramble letters based on image hints. The architecture follows a traditional Android pattern with P2P torrent-based asset delivery:

### Activity Flow
- **SplashActivity**: Initial app launch and routing logic
- **DownloadActivity**: Asset download using hybrid P2P/HTTP approach via jlibtorrent
- **MainActivity**: Core gameplay with image display, letter buttons, scoring, and timer
- **EndGameActivity**: Game completion screen with statistics and reset functionality

### Key Data Management
- **SharedPreferences**: Game state persistence (score, level, currentPictureIndex, totalPlayTimeMillis)
- **File Storage**: Pictures stored in app's internal storage after extraction
- **State Flow**: SplashActivity → DownloadActivity → MainActivity ⇄ EndGameActivity

### Game Logic Structure
- Picture order shuffled once and persisted across sessions
- Scoring: +1 for correct placement, -1 for hints/checks/incorrect words
- Auto-progression through 4364 levels with visual feedback (green/red flashing)
- Timer tracks total play time across all sessions

### Torrent Download Architecture
- **TorrentManager**: Singleton managing jlibtorrent sessions, metadata fetching, and P2P downloads
- **NetworkManager**: Monitors network connectivity and manages WiFi/mobile data transitions
- **TorrentDownloadListener**: Interface for torrent lifecycle callbacks with phase-based communication
- **Download Phases**: METADATA_FETCHING → PEER_DISCOVERY → ACTIVE_DOWNLOADING → VERIFICATION
- **Fallback Strategy**: P2P download attempts first, falls back to HTTP if needed

## Dependencies & Libraries
- **jlibtorrent 1.2.19.0**: P2P BitTorrent protocol implementation for asset downloads
- **Local JAR files**: `app/libs/jlibtorrent-1.2.19.0.jar` and `jlibtorrent-android-arm64-1.2.19.0.jar`
- **Apache Commons Compress**: For tar.gz file extraction
- **Kotlin Coroutines**: Asynchronous operations and background processing
- **AndroidX Compose**: Modern UI toolkit (partially implemented)

## Keystore Configuration
- Release signing uses `keystore.properties` file (git-ignored)
- Contains: storeFile, storePassword, keyAlias, keyPassword
- Keystore files (*.key, *.jks, *.keystore) are excluded from git

## Code Style Guidelines
- **Classes**: PascalCase (MainActivity, DownloadActivity)
- **Functions/Variables**: camelCase (loadCurrentPicture, pictureFiles)
- **Constants**: Regular variables with lateinit when appropriate
- **Imports**: Group Android/Java imports before Kotlin imports
- **Logging**: Use Android's Log utility with class name as tag
- **UI Components**: Named descriptively (letterContainer, nextWordButton)
- **Error Handling**: Check for null/empty values, use early returns with clear error messages
- **Documentation**: Minimal comments, self-documenting code preferred
- **Formatting**: 4-space indentation, line breaks after 100 characters