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
bash scripts/package-android-offline.sh
