# Pocket APK Studio

Pocket APK Studio is designed to prepare and build Android projects locally on an Android phone.

## Version 1.1 — Native Android build bridge groundwork
- Dark dashboard and ZIP/folder import.
- ZIP project inspection for Gradle and Flutter markers.
- Copies a selected Android Gradle ZIP to shared Downloads.
- Can hand off a Gradle build command to Termux:RunCommand and Ubuntu PRoot.
- Does not claim a build succeeded: check the Gradle output in Termux for `BUILD SUCCESS`.

## Local Android build prerequisites
This version uses the user's local Termux + Ubuntu environment as the compiler host. It is not yet a self-contained compiler bundled inside the APK.

1. Install Termux and the matching Termux:RunCommand add-on from the same trusted source.
2. In Termux, run `termux-setup-storage` and grant storage access.
3. In Termux `~/.termux/termux.properties`, set `allow-external-apps = true`, then restart Termux.
4. Install `proot-distro` in Termux and install/login to Ubuntu.
5. Inside Ubuntu, install Java 17, Gradle 8.9, `wget`, `unzip`, and the Android SDK command-line tools.
6. Install Android Platform 35 and Platform Tools using `sdkmanager`.
7. Use ARM64-compatible Android Build Tools 34.0.4 in `$ANDROID_HOME/build-tools/34.0.4`. The standard Linux SDK Build Tools downloaded by `sdkmanager` are x86-64 binaries and cannot run directly in an ARM64-only Ubuntu environment.
8. Set `ANDROID_HOME` in the Ubuntu login shell and accept Android SDK licences.

The app project itself is configured for compile SDK 35, Android Gradle Plugin 8.7.3, Java 17 and Build Tools 34.0.4. Gradle 8.9 is required by this Android Gradle Plugin version.

The bridge expects the Ubuntu distribution name `ubuntu`, the command `proot-distro`, and the ZIP at `/sdcard/Download/PocketAPKStudio/`. The Android app's Build button starts the build command, but cannot currently stream terminal logs back into the app.

## Not yet implemented
- A compiler bundled fully inside the APK.
- In-app build log streaming, APK discovery/install/share.
- Recursive folder import/build.
- Flutter SDK/Dart local build support.
- Automatic SDK download and setup.

Never display a successful build state until a real APK has been produced and verified.
