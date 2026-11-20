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
  `TerrariumTileSource`. The first time a destination's cell is needed it is fetched from a live
  Overpass-compatible endpoint and cached; a network failure leaves the cell retryable (never
  permanently marked as a roadless gap); a genuine empty result stays confirmed-empty.
  `EarthGeneratorPrepareMixin` hooks Earth Platform's own `prepare`/`prepareNear` "fetch ahead of
  generation" entry points to trigger this off the hot generation path, so the actual `surface()`
  hook (and therefore Earth Platform's synchronous `loadNow`) never blocks on HTTP.
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

### Known gaps
- `EarthGeneratorPrepareMixin` prefetches only the single cell a point falls in, not a
  multi-kilometre reach matching Earth Platform's own `prepareNear`.
- Chunky pre-generation's own direct `generate()` path (separate from the travel/teleport path
  `prepareNear` covers) has no dedicated readiness handling yet.
- No real multi-way Yosemite extract or real distant-site extract with full source-vs-generated
  accounting (class counts, connected intersections, tile boundary, documented omissions) exists.
- No disposable-profile in-game visual/collision/seam demonstration has been performed; this
  requires an actual running Minecraft client, which this agent's environment does not have.
