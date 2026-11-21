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
  attempt, on top of the configured `previewMinRequestIntervalMs` (default 5000ms).
  `RoadNetworkProductionWiringTest.withNoOptInPropertiesSetTheDefaultWiringNeverTouchesTheNetwork`
  and `.optingIntoTheProviderWithoutAKeyStillDisablesLiveFetch` prove zero requests happen either
  with no opt-in at all, or with the provider/endpoint opted in but no key present.
- **A disk-cache hit never waits behind a cold cell's backoff (MINECRAFT-566 comment 55450).**
  `RoadTileSource` originally used one global lock around both disk reads and the outbound request
  (including the rate-limit sleep) -- found, not observed as a real hang, but real: after a
  restart, a different already-cached tile could wait behind an unrelated cell's cold request. Now
  split into `cellLocks` (one lock per cell, dedupes concurrent fetches of the SAME cell only) and
  `networkLock` (serializes the one actual outbound request network-wide, held only for that
  request's own duration). Disk-cache hits in `waysForCell` take neither lock at all -- same as
  `cachedWaysForCellOrEmpty` always has. Rate limiting no longer sleeps: `nextEligibleRequestAt`
  is a deadline, and a fetch attempted before it is declined immediately (pending, retryable) rather
  than blocked. `RoadTileSourceTest.aDiskCachedTileIsNotBlockedBehindAColdCellsProviderBackoff`
  seeds a ready tile on disk, triggers a 429+`Retry-After` on a different cell through a fresh
  instance (a restart), and asserts the seeded tile still returns promptly with zero extra HTTP.
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
- **A road crossing an unfetched neighbor's boundary was silently omitted -- found live, fixed on
  the read side (MINECRAFT-566 comments 55454/55456).** John saw a real road abruptly end on bare
  ground on Zippy; his own read of the cache found the player's tile and 7/8 of its neighbors with
  no ready/empty marker. `RoadNetwork.candidatesNear` originally only ever checked a column's OWN
  cell -- a mapped way crossing into an adjacent, still-pending cell had nowhere to be found at
  that boundary: not a missing way, not a bridge/tunnel omission, just a genuinely unfetched
  neighbor.
  **First fix attempt, found broken under real conditions (55456):** widening
  `EarthGeneratorPrepareMixin.prefetch` itself to issue up to 9 sequential `waysForCell` calls (the
  point's cell plus its 8 Moore neighbors). With a realistic multi-second
  `previewMinRequestIntervalMs`, only the first of those 9 calls could succeed before the
  next-eligible deadline rejected the rest -- the regression test had used the zero-interval
  constructor and never caught this.
  **Actual fix:** `RoadNetwork.candidatesNear` now checks the Moore neighborhood's CACHED data
  (read-only, no fetch) when evaluating a column, not just its own cell. A live provider's bbox
  response for one cell already carries the full geometry of any way merely touching that cell,
  including the portion crossing into a neighbor -- so the neighbor's own fetch was never actually
  required to carry the seam; `EarthGeneratorPrepareMixin` stays at its original single-cell
  prefetch. Confirmed in `RoadTilePrefetchMixinTest
  .aSingleFetchCarriesACrossingRoadIntoItsNeverFetchedNeighbor` (one fetched cell, one never-fetched
  neighbor, the crossing way still found) and
  `.aProductionCadenceEmptyCacheRunCarriesTheSeamThroughRealGeneration` (the real mixin, the real
  5000ms-order rate limit -- not zeroed -- through the actual prepare-to-generation path, exactly
  one real fetch, the neighboring candidate present before generation). That test's synthetic
  coordinates could fall in open ocean (where the road mixin correctly skips painting regardless of
  this fix), so it stops at proving the candidate is present, not that a block is painted --
  comment 55458 named that gap explicitly; `.theReadSideNeighborCheckActuallyPaintsARoadSurfaceAtTheBoundaryColumn`
  closes it at real, unambiguously dry-land coordinates (near Boulder, Colorado), actually calling
  `generate` at the boundary column and asserting the real road-surface block.
  **This still only helps a FRESH (not yet generated) cube's read** -- the cached data has to exist
  by the time that column is evaluated. A cube already generated before any relevant fetch
  happened is not retrofitted: this mod has no mechanism to revise an already-generated cube when
  road data arrives later, and 55454/55456 explicitly separate that (a harder, different problem)
  from this fix and do not ask for it. Still a known simplification: a column two or more cells
  away from every cell that has ever actually been fetched can still see a transient gap -- the
  neighborhood check only extends as far as what some fetch, anywhere nearby, already happened to
  cover.
- **A second case the read-side fix cannot carry, found live again (55460): a continuation as a
  DIFFERENT OSM way ID, sharing only a node, whose own geometry lies entirely in a neighbor cell
  no query has ever touched.** That neighbor's data was never returned by any fetch at all -- the
  read-side trick only helps when a single way's geometry already crosses into an already-fetched
  cell. Fixed by `RoadTileSource.enqueueLookahead`: a bounded (`LOOKAHEAD_QUEUE_CAPACITY`),
  deduplicated queue of a prepare point's Moore neighborhood, drained at AT MOST one additional
  fetch per `enqueueLookahead` call -- through the ordinary `waysForCell` path, so it is subject to
  the same one-in-flight guarantee and rate-limit deadline as every other fetch, never bursting
  multiple requests the way 55454's original attempt did. `EarthGeneratorPrepareMixin` calls it
  right after its own synchronous point-cell fetch. Over several prepare calls as a player moves
  normally, this catches up nearby cells -- including a second way's own cell -- without ever
  exceeding the provider's rate limit. Confirmed in `RoadTilePrefetchMixinTest
  .aSuccessorWayInANeverDirectlyFetchedCellIsFoundAfterSeveralPrepareCalls` -- comment 55464 found
  the first version of this test dispatched its stub by request COUNT, not the real bbox, so a
  request to the WRONG neighbor cell could still receive the successor way's data and pass for the
  wrong reason; the corrected version dispatches by the actual bbox parsed from each POST body (one
  shared node at the SAME coordinate in both ways, placed just inside the successor's own cell),
  asserts a POST matching that cell's exact bbox and that it ends up `READY` under that key (not
  merely found via the neighbor scan), uses MOVING prepare points that never directly enter the
  successor's cell (so it can only be reached through the lookahead queue), and generates a real
  block past the first way's own endpoint.
  **A real bug in the queue itself, found by comment 55466 reviewing the test rather than the
  code:** the original `enqueueLookahead` removed a dequeued cell from `queuedCellKeys` 55466
  unconditionally, whether or not the fetch attempt actually resolved it -- relying on a LATER
  `enqueueLookahead` call for an OVERLAPPING area to rediscover the cell was still uncached and
  re-queue it. The first cooldown test called the lookahead repeatedly over the exact same
  overlapping neighborhood, which happened to work but could not distinguish "the original job was
  retained" from "it was silently dropped, then a brand-new job for the same cell got added later."
  Fixed: a dequeued cell whose fetch attempt did not resolve it (declined as not-yet-eligible, or a
  genuine failure -- either way still `PENDING` afterward) is put back at the front of the queue
  and stays marked queued, so it is the SAME retained job tried again, never silently dropped.
  Added `RoadTileSource.setClockForTests`/`clock` (defaults to the real system clock) so a test can
  advance time deterministically instead of sleeping out -- or, worse, shortening -- the real
  multi-second production interval.
  `.aCooldownDeclinedLookaheadEntryStaysEligibleWithoutDuplicateRequests` (rewritten for 55466)
  proves this properly: the real 5-second-order interval (not shortened), a controlled clock (no
  real sleep), and critically a DRAIN call that scans a totally different, non-overlapping
  neighborhood -- so the retained job cannot have been freshly re-added by that call's own scan,
  only genuinely retained from before.
- **A second lookahead-queue bug, found by comment 55468 through pure logical review -- no observed
  Zippy evidence, no Zippy access needed to find it.** The 55466 fix retained ANY cell whose attempt
  left it `PENDING` at the front of the queue, whether that attempt was never actually dispatched
  (declined by the rate-limit deadline, free, instant) or genuinely dispatched and failed (a real
  timeout or 5xx, costing an actual request). Conflating these let a persistently-failing cell
  (e.g. a dense tile that always 504s) sit at the front forever, monopolizing every future
  opportunistic slot and starving its neighbors. Fixed: {@code RoadTileSource} now distinguishes
  `FetchOutcome.DISPATCH_SUPPRESSED` (never sent, retained at the FRONT, as before) from
  `ATTEMPTED_FAILURE` (really sent and failed, rotated to the BACK instead -- still retried
  eventually, never confirmed-empty, just after every other then-queued neighbor gets its own
  turn first). `.aPersistentlyFailingCellRotatesBehindItsNeighborsWithoutFalseEmptyOrBurst` proves
  it: cell A fails twice via a real 504 (bbox-dispatched, controlled clock, six other Moore
  neighbors pre-seeded confirmed-empty so the rotation between just A and B is directly
  observable), B becomes ready on its own turn in between, and A stays genuinely pending (never
  falsely marked empty) both times. (While building this test, an early version placed cells near
  latitude 90 -- an actually invalid WGS84 coordinate -- and `OverpassWayParser`'s own `GeoPoint`
  constructor correctly rejected it; moved the whole scenario away from the poles.)
  `.aPreWarmedNeighborsTurningWayIsFoundDeepInAnUnfetchedCellsInterior`
  (55462) matches his own live, read-only diagnosis directly: a pre-existing, install-wide-cache
  neighbor entry from before the fresh test world even started, a multi-segment turning way, a
  real painted block deep in the still-fully-pending own cell's interior, not just at the seam.
- **Nonsecret, opt-in diagnostic added (55462): `RoadNetwork.diagnoseColumn`.** Distinguishes
  `COVERED` / `EXCLUDED_BRIDGE_OR_TUNNEL` / `PENDING_NO_DATA` / `CONFIRMED_EMPTY_NO_ROAD` for one
  column, backed by a new `RoadTileSource.CellState` (`cellState(lat,lon)`) exposing which of the
  three on-disk cache states actually applies -- `cachedWaysForCellOrEmpty` alone cannot answer
  this, since it deliberately returns the same empty list for "confirmed genuinely empty" and
  "merely still pending." Exposes no credential or request detail, only which state applies.
  Does NOT cover a mixin-level terrain skip (e.g. "under water") -- that check lives in
  `EarthGeneratorRoadMixin`, not here. Confirmed in `RoadTilePrefetchMixinTest
  .diagnoseColumnDistinguishesAllFourCauses` (one generator, four real cells, one cause each).
- **`prepare`/`prepareNear` are unreachable during ordinary gameplay movement -- found by comment
  55474 through pure logical review of Earth Platform's own `ServerCubeWorld` source, no Zippy
  access needed.** Every fix above only ever helped `/earth travel` teleport (`prepareNear`) and
  Chunky pre-generation calling `prepare` per cell. A player simply walking/driving into fresh
  terrain never calls either: the real path is `ServerCubeWorld.tickCubes` (server tick) ->
  `requestAround` (server thread, enqueues only) -> `request` -> `workers.execute` (background
  worker pool) -> `produce`, which on a storage miss calls `generator.generate(...)` directly, with
  no prepare hook anywhere in that chain -- confirmed by reading the real class in this mod's own
  `libs/earthplatform-0.1.0-alpha.73.jar` dependency (the `combined-73` build). Almost certainly the
  real mechanism behind "a road abruptly ends" during ordinary play, though 55474 is explicit this
  is a verified integration gap, not a confirmed explanation for any one specific report.
  **Fix:** a new `ServerCubeWorldRoadPrefetchMixin` injects into `produce`, immediately before its
  call to `generate(...)` -- after the storage-read fast path has already returned, so an
  already-saved/edited cube is untouched -- and dispatches the exact same bounded, deduplicated,
  rate-limited `waysForCell`/`enqueueLookahead` pair `EarthGeneratorPrepareMixin` already uses
  (pulled out into a shared `RoadPrefetch.forColumn`, not a second copy). Because this runs on the
  background worker thread BEFORE `generate()`, a cell that fetches successfully is available to
  `EarthGeneratorRoadMixin`'s `surface()` hook during THIS SAME generation call, not a retrofit of
  an already-saved cube. `produce` is also called from `loadNow` ("synchronous load for places that
  must exist this tick, such as an arrival point", explicitly possibly the main server thread) --
  55474's hard constraint is never adding HTTP there. Both callers share the one `produce` method
  with nothing distinguishing them, so a new `SynchronousLoadGuard` (a `ThreadLocal` depth counter,
  not a plain boolean -- `loadNow` and the background path run on unrelated threads, and nothing
  rules out `loadAround`'s loop nesting one `loadNow` inside another) wraps `loadNow`'s HEAD/RETURN;
  the `produce` injection checks it and does nothing while active.
  **Also added, same comment's ask:** `RoadTileSource.sustainedOutage()` (true after
  `SUSTAINED_OUTAGE_FAILURE_THRESHOLD` consecutive genuinely-dispatched failures ACROSS DIFFERENT
  cells, reset by any single success anywhere -- a persistently-404ing single tile is not proof the
  provider itself is down) backs a new `RoadNetwork.ColumnDiagnosis.PENDING_PROVIDER_OUTAGE`,
  distinct from the ordinary "nobody has asked yet" `PENDING_NO_DATA`.
  **Known, disclosed test gap:** no test drives the real, compiled `ServerCubeWorld.produce`/
  `loadNow` methods themselves -- that needs a real `ServerLevel`, which (unlike `EarthGenerator`,
  which this project's and Earth Platform's own benches construct directly) this project's
  `neoForge.unitTest` harness has no fixture for, and Earth Platform ships none either. What IS
  covered directly: `SynchronousLoadGuardTest` (enter/exit, nesting, and -- the actual bug this
  guards against -- cross-thread isolation, using a real second `Thread`) and the outage-streak
  mechanics (`RoadTileSourceTest`, plus one `RoadTilePrefetchMixinTest` case driving
  `RoadNetwork.diagnoseColumn` through several real dispatched failures). The dispatch itself
  (`RoadPrefetch.forColumn`) is the exact code `RoadTilePrefetchMixinTest` already exercises through
  `prepare`/`prepareNear`. What remains genuinely unverified is only that this specific mixin's own
  injection points actually weave and fire as written against the real `ServerCubeWorld` class;
  NeoForge's `gameTestServer` run (a real dedicated server) is the sanctioned mechanism for that and
  is not attempted here.
- **A real "wrong tile" bug in the fix above, found by comment 55476 through pure logical review of
  both codebases -- no Zippy access needed.** `produce`'s `x`/`z` are CUBE indices (confirmed by
  `loadNow`'s own `pos.getX() >> 4` block-to-cube shift feeding that exact parameter), not the BLOCK
  coordinates `RoadPrefetch.forColumn` actually expects (the same contract `prepare`/`prepareNear`'s
  own callers already honor) -- the first version of `ServerCubeWorldRoadPrefetchMixin` passed the
  raw cube index straight through, querying entirely the wrong map tile (off by a factor of 16) on
  almost every call. **Fixed** by a new `RoadPrefetch.cubeIndexToBlockCenter(int)` (`(cubeIndex << 4)
  + 8`), pulled out as its own named, directly testable function rather than left inline, called on
  both `x` and `z` before dispatching; `RoadPrefetch.forColumn`'s own javadoc now names the block-
  coordinate contract explicitly to prevent a repeat. `RoadTilePrefetchMixinTest
  .ordinaryStreamingPrefetchConvertsCubeIndexToBlockCoordinatesNotTheRawCubeBbox` proves it: an
  off-origin, nonzero/negative cube index, a real HTTP stub, asserting the real POST bbox matches
  the CORRECT (block-center) cell and explicitly asserting ZERO requests match the buggy raw-cube-
  index bbox (not just "some bbox was sent").
  Independently reproduced by John on a real compiled server (clean NeoForge/`combined-73` boot,
  exact jar SHA-256 recorded, a real `request(key, true)` reaching the compiled worker with no
  `prepare` call): the buggy build queried `(2.73,-6.58,2.74,-6.57)` for a synthetic signed cube
  coordinate `(-561017,12,-232999)` whose correct block-center bbox was near
  `(40.00794797,-105.27404946)` -- exactly the raw-cube-index projection. The SAME verifier rerun
  against the corrected commit (`a632bdc`) requested exactly one loopback bbox,
  `(40.000000,-105.280000,40.010000,-105.270000)`, zero raw-index requests, and the worker's `Ready`
  section actually placed the road surface block at the expected column -- confirming the fix end
  to end on a real compiled server, not just this repo's own unit tests.
  **"Bounded-worker" follow-up, also from 55476, addressed next (same comment, continued as the next
  draft on top):** a tall column spans many cube-Y layers, each independently missing storage and
  otherwise each separately dispatching for the exact same cell -- correct (per-cell lock/memory
  cache dedupe it) but potentially wasteful, and on a small worker pool could block several worker
  threads on one cell's lock at once for up to the full network timeout. Fixed by
  `RoadTileSource.waysForCellWithBoundedWait` (used only by `ServerCubeWorldRoadPrefetchMixin`, via
  `RoadPrefetch.forColumnBounded` -- `EarthGeneratorPrepareMixin` keeps using the plain, fully
  synchronous `forColumn`, preserving its own saved reads/cadence unchanged): every caller for the
  SAME still-pending cell (every cube-Y layer of one column, concurrently) coalesces onto ONE real
  fetch via a new `inFlightByCell` map; the actual network wait runs on one dedicated, shared,
  daemon background thread (`ASYNC_FETCH_EXECUTOR`), never a generation worker; each CALLER only
  waits up to a short bound (`PREFETCH_WAIT_BOUND`, 2s, versus the full 20s HTTP timeout) before
  failing open -- the shared fetch keeps running regardless, so a later caller still benefits once
  it resolves. The Moore-neighborhood lookahead is fully async too (`enqueueLookaheadAsync`): the
  calling worker thread never waits on it at all. Four new `RoadTileSourceTest` cases prove this
  with a real delayed HTTP stub: a fetch resolving within the bound returns fresh data promptly; one
  that outlasts a short bound fails open quickly but still fills the cache once the background fetch
  actually finishes (polled, not assumed); five concurrent callers for the same cell share exactly
  one real request; and the async lookahead dispatch returns near-instantly while still actually
  firing in the background.
- **Three real bugs in the bounded-worker fix above, found by comment 55481 through pure logical
  review -- no Zippy access needed.** (1) `inFlightByCell`'s cleanup callback was registered INSIDE
  `computeIfAbsent`'s own mapping function, before that function had returned; `computeIfAbsent`
  does not publish the new entry until the mapping function returns, so a fetch that happened to
  complete synchronously fast enough could run its cleanup (a no-op, since nothing was published
  yet) BEFORE `computeIfAbsent` went ahead and published the (already-completed) future anyway --
  permanently pinning a stale, resolved future, so every later caller for that cell would reuse it
  forever instead of ever dispatching a fresh fetch again. **Fixed** by registering the cleanup only
  AFTER `computeIfAbsent` returns, when publication is guaranteed. (2) `enqueueLookaheadAsync`
  submitted a brand-new task to `ASYNC_FETCH_EXECUTOR`'s own unbounded queue on EVERY call -- a tall
  column's many cube-Y-layer misses would each submit their own, unbounded by
  `LOOKAHEAD_QUEUE_CAPACITY` (which only bounds the neighbor cells one `enqueueLookahead` call
  enqueues, not how many times the ASYNC wrapper itself gets invoked). **Fixed** by a new
  `pendingLookaheadDispatches` set: a later call for the SAME center cell while one is already
  queued is skipped for free, not piled on. (3) Distinct, simultaneously-cold cells (e.g. a player
  moving fast across many never-touched columns) could grow `inFlightByCell` without bound.
  **Fixed** by `MAX_IN_FLIGHT_TILES` (8): admission for a genuinely NEW cell is refused (fails open
  immediately) once that many are already in flight -- a soft, best-effort cap (the check and the
  insert are not atomic together), not a hard guarantee, but enough to keep this bounded. Three new
  `RoadTileSourceTest` cases prove each fix: a zero-delay stub (maximizing the chance of hitting
  bug (1)'s race) followed by a second call for the same cell, asserting it dispatches its own fresh
  request rather than reusing a stale one; 20 rapid same-column lookahead calls, asserting only one
  task is ever actually pending; and 20 threads for 20 distinct cold cells against a slow stub,
  asserting the in-flight count stays meaningfully bounded (not literally 20).
  **Independently reproduced in a real isolated worker, same comment:** a 4.2s loopback response
  (longer than `PREFETCH_WAIT_BOUND`'s 2s) correctly fetched the right bbox, but the column had
  already committed as grass by the time the real data became ready -- "the road placement decision
  can already be stale before adoption" -- a live, concrete instance of the ALREADY-disclosed
  fail-open tradeoff (an ordinary late response still commits roadless terrain), not a new category
  of bug. 55481 asks this be addressed next by reusing "the upstream stream retry seam" --
  `ServerCubeWorld.adoptReady`'s own existing behavior for a `Ready` with a `null` section (`"Leave
  a gap and try again shortly rather than stall"`, scheduling a real retry via `retryAt` a few
  seconds later) -- rather than this mixin's own bounded wait being the only chance a column gets.
  **Not attempted this pass:** doing this would mean cancelling `produce`'s own `generate()` call
  from this mixin's injection and constructing a `ServerCubeWorld.Ready` with a `null` section
  instead, so the EXISTING retry seam picks the column back up shortly after -- `Ready` is a
  *private* record with a non-trivial field list (confirmed by reading it directly), so doing this
  correctly needs a NEW Mixin accessor (e.g. `@Invoker`) exposing its constructor, carefully matched
  against its exact real signature, not attempted in the same pass as the three fixes above. This is
  understood to be the real next step, not a vague gesture at "more work later."
  **Still NOT attempted (named, not hidden):** 55476's suggestion to bracket the guard with
  `finally` rather than HEAD/RETURN: `loadNow`'s only other call, `adoptReady`, is NOT fully wrapped
  in try/catch internally (e.g. `server.getPoiManager().checkConsistencyWithBlocks`,
  `BlockEntity.loadStatic` can throw) -- an uncaught exception there would skip the RETURN-site
  `exit()`, leaving that one thread's `SynchronousLoadGuard` stuck active forever. Plain Mixin
  `@Inject` has no built-in try/finally-equivalent injection point; a real fix needs either a
  bytecode-level wrap or restructuring the guard's lifecycle, neither attempted here. The failure
  direction is safe (that thread would simply stop prefetching, never incorrectly fetch during an
  arrival), and `loadNow` is documented as a direct, synchronous call (not dispatched through
  `workers`), so this is believed not to reach the ordinary-streaming worker threads in practice --
  but this is a belief, not something this pass verified, and is named here rather than left silent.
- **Not attempted this pass, named explicitly as still-open:** separate readiness handling for
  Chunky pre-generation specifically (`ServerCubeWorld.pregenerate`/`ChunkyCompat.pregenerate` call
  `generate()` directly, off the travel/teleport path `prepareNear` covers -- and now off the
  ordinary-movement path `ServerCubeWorldRoadPrefetchMixin` covers, too); a real multi-way
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

## MINECRAFT-566: a real stale-surface-cache bug in the readiness work (comments 55486/55487)

Found through pure logical review of Earth Platform's own `SurfaceGenerator` class (confirmed by
reading its real bytecode), then independently reproduced by John on a real server -- no Zippy
access needed for the finding itself.

