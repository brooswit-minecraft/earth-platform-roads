bump: minor

### Added
- Indexed, multi-region road network (`RoadNetwork`/`RoadSegment`/`RoadClass`):
  ways are loaded from `data/earthplatformroads/roads/regions/*.json` (listed
  by `regions/index.json`), bucketed into a spatial grid index for O(1)-ish
  column lookups instead of a linear scan, and classified against mapped
  vehicular `highway=*` values only (foot trails are rejected at load time).
  Shared OSM node IDs between ways are tracked for mapped-junction detection
  (`RoadNetwork.junctions()`).
- Layered-blackstone road surfaces on inclines: a dedicated compat block
  (`LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE`, registered against
  Layered Slopes 0.2.0's `LayeredBlock`) replaces the previous always-full
  `POLISHED_BLACKSTONE` top surface when a column's local slope is nonzero,
  with a matching non-full collision shape; `under()` always stays a full
  block.

### Changed
- `EarthGeneratorRoadMixin` now looks up the covering `RoadSegment` through
  the new indexed `RoadNetwork` and selects a layer count from
  `RoadSurfaceLayering` instead of always painting a full block.
- The pinned Southside Drive fixture moved from
  `data/earthplatformroads/roads/southside_drive_yosemite.json` to the new
  region format at `data/earthplatformroads/roads/regions/yosemite_valley.json`.

### Known gaps (see the MINECRAFT-566 ticket comment for the full list)
- Only the original pinned Southside Drive extract is bundled; a second
  geographic fixture (Presidio Parkway) and a wider Yosemite extract
  covering El Capitan Bridge's junctions were not ingested this pass.
- The slope-to-layer-count mapping (`RoadSurfaceLayering`) is a first
  heuristic, not verified in-game (no build/playtest was run to confirm the
  rendered/collision seam).
- Bridges/tunnels are modeled (`RoadSegment.bridge()`/`tunnel()`/`layer()`)
  but never rendered as a crossing; a bridge way is simply not drawn as a
  surface, not routed along the riverbed.
