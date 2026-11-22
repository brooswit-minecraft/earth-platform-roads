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
- Opt-in, credential-free Roads loopback-proxy CLIENT mode
  (`-Dearthplatformroads.loopbackProxyEnabled=true`,
  `-Dearthplatformroads.loopbackProxyEndpoint=...`, off by default): redirects the Roads HTTP
  client to a configurable loopback base URL instead of the real Overpass.ch endpoint, checked
  strictly before the existing direct-Overpass.ch opt-in chain so that chain's own code path, key
  binding, and behavior stay byte-for-byte unchanged whenever this switch is off. The direct-
  provider API key is never sent to a loopback target (passed as `null` in this mode, on top of
  `RoadTileSource`'s own existing endpoint-bound credential rule). No silent fallback: a loopback
  fetch failure surfaces through the mod's existing retry/error path exactly like any other failed
  fetch, never a silent retry against the real endpoint. Verified against a disposable, in-process
  fake-proxy test fixture (standing in for the still-separately-repoed MINECRAFT-570 SQLite proxy)
  for the exact 1-send/1-hit/0-upstream-send delta, cold-Roads/warm-fake-proxy vs. warm-Roads-cache
  timing, and the measured interval between actual mod HTTP sends. No coordinate, query, endpoint,
  header, or key is ever logged anywhere in this path.