`SurfaceGenerator` (which `EarthGenerator` extends) permanently memoizes an ENTIRE column's worth
of `Surface` decisions (256 of them, one per Y-slice) in a private `columns`
`ConcurrentHashMap<Long, Column>`, keyed by a private `columnKey(columnX, columnZ)` --
`HashCommon.mix` applied to the packed column/chunk coordinates. The first caller to ask for
anything about a column populates this cache for its ENTIRE lifetime on that generator instance;
`column(int,int)` never recomputes an already-cached entry on its own. Critically, `lowestGround`
(confirmed by reading its bytecode: it calls `column(x,z)` directly) is one such caller --
`ColumnSurvey`/`ServerCubeWorld.plan` calls it every tick for ordinary flying-surface logic, with
no relationship at all to whether this column's road data happens to be ready yet. (`surfaceY`, by
contrast, does a plain `.get` with no compute-on-miss -- confirmed NOT to trigger this.)

This meant the bounded-worker readiness fix (comments 55474-55481) could not actually guarantee
correctness on its own: if `lowestGround` (or anything else routing through `column`) had already
cached this column's decision BEFORE its road tile became ready, the LATER, genuinely fresh
`produce`/`generate` call for that exact same column -- even with the tile now READY -- would hit
that same stale cached entry and never re-invoke `surface()` at all, regardless of how well the
bounded wait/coalescing worked. John's own reproduction on a real server confirmed this exactly: a
`lowestGround` call while PENDING, then the tile resolved to READY, then `generate()` on the SAME
generator instance still emitted grass; only a FRESH generator instance (same seed/data) correctly
emitted blackstone.

