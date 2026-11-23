#!/usr/bin/env bash
# Pocket APK Studio local Android build engine.
# Runs in Termux by default, then re-enters itself inside Ubuntu PRoot.
# This is intentionally a standalone asset so Bash syntax and path handling can
# be tested independently of the Android/Kotlin wrapper.

set -Eeuo pipefail

pas_log() {
    printf '%s\n' "$*"
}

pas_fail() {
    local code="$1"
    shift
    printf 'ERROR: %s\n' "$*" >&2
    exit "$code"
}

pas_require_command() {
    command -v "$1" >/dev/null 2>&1
}

pas_zip_entry_is_safe() {
    local entry="$1"
    local part
    local -a parts=()

    [[ -n "$entry" ]] || return 0
    [[ "$entry" != /* ]] || return 1
    [[ ! "$entry" =~ ^[A-Za-z]: ]] || return 1
    [[ "$entry" != *$'\n'* && "$entry" != *$'\r'* ]] || return 1

    IFS='/' read -r -a parts <<< "$entry"
    for part in "${parts[@]}"; do
        [[ "$part" != ".." ]] || return 1
    done
    return 0
}

pas_validate_zip() {
    local zip_file="$1"
    local listing
    local entry

    [[ -f "$zip_file" && -s "$zip_file" ]] || return 1
    pas_require_command unzip || return 1
    unzip -tqq "$zip_file" >/dev/null 2>&1 || return 1
    listing="$(unzip -Z1 "$zip_file" 2>/dev/null)" || return 1
    [[ -n "$listing" ]] || return 1

    while IFS= read -r entry; do
        [[ -z "$entry" ]] && continue
        pas_zip_entry_is_safe "$entry" || return 1
    done <<< "$listing"
    return 0
}

# Select the shallowest settings file. A settings file at the extracted root
# always wins; otherwise prefer a candidate that has its own Gradle wrapper.
pas_find_gradle_root() {
    local project_dir="$1"
    local candidate
    local parent
    local relative
    local slash_only
    local depth
    local fallback_settings=""
    local fallback_root=""
    local fallback_depth=999999
    local wrapper_settings=""
    local wrapper_root=""
    local wrapper_depth=999999

    PAS_SETTINGS_FILE=""
    PAS_GRADLE_ROOT=""
    [[ -d "$project_dir" ]] || return 1

    while IFS= read -r -d '' candidate; do
        parent="$(dirname "$candidate")"
        relative="${parent#"$project_dir"}"
        relative="${relative#/}"
        slash_only="${relative//[^\/]/}"
        depth="${#slash_only}"
        if [[ -n "$relative" ]]; then
            depth=$((depth + 1))
        fi

        if [[ "$depth" -eq 0 ]]; then
            PAS_SETTINGS_FILE="$candidate"
            PAS_GRADLE_ROOT="$parent"
            return 0
        fi

        if [[ -f "$parent/gradlew" ]]; then
            if [[ -z "$wrapper_settings" ]] || (( depth < wrapper_depth )); then
                wrapper_settings="$candidate"
                wrapper_root="$parent"
                wrapper_depth="$depth"
            fi
        elif [[ -z "$fallback_settings" ]] || (( depth < fallback_depth )); then
            fallback_settings="$candidate"
            fallback_root="$parent"
            fallback_depth="$depth"
        fi
    done < <(
        find "$project_dir" \
            -type d \( -name .gradle -o -name build \) -prune -o \
            -type f \( -name settings.gradle -o -name settings.gradle.kts \) -print0 2>/dev/null
    )

    if [[ -n "$wrapper_settings" ]]; then
        PAS_SETTINGS_FILE="$wrapper_settings"
        PAS_GRADLE_ROOT="$wrapper_root"
        return 0
    fi
    if [[ -n "$fallback_settings" ]]; then
        PAS_SETTINGS_FILE="$fallback_settings"
        PAS_GRADLE_ROOT="$fallback_root"
        return 0
    fi
    return 1
}

# Append final overrides so an existing Java 8/Kotlin 1.8 setting does not win.
pas_align_gradle_targets() {
    local gradle_file="$1"

    [[ -f "$gradle_file" ]] || return 1
    grep -Eq 'android[[:space:]]*\{' "$gradle_file" || return 0

    if [[ "$gradle_file" == *.kts ]]; then
        cat >> "$gradle_file" <<'PAS_KTS_JAVA_TARGET'

// Pocket APK Studio: force Android Java bytecode compatibility to 17.
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
PAS_KTS_JAVA_TARGET
        if grep -Eiq 'kotlin' "$gradle_file"; then
            cat >> "$gradle_file" <<'PAS_KTS_KOTLIN_TARGET'

// Pocket APK Studio: keep Kotlin bytecode aligned with Java 17.
android {
    kotlinOptions {
        jvmTarget = "17"
    }
}
PAS_KTS_KOTLIN_TARGET
        fi
    else
        cat >> "$gradle_file" <<'PAS_GROOVY_JAVA_TARGET'

// Pocket APK Studio: force Android Java bytecode compatibility to 17.
android {
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}
PAS_GROOVY_JAVA_TARGET
        if grep -Eiq 'kotlin' "$gradle_file"; then
            cat >> "$gradle_file" <<'PAS_GROOVY_KOTLIN_TARGET'

// Pocket APK Studio: keep Kotlin bytecode aligned with Java 17.
android {
    kotlinOptions {
        jvmTarget = '17'
    }
}
PAS_GROOVY_KOTLIN_TARGET
        fi
    fi
    return 0
}

pas_find_apks() {
    local project_root="$1"
    local apk_file

    PAS_APK_FILES=()
    [[ -d "$project_root" ]] || return 1
    while IFS= read -r -d '' apk_file; do
        [[ -s "$apk_file" ]] && PAS_APK_FILES+=("$apk_file")
    done < <(
        find "$project_root" -type d -name .gradle -prune -o \
            -type f -path '*/build/outputs/apk/debug/*.apk' -print0 2>/dev/null
    )
    return 0
}

pas_verify_sha256() {
    local file="$1"
    local expected="$2"
    [[ -f "$file" && -s "$file" ]] || return 1
    printf '%s  %s\n' "$expected" "$file" | sha256sum -c - >/dev/null 2>&1
}

pas_run_self_tests() (
    set -Eeuo pipefail
    local test_root
    local valid_zip
    local unsafe_zip
    local extracted
    local kts_file
    local groovy_file
    local fake_apk
    local digest
    local test_count=0

    test_root="$(mktemp -d "${TMPDIR:-/tmp}/pas-build-engine-test.XXXXXX")"
    trap 'rm -rf -- "$test_root"' EXIT

    test_pass() {
        printf 'PASS: %s\n' "$1"
        test_count=$((test_count + 1))
    }

    for tool in python3 unzip find grep sha256sum mktemp; do
        pas_require_command "$tool" || {
            printf 'FAIL: required test tool is missing: %s\n' "$tool" >&2
            return 1
        }
    done
    test_pass "required test tools are present"

    if pas_zip_entry_is_safe "app module/build.gradle.kts"; then
        test_pass "ZIP entry with spaces is accepted"
    else
        printf 'FAIL: safe ZIP entry was rejected\n' >&2
        return 1
    fi
    if pas_zip_entry_is_safe "../outside.txt" || pas_zip_entry_is_safe "/absolute/path"; then
        printf 'FAIL: unsafe ZIP traversal entry was accepted\n' >&2
        return 1
    else
        test_pass "absolute and parent-traversal ZIP entries are rejected"
    fi

    valid_zip="$test_root/project archive with spaces.zip"
    unsafe_zip="$test_root/unsafe archive.zip"
    python3 - "$valid_zip" "$unsafe_zip" <<'PAS_TEST_ZIPS'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1], "w") as archive:
    archive.writestr("Wrapper Project/settings.gradle.kts", 'rootProject.name = "Sample"')
    archive.writestr(
        "Wrapper Project/app/build.gradle.kts",
        'plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }\n'
        'android { compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8 } }\n'
    )
with zipfile.ZipFile(sys.argv[2], "w") as archive:
    archive.writestr("../escaped.txt", "must be rejected")
PAS_TEST_ZIPS

    if pas_validate_zip "$valid_zip"; then
        test_pass "valid ZIP with spaces passes archive validation"
    else
        printf 'FAIL: valid ZIP was rejected\n' >&2
        return 1
    fi
    if pas_validate_zip "$unsafe_zip"; then
        printf 'FAIL: ZIP traversal archive was accepted\n' >&2
        return 1
    else
        test_pass "ZIP containing parent traversal is rejected"
    fi
    if pas_validate_zip "$test_root/missing project.zip"; then
        printf 'FAIL: missing ZIP was accepted\n' >&2
        return 1
    else
        test_pass "missing ZIP produces a detectable validation failure"
    fi

    extracted="$test_root/extracted project"
    mkdir -p "$extracted"
    unzip -q "$valid_zip" -d "$extracted"
    if pas_find_gradle_root "$extracted" &&
        [[ "$PAS_GRADLE_ROOT" == "$extracted/Wrapper Project" ]] &&
        [[ "$PAS_SETTINGS_FILE" == "$extracted/Wrapper Project/settings.gradle.kts" ]]; then
        test_pass "nested ZIP root is discovered when folder names contain spaces"
    else
        printf 'FAIL: nested Gradle root detection returned [%s]\n' "$PAS_GRADLE_ROOT" >&2
        return 1
    fi

    mkdir -p "$test_root/direct project"
    : > "$test_root/direct project/settings.gradle"
    if pas_find_gradle_root "$test_root/direct project" &&
        [[ "$PAS_GRADLE_ROOT" == "$test_root/direct project" ]]; then
        test_pass "Gradle settings at ZIP root are discovered"
    else
        printf 'FAIL: direct Gradle root was not discovered\n' >&2
        return 1
    fi
    if pas_find_gradle_root "$test_root/no settings project"; then
        printf 'FAIL: project without Gradle settings was accepted\n' >&2
        return 1
    else
        test_pass "project without settings.gradle is rejected"
    fi

    kts_file="$test_root/Kotlin Android Module/build.gradle.kts"
    mkdir -p "$(dirname "$kts_file")"
    cat > "$kts_file" <<'PAS_TEST_KTS'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}
PAS_TEST_KTS
    pas_align_gradle_targets "$kts_file"
    grep -Fq 'sourceCompatibility = JavaVersion.VERSION_17' "$kts_file"
    grep -Fq 'targetCompatibility = JavaVersion.VERSION_17' "$kts_file"
    grep -Fq 'jvmTarget = "17"' "$kts_file"
    test_pass "Kotlin DSL existing Java 8 and Kotlin 1.8 targets are overridden to 17"

    groovy_file="$test_root/Groovy Android Module/build.gradle"
    mkdir -p "$(dirname "$groovy_file")"
    cat > "$groovy_file" <<'PAS_TEST_GROOVY'
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}
android {
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = '1.8' }
}
PAS_TEST_GROOVY
    pas_align_gradle_targets "$groovy_file"
    grep -Fq 'sourceCompatibility JavaVersion.VERSION_17' "$groovy_file"
    grep -Fq 'targetCompatibility JavaVersion.VERSION_17' "$groovy_file"
    grep -Fq "jvmTarget = '17'" "$groovy_file"
    test_pass "Groovy DSL existing Java 8 and Kotlin 1.8 targets are overridden to 17"

    printf 'aapt2-test-binary' > "$test_root/aapt2"
    digest="$(sha256sum "$test_root/aapt2" | awk '{print $1}')"
    if pas_verify_sha256 "$test_root/aapt2" "$digest"; then
        test_pass "AAPT2 SHA-256 verification accepts a matching checksum"
    else
        printf 'FAIL: matching SHA-256 checksum was rejected\n' >&2
        return 1
    fi
    if pas_verify_sha256 "$test_root/aapt2" "0000000000000000000000000000000000000000000000000000000000000000"; then
        printf 'FAIL: mismatched SHA-256 checksum was accepted\n' >&2
        return 1
    else
        test_pass "AAPT2 SHA-256 verification rejects a mismatched checksum"
    fi
    if pas_require_command "pas-command-that-does-not-exist"; then
        printf 'FAIL: nonexistent command was reported as installed\n' >&2
        return 1
    else
        test_pass "missing tool detection returns a failure"
    fi

    fake_apk="$test_root/Android Project With Spaces/app/build/outputs/apk/debug/app-debug.apk"
    mkdir -p "$(dirname "$fake_apk")"
    printf 'fake-apk-content' > "$fake_apk"
    pas_find_apks "$test_root/Android Project With Spaces"
    if [[ "${#PAS_APK_FILES[@]}" -eq 1 ]] && [[ "${PAS_APK_FILES[0]}" == "$fake_apk" ]]; then
        test_pass "debug APK discovery supports paths containing spaces"
    else
        printf 'FAIL: debug APK discovery returned %s candidates\n' "${#PAS_APK_FILES[@]}" >&2
        return 1
    fi

    printf 'SELF_TESTS_PASSED=%s\n' "$test_count"
)

pas_ubuntu_main() {
    local zip_name="${1-}"
    local shared_zip
    local java_version
    local java_major
    local android_sdk
    local aapt2_dir
    local aapt2
    local aapt2_tmp
    local aapt2_sha256="7e5ae2e1f62fc24cab14072555ffd0a1a7e1ce27e82cc1008967b835b6d8df5b"
    local gradle_properties
    local project_base="/root/pocket-apk-studio"
    local project="$project_base/project"
    local gradle_root
    local settings_file
    local gradle_file
    local android_modules=0
    local wrapper=""
    local search_dir
    local apk
    local apk_name
    local output_dir="/mnt/shared/Download/PocketAPKStudio/Builds"
    local apk_count=0
    local -a build_files=()

    trap 'rc=$?; printf "UBUNTU_BUILD_EXIT=%s\n" "$rc"' EXIT

    case "$zip_name" in
        ""|*/*|*[!A-Za-z0-9._-]*)
            pas_fail 25 "Invalid project ZIP file name passed to Ubuntu."
            ;;
        project-*)
            ;;
        *)
            pas_fail 25 "Unexpected ZIP name; expected a copied Pocket APK Studio project file."
            ;;
    esac
    case "$zip_name" in
        *.[zZ][iI][pP]) ;;
        *) pas_fail 25 "The imported project file does not have a .zip extension." ;;
    esac

    shared_zip="/mnt/shared/Download/PocketAPKStudio/$zip_name"
    [[ -r "$shared_zip" ]] || pas_fail 25 "Imported ZIP is not readable in the Ubuntu shared mount: $shared_zip"

    case "$(uname -m)" in
        aarch64|arm64) ;;
        *) pas_fail 30 "This local build engine requires an ARM64 Android device; Ubuntu reports architecture $(uname -m)." ;;
    esac

    pas_require_command java || pas_fail 11 "Java is missing in Ubuntu. Install Java 17 and retry."
    java_version="$(java -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p')"
    [[ -n "$java_version" ]] || pas_fail 31 "Could not read the Java version from java -version."
    if [[ "$java_version" == 1.* ]]; then
        java_major="$(printf '%s' "$java_version" | cut -d. -f2)"
    else
        java_major="$(printf '%s' "$java_version" | cut -d. -f1)"
    fi
    [[ "$java_major" =~ ^[0-9]+$ ]] || pas_fail 31 "Could not parse Java version: $java_version"
    (( java_major >= 17 )) || pas_fail 32 "Java 17 or newer is required; Ubuntu currently reports Java $java_version."
    pas_log "ENVIRONMENT: Java $java_version"

    android_sdk="${ANDROID_HOME:-/root/Android/Sdk}"
    ANDROID_HOME="$android_sdk"
    ANDROID_SDK_ROOT="$android_sdk"
    export ANDROID_HOME ANDROID_SDK_ROOT
    export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/35.0.0:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    [[ -f "$ANDROID_HOME/platforms/android-35/android.jar" ]] ||
        pas_fail 15 "Android SDK Platform 35 is missing: $ANDROID_HOME/platforms/android-35/android.jar. Install platform 35 in Ubuntu."
    pas_require_command unzip || pas_fail 33 "The unzip command is missing in Ubuntu."
    pas_require_command find || pas_fail 34 "The find command is missing in Ubuntu."
    pas_require_command sha256sum || pas_fail 35 "The sha256sum command is missing in Ubuntu."
    pas_require_command grep || pas_fail 36 "The grep command is missing in Ubuntu."
    pas_require_command sed || pas_fail 37 "The sed command is missing in Ubuntu."

    aapt2_dir="/root/.local/bin"
    aapt2="$aapt2_dir/aapt2"
    mkdir -p "$aapt2_dir"
    if [[ ! -x "$aapt2" ]] || ! pas_verify_sha256 "$aapt2" "$aapt2_sha256" || ! "$aapt2" version >/dev/null 2>&1; then
        aapt2_tmp="$aapt2.download.$$"
        rm -f -- "$aapt2_tmp"
        pas_log "Installing or refreshing the pinned ARM64 AAPT2 binary..."
        if pas_require_command curl; then
            curl -L --fail --silent --show-error \
                "https://github.com/ReVanced/aapt2/releases/download/v1.1.0/aapt2-arm64-v8a" \
                -o "$aapt2_tmp" || pas_fail 17 "AAPT2 download failed. Check the Ubuntu network connection."
        elif pas_require_command wget; then
            wget -q \
                "https://github.com/ReVanced/aapt2/releases/download/v1.1.0/aapt2-arm64-v8a" \
                -O "$aapt2_tmp" || pas_fail 17 "AAPT2 download failed. Check the Ubuntu network connection."
        else
            pas_fail 17 "curl or wget is required to download the pinned ARM64 AAPT2 binary."
        fi
        pas_verify_sha256 "$aapt2_tmp" "$aapt2_sha256" ||
            pas_fail 18 "ARM64 AAPT2 checksum verification failed; the downloaded file was not installed."
        chmod 755 "$aapt2_tmp"
        mv -f -- "$aapt2_tmp" "$aapt2"
    fi
    pas_verify_sha256 "$aapt2" "$aapt2_sha256" ||
        pas_fail 18 "The installed ARM64 AAPT2 binary failed checksum verification."
    "$aapt2" version || pas_fail 19 "The verified AAPT2 binary could not execute on this device."
    pas_log "AAPT2_CHECKSUM=VERIFIED"

    gradle_properties="$HOME/.gradle/gradle.properties"
    mkdir -p "$(dirname "$gradle_properties")"
    touch "$gradle_properties"
    if grep -q '^android.aapt2FromMavenOverride=' "$gradle_properties"; then
        sed -i "s|^android.aapt2FromMavenOverride=.*|android.aapt2FromMavenOverride=$aapt2|" "$gradle_properties"
    else
        printf 'android.aapt2FromMavenOverride=%s\n' "$aapt2" >> "$gradle_properties"
    fi

    pas_log "ZIP_VALIDATION: checking archive integrity and entry paths"
    pas_validate_zip "$shared_zip" ||
        pas_fail 20 "The imported ZIP is corrupt, empty, missing, or contains an unsafe absolute/parent-traversal path."
    mkdir -p "$project_base"
    [[ "$project" == "/root/pocket-apk-studio/project" ]] ||
        pas_fail 26 "Internal project workspace path validation failed."
    rm -rf -- "$project"
    mkdir -p -- "$project"
    unzip -q "$shared_zip" -d "$project" ||
        pas_fail 21 "Project ZIP extraction failed in the Ubuntu workspace."
    pas_log "ZIP_EXTRACTED=$project"

    pas_find_gradle_root "$project" ||
        pas_fail 14 "No settings.gradle or settings.gradle.kts was found after ZIP extraction."
    gradle_root="$PAS_GRADLE_ROOT"
    settings_file="$PAS_SETTINGS_FILE"
    pas_log "DIAGNOSTIC: PROJECT=[$project]"
    pas_log "DIAGNOSTIC: SETTINGS=[$settings_file]"
    pas_log "DIAGNOSTIC: GRADLE_ROOT=[$gradle_root]"

    while IFS= read -r -d '' gradle_file; do
        build_files+=("$gradle_file")
    done < <(
        find "$gradle_root" -type d -name .gradle -prune -o \
            -type f \( -name build.gradle -o -name build.gradle.kts \) -print0 2>/dev/null
    )
    (( ${#build_files[@]} > 0 )) ||
        pas_fail 14 "Gradle settings were found, but no build.gradle or build.gradle.kts module files were discovered."

    for gradle_file in "${build_files[@]}"; do
        if grep -Eq 'android[[:space:]]*\{' "$gradle_file"; then
            pas_align_gradle_targets "$gradle_file" ||
                pas_fail 38 "Could not apply Java/Kotlin 17 compatibility overrides to: $gradle_file"
            android_modules=$((android_modules + 1))
            pas_log "JVM_TARGETS_ALIGNED=17 FILE=[$gradle_file]"
        fi
    done
    (( android_modules > 0 )) ||
        pas_fail 39 "No Android module with an android { } Gradle block was found. This ZIP may not be an Android Gradle project."

    wrapper=""
    search_dir="$gradle_root"
    while [[ "$search_dir" == "$project" || "$search_dir" == "$project/"* ]]; do
        if [[ -f "$search_dir/gradlew" ]]; then
            wrapper="$search_dir/gradlew"
            break
        fi
        [[ "$search_dir" == "$project" ]] && break
        search_dir="$(dirname "$search_dir")"
    done

    pas_log "GRADLE_ROOT=$gradle_root"
    if [[ -n "$wrapper" ]]; then
        chmod +x "$wrapper" || pas_fail 40 "Could not make the Gradle wrapper executable: $wrapper"
        pas_log "GRADLE_LAUNCHER=$wrapper"
        "$wrapper" -p "$gradle_root" --no-daemon --max-workers=2 --console=plain assembleDebug
    elif pas_require_command gradle; then
        pas_log "GRADLE_LAUNCHER=$(command -v gradle)"
        gradle --no-daemon --max-workers=2 --console=plain -p "$gradle_root" assembleDebug
    else
        pas_fail 12 "No gradlew wrapper was found under the imported project and Gradle is not installed in Ubuntu."
    fi

    mkdir -p "$output_dir"
    pas_find_apks "$gradle_root" || pas_fail 16 "Could not scan Gradle outputs for debug APK files."
    (( ${#PAS_APK_FILES[@]} > 0 )) ||
        pas_fail 16 "Gradle returned success, but no non-empty debug APK was found under build/outputs/apk/debug."

    for apk in "${PAS_APK_FILES[@]}"; do
        apk_name="$(basename "$apk")"
        cp -f -- "$apk" "$output_dir/$apk_name" ||
            pas_fail 41 "Could not copy APK to shared Downloads: $apk_name"
        [[ -s "$output_dir/$apk_name" ]] ||
            pas_fail 42 "Copied APK is empty: $output_dir/$apk_name"
        echo "APK_SAVED=/storage/emulated/0/Download/PocketAPKStudio/Builds/$apk_name"
        apk_count=$((apk_count + 1))
    done

    pas_log "BUILD_SUCCESS=1"
    pas_log "BUILD_SUCCESS: $apk_count debug APK file(s) copied and checked in Downloads/PocketAPKStudio/Builds."
}

pas_termux_main() {
    local zip_name="${1-}"
    local shared="/storage/emulated/0"
    local build_dir="$shared/Download/PocketAPKStudio"
    local live_log="$build_dir/build-live.log"
    local engine_in_shared="$build_dir/build-project.sh"

    trap 'rc=$?; printf "BUILD_ENGINE_EXIT=%s\n" "$rc"' EXIT

    mkdir -p "$build_dir" || pas_fail 20 "Could not create Downloads/PocketAPKStudio."
    : > "$live_log" || pas_fail 20 "Could not create the live build log: $live_log"
    exec > >(tee -a "$live_log") 2>&1

    pas_log "Pocket APK Studio: local Android build started"
    pas_log "TERMUX_VERSION=${TERMUX_VERSION:-unknown}"
    [[ -d "$shared/Download" ]] ||
        pas_fail 24 "Termux shared storage is not ready. Open Termux, run termux-setup-storage, and grant storage access."
    [[ -r "$engine_in_shared" ]] ||
        pas_fail 23 "The standalone build-project.sh script was not copied to shared Downloads."
    case "$zip_name" in
        ""|*/*|*[!A-Za-z0-9._-]*)
            pas_fail 25 "Invalid imported ZIP name passed from Pocket APK Studio."
            ;;
        project-*)
            ;;
        *)
            pas_fail 25 "Unexpected imported ZIP name; expected a copied Pocket APK Studio project file."
            ;;
    esac
    case "$zip_name" in
        *.[zZ][iI][pP]) ;;
        *) pas_fail 25 "Imported project file does not have a .zip extension." ;;
    esac
    [[ -r "$build_dir/$zip_name" ]] ||
        pas_fail 25 "Imported project ZIP is not readable in Termux shared storage: $build_dir/$zip_name"
    pas_require_command proot-distro ||
        pas_fail 10 "proot-distro is missing in Termux. Install it and set up the Ubuntu distribution."

    pas_log "TERMUX_ZIP=$build_dir/$zip_name"
    pas_log "PROOT_BIND=$shared:/mnt/shared"
    proot-distro login --bind "$shared:/mnt/shared" ubuntu -- \
        bash /mnt/shared/Download/PocketAPKStudio/build-project.sh --ubuntu "$zip_name" ||
        pas_fail 43 "The Ubuntu PRoot build process returned a failure. See the preceding Ubuntu diagnostics."
    pas_log "BUILD_ENGINE_COMPLETE=1"
}

if [[ "${1-}" == "--self-test" ]]; then
    pas_run_self_tests
    exit $?
elif [[ "${1-}" == "--ubuntu" ]]; then
    shift
    pas_ubuntu_main "$@"
    exit $?
else
    pas_termux_main "$@"
    exit $?
fi
