#!/usr/bin/env bash
# Downloads the published Layered Slopes 0.2.0 mod jar this project compiles and tests against,
# verifies its checksum, and drops it at libs/layeredslopes-0.2.0.jar.
#
# Unlike Earth Platform, Layered Slopes publishes a built jar as a GitHub release asset, so this
# is a plain pinned download + checksum rather than a from-source build.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS_DIR="$REPO_ROOT/libs"

LAYERED_SLOPES_VERSION=0.2.0
LAYERED_SLOPES_URL="https://github.com/brooswit-minecraft/layered-slopes/releases/download/v${LAYERED_SLOPES_VERSION}/layeredslopes-${LAYERED_SLOPES_VERSION}.jar"
LAYERED_SLOPES_SHA256=0d6ef73152d99e9de1278f272fe1f40fec039a2b1d31d32bd38af7e0f79b2878

mkdir -p "$LIBS_DIR"
DEST="$LIBS_DIR/layeredslopes-${LAYERED_SLOPES_VERSION}.jar"
curl -sSL -o "$DEST" "$LAYERED_SLOPES_URL"

actual_sha256="$(sha256sum "$DEST" | awk '{print $1}')"
if [ "$actual_sha256" != "$LAYERED_SLOPES_SHA256" ]; then
  echo "Refusing to use $DEST: sha256 $actual_sha256, expected $LAYERED_SLOPES_SHA256." >&2
  echo "The pinned release asset changed; re-verify before trusting this download." >&2
  rm -f "$DEST"
  exit 1
fi

echo "Fetched $DEST"
sha256sum "$DEST"