**Fixed** with a narrow, per-column cache eviction, not a global clear (55486's own explicit ask):
a new `SurfaceGeneratorColumnCacheAccessor` (`@Accessor` interface Mixin exposing the private
`columns` map) plus `RoadPrefetch.surfaceGeneratorColumnKey` (replicating `columnKey`'s exact
formula via the same real, public fastutil `HashCommon.mix` -- no accessor needed for the private
method itself, only the field) and `RoadPrefetch.evictStaleSurfaceDecision`, called
unconditionally by `ServerCubeWorldRoadPrefetchMixin`'s existing injection point, right before
`produce`'s own call to `generate()` -- for BOTH the ordinary-streaming path and `loadNow`'s
arrival path (not gated by `SynchronousLoadGuard`: evicting a map entry is not HTTP, and the
staleness risk is not specific to either path). Free in the ordinary case: `produce` only ever
runs once per column under normal operation, so there is nothing legitimate to evict yet; evicting
simply guarantees `generate()`'s own imminent, already-about-to-happen recomputation sees current
data instead of possibly-stale cached data.

**Verified** by `RoadTilePrefetchMixinTest.evictingTheStaleSurfaceDecisionFixesARealReproductionOfTheCachingBug`,
replicating John's own reproduction methodology directly against a real `EarthGenerator` (no
`ServerCubeWorld` needed for this specific mechanism): calls `lowestGround` while a cell is
genuinely PENDING, writes the tile READY directly to disk, confirms `generate()` WITHOUT the fix's
eviction still shows the stale natural terrain (reproducing the bug first), then confirms the SAME
generator instance, after `evictStaleSurfaceDecision`, correctly paints the real road.

