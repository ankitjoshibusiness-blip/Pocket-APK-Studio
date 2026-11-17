# Pocket APK Studio

Pocket APK Studio is an Android-on-device APK builder project.

## Current status — Version 1.0 starter
- Dark dashboard UI.
- Import a project ZIP through Android's file picker.
- Select a project folder.
- Basic ZIP inspection for Android Gradle and Flutter project markers.
- Build action clearly reports that the local compilation engine is not yet integrated.

## Planned implementation
1. Native Android Java/Kotlin local build engine.
2. Flutter SDK and local Flutter build support.
3. SDK/Gradle setup manager, build logs, APK output and install/share actions.

## Important
This starter app does **not** compile APKs yet. A real local build engine still needs Android SDK platform/build-tools, Gradle, and (for Flutter projects) Flutter SDK/Dart. These components are large and need to be installed and tested on the target ARM64 phone. The UI must not report a successful APK build until a real APK file has been produced and verified.
