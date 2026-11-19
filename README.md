# earth-platform-roads

Blackstone road surfaces mapped from OpenStreetMap onto Earth Platform's generated terrain.

NeoForge 1.21.1, Java 21. Mod ID: `earthplatformroads`. MIT licensed.

## What this prototype does

Overlays a short, real, pinned OpenStreetMap extract of Yosemite Valley's
Southside Drive onto [Earth Platform](https://github.com/pirut/earth-platform)
0.1.0-alpha.73's cube generation: columns within 3 blocks either side of the
road's centerline (one way, two lanes -- see
`docs/road-data-provenance.md`) generate a continuous polished-blackstone
surface, at-grade, with vegetation suppressed; every other column is
untouched. See `docs/design.md` for why this is a Mixin and exactly where it
hooks in, and `docs/earth-platform-dependency.md` for how to build Earth
Platform as a local compile/test dependency (it is not published anywhere).

**Scope, deliberately**: one ~190m at-grade segment only. No bridges,
tunnels, grade separation, intersections, worldwide rollout, or claims about
driving behaviour -- see MINECRAFT-564.

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
