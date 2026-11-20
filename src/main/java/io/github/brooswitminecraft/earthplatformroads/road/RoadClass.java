package io.github.brooswitminecraft.earthplatformroads.road;

import java.util.Map;

/**
 * Mapped vehicular road classes this mod renders, matching OSM {@code highway=*} values. Foot-only
 * values ({@code footway}, {@code path}, {@code cycleway}, {@code pedestrian}, {@code steps},
 * {@code track} with {@code motor_vehicle=no}, ...) are deliberately not in this map: a way whose
 * {@code highway} tag is not a key here is not ingested as a road at all (see
 * {@link #fromOsmHighway}), so foot trails are never conflated with roads.
 */
public enum RoadClass {
    MOTORWAY(5.0),
    TRUNK(4.5),
    PRIMARY(4.0),
    SECONDARY(3.0),
    TERTIARY(2.5),
    UNCLASSIFIED(2.0),
    RESIDENTIAL(2.0),
    LIVING_STREET(2.0),
    MOTORWAY_LINK(3.0),
    TRUNK_LINK(3.0),
    PRIMARY_LINK(3.0),
    SECONDARY_LINK(2.5),
    TERTIARY_LINK(2.5),
    SERVICE(1.5),
    /** OSM's generic "a road exists here, not yet classified" value; rare, but still a mapped vehicular way. */
    ROAD(2.0);

    /** Default half-width in blocks when the way carries no usable {@code lanes} tag. */
    public final double defaultHalfWidthBlocks;

    RoadClass(double defaultHalfWidthBlocks) {
        this.defaultHalfWidthBlocks = defaultHalfWidthBlocks;
    }

    private static final Map<String, RoadClass> BY_OSM_HIGHWAY = Map.ofEntries(
            Map.entry("motorway", MOTORWAY),
            Map.entry("trunk", TRUNK),
            Map.entry("primary", PRIMARY),
            Map.entry("secondary", SECONDARY),
            Map.entry("tertiary", TERTIARY),
            Map.entry("unclassified", UNCLASSIFIED),
            Map.entry("residential", RESIDENTIAL),
            Map.entry("living_street", LIVING_STREET),
            Map.entry("motorway_link", MOTORWAY_LINK),
            Map.entry("trunk_link", TRUNK_LINK),
            Map.entry("primary_link", PRIMARY_LINK),
            Map.entry("secondary_link", SECONDARY_LINK),
            Map.entry("tertiary_link", TERTIARY_LINK),
            Map.entry("service", SERVICE),
            Map.entry("road", ROAD));

    /** {@code null} for any {@code highway} value this mod does not treat as a vehicular road. */
    public static RoadClass fromOsmHighway(String highway) {
        return BY_OSM_HIGHWAY.get(highway);
    }

    /** Half-width in blocks for a way of this class carrying {@code lanes} lanes (0 or unknown: use the class default). */
    public double halfWidthBlocks(int lanes) {
        if (lanes <= 0) return defaultHalfWidthBlocks;
        // One block per lane plus a one-block shoulder either side, matching the convention recorded
        // in docs/road-data-provenance.md for the original two-lane Southside Drive pin (lanes=2 -> 3.0).
        return lanes / 2.0 + 2.0;
    }
}