**Not attempted:** a dedicated accessor/invalidation test driving the real, compiled
`ServerCubeWorld.produce` itself (same disclosed `ServerLevel` gap as the rest of this readiness
work) -- the mechanism (the accessor, the key replication, the eviction) is proven directly instead,
against a real generator.

### A real ordering bug in the eviction fix, found by comment 55492 through pure logical review

The first version evicted BEFORE dispatching/waiting on the fetch (`RoadPrefetch.forColumnBounded`),
not after. That left a window, up to `PREFETCH_WAIT_BOUND` wide, during which some OTHER
concurrent caller -- a `lowestGround` survey landing on the server tick thread, for example --
could re-populate this exact column's cache with ANOTHER premature decision, undoing the eviction
before `generate()` ever actually benefited from it. **Fixed** by reordering
`ServerCubeWorldRoadPrefetchMixin`'s injection: the dispatch/wait now runs first (skipped entirely
during `loadNow`'s own arrival path, same as before), and the eviction is the very LAST statement
in the method, immediately before falling through into `generate()`.

**Known, disclosed residual race, still not fully closed:** `SurfaceGenerator`'s own `column()`
cache fills via a plain, non-atomic get-then-compute-then-put (confirmed by reading its bytecode
again), not `computeIfAbsent` -- a DIFFERENT, already-in-flight concurrent computation for this
exact column, started before this eviction ran, can still publish its own (also premature) result
AFTER this eviction, winning the race anyway. Closing that fully would mean owning
`SurfaceGenerator`'s own caching logic entirely (e.g. replacing `column()` wholesale via Mixin), not
attempted here -- a materially bigger, riskier change than a targeted reorder.

**Verified** by `RoadTilePrefetchMixinTest
.evictingAfterTheBoundedWaitSurvivesALowestGroundSurveyRacingInTheMiddleOfIt`, built exactly to
John's own suggested methodology: a real HTTP stub holds its response back deliberately; the real
dispatch sequence (`forColumnBounded` then, after it returns, `evictStaleSurfaceDecision` -- the
SAME order `ServerCubeWorldRoadPrefetchMixin` now uses) runs on a background thread; once the
request is confirmed actually in flight and holding, a `lowestGround` call for the SAME column
deliberately races into the middle of the wait; the response is then released (comfortably before
the bound); and the test asserts the real road still paints correctly despite that race -- proving
the reorder, not just asserting it.

### A bounded verify-and-retry for that residual race (comments 55496/55498/55499)

John independently reproduced the residual race above with deliberate test-only latches
(a completed, premature computation paused right before its own publish, released only after this
mixin's eviction and its own fresh `generate()` call had already run) and asked for a real fix, not
another ordering tweak -- correctly: the problem is WHO WINS the write race, not WHEN the eviction
happens, and no reorder changes that.

The actual root-cause fix -- move the road material decision out of `SurfaceGenerator`'s cache
entirely, so there is nothing left for a stale write to corrupt -- was evaluated and deliberately
**not** attempted. `surface()`'s `Surface` record carries more fields than the top/under block
states this mixin pair already overrides (passed as `0, 0, 0, 0, 0, false` in the existing
override), and comment 55498 named concrete, additional real risks in reimplementing that logic
externally: cached `Surface` data in the real `combined-73` build also drives cave-mouth behavior
at depth 0/1 and `StructureHost.fill`'s own foundation filling -- a blind post-`generate()`
overwrite of "top plus a few blocks of under" could plug an existing cave mouth or corrupt a
generated structure's blocks/metadata, in ordinary play, not just under an engineered race. That
rewrite touches the single most heavily-tested part of this whole mod and needs its own dedicated,
carefully-verified pass against a real running game -- not a same-change patch alongside everything
else, in an environment with no running Minecraft client to verify it against.

**Fixed instead** with a bounded verify-and-retry, leaving the existing, already-correct
`surface()`-based mechanism completely untouched: `ServerCubeWorldRoadPrefetchMixin`'s `@Inject` at
the `generate()` call site became an `@Redirect`
(`earthplatformroads$generateWithStaleRetry`) that calls the real `generate()`, then checks
`RoadPrefetch.sectionLooksStale` -- re-deriving, from fresh `RoadNetwork` data and `EarthGenerator`'s
own NATURAL `surfaceY`/`waterY` (never the decorated output, per 55498's explicit guidance), whether
every column in this cube that should show a road actually does in the generated section -- and if
not, evicts again and retries (re-evict, regenerate) up to `MAX_STALE_RETRY_ATTEMPTS` (3) times. In
ordinary play this never retries at all; the race would have to recur on every single attempt to
survive all 3. This narrows the race to "exhausts a small, bounded retry budget", not "eliminated" --
a disclosed, deliberately bounded residual gap (unbounded retries would risk an unbounded
generation stall instead), not claimed as fully closed.

**Verified** by `RoadTilePrefetchMixinTest.sectionLooksStaleDetectsAMismatchAgainstFreshRoadDataAndClearsOnceFixed`:
a correctly generated section (road data already ready) is proven to never be flagged; the exact
same section with ONLY the road column's own block swapped to a natural material (modelling what a
won stale race actually leaves behind) is proven to be flagged. **Known, disclosed test gap:** the
retry LOOP's own end-to-end behavior (that a second attempt actually recovers after a first-attempt
staleness detection) is not independently reproduced by an automated test this pass -- doing that
means pausing `SurfaceGenerator`'s own internal computation at a specific bytecode point, the same
test-only-latch technique John's own reproduction used, which needs its own dedicated hook into
Earth Platform internals beyond what exists here; the detection function itself (what the retry
loop actually decides on) is proven directly and deterministically instead.

## MINECRAFT-566: road width, centerline and shoulder styling (comment 55484)

