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

# A Gradle wrapper is usable only when its launcher, JAR and properties exist and the
# wrapper JAR is a valid ZIP. Source archives sometimes omit or truncate wrapper files.
pas_read_gradle_wrapper_metadata() {
    local properties_file="$1"
    local distribution_url
    local base_name
    local checksum

    PAS_GRADLE_VERSION=""
    PAS_GRADLE_WRAPPER_SHA256=""
    PAS_GRADLE_DISTRIBUTION_TYPE=""
    [[ -s "$properties_file" ]] || return 1

    distribution_url="$(sed -n 's/^[[:space:]]*distributionUrl[[:space:]]*=[[:space:]]*//p' "$properties_file" | tail -n 1)"
    distribution_url="${distribution_url//\\:/:}"
    distribution_url="${distribution_url//\\=/=}"
    distribution_url="${distribution_url%%\?*}"
    base_name="${distribution_url##*/}"
    if [[ "$base_name" =~ ^gradle-([0-9]+([.][0-9]+){1,2}([.-][A-Za-z0-9]+)*)-(bin|all)\.zip$ ]]; then
        PAS_GRADLE_VERSION="${BASH_REMATCH[1]}"
        PAS_GRADLE_DISTRIBUTION_TYPE="${BASH_REMATCH[4]}"
    else
        return 1
    fi

    checksum="$(sed -n 's/^[[:space:]]*distributionSha256Sum[[:space:]]*=[[:space:]]*//p' "$properties_file" | tail -n 1 | tr -d '\r')"
    if [[ "$checksum" =~ ^[A-Fa-f0-9]{64}$ ]]; then
        PAS_GRADLE_WRAPPER_SHA256="${checksum,,}"
    fi
    return 0
}

pas_find_gradle_wrapper() {
    local project_dir="$1"
    local gradle_root="$2"
    local search_dir="$gradle_root"
    local wrapper_jar
    local wrapper_properties

    PAS_GRADLE_WRAPPER=""
    while [[ "$search_dir" == "$project_dir" || "$search_dir" == "$project_dir/"* ]]; do
        wrapper_jar="$search_dir/gradle/wrapper/gradle-wrapper.jar"
        wrapper_properties="$search_dir/gradle/wrapper/gradle-wrapper.properties"
        if [[ -s "$search_dir/gradlew" && -s "$wrapper_jar" && -s "$wrapper_properties" ]] &&
            unzip -tqq "$wrapper_jar" >/dev/null 2>&1 &&
            pas_read_gradle_wrapper_metadata "$wrapper_properties"; then
            PAS_GRADLE_WRAPPER="$search_dir/gradlew"
            return 0
        fi
        [[ "$search_dir" == "$project_dir" ]] && break
        search_dir="$(dirname "$search_dir")"
    done
    return 1
}

pas_find_gradle_wrapper_properties() {
    local project_dir="$1"
    local gradle_root="$2"
    local search_dir="$gradle_root"

    PAS_GRADLE_WRAPPER_PROPERTIES=""
    while [[ "$search_dir" == "$project_dir" || "$search_dir" == "$project_dir/"* ]]; do
        if [[ -s "$search_dir/gradle/wrapper/gradle-wrapper.properties" ]]; then
            PAS_GRADLE_WRAPPER_PROPERTIES="$search_dir/gradle/wrapper/gradle-wrapper.properties"
            return 0
        fi
        [[ "$search_dir" == "$project_dir" ]] && break
        search_dir="$(dirname "$search_dir")"
    done
    return 1
}

pas_find_cached_gradle() {
    local version="$1"
    local candidate="${HOME:-/root}/.local/share/pocket-apk-studio/gradle-$version/bin/gradle"

    [[ -x "$candidate" ]] || return 1
    "$candidate" --version >/dev/null 2>&1 || return 1
    PAS_GRADLE_LAUNCHER="$candidate"
    return 0
}

# Download an official Gradle distribution only when no complete wrapper or suitable
# installed Gradle is available. Verify the official SHA-256 sidecar (or wrapper checksum).
pas_install_gradle_distribution() {
    local version="$1"
    local expected_sha="${2-}"
    local distribution_type="${3:-bin}"
    local cache_root="${HOME:-/root}/.local/share/pocket-apk-studio"
    local install_dir="$cache_root/gradle-$version"
    local temp_dir
    local archive
    local checksum_file
    local distribution_url="https://services.gradle.org/distributions/gradle-$version-$distribution_type.zip"

    if [[ -x "$install_dir/bin/gradle" ]] && "$install_dir/bin/gradle" --version >/dev/null 2>&1; then
        PAS_GRADLE_LAUNCHER="$install_dir/bin/gradle"
        return 0
    fi

    pas_require_command unzip || return 1
    pas_require_command sha256sum || return 1
    if ! pas_require_command curl && ! pas_require_command wget; then
        pas_log "ERROR: curl or wget is required to download the Gradle distribution."
        return 1
    fi

    mkdir -p "$cache_root" || return 1
    temp_dir="$(mktemp -d "$cache_root/.gradle-$version.XXXXXX")" || return 1
    archive="$temp_dir/gradle-$version-$distribution_type.zip"
    checksum_file="$temp_dir/gradle-$version-$distribution_type.zip.sha256"

    if pas_require_command curl; then
        if ! curl -fL --retry 2 --silent --show-error "$distribution_url" -o "$archive"; then
            pas_log "ERROR: Gradle $version download failed: $distribution_url"
            rm -rf -- "$temp_dir"
            return 1
        fi
        if [[ ! "$expected_sha" =~ ^[A-Fa-f0-9]{64}$ ]]; then
            if ! curl -fL --retry 2 --silent --show-error "$distribution_url.sha256" -o "$checksum_file"; then
                pas_log "ERROR: official Gradle $version SHA-256 file could not be downloaded."
                rm -rf -- "$temp_dir"
                return 1
            fi
        fi
    else
        if ! wget -q "$distribution_url" -O "$archive"; then
            pas_log "ERROR: Gradle $version download failed: $distribution_url"
            rm -rf -- "$temp_dir"
            return 1
        fi
        if [[ ! "$expected_sha" =~ ^[A-Fa-f0-9]{64}$ ]]; then
            if ! wget -q "$distribution_url.sha256" -O "$checksum_file"; then
                pas_log "ERROR: official Gradle $version SHA-256 file could not be downloaded."
                rm -rf -- "$temp_dir"
                return 1
            fi
        fi
    fi

    if [[ ! "$expected_sha" =~ ^[A-Fa-f0-9]{64}$ ]]; then
        expected_sha="$(awk 'NF { print $1; exit }' "$checksum_file" | tr -d '\r')"
    fi
    if [[ ! "$expected_sha" =~ ^[A-Fa-f0-9]{64}$ ]] ||
        ! pas_verify_sha256 "$archive" "${expected_sha,,}" ||
        ! unzip -tqq "$archive" >/dev/null 2>&1; then
        pas_log "ERROR: downloaded Gradle $version archive failed SHA-256 or ZIP integrity validation."
        rm -rf -- "$temp_dir"
        return 1
    fi

    if ! unzip -q "$archive" -d "$temp_dir" ||
        [[ ! -x "$temp_dir/gradle-$version/bin/gradle" ]] ||
        ! "$temp_dir/gradle-$version/bin/gradle" --version >/dev/null 2>&1; then
        pas_log "ERROR: Gradle $version archive did not contain a runnable distribution."
        rm -rf -- "$temp_dir"
        return 1
    fi

    rm -rf -- "$install_dir"
    if ! mv -- "$temp_dir/gradle-$version" "$install_dir"; then
        pas_log "ERROR: could not install Gradle $version into $install_dir."
        rm -rf -- "$temp_dir"
        return 1
    fi
    rm -rf -- "$temp_dir"
    PAS_GRADLE_LAUNCHER="$install_dir/bin/gradle"
    pas_log "GRADLE_BOOTSTRAPPED=$version"
    return 0
}

