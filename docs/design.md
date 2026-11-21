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

## MINECRAFT-566: ordinary-movement fetch-admission priority and cooldown retry (comments 55522/55526)

A SEPARATE branch from the flat-deck prototype, built from `b7ff716` (canopy clearance only) per
John's own explicit instruction once his own compiled-build testing confirmed that commit looked
good on Zippy: "keep this based on the accepted b7 canopy branch (exclude unfinished deck) for a
clean preview." This slice does not touch `RoadDeck`/`RoadSegment.projectOnto`/`isSameRoadAs` at
all -- those stay on the other branch, explicitly not promoted here.

**The bug (comment 55522, reproduced via a real loopback NeoForge/combined-73 fixture, not a live
route):** a speculative lookahead fetch (tile A) and the CURRENT cell's own bounded-wait fetch
(tile B) both ultimately run through `RoadTileSource`'s single dedicated `ASYNC_FETCH_EXECUTOR`
thread. The first version used a plain FIFO queue (`Executors.newSingleThreadExecutor`'s default),
so a lookahead job queued moments before an immediate job's own request could make that immediate
job wait behind it for a thread-scheduling slot it did not need to wait for -- observed concretely:
B's own task sat queued (no POST sent at all) while A's request was in flight; by the time A
released the thread, B's own attempt landed exactly inside this instance's own steady
`minRequestInterval` cooldown and was declined outright (a single-shot attempt, not retried);
generation proceeded with natural terrain (grass) while B's data was still genuinely PENDING; and
once B eventually did resolve, the already-adopted grass cube had already been saved, so a native
reopen just replayed it -- this mod never retrofits an already-saved column, by existing design.

**Fixed, two parts:**
- **Priority.** `ASYNC_FETCH_EXECUTOR` is now a `ThreadPoolExecutor` backed by a
  `PriorityBlockingQueue<PriorityTask>` (a small `Runnable`/`Comparable` wrapper tagging each
  submission `PRIORITY_IMMEDIATE` or `PRIORITY_LOOKAHEAD`, ties broken by submission order).
  `waysForCellWithBoundedWait` and `enqueueLookaheadAsync` submit `PriorityTask`s directly via
  `execute` (never `supplyAsync`/`submit`, which would wrap the task in a type the priority queue
  cannot compare) so the object the queue actually orders is the one whose priority matters. This
  cannot preempt a job already RUNNING -- the provider's own `networkLock` parallel-1 limit means a
  genuinely in-flight request must finish regardless of what arrives after it -- but it does stop a
  newly-queued lookahead job from jumping ahead of an immediate job that is, or arrives, still
  waiting in the queue.
- **Retry across cooldown.** A new `waysForCellRetryingAcrossCooldown`, used only by
  `waysForCellWithBoundedWait`'s own dispatched task (never by the plain, single-shot
  `waysForCell`), retries ONLY while the reason nothing resolved is genuinely this instance's own
  steady cooldown (`nextEligibleRequestAt` still ahead of now) -- sleeping exactly until that
  cooldown or this call's own deadline, whichever is sooner, never longer, and bounded to a small
  fixed number of retries regardless. A real ATTEMPTED failure (a request that was actually
  dispatched and failed) is deliberately NOT retried here, so a persistently-failing provider cannot
  be hammered by this loop -- `consecutiveAttemptedFailures`/`sustainedOutage` already track that
  case on their own terms. The sleep happens on `ASYNC_FETCH_EXECUTOR`'s own background thread,
  never the caller's (generation worker) thread, so it never blocks generation.

**Verified**, against a local HTTP stub (never a real live route): `RoadTileSourceTest
.immediateRequestsArePrioritizedOverQueuedLookaheadRequests` (an "occupier" request holds the
executor's one thread busy; a lookahead and then an immediate request are queued, in that order,
while it is busy; the immediate request's own HTTP request must land before the lookahead's once
the thread frees up) and `.boundedWaitRetriesAcrossASteadyCooldownRatherThanGivingUpOnTheFirstDecline`
(a bounded-wait call whose first attempt lands during a short, real cooldown from an unrelated
immediately-preceding fetch must retry and actually succeed, not give up on the first decline). Both
tolerate `ASYNC_FETCH_EXECUTOR` being shared process-wide (see its own class doc) by using generous
bounds/polling rather than tight timing assertions, after an earlier, tighter version of the
cooldown test was observed to fail when run alongside this file's other, slower tests (a real,
confirmed cross-test timing interaction via that shared executor, not a bug in the fix itself --
isolated reruns of the exact same assertions passed cleanly). 86/86 tests pass (2 new).

**Explicitly out of scope, per John's own boundaries:** the deeper "bound deferral of only unsaved
road-relevant surface cubes" half of 55522's ask -- a generation-level mechanism to actually RETRY
a cube's generation once pending data resolves, rather than only improving how promptly the FETCH
itself resolves -- was not attempted. That would need the same kind of "verify output against fresh
data, retry generation" approach already found buggy once before (the currently-disabled
`sectionLooksStale`/stale-retry mechanism on the other branch) -- real additional design work, not
a quick extension of this fix. The forced-load (`loadNow`) exception from comment 55524 is
unchanged and already documented/tested (`SynchronousLoadGuard`/`SynchronousLoadGuardTest`, both
predating this slice) -- `ServerCubeWorldRoadPrefetchMixin`'s own class doc now cross-references
John's confirmation of it rather than duplicating that coverage; deliberately not changing
`loadNow`'s own synchronous contract, per his explicit "do not... change Sable/portal semantics"
instruction. No server-thread HTTP, no provider-cadence bypass, no edits to existing saves, and the
flat-deck prototype remains untouched and not promoted by this branch.

## MINECRAFT-566: the cooldown-retry fix itself had a real bug (comment 55528)

