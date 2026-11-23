# Pocket APK Studio

Pocket APK Studio is an Android-phone workspace for importing, editing, previewing and building Android Gradle projects through a local Termux + Ubuntu toolchain.

## Current build pipeline

- Import an Android project from a ZIP or a folder selected through Android's Storage Access Framework. Folder imports are packaged into a ZIP while excluding generated folders such as `.gradle`, `build`, `.git`, `node_modules` and `.idea`.
- Edit the imported project in the in-app editor.
- Build debug APKs locally through Termux:RunCommand and Ubuntu PRoot.
- Build output is written to `Downloads/PocketAPKStudio/build-live.log` and displayed in the Workspace Build Logs panel while the command runs. The final Termux callback remains the authoritative result and includes stdout/stderr.
- A successful state is shown only when Gradle exits successfully and a non-empty APK is verified at the reported path.

- The phone-side build runner is maintained as the standalone asset `app/src/main/assets/build-project.sh`. Pocket APK Studio copies it beside the imported ZIP in shared Downloads and invokes the same script first in Termux, then inside Ubuntu PRoot. The user's imported ZIP is kept unchanged; the script builds from an extracted working copy.
- The primary APK output is reserved through an app-owned Downloads MediaStore entry before Termux starts. The result callback copies that APK into app-private storage, so install/share/preview do not rely on reading a file created by another app through a raw shared-storage path.
- GitHub Actions runs `bash -n` and the runner's `--self-test` mode before compiling Pocket APK Studio. These checks cover safe ZIP paths, ZIP integrity, nested Gradle roots, spaces in paths, Java/Kotlin 17 overrides, AAPT2 checksum verification, missing tools and debug APK discovery. They do not replace a real phone-side build test.

## Local build prerequisites

This is not a compiler bundled inside the APK. The phone must have the local build environment:

1. Install Termux and the matching Termux:RunCommand add-on from the same trusted source.
2. In Termux, run `termux-setup-storage` and grant shared-storage access.
3. In `~/.termux/termux.properties`, set `allow-external-apps = true`, then restart Termux.
4. Install `proot-distro` in Termux and install/login to the Ubuntu distribution named `ubuntu`.
5. Inside Ubuntu, install Java 17, `curl`, `wget`, `unzip`, `zip`, and Gradle 8.9 (unless the imported project includes `gradlew`).
6. Install Android SDK command-line tools, Android Platform 35, Platform Tools and Build Tools 35.0.0; accept SDK licences.
7. The local build bridge downloads and verifies an ARM64-compatible `aapt2` binary and configures Gradle's `android.aapt2FromMavenOverride` for it. The standard SDK Build Tools package is still installed for the other SDK utilities.
8. Keep `ANDROID_HOME=/root/Android/Sdk` available inside Ubuntu.

The Pocket APK Studio app itself is configured for compile SDK 35, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Java 17 and Gradle 8.9. GitHub Actions uses the same Android Platform 35 / Build Tools 35.0.0 versions for the app's CI build.

## Preview limitations

The in-pane **Source Preview** is an approximation; it does not execute imported app code. XML layouts and Compose source are mapped to a limited set of native controls and can differ from the real app. For an exact result, choose **Build & Run · Exact Device Preview**. Pocket APK Studio sends the project through the local Gradle build pipeline, then Android installs the produced APK. Tap **Run Exact Device Preview** to launch that installed app on the same phone. This is real Android runtime rendering on the device, not an embedded Android emulator. The app is not displayed inside the editor pane.

The preview is not a build validator: a rendered preview does not guarantee Kotlin/Java compilation, resource linking, manifest validation, dependency resolution, or Gradle task success. Those checks happen during the actual Gradle build and their errors are shown in Build Logs.

## Current limitations

- Flutter/Dart projects can be identified but cannot be built by the current local pipeline; Flutter SDK integration is not included.
- The APK build task currently runs `assembleDebug`; release signing and release APK generation are not configured.
- Some source-preview widgets and Android layout classes are approximated rather than executed.
- Build progress is based on live process output; it is not a precise Gradle task percentage.
