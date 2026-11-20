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
  loudly (`RoadNetwork.checkSchemaVersion`). See `RoadNetworkExternalRegionTest`, which proves a
  genuinely unbundled destination (a region file that exists nowhere in this mod's jar) loads
  through the exact same pipeline, alongside bundled Yosemite, with no Java change.
- **Automatic, live road-tile fetch -- `RoadTileSource`.** Review correctly rejected the two sources
  above as the actual milestone deliverable: they both still require a player or operator to find
  and install a region file for a destination ahead of time, not coverage that "appears
  automatically wherever he travels." `RoadTileSource` fetches a destination's own
  `RoadNetwork.CELL_DEGREES`-degree cell from a live endpoint the first time it is needed and caches
  it to disk, modeled directly on
  `io.earthplatform.geography.globe.TerrariumTileSource` -- the pattern Earth Platform's own
  dependency already uses for this exact generation-time constraint. Three cache states
  (`RoadTileSource`'s own class doc has the full reasoning): **ready** (fetched, cached, read every
  time after -- this is what "pins" a world's road data to whatever it actually fetched), **confirmed
  empty** (fetched, genuinely zero ways there -- stays empty, not re-fetched forever), and
  **pending** (never fetched successfully -- a network failure leaves it here deliberately, so an
  outage can never be permanently baked in as a roadless gap; the very next access, including after
  a server restart reading the same cache root, retries).
- **No default live source; one opt-in preview provider.** Three candidates were tried and
  rejected as a public default: three public Overpass mirrors (overloaded), the official OSM
  editing API (explicitly disallowed by policy as an automated game-fetch backend), and VersaTiles'
  hosted Shortbread vector tiles (screened and rejected -- its generation profile merges road
  geometry and can drop tags like `layer` at every zoom, which would break `RoadSegment`'s
  ordered-node-ID junction model and the incline mixin's same-way `wayId` blending). This mod now
  ships NO hardcoded live endpoint at all. The one sanctioned live source is an opt-in preview John
  selected himself (MINECRAFT-566 comment 55446): a hosted Overpass provider's free/noncommercial
  tier (Overpass.ch), gated behind ALL of `-Dearthplatformroads.previewProvider=overpass-ch`,
  `-Dearthplatformroads.previewEndpoint=...` (no default URL), and the `EARTHPLATFORMROADS_PREVIEW_API_KEY`
  environment variable (never a `-D` system property -- those are visible in process argument
  listings; see `RoadNetwork.buildDefaultTileSource`'s own doc) -- a missing key disables the
  provider entirely rather than sending an anonymous request. The credential is sent (as an
  `X-API-Key` header, verified against overpass.ch's own docs) ONLY when the configured endpoint
  exactly equals `RoadTileSource.CREDENTIAL_BOUND_ENDPOINT`
  (`https://api.overpass.ch/v1/interpreter`) -- a misconfigured `previewEndpoint` can disable the
  preview but can never leak the credential elsewhere; `shouldSendApiKeyTo` is unit-tested directly
  against that exact URI plus several near-miss decoys (wrong path, wrong scheme, no key). A 429/503
  response's `Retry-After` header (also verified against the docs) is honored for the next fetch
  attempt, on top of the configured `previewMinRequestIntervalMs` (default 5000ms) `RoadTileSource`
  already enforces alongside its one-in-flight-request guarantee.
  `RoadNetworkProductionWiringTest.withNoOptInPropertiesSetTheDefaultWiringNeverTouchesTheNetwork`
  and `.optingIntoTheProviderWithoutAKeyStillDisablesLiveFetch` prove zero requests happen either
  with no opt-in at all, or with the provider/endpoint opted in but no key present.
  `RoadTileSource.fetchCell` speaks real Overpass QL (`out geom`) again, with `checkForErrorRemark`
  restored (and its two tests) -- that failure shape is reachable again now that this class talks to
  a real Overpass-protocol endpoint. This preview is explicitly live data, not the pinned/immutable
  worldwide snapshot the separate offline SQLite-catalog pilot work is building toward.
- **Where fetching happens, and where it never does.** `EarthGeneratorPrepareMixin` hooks Earth
  Platform's own documented "fetching data ahead of generation" entry points,
  `EarthGenerator.prepare`/`.prepareNear` (see that class's own "Reading this file" section) --
  called off-thread already by Earth Platform itself (`/earth travel`'s command handler wraps its
  call in `CompletableFuture.supplyAsync`; `ChunkyCompat` calls `prepare` once per pre-generation
  cell) specifically so synchronous generation never waits on the network. This mixin does the same
  for road data: it blocks (safely, in those same already-async contexts) on `RoadTileSource
  .waysForCell` for the point's cell. `EarthGeneratorRoadMixin`'s `surface()` hook, by contrast,
  never fetches -- `RoadNetwork.candidatesNear` only ever calls the read-only
  `RoadTileSource.cachedWaysForCellOrEmpty`, so the hot generation path, including Earth Platform's
  `loadNow` (explicitly synchronous on whatever thread calls it, possibly the main server thread per
  its own doc comment), never blocks on HTTP. Verified end-to-end against a local HTTP stub (never a
  real live service, deterministically) in `RoadTileSourceTest` (fetch/cache/pending-retry/
  confirmed-empty, each independently) and `RoadTilePrefetchMixinTest` (calling the real
  `EarthGenerator.prepareNear` through the real mixin populates the cache; a subsequent
  `RoadNetwork` lookup finds it with zero further fetches).
- **Known simplification, named in `EarthGeneratorPrepareMixin`'s own class doc:** this mixin only
  prefetches the single cell a point falls in, not a multi-kilometre reach matching Earth
  Platform's own `prepareNear`/`NEAR_REACH` -- a column right at the edge of a freshly-prepared
  area, or one a very fast-moving player reaches before that one fetch completes, can still see a
  transient first-visit gap. Widening the prefetch to neighboring cells is a reasonable next step,
  not done here.
- **Not attempted this pass, named explicitly as still-open:** separate readiness handling for
  Chunky pre-generation specifically (`ServerCubeWorld.pregenerate`/`ChunkyCompat.pregenerate` call
  `generate()` directly, off the travel/teleport path `prepareNear` covers); a real multi-way
  Yosemite extract plus a real distant-site extract with full source-vs-generated accounting
  (class counts, connected intersections, tile boundary, documented omissions); and any
  disposable-profile in-game visual/collision/seam demonstration, which requires an actual running
  Minecraft client this agent's environment does not have.
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
  (`EarthGenerator` reads `under()` at depths 1-3). The distance-to-layers decay
  (`RoadSurfaceLayering.layersForNearestLowerNeighbor`) is verified by its own pure unit test
  (`RoadSurfaceLayeringTest`) and against real measured generated data
  (`RoadGenerationTest#aGeneratedShortInclineClosesEveryAdjacentStepToASmallSeamNotAWholeBlockCliff`,
  `RealOsmFixtureSmokeTest`). **Updated:** a disposable-profile playtest on the captured Yosemite
  slice (the bundled `yosemite_valley.json` extract) confirmed the road looks smooth with the full
  Slopes/car/Flowing Fluids pack -- the first real in-game visual confirmation of this layering
  behavior, not just a synthetic collision-shape check. That preview build is left intact; it is not
  evidence about the live-tile-fetched path, which still has no in-game visual confirmation.
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
