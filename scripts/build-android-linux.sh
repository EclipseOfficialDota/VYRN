#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

if [[ ${1:-} == --help ]]; then
    echo 'Usage: bash scripts/build-android-linux.sh [--install-tools] -ApiUrl https://api.your-domain.com'
    echo '--install-tools: install Ubuntu 24.04 x86_64 build dependencies; SDK licenses require your acceptance.'
    exit 0
fi
if [[ $(uname -m) != x86_64 ]]; then
    echo 'Android native builds require an x86_64 Linux build host.' >&2
    exit 1
fi
if [[ ${1:-} == --install-tools ]]; then
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
if [[ ! -x $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager ]]; then
    temp=$(mktemp -d)
    trap 'rm -rf -- "$temp"' EXIT
    curl --fail --location --retry 3 --connect-timeout 30 --proto '=https' https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o "$temp/sdk.zip"
    echo "7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee  $temp/sdk.zip" | sha256sum --check
    unzip -q "$temp/sdk.zip" -d "$temp"
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