A deliberately SEPARATE slice from the readiness/bounded-worker work above -- John explicitly asked
for this to be queued as its own commit so it would not displace that in-flight work, after live
playtest feedback on `a632bdc`: "present roads look about half as wide as expected", wanting a
yellow-terracotta centerline and gravel shoulders.

- **Width.** `RoadClass.defaultPavedHalfWidthBlocks` is roughly doubled per class (e.g. MOTORWAY
  5.0 -> 10.0, SECONDARY 3.0 -> 6.0, SERVICE 1.5 -> 3.0), and the lanes-based formula
  (`pavedHalfWidthBlocks(lanes)`, was `lanes/2.0 + 2.0`) doubled the same way (`lanes + 4.0`).
  Renamed from `halfWidthBlocks` throughout to `pavedHalfWidthBlocks` -- the width was never
  persisted in any region/tile JSON to begin with (`RoadSegment.toWayJson` never wrote it;
  `RoadNetwork.parseWay`/`OverpassWayParser` always recompute it fresh from `roadClass` + `lanes`
  at load time), so changing the formula affects every already-bundled, external, AND already-
  fetched-and-cached tile on its very next load, with zero migration of any kind needed. An
  already-GENERATED, saved cube is still never retrofitted (the existing, unrelated
  "no retrofit" limitation) -- this only ever affects fresh generation.
- **Cross-section.** `RoadSegment.crossSectionAt` is the one place that decides which zone a column
  falls into, nesting three thresholds against the exact same `distanceBlocks` value:
  `CENTERLINE_HALF_WIDTH_BLOCKS` (0.5, classes with `RoadClass.hasCenterline` only) <
  `pavedHalfWidthBlocks` < `totalHalfWidthBlocks()` (paved + `SHOULDER_WIDTH_BLOCKS`, 1.0).
  `coversColumn` (used by `segmentCoveringColumn`, and therefore the bridge/tunnel exclusion check
  too) now means the WHOLE footprint, shoulder included, not just the paved surface. Using ONE
  distance value with nested thresholds, rather than separately re-deriving each zone's own check,
  is what guarantees the centerline can never read as outside the paved surface -- John's named
  acceptance requirement -- regardless of how a curve's or junction's nearest-segment selection
  behaves; `RoadCrossSectionTest.theCenterlineNeverExtendsPastThePavedSurfaceEvenAtASharpBend`
  checks this explicitly at a real (if synthetic) 90-degree bend, not just a straight stretch.
  `RoadClass.SERVICE` is the one class with `hasCenterline = false` -- a driveway/service road
  reads as a narrow, unstriped path even at its own (still-widened) paved width, matching real-world
  road-marking convention; `RoadCrossSectionTest.aServiceRoadNeverReturnsCenterlineRegardlessOfDistance`
  checks it.
- **Materials.** `EarthGeneratorRoadMixin` picks blackstone/yellow-terracotta/gravel (plus the
  SAME `RoadSurfaceLayering` decayed-layer treatment for whichever one applies, so slope-layering
  continuity now holds for all three, not just blackstone) from the zone `crossSectionAt` returns.
  `under()` differs by zone too: blackstone under the centerline (a painted line ON pavement, not a
  full-depth material change) and under the paved surface itself, but GRAVEL under the gravel
  shoulder (a real shoulder is gravel a few layers down, not pavement with a gravel skin) --
  `EarthGenerator` reads `under()` at depths 1-3, per the ticket's existing instruction.
  `LayeredSlopesCompat` registers two more compat-owned `LayeredBlock`s the exact same way as the
  existing blackstone one (`LAYERED_YELLOW_TERRACOTTA` over `Blocks.YELLOW_TERRACOTTA`,
  `LAYERED_GRAVEL` over `Blocks.GRAVEL`), each with their own copied-and-retextured block/item
  models and blockstate (identical per-layer-count geometry to the blackstone variant; only the
  texture reference differs) under `assets/earthplatformroads`. **No falling gravel**: confirmed by
  reading `LayeredBlock`'s own class declaration directly -- it extends plain
  `net.minecraft.world.level.block.Block`, not `FallingBlock`, so `LAYERED_GRAVEL` never has
  falling-block physics regardless of what it rests on; `RoadCrossSectionTest
  .layeredGravelHasNoFallingBlockPhysics` asserts this structurally (via the class hierarchy
  itself), not just a comment claiming it.
- **Candidate-query margin across tile/cube seams** (an explicit ask): already safe, no change
  needed. `RoadNetwork.buildIndex`'s spatial-index margin is a FIXED `CELL_DEGREES`-sized margin
  (~1.1km) around every segment, chosen to be generous regardless of a way's own width -- even the
  widest new footprint (MOTORWAY, 10.0 paved + 1.0 shoulder = 11 blocks, ~11m) is utterly dwarfed by
  that margin, so a column's cell lookup already finds every segment that could possibly reach it,
  before and after this change.
- **Verification on a non-private fixture.** All of the above was checked against the bundled
  Yosemite Southside Drive extract (`data/earthplatformroads/roads/regions/yosemite_valley.json`,
  SECONDARY class, `hasCenterline = true`) -- never John's own private location.
  `RoadGenerationTest.theRealGeneratedCrossSectionGoesYellowThenBlackstoneThenGravelThenNatural`
  walks away from the pinned centerline through REAL generation (not just the geometry check
  `RoadCrossSectionTest` already covers) and asserts the real blocks actually go yellow-terracotta
  -> polished blackstone -> gravel -> natural terrain, in that order, with a distinct material at
  each zone. `RoadCrossSectionTest.theCrossSectionGoesCenterlineThenPavedThenShoulderThenOffRoad...`
  checks the same ordering property purely geometrically (no game bootstrap). Diagonal/curve
  behavior specifically: the bent-geometry test above, plus the existing
  `theRoadIsContinuousAcrossACubeBoundary`/`aGeneratedShortInclineClosesEveryAdjacentStepToASmallSeamNotAWholeBlockCliff`
  real-generation tests (now exercised against the new, wider footprint) continue to pass unchanged.
  No world/cache migration, and no already-saved terrain touched -- see "Width" above.
