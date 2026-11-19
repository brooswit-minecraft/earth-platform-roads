# Road data: source, snapshot, licence, reproduction

## Source and licence

Road centerline data is from [OpenStreetMap](https://www.openstreetmap.org/copyright),
© OpenStreetMap contributors, licensed [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1.0/).
This prototype bundles a bounded extract, not the whole planet file or even
all of Yosemite's road network.

## Exact query and snapshot

Fetched via the public Overpass API on 2026-11-19 (`timestamp_osm_base`
`2026-11-19T17:40:26Z`, recorded in the pinned file itself):

```
[out:json][timeout:60];
way["name"~"Southside Drive",i](37.70,-119.65,37.76,-119.55);
out geom;
```

Of the 9 ways this returned, this prototype uses OSM way `10432929`
(`highway=secondary`, `lanes=2`, `oneway=yes`, `maxspeed=35 mph`,
`surface=asphalt`), node indices 60-68 of that way's 86-point geometry: a
~190m run (`length_metres_approx` in the pinned file) near Yosemite Valley's
El Capitan Meadow -- the closest point on this way to Earth Platform's own
default Earth-world home point
(`EarthSettings.DEFAULT_LATITUDE/LONGITUDE`, 37.7240, -119.6365) is ~370m
away, well inside the same bounded extract.

The full pinned extract, including every field above plus the node IDs and
the nine centerline points actually used, is bundled at
`src/main/resources/data/earthplatformroads/roads/southside_drive_yosemite.json`
and is the only data this prototype's generation code reads -- see
`docs/design.md` for why that is safe to read during cube generation (no
network access at generation time; the fetch above happened once, ahead of
time, by a human/agent preparing this pinned extract).

## Reproducing the fetch

```sh
curl -sS -X POST --data-binary @- \
  -H 'User-Agent: earth-platform-roads-prototype/0.1 (contact: see repo)' \
  https://overpass-api.de/api/interpreter <<'EOF'
[out:json][timeout:60];
way["name"~"Southside Drive",i](37.70,-119.65,37.76,-119.55);
out geom;
EOF
```

Overpass is a live community service: a re-run today will return whatever
OSM looks like today, not the pinned 2026-11-19 snapshot. The pinned JSON
resource in this repo, not a fresh fetch, is this prototype's actual data
source -- that's the point of pinning it.

## Why six blocks wide

OSM tags this way `lanes=2`; a half-width of 3 blocks (6 blocks total)
covers two lanes plus a shoulder either side, matching the ticket's
"roughly six blocks wide" without claiming a specific real-world lane
width.
