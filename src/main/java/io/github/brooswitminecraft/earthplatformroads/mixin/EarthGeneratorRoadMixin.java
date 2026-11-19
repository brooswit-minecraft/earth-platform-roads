package io.github.brooswitminecraft.earthplatformroads.mixin;

import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import io.github.brooswitminecraft.earthplatformroads.road.RoadNetwork;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The prototype's whole seam into generation: {@link EarthGenerator} is {@code final} and ships no
 * extension point, so a column's cover is overridden after the fact at the one place it is decided
 * -- {@code surface(int,int,int,int,int)}, called once per column by both near-cube generation
 * ({@link SurfaceGenerator#terrain}) and far-LOD generation. Overriding it here, rather than cube
 * contents after the fact, means a road column gets no trees, grass or flowers in the first place
 * instead of having them grown and then removed, and automatically gets the same cave/ore/strata
 * treatment under it as any other column -- only the top few blocks and vegetation eligibility change.
 *
 * <p>Ground height, water and biome are left untouched: this is an at-grade overlay only, exactly
 * the prototype's scope (no cuttings, embankments, bridges or tunnels).
 */
@Mixin(EarthGenerator.class)
public abstract class EarthGeneratorRoadMixin {
    @Inject(
            method = "surface(IIIII)Lio/earthplatform/minecraft/cubic/gen/SurfaceGenerator$Surface;",
            at = @At("RETURN"),
            cancellable = true)
    private void earthplatformroads$overlayRoad(
            int x, int z, int y, int water, int slope, CallbackInfoReturnable<SurfaceGenerator.Surface> cir) {
        // A column already under water (a lake, a river, the sea) never becomes road surface: the
        // prototype paints an at-grade road, not a causeway or ford.
        if (water != SurfaceGenerator.DRY && water >= y) return;
        EarthGenerator self = (EarthGenerator) (Object) this;
        if (!RoadNetwork.coversColumn(self.grid(), x, z)) return;
        SurfaceGenerator.Surface original = cir.getReturnValue();
        cir.setReturnValue(new SurfaceGenerator.Surface(
                original.biome(),
                Blocks.POLISHED_BLACKSTONE.defaultBlockState(),
                Blocks.POLISHED_BLACKSTONE.defaultBlockState(),
                0, 0, 0, 0, 0, false));
    }
}
