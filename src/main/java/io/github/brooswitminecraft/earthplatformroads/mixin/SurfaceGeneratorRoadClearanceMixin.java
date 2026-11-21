package io.github.brooswitminecraft.earthplatformroads.mixin;

import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import io.github.brooswitminecraft.earthplatformroads.road.RoadCanopyClearance;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * MINECRAFT-566 comment 55488 (John, redirected to here explicitly by comment 55503 once the
 * stale-retry work was shelved): a tree or structure can still leave a log/leaf/other block sitting
 * over an already-painted road column, since nothing about this mod's own
 * {@code EarthGeneratorRoadMixin} (which only ever changes what {@code surface()} reports for the
 * TOP few blocks at a column's natural height) stops ordinary vegetation/structure decoration from
 * placing things ABOVE that height afterward.
 *
 * <p>Hooks the actual final generation seam: {@code SurfaceGenerator.generate(int,int,int,List,List)}'s
 * own call to its private static {@code dropStrayBlockEntities(...)}, confirmed by reading the real
 * class (CFR-decompiled for understanding only -- nothing here is copied from that decompiled
 * source) to run in the exact order {@code terrain()} (includes vegetation decoration) -&gt;
 * {@code Vegetation.bees()} -&gt; {@code structures.place()} + {@code recalcBlockCounts()} -&gt;
 * {@code dropStrayBlockEntities()} -&gt; return. Every pass that could put a block over a road has
 * ALREADY run by the time this redirect fires, and redirecting the {@code dropStrayBlockEntities}
 * call (rather than injecting at {@code RETURN}, which runs strictly after it) means this mod's own
 * clearing happens BEFORE {@code dropStrayBlockEntities} gets to look at the section -- so its
 * existing filter (removing a block-entity tag whose block is no longer valid at that position)
 * correctly drops the tag for anything {@link RoadCanopyClearance} just cleared to air, instead of
 * leaving a dangling block-entity reference this mod would otherwise have to clean up itself.
 *
 * <p>A {@code @Redirect} handler for a call to a STATIC target method takes exactly that method's
 * own parameters (no implicit receiver) -- but the handler itself is declared non-static here, and
 * once woven becomes part of {@code SurfaceGenerator} itself, so it still sees the surrounding
 * {@code generate()} call's own {@code this} (the real generator instance) with no local-variable
 * capture needed for anything.
 */
@Mixin(SurfaceGenerator.class)
public abstract class SurfaceGeneratorRoadClearanceMixin {
    @Shadow
    private static void dropStrayBlockEntities(LevelChunkSection section, int cubeX, int cubeY, int cubeZ, List<CompoundTag> blockEntities) {
        throw new AssertionError("shadowed");
    }

    @Redirect(method = "generate(IIILjava/util/List;Ljava/util/List;)Lnet/minecraft/world/level/chunk/LevelChunkSection;",
            at = @At(value = "INVOKE",
                    target = "Lio/earthplatform/minecraft/cubic/gen/SurfaceGenerator;dropStrayBlockEntities(Lnet/minecraft/world/level/chunk/LevelChunkSection;IIILjava/util/List;)V"))
    private void earthplatformroads$clearRoadCanopyThenDropStray(
            LevelChunkSection section, int cubeX, int cubeY, int cubeZ, List<CompoundTag> blockEntities) {
        if ((Object) this instanceof EarthGenerator earthGenerator) {
            RoadCanopyClearance.clear(earthGenerator, cubeX, cubeY, cubeZ, section);
        }
        dropStrayBlockEntities(section, cubeX, cubeY, cubeZ, blockEntities);
    }
}