- **Not attempted this pass:** junction-specific cross-section behavior (what happens where two
  differently-classed ways' shoulders/centerlines overlap at a mapped junction) beyond what the
  existing per-segment, independent `crossSectionAt` evaluation already does implicitly (whichever
  segment `segmentCoveringColumn` picks first wins that column, same as before this change) --  no
  bundled fixture currently has two crossing/joining ways to exercise this directly, and a dedicated
  junction fixture is listed as a known gap already (see "Still only one bundled region" above).
  A real in-game visual/collision confirmation of the new width and materials, matching the
  existing blackstone layering's own disposable-profile playtest, was not attempted here either --
  this agent's environment has no running Minecraft client.

## MINECRAFT-566: why a flat road deck (comment 55485) is a materially bigger change than it looks

Investigated (not implemented) via CFR-decompiling the real `SurfaceGenerator`/`EarthGenerator`
classes for understanding only (nothing copied from the decompiled source into this codebase).

`SurfaceGenerator.terrain()` derives EVERY block it paints for a column -- top material at depth 0,
`under()` at depth 1-3, natural rock below that -- from `col.height[gi]`, the cached NATURAL ground
height written by `EarthGenerator.ground(int, int, int[])`. `surface()` (what
`EarthGeneratorRoadMixin` already hooks, and the ONLY hook this mod has ever used) only ever picks
WHICH MATERIAL paints at the column's EXISTING natural height -- it has no way to raise where "the
surface" actually is. A flat deck spanning a dip, by definition, needs some columns painted TALLER
than their own natural ground, which means the only real lever is `ground()`'s own output -- and
that same value is what EarthGenerator's cave placement (`below.kind`/`caveField`), structure
placement (`surfaceY`), biome sampling, vegetation/tree decoration, and the water table all derive
from too. Overriding it is categorically different from, and riskier than, the surface-MATERIAL-only
changes this mod has made so far (including everything evaluated and declined for the 55496/55498
residual-race work) -- it is the single most foundational input in the whole generation pipeline,
with a correspondingly wide, hard-to-predict blast radius, and no way to visually verify the result
without a real running game.

**Not attempted this pass**, consistent with 55485's own explicit "if support work makes a larger
iteration, provide the verified styling preview separately with honest limits" -- the styling slice
(comment 55484) stands as shipped; this flat-deck/stone-brick-support work remains queued, now with
a concrete understanding of the real mechanism and risk, not a vague "later."

## MINECRAFT-566: the verify-and-retry's own detector turned out to have false positives (comments 55505/55507)

A compiled-worker run John did (comment 55505) found the bounded verify-and-retry above
(`sectionLooksStale` / `MAX_STALE_RETRY_ATTEMPTS = 3`) rejecting an already-correctly-painted
section twice more than necessary (two wasted extra `generate()` calls); a follow-up diagnostic
(55507) found it specifically flagging columns whose generated block was legitimately natural
terrain (not actually stale at all) as false positives. John: the real fix is comparing the
generation's own INPUT `Surface` decision, or an explicit stale-snapshot signal, rather than
inferring staleness from the final decorated blocks the way `sectionLooksStale` does now; `31c67c5`
stays held from any Zippy installation until that is designed and verified.

Given the deadline and an honest "I do not yet understand the exact root cause either" (comment
55508), this was **not** attempted under time pressure. Instead `MAX_STALE_RETRY_ATTEMPTS` was set
to `0` (`b637907`) -- Java's own `&&` short-circuit means `sectionLooksStale` is never even called
now, so the whole retry mechanism is functionally disabled, not just capped. Shipped behavior
reverts to exactly `c78bf01`'s: evict once, generate once -- the smaller, already-verified fallback
John himself named as acceptable. The detection function and retry loop are left in place,
dormant and still covered by their own direct test
(`RoadTilePrefetchMixinTest.sectionLooksStaleDetectsAMismatchAgainstFreshRoadDataAndClearsOnceFixed`),
so re-enabling is a one-line change once a safer detector replaces `sectionLooksStale` itself.

## MINECRAFT-566: leaf-preserving road-column clearance (comment 55488)

The separable slice comment 55503 explicitly redirected toward once 55485 (flat deck) and the
stale-retry false positives were both correctly judged too risky to bundle into the deadline build.

A column's own road MATERIAL is decided once, at its natural height, by `surface()`
(`EarthGeneratorRoadMixin`) -- but nothing stops ordinary tree/structure decoration from placing a
log, leaf canopy, or other block ABOVE that height afterward, the same way it would over any other
column. `SurfaceGenerator.generate(int,int,int,List,List)`'s real sequence (confirmed by reading the
decompiled class, nothing copied from it): `terrain()` (vegetation decoration included) ->
`Vegetation.bees()` -> `structures.place()` + `recalcBlockCounts()` -> `dropStrayBlockEntities()` ->
return. By the time that last call runs, every pass that could have placed something over a road
column has already happened -- "the actual final generation seam after structures and before
dropStrayBlockEntities" per John's own phrasing.