John's own independent loopback reproduction of `5a4e1b2` found exactly the kind of bug this
pattern invites: `waysForCellRetryingAcrossCooldown` inferred "was this attempt just declined by
cooldown?" purely from whether `nextEligibleRequestAt` was still ahead of now -- but
`fetchCell` advances that instant by the steady cadence on EVERY dispatched request, successful or
not, since it is set right after sending, before the response status is even checked. A genuinely
ATTEMPTED failure (an HTTP 500 that really was sent and really did fail) therefore left the exact
same signal a cooldown-declined attempt would -- `waysForCell` itself discarded the real
`FetchOutcome` entirely, so the retry loop had no way to tell them apart, and retried (sending a
SECOND real request) after a genuine failure too. Concretely reproduced: four fresh demands with a
1.8s budget and 200ms cadence against a deliberately-failing endpoint gave POST counts `1,1,1,2` --
a real duplicate request roughly 206ms after the first.

**Fixed** by actually propagating the outcome: a new private `waysForCellDetailed`/
`FetchAttemptResult` record returns the real `FetchOutcome` alongside the result, and
`waysForCellRetryingAcrossCooldown` now checks it directly -- retrying ONLY on
`DISPATCH_SUPPRESSED`, never on `RESOLVED` or `ATTEMPTED_FAILURE`. The public `waysForCell` API is
unchanged (still just returns the ways list; every other caller is unaffected). Also added a 1ms
floor to the sleep duration, per John's second (unconfirmed, "plausible cause") observation that an
early sub-millisecond wake could consume a retry attempt without actually advancing real wall-clock
time, risking exhausting the bounded retry count before the real cooldown passes.

**Verified**: a new `RoadTileSourceTest.boundedWaitNeverRetriesAGenuineAttemptedFailureEvenWithPlentyOfBoundLeft`
asserts a bounded-wait call against a provider that genuinely fails (HTTP 500) sends EXACTLY one
real request and comes back empty, even with a generous bound that would easily have covered a
cooldown-driven retry -- the exact shape of the bug this fixes. 87/87 tests pass (1 new), stable
across repeated reruns of the full suite.

**Not attempted**, per John's own "if feasible" framing (not the confirmed bug, and not required):
this retry loop still occupies `ASYNC_FETCH_EXECUTOR`'s one shared thread for the full duration of
each sleep -- no other queued task, immediate or lookahead, can run until this call's own
`Runnable.run()` returns, since sleeping does not yield the thread to a different queued task.
Avoiding that would need scheduling a resubmission after the cooldown rather than sleeping in
place -- a real further improvement, left as a disclosed, not-yet-attempted gap rather than a
rushed partial redesign.

## MINECRAFT-566: the deferred "pending surface-cube" half of 55522, now attempted (comment 55526)

Once John's own native loopback verification confirmed the fetch-side fix (priority + cooldown
retry, including the 55528 correction) actually holds, he directed moving on to the deeper half of
55522 that was deliberately deferred earlier: "proceed with the narrow unsaved surface-cube
defer/retry and explicit bounded fail-open ... keep forced loads as an explicit separate control."
He also offered to verify actual paint and native save/reopen himself on his own infrastructure,
which removes the earlier blocker (no real dedicated server in this environment to verify
dispatch/block/save-reopen against).

**Design, deliberately narrow:** added to `ServerCubeWorldRoadPrefetchMixin`'s existing `@Redirect`,
AFTER the first `generate()` call and the (still disabled) stale-retry loop, as its own separate
bounded loop -- never merged with or extending the stale-retry mechanism, which is a DIFFERENT
problem (a stale per-column cache entry) with a DIFFERENT, already-found-unreliable detector
(comparing decorated output against fresh geometry). The new detector,
`RoadPrefetch.isPendingForColumn`, is a direct read of `RoadTileSource`'s own authoritative
`CellState` for the cube's own center cell -- no inference from decorated blocks at all, avoiding
exactly the false-positive risk `sectionLooksStale` had.

Only runs for the ordinary-streaming path -- `SynchronousLoadGuard.isActive()` gates it out
entirely for `loadNow`'s own arrival, matching John's explicit "keep forced loads as an explicit
separate control." Only ever touches a cube THIS call is producing fresh: `produce`'s own
storage-read fast path above this injection means an already-saved cube never reaches here, so this
can never retrofit one. Bounded two ways at once -- a small fixed attempt count
(`MAX_PENDING_DEFER_ATTEMPTS`, 2) AND a real wall-clock window (`PENDING_DEFER_WINDOW`, 3 seconds
total across every attempt combined, not per-attempt) -- and an EXPLICIT fail-open the moment either
bound runs out, or a re-dispatched wait comes back still pending: accept whatever the last attempt
produced, exactly like any other pending/failed cell already does. Re-dispatching the same cell
reuses `RoadPrefetch.forColumnBounded`, which is itself coalesced via `RoadTileSource`'s own
in-flight map -- this never sends a second real request while the first is still outstanding.

**Verified**: `RoadTilePrefetchMixinTest.isPendingForColumnReflectsTheRealCacheStateDirectly`
proves the new detector against all three real `RoadTileSource.CellState`s directly (a never-touched
cell is genuinely PENDING; a cell with a real ready marker, or a confirmed-empty marker, is NOT).
The mixin's own injection points remain the SAME known, disclosed test gap this class's doc has
named since earlier in this ticket -- no real `ServerCubeWorld`/`ServerLevel` exists in this
project's `neoForge.unitTest` harness to drive `produce`/`generate` through directly; per John's own
offer, actual paint continuity and native save/reopen will be verified on his own infrastructure,
not here. 88/88 tests pass (1 new).

## MINECRAFT-566: a real race in the pending-surface-cube defer/retry (comment 55532)

John reproduced a race natively on `combined-73` against the 55526 commit: the first real
generation for a column produced grass at 4211ms; the held loopback tile became READY at 4302ms
(exactly one POST sent); the FIRST actual pending probe the defer loop ran did not fire until
4305ms and, by then, correctly reported `false` (not pending) -- so the loop never retried at all,
even though the output it was about to accept had been generated while the tile was still
genuinely pending. A second, identical fresh generator reproduced against the same resolved tile
painted the road correctly, confirming the data itself was fine -- only the TIMING of the check
was wrong. Worker generated exactly once; stale grass survived a native save/reopen.

**Root cause:** the 55526 version called `isPendingForColumn` only AFTER the first accepted
`generate()` call returned. That call itself can take long enough that the tile transitions
PENDING -> READY *during* it (here, within roughly 91ms of the generate call starting). By the
time the post-hoc check runs, it is looking at the CURRENT (now-ready) state, not the state the
already-completed `generate()` call actually saw -- "not pending" was the correct answer to the
wrong question. No amount of retrying the check itself fixes this: the defect is in checking
AFTER rather than BEFORE.

