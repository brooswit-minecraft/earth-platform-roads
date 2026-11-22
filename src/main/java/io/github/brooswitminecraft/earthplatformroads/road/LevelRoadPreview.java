package io.github.brooswitminecraft.earthplatformroads.road;

import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import io.github.brooswitminecraft.earthplatformroads.compat.layeredslopes.LayeredSlopesCompat;
import io.github.brooswitminecraft.layeredslopes.Anchor;
import io.github.brooswitminecraft.layeredslopes.LayeredBlock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Opt-in, ordinary-generation preview for dry interiors of locally straight mapped road legs. */
public final class LevelRoadPreview {
    private static final boolean ENABLED = Boolean.getBoolean("earthplatformroads.levelRoadPreview");
    private LevelRoadPreview() {}

    private record Leg(double ax, double az, double bx, double bz, double ux, double uz, double length) {
        static Leg between(double ax, double az, double bx, double bz) {
            // Canonical order, so reversing a two-way geometry preserves station IDs.
            if (ax > bx || (ax == bx && az > bz)) return between(bx, bz, ax, az);
            double length = Math.hypot(bx - ax, bz - az);
            return length == 0 ? null : new Leg(ax, az, bx, bz, (bx - ax) / length, (bz - az) / length, length);
        }

        double along(double x, double z) { return (x - ax) * ux + (z - az) * uz; }
    }

    private record AtStation(Leg leg, int number) {}
    private record StationKey(long way, AtStation at) {}
    private record Station(RoadStationProfile.Cap cap, boolean eligible) {
        static final Station SKIP = new Station(null, false);
    }

