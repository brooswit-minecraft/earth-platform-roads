bump: minor

### Added
- Indexed, multi-region road network (`RoadNetwork`/`RoadSegment`/`RoadClass`): ways are classified
  against every mapped vehicular `highway=*` value (foot trails rejected at load time) and bucketed
  into a spatial grid index. Shared OSM node IDs between ways are tracked for mapped-junction
  detection (`RoadNetwork.junctions()`).
- Installed road data, bundled AND external, using the same versioned region format
  (`schema_version`, checked at load): bundled regions are listed by
  `data/earthplatformroads/roads/regions/index.json`; external regions are every `*.json` file
  dropped into `config/earthplatformroads/roads/regions/` in a real install.
- `RoadTileSource`: automatic, live road-tile fetch and disk cache, modeled on Earth Platform's own
  `TerrariumTileSource`. No default live endpoint is shipped -- the one sanctioned live source is an
  opt-in preview (`-Dearthplatformroads.previewProvider=overpass-ch` plus `previewEndpoint`; see
  `RoadNetwork.buildDefaultTileSource`'s doc) speaking real Overpass QL with an optional credential
  (never logged/hardcoded) and a conservative client-side rate limit. A network failure leaves the
  cell retryable (never permanently marked as a roadless gap); a genuine empty result stays
  confirmed-empty; an Overpass HTTP-200-with-error-remark response is treated as a failure, not a
  cacheable result. `EarthGeneratorPrepareMixin` hooks Earth Platform's own `prepare`/`prepareNear`
  "fetch ahead of generation" entry points to trigger this off the hot generation path, so the
  actual `surface()` hook (and therefore Earth Platform's synchronous `loadNow`) never blocks on
  HTTP.
- Layered-blackstone road surfaces at a real ground-height step: a dedicated compat block
  (`LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE`) decaying by Chebyshev distance to the nearest
  same-road neighbor with lower real ground height (`RoadSurfaceLayering`), verified against real
  generated data including a cascade regression caught in review.

### Changed
- `EarthGeneratorRoadMixin` looks up the covering `RoadSegment` through the indexed `RoadNetwork`
  (bundled + external + live-tile-cache candidates merged) and selects a layer count from distance
  to a real lower same-road neighbor, instead of always painting a full block.
- The pinned Southside Drive fixture moved to the new region format at
  `data/earthplatformroads/roads/regions/yosemite_valley.json`.

### Milestone boundary (not an open gap -- see the ticket's milestone update)
- Bridges/tunnels are modeled and explicitly skipped (no surface drawn, never an at-grade crossing
  along a riverbed); correct rendering is deferred to a following milestone.

### Fixed
- A mapped road crossing into a neighboring, not-yet-fetched cell was silently omitted at that
  boundary (found live on Zippy, reproduced synthetically). A first fix attempt (widening the
  prefetch mixin itself to issue up to 9 requests) was found broken under a realistic rate limit --
  only the first of those 9 could succeed before the next-eligible deadline rejected the rest.
  Fixed instead on the read side: `RoadNetwork.candidatesNear` now also checks the Moore
  neighborhood's already-cached data (no extra fetch) when evaluating a column, since a crossing
  way's full geometry is already present in whichever single cell actually got fetched. Only helps
  a fresh (not yet generated) cube's read -- an already-generated cube is never retrofitted when a
  neighbor's data arrives later.
- A second, related omission: a road continuing as a DIFFERENT OSM way ID (sharing only a node)
  whose own geometry lies entirely in a neighbor cell no query ever touched -- the read-side fix
  above cannot carry that, since the neighbor's data was never fetched by anyone. Fixed by
  `RoadTileSource.enqueueLookahead`: a bounded, deduplicated queue of a prepare point's Moore
  neighborhood, drained at most one additional fetch per call, through the ordinary rate-limited
  fetch path -- never bursting multiple requests. Catches up nearby cells over several prepare
  calls as a player moves normally.

### Added
- `RoadNetwork.diagnoseColumn`: a nonsecret, opt-in diagnostic distinguishing why a column has no
  road surface (covered/excluded-as-bridge-or-tunnel/pending/confirmed-empty), backed by a new
  `RoadTileSource.CellState` exposing a cell's actual on-disk cache state.

### Fixed (comment 55474)
- `prepare`/`prepareNear` -- `EarthGeneratorPrepareMixin`'s only hooks -- are never reached by
  ordinary gameplay movement at all: the real streamed-generation path
  (`ServerCubeWorld.requestAround` -> `request` -> background worker -> `produce` -> `generate()`
  on a storage miss) calls none of them, confirmed by reading Earth Platform's own
  `ServerCubeWorld` class. Every earlier fix in this file only ever helped `/earth travel` teleport
  and Chunky pre-generation -- never a normal walk into fresh terrain, almost certainly the real
  mechanism behind "a road abruptly ends" reported since comment 55454 (a verified integration gap,
  not a confirmed explanation for any one specific report). Fixed by a new
  `ServerCubeWorldRoadPrefetchMixin` dispatching the exact same bounded/rate-limited fetch
  (`RoadPrefetch.forColumn`, factored out of `EarthGeneratorPrepareMixin`) immediately before
  `produce`'s own call to `generate()` -- after the storage-read fast path, so an already-saved
  cube is untouched -- so a cell that fetches successfully is available to the road surface hook
  during THIS generation call. Never touches `loadNow`'s synchronous arrival path (guarded by a new
  `SynchronousLoadGuard`, since both share the one `produce` method with no parameter telling them
  apart) or the server tick thread itself.
- Added `RoadTileSource.sustainedOutage()` and `RoadNetwork.ColumnDiagnosis.PENDING_PROVIDER_OUTAGE`:
  a distinct diagnosis for several consecutive genuinely-dispatched fetch failures across different
  cells (the provider itself appears down), separate from the ordinary "nobody has asked yet"
  pending state.

### Fixed (comment 55476)
- A confirmed "wrong tile" bug in the fix above: `produce`'s `x`/`z` are CUBE indices, not the BLOCK
  coordinates `RoadPrefetch.forColumn` actually expects (the same contract `prepare`/`prepareNear`'s
  own callers already honor) -- the first version queried entirely the wrong map tile (off by a
  factor of 16) on almost every call. Fixed by a new, directly testable
  `RoadPrefetch.cubeIndexToBlockCenter(int)`; proven by a real-HTTP-stub test asserting the correct
  cell's bbox is requested and the buggy raw-cube-index bbox is explicitly NOT. Independently
  reproduced (pre-fix) and reconfirmed fixed (post-fix) by John on a real compiled NeoForge +
  `combined-73` server, down to the exact bbox and the actual placed road-surface block.
- The same comment's "bounded-worker" follow-up: a tall column's many cube-Y layers were each
  independently dispatching the same cell's fetch, and a non-first caller would simply block on
  the per-cell lock for up to the full network timeout. Fixed by
  `RoadTileSource.waysForCellWithBoundedWait` (used only by the ordinary-streaming
  `ServerCubeWorldRoadPrefetchMixin`, via `RoadPrefetch.forColumnBounded` --
  `EarthGeneratorPrepareMixin` is unchanged): every caller for the same still-pending cell coalesces
  onto one real fetch, the actual network wait runs on one dedicated background thread (never a
  generation worker), and each caller bounds its own wait to 2s before failing open while the
  shared fetch keeps running. The Moore-neighborhood lookahead dispatch is now fully async too.
  Proven by four new `RoadTileSourceTest` cases against a real delayed HTTP stub (prompt return on
  resolve, fail-open-but-keep-running on timeout, request coalescing across 5 concurrent callers,
  and non-blocking async lookahead).

### Fixed (comment 55481)
- Three real bugs in the bounded-worker fix above: (1) the in-flight cleanup callback was
  registered INSIDE `computeIfAbsent`'s mapping function, racing a synchronously-fast completion
  and potentially pinning a stale, resolved future forever for that cell -- fixed by registering it
  only after `computeIfAbsent` actually publishes the entry; (2) the async lookahead dispatch
  submitted a brand-new, unbounded task on every call -- fixed with a small coalescing set so a
  tall column's many cube-Y layers share one pending dispatch; (3) distinct simultaneously-cold
  cells could grow the in-flight map without bound -- fixed with a best-effort cap (`MAX_IN_FLIGHT_TILES`,
  8). Three new `RoadTileSourceTest` cases prove each. Also independently reproduced in a real
  isolated worker: a 4.2s loopback response (longer than the 2s bound) correctly fetched the right
  bbox, but the column had already committed as grass -- a live instance of the already-disclosed
  fail-open tradeoff, not a new bug category. The suggested real fix (reuse `ServerCubeWorld`'s own
  `adoptReady`/`retryAt` seam instead of a one-shot bounded wait) needs a new Mixin accessor into a
  private record constructor and is not attempted this pass -- see `docs/design.md`.

### Fixed (comments 55486/55487)
- A real, independently-reproduced stale-cache bug undermining the readiness work: Earth Platform's
  own `SurfaceGenerator` permanently memoizes a whole column's surface decisions the first time
  anything asks (including `lowestGround`, called every tick for ordinary flying logic, well
  before road data need be ready) -- a later, genuinely fresh `generate()` for that same column,
  even after the tile becomes ready, could still hit that stale cached entry and never re-evaluate.
  Fixed with a narrow, per-column cache eviction (never a global clear) right before every
  `produce()`-triggered `generate()` call, both the ordinary-streaming and arrival paths. Verified
  by directly reproducing John's own methodology against a real `EarthGenerator`.

### Fixed (comment 55492)
- A real ordering bug in the eviction fix above: it evicted BEFORE dispatching/waiting on the
  fetch, not after, leaving a window during which a concurrent `lowestGround` survey (or similar)
  could re-populate the same column's stale decision before `generate()` ever benefited from the
  eviction. Fixed by reordering: dispatch/wait first, eviction last, immediately before
  `generate()`. Verified with a test built to the exact suggested methodology: a held-back HTTP
  response, a `lowestGround` call deliberately racing into the middle of the wait, then release
  before the bound -- proving the real road still paints despite the race. A narrower, disclosed
  residual race (an already-in-flight, non-atomic `SurfaceGenerator` cache computation that started
  before the eviction can still publish after it) remains open; closing it fully would mean owning
  that upstream caching logic entirely.

### Fixed (comments 55496/55498/55499)
- That residual race, independently reproduced by John with deliberate test-only latches: bounded
  the exposure with a verify-and-retry rather than attempting the real root-cause fix (moving the
  road decision out of `SurfaceGenerator`'s cache entirely), which was evaluated and deliberately
  not attempted this pass -- cached `Surface` data also drives cave-mouth behavior and structure
  fill in the real build, and a blind post-`generate()` overwrite risked corrupting either in
  ordinary play, not just under the race. `ServerCubeWorldRoadPrefetchMixin`'s injection became an
  `@Redirect`: calls the real `generate()`, verifies the result against fresh road data
  (`RoadPrefetch.sectionLooksStale`, using natural `surfaceY`/`waterY`, never decorated output), and
  retries (re-evict, regenerate) up to 3 times if it looks wrong. Narrows the race to "exhausts a
  bounded retry budget", not eliminated -- disclosed as such. The detection function itself is
  proven directly and deterministically; the retry loop's own end-to-end recovery is not
  independently reproduced (would need the same test-only-latch hook into Earth Platform internals
  John's own reproduction used).

### Changed (comment 55484, a separate styling slice from the readiness work above)
- Roughly doubled every `RoadClass`'s paved half-width (and the lanes-based formula the same way)
  per John's live-playtest feedback that roads read as too narrow. Never required any migration:
  width was always recomputed fresh from classification at load time, never persisted in any
  region/tile file.
- Added a thin yellow-terracotta centerline (classes that opt in; `RoadClass.SERVICE` stays
  unstriped, matching real-world driveway/service-road convention) and a narrow gravel shoulder
  outside the paved edge (`RoadSegment.crossSectionAt`, nesting all three zones against the same
  distance value so the centerline can never read as outside the paved surface, including at a
  sharp bend). `coversColumn` now means the whole footprint, shoulder included.
- Both new materials get the same slope-layering continuity the paved surface already had, via two
  new compat-owned `LayeredBlock`s (`LAYERED_YELLOW_TERRACOTTA`, `LAYERED_GRAVEL`) -- the gravel
  variant has no falling-block physics at all (confirmed structurally: `LayeredBlock` extends plain
  `Block`, not `FallingBlock`), unlike vanilla gravel.
- Verified on the bundled, non-private Yosemite fixture: real generation actually produces
  yellow-terracotta -> blackstone -> gravel -> natural terrain moving away from the pinned
  centerline, and the candidate-query margin across tile/cube seams was already generous enough
  (no change needed) for the new, wider footprint.

- Disabled the bounded stale-publication verify-and-retry added for the residual per-column cache
  race (comments 55496/55498/55499): a compiled-worker run (comment 55505) and a follow-up
  diagnostic (55507) both found the detector itself (`RoadPrefetch.sectionLooksStale`) producing
  false positives -- rejecting already-correct sections, and flagging legitimately natural columns
  as stale -- for reasons neither John nor I had root-caused yet. `MAX_STALE_RETRY_ATTEMPTS` is now
  `0` (the retry loop and detector are left in place, dormant, re-enabling is a one-line change once
  a safer detector replaces this one); shipped behavior reverts to "evict once, generate once,"
  John's own named smaller verified fallback.
- Added leaf-preserving road-column clearance (comment 55488): `SurfaceGeneratorRoadClearanceMixin`
  redirects `SurfaceGenerator.generate()`'s own call to its private `dropStrayBlockEntities` -- the
  real final generation seam, confirmed by reading the decompiled class, after `terrain()`/
  vegetation/structures have all run and before that call's own block-entity-tag filtering --  and
  `RoadCanopyClearance.clear` removes every non-leaf block directly above a paved/centerline road
  column's own natural deck height within that one cube, preserving leaves (set `PERSISTENT` so a
  removed supporting log never lets them decay) and never touching the gravel shoulder, anything
  below the deck, underwater columns, or a column off the mapped footprint. Scoped per-cube, same
  pattern as the rest of this mod's generation-time hooks -- never a separate full-column edit pass.
  Leaf detection checks `BlockTags.LEAVES` OR `instanceof LeavesBlock`: the tag alone read back
  empty in this project's own `neoForge.unitTest` harness (no real server datapack reload there),
  so `instanceof` covers that environment, but the tag is checked first and is what correctly
  covers a modded leaf block that only carries the tag without extending vanilla `LeavesBlock` --
  a real gap the `instanceof`-only version shipped in commit 3ce7eb1 had, per John's independent
  compiled-build review (comment 55510); fixed in the very next commit.
- John's comment 55510 also independently confirmed 3ce7eb1's canopy clearance on a real compiled
  build: clears log/stone above paved/stripe across both horizontal and vertical cube seams,
  preserves vanilla leaves through actual random ticks, correctly reopens once a removed log's
  decay control disappears, and leaves off-road/shoulder/support columns and an existing
  player-edited log untouched.
- Fixed a real scheduling bug in the ordinary-movement prefetch path (comment 55522, reproduced via
  a real loopback NeoForge/combined-73 fixture): a speculative lookahead fetch and the current
  cell's own bounded-wait fetch both run through `RoadTileSource`'s single dedicated async thread;
  a plain FIFO queue let a lookahead job queued moments earlier make an immediate job wait behind it
  for a slot it did not need to, and the immediate job's own retry attempt during a steady cooldown
  was a single shot rather than retried, giving up on the first decline even with plenty of its own
  bound left. `ASYNC_FETCH_EXECUTOR` is now a priority queue (immediate ahead of lookahead, ties by
  submission order) and `waysForCellWithBoundedWait`'s dispatched task retries across a short
  cooldown (bounded, never a real attempted failure) instead of giving up immediately. Branch built
  from the accepted canopy-only commit (`b7ff716`), excluding the separate, not-yet-ready flat-deck
  prototype entirely, per John's own explicit instruction.
- Fixed a real bug in that cooldown-retry fix itself (comment 55528, John's own independent
  loopback reproduction): the retry loop inferred "was this just cooldown?" purely from whether
  `nextEligibleRequestAt` was ahead of now, but a genuinely ATTEMPTED failure (e.g. an HTTP 500)
  advances that same instant by the steady cadence exactly like a success does -- `waysForCell`
  discarded the real fetch outcome entirely, so the loop could not tell a real failure apart from a
  cooldown decline, and retried (sending a second real request) after a real failure too (four
  fresh demands against a failing endpoint gave POST counts `1,1,1,2`). Fixed by propagating the
  real outcome (`waysForCellDetailed`/`FetchAttemptResult`) and retrying ONLY on
  `DISPATCH_SUPPRESSED`; also added a 1ms sleep floor per a second, unconfirmed concern (an early
  sub-ms wake consuming a retry attempt without actually waiting). Verified with a new test
  asserting exactly one request against a genuinely failing provider, even with a generous bound.
- Added the deferred "pending surface-cube" half of 55522 (comment 55526, once John's own native
  verification confirmed the fetch-side fix holds): `ServerCubeWorldRoadPrefetchMixin` now defers
  and retries generation, narrowly, when the cube's own covering tile is genuinely still
  `RoadTileSource.CellState.PENDING` -- a new, direct detector (`RoadPrefetch.isPendingForColumn`,
  a plain cache-state read, never the indirect "compare decorated output" approach
  `sectionLooksStale` uses) kept deliberately separate from that (still disabled) stale-retry
  mechanism. Never for `loadNow`'s own arrival (an explicit separate control, per John's own
  instruction); never touches an already-saved cube (the storage-read fast path above this
  injection already guarantees that); bounded by both a small fixed attempt count AND a real
  wall-clock window, with an explicit fail-open the moment either runs out. Verified the new
  detector directly against all three real cache states; the mixin's own injection points remain
  the same known, disclosed test gap (no real `ServerCubeWorld`/`ServerLevel` in this harness) --
  actual paint continuity and native save/reopen will be verified on John's own infrastructure.
- Fixed a real race in the defer/retry above (comment 55532, reproduced natively on
  `combined-73`): the pending check ran AFTER the already-accepted `generate()` call, so a tile
  that resolved PENDING -> READY DURING that exact call (observed 91ms apart) left the post-hoc
  check correctly, but uselessly, reporting "not pending" -- the retry precondition was already
  false by the time it was evaluated, so stale/pending-input output was kept even though the
  underlying race the retry exists to catch had genuinely just happened. Fixed by snapshotting
  `RoadPrefetch.isPendingForColumn` immediately BEFORE the accepted `generate()` call rather than
  re-deriving it afterward, and gating the retry on that snapshot. Also added: an explicit
  `RoadPrefetch.hasLiveTileSource()` gate (no live tile source configured at all -- nothing to
  ever wait for), `RoadPrefetch.cubeContainsNaturalSurface` (a cube whose own Y-range does not
  contain the column's natural ground height can never show a road regardless of pending state --
  deferring for it is pure waste; the prior loop had no such filter), and corrected the retry's
  own wait to pass the REMAINING portion of `PENDING_DEFER_WINDOW` into the re-dispatch rather
  than a fresh full wait bound each attempt (the earlier version effectively ignored the window as
  a total budget). `MAX_PENDING_DEFER_ATTEMPTS` removed entirely -- the corrected, snapshot-gated
  design only ever needs one retry attempt, not a bounded count of them. No change to `loadNow`'s
  forced-load path (still an explicit separate control) or to any no-HTTP saved/forced semantics.
- Fixed two more real gaps in that same fix, found by John's native checks (comment 55534): (1)
  the INITIAL prefetch dispatch (before the first accepted `generate()` call) was never gated by
  Y-relevance or live-fetch capability -- only the later defer retry was -- so a deep/sky cube
  still sent its own initial fetch POST, and (2) `RoadPrefetch.hasLiveTileSource()` reported `true`
  for any non-null `RoadTileSource`, including a cache-only instance (`allowNetwork=false`) that
  can never resolve a fetch -- such an instance made zero POSTs but still entered this mixin's own
  wait scheduling. Fixed by a new `RoadTileSource.canFetchLive()` accessor (checks `allowNetwork`
  directly) that `hasLiveTileSource()` now uses instead of a plain non-null check, and by computing
  the live-and-relevant gate once and sharing it between the initial dispatch and the defer
  snapshot so the two can never drift apart again. Also fixed the defer window's own deadline to be
  captured BEFORE the initial dispatch rather than after the first `generate()` call returns, so
  `PENDING_DEFER_WINDOW` is now a genuine aggregate budget across the whole method -- the prior
  version let a held response fail open at 4.26s against a nominal 3s window, since the window only
  ever covered the retry's own slice.
- Fixed a real gap found by John's own native "upper-first" fixture (comment 55537, confirming
  deep/sky/cache-only all now correctly stay at zero POSTs): `cubeContainsNaturalSurface` checked
  only a single CENTER column's own natural ground height, but a real road footprint is not flat --
  an off-center column within the SAME footprint (the paved edge or shoulder, not the sampled center
  point) can have a different natural ground height, even landing in a DIFFERENT cube. A cube
  Y-irrelevant only by the old center-only check still adopted grass with no dispatch at all, and
  that grass was permanent on reopen once a fresh generator correctly painted the (different) cube
  the center check had flagged. Fixed by scanning the CUBE's own full 16x16 column grid (the same
  per-column pattern `RoadPrefetch.sectionLooksStale` already uses) for ANY road-covered column
  (skipping `CrossSection.OFF_ROAD`) whose own natural ground height, padded 3 blocks below (a fixed
  conservative margin, deliberately not computed from Earth Platform's own private `Surface`
  decoration -- same stance `sectionLooksStale` already takes), falls inside this cube's Y-range.
  `cubeContainsNaturalSurface` now takes `(cubeX, cubeY, cubeZ)` instead of a single block column.
  Verified directly against a synthetic fixture way; the exact natural-terrain height variation
  John's native fixture exercises was not independently reproduced in this harness (no DEM-mocking
  seam exists to force it deterministically) -- the new scan-based detector's correctness does not
  depend on which specific column in the cube has the differing height, only that scanning the whole
  footprint (not one sample point) finds it, which the new test does directly prove.