**Fix:** snapshot `wasPendingBeforeAcceptedAttempt = RoadPrefetch.isPendingForColumn(...)`
immediately before calling the real, accepted `generate()` -- never re-derived afterward -- and
gate the retry decision on that snapshot. This correctly retries exactly the case 55532 found (an
attempt that ran against genuinely-pending input, even if the tile resolved moments later) and
correctly skips retrying when the snapshot was already READY (the ordinary, non-racy case,
unchanged from 55526). Because the snapshot can only ever be true once per accepted attempt, one
retry attempt is structurally sufficient -- `MAX_PENDING_DEFER_ATTEMPTS` is removed rather than
tuned; the only remaining bound is the wall-clock `PENDING_DEFER_WINDOW` (3s total).

Two more gates John asked for explicitly, both straightforward: `RoadPrefetch.hasLiveTileSource()`
(no live tile source configured at all -- skip the whole mechanism, nothing will ever resolve)
and `RoadPrefetch.cubeContainsNaturalSurface` (a road is only ever painted at a column's own
natural ground height, so a cube whose Y-range does not even contain that height can never show
one regardless of pending state -- the 55526 loop had no such filter and would have deferred for
cubes that could never possibly benefit). Also corrected the retry's own wait: 55526 passed a
fresh `PREFETCH_WAIT_BOUND` (2s) into the re-dispatch on every attempt, rather than the REMAINING
portion of the 3s `PENDING_DEFER_WINDOW` total budget -- fixed to compute and pass what is
actually left.

No change to `loadNow`'s forced-load path (`SynchronousLoadGuard` still gates it out of this
entire mechanism) or to any no-HTTP saved/forced semantics, per John's explicit instruction.
89/89 tests pass. The same disclosed test gap as 55526 remains: no real `ServerCubeWorld`/
`ServerLevel` fixture exists in this harness to drive `produce`/`generate` end to end, so this fix
is verified directly against `RoadPrefetch`'s own new detectors/gates; actual paint continuity and
native save/reopen will be reverified on John's own infrastructure. Not yet confirmed ready for
Zippy.

## MINECRAFT-566: two more real gaps in the 55532 fix (comment 55534)

John's native checks against `54cc747` confirmed the race fix itself holds (one POST, correct
stripe, correct native reopen, both controlled PENDING->READY timings), but found two more real
issues in the surrounding gating:

1. **Initial dispatch wasn't gated.** The `liveAndRelevant` gate (`hasLiveTileSource` +
   `cubeContainsNaturalSurface`) only ever covered the LATER defer retry; the INITIAL
   `RoadPrefetch.forColumnBounded` dispatch before the first accepted `generate()` call ran
   unconditionally whenever `!SynchronousLoadGuard.isActive()`. John's native checks: a deep cube
   and a sky cube each still sent their own initial POST. Fixed by computing the gate once, before
   the initial dispatch, and reusing it for both call sites.

2. **`hasLiveTileSource` conflated "exists" with "can fetch."** A cache-only install
   (`allowNetwork=false` -- e.g. the "not opted into the preview provider" default) is a real,
   non-null `RoadTileSource` that serves already-cached reads correctly, but every fetch attempt on
   it returns `DISPATCH_SUPPRESSED` immediately -- it can never resolve a pending cell through its
   own network call. The plain non-null check reported such an instance as "live," so John's native
   check against one found it making zero POSTs while still entering this mixin's wait scheduling.
   Fixed with a new `RoadTileSource.canFetchLive()` accessor (`allowNetwork` itself) that
   `RoadPrefetch.hasLiveTileSource()` now checks instead of mere non-null existence --
   `isPendingForColumn`'s own cached-read behavior is deliberately untouched, so a cache-only
   install's already-fetched data still reads normally.

Also fixed while addressing (1): the defer window's deadline was computed via `Instant.now()`
AFTER the first accepted `generate()` call had already returned, so `PENDING_DEFER_WINDOW` (3s)
only ever bounded the retry's own slice of time, not the method's real total extra latency
(initial dispatch wait + first `generate()` + retry wait). John's own check: a held response failed
open at 4.26s against the nominally-3s window. Fixed by capturing the deadline once, before the
initial dispatch, so the window is now a genuine aggregate budget across the whole method.

89/89 tests pass (one updated to cover the cache-only-is-not-live distinction directly, alongside
a new positive case for an actually live-capable configured source).

## MINECRAFT-566: the Y-filter needed the full footprint, not one center column (comment 55537)

John's native checks against `88ecd8f` confirmed the 55534 fixes hold: deep, sky, and a
preview-off cache-only instance all correctly bypass both the initial dispatch and the defer retry,
sending zero POSTs. A new "positive control" fixture then failed: a column whose own natural ground
height is Y79 at the sampled CENTER, but an off-center column within the SAME real road footprint
(the paved edge or shoulder a block or two out) has its own natural ground height at Y80 -- landing
in a DIFFERENT cube. With the cold "upper" (Y80-containing) cube requested first, the OLD
center-only `cubeContainsNaturalSurface` check reported it as Y-irrelevant (only the center's own
Y79 mattered to that check), so the worker adopted grass with zero dispatch at all -- not even the
old, un-gated initial fetch from before 55534. The "lower" cube (relevant by the center check)
correctly fetched once and a fresh generator painted the correct layered stripe for IT, but the
upper cube's grass, already saved before any fetch was ever attempted, survived native reopen
permanently.

