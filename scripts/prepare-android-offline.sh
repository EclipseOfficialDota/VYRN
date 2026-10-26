#!/usr/bin/env bash
# Private, same-platform build kit; never upload SDK binaries to public Releases.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
[[ $(uname -m) == x86_64 && $(uname -s) == Linux ]] || { echo 'Use a connected x86_64 Linux build host.' >&2; exit 1; }
export ANDROID_HOME="$PWD/.tools/android-sdk"
bash scripts/build-android-linux.sh "$@"
# Do not label a populated cache offline-ready before testing without networking.
command -v unshare >/dev/null || { echo 'unshare is needed to verify offline operation.' >&2; exit 1; }
if [[ $EUID == 0 ]]; then
    unshare --net -- bash -c 'ip link set lo up; exec bash scripts/build-android-linux.sh --offline "$@"' -- "$@"
else
    sudo unshare --net -- bash -c 'ip link set lo up; exec runuser -u "$1" -- "${@:2}"' -- "$(id -un)" \
        env "JAVA_HOME=$JAVA_HOME" "PATH=$PATH" "ANDROID_HOME=$ANDROID_HOME" bash scripts/build-android-linux.sh --offline "$@"
fi
mkdir -p .local/offline
kit="$PWD/.local/offline/vyrn-android-offline.tar.gz"
[[ ! -e $kit ]] || { echo 'Existing private kit preserved; move it before preparing another.' >&2; exit 1; }
revision=$(git rev-parse HEAD)
printf '%s\n' "$revision" > .local/offline/source-commit.txt
# Include dependencies and pinned upstream sources, never runtime.env, signing keys,
# account data, personal Gradle properties, daemon logs or APK outputs.
tar --exclude='android/upstream/TMessagesProj/build' \
    --exclude='android/upstream/TMessagesProj/.cxx' \
    --exclude='android/upstream/build' --exclude='*/.gradle' \
    -czf "$kit.part" .tools/android-sdk .local/gradle/caches .local/gradle/wrapper \
    android/upstream .local/offline/source-commit.txt
mv -- "$kit.part" "$kit"
(cd .local/offline && sha256sum vyrn-android-offline.tar.gz > vyrn-android-offline.tar.gz.sha256)
echo "Private build kit: $kit"
echo 'Requires x86_64 Linux with JDK21, PowerShell7, Git, Python3.12+, tar and native build prerequisites already installed.'