`SurfaceGeneratorRoadClearanceMixin` `@Redirect`s exactly that `dropStrayBlockEntities` call site
(not an `@Inject` at `RETURN`, which would run strictly AFTER it, nor local-variable capture of
`generate()`'s own `section` local, which would need typing an unused, inaccessible second captured
local (`VanillaStructures`) just to satisfy Mixin's capture-ordering contract). Redirecting a call
to a PRIVATE STATIC target method still lets the handler itself be declared non-static: once woven
into `SurfaceGenerator`, it shares the exact same `this` as the surrounding (non-static) `generate()`
call, with no local capture needed for anything. The handler clears first, via
`RoadCanopyClearance.clear(EarthGenerator, cubeX, cubeY, cubeZ, section)`, then calls the real
(`@Shadow`-declared) `dropStrayBlockEntities` -- clearing BEFORE that call means its own existing
filter (drop a block-entity tag whose block is no longer valid there) correctly cleans up anything
this mod just cleared to air, rather than duplicating that bookkeeping.

`RoadCanopyClearance.clear` scans this one cube's own 16x16 columns (never a separate full-column
edit pass, matching this mod's existing per-cube pattern): for every column whose fresh
`RoadNetwork`/`crossSectionAt` zone is `PAVED` or `CENTERLINE` (never `SHOULDER`, never a column
underwater by the same `surfaceY`/`waterY` check `EarthGeneratorRoadMixin` and `sectionLooksStale`
already use), every non-air block strictly above that column's own natural ground height, up to
this cube's own top, is cleared to air -- except leaves, which are preserved but made
`LeavesBlock.PERSISTENT` (so removing a supporting log in the same pass never leaves them to decay
naturally afterward). Leaf detection checks `state.is(BlockTags.LEAVES)` OR `instanceof LeavesBlock`
(either counts): `instanceof` alone was the first version shipped (`3ce7eb1`) specifically because
the tag read back empty in this project's own `neoForge.unitTest` harness (no real server datapack
reload happens there); John's comment 55510, after independently compiling and testing that exact
build on a real server, pointed out a real gap in that `instanceof`-only version -- a modded leaf
block that carries `BlockTags.LEAVES` without extending vanilla `LeavesBlock` would not be
recognized as a leaf and would get cleared instead of preserved. Checking the tag FIRST (falling
back to `instanceof` only for this harness's own tag-binding limitation) fixes that while keeping
the test suite green.

**Verified** by `RoadGenerationTest.canopyClearanceRemovesNonLeafBlocksOverTheRoadButPreservesLeavesAsPersistent`:
against the same real, non-private Yosemite fixture the rest of this ticket's generation tests use,
a hand-placed log and non-persistent leaf directly above the pinned centerline's own ground height
are cleared/preserved-and-made-persistent respectively, while the identical placements one column
off-road are left completely untouched. **Known, disclosed test gap:** this calls
`RoadCanopyClearance.clear` directly, not through the mixin's own `@Redirect` -- exercising the
mixin's wiring end to end would need a real tree/structure actually landing a block over this exact
pinned column during a real `generate()` call, which is not reproducible from this harness.

**Independently confirmed on a real compiled build** (John, comment 55510): `3ce7eb1`'s canopy
clearance correctly clears a log/stone block above a paved/striped column across both horizontal
and vertical cube seams, preserves real vanilla leaves through actual random ticks (persistence
holds, and reopens correctly once a removed log's own decay control disappears), and leaves
off-road/shoulder/support columns and an existing player-edited log completely untouched. A real
worker's healthy-fetch regression also still correctly placed the centerline stripe.

**Disclosed limitation, per the same comment:** `RoadCanopyClearance` classifies a column purely by
`RoadNetwork`'s own mapped geometry -- it never checks whether `EarthGeneratorRoadMixin` actually
painted the expected road material there (that would mean re-deriving it the way the currently
-disabled `RoadPrefetch.sectionLooksStale` does; see that class's own doc). On the rare column hit
by the still-unresolved residual per-column surface-cache race (55496/55498/55505/55507), this can
clear a column's canopy even though the road surface underneath never actually got painted. This is
a disclosed gap, not a fix for that race -- a clean canopy over a column is not evidence the road
material underneath is correct.

## MINECRAFT-566: a first flat-road-deck prototype (comment 55485, redirected by 55503)

Comment 55510 offered two next directions (narrow bounded pending-data readiness, or a road-local
deck prototype); the deck was chosen -- it has a settled, lower-risk direction already from 55503
("local deck/support placement using sampled natural heights and road-local streaming/save extent,
rather than globally overriding `EarthGenerator.ground`"), while re-entering the stale-cache
detector means re-opening a problem neither John nor I have root-caused yet (see the two sections
above).

The key realization: `RoadCanopyClearance` already proved a SUBTRACTIVE overlay at the post
-generation seam (after `terrain()`/vegetation/structures, before `dropStrayBlockEntities`) is safe
-- it never touches the natural height input, so caves/structures/biomes/vegetation/water are
completely unaffected. A flat deck can be built the SAME way, just ADDITIVE instead of subtractive:
pave a pavement block and stone-brick support strictly ABOVE a column's own natural ground, never
touching `ground()` itself. This is categorically different from, and far less risky than, the
"raise `ground()` itself" approach the earlier section on this same topic declined.

`RoadDeck.deckY` raises a column's own natural ground to the local MAXIMUM natural ground height
among nearby ROAD columns (never non-road terrain) within a small, deliberately conservative 6
-block window -- large enough to fill a short dip (55485's own stated problem), far too small to
turn a road climbing a real mountain grade into one artificial, unified plateau. Never lowered (no
cuttings/embankments-into-hills, consistent with this mod's existing "no cuttings" design decision).
Pure function of coordinates and the generator's own natural heights (never of which blocks have
actually been painted), so it is continuous across a cube boundary and deterministic regardless of
generation order -- the same guarantee `EarthGeneratorRoadMixin`'s own slope layering already
relies on.

Where a column is raised, `RoadDeck.buildSupportAndDeck` fills stone bricks strictly between the
natural ground and the new deck top, then caps it with a PLAIN full pavement block (yellow
terracotta for `CENTERLINE`, polished blackstone otherwise) -- deliberately NOT the layered
slope-compat block `EarthGeneratorRoadMixin` paints at natural height: that layering exists
specifically to smooth a STEP between differently-heighted neighbor columns, and every column
sharing the same `deckY` is, by construction, flat relative to its neighbors -- there is no step
here to smooth, so a plain full block is the materially correct choice, not a simplification.

`RoadCanopyClearance`'s own clearance floor now calls the same `RoadDeck.deckY` instead of the raw
natural height (a pure function, so both callers agree without passing anything between them), and
`SurfaceGeneratorRoadClearanceMixin` calls `RoadDeck.buildAll` BEFORE `RoadCanopyClearance.clear` at
the same seam -- the deck/support this builds is standing by the time clearance computes its own
(now possibly-raised) floor, so it is not immediately cleared out from under itself.

**Scope, deliberately narrow (per the ticket's own "small playable draft, not an all-or-nothing
rewrite"):** PAVED/CENTERLINE only, matching `RoadCanopyClearance`'s own scope -- never the gravel
shoulder. A filled dip currently shows a drop to the shoulder's own natural height at the paved
edge; extending support under the shoulder too is a disclosed follow-up, not attempted here.

**Verified:** `RoadGenerationTest.deckNeverLowersAColumnAndRaisesARealDipToItsHighestNearbyRoadNeighbor`
and `.deckBuildsStoneBrickSupportUnderAPlainFullPavementTop`, against the same real DEM-generated
incline row and pinned centerline point the rest of this ticket's generation tests use: `deckY`
never reports below natural ground, a real dip in that row actually gets raised, and the pinned
centerline column (confirmed to be raised by a real nearby higher road column) gets stone-brick
support directly above its natural ground and a plain full pavement block at the deck top. One
assertion attempted and DROPPED during this work, left out deliberately rather than left in wrong:
"the row's own highest column is never raised further" cannot be verified from a 1D row measurement
alone, since `deckY`'s real window is a full 2D box (every nearby road column, not only ones
sharing the same Z row) -- an off-row neighbor legitimately raised a column the 1D data alone made
look like it shouldn't be, which was the test's own wrong assumption, not a `RoadDeck` bug.

**Not attempted this pass:** no in-game visual confirmation (this agent's environment has no
running Minecraft client); shoulder support (above); junction-specific behavior where two
differently-raised roads cross. This is explicitly a prototype, consistent with the ticket's own
"not an all-or-nothing rewrite."

## MINECRAFT-566: cross-slope acceptance for the flat deck (comment 55513/55514)

John independently compiled `a63063e`'s `RoadDeck` against a deterministic fixture and confirmed,
precisely, what the design note above already flagged as untested: a straight 12-block-wide paved
road running along Z with a real cross/side slope (natural height rising with X, constant along Z)
produced `[70,71,72,73,74,75,76,76,76,76,76,76]` -- a ramped, still-stepped result, not the flat
"twelve 75s" 55485 actually asked for. The isotropic box's reach was simply wrong-shaped: it looks
further ALONG the road than ACROSS it, which fills a dip along the road's own length but cannot
flatten a slope running transverse to it.

**Fixed** by reworking `deckY` (now `RoadDeck.sharedHeight`, with `deckY` as its thin
production-facing wrapper) to use `RoadSegment.projectOnto` -- a new method returning the nearest
point on the centerline to a column (its "station") and the unit tangent there. For a column, the
shared height is now the max natural height sampled ACROSS the road's own paved width at that exact
station (along the perpendicular to the tangent, in 0.5-block steps so a diagonal road's own columns
are not aliased), restricted to samples that are still the SAME segment (`.equals`, never a nearby
unrelated road -- addresses 55513's own "no influence from... a nearby unrelated road") and still
PAVED/CENTERLINE (never the shoulder -- addresses "no influence from the uphill shoulder"). Every
column sharing a station therefore computes the identical shared height, which is what makes the
deck flat ACROSS the road while a DIFFERENT station still gets its own, independently-computed
height -- longitudinal grade along the road is preserved, only the transverse profile is flattened.

**Verified** against John's own exact fixture: `RoadCrossSectionTest.sharedHeightIsFlatAcrossARealCrossSlopeNotRampedByAnIsotropicWindow`
reproduces it precisely (pavement x=0-11, gravel shoulder at x=-1/12, natural height 64+x) and
asserts all twelve columns return 75, matching his own named acceptance exactly, not an
approximation. Two more cases from his explicit ask: `.sharedHeightStaysBoundedAndChangesGraduallyAlongADiagonalRoad`
(a genuine 45-degree road; exact equality is not meaningful for a diagonal, since sampling rounds to
the nearest block, so this instead checks the height is never below natural ground and never jumps
more than one block between adjacent stations) and `.sharedHeightStaysFlatAcrossARealCubeBoundary`
(the same straight fixture repositioned so its paved width straddles x=15/16). All three run as pure
geometry (no Minecraft bootstrap, no live `RoadNetwork` index) by extracting `RoadDeck.sharedHeight`
with the natural-height lookup and the same-road check both injected -- `RoadDeck.deckY` itself
supplies the real ones (`EarthGenerator::surfaceY`, the live spatial index) in production.
`RoadGenerationTest.supportAndDeckAssembleCorrectlyAcrossARealVerticalCubeSeam` covers 55513's
"verify one vertical-seam save/reopen" ask: a synthetic column whose natural ground and deck top
land in different cubes, built via two independently-constructed `LevelChunkSection`s (the closest
proxy available -- this harness has no real `ServerLevel`/save-to-disk path, a disclosed, standing
gap noted elsewhere in this document), confirmed to regenerate identically on a repeat build.

**Performance** (per 55513's own ask, "share computation... if it remains expensive," explicitly
named as a source-based operation count rather than measured timing, since this environment has no
profiler and no running client to measure a representative section against): the transverse sampler
visits roughly `2 * pavedHalfWidthBlocks / 0.5 + 1` points per column (about 23 for a 5.5-half-width
road) versus the old isotropic box's fixed 13x13-1=168 neighbor checks -- an roughly 85% reduction
per call, and `RoadDeck.buildAll`/`RoadCanopyClearance.clear` each still call this once per covered
column (reusing the exact same deterministic function rather than caching a value between them, per
the existing "pure function" pattern this whole mixin pair already relies on for correctness).

## MINECRAFT-566: three further gaps found on d179c93, one attempted and reverted (comment 55516)

John independently compiled `d179c93` against his own fixture and confirmed the transverse sampler
now yields a correct flat Y and that support/cap survive a close/reopen without regenerating the
lower section -- but found three further, real problems:

1. **Layer-count mismatch.** Actual layer counts across the twelve paved columns came back
   `[16,16,16,16,16,16,16,16,16,16,16,1]`: the natural-high column (already AT the shared height,
   so `buildSupportAndDeck` never touches it) keeps whatever partial slope-compat layer
   `RoadSurfaceLayering` had already decided for it, while its now-raised neighbors get a plain full
   cap at the same Y -- visually a thin sliver sitting beside eleven full blocks.

   **Attempted and REVERTED.** `RoadDeck.computeStation`/`Station` now tracks
   `crossSlopeDetected` (true whenever ANY sample across the width differs in natural height from
   any other, detected via min != max among qualifying samples) -- correct and directly verified
   (`RoadCrossSectionTest.stationFlagsCrossSlopeOnlyWhenTheWidthActuallyVaries`). The first attempt
   at using it made `buildSupportAndDeck` repaint even a non-raised column's cap whenever its own
   station was cross-sloped, to force every column at the same station onto one uniform material.
   This BROKE an existing, already-verified regression test --
   `RoadGenerationTest.aGeneratedShortInclineClosesEveryAdjacentStepToASmallSeamNotAWholeBlockCliff`
   started failing with a full-block gap at a real incline column, because forcing a flat cap on a
   column just because its OWN cross-section happens to be sloped can introduce a NEW longitudinal
   step against an adjacent, differently-raised station that `RoadSurfaceLayering` was correctly
   smoothing before this change existed. Reconciling "flat across the road" with "smooth along the
   road" at the SAME time needs a genuine 2D blended height field, not an independent per-column
   repaint decision -- real design work, not available in the remaining time. The repaint was
   reverted (`buildSupportAndDeck`'s guard is back to `deckY <= naturalY`, ignoring
   `crossSlopeDetected`); `Station`'s detection itself is correct and kept as a building block for
   whoever does that real fix, with
   `RoadGenerationTest.theNaturalHighColumnOfACrossRaisedStationIsNotYetRepainted` pinning the
   CURRENT, disclosed-imperfect behavior so that future fix has a clear regression signal.

2. **Shoulder gravel at the supported edge.** The low-side shoulder keeps its own natural gravel at
   its own (unraised) natural height, which can sit well below a large raise on the paved edge next
   to it -- John: "suppress gravel only at the supported edge and retain grounded shoulders." Not
   attempted this pass; shoulder handling was already an explicitly disclosed gap before this
   comment, and extending it correctly (telling "the supported edge" apart from "a grounded
   shoulder" needs the same per-station reasoning as item 1) is new design work, not a quick patch.

3. **Diagonal longitudinal collision not actually established.** The existing diagonal test
   (`sharedHeightStaysBoundedAndChangesGraduallyAlongADiagonalRoad`) only asserts the integer shared
   height changes by at most one block between adjacent stations; John correctly points out this
   does not establish smooth COLLISION continuity the way
   `aGeneratedShortInclineClosesEveryAdjacentStepToASmallSeamNotAWholeBlockCliff` does for the
   existing natural-height case (fractional collision-top gap, not just an integer Y). Not
   strengthened this pass.

**Disposition:** given the deadline and that this is now the third round of real correctness gaps
found in three successive reviews of the SAME prototype, and given John's own explicit instruction
("Keep b7ff716 as the ready fallback until these are addressed"), this flat-deck work stays an
explicitly disclosed, NOT-ready prototype. `b7ff716` (canopy clearance only, independently verified
clean on a real compiled build) remains the actual candidate for anything beyond continued review.

## MINECRAFT-566: a fourth, separate bug -- record equality across independently-parsed tile copies (comment 55518)

While `fd8cea5` was held, John found a FOURTH, genuinely separate problem (his own words: "independent
of actual fractional cap continuity or shoulder omission"), reproduced in an exact compiled build
with two offline tile cache files and no provider: `RoadDeck`'s own "is this sample point still THIS
road" check used plain `RoadSegment` record `.equals`. A record's auto-generated `equals` compares
`centerline` (a `GeoPoint[]`) by ARRAY REFERENCE, not content -- so the SAME real way, independently
parsed from two different tile/cache files (concretely: "the first copy drops out of the relative
3x3 lookup halo"), produces two `RoadSegment` instances with identical VALUES but distinct backing
arrays, and `.equals` wrongly rejects them as different roads. The observed effect: a station's west
side (loaded via one tile's own copy of the way) could not see the east side's higher ground (loaded
via a DIFFERENT tile's copy of the SAME way) at all -- actual station heights came back west 100 /
east 108, with the west side's pavement staying at Y100 and nothing painted at the intended Y108.

**Fixed** with `RoadSegment.isSameRoadAs`: matches on the real way's own stable, positive OSM
`wayId` when both sides have one (the one piece of identity that survives being independently
re-parsed from a different file), falling back to full record equality only when either side's
`wayId` is not a real one (`<= 0`, the existing pinned/test-fixture convention) -- so distinct
synthetic test roads that all happen to share `wayId == 0` are never incorrectly merged, exactly
John's own "leave distinct ID-less test roads distinct" ask. `RoadDeck.station`/`deckY` now call
`segment.isSameRoadAs(...)` instead of `segment.equals(...)`.

**Verified**, per John's own explicit ask for "an offline two-file halo-boundary regression with
unrelated-way control": `RoadSegmentTest.isSameRoadAsMatchesByWayIdEvenWithDistinctBackingArrays`
(two separate instances, same wayId, genuinely distinct backing arrays -- confirms `.equals` itself
still rejects them, so this is really exercising the fix and not a tautology),
`.isSameRoadAsRejectsADifferentWayIdEvenWithIdenticalGeometry` (the unrelated-way control -- a
different real wayId must never merge, even with identical geometry), and
`.isSameRoadAsLeavesDistinctIdLessTestRoadsDistinct`. End to end, through `RoadDeck.station` itself,
`RoadCrossSectionTest.stationMatchesTheSameWayAcrossTwoIndependentlyParsedInstancesAtAHaloBoundary`
reproduces John's exact west-100/east-108 shape (two `RoadSegment` instances, same wayId, each only
"visible" for its own half of the road) and confirms the west column now correctly discovers the
east side's higher ground through the other instance -- with a second assertion in the same test
documenting the actual pre-fix behavior (plain `.equals` never discovers it) side by side, so the
bug this fixes is pinned, not just asserted away. 96/96 tests pass.

## MINECRAFT-566: a fifth, separate finding -- ordinary-movement fetch-admission priority race (comment 55522)

John found a genuinely different, deeper problem via a real NeoForge/combined-73 loopback
reproduction (not the deck, not canopy clearance): in the ORDINARY-movement prefetch path
(`RoadTileSource`/`RoadPrefetch`), a speculative lookahead fetch (tile A) can hold the only
available admission slot/cooldown window while the CURRENT cell's own fetch (tile B, the one
generation is actually about to consume) never gets to dispatch its own request at all. When
generation runs before B's data arrives, it falls back to natural terrain (grass) -- the known,
accepted bounded-wait limitation -- but then B's own queued job gets dropped unfulfilled once the
provider's cooldown window passes, rather than being retried. By the time B eventually DOES fetch
successfully, the grass cube has already been adopted/saved; reopening it from native
`CubeStorage` just replays the saved grass, since this mod never retrofits already-saved terrain
(an existing, repeatedly-stated design constraint, not something this finding changes).

Suggested fix direction (John's own, not yet attempted): prioritize a cell's own queued immediate
job over a speculative lookahead competing for the same slot; keep that demand pending across
cooldown windows instead of dropping it outright; bound any retry to cubes that are both unsaved
and still genuinely pending (never an already-saved cube, never an unrelated one). Explicit
boundaries he named: preserve the existing confirmed-empty/failure/outage distinctions and the
sustained-outage fail-open behavior; never block the game/server thread on HTTP; never rewrite a
saved world; this does not change anything about the deck's own (separately disclosed,
not-promoted) status.

**Not attempted.** Properly verifying a change to fetch-admission scheduling needs exactly the real
dispatch-timing/native-reopen testing John describes against an actual dedicated server -- this
agent's environment has neither a running Minecraft client nor a real dedicated server to drive
`ServerCubeWorld`/`ServerLevel` through (the same standing gap this document has noted since
earlier in this ticket: `neoForge.unitTest` is a plain JUnit JVM, not a running server). Shipping an
unverified concurrency/scheduling change this close to the deadline, with no way to confirm it
myself against the real timing this bug is actually about, was judged worse than leaving it
disclosed for whoever picks it up next. `b7ff716` (canopy clearance only) remains the confirmed,
ready candidate -- untouched by this finding.

## Compile/test-time-only dependency, not a fork

Earth Platform's source is never copied, patched, or vendored into this
repo. `scripts/build_earth_platform_dependency.sh` builds its pinned
`combined-73` tag (release 0.1.0-alpha.73) exactly as upstream does and
drops the resulting jar at `libs/`, gitignored. This mod's own code imports
only Earth Platform's public API (`EarthGenerator.grid()`,
`.settings()`, `.surfaceY()`, `.generate()`, `SurfaceGenerator.Surface`,
`.DRY`, and the `geography` module's `GeoPoint`/`MercatorGrid`).
