#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
if ! command -v python3 >/dev/null 2>&1; then
    if [[ $(id -u) != 0 || ! -f /etc/os-release ]]; then
        echo 'Run on a supported Ubuntu/Debian server: sudo bash setup.sh' >&2
        exit 1
    fi
    . /etc/os-release
    case "${ID:-}:${VERSION_ID:-}" in
        ubuntu:22.04|ubuntu:24.04|ubuntu:26.04|ubuntu:26.10|debian:12|debian:13) ;;
        *) echo 'Automatic setup supports Ubuntu 22.04/24.04/26.04/26.10 and Debian 12/13 only.' >&2; exit 1 ;;
    esac
    read -r -p 'Install Python3 for the setup panel? [y/N] ' answer
    [[ $answer == y || $answer == Y ]] || exit 1
    apt-get update
    apt-get install -y python3
fi
export PYTHONUTF8=1
exec python3 scripts/setup-server.py "$@"