pas_select_gradle_launcher() {
    local project_dir="$1"
    local gradle_root="$2"
    local wrapper_version=""
    local wrapper_sha=""
    local system_version=""
    local system_major=""

    PAS_GRADLE_LAUNCHER=""
    PAS_GRADLE_KIND=""

    if pas_find_gradle_wrapper "$project_dir" "$gradle_root"; then
        PAS_GRADLE_LAUNCHER="$PAS_GRADLE_WRAPPER"
        PAS_GRADLE_KIND="wrapper"
        return 0
    fi

    # If wrapper files are incomplete but properties survived, keep the project's
    # declared Gradle version. Fall back to the pinned version only without metadata.
    if pas_find_gradle_wrapper_properties "$project_dir" "$gradle_root" &&
        pas_read_gradle_wrapper_metadata "$PAS_GRADLE_WRAPPER_PROPERTIES"; then
        wrapper_version="$PAS_GRADLE_VERSION"
        wrapper_sha="$PAS_GRADLE_WRAPPER_SHA256"
        if pas_find_cached_gradle "$wrapper_version"; then
            PAS_GRADLE_KIND="cached-wrapper-version"
            pas_log "WARNING: Gradle wrapper files are incomplete; using cached Gradle $wrapper_version from its wrapper properties."
            return 0
        fi
        if [[ "${PAS_DISABLE_GRADLE_BOOTSTRAP:-0}" != "1" ]] &&
            pas_install_gradle_distribution "$wrapper_version" "$wrapper_sha" "$PAS_GRADLE_DISTRIBUTION_TYPE"; then
            PAS_GRADLE_KIND="bootstrapped-wrapper-version"
            pas_log "WARNING: Gradle wrapper files are incomplete; using a verified Gradle $wrapper_version distribution."
            return 0
        fi
    fi

    if pas_require_command gradle; then
        system_version="$(gradle --version 2>/dev/null | awk '$1 == "Gradle" { print $2; exit }')"
        system_major="${system_version%%.*}"
        if [[ "$system_major" =~ ^[0-9]+$ ]] && (( system_major >= 8 )); then
            PAS_GRADLE_LAUNCHER="$(command -v gradle)"
            PAS_GRADLE_KIND="system"
            return 0
        fi
        pas_log "WARNING: Ignoring unsupported system Gradle version [${system_version:-unknown}]; Gradle 8 or newer is required for the fallback."
    fi

    if pas_find_cached_gradle "9.3.1"; then
        PAS_GRADLE_KIND="cached-default"
        return 0
    fi
    if [[ "${PAS_DISABLE_GRADLE_BOOTSTRAP:-0}" == "1" ]]; then
        return 1
    fi
    if pas_install_gradle_distribution "9.3.1"; then
        PAS_GRADLE_KIND="bootstrapped-default"
        return 0
    fi
    return 1
}

# Derive Android SDK package requirements from the imported Gradle project.
pas_sdk_append_unique() {
    local collection="$1"
    local value="$2"
    local existing
    case "$collection" in
        compile)
            for existing in "${PAS_REQUIRED_COMPILE_SDKS[@]}"; do
                [[ "$existing" == "$value" ]] && return 0
            done
            PAS_REQUIRED_COMPILE_SDKS+=("$value")
            ;;
        build-tools)
            for existing in "${PAS_REQUIRED_BUILD_TOOLS[@]}"; do
                [[ "$existing" == "$value" ]] && return 0
            done
            PAS_REQUIRED_BUILD_TOOLS+=("$value")
            ;;
        *) return 2 ;;
    esac
}

pas_default_build_tools_for_api() {
    case "$1" in
        36) printf '36.0.0\n' ;;
        35) printf '35.0.0\n' ;;
        34) printf '34.0.0\n' ;;
        33) printf '33.0.2\n' ;;
        32) printf '32.0.0\n' ;;
        31) printf '31.0.0\n' ;;
        30) printf '30.0.3\n' ;;
        29) printf '29.0.3\n' ;;
        28) printf '28.0.3\n' ;;
        27) printf '27.0.3\n' ;;
        26) printf '26.0.2\n' ;;
        *) printf '35.0.0\n' ;;
    esac
}