**Root cause:** a real road footprint (paved surface + centerline + gravel shoulder) is not flat --
different columns across its width can have different natural ground heights, sometimes enough to
fall in different cubes. Sampling only ONE column (the cube's geometric center) to decide "is this
cube Y-relevant at all" cannot see that some OTHER column in the same cube, elsewhere in the
footprint, needs a completely different cube's worth of Y-range.

**Fix:** `RoadPrefetch.cubeContainsNaturalSurface` no longer takes a single block column. It now
takes `(cubeX, cubeY, cubeZ)` and scans the cube's own full 16x16 column grid -- the same per-column
iteration `sectionLooksStale` already uses for the same underlying reason -- looking for ANY
road-covered column (`RoadNetwork.segmentCoveringColumn` non-null, and not
`CrossSection.OFF_ROAD`) whose own natural ground height, padded a fixed, conservative
`BASE_PAVING_PADDING_BLOCKS` (3) below, falls inside this cube's Y-range. The cube is relevant the
moment ANY such column qualifies -- not just the center sample. The 3-block padding is a fixed
constant, deliberately not computed from Earth Platform's own private, memoized `Surface`
decoration (per John's own "no material-bearing upstream surface memo" framing) -- the same stance
this whole mixin pair already takes toward that upstream cache (see `sectionLooksStale`'s own doc).

Verified directly: a new synthetic fixture way (never one of John's own real test locations) proves
the scan finds a road-covered column and correctly gates both the containing cube and cubes 50
blocks above/below it, plus a new negative case (a cube with no road-covered column in its own
footprint at all is never relevant regardless of Y). The EXACT natural-terrain height variation
John's native fixture reproduces (two real DEM-derived heights, one block apart, landing in
different cubes) was not independently reproduced here -- this harness has no DEM-mocking seam to
force that deterministically -- but the fix's correctness does not depend on knowing in advance
which column in the cube has the differing height; it depends only on scanning the whole footprint
instead of one sample point, which the new tests directly exercise. 89/89 tests pass (two new
cases, one updated to the new 3-argument signature with a real on-road fixture rather than an
arbitrary off-road sample point).

## MINECRAFT-566: the Y-filter cannot require mapped geometry before a fetch (comment 55539)

John's native checks against `0c4521c` ran an "exact cold-bootstrap" regression: no bundled or
adjacent geometry, no cache files, no earlier prepare call -- a genuinely fresh install's first walk
into fresh terrain, with a healthy loopback provider ready to respond. BOTH real workers (a plain
flat case, and the 55537 upper-first case) saved grass with ZERO prefetch calls and ZERO POSTs at
all, tile left genuinely PENDING. A separate, explicit fixture-only fetch then correctly painted
yellow pavement in fresh versions of both -- confirming the provider and data path are fine; only
this gate's own precondition was wrong.

**Root cause:** the 55537 fix required each sampled column to be `RoadNetwork.segmentCoveringColumn`
-covered before its natural ground height counted toward relevance at all. But mapped geometry for
a cell only exists AFTER that cell has been successfully fetched -- a cell that is still genuinely
PENDING (never fetched) has NO geometry yet, by construction. Requiring geometry to decide whether
this cube is even worth fetching FOR is circular: the gate was being asked "is there a road here"
using exactly the information a successful fetch is what would produce. Every ordinary, healthy,
never-before-visited cube failed this check and the entire defer mechanism (including the INITIAL
dispatch, now that 55534 shares the same gate) was skipped -- the worst possible regression for
precisely the ordinary-movement-into-uncached-tiles case this whole ticket exists to fix.

**Fix**, per John's own direction ("use natural-only support relevance before fetch... reserve
mapped geometry for resolved tiles if useful"): `RoadPrefetch.cubeContainsNaturalSurface` no longer
calls `RoadNetwork.segmentCoveringColumn` or looks at `CrossSection` at all. It scans the cube's own
16x16 column footprint for NATURAL ground height alone (via `EarthGenerator.surfaceY`, already
geometry-independent), padded the same fixed `BASE_PAVING_PADDING_BLOCKS` (3) below. This is more
permissive than "a road is mapped here" -- it cannot know that before a fetch resolves -- but still
correctly excludes a cube that is genuinely deep underground or high in the sky relative to this
whole footprint's own natural terrain, which is the only question this gate can safely answer
before a fetch. Using mapped geometry to narrow this further once a tile IS already resolved is a
disclosed possible future refinement, not required to fix this bug and not attempted.

Also fixed per the same comment: `liveAndRelevant` now short-circuits on
`SynchronousLoadGuard.isActive()` FIRST, so `loadNow`'s own arrival path never pays for the (now a
real 16x16 scan) cost of computing this gate at all -- it was previously computed unconditionally
even though its result was never used on that path.

The test is rewritten to an entirely cold boot: no fixture file, no cache file, no HTTP stub, the
tile source genuinely `PENDING` throughout (asserted directly), proving the gate reports a cube
relevant using nothing but natural terrain -- exactly the precondition this bug violated. 89/89
tests pass.

## MINECRAFT-566: the defer budget is now cadence-aware, not a fixed guess (comment 55535)

John's native round against `54cc747` (pre-55534) found the "slow speculative" shape still failing
under the provider's own DEFAULT steady request cadence (longer than the then-fixed 3s
`PENDING_DEFER_WINDOW`): a queued fetch for the demanded cell got delayed behind an earlier request
by the cadence itself (not a bug -- real rate limiting), the fixed defer budget ran out well before
that real cooldown cleared, generated grass, and -- because `produce`'s storage-read fast path means
an already-saved cube never reaches this injection again -- that grass was PERMANENT on reopen. A
later, explicit fetch after the real cooldown correctly resolved the same cell, confirming the data
and fetch path were fine; only the fixed timing budget was wrong, structurally -- it could not be
both short (fail a genuine outage open quickly) and long enough to survive an entirely ordinary
cooldown, because it had no way to know which situation it was actually in.

**Fix:** the defer budget is now computed DYNAMICALLY, once per call, instead of a fixed constant:
- `RoadTileSource.timeUntilNextEligibleRequest()` (new, instance-wide, read-only) reports the REAL
  remaining steady-cadence cooldown, `Duration.ZERO` once it has cleared.
- `RoadPrefetch.timeUntilNextEligibleRequest()`/`RoadPrefetch.sustainedOutage()` expose that and
  `RoadTileSource.sustainedOutage()` the same way `hasLiveTileSource` already does.
- `ServerCubeWorldRoadPrefetchMixin` computes `deferBudget` once, before the initial dispatch: if
  `sustainedOutage()` (several real, dispatched failures in a row -- a provider genuinely known to
  be down), clamp to the short `PREFETCH_WAIT_BOUND` (2s) rather than waiting out a cooldown that
  will not help; otherwise, `timeUntilNextEligibleRequest() + DEFER_ROUND_TRIP_MARGIN` (2s, for the
  request/response itself once the cooldown clears), clamped to a hard `MAX_DEFER_WAIT` ceiling
  (10s) regardless of cadence. This budget is used as BOTH the initial dispatch's own bound and the
  total aggregate the later retry's remaining-portion calculation is drawn from (the 55534 fix's own
  aggregate-budget structure, preserved) -- "a deliberate finite outage policy," per John's own
  framing, rather than either extreme.

