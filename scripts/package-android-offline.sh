#!/usr/bin/env bash
# Packaging only; the caller must validate the cache before invoking this helper.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
mkdir -p .local/offline
kit="$PWD/.local/offline/vyrn-android-offline.tar.gz"
[[ ! -e $kit ]] || { echo 'Existing private kit preserved; move it before preparing another.' >&2; exit 1; }
git rev-parse HEAD > .local/offline/source-commit.txt
# Preserve source directories named build (especially NDK/build and libvpx/build).
tar --exclude='android/upstream/TMessagesProj/build' \
    --exclude='android/upstream/TMessagesProj/.cxx' \
    --exclude='android/upstream/build' --exclude='*/.gradle' \
    -czf "$kit.part" .tools/android-sdk .local/gradle/caches .local/gradle/wrapper \
    android/upstream .local/offline/source-commit.txt
mv -- "$kit.part" "$kit"
(cd .local/offline && sha256sum vyrn-android-offline.tar.gz > vyrn-android-offline.tar.gz.sha256)
echo "Private build kit: $kit"