    /** Called only for storage misses, after the original generation/clearance/other mixins. */
    public static boolean apply(EarthGenerator earth, int cx, int cy, int cz, LevelChunkSection section) {
        // Existing readiness/provider configuration is unchanged. Lookups below only read cached ways.
        if (!ENABLED) return false;
        var grid = earth.grid();
        int x0 = cx << 4, y0 = cy << 4, z0 = cz << 4;
        Map<StationKey, Station> stations = new HashMap<>();
        boolean changed = false;
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            int x = x0 + lx, z = z0 + lz;
            RoadSegment road = RoadNetwork.segmentCoveringColumn(grid, x, z);
            if (road == null || road.wayId() <= 0 || road.bridge() || road.tunnel()
                    || road.pavedHalfWidthBlocks() > 16.0) continue;
            var zone = road.crossSectionAt(grid, x, z);
            // Native shoulders stay at ground, with no added raised shoulder material or supports.
            if (zone != RoadSegment.CrossSection.PAVED && zone != RoadSegment.CrossSection.CENTERLINE) continue;
            var at = locate(earth, road, x, z);
            if (at == null) continue;
            int natural = earth.surfaceY(x, z);
            if (!dry(earth, x, z, natural)) continue;
            Station station = stations.computeIfAbsent(new StationKey(road.wayId(), at), key -> station(earth, road, at));
            if (!station.eligible() || station.cap().topSixteenths() < 16L * (natural + 1L)) continue;
            int capY = station.cap().blockY();
            int lo = Math.max(0, natural - y0);
            int hi = Math.min(15, capY - y0);
            if (lo > hi) continue;
            // Never overwrite an obstruction or a block entity. Native Roads already clears
            // its core canopy; this pass treats anything still present above ground as blocked.
            boolean safe = true;
            for (int y = lo; y <= hi; y++) {
                BlockState current = section.getBlockState(lx, y, lz);
                if (y + y0 == natural ? !roadSurface(current) : !current.isAir()) {
                    safe = false;
                    break;
                }
            }
            if (!safe) continue;
            for (int y = lo; y <= hi; y++) {
                BlockState state = y + y0 == capY ? cap(zone, station.cap().layers())
                        : Blocks.STONE_BRICKS.defaultBlockState();
                changed |= put(section, lx, y, lz, state);
            }
        }
        if (changed) section.recalcBlockCounts();
        return changed;
    }

    private static AtStation locate(EarthGenerator earth, RoadSegment road, int x, int z) {
        var grid = earth.grid();
        var points = road.centerline();
        Leg best = null;
        double distance = Double.POSITIVE_INFINITY, bestAlong = 0;
        for (int i = 1; i < points.length; i++) {
            var a = points[i - 1];
            var b = points[i];
            Leg leg = Leg.between(grid.blockX(a), grid.blockZ(a), grid.blockX(b), grid.blockZ(b));
            if (leg == null) continue;
            double along = Math.clamp(leg.along(x + .5, z + .5), 0, leg.length());
            double dx = x + .5 - (leg.ax() + leg.ux() * along);
            double dz = z + .5 - (leg.az() + leg.uz() * along);
            double d2 = dx * dx + dz * dz;
            if (d2 < distance) {
                distance = d2;
                best = leg;
                bestAlong = along;
            } else if (d2 == distance && !leg.equals(best)) {
                // Junctions/corners are not part of this preview.
                best = null;
            }
        }
        if (best == null) return null;
        int n = (int) Math.floor(bestAlong);
        return n < 2 || n + 2 >= best.length() ? null : new AtStation(best, n);
    }

    private static Station station(EarthGenerator earth, RoadSegment road, AtStation at) {
        int middle = naturalMaximum(earth, road, at);
        if (middle == Integer.MIN_VALUE) return Station.SKIP;
        int before = naturalMaximum(earth, road, new AtStation(at.leg(), at.number() - 1));
        int after = naturalMaximum(earth, road, new AtStation(at.leg(), at.number() + 1));
        if (before == Integer.MIN_VALUE || after == Integer.MIN_VALUE) return Station.SKIP;
        double top = Math.max(middle + 1.0, (before + 2.0 * middle + after) * .25 + 1.0);
        var cap = RoadStationProfile.capAtPhysicalTop(top);
        return cap.blockY() > 16383 ? Station.SKIP : new Station(cap, true);
    }

    private static int naturalMaximum(EarthGenerator earth, RoadSegment road, AtStation at) {
        int maximum = Integer.MIN_VALUE;
        var leg = at.leg();
        double width = road.pavedHalfWidthBlocks();
        double cx = leg.ax() + (at.number() + .5) * leg.ux();
        double cz = leg.az() + (at.number() + .5) * leg.uz();
        double xReach = .5 * Math.abs(leg.ux()) + width * Math.abs(leg.uz());
        double zReach = .5 * Math.abs(leg.uz()) + width * Math.abs(leg.ux());
        var grid = earth.grid();
        for (int x = (int) Math.ceil(cx - xReach - .5); x <= Math.floor(cx + xReach - .5); x++) {
            for (int z = (int) Math.ceil(cz - zReach - .5); z <= Math.floor(cz + zReach - .5); z++) {
                double across = -(x + .5 - cx) * leg.uz() + (z + .5 - cz) * leg.ux();
                if (Math.abs(across) > width || Math.floor(leg.along(x + .5, z + .5)) != at.number()) continue;
                if (!at.equals(locate(earth, road, x, z))) continue;
                RoadSegment winner = RoadNetwork.segmentCoveringColumn(grid, x, z);
                if (!road.isSameRoadAs(winner)) return Integer.MIN_VALUE;
                var zone = road.crossSectionAt(grid, x, z);
                if (zone != RoadSegment.CrossSection.PAVED && zone != RoadSegment.CrossSection.CENTERLINE) continue;
                int y = earth.surfaceY(x, z);
                if (!dry(earth, x, z, y)) return Integer.MIN_VALUE;
                maximum = Math.max(maximum, y);
            }
        }
        return maximum;
    }

    private static boolean dry(EarthGenerator earth, int x, int z, int ground) {
        int water = earth.waterY(x, z);
        return water == SurfaceGenerator.DRY || water < ground;
    }

    private static boolean roadSurface(BlockState s) {
        return s.is(Blocks.POLISHED_BLACKSTONE) || s.is(Blocks.YELLOW_TERRACOTTA) || s.is(Blocks.GRAVEL)
                || s.is(LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE.get())
                || s.is(LayeredSlopesCompat.LAYERED_YELLOW_TERRACOTTA.get())
                || s.is(LayeredSlopesCompat.LAYERED_GRAVEL.get());
    }

    private static BlockState cap(RoadSegment.CrossSection zone, int layers) {
        var block = zone == RoadSegment.CrossSection.CENTERLINE
                ? LayeredSlopesCompat.LAYERED_YELLOW_TERRACOTTA.get()
                : LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE.get();
        return block.defaultBlockState().setValue(LayeredBlock.LAYERS, layers).setValue(LayeredBlock.ANCHOR, Anchor.FLOOR);
    }

    private static boolean put(LevelChunkSection section, int x, int y, int z, BlockState state) {
        if (section.getBlockState(x, y, z) == state) return false;
        section.setBlockState(x, y, z, state, false);
        return true;
    }
}
