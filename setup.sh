#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
if ! command -v python3 >/dev/null 2>&1; then
    if [[ $(id -u) != 0 || ! -f /etc/os-release ]]; then
        echo 'Run on Ubuntu24.04: sudo bash setup.sh' >&2
        exit 1
    fi
    . /etc/os-release
    if [[ ${ID:-} != ubuntu || ${VERSION_ID:-} != 24.04 ]]; then
        echo 'Automatic setup supports Ubuntu24.04 only.' >&2
        exit 1
    fi
    read -r -p 'Install Python3 for the setup panel? [y/N] ' answer
    [[ $answer == y || $answer == Y ]] || exit 1
    apt-get update
    apt-get install -y python3
fi
export PYTHONUTF8=1
exec python3 scripts/setup-server.py "$@"
