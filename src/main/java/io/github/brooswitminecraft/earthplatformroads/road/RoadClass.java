package io.github.brooswitminecraft.earthplatformroads.road;

import java.util.Map;

/**
 * Mapped vehicular road classes this mod renders, matching OSM {@code highway=*} values. Foot-only
 * values ({@code footway}, {@code path}, {@code cycleway}, {@code pedestrian}, {@code steps},
 * {@code track} with {@code motor_vehicle=no}, ...) are deliberately not in this map: a way whose
 * {@code highway} tag is not a key here is not ingested as a road at all (see
 * {@link #fromOsmHighway}), so foot trails are never conflated with roads.
 *
 * <p>MINECRAFT-566's road-styling ask (John's live-playtest feedback on {@code a632bdc}: "present
 * roads look about half as wide as expected"): every {@code defaultPavedHalfWidthBlocks} here is
 * roughly double its previous value, and the lanes-based formula in {@link #pavedHalfWidthBlocks}
 * is doubled the same way. {@link #hasCenterline} opts {@link #SERVICE} out of the new yellow
 * centerline stripe -- a driveway/service road is the one mapped class that reads as a narrow,
 * unstriped path even at its own (still widened) paved width, matching real-world road-marking
 * convention. See {@link RoadSegment#crossSectionAt} for how this combines with the new gravel
 * shoulder into an actual per-column cross-section.
 */
public enum RoadClass {
    MOTORWAY(10.0, true),
    TRUNK(9.0, true),
    PRIMARY(8.0, true),
    SECONDARY(6.0, true),
    TERTIARY(5.0, true),
    UNCLASSIFIED(4.0, true),
    RESIDENTIAL(4.0, true),
    LIVING_STREET(4.0, true),
    MOTORWAY_LINK(6.0, true),
    TRUNK_LINK(6.0, true),
    PRIMARY_LINK(6.0, true),
    SECONDARY_LINK(5.0, true),
    TERTIARY_LINK(5.0, true),
    /** Narrow, path-like, deliberately unstriped per MINECRAFT-566's styling ask -- see this enum's own class doc. */
    SERVICE(3.0, false),
    /** OSM's generic "a road exists here, not yet classified" value; rare, but still a mapped vehicular way. */
    ROAD(4.0, true),
    /**
     * MINECRAFT-568: an eligible motor track (not excluded by {@code motor_vehicle=no} -- see the
     * parsers that check that tag before ever constructing this class). Narrow and unstriped, like
     * {@link #SERVICE}: a track is included here carefully, as a real vehicular way when mapped as
     * one, never because this mod assumes a track implies any particular surface -- its material
     * still comes from the explicit {@code surface} tag (or the same {@link RoadSurfaceMaterial#PAVED}
     * class default as everything else when that tag is missing), per {@link RoadSurfaceMaterial}'s
     * own class doc.
     */
    TRACK(3.0, false);

    /** Default PAVED half-width in blocks (not counting the gravel shoulder) when the way carries no usable {@code lanes} tag. */
    public final double defaultPavedHalfWidthBlocks;
    /** Whether this class gets the thin yellow-terracotta centerline stripe at all; see this enum's own class doc. */
    public final boolean hasCenterline;

    RoadClass(double defaultPavedHalfWidthBlocks, boolean hasCenterline) {
        this.defaultPavedHalfWidthBlocks = defaultPavedHalfWidthBlocks;
        this.hasCenterline = hasCenterline;
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
            Map.entry("road", ROAD),
            Map.entry("track", TRACK));

    /** {@code null} for any {@code highway} value this mod does not treat as a vehicular road. */
    public static RoadClass fromOsmHighway(String highway) {
        return BY_OSM_HIGHWAY.get(highway);
    }

    /** PAVED half-width in blocks (not counting the gravel shoulder) for a way of this class carrying {@code lanes} lanes (0 or unknown: use the class default). */
    public double pavedHalfWidthBlocks(int lanes) {
        if (lanes <= 0) return defaultPavedHalfWidthBlocks;
        // Doubled from the original one-block-per-lane-plus-one-block-shoulder convention
        // (lanes/2.0 + 2.0, recorded in docs/road-data-provenance.md for the original pinned
        // Southside Drive fixture) to match the new ~2x paved width.
        return lanes + 4.0;
    }
}
