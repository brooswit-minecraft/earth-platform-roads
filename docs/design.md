# Design: where the road overlay hooks in, and why

## The seam

`EarthGenerator` (in Earth Platform's `minecraft` module) is `final` and
ships no addon/extension API: `CubicSettings.open()` builds it directly from
world settings, and nothing in the cube-generation path (`ServerCubeWorld`,
`SurfaceGenerator`) fires an event or calls through a registry a dependent
mod could hook. Earth Platform's own compat code for *other* mods (Create,
Sable/Dynamic Immersive Vehicles, Waystones, WorldEdit, ...) is written the
same way it has to be written here, just in the other direction: optional
Mixins into the other mod's classes
(`minecraft/src/main/java/io/earthplatform/minecraft/cubic/mixin/compat/`).
This prototype is the mirror image of that: a required Mixin (Earth
Platform is a hard dependency of this mod, not optional) into
`EarthGenerator`.

The exact target is:

```
protected Surface surface(int x, int z, int y, int water, int slope)
```

(`io.earthplatform.minecraft.cubic.earth.EarthGenerator`, descriptor
`(IIIII)Lio/earthplatform/minecraft/cubic/gen/SurfaceGenerator$Surface;`).
This is the one place a column's *cover* is decided -- top block, under
block, and vegetation eligibility -- and it is called exactly once per
column by both near-cube generation (`SurfaceGenerator.terrain`, via the
per-cube-column cache in `SurfaceGenerator.column`) and far-LOD generation
(through `farSurface`, which this prototype does not touch -- see
"Exclusions" below). `EarthGeneratorRoadMixin` injects at `@At("RETURN")`,
cancellable, and swaps in a blackstone `Surface` with vegetation counts
(`trees`, `grass`, `flowers`) all zeroed when the column falls within the
road's half-width; otherwise it leaves the original `Surface` alone.

Overriding *cover*, not *blocks after the fact*, is the reason a road column
never grows a tree and then has it chopped: it was never eligible to grow
one. It also means caves, ores and rock strata under the road are generated
completely normally -- `SurfaceGenerator.terrain()` only asks `surface()`
for the top few blocks' identity, nothing below that.

## Why ground height is untouched

