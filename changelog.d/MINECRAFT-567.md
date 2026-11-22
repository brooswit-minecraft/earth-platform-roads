bump: minor

### Added
- `RoadFetchTelemetry`: local, in-process aggregate counters for `RoadTileSource`'s own real fetch/
  cache call sites -- actual JSON tile payload reads, actual non-zero-byte `.empty`-marker payload
  reads, metadata-only zero-byte marker checks, in-memory tile-cache hits, real outbound HTTP sends
  (including genuine retry sends), and suppressed/deduped requests that never reach the network.
  Every counter is a plain aggregate integer; nothing here ever logs a coordinate, endpoint, header,
  query body, or key. Cheap `snapshot()`/`deltaSinceLastCall()` helpers, plus opt-in (off by default),
  rate-limited low-frequency local reporting
  (`-Dearthplatformroads.fetchTelemetryReportingEnabled=true`,
  `-Dearthplatformroads.fetchTelemetryReportIntervalSeconds=...`) that logs only aggregate numbers.
  Pure instrumentation -- no existing fetch/cache behavior changes.