Verified directly: a new `RoadTileSourceTest` case proves `timeUntilNextEligibleRequest` tracks a
real steady-cadence cooldown (positive right after a dispatch, zero once it genuinely elapses); a
new `RoadTilePrefetchMixinTest` case proves `RoadPrefetch`'s own wrappers delegate correctly and
return the right defaults with no live source, including driving a real `sustainedOutage() == true`
state with three genuinely dispatched failures against a stub. 91/91 tests pass. The mixin-level
end-to-end behavior (does the generation worker actually wait the right amount under a real cadence)
remains the same disclosed test gap as every other fix in this file -- no real `ServerCubeWorld`
fixture exists here; John's own native infrastructure is what actually proved the bug and will
reverify the fix.

## MINECRAFT-566: readiness still samples one cell per cube, not the cells it actually spans (comment 55542, explicitly deferred)

John's own framing: "Separate later acceptance control; do not divert the current default-cadence
work... keep this as a narrow follow-up if needed." Native checks against `ad55ad7` found a real
cube spanning two different map cells -- a center cell A (confirmed empty) and an outer strip
covered only by a different cell B, whose healthy fetch was still held in flight. `isPendingForColumn`
(and therefore `wasPendingBeforeAcceptedAttempt`'s snapshot) only ever checks the single cell the
CENTER column maps to -- it saw cell A, already confirmed-empty, and concluded "not pending,
nothing to defer for," even though cell B (covering a real, different part of this exact cube's own
footprint) was still genuinely in flight. The worker accepted grass; B then resolved and a fresh
generator correctly painted the stripe from a way wholly within B; the original cube's grass was
permanent on reopen. The SAME underlying class of bug `cubeContainsNaturalSurface` already had to
be fixed for (55537) -- single-point sampling missing a real footprint's own span across more than
one unit of granularity -- now applies to the PENDING check itself, which still samples only the
center.

**Not attempted this pass**, per John's own explicit instruction not to divert from 55535. His
own suggested direction for whenever this is picked up: "count actual data cells intersecting the
relevant footprint as demand, not the entire speculative halo" -- i.e., identify the actual SET of
distinct cells a cube's real column footprint intersects (not a broad speculative neighborhood) and
check pending-ness across exactly that set, the same kind of full-footprint scan
`cubeContainsNaturalSurface` already does for Y-relevance, applied to cell-pending-ness instead of
natural height.

## MINECRAFT-566: John's direction change supersedes every bounded-fail-open design above (comment 55545)

Two more native findings against `bf7ae5f` (comments 55544/55545) landed almost immediately after
the cadence-aware budget (above) shipped:

- **55544**: even the dynamic budget failed a core case -- a speculative request A was still
  in-flight (dispatched, awaiting its response) at the exact moment B sampled its own budget from
  `RoadTileSource.nextEligibleRequestAt`. That field does not move until A's response actually
  lands, so B's one-time snapshot silently missed the real future cooldown entirely (sampled near
  zero while the real eligible slot was still ~5.7s away). "A one-time snapshot of committed
  cooldown misses an in-flight response setting the next interval" -- no one-time read of that
  field can ever be reliable against this race.
- **55545, a full direction change**, not a bug report: "the mod should keep waiting for needed map
  data... no timeout/cooldown/outage alone should adopt or permanently save bare terrain." This
  supersedes the entire "accept whatever `generate()` produces once some bound is reached" premise
  every fix from comment 55522 through 55544 shared.

**The new mechanism**, built on a native Earth Platform seam discovered by reading the real,
decompiled `ServerCubeWorld` class (not guessed): `ServerCubeWorld.produce` wraps its own call to
`CubeGenerator.generate(...)` in a `try { ... } catch (RuntimeException failure) { return new
Ready(..., null, ..., failure, ...); }`. Its caller, `adoptReady`, treats a `null`-section `Ready`
as a genuine generation failure: it does NOT create a `Cube`, does NOT touch storage at all, and
schedules `retryAt.put(key, ticks + 100)` (~5 real-world seconds at 20 TPS) -- the next ordinary
tick-driven `request` pass naturally re-attempts the exact same cube later, on whatever worker
thread happens to pick it up then. This is Earth Platform's OWN existing, already-native retry
machinery, used by every OTHER kind of generation failure already -- nothing new needed building.

