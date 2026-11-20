# earth-platform-roads

Blackstone road surfaces mapped from OpenStreetMap onto Earth Platform's generated terrain.

NeoForge 1.21.1, Java 21. Mod ID: `earthplatformroads`. MIT licensed.

## What this mod does

Overlays mapped OpenStreetMap road centerlines onto
[Earth Platform](https://github.com/pirut/earth-platform) 0.1.0-alpha.73's
cube generation: columns within a class-derived half-width of a mapped way's
centerline generate a continuous polished-blackstone surface, at-grade, with
vegetation suppressed; every other column is untouched. On an incline, the
top surface uses a [Layered Slopes](https://github.com/brooswit-minecraft/layered-slopes)
compat block with sub-block height instead of always a full block (see
`docs/design.md`'s MINECRAFT-566 section). See `docs/design.md` for why this
is a Mixin and exactly where it hooks in, and `docs/earth-platform-dependency.md`
for how to build Earth Platform as a local compile/test dependency (it is
not published anywhere; Layered Slopes is, via GitHub releases --
`scripts/fetch_layered_slopes_dependency.sh`).

Road data is installed, not hard-coded, from two sources using the same
versioned format: bundled regions baked into the jar
(`data/earthplatformroads/roads/regions/*.json`, listed by `regions/index.json`)
and external regions -- every `*.json` file dropped directly into
`config/earthplatformroads/roads/regions/` in a real install, no index file
needed there, no Java change, no mod rebuild. This is literal: mapped
coverage for a new destination is installing a region file, not this mod
shipping an ever-bigger bundled extract (see `docs/design.md`'s MINECRAFT-566
section, and `RoadNetworkExternalRegionTest` for proof against a genuinely
unbundled destination). As of this writing only Yosemite Valley's original
pinned Southside Drive extract (~190m, one way) is bundled by default.

**Explicit current milestone boundary** (not an open gap -- deferred by
MINECRAFT-566's own milestone update): bridges and tunnels are tracked in the
data model but never rendered as a crossing (no surface is drawn for them at
all, rather than an incorrect at-grade one); correct rendering is a following
milestone. **Explicit current gaps**: the layering heuristic
(`RoadSurfaceLayering`) is verified against real generated data in tests but
has not been verified in an actual playtest. See the MINECRAFT-566 ticket for
the current, up-to-date status.

## Building

Requires a JDK 21 whose `javac` actually supports `--release 21` (see the
note in `scripts/build_earth_platform_dependency.sh` -- Ubuntu's
`openjdk-21-jre-headless` package does not).

```sh
scripts/build_earth_platform_dependency.sh   # once, to produce libs/earthplatform-0.1.0-alpha.73.jar
./gradlew build                              # compiles, then runs both test suites below
```

## Verifying the prototype actually generates blackstone

`src/test/java/.../road/RoadGenerationTest.java` constructs a real
`EarthGenerator` (no saved world, no hand placement) for a brand-new Yosemite
Earth world, generates the real cubes at and around the pinned road, and
reads back actual block states:

```sh
./gradlew test --tests '*RoadGenerationTest'
```

This needs network access to fetch real elevation tiles (the same AWS
Terrarium DEM Earth Platform always reads ground height from -- fetching it
is how its own test suite, e.g. `DataRulesBench`, verifies real terrain
too). The mod's own road-overlay code never makes a network request; see
`docs/design.md`.

`RoadSegmentTest` is the fast, no-bootstrap unit test of the pure geometry
(point-to-segment distance, half-width boundary) and runs in any JVM.

`RoadGenerationTest` also covers: the road column's ground height is above
vanilla's Y 320 build limit and still generates as blackstone there;
adjacent columns straddling a cube (16-block) boundary are both road
surface, with no gap or misalignment at the seam; and regenerating the same
cube, or generating its neighbours first, is deterministic and does not
change the road's surface.

## Releasing

Every PR that touches `src/` must add exactly one fragment under
`changelog.d/` (see `changelog.d/README.md`). Pushing to `main` with new
fragments tags a GitHub release automatically; nothing is committed back by
hand.
