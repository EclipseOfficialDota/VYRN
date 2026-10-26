#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

offline=false
if [[ ${1:-} == --offline ]]; then
    offline=true
    shift
fi
if [[ -f .local/offline/vyrn-android-offline.tar.gz && ${1:-} != --install-tools ]]; then
    offline=true
    echo 'Local offline kit detected; dependency downloads are disabled.'
fi
if [[ ${1:-} == --help ]]; then
    echo 'Usage: bash scripts/build-android-linux.sh [--offline | --install-tools] -ApiUrl https://api.your-domain.com'
    echo '--install-tools: install Ubuntu 24.04 x86_64 build dependencies; SDK licenses require your acceptance.'
    exit 0
fi
if [[ $(uname -m) != x86_64 ]]; then
    echo 'Android native builds require an x86_64 Linux build host.' >&2
    exit 1
fi
if [[ ${1:-} == --install-tools ]]; then
    [[ $offline == false ]] || { echo 'Offline mode cannot install system packages.' >&2; exit 1; }
    shift
    source /etc/os-release
    if [[ $ID != ubuntu || $VERSION_ID != 24.04 || $EUID != 0 ]]; then
        echo '--install-tools requires root on Ubuntu 24.04. Otherwise install JDK21, PowerShell7 and the Android SDK manually.' >&2
        exit 1
    fi
    apt-get update
    apt-get install -y ca-certificates curl unzip git openjdk-21-jdk-headless build-essential ninja-build pkg-config
    if ! command -v pwsh >/dev/null; then
        package=$(mktemp --suffix=.deb)
        curl --fail --location --proto '=https' https://packages.microsoft.com/config/ubuntu/24.04/packages-microsoft-prod.deb -o "$package"
        dpkg -i "$package"
        rm -- "$package"
        apt-get update
        apt-get install -y powershell
    fi
fi
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
export ANDROID_HOME=${ANDROID_HOME:-"$PWD/.tools/android-sdk"}
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
command -v pwsh >/dev/null || { echo 'Install PowerShell7 first; see docs/ANDROID_TESTING.md.' >&2; exit 1; }
[[ -x $JAVA_HOME/bin/java ]] || { echo 'Install JDK21 and set JAVA_HOME.' >&2; exit 1; }
if [[ $offline == true ]]; then
    if [[ -f .local/offline/vyrn-android-offline.tar.gz && ! -d android/upstream ]]; then
        python3 scripts/restore-android-offline.py
    fi
    for required in 'cmdline-tools/latest/bin/sdkmanager' 'platforms/android-36/android.jar' 'build-tools/36.0.0/aapt2' 'ndk/27.2.12479018/source.properties' 'ndk/27.2.12479018/build/cmake/android.toolchain.cmake' 'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/clang' 'cmake/3.22.1/bin/cmake'; do
        [[ -f $ANDROID_HOME/$required ]] || { echo "Offline SDK package missing: $required. Prepare the full cache on a connected Linux host first." >&2; exit 1; }
    done
    [[ -d android/upstream/.git && -d .local/gradle/caches && -d .local/gradle/wrapper/dists ]] || { echo 'Offline sources or Gradle caches are missing. A command-line-tools ZIP alone is not an offline build kit.' >&2; exit 1; }
    distribution=$(sed -n 's|^distributionUrl=.*/\(gradle-[A-Za-z0-9.-]*\)\.zip$|\1|p' android/upstream/gradle/wrapper/gradle-wrapper.properties)
    if [[ ! $distribution =~ ^gradle-[A-Za-z0-9.-]+$ ]] || ! compgen -G ".local/gradle/wrapper/dists/$distribution/*/$distribution.zip.ok" >/dev/null; then
        echo 'The pinned Gradle distribution is not cached; offline mode will not download it.' >&2
        exit 1
    fi
    pwsh -NoProfile -File scripts/build-android.ps1 -Offline "$@"
    echo "APKs: $PWD/artifacts/"
    exit 0
fi
if [[ ! -x $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager ]]; then
    temp=$(mktemp -d)
    trap 'rm -rf -- "$temp"' EXIT
    archive=${VYRN_ANDROID_TOOLS_ZIP:-"$PWD/.local/android/vyrn-android-tools.zip"}
    # Retain compatibility with archives transferred by earlier installers.
    if [[ ! -f $archive && -z ${VYRN_ANDROID_TOOLS_ZIP:-} ]]; then
        archive="$PWD/.local/android/commandlinetools-linux-13114758_latest.zip"
    fi
    if [[ -f $archive ]]; then
        echo 'Using local Android command-line tools archive.'
    elif [[ -n ${VYRN_ANDROID_TOOLS_ZIP:-} ]]; then
        echo 'VYRN_ANDROID_TOOLS_ZIP does not point to a regular archive file.' >&2
        exit 1
    else
        archive="$temp/sdk.zip"
        if ! curl --fail --location --retry 3 --connect-timeout 30 --proto '=https' https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o "$archive"; then
            echo 'SDK download failed. Place the verified archive in .local/android/commandlinetools-linux-13114758_latest.zip and retry. See docs/ANDROID_TESTING.md.' >&2
            exit 22
        fi
    fi
    echo "7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee  $archive" | sha256sum --check
    unzip -q "$archive" -d "$temp"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    [[ ! -e $ANDROID_HOME/cmdline-tools/latest ]] || { echo 'Existing incomplete SDK preserved; repair it manually.' >&2; exit 1; }
    mv -- "$temp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
"$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses
"$sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools' 'ndk;27.2.12479018' 'cmake;3.22.1'
pwsh -NoProfile -File android/fetch-upstream.ps1
pwsh -NoProfile -File scripts/build-android.ps1 "$@"
echo "APKs: $PWD/artifacts/"
