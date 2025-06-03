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
This is a word puzzle game where players unscramble letters based on image hints. The architecture follows a traditional Android pattern:

### Activity Flow
- **DownloadActivity**: First-run asset download from `https://unjumble.au/files/pictures.tar.gz`
- **MainActivity**: Core gameplay with image display, letter buttons, scoring, and timer
- **EndGameActivity**: Game completion screen with statistics and reset functionality

### Key Data Management
- **SharedPreferences**: Game state persistence (score, level, currentPictureIndex, totalPlayTimeMillis)
- **File Storage**: Pictures stored in app's internal storage after extraction
- **State Flow**: DownloadActivity → MainActivity ⇄ EndGameActivity

### Game Logic Structure
- Picture order shuffled once and persisted across sessions
- Scoring: +1 for correct placement, -1 for hints/checks/incorrect words
- Auto-progression through 4364 levels with visual feedback (green/red flashing)
- Timer tracks total play time across all sessions

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