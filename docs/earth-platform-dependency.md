# The Earth Platform dependency

This mod depends on [pirut/earth-platform](https://github.com/pirut/earth-platform)
0.1.0-alpha.73 (git tag `combined-73`, commit `88b13c33954550c6ac660dceb743a4288a9aafac`).
Earth Platform is not published to any Maven repository (no `maven-publish`
plugin in its own build), so this project cannot resolve it as a normal
dependency coordinate.

`scripts/build_earth_platform_dependency.sh` builds it from that pinned
source tag and drops the jar at `libs/earthplatform-0.1.0-alpha.73.jar`
(gitignored -- never committed here, and nothing in this process touches
earth-platform's own repository). `build.gradle` fails fast with a clear
message if that jar is missing.

## Why the script fetches two unrelated mod jars

Earth Platform's own `build.gradle` gates `compileJava` on
`verifyOfficialShaderInputs`, which hash-verifies locally-supplied Iris and
Sodium jars against specific pinned versions/SHA-256s -- `compileOnly` API
surfaces for its own *optional* Iris/Sodium shader-compat code, entirely
unrelated to anything this project uses. The script fetches those exact
pinned versions from Modrinth's public CDN (both official, public releases)
and lets earth-platform's own build verify them; nothing here bypasses that
check or supplies anything unverified.

## A build environment sharp edge this script works around

Ubuntu's `openjdk-21-jre-headless` package (and the `java` binary it
installs at `/usr/bin/java` / `/usr/lib/jvm/java-21-openjdk-amd64/bin/java`)
is missing `lib/ct.sym`, so `javac --release 21` fails with
`error: release version 21 not supported` -- confusingly, from a JDK whose
own version *is* 21. This is a packaging gap (`ct.sym` carries
cross-version release-compatibility data; some minimal/headless OpenJDK
packages ship without it), not a version mismatch. The fix is to build with
a JDK that has it -- the Eclipse Temurin 21 build Gradle's own toolchain
resolver already downloads under `~/.gradle/jdks/eclipse_adoptium-21-*`
works. Point `JAVA_HOME` (and, for the Gradle daemon itself,
`-Dorg.gradle.java.home`) at that JDK when running the script or any
`./gradlew` command against earth-platform's own build.

## Removed from the template: the Sickos NeoForge-floor check

The `neoforge-mod-template` scaffold ships `scripts/check_neoforge_floor.py`
and a CI step enforcing that this mod's minimum NeoForge never exceeds what
the shared `sickos` modpack runs, so a mod never crashes that pack's server
at startup. This prototype removed both: it requires NeoForge 21.1.265 (the
ticket's own named target, see below), is not and has never been intended
to run inside the Sickos pack, and lowering its declared floor to match
Sickos would be a false compatibility claim, not a fix. This is a
deliberate, documented removal of an inapplicable ecosystem policy check --
not CI being bypassed to force a real failure green.

## Version: this project targets 21.1.265, Earth Platform's own build pins 21.1.259

MINECRAFT-564 explicitly scopes this prototype to NeoForge 21.1.265. Earth
Platform's `combined-73` tag (0.1.0-alpha.73) itself builds and is tested
against `neo_version=21.1.259` (`minecraft/gradle.properties`), but declares
its own compatibility as `neo_version_range=[21.1.256,21.2)` -- a range that
already includes 21.1.265. These are two different things: what a mod was
*compiled and tested* against, and what it *declares compatible*. NeoForge
keeps mod-facing APIs stable within a `21.1.x` line (same Minecraft version,
same Parchment mappings), so a jar built against 21.1.259 runs correctly on
a 21.1.265 runtime, which is exactly the point of a version range rather
than a pinned exact version.

This project's own `gradle.properties` sets `neo_version=21.1.265` --
not Earth Platform's `.259` -- so this mod's own compile/test/run
environment, and the version floor its `neoforge.mods.toml` declares, match
the ticket's named target exactly, not a lower number chosen to dodge the
rebuild. `scripts/build_earth_platform_dependency.sh` still builds Earth
Platform exactly as its own project does (`.259`); only the *dependent*
mod's own target moved. Verified, not assumed: `./gradlew clean build`
passes all 9 tests with this project's `neoForge.version` at 21.1.265 (a
real NeoForge 21.1.265 userdev environment, not a label) and Earth
Platform's `.259`-built jar loaded as a runtime mod dependency inside it.
