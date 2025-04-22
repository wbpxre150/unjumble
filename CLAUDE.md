# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build/Test Commands
- `./gradlew build` - Build the entire project
- `./gradlew test` - Run unit tests
- `./gradlew androidTest` - Run instrumented tests
- `./gradlew lint` - Run Android lint checks
- `./gradlew :app:testDebugUnitTest --tests "com.wbpxre150.unjumbleapp.ExampleUnitTest"` - Run a single test

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