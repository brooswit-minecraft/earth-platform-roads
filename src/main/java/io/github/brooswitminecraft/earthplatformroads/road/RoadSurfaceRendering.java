package io.github.brooswitminecraft.earthplatformroads.road;

import io.github.brooswitminecraft.earthplatformroads.compat.layeredslopes.LayeredSlopesCompat;
import io.github.brooswitminecraft.layeredslopes.Anchor;
import io.github.brooswitminecraft.layeredslopes.LayerMath;
import io.github.brooswitminecraft.layeredslopes.LayeredBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Pure {@link BlockState} selection for a road column's top/under surface, by {@link
 * RoadSegment.CrossSection} zone, slope-layering decay, and mapped {@link RoadSurfaceMaterial} --
 * factored out of {@code EarthGeneratorRoadMixin} (MINECRAFT-568) so this logic is directly
 * unit-testable: a Mixin class may only contain {@code private} helper methods (Mixin itself
 * rejects applying a mixin whose class has any other non-injector method, "non-private static
 * method ... Apply Methods"), so these could not stay package-visible there for a test to call.
 *
 * <p>MINECRAFT-566's road-styling history (comment 55484): {@code CENTERLINE} (thin, astride the
 * centerline, classes that opt in only) paints {@link LayeredSlopesCompat#LAYERED_YELLOW_TERRACOTTA}
 * over a blackstone {@code under()} (a painted line ON pavement, not a full-depth material change)
 * -- but only for a {@link RoadSurfaceMaterial#hasCenterlineMarking} (paved) recipe, per
 * MINECRAFT-568's "keep center markings for appropriate paved recipes"; an unpaved way's centerline
 * band instead renders as that way's own material, same as its {@code PAVED} zone. {@code SHOULDER}
 * (the narrow band outside the paved edge) always paints {@link LayeredSlopesCompat#LAYERED_GRAVEL}
 * over a gravel {@code under()}, independent of the road's own material -- "keep existing ...
 * clearing behavior," unchanged by MINECRAFT-568. The SAME {@link RoadSurfaceLayering} decay applies
 * to every zone's material, so the slope-layering continuity this mod already provides for
 * blackstone carries over to every new material too, not just the paved surface. {@code under()}
 * always stays a full block (never layered) because {@code EarthGenerator} uses it at depths 1-3
 * under the column.
 */
public final class RoadSurfaceRendering {
    private RoadSurfaceRendering() {}

    /**
     * The full-depth material under this zone's top surface. The gravel shoulder is always gravel,
     * independent of the road's own mapped {@code surface}; a centerline stripe (paved recipes only)
     * paints a stripe ON pavement, so its {@code under} is still blackstone, not the stripe's own
     * paint color. Every other zone -- the paved surface itself, and an unpaved way's centerline
     * band (no stripe to paint, see {@link #topSurfaceFor}) -- uses this way's own material.
     */
    public static BlockState underSurfaceFor(RoadSegment.CrossSection crossSection, RoadSurfaceMaterial material) {
        if (crossSection == RoadSegment.CrossSection.SHOULDER) return Blocks.GRAVEL.defaultBlockState();
        if (crossSection == RoadSegment.CrossSection.CENTERLINE && material.hasCenterlineMarking()) {
            return Blocks.POLISHED_BLACKSTONE.defaultBlockState();
        }
        return fullBlockFor(material);
    }

    /**
     * Full block when no same-road neighbor within range is lower; a fractional layered block of
     * this zone's own material that decays by distance to one that is. The yellow-terracotta
     * centerline stripe only ever paints over a {@link RoadSurfaceMaterial#hasCenterlineMarking}
     * (paved) recipe -- "keep center markings for appropriate paved recipes" -- so a gravel, dirt or
     * sand way's CENTERLINE band (its {@code roadClass} still opted in) simply renders as that way's
     * own material instead, exactly like its PAVED zone, with no separate stripe.
     */
    public static BlockState topSurfaceFor(RoadSegment.CrossSection crossSection, int layers, RoadSurfaceMaterial material) {
        if (crossSection == RoadSegment.CrossSection.SHOULDER) {
            return layers >= LayerMath.MAX_LAYERS
                    ? Blocks.GRAVEL.defaultBlockState()
                    : layered(LayeredSlopesCompat.LAYERED_GRAVEL.get(), layers);
        }
        if (crossSection == RoadSegment.CrossSection.CENTERLINE && material.hasCenterlineMarking()) {
            return layers >= LayerMath.MAX_LAYERS
                    ? Blocks.YELLOW_TERRACOTTA.defaultBlockState()
                    : layered(LayeredSlopesCompat.LAYERED_YELLOW_TERRACOTTA.get(), layers);
        }
        return layers >= LayerMath.MAX_LAYERS ? fullBlockFor(material) : layered(layeredBlockFor(material), layers);
    }

    /** The plain, full-block (16-layer-equivalent) form of a material family, used both as a zone's "under" and its own full-height top. */
    private static BlockState fullBlockFor(RoadSurfaceMaterial material) {
        return switch (material) {
            case PAVED -> Blocks.POLISHED_BLACKSTONE.defaultBlockState();
            case GRAVEL_COMPACTED -> Blocks.GRAVEL.defaultBlockState();
            case DIRT_GROUND -> Blocks.DIRT.defaultBlockState();
            case SAND -> Blocks.SAND.defaultBlockState();
        };
    }

    /**
     * This family's own layered block. PAVED/GRAVEL_COMPACTED are this mod's own {@link
     * LayeredSlopesCompat} registrations (pre-existing, MINECRAFT-566); DIRT_GROUND/SAND instead
     * reuse Layered Slopes' own already-registered {@code dynamicterrain:layered_dirt}/{@code
     * layered_sand} directly -- see {@link LayeredSlopesCompat}'s own class doc for why.
     */
    private static Block layeredBlockFor(RoadSurfaceMaterial material) {
        return switch (material) {
            case PAVED -> LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE.get();
            case GRAVEL_COMPACTED -> LayeredSlopesCompat.LAYERED_GRAVEL.get();
            case DIRT_GROUND -> LayeredSlopesCompat.layeredDirtFromLayeredSlopes();
            case SAND -> LayeredSlopesCompat.layeredSandFromLayeredSlopes();
        };
    }

    private static BlockState layered(Block block, int layers) {
        return block.defaultBlockState().setValue(LayeredBlock.ANCHOR, Anchor.FLOOR).setValue(LayeredBlock.LAYERS, layers);
    }
}