So: instead of accept-then-maybe-retry-once-then-fail-open, `ServerCubeWorldRoadPrefetchMixin` now
dispatches a fire-and-forget fetch for every real cell the cube's own relevant footprint intersects
(`RoadPrefetch.dispatchRelevantCellFetches`, resolving 55542 as part of this change -- the actual
intersecting cells, never a speculative halo) and, if ANY of them is still genuinely PENDING
(`RoadPrefetch.isAnyRelevantCellPending`), throws `RoadDataNotReadyException` INSTEAD OF ever
calling the real `generate()`. Both new `RoadPrefetch` methods reuse the exact same
natural-height-padded, geometry-independent footprint scan `cubeContainsNaturalSurface` already
uses (per comment 55539's own lesson: never require mapped geometry before a fetch resolves),
just checking cell state across every column in that scan instead of (or in addition to) natural
height.

**What this eliminates, not just fixes:** because `generate()` is never called at all while
relevant data is pending, the entire "snapshot readiness before the accepted attempt" race
(comments 55532/55534/55537/55539) is now structurally moot for this mechanism -- there is no
already-accepted, possibly-premature attempt to race against. There is also no fixed or dynamic
defer budget to size (comments 55534/55535/55544's entire "how long should this worker wait"
question) -- this call site never waits at all; it dispatches and immediately either proceeds or
throws. `RoadTileSource.timeUntilNextEligibleRequest()` (added for 55535, proven unreliable by
55544) and `RoadPrefetch.sustainedOutage()`/`RoadPrefetch.timeUntilNextEligibleRequest()` (its
wrappers) are removed entirely rather than patched further -- comment 55545 explicitly accepts that
a sustained outage no longer causes any fallback to grass at all; the cube simply keeps being
retried by Earth Platform's own scheduler until the data resolves or the player no longer wants it
(Earth Platform's own `wanted`/`droppedKeys` mechanism, already native).

**`loadNow`'s own forced-arrival contract, now an explicit policy rather than an open question**
(comment 55545's own ask): `SynchronousLoadGuard` still gates this entire mechanism out of
`loadNow` completely, because `loadNow` cannot defer or retry at all -- its caller needs a `Cube`
back on this exact call, synchronously, this tick, with no "come back next tick" option the way
`request`'s worker-pool path has via `retryAt`. The deliberate, explicit policy (the SAME behavior
documented since comments 55522/55524, now stated as a policy rather than left implicit): a forced
arrival always adopts whatever natural or already-resolved terrain the generator has for that exact
column, right now, never blocking and never throwing. The alternative (blocking an arrival
indefinitely, or returning `null` and risking every OTHER forced-load caller, portal ground
selection among them) was evaluated and rejected as worse, not overlooked.

Verified: `RoadPrefetch.isAnyRelevantCellPending`/`dispatchRelevantCellFetches` against a real,
entirely-cold synthetic fixture (no cache file, no mapped geometry, a real HTTP stub) -- proving
pending-ness is reported correctly before a fetch, dispatch actually sends a real request, and the
same cube is no longer reported pending once that cell resolves. `RoadDataNotReadyException` is a
plain `RuntimeException` (confirmed by reading the decompiled class to be exactly the type
`produce`'s own `catch` clause is declared to catch). 90/90 tests pass (net: two obsolete tests for
the now-removed cadence-budget methods replaced by one new test for the two new methods).
**Not independently reproduced here**: that Earth Platform's own `retryAt`/`adoptReady` pipeline
actually behaves as the decompiled source describes end to end on a real, running server -- the
same disclosed harness gap as every other fix in this class; John's own native infrastructure is
what will confirm that.

## MINECRAFT-566: a liveness gap in the "fire-and-forget" dispatch itself (comment 55547)

John's source review of `61341d4` confirmed the mixin now hooks the right pre-generation seam, with
native delayed/cooldown, outage/recovery, and confirmed-empty controls underway -- but flagged one
remaining gap in parallel: `dispatchRelevantCellFetches` called
`RoadTileSource.waysForCellWithBoundedWait(lat, lon, Duration.ZERO)` per column, expecting
`Duration.ZERO` to mean "dispatch and never wait." It does not mean only that.

**Root cause:** `waysForCellWithBoundedWait`'s own `bound` parameter does double duty -- it is both
the CALLER's own wait timeout (`future.get(bound, ...)`) AND, passed straight through, the
dispatched task's own internal `waysForCellRetryingAcrossCooldown` retry DEADLINE. With
`bound = Duration.ZERO`, that internal deadline is also "now" -- so the very first time the retry
loop finds this cell cooldown-suppressed, its own `wakeAt` (clamped to that zero-width deadline)
is never after "now," and it gives up and completes immediately, same as a genuine, permanent
failure would. Completing removes the task from `inFlightByCell` -- this cell's own "current,
high-priority demand" registration is gone entirely. `ServerCubeWorld` itself only remembers the
CUBE key (via its own `retryAt`), never this specific tile fetch's own dispatch priority, so once
that registration is dropped, a DIFFERENT, merely speculative lookahead cell can take the very next
legal request slot ahead of this genuinely still-wanted one. John's own native description: a
speculative request C can win the next eligible slot before a still-wanted B's own 100-tick retry
even gets a chance, and repeated lookahead can starve a real demand even against a fully healthy
backend -- effectively reintroducing the pre-55528 "gives up on the first decline" bug, specific to
this one call site's own choice of bound.

**Fix:** a genuinely fire-and-forget dispatch, `RoadTileSource.dispatchCellFetch(lat, lon)` --
reuses the exact same coalesced, `PRIORITY_IMMEDIATE` dispatch `waysForCellWithBoundedWait` uses,
but the CALLER never calls `future.get(...)` at all (truly never waits, not even for a moment),
and the retry loop's own internal deadline is a separate, generous, fixed
`FIRE_AND_FORGET_RETRY_BUDGET` (30s) completely decoupled from any caller-side wait -- long enough
to survive an entirely ordinary cooldown without prematurely dropping this cell's own registration.
This background thread occupying itself for up to that long is the SAME disclosed, pre-existing
"one shared thread held during a cooldown sleep" limitation already named in this file (not a new
regression) -- it costs nothing a caller can observe, since nothing calls `.get()` on it.

Also addressed, per John's own explicit asks: `RoadPrefetch.dispatchRelevantCellFetches` now
deduplicates by actual map cell BEFORE dispatching (collects distinct cell keys across the cube's
own 256-column scan, dispatches once per distinct cell) rather than redundantly
checking/re-dispatching the same cell up to 256 times -- "deduplicate relevant footprint tile keys
before dispatch." "Drain current before speculation" and "cap queue/sleep resources" both continue
to hold via EXISTING, unmodified machinery once the registration-dropping bug above is fixed: the
priority queue (`PRIORITY_IMMEDIATE` before `PRIORITY_LOOKAHEAD`) already drains current demand
first as long as it stays registered, and `MAX_IN_FLIGHT_TILES`/`MAX_COOLDOWN_RETRIES` already cap
resources -- neither needed new code, just no longer being undermined by the early-exit bug.

Verified: a new `RoadTileSourceTest` case proves `dispatchCellFetch` returns near-instantly (never
blocks the caller) yet the SAME cell still actually resolves once a real steady-cadence cooldown
clears -- directly exercising the exact property that was broken. 91/91 tests pass. Not
independently reproduced here: the specific multi-cell lookahead-starves-current-demand race
John's own native control describes (A active, B queued, C challenging at the first eligible slot)
-- the same disclosed harness gap as the rest of this class; his own native infrastructure is what
found this and will confirm the fix.

## MINECRAFT-566: the first forced-caller slice -- Earth Platform geographic travel only (comments 55551-55556)

Once the ordinary-movement mechanism was confirmed native (comment 55549), John asked for the next
forced-caller slice as its own SEPARATE candidate, deliberately narrow: Earth Platform's own
`/earth travel` arrival queue only -- not generic `loadNow`, portal placement, respawn, or Sable,
all explicitly out of scope. This went through three rounds of John's own independent review before
landing on a design he had not yet rejected.

**Reading the real, decompiled `CubicEvents` class** confirmed the exact mechanism John's own
comments described: `CubicEvents.arrive` (called every tick) walks a static `ARRIVALS` queue; for
each entry it computes the landing column's ground height and calls `cubes.readyAround(pos)` -- a
cheap, non-blocking check of whether the destination's 3x3x3 cube neighborhood already exists in
memory. If not ready and `arrival.waited < 200` (`ARRIVAL_PATIENCE`, ~10s at 20 TPS), it just
increments `waited` and tries again next tick -- already an async, never-blocks pattern, built into
Earth Platform itself. Only once ready, or that patience exhausts, does it call `standOn`, whose own
first statement is `cubes.loadAround(pos)` -- Earth Platform's own synchronous, forcing generation
fallback -- then teleport. The gap: `readyAround` only ever asks about TERRAIN, never this mod's own
road data for the same footprint -- a destination whose terrain generates quickly but whose road
tile is still pending falls through the unrelated 200-tick clock into the same
forced-adopt-and-permanently-save bug comment 55549 already confirmed natively for ordinary
movement, now for a real geographic-travel teleport.

**Round 1 (comments 55551/55552), rejected by comment 55554:** a plain `@Redirect` on
`readyAround`/`List.set` only. Two real bugs: (1) it returned early, never checking road data at
all, whenever the real `readyAround` was `false` -- but this mod's own ordinary-movement mechanism
throwing `RoadDataNotReadyException` instead of generating is OFTEN the actual reason the landing
cube does not exist yet, so road-pending went unchecked for the single most common real case; (2)
once `waited` had already reached 200 (any earlier tick, any reason), the `&&` short-circuits past
the `readyAround` redirect's own answer entirely, so the `List.set` no-op that "pinned" `waited`
below 200 was never even reached, and `standOn` ran regardless -- John's own described native
boundary fixture (seed `waited=199`, tick once to 200, road still pending) would have failed this.

**Round 2 (comment 55554's own fix), rejected by comment 55556:** rather than trying to stop
`standOn` from being called at all (its own redirect would need to call the REAL private
implementation when not held, which a sibling project found throws `IllegalAccessError` across
Earth Platform's own NeoForge module boundary for a private member), this gated the actual forcing
call itself -- `standOn`'s own `cubes.loadAround(pos)`, public on `ServerCubeWorld`, via a separate
`@Redirect` scoped to `standOn`. John's own independent review rejected this too: letting `standOn`
still execute left its own subsequent block reads, occasional `clearLeaves`/`setBlockAndUpdate`
writes, and `tellPlace`'s own arrival message all live ("do not ship the partial fall-through").
It also used a bare global static field for the road-held flag ("leaks beyond the command
invocation") and an unscoped `List.remove(int)` redirect that caught Earth Platform's OWN preceding
disconnected-player cleanup call too (a real, distinct `ARRIVALS.remove(i)` earlier in the same
method, for `arrival.player.isRemoved()`).

**Round 3, the actual fix, per John's own exact prescription using MixinExtras** (confirmed
registered in his native fixture, and already present on this project's own classpath transitively
via `mixinextras-neoforge:0.5.5` -- no new Gradle dependency needed): make the ORIGINAL comparison
itself stay true while road-held, so `arrive` takes the "keep waiting" branch NATURALLY and never
reaches `standOn`/`loadAround`/`teleportTo`/`tellPlace` at all -- eliminating every one of round 2's
side-effect concerns at the source, rather than gating each of them individually afterward.
- `@WrapOperation` on `ServerCubeWorld.readyAround(BlockPos)`: calls the real method via
  `operation.call(...)` (never replacing it blindly), then checks
  `RoadPrefetch.isAnyRelevantCellPendingNearBlock` across the SAME 3x3x3 cube neighborhood
  `readyAround`/`loadAround` both traverse, dispatching a fire-and-forget fetch for it. Writes the
  combined answer into a `@Share`'d `LocalBooleanRef` -- a PER-INVOCATION value MixinExtras
  allocates fresh each call to `arrive` and threads through every injector declaring the same
  `@Share("roadHeld")` parameter, never a global static field (round 2's "leaks" bug, fixed).
- `@ModifyExpressionValue` on the EXACT `CubicEvents.Arrival.waited` field read John's own `javap`
  against the real, exact `combined-73` class confirmed is ordinal 0 (the one feeding the `< 200`
  comparison, distinct from ordinal 1, the increment): returns `0` instead of the real value while
  road-held, so `arrival.waited < 200` stays `true` regardless of how large the real counter has
  grown. Never references the private `Arrival` record type itself -- only the `int` value read
  from its field, which MixinExtras hands directly to the handler.

The real `waited` counter is deliberately left to increment normally even while held (the
`List.set` call is NOT wrapped) -- John's own framing ("use `@WrapOperation` for `List.set` only...
if needed") leaves this optional, and it genuinely is not needed: `waited` is used nowhere else in
`arrive` except this one (now permanently clamped) comparison and its own increment, so the real
counter growing unboundedly has no observable effect (`int` overflow at this rate is millions of
years away). `List.remove`/`standOn`/`loadAround`/`teleportTo`/`tellPlace` are not touched by this
mixin at all anymore -- nothing is left to gate once the comparison itself never lets `arrive` reach
them, which also means round 2's disclosed `tellPlace` cosmetic gap no longer exists at all.

**Comment 55554's "check actual intersecting map cells, not only nine cube centers" (the "outer-
strip gap" applying again):** round 2's "sample each cube's own center column" simplification
reintroduced the exact 55537/55542 bug class -- a cube can straddle a real cell boundary, so its
center resolves to one cell while an off-center part of the SAME cube resolves to a different,
still-pending one. Fixed by `RoadPrefetch.intersectingCells`: converts only the 4 CORNERS of the
3x3 cube neighborhood's own block-coordinate extent to lat/lon (never an interior column, and never
touching `surfaceY` -- which cell a point falls in is purely latitude/longitude), then enumerates
every whole cell between the resulting min/max cell-lat/lon. Typically 1 to 4 cells in practice
(one real cell is far larger than this whole 48-block-wide neighborhood), and correctly catches a
boundary cutting through the middle of the neighborhood, not just its center.

**Verification:** `./gradlew compileJava` succeeds against the real `libs/earthplatform-0.1.0-alpha.73.jar`
dependency -- Mixin's own annotation processor validates these exact `@WrapOperation`/
`@ModifyExpressionValue` target descriptors (including the exact field descriptor and ordinal) at
compile time, so a clean compile here is meaningfully stronger evidence than ordinary javac success
alone. A `RoadTilePrefetchMixinTest` case directly proves the two new `RoadPrefetch` methods against
a real, entirely-cold synthetic fixture. 92/92 tests pass.

**Known, disclosed limitations of this exact candidate:**
- No test in this repository drives the real, compiled `CubicEvents.arrive` method itself, for the
  same reason no test drives `ServerCubeWorld.produce` directly -- the same disclosed harness gap
  as every mixin in this mod. Whether MixinExtras's `@Share`/`@WrapOperation`/
  `@ModifyExpressionValue` actually weave and fire as written against the real, compiled
  `CubicEvents` class is not verified here either.
- John's own described native boundary fixture (seed a real arrival at `waited=199` with a cold
  loopback tile, instrument `standOn` to confirm it does not run at tick 200 while pending and does
  run once after the footprint is adopted, plus removed-player and direct-spawn controls) is
  exactly the right test for this and needs his own native infrastructure to confirm.

## MINECRAFT-566: two release blockers found by math/logic review of 7095fc4 (comment 55558)

While native core testing proceeded in parallel, John's own independent source/math review of
`7095fc4` found two real, confirmed-by-exact-math blockers, plus a further narrowing.

**Blocker 1, the antimeridian bug:** `intersectingCells` computed a naive `min`/`max` across its 4
corners' RAW longitude values. For a landing footprint that straddles the antimeridian (longitude
+-180 degrees -- John's own exact figures: at 1 meter/block, a landing at Web Mercator's own
`X=20037508` gives corner longitudes `+179.999817`/`-179.999761`), that naive min/max spans almost
the ENTIRE -180..180 range (`(179.999817 - (-179.999761)) / 0.01 ~ 36000` cells -- the exact count
John cited) instead of the true, tiny 2-cell local span either side of the antimeridian (his own
cited keys: `17999` and `-18000`). Every pending-check and dispatch call near the antimeridian
would have enumerated and queried ~36,000 cells, twice per tick, for every held arrival -- a severe
performance and correctness bug. Fixed by detecting the wrap (the 4 corners' own raw span exceeding
180 degrees is impossible for a genuinely local 48-block-wide footprint under any non-wrapped
projection), "unwrapping" by shifting negative corner longitudes by +360 before taking min/max (so
the true, short interval is computed correctly), and normalizing the resulting per-cell query
longitude back into -180..180 via a standard wrap formula before it is ever used. Verified by hand
against John's own exact figures: the corrected computation yields exactly cell longitudes 17999
and 18000 (which normalizes to longitude ~-179.995, the same cell his own "-18000" key names) --
matching his own cited correct answer precisely. Not independently reproduced as an automated test
in this harness (constructing a real `EarthGenerator` fixture landing exactly at the antimeridian
would need knowing that fixture's own geographic reference origin precisely, which was not
attempted this pass) -- confidence here rests on the hand-verified match against John's own cited
exact values, not a new test.

**Blocker 2:** "if native readyAround returns true, all cubes are already loaded and no fresh
terrain is at stake: return immediately before dispatch." The mixin's own `@WrapOperation` was
checking (and dispatching for) road-pending data even when the real, underlying `readyAround`
already reported the whole neighborhood ready -- but if every cube already exists, there is no
FRESH generation at risk at all; nothing this mechanism exists to protect is actually happening, so
paying for the check or sending a dispatch is pure waste (and, worse, could needlessly hold an
arrival that gains nothing from waiting). Fixed by returning immediately once the real
`readyAround` is `true`, before the road-pending check or dispatch ever run.

**Further narrowing**, per "for partial footprints qualify only missing natural-surface-relevant
cubes rather than blocking on every X/Z tile unconditionally": added a single, cheap pre-check
(`RoadPrefetch.cubeContainsNaturalSurface`, reused as-is -- one 256-column scan against just the
CENTER cube the landing position itself sits in, not all 27) before the (already cheap,
antimeridian-fixed) cell-intersection check runs at all -- an arrival whose own landing column is
deep underground or far up in open sky gains nothing from holding for road data, so this skips the
whole mechanism for that case rather than treating every X/Z tile in the neighborhood as uniformly
relevant regardless. A fuller "which of the 27 cubes are actually missing" breakdown (as opposed to
just the center) was not attempted -- no clear, already-available `ServerCubeWorld` API for that
per-cube breakdown was found in this pass; this narrower, single-cube version is a reasonable,
cheap, well-justified partial step using existing, already-tested infrastructure, not the full ask.

92/92 tests pass (`./gradlew compileJava` confirms the unchanged Mixin target descriptors still
validate against the real dependency; no new automated test added for the antimeridian fix itself,
per the disclosed reasoning above).

## Compile/test-time-only dependency, not a fork

Earth Platform's source is never copied, patched, or vendored into this
repo. `scripts/build_earth_platform_dependency.sh` builds its pinned
`combined-73` tag (release 0.1.0-alpha.73) exactly as upstream does and
drops the resulting jar at `libs/`, gitignored. This mod's own code imports
only Earth Platform's public API (`EarthGenerator.grid()`,
`.settings()`, `.surfaceY()`, `.generate()`, `SurfaceGenerator.Surface`,
`.DRY`, and the `geography` module's `GeoPoint`/`MercatorGrid`).