The mixin never changes `ground()`/ `y`/`water`: a road column keeps Earth
Platform's real elevation. This is deliberately an at-grade-only overlay
(see MINECRAFT-564's scope: "No bridges/tunnels/major or grade-separated
crossings... no reshaping old terrain"). The pinned segment was chosen from
the real OSM way precisely because it is flat valley floor, so this is not
a shortcut around a problem -- there is no appreciable grade to smooth.

## Why the geometry is looked up through the world's own grid, not pre-baked

`EarthGenerator.grid()` and `EarthGenerator.settings()` are both public, so
`RoadSegment.distanceBlocks` projects the pinned WGS84 centerline into block
space through the *actual* `MercatorGrid` of whatever `EarthGenerator`
instance is running, using its real scale
(`EarthSettings.metresPerBlock()`). This still reads only pinned, bundled,
local data at generation time -- it is ordinary trigonometry on an array of
nine lat/lon pairs baked into the jar's resources
(`data/earthplatformroads/roads/southside_drive_yosemite.json`), not a
lookup against anything external -- but it means the overlay is correct for
the real projection in use rather than for one assumed, hard-coded scale.

## Exclusions (deliberate, matching MINECRAFT-564's stated scope)

- **Far-LOD (`farSurface`) is not overridden.** The prototype's acceptance
  is about near-cube generation; "don't claim long-distance visuals" is in
  the ticket description. A road rendered at far LOD is a possible future
  seam (`farSurface` has the identical signature plus a `level` parameter),
  not attempted here.
- **No intersections, bridges, tunnels, or grade changes.** The pinned
  extract is a single OSM way's short run of consecutive nodes with no
  junctions in range.
- **Only Yosemite / the default Earth home.** The pinned centerline is real
  WGS84 coordinates and the projection is computed from the live
  `MercatorGrid`, so this would in principle paint the same lat/lon
  worldwide, but nothing beyond this one segment was fetched, reviewed, or
  tested -- "no worldwide rollout" per the ticket.
- **MINECRAFT-563 coordination.** MINECRAFT-563 (layered-slopes-earth-platform,
  separate task/owner) targets natural-terrain *smoothing*, a different
  extension point (Smooth Slopes' `top_layer_modification` registration,
  per its own ticket) operating on different columns in the general case.
  Both land on the same `EarthGenerator.surface()` seam only where a smoothed
  natural column and a road column would ever be the same (x,z) -- which,
  for this prototype's few dozen road columns on valley-floor land Southside
  Drive already occupies, should not arise. If both mods are ever installed
  together, whichever mixin's `@At("RETURN")` injector runs last wins that
  column; this has not been tested together with MINECRAFT-563's work
  (separate repo, not yet landed) and is called out here rather than assumed
  fine.

## MINECRAFT-566: indexed multi-region network, junctions, and layered surfaces

MINECRAFT-564 above was the scoped proof of concept, not the finished feature; this section
records what MINECRAFT-566 actually changed and, as importantly, what it did not finish.

- **Data model.** `RoadSegment` now carries a stable OSM `wayId`, a `RoadClass` (every mapped
  vehicular `highway=*` value, explicitly excluding foot-only values -- see
  `RoadClass.fromOsmHighway`), the way's real OSM `nodeIds` 1:1 with its centerline, and
  `bridge`/`tunnel`/`layer` flags. `RoadNetwork` buckets every way into a ~1.1km
  latitude/longitude grid cell index so a column lookup only ever walks the handful of ways near
  it (`RoadNetwork.candidatesNear`), not the whole network.
- **Installed data, bundled AND external, same versioned format.** Bundled regions are listed by
  `data/earthplatformroads/roads/regions/index.json` (classpath resources have no directory
  listing). External regions are every `*.json` file found directly under
  `RoadNetwork.externalRegionsDirectory()` -- by default `config/earthplatformroads/roads/regions/`
  in the real game install, a real directory needing no index file. Every region, either source,
  must declare a `schema_version` matching `RoadNetwork.SUPPORTED_SCHEMA_VERSION` or loading fails
  loudly (`RoadNetwork.checkSchemaVersion`). This is MINECRAFT-566's milestone update taken
  literally: John wants mapped coverage to appear "wherever he travels" by installing data, not by
  this mod shipping an ever-bigger bundled extract -- see `RoadNetworkExternalRegionTest`, which
  proves a genuinely unbundled destination (a region file that exists nowhere in this mod's jar)
  loads through the exact same pipeline, alongside bundled Yosemite, with no Java change.
- **Junctions.** Two ways sharing a real OSM node ID are a mapped junction
  (`RoadSegment.sharesJunctionWith`, `RoadNetwork.junctions()`); crossing geometry without a shared
  node is deliberately not reported as one.
- **Bridges and tunnels are tracked but never rendered as a crossing -- and that is now the
  accepted milestone boundary, not an open gap.** `segmentCoveringColumn` skips any way with
  `bridge` or `tunnel` set, so this mixin paints no surface at all for those columns rather than an
  incorrect at-grade crossing (a road "routed along the riverbed"). Which ways were excluded this
  way is queryable (`RoadNetwork.excludedBridgesAndTunnels()`) for reporting the gap explicitly.
  MINECRAFT-566's milestone update explicitly defers correct bridge/tunnel rendering to a
  following milestone and says this one must not be blocked on it -- this mixin's existing
  skip-and-report behavior already satisfies that; no further bridge/tunnel work is owed by this
  milestone. The only bundled region (`yosemite_valley.json`) still contains zero bridges or
  tunnels, so the skip path is exercised by the schema but not by bundled fixture data.
- **Layered surfaces on a real step.** `EarthGeneratorRoadMixin` asks `RoadSurfaceLayering` for a
  layer count based on the Chebyshev-ring distance to the nearest same-road neighbor with a lower
  real ground height (searching outward up to `RoadSurfaceLayering.MAX_NEIGHBOR_DISTANCE` columns,
  decaying linearly), not the generic `slope` parameter (an earlier version used that and rounded a
  real single-block step down to "flat" -- see the MINECRAFT-566 ticket comments for that
  back-and-forth, caught in review against real generated data, not hypothetically). When the
  result is less than a full block, the mixin uses `LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE`
  (a dedicated compat-owned `LayeredBlock` over `POLISHED_BLACKSTONE`, registered in this mod's own
  namespace -- see `compat/layeredslopes/LayeredSlopesCompat.java`) instead of always painting a
  full block.
  `under()` is always a full block, never layered, matching the ticket's explicit instruction
  (`EarthGenerator` reads `under()` at depths 1-3). **The slope-to-layers mapping
  (`RoadSurfaceLayering.SLOPE_UNITS_PER_LAYER`) is a first heuristic verified only by its own pure
  unit test (`RoadSurfaceLayeringTest`) and by a synthetic collision-shape check
  (`RoadGenerationTest#anInclinedColumnUsesTheLayeredCompatBlockNotAWholeBlockStair`) -- no
  build/playtest confirmed how it actually looks or feels across a real inclined run in-game.**
- **Still only one bundled region.** Only Southside Drive's original pinned extract is bundled as
  of this change. A second, geographically separate, bigger hand-curated extract (the ticket
  originally suggested Presidio Parkway) is no longer the milestone's deliverable: John's
  milestone update explicitly supersedes that guidance, asking instead for the tile pipeline
  itself to be proven against an unbundled destination -- which `RoadNetworkExternalRegionTest`
  does, against a real external directory, not a bigger bundled file. A live Overpass fetch
  attempted earlier in this work returned "502 Gateway Timeout -- at capacity" (see
  `docs/road-data-provenance.md`); that attempt is now superseded context, not a blocking gap.

## Compile/test-time-only dependency, not a fork

Earth Platform's source is never copied, patched, or vendored into this
repo. `scripts/build_earth_platform_dependency.sh` builds its pinned
`combined-73` tag (release 0.1.0-alpha.73) exactly as upstream does and
drops the resulting jar at `libs/`, gitignored. This mod's own code imports
only Earth Platform's public API (`EarthGenerator.grid()`,
`.settings()`, `.surfaceY()`, `.generate()`, `SurfaceGenerator.Surface`,
`.DRY`, and the `geography` module's `GeoPoint`/`MercatorGrid`).
