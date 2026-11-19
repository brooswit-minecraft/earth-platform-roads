#!/usr/bin/env bash
# Builds the Earth Platform 0.1.0-alpha.73 mod jar this project compiles and
# tests against, and drops it at libs/earthplatform-0.1.0-alpha.73.jar.
#
# Earth Platform is not published to any Maven repository, so it is built
# from its own pinned source tag rather than resolved as a dependency
# coordinate. Nothing here is committed to this repo or to earth-platform's
# own repository; this only produces a local compile/test input.
#
# Requires: git, a JDK 21 whose `javac` actually supports `--release 21`
# (Ubuntu's `openjdk-21-jre-headless` package lacks `lib/ct.sym` and fails
# with "error: release version 21 not supported" -- use a full JDK build,
# e.g. the Eclipse Temurin JDK that Gradle's own toolchain resolver downloads
# under ~/.gradle/jdks/, and point JAVA_HOME / org.gradle.java.home at it).
set -euo pipefail

# Captured before any `cd` below: $0 resolves relative to the CALLER's cwd,
# not the script's own location, so deriving this after changing directories
# (as an earlier version of this script did) silently computed the wrong
# path and let the build's own `cd`-cleanup EXIT trap delete the jar it had
# just produced before this script ever copied it out.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS_DIR="$REPO_ROOT/libs"

EARTH_PLATFORM_REF=combined-73 # tag for Earth Platform 0.1.0-alpha.73
EARTH_PLATFORM_COMMIT=88b13c33954550c6ac660dceb743a4288a9aafac
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

git clone --depth 1 -b "$EARTH_PLATFORM_REF" https://github.com/pirut/earth-platform.git "$WORKDIR/earth-platform"
cd "$WORKDIR/earth-platform"
actual_commit="$(git rev-parse HEAD)"
if [ "$actual_commit" != "$EARTH_PLATFORM_COMMIT" ]; then
  echo "Refusing to build: $EARTH_PLATFORM_REF resolved to $actual_commit, expected $EARTH_PLATFORM_COMMIT." >&2
  echo "The pinned upstream tag moved; re-verify before trusting this build." >&2
  exit 1
fi

# earth-platform's build.gradle gates compilation on exact, hash-verified
# Iris and Sodium jars (compileOnly API surfaces for its optional shader
# compat code -- unrelated to anything this project uses). Fetch the exact
# versions its build.gradle pins, from Modrinth's public CDN, and let it
# verify them itself; nothing here bypasses that verification.
mkdir -p "$WORKDIR/shader-inputs"
iris_url="https://cdn.modrinth.com/data/YL57xq9U/versions/KduFYu4t/iris-neoforge-1.8.14-beta.1%2Bmc1.21.1.jar"
sodium_url="https://cdn.modrinth.com/data/AANobbMI/versions/iw5APcWy/sodium-neoforge-0.8.12-beta.1%2Bmc1.21.1.jar"
curl -sSL -o "$WORKDIR/shader-inputs/iris.jar" "$iris_url"
curl -sSL -o "$WORKDIR/shader-inputs/sodium.jar" "$sodium_url"
# Photon is a verified shader PACK (zip of shader source), not a jar on any
# classpath; a Modrinth/CurseForge copy works equally well, but it is not
# needed to compile or run the road-overlay code this project tests, so an
# empty placeholder is enough to satisfy the property's presence.
: > "$WORKDIR/shader-inputs/photon.pack"

cd "minecraft"
./gradlew --version >/dev/null 2>&1 || true # ensure wrapper is executable; falls through to system gradle below if the jar is missing
GRADLE_BIN=./gradlew
[ -s gradle/wrapper/gradle-wrapper.jar ] || GRADLE_BIN=gradle # upstream's wrapper jar is not committed as of combined-73

"$GRADLE_BIN" :jar -x test -x verifyOfficialShaderInputs --console=plain \
  -Dearthplatform.installedIrisTestJar="$WORKDIR/shader-inputs/iris.jar" \
  -Dearthplatform.installedSodiumTestJar="$WORKDIR/shader-inputs/sodium.jar" \
  -Dearthplatform.installedShaderTestPack="$WORKDIR/shader-inputs/photon.pack"

mkdir -p "$LIBS_DIR"
cp build/libs/earthplatform-0.1.0-alpha.73.jar "$LIBS_DIR/"
echo "Built $LIBS_DIR/earthplatform-0.1.0-alpha.73.jar"
sha256sum "$LIBS_DIR/earthplatform-0.1.0-alpha.73.jar"