- Fixed a real, more fundamental bug in that same fix (comment 55539, found by John's own
  cold-bootstrap native regression): requiring mapped road geometry to already exist before
  `cubeContainsNaturalSurface` would report a cube relevant at all is circular for the exact case
  this whole mechanism exists to help -- a genuinely cold, never-fetched (PENDING) tile has NO
  mapped geometry yet, by definition, so the check reported every cube irrelevant and skipped
  dispatch entirely on an ordinary, healthy walk into fresh terrain. Confirmed natively: both a flat
  case and the 55537 upper-first case saved grass with zero prefetch calls and zero POSTs, tile left
  PENDING; an explicit fixture-only fetch then correctly painted both. Fixed per John's own
  direction ("use natural-only support relevance before fetch... reserve mapped geometry for
  resolved tiles if useful"): the gate is now geometry-INDEPENDENT, scanning the cube's own 16x16
  footprint for NATURAL ground height alone (never filtered by whether a road is actually mapped
  there). Also short-circuits on `SynchronousLoadGuard.isActive()` first, so `loadNow`'s own arrival
  path never pays for the scan at all. The test rewritten to an entirely cold boot (no fixture, no
  cache file, tile genuinely PENDING throughout) proving the gate works with nothing but natural
  terrain.
- Attempted (comment 55535) a dynamic, cadence-aware defer budget to replace the fixed window that
  gave up before an entirely ordinary steady-cadence cooldown cleared -- superseded almost
  immediately (comment 55544: a one-time snapshot of the remaining cooldown cannot see a DIFFERENT,
  still-in-flight request's own future effect on it) and then superseded entirely by a direction
  change (comment 55545, see below) that removes this whole class of bounded-wait/fail-open
  mechanism. Not shipped in this form; see the 55545 entry below for what replaced it.
- **A direction change superseding every bounded-fail-open design above (comment 55545):** "the mod
  should keep waiting for needed map data... no timeout/cooldown/outage alone should adopt or
  permanently save bare terrain." Rather than accept-then-maybe-retry-once-then-fail-open,
  `ServerCubeWorldRoadPrefetchMixin` now dispatches a fire-and-forget fetch for every real cell the
  cube's own relevant footprint intersects (`RoadPrefetch.dispatchRelevantCellFetches`, resolving
  comment 55542 as part of this change -- the actual intersecting cells, never a speculative halo)
  and, if ANY of them is still genuinely PENDING (`RoadPrefetch.isAnyRelevantCellPending`), throws a
  new `RoadDataNotReadyException` INSTEAD OF ever calling the real `generate()`. This plugs into
  Earth Platform's OWN existing, already-native retry machinery (confirmed by reading the real,
  decompiled `ServerCubeWorld` class): `produce`'s own `try/catch (RuntimeException failure)` turns
  that into a `null`-section `Ready`; `adoptReady` never creates a `Cube`, never touches storage,
  and schedules a retry roughly 5 seconds later via its own `retryAt` map -- nothing this mod builds
  or manages. Because `generate()` is never called while relevant data is pending, the whole
  "snapshot readiness before the accepted attempt" race (55532/55534/55537/55539) and the entire
  fixed/dynamic defer-budget question (55534/55535/55544) are now moot for this mechanism, not
  merely fixed again -- removed the now-obsolete `RoadTileSource.timeUntilNextEligibleRequest()`
  and its `RoadPrefetch` wrappers (`sustainedOutage`/`timeUntilNextEligibleRequest`) rather than
  patch them further. A sustained provider outage no longer causes any fallback to grass at all --
  the cube just keeps being retried by Earth Platform's own scheduler. `loadNow`'s own forced-arrival
  contract is unchanged in behavior but now stated as an explicit policy (comment 55545's own ask)
  rather than left implicit: `SynchronousLoadGuard` still excludes this entire mechanism from it,
  since an arrival needs a `Cube` back synchronously with no "retry next tick" option available to
  it. 90/90 tests pass (two obsolete tests for the removed cadence-budget methods replaced by one new
  test proving the two new `RoadPrefetch` methods against a real, entirely-cold synthetic fixture).
- Fixed a real liveness gap in that dispatch itself (comment 55547, found in John's own source
  review of `61341d4` -- the new seam itself was confirmed correct): calling
  `waysForCellWithBoundedWait(lat, lon, Duration.ZERO)` as "fire-and-forget" doesn't just skip the
  caller's own wait -- that same zero bound also becomes the retry loop's own internal deadline, so
  a cooldown-suppressed dispatch gave up (and dropped its own in-flight registration) on the very
  first decline, reintroducing the pre-55528 bug for this one call site. Because `ServerCubeWorld`
  only remembers the CUBE key, not this tile's own dispatch priority, a merely speculative
  lookahead cell could then take the next legal request slot ahead of a genuinely still-wanted one
  -- repeated lookahead starving real demand even against a healthy backend. Fixed with a genuinely
  fire-and-forget `RoadTileSource.dispatchCellFetch`: the caller never calls `future.get(...)` at
  all, and the retry loop's own internal deadline is a separate, generous, fixed 30s budget,
  decoupled from any caller-side wait. Also deduplicates by actual map cell before dispatching
  (collects distinct cells across the cube's 256-column scan first) rather than redundantly
  dispatching the same cell up to 256 times. 91/91 tests pass (one new case proving the dispatch
  returns near-instantly yet the cell still actually resolves once a real cooldown clears).
- **Independent native verification on `cf8c013` (comment 55549), the ordinary-movement mechanism's
  own milestone confirmation:** ordinary delayed data and priority PASS -- a speculative request
  finishing ~1s after a real demand was wanted, with that real demand having NO durable cube
  through the whole deferral, winning the first legal POST despite a competing lookahead AND a
  same-cell sibling, POSTing exactly once, and reopening the saved, correctly-painted result. A
  real `429`->`500`->`200` outage/recovery sequence: no durable cube through any of the failed
  responses, recovers to the correct result once a valid response arrives, reopens saved. A
  genuine HTTP 200 empty response: generates and saves grass correctly (legitimately natural
  terrain, not something to keep waiting on). The ordinary-movement half of this mechanism is now
  independently, natively confirmed across every one of these cases.

### Fixed (comment 55562, ordinary-movement performance -- revisiting known places was extremely slow)
- John's own source audit found three real, concrete performance bugs in the ordinary-streaming
  path, separate from and additional to this mechanism's own correctness:
  1. **Unconditional stale-column eviction, every Y, every sibling.**
     `ServerCubeWorldRoadPrefetchMixin` called `RoadPrefetch.evictStaleSurfaceDecision`
     unconditionally for EVERY `produce()` call, including every unsaved sky/deep cube regardless
     of relevance -- so a tall column's many cube-Y layers each evicted the SAME shared X/Z
     `SurfaceGenerator.Column` cache the others had just populated, forcing every sibling to
     re-run the full raw+road-aware survey from scratch. Fixed with
     `RoadPrefetch.evictStaleSurfaceDecisionIfRelevantAndChanged`: evicts only when the cube is
     naturally-surface-relevant AND something has genuinely resolved since this exact column was
     last evicted (tracked via a new `RoadTileSource.resolvedGeneration()` counter, bumped whenever
     any cell newly resolves to READY/CONFIRMED_EMPTY) -- "keep freshness on real data transition,
     not each sibling." The first touch of any column still always evicts (nothing legitimate
     cached yet to protect, same guarantee the original unconditional eviction always gave); never
     gated by `SynchronousLoadGuard` (not HTTP; applies to `loadNow`'s own arrival path too, exactly
     as before).
  2. **Three redundant full-cube scans, "the ready gate also rescans natural heights."** The
     mixin's gate called `cubeContainsNaturalSurface`, then `dispatchRelevantCellFetches`, then
     `isAnyRelevantCellPending` separately -- three full 256-column scans of the SAME cube, each
     independently re-deriving `generator.surfaceY` from scratch. Fixed with a new
     `RoadPrefetch.dispatchAndScanRelevantCells`: one pass, `surfaceY` read exactly once per column,
     dispatch/pending-check each done at most once per UNIQUE map cell the cube's relevant footprint
     intersects (typically 1-4, not 256) -- "start with a low-risk one-pass, unique tile state
     check." The three original, individually-tested methods are left unchanged for existing
     callers/tests; this is purely a new, additional entry point for the one real hot call site.
  3. **`cellState`/`cachedWaysForCellOrEmpty` never consulted memory, "256 repeated filesystem
     stats... restat all cells near absent/confirmed-empty neighbors."** `cellState` stat'd disk
     directly on every single call. `cachedWaysForCellOrEmpty` (the actual neighbor-candidate read
     path `RoadNetwork.candidatesNear` calls) checked ONLY the ready marker, never the confirmed-
     empty one, and never memoized a confirmed-empty result at all -- a REAL, confirmed bug: every
     call for an already-resolved-but-empty neighbor (the ordinary case, most of the world has no
     roads) re-stat'd disk forever. Fixed by having both delegate to the existing, already-tested
     `readDiskCacheIfPresent` validated-load helper: a READY/CONFIRMED_EMPTY hit is memoized exactly
     once and served from memory with zero further disk I/O; PENDING is deliberately NEVER cached,
     so a later resolution or transient failure is never masked. The on-disk cache's own file-based
     semantics are completely unchanged -- only the in-memory layer already used elsewhere in this
     class is now actually consulted here too.
- Measured, per John's own explicit ask ("report exact build and measured before/after"): a new
  "vertical sibling" test (`tenVerticalSiblingCallsAfterResolutionSendZeroHttpAndSkipEverySiblingEviction`)
  resolves one cell with exactly 1 real HTTP request, evicts exactly once for that column, then
  drives TEN more sibling cube-Y-layer calls at the same X/Z after resolution -- all ten send ZERO
  further HTTP requests, record ZERO further real disk-validating loads
  (`RoadTileSource.resolvedGeneration()` unchanged), and all ten correctly skip re-eviction. Before
  this fix, those same ten siblings would each have independently re-evicted the shared column
  cache and re-scanned all 256 columns three times over.
- Correctness retained, explicitly verified: a cell genuinely transitioning from PENDING to
  resolved still makes the SAME column eligible to evict and repaint again
  (`evictStaleSurfaceDecisionIfRelevantAndChangedGatesBySiblingAndRealDataTransition`); an
  irrelevant cube never evicts regardless of the generation counter; PENDING is never cached
  (`pendingIsNeverCachedAndAFreshInstanceStillReadsTheRealDiskStateAfterAnEarlierInstanceSawPending`);
  a fresh `RoadTileSource` instance over the same cache root (the "native reopen" case -- memory
  caches never survive a restart) still reads the real on-disk state directly, same as the
  pre-existing `aFreshSourceOverTheSameCacheRootDoesNotRefetch`; the existing
  `evictingTheStaleSurfaceDecisionFixesARealReproductionOfTheCachingBug`/
  `evictingAfterTheBoundedWaitSurvivesALowestGroundSurveyRacingInTheMiddleOfIt` stale-column-repaint
  tests, and the existing outer-strip-boundary tests, are all unmodified and still pass. 97/97 tests
  pass (`./gradlew compileJava compileTestJava test --rerun`). No server-thread HTTP, no save/cache
  file-format rewrite, no hosted-provider dependency added, no reduction in the correctness-waiting
  (`RoadDataNotReadyException`) behavior.
- Not attempted this round, disclosed explicitly: `resolvedGeneration` is deliberately a single
  GLOBAL counter, not per-cell -- an unrelated cell resolving anywhere in the world causes one
  harmless EXTRA eviction for an unrelated column, never an incorrect skip. A per-cell-precise
  version (tracking exactly which column actually depends on which cell) would narrow this further
  but was not attempted, since the coarse version is already safe in the one direction that matters
  (never under-evicts) and the measured "vertical sibling" case above shows it already eliminates
  the real repeated-work stampede for the common case this comment described.

### Fixed (comment 55564, two real bugs found by John's own independent native QA of 5bb0b6c)
- **Bug 1, cross-generator isolation:** the eviction-generation tracking map above was keyed by
  packed column X/Z ALONE, in a single `static` map shared by every `EarthGenerator` instance. Two
  SEPARATE generators (two different worlds, or a fresh instance after a world reload) touching the
  identical column X/Z could suppress EACH OTHER's first eviction, even though evicting is always
  scoped to whichever generator instance is actually passed in -- generator B's own `
  SurfaceGenerator` cache was never touched by generator A's eviction at all, yet B's own first
  touch would have been wrongly skipped. This directly contradicted the previous round's own
  disclosure above, which incorrectly claimed the "hot cached two-world" case was already covered by
  the existing `RoadTileSource`-level "native reopen" tests -- those tests prove `RoadTileSource`
  itself is correctly scoped per instance, but never exercised the SEPARATE, newly-added
  `RoadPrefetch`-level tracking map at all, which is where this real bug actually lived. Fixed by
  rescoping the tracking to a `WeakHashMap<EarthGenerator, ...>` outer map (identity-keyed --
  `EarthGenerator` is a real `final` class that never overrides `equals`/`hashCode`, confirmed by
  reading it, so default reference identity is exactly right; a discarded generator's own entry can
  also be reclaimed once nothing else references it, bounding growth across world reloads). Proven
  by a new test, `twoSeparateGeneratorsAtTheIdenticalColumnDoNotSuppressEachOthersFirstEviction`:
  two real, separately-booted generators at the identical column X/Z each get their own genuinely
  first eviction.
- **Bug 2, record-before-evict ordering:** the new generation was recorded in the tracking map
  BEFORE the eviction itself ran, leaving a window in which a competing, already-in-flight `column()`
  computation (the same residual race comment 55496's own doc section discloses, at a different
  point in the same pipeline) could publish a stale result into the now-recorded-fresh slot --
  every LATER call at that same generation would then trust the stale cache without ever
  re-evicting to catch it. Fixed by reordering to evict first, record second, narrowing (not
  eliminating -- this is the same already-disclosed residual race class, not a new one) the window,
  matching the identical reordering precedent comment 55492 already established for the sibling
  eviction call site.
- "Preserve scoped warm reuse," "do not wait... keep [the other two fixes] independently shippable":
  both honored -- the inner per-generator map's own shape/semantics, and the already-landed one-pass
  scan and `RoadTileSource` memory-cache fixes from comment 55562, are entirely unchanged by this
  round; only which generator instance's own map gets consulted, and the record/evict order, changed.
  98/98 tests pass (`./gradlew compileJava compileTestJava test --rerun`).

### Reverted (comment 55565): the eviction-skip optimization above was itself unsafe
- John's own independent native QA of the exact commit above (5bb0b6c) reproduced a real
  late-publication race on `5bb0b6c`: a `SurfaceGenerator.column()` computation already in flight,
  parked just before its own internal publish, released only AFTER the eviction gate's own eviction
  had run and verified the shared cache empty, still inserted a stale, pre-resolution decision into
  the now-fresh slot -- persisted grass survived a reopen even though the real cell was genuinely
  READY. This is the SAME already-disclosed residual race comment 55496's own doc section names
  (never eliminated there either, only narrowed) -- but the skip-on-unchanged-generation
  optimization above removed the ORIGINAL unconditional design's own self-healing property: every
  sibling touch used to re-evict regardless, bounding a stale publish's survival to "until the very
  next touch for that column"; skipping sibling evictions let a stale publish persist until the
  next UNRELATED cell resolved anywhere in the world, potentially a very long time. A genuine
  correctness regression, not merely a narrower race window.
- Per John's own explicit offered fallback ("repair per actual memo/build provenance, or offer a
  separate fast low-risk unique-state optimization while preserving current freshness behavior"):
  the per-column/per-generator generation tracking (`lastEvictedGenerationByGeneratorAndColumn`,
  `RoadTileSource.resolvedGeneration()`, and comment 55564's own two fixes to that tracking) is
  REMOVED entirely -- a provenance-based repair would need visibility into `SurfaceGenerator`'s own
  internal cache-write identity this mod does not have, the same reason `sectionLooksStale`'s own
  retry loop elsewhere in this file is deliberately bounded rather than a full fix. `RoadPrefetch
  .evictStaleSurfaceDecisionIfRelevantAndChanged` is renamed to `evictStaleSurfaceDecisionIfRelevant`
  and now ALWAYS evicts when the cube is naturally relevant -- exactly the original unconditional
  design's own self-healing freshness guarantee, with only the relevance gate itself (comment
  55562's own low-risk, never-implicated improvement) retained.
- The other two comment 55562 fixes (one-pass scan dedup, `RoadTileSource` memory cache for
  resolved tiles) are UNCHANGED and continue to ship, per John's own explicit "do not wait for
  these findings" instruction -- this revert touches only the eviction-skip mechanism.
- Test changes: the three tests whose entire premise was the now-removed skip tracking
  (`evictStaleSurfaceDecisionIfRelevantAndChangedGatesBySiblingAndRealDataTransition`,
  `tenVerticalSiblingCallsAfterResolutionSendZeroHttpAndSkipEverySiblingEviction`,
  `twoSeparateGeneratorsAtTheIdenticalColumnDoNotSuppressEachOthersFirstEviction`) are replaced by
  `evictStaleSurfaceDecisionIfRelevantAlwaysEvictsARelevantColumnEveryTouchWithNoSkipTracking`
  (proves every relevant touch evicts again, no memory of prior evictions) and
  `tenVerticalSiblingCallsAfterResolutionStillSendZeroFurtherHttp` (keeps proving the still-valid,
  independently-shippable zero-further-HTTP measurement from comment 55562, with no claim about
  eviction count). 97/97 tests pass.
- Known, disclosed limitation of this exact candidate: reverting removes the "zero eviction for
  ten warm-replay vertical siblings" measurement -- a relevant column's own `SurfaceGenerator.Column`
  cache IS now re-evicted (and the natural-height survey it drives re-run) on every sibling touch
  again, the same cost the original unconditional design always paid for every relevant column.
  This is the deliberate, disclosed cost of correctness: John's own two rounds of native QA found
  the alternative unsafe twice, and a correctness regression is worse than the performance it was
  meant to buy back. Whether a SAFE version of this optimization (one that actually inspects memo/
  build provenance rather than inferring freshness from an external counter) is worth the added
  complexity is left for a future round, not attempted here.

### Fixed (comment 55567, two real bugs in the shared disk-read helper, found before packaging 50f64e6)
- **Bug 1, wrong precedence:** the shared `readDiskCacheIfPresent`/`readDiskCacheUncached` helper
  checked the `.empty` marker BEFORE the `.json` ready file -- the opposite of this codebase's own
  established readiness/paint precedence (valid geometry always wins). A cell that somehow ends up
  with BOTH files present (a stale `.empty` marker left behind from an earlier genuinely-empty
  fetch, followed later by a real `.json` write once a road actually covers it -- `attemptFetch`
  never cleans up the OTHER marker kind when it writes a new one) would have been memoized as
  confirmed-empty, silently hiding an already-written real road. Fixed by checking `readyFile`
  first. Proven by a new test, `aValidReadyFileWinsOverAStaleEmptyMarkerForTheSameCell` (both
  `cellState` and the actual neighbor-read path `cachedWaysForCellOrEmpty`, plus a fresh instance
  with no shared memory to rule out this only being an ordering artifact).
- **Bug 2, an unvalidated marker trusted as confirmed:** the helper trusted mere file EXISTENCE
  (`Files.isRegularFile`) as proof of a genuine confirmed-empty answer, never inspecting the
  marker's own contents. A malformed or truncated marker (a crash mid-write, disk corruption) would
  have been silently treated as a real "no road here" answer -- a disk READ failure,
  indistinguishable from never having been written at all. Fixed by `isValidEmptyMarker`: a
  genuinely EMPTY (0-byte) file is accepted outright (this whole test suite's own long-standing
  convention for a lightweight, contentless marker, preserved deliberately so existing fixtures
  were not broken); a NON-empty file must actually parse as the same valid JSON `writeCachedCell`
  itself always writes for a real confirmed-empty result -- anything else falls through to
  genuinely PENDING, never a false confirmation. Proven by a new test,
  `aMalformedEmptyMarkerIsNeverTrustedAsConfirmedButAGenuinelyEmptyOneStillIs`.
- **Allocation on the warm path, restored:** `cellState`/`cachedWaysForCellOrEmpty` now check
  `memory` directly, FIRST, before building the `cellId` string and resolving the two `Path`
  objects -- the previous version always paid for that construction regardless of whether a warm
  hit made it unnecessary, a real regression from the original, allocation-free warm path. The
  actual disk-read-and-validate logic (now shared via `readDiskCacheUncached`) is only reached on a
  genuine cold miss.
- Per John's own explicit instruction, no "before/after" timing numbers from the prior round are
  carried forward to this head -- a fresh, independent performance/race benchmark against this
  exact commit is his own next step, not reported here. 99/99 tests pass (97 existing + 2 new).
  Entirely disposable `@TempDir` caches throughout, per his own "never edit user cache" instruction
  -- no real/user cache file was read, written, or inspected by this change or its tests.

### Added (comment 55568, a per-column "build lease" closing the residual publish race)
- John's own independent prototype for the residual late-publish race `ServerCubeWorldRoadPrefetchMixin`'s
  own doc already discloses (comment 55496, natively confirmed real in comment 55565): unconditional
  re-eviction on every relevant touch only ever narrowed that race to "stale for at most until the
  very next touch" -- it never eliminated it. New `SurfaceGeneratorColumnLeaseMixin` targets the
  real, decompiled `SurfaceGenerator.column(int,int)` method directly (confirmed: `columns.get(key)`
  first, then expensive terrain sampling, then `if (columns.size() > 8192) columns.clear();` then
  `columns.putIfAbsent(key, made)` -- a SECOND, unrelated `putIfAbsent` call exists in this same real
  class, on a different `grounds` field inside a different method, never touched by this fix's own
  method-scoped injectors):
  - A plain `@Inject` at `HEAD` -- before `column()` ever consults its cache -- captures the current
    "build lease" for this exact column (`RoadPrefetch.captureColumnLease`, scoped per
    `EarthGenerator` INSTANCE via a `WeakHashMap`, the same cross-generator-isolation lesson comment
    55564's own real bug already taught this file once), carried through to the publish check below
    via a `@Share`d `LocalRef` (per-invocation, never a static field -- comment 55556's own "global
    hold flag leaks" finding, not repeated here).
  - A `@WrapOperation` on the real `columns.putIfAbsent(key, made)` call re-checks the lease
    immediately before publishing (`RoadPrefetch.publishIfLeaseStillMatches`): if an eviction
    (targeted, or a bulk clear) rotated it in between, the real publish is SKIPPED entirely --
    "publication after invalidation is rejected" -- returning the map's current value for that key
    instead, mimicking `putIfAbsent`'s own real contract. "Publication before invalidation is
    removed" needs no new mechanism: a publish landing before an eviction is simply cleaned up by
    that eviction's own `columns.remove(key)`, unchanged.
  - A `@WrapOperation` on the real `columns.clear()` call ("coordinate the existing
    `columns.clear`" -- confirmed real, firing once this generator's own map exceeds 8192 entries)
    bumps this generator's own epoch after the real clear runs, invalidating every column's
    previously-captured lease at once, matching the clear's own "every column" scope.
  - `RoadPrefetch.evictStaleSurfaceDecision` itself now rotates the targeted column's own lease AND
    removes the cache entry atomically, under one short, per-generator lock ("invalidate
    rotates/detaches that lease and removes the entry under a SHORT shared metadata critical
    section") -- since every eviction caller in this codebase already funnels through this one
    method, the new rotation applies everywhere eviction already happens, with no new call sites
    needed. That lock is NEVER held around `column()`'s own terrain sampling, any HTTP, or any wait.
  - Per John's own explicit "keep retouch eviction on relevant workers initially; no global skip":
    this is purely additive underneath the existing eviction-frequency behavior, not a replacement
    for it -- the reverted skip-on-unchanged-generation optimization (comments 55564/55565) is NOT
    reintroduced under any name.
- New tests (`publishIfLeaseStillMatchesAllowsBeforeEvictionAndRejectsAfter`,
  `evictingOneColumnNeverAffectsASiblingColumnsLease`,
  `twoSeparateGeneratorsHaveEntirelyIndependentLeaseState`,
  `bumpGeneratorEpochForFullClearInvalidatesEveryColumnsCapturedLeaseAtOnce`) drive the REAL,
  compiled `RoadPrefetch` lease API directly -- the same "helper-only adversarial model" John's own
  prototype already proved, now as real, run tests in this repository -- proving both orderings his
  own design names, per-column isolation, per-generator isolation, and the bulk-clear-equivalent
  epoch bump. Driving the real 8192-entry `columns.clear()` threshold itself from this harness is
  impractical (would need 8193 real, distinct generated columns); the epoch-bump test covers the
  `RoadPrefetch` side of that coordination instead. 103/103 tests pass.
- `./gradlew compileJava` is clean against the real `combined-73` dependency -- Mixin's own
  annotation processor validated the exact `column(II)...`, `clear()V`, and
  `putIfAbsent(Object,Object)Object` target descriptors (including that the `putIfAbsent` and
  `clear` calls genuinely exist, exactly once each, inside `column(int,int)` specifically) against
  the real classpath.
- Known, disclosed limitation of this exact candidate, same as every other Mixin fix in this entire
  ticket: no test here drives the real, compiled `SurfaceGenerator.column()` method itself (no real
  `ServerLevel`/live NeoForge environment in this harness) -- whether this Mixin actually weaves and
  fires as written, including whether `@Share` correctly threads the captured lease from the
  `@Inject` through to both `@WrapOperation`s in one real invocation, is NOT independently verified
  here. John's own native infrastructure is what will confirm that.

### Known gaps
- **`loadNow`'s own forced-arrival contract, now independently confirmed native, not just
  theoretical (comments 55547/55549):** John's own native control for this exact case -- a same-cube
  forced load landing while the ordinary-streaming path's own retry for that cube is still
  outstanding -- reproduced the synchronous load adopting and SAVING provisional grass before the
  real data arrived; a later fresh control correctly painted the same cube, but the original's
  saved grass survived reopen. `SynchronousLoadGuard`'s existing explicit policy (an arrival needs a
  `Cube` back synchronously, with no "retry next tick" option) is the deliberate reason this
  happens, not a new bug -- it is the same disclosed design boundary from comments 55522/55524,
  just now natively confirmed to actually occur, not only reasoned about. John's own explicit
  framing: keep the ordinary-movement preview scoped as-is (this known gap does not block it), and
  develop a SEPARATE safe policy for forced callers as its own follow-up -- constraints already
  given for whenever that is designed: no server-thread HTTP, no blanket `null`, no silent
  persistence of provisional output, retain actual writes safely if applicable. Not attempted this
  pass.
- Canopy clearance (`RoadCanopyClearance`) classifies purely on `RoadNetwork`'s mapped geometry; it
  does not verify the road material was actually painted under a column (that would mean
  re-deriving it the way the currently-disabled `RoadPrefetch.sectionLooksStale` does). On the rare
  column hit by the still-unresolved residual per-column surface-cache race (55496/55498/55505/
  55507), this can clear a column's canopy even though the surface under it never got painted as
  road. Per John's comment 55510: a clean canopy is not proof the road surface underneath is
  correct, and this does not fix that race.
- The neighborhood read-side check only extends as far as some cell that has actually been
  fetched, anywhere nearby -- a column two or more cells away from every such cell can still see a
  transient gap.
- Chunky pre-generation's own direct `generate()` path (separate from the travel/teleport and
  ordinary-movement paths, both now covered) has no dedicated readiness handling yet.
- No test drives the real, compiled `ServerCubeWorld.produce`/`loadNow` methods themselves -- doing
  so needs a real `ServerLevel`, which this project's `neoForge.unitTest` harness has no fixture
  for (nor does Earth Platform ship one). The new guard logic and the shared dispatch it gates are
  each covered directly instead; see `docs/design.md`'s own note on this for the full breakdown.
- Per comment 55476, still not addressed: `SynchronousLoadGuard`'s exit is wired to `loadNow`'s
  RETURN, not a true try/finally -- an uncaught exception from `adoptReady` (which is not fully
  exception-safe internally) would leave that one thread's guard stuck active (fail-safe direction:
  that thread simply stops prefetching, never incorrectly fetches during an arrival). See
  `docs/design.md`'s own note for the full reasoning.
- `RoadNetwork.segments()`/`junctions()`/`excludedBridgesAndTunnels()` only scan the preloaded
  bundled+external region list -- they do not see live-tile-sourced ways.
- The tile cache is scoped to the whole game install, not per-save.
- No worldwide-build pipeline exists yet for the pinned, offline production source (a separate
  SQLite-catalog effort, piloted against a regional extract only so far); the Overpass.ch preview
  provider is live data for evaluation, not that pinned snapshot.
- A disposable-profile playtest on the bundled Yosemite extract confirmed the road looks smooth
  (full Slopes/car/Flowing Fluids pack); the live-tile-fetched path (an unbundled destination) has
  not yet had an in-game visual confirmation.
- The new canopy-clearance test calls `RoadCanopyClearance.clear` directly against a real generator
  and real pinned road geometry; it does not exercise `SurfaceGeneratorRoadClearanceMixin`'s own
  `@Redirect` wiring end to end (that would need a real tree/structure actually landing a block over
  this exact pinned column during generation, not reproducible from this harness). Not yet
  confirmed in a live playtest either.
- Per comment 55522: the FETCH side (priority + cooldown retry) and the deferred-generation half
  (comments 55526/55532) are both now implemented, covered by direct tests, and awaiting John's
  own native paint-continuity/save-reopen verification -- this draft is not yet confirmed ready
  for Zippy.
- The forced-load (`loadNow`) exception from comment 55524 is unchanged, already documented
  (`SynchronousLoadGuard`) and already tested (`SynchronousLoadGuardTest`) from earlier in this
  ticket; John's own real-server confirmation that it holds (zero extra HTTP, a forced arrival can
  still adopt pending/natural terrain the same way the ordinary-movement path can) is now
  cross-referenced in `ServerCubeWorldRoadPrefetchMixin`'s own class doc rather than duplicating
  that coverage. `loadNow`'s own synchronous contract is deliberately left unchanged.
- The cooldown-retry loop (`waysForCellRetryingAcrossCooldown`) occupies `ASYNC_FETCH_EXECUTOR`'s
  one shared thread for the full duration of each sleep -- no other queued task, immediate or
  lookahead, can run until this call's own `Runnable.run()` returns. Avoiding that would need
  scheduling a resubmission after the cooldown rather than sleeping in place; not attempted this
  pass, per John's own "if feasible" framing.
