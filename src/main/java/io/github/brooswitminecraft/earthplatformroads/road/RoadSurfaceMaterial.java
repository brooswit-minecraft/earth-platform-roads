package io.github.brooswitminecraft.earthplatformroads.road;

import java.util.Locale;
import java.util.Map;

/**
 * The visible material family a road's PAVED cross-section zone renders as, derived from the
 * mapped OSM {@code surface} tag -- MINECRAFT-568's "render mapped road surface materials" ask.
 *
 * <p><b>Explicit tag first, class default only if missing (per the ticket's explicit instruction)</b>:
 * {@link #fromOsmSurfaceTag} maps a known {@code surface} value to its family; a {@code null} or
 * unrecognized value always falls back to {@link #PAVED} -- the single class default this mod has
 * always rendered -- regardless of {@code highway} class. In particular a {@code highway=track} way
 * with no {@code surface} tag renders exactly like any other untagged way: this mod never assumes "a
 * track means dirt" (the ticket's explicit instruction), since that would be inventing a source tag
 * rather than reading one. {@link RoadSegment#surface()} keeps the raw tag value (or {@code null})
 * alongside the derived family, so "no tag was ever mapped" stays distinguishable from "mapped and
 * explicitly paved" for provenance, even though both currently render identically.
 *
 * <p><b>Dirt/ground is a first-slice fallback</b> (per the ticket): a true sub-block layered dirt
 * path is MINECRAFT-569's own paired work; until it ships, {@link #DIRT_GROUND} reuses the same
 * {@link io.github.brooswitminecraft.layeredslopes.LayerMath} layering this mod already applies to
 * blackstone, just over plain dirt, rather than waiting on 569.
 */
public enum RoadSurfaceMaterial {
    /** The pre-existing default: polished blackstone, with the yellow-terracotta centerline stripe. */
    PAVED,
    /** Loose aggregate, not bound pavement: no centerline stripe -- see {@link #hasCenterlineMarking}. */
    GRAVEL_COMPACTED,
    /** First-slice fallback for an unpaved way; MINECRAFT-569 will replace this with a true layered dirt path. */
    DIRT_GROUND,
    SAND;

    private static final Map<String, RoadSurfaceMaterial> BY_OSM_SURFACE = Map.ofEntries(
            Map.entry("asphalt", PAVED),
            Map.entry("paved", PAVED),
            Map.entry("concrete", PAVED),
            Map.entry("concrete:plates", PAVED),
            Map.entry("concrete:lanes", PAVED),
            Map.entry("paving_stones", PAVED),
            Map.entry("sett", PAVED),
            Map.entry("cobblestone", PAVED),
            Map.entry("metal", PAVED),
            Map.entry("wood", PAVED),
            Map.entry("gravel", GRAVEL_COMPACTED),
            Map.entry("compacted", GRAVEL_COMPACTED),
            Map.entry("fine_gravel", GRAVEL_COMPACTED),
            Map.entry("pebblestone", GRAVEL_COMPACTED),
            Map.entry("ballast", GRAVEL_COMPACTED),
            Map.entry("dirt", DIRT_GROUND),
            Map.entry("ground", DIRT_GROUND),
            Map.entry("earth", DIRT_GROUND),
            Map.entry("unpaved", DIRT_GROUND),
            Map.entry("mud", DIRT_GROUND),
            Map.entry("sand", SAND));

    /**
     * {@link #PAVED} for {@code null} (no mapped {@code surface} tag at all) or any value this mod
     * does not recognize -- never a guess, and never different by {@code highway} class (see this
     * enum's own class doc for why a track is not special-cased here).
     */
    public static RoadSurfaceMaterial fromOsmSurfaceTag(String surfaceTag) {
        if (surfaceTag == null) return PAVED;
        RoadSurfaceMaterial found = BY_OSM_SURFACE.get(surfaceTag.toLowerCase(Locale.ROOT).trim());
        return found != null ? found : PAVED;
    }

    /** Only {@link #PAVED} keeps the yellow-terracotta centerline stripe -- "keep center markings for appropriate paved recipes." */
    public boolean hasCenterlineMarking() {
        return this == PAVED;
    }
}
