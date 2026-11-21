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

### Known gaps
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