pas_detect_android_sdk_requirements() {
    local project_root="$1"
    local gradle_file
    local line
    local normalized
    local catalog_file
    local catalog_line
    local in_versions=0
    local sdk_version
    local build_tools_version
    local mapped_tools
    local -a catalogs=()

    PAS_REQUIRED_COMPILE_SDKS=()
    PAS_REQUIRED_BUILD_TOOLS=()
    [[ -d "$project_root" ]] || return 1

    while IFS= read -r -d '' gradle_file; do
        case "$gradle_file" in
            */libs.versions.toml|*/gradle/*versions*.toml)
                catalogs+=("$gradle_file")
                continue
                ;;
        esac
        while IFS= read -r line || [[ -n "$line" ]]; do
            normalized="${line//\"/}"
            normalized="${normalized//\'/}"
            if [[ "$normalized" =~ compileSdk(Version)?[[:space:]]*=?[[:space:]]*\(?[[:space:]]*([0-9]{2,3}) ]]; then
                sdk_version="${BASH_REMATCH[2]}"
                pas_sdk_append_unique compile "$sdk_version"
            fi
            if [[ "$normalized" =~ buildToolsVersion[[:space:]]*=?[[:space:]]*\(?[[:space:]]*([0-9]+[.][0-9]+[.][0-9]+) ]]; then
                build_tools_version="${BASH_REMATCH[1]}"
                pas_sdk_append_unique build-tools "$build_tools_version"
            fi
        done < "$gradle_file"
    done < <(
        find "$project_root" \
            -type d \( -name .gradle -o -name build \) -prune -o \
            -type f \( -name build.gradle -o -name build.gradle.kts -o -name '*.toml' \) -print0 2>/dev/null
    )

    for catalog_file in "${catalogs[@]}"; do
        in_versions=0
        while IFS= read -r catalog_line || [[ -n "$catalog_line" ]]; do
            if [[ "$catalog_line" =~ ^[[:space:]]*\[versions\][[:space:]]*$ ]]; then
                in_versions=1
                continue
            fi
            if [[ "$catalog_line" =~ ^[[:space:]]*\[ ]]; then
                in_versions=0
            fi
            (( in_versions )) || continue
            if [[ "$catalog_line" =~ ^[[:space:]]*(compileSdk|compileSdkVersion|androidCompileSdk|androidCompileSdkVersion)[[:space:]]*=[[:space:]]*\"?([0-9]{2,3}) ]]; then
                pas_sdk_append_unique compile "${BASH_REMATCH[2]}"
            elif [[ "$catalog_line" =~ ^[[:space:]]*(buildTools|buildToolsVersion|androidBuildTools)[[:space:]]*=[[:space:]]*\"?([0-9]+[.][0-9]+[.][0-9]+) ]]; then
                pas_sdk_append_unique build-tools "${BASH_REMATCH[2]}"
            fi
        done < "$catalog_file"
    done

    if (( "${#PAS_REQUIRED_COMPILE_SDKS[@]}" == 0 )); then
        PAS_REQUIRED_COMPILE_SDKS+=(35)
        pas_log "WARNING: No numeric compileSdk was found; using Android API 35 as the baseline."
    fi
    for sdk_version in "${PAS_REQUIRED_COMPILE_SDKS[@]}"; do
        mapped_tools="$(pas_default_build_tools_for_api "$sdk_version")"
        pas_sdk_append_unique build-tools "$mapped_tools"
    done
    pas_log "SDK_REQUIREMENTS: platforms=[${PAS_REQUIRED_COMPILE_SDKS[*]}] build-tools=[${PAS_REQUIRED_BUILD_TOOLS[*]}]"
    return 0
}

pas_resolve_android_sdk_manager() {
    local sdk_root="$1"
    local candidate
    PAS_ANDROID_SDKMANAGER=""
    if [[ -n "${ANDROID_SDK_MANAGER:-}" && -x "$ANDROID_SDK_MANAGER" ]]; then
        PAS_ANDROID_SDKMANAGER="$ANDROID_SDK_MANAGER"
        return 0
    fi
    for candidate in "$sdk_root/cmdline-tools/latest/bin/sdkmanager" "/root/Android/cmdline-tools/latest/bin/sdkmanager"; do
        if [[ -x "$candidate" ]]; then
            PAS_ANDROID_SDKMANAGER="$candidate"
            return 0
        fi
    done
    if command -v sdkmanager >/dev/null 2>&1; then
        PAS_ANDROID_SDKMANAGER="$(command -v sdkmanager)"
        return 0
    fi
    return 1
}

pas_ensure_android_sdk() {
    local sdk_root="$1"
    PAS_ANDROID_SDKMANAGER=""
    local sdk_version
    local build_tools_version
    local sdkmanager_dir
    local path_entry
    local -a packages=()
    local -a path_entries=()

    [[ -d "$sdk_root" ]] || mkdir -p "$sdk_root" || return 1
    for sdk_version in "${PAS_REQUIRED_COMPILE_SDKS[@]}"; do
        [[ -s "$sdk_root/platforms/android-$sdk_version/android.jar" ]] ||
            packages+=("platforms;android-$sdk_version")
    done
    [[ -x "$sdk_root/platform-tools/adb" ]] || packages+=("platform-tools")
    for build_tools_version in "${PAS_REQUIRED_BUILD_TOOLS[@]}"; do
        [[ -x "$sdk_root/build-tools/$build_tools_version/aapt" ]] ||
            packages+=("build-tools;$build_tools_version")
    done

    if (( "${#packages[@]}" > 0 )); then
        if ! pas_resolve_android_sdk_manager "$sdk_root"; then
            pas_log "ERROR: Required Android SDK packages are missing (${packages[*]}) and sdkmanager was not found."
            pas_log "ERROR: Expected sdkmanager at $sdk_root/cmdline-tools/latest/bin/sdkmanager or /root/Android/cmdline-tools/latest/bin/sdkmanager."
            return 1
        fi
        pas_log "SDKMANAGER=[$PAS_ANDROID_SDKMANAGER]"
        pas_log "SDK_INSTALL_PACKAGES=(${packages[*]})"
        if command -v yes >/dev/null 2>&1; then
            yes | "$PAS_ANDROID_SDKMANAGER" "--sdk_root=$sdk_root" --licenses >/dev/null 2>&1 || true
        else
            "$PAS_ANDROID_SDKMANAGER" "--sdk_root=$sdk_root" --licenses </dev/null >/dev/null 2>&1 || true
        fi
        if ! "$PAS_ANDROID_SDKMANAGER" "--sdk_root=$sdk_root" "${packages[@]}"; then
            pas_log "ERROR: sdkmanager failed while installing required Android SDK packages."
            return 1
        fi
    else
        pas_log "SDK_COMPONENTS: all required components are already installed."
    fi

    for sdk_version in "${PAS_REQUIRED_COMPILE_SDKS[@]}"; do
        [[ -s "$sdk_root/platforms/android-$sdk_version/android.jar" ]] || {
            pas_log "ERROR: Android SDK Platform $sdk_version is still missing after sdkmanager completed."
            return 1
        }
    done
    [[ -x "$sdk_root/platform-tools/adb" ]] || {
        pas_log "ERROR: Android SDK platform-tools/adb is missing after sdkmanager completed."
        return 1
    }
    for build_tools_version in "${PAS_REQUIRED_BUILD_TOOLS[@]}"; do
        [[ -x "$sdk_root/build-tools/$build_tools_version/aapt" ]] || {
            pas_log "ERROR: Android Build Tools $build_tools_version are still missing after sdkmanager completed."
            return 1
        }
    done

    if [[ -n "$PAS_ANDROID_SDKMANAGER" ]]; then
        sdkmanager_dir="$(dirname "$PAS_ANDROID_SDKMANAGER")"
        path_entries+=("$sdkmanager_dir")
    fi
    path_entries+=("$sdk_root/platform-tools")
    for build_tools_version in "${PAS_REQUIRED_BUILD_TOOLS[@]}"; do
        path_entries+=("$sdk_root/build-tools/$build_tools_version")
    done
    path_entry="$(IFS=:; printf '%s' "${path_entries[*]}")"
    export PATH="$path_entry:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    for sdk_version in "${PAS_REQUIRED_COMPILE_SDKS[@]}"; do
        pas_log "SDK_PLATFORM_VERIFIED=android-$sdk_version"
    done
    for build_tools_version in "${PAS_REQUIRED_BUILD_TOOLS[@]}"; do
        pas_log "SDK_BUILD_TOOLS_VERIFIED=$build_tools_version"
    done
    return 0
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
    local apk_dir

    PAS_APK_FILES=()
    [[ -d "$project_root" ]] || return 1
    while IFS= read -r -d '' apk_file; do
        [[ -s "$apk_file" ]] || continue
        apk_dir="${apk_file%/*}"
        [[ "${apk_dir##*/}" == "debug" ]] || continue
        case "$apk_dir" in
            */build/outputs/apk/*) PAS_APK_FILES+=("$apk_file") ;;
        esac
    done < <(
        find "$project_root" -type d -name .gradle -prune -o -type f -name "*.apk" -path "*/build/outputs/apk/*" -print0 2>/dev/null
    )
    return 0
}

pas_copy_apk_to_output() {
    local source="$1"
    local destination="$2"
    [[ -f "$source" && -s "$source" ]] || return 1
    mkdir -p "$(dirname "$destination")" || return 1
    cp -f -- "$source" "$destination" || return 1
    [[ -f "$destination" && -s "$destination" ]]
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
    local flavored_apk
    local multi_flavored_apk
    local release_apk
    local output_apk
    local discovered_apk
    local found_direct
    local found_flavored
    local found_multi_flavored
    local found_release
    local digest
    local wrapper_project
    local nested_gradle_root
    local incomplete_wrapper_project
    local plain_gradle_project
    local wrapper_jar
    local cache_home
    local cached_gradle
    local mock_bin
    local mock_gradle_zip
    local mock_gradle_sha
    local mock_request_log
    local bootstrap_home
    local fake_bin
    local sdk_project
    local sdkmanager_fixture
    local sdk_root_fixture
    local missing_sdk_root
    local sdk_request_log
    local saved_path="$PATH"
    local saved_home="${HOME:-/root}"
    local test_count=0

    test_root="$(mktemp -d "${TMPDIR:-/tmp}/pas-build-engine-test.XXXXXX")"
    trap '/bin/rm -rf -- "$test_root"' EXIT

    test_pass() {
        printf 'PASS: %s\n' "$1"
        test_count=$((test_count + 1))
    }

    for tool in python3 unzip find grep sha256sum mktemp cmp; do
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

    # Validate complete, corrupt and partial Gradle wrapper layouts.
    wrapper_project="$test_root/wrapper project"
    wrapper_jar="$wrapper_project/gradle/wrapper/gradle-wrapper.jar"
    mkdir -p "$wrapper_project/gradle/wrapper" "$wrapper_project/app"
    printf '#!/usr/bin/env sh\nexit 0\n' > "$wrapper_project/gradlew"
    printf 'distributionUrl=https\\://services.gradle.org/distributions/gradle-8.9-bin.zip\n' > "$wrapper_project/gradle/wrapper/gradle-wrapper.properties"
    printf 'not-a-valid-jar' > "$wrapper_jar"
    if pas_find_gradle_wrapper "$wrapper_project" "$wrapper_project"; then
        printf 'FAIL: invalid Gradle wrapper JAR was accepted\n' >&2
        return 1
    else
        test_pass "corrupt Gradle wrapper JAR is rejected"
    fi
    python3 - "$wrapper_jar" <<'PAS_TEST_WRAPPER_JAR'
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1], "w") as archive:
    archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
PAS_TEST_WRAPPER_JAR
    : > "$wrapper_project/settings.gradle"
    : > "$wrapper_project/app/build.gradle"
    nested_gradle_root="$wrapper_project/app"
    if pas_find_gradle_wrapper "$wrapper_project" "$nested_gradle_root" &&
        [[ "$PAS_GRADLE_WRAPPER" == "$wrapper_project/gradlew" ]] &&
        [[ "$PAS_GRADLE_VERSION" == "8.9" ]]; then
        test_pass "valid parent Gradle wrapper is found and its declared version is read"
    else
        printf 'FAIL: complete parent Gradle wrapper was not discovered\n' >&2
        return 1
    fi

    incomplete_wrapper_project="$test_root/incomplete wrapper project"
    mkdir -p "$incomplete_wrapper_project/gradle/wrapper"
    printf '#!/usr/bin/env sh\nexit 0\n' > "$incomplete_wrapper_project/gradlew"
    printf 'distributionUrl=https\\://services.gradle.org/distributions/gradle-8.9-bin.zip\n' > "$incomplete_wrapper_project/gradle/wrapper/gradle-wrapper.properties"
    if pas_find_gradle_wrapper "$incomplete_wrapper_project" "$incomplete_wrapper_project"; then
        printf 'FAIL: Gradle wrapper without its JAR was accepted\n' >&2
        return 1
    else
        test_pass "Gradle wrapper without its JAR is rejected"
    fi

    cache_home="$test_root/gradle cache home"
    cached_gradle="$cache_home/.local/share/pocket-apk-studio/gradle-8.9/bin/gradle"
    mkdir -p "$(dirname "$cached_gradle")"
    printf '#!/bin/sh\necho "Gradle 8.9"\n' > "$cached_gradle"
    chmod +x "$cached_gradle"
    HOME="$cache_home"

    fake_bin="$test_root/fake-bin"
    mkdir -p "$fake_bin"
    printf '#!/bin/sh\necho "Gradle 8.9"\n' > "$fake_bin/gradle"
    chmod +x "$fake_bin/gradle"
    PATH="$fake_bin:$saved_path"
    if pas_select_gradle_launcher "$incomplete_wrapper_project" "$incomplete_wrapper_project" &&
        [[ "$PAS_GRADLE_KIND" == "cached-wrapper-version" ]] &&
        [[ "$PAS_GRADLE_LAUNCHER" == "$cached_gradle" ]]; then
        test_pass "incomplete wrapper uses cached Gradle matching its declared version"
    else
        PATH="$saved_path"
        HOME="$saved_home"
        printf 'FAIL: incomplete wrapper did not select its matching cached Gradle version\n' >&2
        return 1
    fi

    # Exercise the actual download/checksum/extract/install path without network access.
    printf 'distributionUrl=https\\://services.gradle.org/distributions/gradle-8.9-all.zip\n' > "$incomplete_wrapper_project/gradle/wrapper/gradle-wrapper.properties"
    mock_bin="$test_root/mock-download-bin"
    mock_gradle_zip="$test_root/mock-gradle-8.9-all.zip"
    mock_request_log="$test_root/mock-gradle-requests.log"
    bootstrap_home="$test_root/Gradle bootstrap home"
    mkdir -p "$mock_bin" "$bootstrap_home"
    python3 - "$mock_gradle_zip" <<'PAS_TEST_GRADLE_DISTRIBUTION'
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1], "w", compression=zipfile.ZIP_DEFLATED) as archive:
    info = zipfile.ZipInfo("gradle-8.9/bin/gradle")
    info.create_system = 3
    info.external_attr = (0o100755 << 16)
    archive.writestr(info, "#!/bin/sh\necho 'Gradle 8.9'\n")
PAS_TEST_GRADLE_DISTRIBUTION
    mock_gradle_sha="$(sha256sum "$mock_gradle_zip" | awk '{print $1}')"
    cat > "$mock_bin/curl" <<'PAS_TEST_MOCK_CURL'
#!/bin/sh
url=""
output=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        -o) output="$2"; shift 2; continue ;;
        https://*) url="$1" ;;
    esac
    shift
done
printf '%s\n' "$url" >> "$MOCK_GRADLE_REQUEST_LOG"
case "$url" in
    *.sha256) printf '%s  mock-gradle.zip\n' "$MOCK_GRADLE_SHA" > "$output" ;;
    *) cp "$MOCK_GRADLE_ZIP" "$output" ;;
esac
PAS_TEST_MOCK_CURL
    chmod +x "$mock_bin/curl"
    export MOCK_GRADLE_SHA="$mock_gradle_sha"
    export MOCK_GRADLE_ZIP="$mock_gradle_zip"
    export MOCK_GRADLE_REQUEST_LOG="$mock_request_log"
    HOME="$bootstrap_home"
    PATH="$mock_bin:$saved_path"
    if pas_select_gradle_launcher "$incomplete_wrapper_project" "$incomplete_wrapper_project" &&
        [[ "$PAS_GRADLE_KIND" == "bootstrapped-wrapper-version" ]] &&
        [[ "$PAS_GRADLE_DISTRIBUTION_TYPE" == "all" ]] &&
        [[ -x "$PAS_GRADLE_LAUNCHER" ]] &&
        "$PAS_GRADLE_LAUNCHER" --version | grep -q "Gradle 8.9" &&
        grep -q 'gradle-8.9-all.zip$' "$mock_request_log" &&
        grep -q 'gradle-8.9-all.zip.sha256$' "$mock_request_log"; then
        test_pass "incomplete wrapper bootstraps its declared all distribution after SHA-256 verification"
    else
        PATH="$saved_path"
        HOME="$saved_home"
        printf 'FAIL: verified Gradle wrapper distribution bootstrap did not complete\n' >&2
        return 1
    fi
    unset MOCK_GRADLE_SHA MOCK_GRADLE_ZIP MOCK_GRADLE_REQUEST_LOG
    HOME="$saved_home"
    PATH="$saved_path"
    plain_gradle_project="$test_root/project without wrapper"
    mkdir -p "$plain_gradle_project"
    : > "$plain_gradle_project/settings.gradle"
    PATH="$fake_bin:$saved_path"
    if pas_select_gradle_launcher "$plain_gradle_project" "$plain_gradle_project" &&
        [[ "$PAS_GRADLE_KIND" == "system" && "$PAS_GRADLE_LAUNCHER" == "$fake_bin/gradle" ]]; then
        test_pass "supported installed Gradle 8+ is used when wrapper metadata is absent"
    else
        PATH="$saved_path"
        HOME="$saved_home"
        printf 'FAIL: supported system Gradle fallback was not selected\n' >&2
        return 1
    fi

    HOME="$cache_home"
    cached_gradle="$cache_home/.local/share/pocket-apk-studio/gradle-9.3.1/bin/gradle"
    mkdir -p "$(dirname "$cached_gradle")"
    printf '#!/bin/sh\necho "Gradle 9.3.1"\n' > "$cached_gradle"
    chmod +x "$cached_gradle"
    mkdir -p "$test_root/empty-bin"
    PATH="$test_root/empty-bin"
    if pas_select_gradle_launcher "$plain_gradle_project" "$plain_gradle_project" &&
        [[ "$PAS_GRADLE_KIND" == "cached-default" ]] &&
        [[ "$PAS_GRADLE_LAUNCHER" == "$cached_gradle" ]]; then
        test_pass "project without wrapper can use cached pinned Gradle 9.3.1"
    else
        PATH="$saved_path"
        HOME="$saved_home"
        printf 'FAIL: cached Gradle 9.3.1 fallback was not selected\n' >&2
        return 1
    fi

    HOME="$test_root/empty home"
    PATH="$saved_path"
    mkdir -p "$HOME"
    PATH="$test_root/empty-bin"
    PAS_DISABLE_GRADLE_BOOTSTRAP=1
    if pas_select_gradle_launcher "$plain_gradle_project" "$plain_gradle_project"; then
        PATH="$saved_path"
        HOME="$saved_home"
        printf 'FAIL: Gradle selection succeeded without wrapper, system Gradle or cache\n' >&2
        return 1
    else
        test_pass "missing wrapper, system Gradle and cache fail clearly without network access"
    fi
    unset PAS_DISABLE_GRADLE_BOOTSTRAP
    HOME="$saved_home"
    PATH="$saved_path"

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

    # Detect API/build-tools requirements across Kotlin DSL, Groovy DSL and version catalogs.
    sdk_project="$test_root/SDK Project With Spaces"
    mkdir -p "$sdk_project/app" "$sdk_project/legacy" "$sdk_project/catalog" "$sdk_project/gradle"
    cat > "$sdk_project/app/build.gradle.kts" <<'PAS_TEST_SDK_KTS'
plugins { id("com.android.application") }
android { compileSdk = 36 }
PAS_TEST_SDK_KTS
    cat > "$sdk_project/legacy/build.gradle" <<'PAS_TEST_SDK_GROOVY'
android {
    compileSdkVersion 34
    buildToolsVersion "34.0.0"
}
PAS_TEST_SDK_GROOVY
    cat > "$sdk_project/catalog/build.gradle.kts" <<'PAS_TEST_SDK_CATALOG_MODULE'
android { compileSdk = libs.versions.androidCompileSdk.get().toInt() }
PAS_TEST_SDK_CATALOG_MODULE
    cat > "$sdk_project/gradle/libs.versions.toml" <<'PAS_TEST_SDK_TOML'
[versions]
androidCompileSdk = "33"
androidBuildTools = "33.0.2"
PAS_TEST_SDK_TOML
    pas_detect_android_sdk_requirements "$sdk_project"
    if [[ " ${PAS_REQUIRED_COMPILE_SDKS[*]} " == *" 36 "* ]] &&
        [[ " ${PAS_REQUIRED_COMPILE_SDKS[*]} " == *" 34 "* ]] &&
        [[ " ${PAS_REQUIRED_COMPILE_SDKS[*]} " == *" 33 "* ]] &&
        [[ " ${PAS_REQUIRED_BUILD_TOOLS[*]} " == *" 34.0.0 "* ]] &&
        [[ " ${PAS_REQUIRED_BUILD_TOOLS[*]} " == *" 36.0.0 "* ]] &&
        [[ " ${PAS_REQUIRED_BUILD_TOOLS[*]} " == *" 33.0.2 "* ]]; then
        test_pass "compileSdk detection supports Kotlin DSL, Groovy DSL and version-catalog API values"
    else
        printf 'FAIL: SDK requirements are incomplete: platforms=[%s] build-tools=[%s]\n' \
            "${PAS_REQUIRED_COMPILE_SDKS[*]}" "${PAS_REQUIRED_BUILD_TOOLS[*]}" >&2
        return 1
    fi

    # Exercise package installation with an offline sdkmanager stub and spaced paths.
    sdk_root_fixture="$test_root/Android SDK With Spaces"
    sdkmanager_fixture="$test_root/Mock SDK Manager Path/sdkmanager"
    sdk_request_log="$test_root/sdkmanager-requests.log"
    mkdir -p "$(dirname "$sdkmanager_fixture")"
    cat > "$sdkmanager_fixture" <<'PAS_TEST_SDKMANAGER'
#!/usr/bin/env bash
set -Eeuo pipefail
sdk_root=""
for argument in "$@"; do
    case "$argument" in
        --sdk_root=*) sdk_root="${argument#*=}" ;;
    esac
done
printf '%s\n' "$*" >> "$MOCK_SDK_REQUEST_LOG"
for argument in "$@"; do
    case "$argument" in
        "platforms;android-36")
            mkdir -p "$sdk_root/platforms/android-36"
            printf 'android-36' > "$sdk_root/platforms/android-36/android.jar"
            ;;
        "platform-tools")
            mkdir -p "$sdk_root/platform-tools"
            printf '#!/bin/sh\nexit 0\n' > "$sdk_root/platform-tools/adb"
            chmod +x "$sdk_root/platform-tools/adb"
            ;;
        "build-tools;36.0.0")
            mkdir -p "$sdk_root/build-tools/36.0.0"
            printf '#!/bin/sh\nexit 0\n' > "$sdk_root/build-tools/36.0.0/aapt"
            chmod +x "$sdk_root/build-tools/36.0.0/aapt"
            ;;
    esac
done
PAS_TEST_SDKMANAGER
    chmod +x "$sdkmanager_fixture"
    export MOCK_SDK_REQUEST_LOG="$sdk_request_log"
    ANDROID_SDK_MANAGER="$sdkmanager_fixture"
    PAS_REQUIRED_COMPILE_SDKS=(36)
    PAS_REQUIRED_BUILD_TOOLS=(36.0.0)
    if pas_ensure_android_sdk "$sdk_root_fixture" &&
        [[ -s "$sdk_root_fixture/platforms/android-36/android.jar" ]] &&
        [[ -x "$sdk_root_fixture/platform-tools/adb" ]] &&
        [[ -x "$sdk_root_fixture/build-tools/36.0.0/aapt" ]] &&
        grep -q 'platforms;android-36' "$sdk_request_log" &&
        grep -q 'build-tools;36.0.0' "$sdk_request_log" &&
        grep -q 'platform-tools' "$sdk_request_log"; then
        test_pass "sdkmanager installs API 36, platform-tools and build-tools with spaces in SDK paths"
    else
        printf 'FAIL: sdkmanager did not install and verify all requested SDK packages\n' >&2
        return 1
    fi
    unset MOCK_SDK_REQUEST_LOG ANDROID_SDK_MANAGER

    # Re-check a fully provisioned SDK without requiring sdkmanager to remain resolvable.
    if pas_ensure_android_sdk "$sdk_root_fixture" && [[ -z "$PAS_ANDROID_SDKMANAGER" ]]; then
        test_pass "already-installed SDK components pass without a resolvable sdkmanager"
    else
        printf 'FAIL: an already-installed Android SDK still required sdkmanager\n' >&2
        return 1
    fi

    # Missing packages and no sdkmanager must fail clearly without network access.
    missing_sdk_root="$test_root/SDK Without Command Line Tools"
    mkdir -p "$missing_sdk_root" "$test_root/no-sdkmanager-bin"
    ANDROID_SDK_MANAGER="$test_root/missing-sdkmanager"
    PATH="$test_root/no-sdkmanager-bin"
    if pas_ensure_android_sdk "$missing_sdk_root"; then
        PATH="$saved_path"
        printf 'FAIL: missing sdkmanager was reported as a successful SDK installation\n' >&2
        return 1
    else
        test_pass "missing SDK components and missing sdkmanager produce a detectable failure"
    fi
    PATH="$saved_path"
    unset ANDROID_SDK_MANAGER

    fake_apk="$test_root/Android Project With Spaces/app/build/outputs/apk/debug/app-debug.apk"
    flavored_apk="$test_root/Android Project With Spaces/app/build/outputs/apk/free/debug/app-free-debug.apk"
    multi_flavored_apk="$test_root/Android Project With Spaces/app/build/outputs/apk/demo/google/debug/app-demo-google-debug.apk"
    release_apk="$test_root/Android Project With Spaces/app/build/outputs/apk/free/release/app-free-release.apk"
    mkdir -p "$(dirname "$fake_apk")" "$(dirname "$flavored_apk")" "$(dirname "$multi_flavored_apk")" "$(dirname "$release_apk")"
    printf "fake-apk-content" > "$fake_apk"
    printf "flavored-apk-content" > "$flavored_apk"
    printf "multi-flavored-apk-content" > "$multi_flavored_apk"
    printf "release-apk-content" > "$release_apk"
    pas_find_apks "$test_root/Android Project With Spaces"
    found_direct=0
    found_flavored=0
    found_multi_flavored=0
    found_release=0
    for discovered_apk in "${PAS_APK_FILES[@]}"; do
        [[ "$discovered_apk" == "$fake_apk" ]] && found_direct=1
        [[ "$discovered_apk" == "$flavored_apk" ]] && found_flavored=1
        [[ "$discovered_apk" == "$multi_flavored_apk" ]] && found_multi_flavored=1
        [[ "$discovered_apk" == "$release_apk" ]] && found_release=1
    done
    if [[ "${#PAS_APK_FILES[@]}" -eq 3 ]] &&
        [[ "$found_direct" -eq 1 ]] &&
        [[ "$found_flavored" -eq 1 ]] &&
        [[ "$found_multi_flavored" -eq 1 ]] &&
        [[ "$found_release" -eq 0 ]]; then
        test_pass "debug APK discovery supports zero, one or multiple flavor dimensions, excludes release builds and handles paths with spaces"
    else
        printf "FAIL: APK discovery returned %s candidates (direct=%s flavored=%s multi_flavored=%s release=%s)\n" "${#PAS_APK_FILES[@]}" "$found_direct" "$found_flavored" "$found_multi_flavored" "$found_release" >&2
        return 1
    fi

    output_apk="$test_root/Downloads With Spaces/Pocket APK Studio Build.apk"
    mkdir -p "$(dirname "$output_apk")"
    printf 'old placeholder' > "$output_apk"
    if pas_copy_apk_to_output "$fake_apk" "$output_apk" &&
        cmp -s "$fake_apk" "$output_apk"; then
        test_pass "APK copying overwrites an app-created placeholder and preserves spaced paths"
    else
        printf 'FAIL: APK copy to registered output path failed\n' >&2
        return 1
    fi

    printf 'SELF_TESTS_PASSED=%s\n' "$test_count"
)

pas_ubuntu_main() {
    local zip_name="${1-}"
    local output_name="${2-}"
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
    local apk
    local apk_name
    local output_dir="/mnt/shared/Download/PocketAPKStudio/Builds"
    local apk_count=0
    local candidate
    local -a build_files=()

    trap 'rc=$?; printf "UBUNTU_BUILD_EXIT=%s\n" "$rc"' EXIT
    export PS4='+[${EPOCHREALTIME}] ${BASH_SOURCE##*/}:${LINENO}: '
    set -x

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
    case "$output_name" in
        ""|*/*|*[!A-Za-z0-9._-]*) pas_fail 25 "Invalid reserved APK output file name." ;;
        *.apk) ;;
        *) pas_fail 25 "Reserved APK output file must end in .apk." ;;
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
    export PATH="$ANDROID_HOME/platform-tools:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
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

    pas_detect_android_sdk_requirements "$gradle_root" ||
        pas_fail 41 "Could not inspect the imported project for Android SDK requirements."
    pas_ensure_android_sdk "$android_sdk" ||
        pas_fail 42 "Required Android SDK platforms/build-tools could not be installed or verified. Review SDKMANAGER messages above."

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

    pas_log "GRADLE_ROOT=$gradle_root"
    if ! pas_select_gradle_launcher "$project" "$gradle_root"; then
        pas_fail 12 "No usable Gradle wrapper, compatible cached/system Gradle or verified Gradle distribution could be selected. Check preceding Gradle bootstrap and network/checksum messages."
    fi
    if [[ "$PAS_GRADLE_KIND" == "wrapper" ]]; then
        chmod +x "$PAS_GRADLE_LAUNCHER" ||
            pas_fail 40 "Could not make the Gradle wrapper executable: $PAS_GRADLE_LAUNCHER"
        pas_log "GRADLE_LAUNCHER=$PAS_GRADLE_LAUNCHER (complete wrapper)"
        "$PAS_GRADLE_LAUNCHER" -p "$gradle_root" --no-daemon --max-workers=2 --console=plain --info assembleDebug
    else
        pas_log "GRADLE_LAUNCHER=$PAS_GRADLE_LAUNCHER ($PAS_GRADLE_KIND)"
        "$PAS_GRADLE_LAUNCHER" --no-daemon --max-workers=2 --console=plain --info -p "$gradle_root" assembleDebug
    fi

    mkdir -p "$output_dir"
    pas_find_apks "$gradle_root" || pas_fail 16 "Could not scan Gradle outputs for debug APK files."
    (( ${#PAS_APK_FILES[@]} > 0 )) ||
        pas_fail 16 "Gradle returned success, but no non-empty debug APK was found under build/outputs/apk/debug."

    # Keep each normal variant APK filename in shared Downloads for direct access.
    for apk in "${PAS_APK_FILES[@]}"; do
        apk_name="$(basename "$apk")"
        cp -f -- "$apk" "$output_dir/$apk_name" ||
            pas_fail 41 "Could not copy APK to shared Downloads: $apk_name"
        [[ -s "$output_dir/$apk_name" ]] ||
            pas_fail 42 "Copied APK is empty: $output_dir/$apk_name"
        apk_count=$((apk_count + 1))
    done

    # Prefer any discovered debug variant in the conventional app module.
    # Flavor dimensions can add more than one directory before the final debug/.
    apk=""
    for candidate in "${PAS_APK_FILES[@]}"; do
        if [[ "$candidate" == "$gradle_root/app/"* ]]; then
            apk="$candidate"
            break
        fi
    done
    [[ -n "$apk" ]] || apk="${PAS_APK_FILES[0]}"
    pas_copy_apk_to_output "$apk" "$output_dir/$output_name" ||
        pas_fail 44 "Could not copy a verified APK into the reserved Downloads output: $output_name"
    echo "APK_SOURCE=$apk"
    echo "APK_SAVED=/storage/emulated/0/Download/PocketAPKStudio/Builds/$output_name"

    pas_log "BUILD_SUCCESS=1"
    pas_log "BUILD_SUCCESS: $apk_count debug APK file(s) copied; primary APK registered as $output_name."

}

pas_termux_main() {
    local zip_name="${1-}"
    local output_name="${2-}"
    local shared="/storage/emulated/0"
    local build_dir="$shared/Download/PocketAPKStudio"
    local live_log="$build_dir/build-live.log"
    local engine_in_shared="$build_dir/build-project.sh"

    trap 'rc=$?; printf "BUILD_ENGINE_EXIT=%s\n" "$rc"' EXIT

    mkdir -p "$build_dir" || pas_fail 20 "Could not create Downloads/PocketAPKStudio."
    : > "$live_log" || pas_fail 20 "Could not create the live build log: $live_log"
    exec > >(tee -a "$live_log") 2>&1
    export PS4='+[${EPOCHREALTIME}] ${BASH_SOURCE##*/}:${LINENO}: '
    set -x

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
    case "$output_name" in
        ""|*/*|*[!A-Za-z0-9._-]*) pas_fail 25 "Invalid reserved APK output file name." ;;
        *.apk) ;;
        *) pas_fail 25 "Reserved APK output file must end in .apk." ;;
    esac
    [[ -r "$build_dir/$zip_name" ]] ||
        pas_fail 25 "Imported project ZIP is not readable in Termux shared storage: $build_dir/$zip_name"
    pas_require_command proot-distro ||
        pas_fail 10 "proot-distro is missing in Termux. Install it and set up the Ubuntu distribution."

    pas_log "TERMUX_ZIP=$build_dir/$zip_name"
    pas_log "PROOT_BIND=$shared:/mnt/shared"
    proot-distro login --bind "$shared:/mnt/shared" ubuntu -- \
        bash /mnt/shared/Download/PocketAPKStudio/build-project.sh --ubuntu "$zip_name" "$output_name" ||
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
