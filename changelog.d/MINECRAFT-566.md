bump: minor

### Added
- Indexed, multi-region road network (`RoadNetwork`/`RoadSegment`/`RoadClass`):
  ways are classified against every mapped vehicular `highway=*` value (foot
  trails are rejected at load time) and bucketed into a spatial grid index for
  O(1)-ish column lookups instead of a linear scan. Shared OSM node IDs
  between ways are tracked for mapped-junction detection
  (`RoadNetwork.junctions()`).
- Installed road data, bundled AND external, using the same versioned region
  format (`schema_version`, checked at load): bundled regions are listed by
  `data/earthplatformroads/roads/regions/index.json`; external regions are
  every `*.json` file dropped into `config/earthplatformroads/roads/regions/`
  in a real install -- mapped coverage for a new destination is installing a
  file there, not a bigger bundled extract or a Java change
  (`RoadNetworkExternalRegionTest` proves this against a genuinely unbundled
  destination).
- Layered-blackstone road surfaces at a real ground-height step: a dedicated
  compat block (`LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE`, registered
  against Layered Slopes 0.2.0's `LayeredBlock`) replaces the previous
  always-full `POLISHED_BLACKSTONE` top surface, decaying by Chebyshev
  distance to the nearest same-road neighbor with lower real ground height
  (`RoadSurfaceLayering`), with a matching non-full collision shape; `under()`
  always stays a full block. Verified against real generated data, not a
  synthetic slope value, including the cascade-regression case caught in
  review (see the ticket comments for that back-and-forth).

### Changed
- `EarthGeneratorRoadMixin` now looks up the covering `RoadSegment` through
  the indexed `RoadNetwork` and selects a layer count from distance to a real
  lower same-road neighbor, instead of always painting a full block.
- The pinned Southside Drive fixture moved from
  `data/earthplatformroads/roads/southside_drive_yosemite.json` to the new
  region format at `data/earthplatformroads/roads/regions/yosemite_valley.json`.

### Milestone boundary (not an open gap -- see the ticket's milestone update)
- Bridges/tunnels are modeled (`RoadSegment.bridge()`/`tunnel()`/`layer()`)
  and explicitly skipped (no surface drawn, never an at-grade crossing along
  a riverbed); correct rendering is deferred to a following milestone by
  the ticket's own updated scope.

### Known gaps
- Only the original pinned Southside Drive extract is bundled by default; a
  second, bigger hand-curated geographic fixture is no longer the
  deliverable (superseded by the external-region pipeline above).
- The layering heuristic is verified against real generated data in tests
  but not against an actual in-game playtest.
