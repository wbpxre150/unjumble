# Unjumble

A word puzzle game where players unscramble letters to form words based on image hints. Challenge yourself through 4,364 unique levels with pictures that provide clues to help you solve each anagram.

## 🎮 Features

- **4,364 Unique Levels**: Extensive word collection with diverse vocabulary
- **Image Hints**: Visual clues help guide you to the correct word
- **Scoring System**: Earn points for correct answers, lose points for hints and mistakes
- **Progress Tracking**: Game saves your progress automatically
- **Timer System**: Track your total play time across all sessions
- **Offline Gameplay**: Play without internet connection once assets are downloaded
- **P2P Downloads**: Efficient asset delivery using BitTorrent technology with HTTP fallback

## 🏗️ Architecture

### Game Flow
- **SplashActivity**: Initial app launch and routing
- **DownloadActivity**: Asset download using hybrid P2P/HTTP approach via jlibtorrent
- **MainActivity**: Core gameplay with image display, letter buttons, scoring, and timer
- **EndGameActivity**: Game completion screen with statistics and reset functionality

### Technical Features
- **Hybrid Download System**: P2P BitTorrent downloads with automatic HTTP fallback
- **Network-Aware**: Intelligent switching between WiFi and mobile data
- **State Persistence**: Game progress saved using SharedPreferences
- **Asset Management**: Pictures stored in app's internal storage after extraction

## 🔧 Technologies

- **Language**: Kotlin
- **UI Framework**: Android Views with partial Compose implementation
- **P2P Library**: jlibtorrent 1.2.19.0 for torrent-based asset downloads
- **Compression**: Apache Commons Compress for tar.gz extraction
- **Concurrency**: Kotlin Coroutines for asynchronous operations
- **Build System**: Gradle with Kotlin DSL

## 📱 Requirements

- **Android**: API 24+ (Android 7.0)
- **Storage**: ~500MB for game assets
- **Network**: WiFi or mobile data for initial download

## 🏗️ Building

### Prerequisites
- Android Studio or compatible IDE
- JDK 17+
- Android SDK with API 34

### Commands
```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (requires keystore configuration)
./gradlew assembleRelease

# Copy APK to downloads
./copy_apk.sh

# Run tests
./gradlew test
./gradlew androidTest

# Code quality
./gradlew lint
```

### Keystore Configuration
For release builds, create `keystore.properties` in the root directory:
```properties
storeFile=path/to/your/keystore
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

## 🎯 How to Play

1. **Download Assets**: First launch downloads game content via P2P/HTTP
2. **View Image**: Each level shows a picture as a hint
3. **Unscramble Letters**: Tap letter buttons to form the correct word
4. **Score Points**: +1 for correct placement, -1 for hints/checks/incorrect words
5. **Progress**: Automatically advances through levels with visual feedback

## 🔄 Recent Updates

### Version 8.0 (Latest)
- **Comprehensive P2P Optimizations**: FrostWire-level performance with 70-90% speed improvements
- **Enhanced Connection Scaling**: WiFi 800 connections (4x increase), mobile 300 connections
- **Advanced Download Resume**: Fixed partial download functionality with SharedPreferences fallback
- **Multi-threaded Monitoring**: DHT, tracker, and peer discovery with enhanced reliability
- **Network-Aware Intelligence**: Dynamic WiFi/mobile data switching with optimized settings
- **Session State Persistence**: Maintains download progress across app restarts with metadata caching
- **Enhanced Seeding**: Proper torrent seeding implementation with background metadata fetching

### Previous Versions
- **Version 6.0**: Network switching fixes and download protection
- **Version 2.0**: P2P download improvements and robustness enhancements
- **Version 1.0**: Initial release with basic gameplay

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run tests and lint checks
5. Submit a pull request

## 📄 License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

## 🔗 Links

- **Play Online**: https://unjumble.au
- **Source Code**: https://github.com/wbpxre150/unjumble
- **Issues**: https://github.com/wbpxre150/unjumble/issues
- **Releases**: https://github.com/wbpxre150/unjumble/releases
- **Author**: Adam Bullock (wbpxre150@outlook.com)

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/com.wbpxre150.unjumbleapp/)