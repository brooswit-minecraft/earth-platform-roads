package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.geography.GeoPoint;
import io.github.brooswitminecraft.earthplatformroads.compat.layeredslopes.LayeredSlopesCompat;
import io.github.brooswitminecraft.layeredslopes.Anchor;
import io.github.brooswitminecraft.layeredslopes.LayerMath;
import io.github.brooswitminecraft.layeredslopes.LayeredBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-568: "render mapped road surface materials" -- exercises
 * {@link RoadSurfaceRendering#underSurfaceFor}/{@link RoadSurfaceRendering#topSurfaceFor} directly
 * against every material family, with no generator, no DEM fetch and no world bootstrap -- pure
 * block-state selection, run inside the modded test JVM only because {@link LayeredSlopesCompat}'s
 * {@code DeferredBlock}s need real registries to {@code .get()} (same reason {@code
 * RoadGenerationTest} needs that environment), not because this test itself does any I/O. "Actual
 * generated material" evidence for this ticket's ask, and "no warm-path added I/O" by construction:
 * every method under test is pure {@code BlockState} selection over already-in-memory values.
 */
class RoadSurfaceRenderingTest {
    @Test
    void missingSurfaceOnAPavedZoneStillGeneratesPlainBlackstoneExactlyLikeBeforeThisTicket() {
        RoadSurfaceMaterial material = RoadSurfaceMaterial.fromOsmSurfaceTag(null);
        BlockState top = RoadSurfaceRendering.topSurfaceFor(RoadSegment.CrossSection.PAVED, LayerMath.MAX_LAYERS, material);
        assertEquals(Blocks.POLISHED_BLACKSTONE.defaultBlockState(), top, "Existing paved behavior must be byte-identical when surface is unmapped");
    }

    @Test
    void explicitGravelSurfaceOnAPavedZoneGeneratesGravelNotBlackstone() {
        RoadSurfaceMaterial material = RoadSurfaceMaterial.fromOsmSurfaceTag("gravel");
        BlockState top = RoadSurfaceRendering.topSurfaceFor(RoadSegment.CrossSection.PAVED, LayerMath.MAX_LAYERS, material);
        assertEquals(Blocks.GRAVEL.defaultBlockState(), top, "A gravel-tagged way must render visibly different from paved blackstone");
    }

    @Test
    void explicitDirtOrGroundSurfaceOnAPavedZoneGeneratesDirt() {
        RoadSurfaceMaterial material = RoadSurfaceMaterial.fromOsmSurfaceTag("ground");
        BlockState top = RoadSurfaceRendering.topSurfaceFor(RoadSegment.CrossSection.PAVED, LayerMath.MAX_LAYERS, material);
        assertEquals(Blocks.DIRT.defaultBlockState(), top);
    }

    @Test
    void explicitSandSurfaceOnAPavedZoneGeneratesSand() {
        RoadSurfaceMaterial material = RoadSurfaceMaterial.fromOsmSurfaceTag("sand");
        BlockState top = RoadSurfaceRendering.topSurfaceFor(RoadSegment.CrossSection.PAVED, LayerMath.MAX_LAYERS, material);
        assertEquals(Blocks.SAND.defaultBlockState(), top);
    }

    @Test
    void aPartialLayerCountUsesThisMaterialsOwnLayeredBlockNotAlwaysBlackstone() {
        BlockState gravelTop = RoadSurfaceRendering.topSurfaceFor(
                RoadSegment.CrossSection.PAVED, LayerMath.MIN_LAYERS, RoadSurfaceMaterial.GRAVEL_COMPACTED);
        assertTrue(gravelTop.is(LayeredSlopesCompat.LAYERED_GRAVEL.get()), "Expected the layered GRAVEL compat block, got " + gravelTop);
        assertEquals(LayerMath.MIN_LAYERS, gravelTop.getValue(LayeredBlock.LAYERS));
        assertEquals(Anchor.FLOOR, gravelTop.getValue(LayeredBlock.ANCHOR));

        BlockState dirtTop = RoadSurfaceRendering.topSurfaceFor(
                RoadSegment.CrossSection.PAVED, LayerMath.MIN_LAYERS, RoadSurfaceMaterial.DIRT_GROUND);
        assertTrue(dirtTop.is(LayeredSlopesCompat.layeredDirtFromLayeredSlopes()),
                "Expected Layered Slopes' own dynamicterrain:layered_dirt (reused, not a new registration), got " + dirtTop);
        assertEquals(LayerMath.MIN_LAYERS, dirtTop.getValue(LayeredBlock.LAYERS));

        BlockState sandTop = RoadSurfaceRendering.topSurfaceFor(
                RoadSegment.CrossSection.PAVED, LayerMath.MIN_LAYERS, RoadSurfaceMaterial.SAND);
        assertTrue(sandTop.is(LayeredSlopesCompat.layeredSandFromLayeredSlopes()),
                "Expected Layered Slopes' own dynamicterrain:layered_sand (reused, not a new registration), got " + sandTop);
        assertEquals(LayerMath.MIN_LAYERS, sandTop.getValue(LayeredBlock.LAYERS));
    }

    /** John's review (56315): "inventory and reuse the compatible Layered Slopes palette first" -- confirms no duplicate block id was registered for either material. */
    @Test
    void dirtAndSandReuseLayeredSlopesOwnBlocksRatherThanRegisteringNewOnes() {
        assertEquals(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("dynamicterrain", "layered_dirt"),
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(LayeredSlopesCompat.layeredDirtFromLayeredSlopes()));
        assertEquals(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("dynamicterrain", "layered_sand"),
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(LayeredSlopesCompat.layeredSandFromLayeredSlopes()));
    }

    @Test
    void centerlineStripeStillPaintsYellowTerracottaOnlyForThePavedRecipe() {
        BlockState top = RoadSurfaceRendering.topSurfaceFor(RoadSegment.CrossSection.CENTERLINE, LayerMath.MAX_LAYERS, RoadSurfaceMaterial.PAVED);
        assertEquals(Blocks.YELLOW_TERRACOTTA.defaultBlockState(), top, "Existing centerline-stripe behavior must be unchanged for paved ways");
        BlockState under = RoadSurfaceRendering.underSurfaceFor(RoadSegment.CrossSection.CENTERLINE, RoadSurfaceMaterial.PAVED);
        assertEquals(Blocks.POLISHED_BLACKSTONE.defaultBlockState(), under, "The stripe is painted ON pavement, so its under() must stay blackstone");
    }

    /** "Keep center markings for appropriate paved recipes" -- an unpaved way's centerline band has no stripe to paint. */
    @Test
    void anUnpavedWaysCenterlineBandHasNoStripeAndRendersItsOwnMaterialInstead() {
        BlockState top = RoadSurfaceRendering.topSurfaceFor(RoadSegment.CrossSection.CENTERLINE, LayerMath.MAX_LAYERS, RoadSurfaceMaterial.DIRT_GROUND);
        assertEquals(Blocks.DIRT.defaultBlockState(), top, "A dirt/ground way must not get a yellow-terracotta stripe");
        BlockState under = RoadSurfaceRendering.underSurfaceFor(RoadSegment.CrossSection.CENTERLINE, RoadSurfaceMaterial.DIRT_GROUND);
        assertEquals(Blocks.DIRT.defaultBlockState(), under);
    }

    /** "Keep existing ... clearing behavior": the gravel shoulder stays gravel regardless of the road's own mapped material. */
    @Test
    void theShoulderStaysGravelRegardlessOfTheRoadsOwnMaterial() {
        for (RoadSurfaceMaterial material : RoadSurfaceMaterial.values()) {
            BlockState top = RoadSurfaceRendering.topSurfaceFor(RoadSegment.CrossSection.SHOULDER, LayerMath.MAX_LAYERS, material);
            assertEquals(Blocks.GRAVEL.defaultBlockState(), top, "Shoulder must stay gravel for material " + material);
            BlockState under = RoadSurfaceRendering.underSurfaceFor(RoadSegment.CrossSection.SHOULDER, material);
            assertEquals(Blocks.GRAVEL.defaultBlockState(), under);
        }
    }

    /**
     * In-place geometry (ticket's own wording): a way's mapped surface changes only which material
     * paints, never its width, stripe eligibility or shoulder position -- the SAME {@link RoadSegment}
     * geometry (crossSectionAt/distanceBlocks/pavedHalfWidthBlocks) regardless of {@code surface}.
     */
    @Test
    void mappedSurfaceNeverChangesARoadsGeometryOnlyItsMaterial() {
        GeoPoint a = new GeoPoint(37.72, -119.64), b = new GeoPoint(37.721, -119.641);
        long[] nodeIds = new long[0];
        RoadSegment paved = new RoadSegment(1L, "road", RoadClass.SECONDARY, new GeoPoint[] {a, b}, nodeIds, 6.0, false, false, 0, null);
        RoadSegment gravel = new RoadSegment(1L, "road", RoadClass.SECONDARY, new GeoPoint[] {a, b}, nodeIds, 6.0, false, false, 0, "gravel");
        assertEquals(paved.pavedHalfWidthBlocks(), gravel.pavedHalfWidthBlocks());
        assertEquals(paved.totalHalfWidthBlocks(), gravel.totalHalfWidthBlocks());
        var grid = new io.earthplatform.geography.globe.MercatorGrid(1.0 / Math.cos(Math.toRadians(37.72)));
        int ax = (int) grid.blockX(a), az = (int) grid.blockZ(a);
        for (int dz = -10; dz <= 10; dz++) {
            assertEquals(paved.crossSectionAt(grid, ax, az + dz), gravel.crossSectionAt(grid, ax, az + dz),
                    "Geometry (which zone a column falls into) must not depend on the mapped surface, at dz=" + dz);
        }
    }

    @Test
    void layeredDirtAndSandNeverHaveFallingBlockPhysics() {
        assertTrue(!net.minecraft.world.level.block.FallingBlock.class.isAssignableFrom(LayeredBlock.class),
                "LayeredBlock (and therefore LAYERED_DIRT/LAYERED_SAND) must never have falling-block physics");
    }
}
