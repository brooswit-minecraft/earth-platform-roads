package io.github.brooswitminecraft.earthplatformroads.road;

import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import io.github.brooswitminecraft.earthplatformroads.compat.layeredslopes.LayeredSlopesCompat;
import io.github.brooswitminecraft.layeredslopes.Anchor;
import io.github.brooswitminecraft.layeredslopes.LayeredBlock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Opt-in, ordinary-generation preview for bounded dry mapped ways, with one profile across internal vertices. */
public final class LevelRoadPreview {
    private static final boolean ENABLED = Boolean.getBoolean("earthplatformroads.levelRoadPreview");
    private LevelRoadPreview() {}

    // Bounds work in the default-off preview. Oversized/unsafe ways stay native as a whole.
    private static final int MAX_STATIONS = 256;
    private static final int MAX_NODES = 64;
    private static final int MAX_CACHED_WAYS = 64;
    private static final int MAX_GRADE_Q = 8; // half a block per world-block station
    private static final Map<EarthGenerator, ConcurrentHashMap<Way, Plan>> BY_GENERATOR = new WeakHashMap<>();

    private record Point(double x, double z) {}
    private record Way(long id, double width, List<Point> points) {}
    private record Leg(Point a, double ux, double uz, double length, double offset) {
        Point at(double t) { return new Point(a.x() + t * ux, a.z() + t * uz); }
    }
    private record Plan(List<Leg> legs, int[] tops, int minGround, int maxCap) {
        RoadStationProfile.Cap cap(int x, int z) {
            return new RoadStationProfile.Cap(tops[station(legs, tops.length, x, z)]);
        }
    }
    private static final Plan SKIP = new Plan(List.of(), new int[0], 0, -1);

    private static ConcurrentHashMap<Way, Plan> plans(EarthGenerator earth) {
        synchronized (BY_GENERATOR) {
            return BY_GENERATOR.computeIfAbsent(earth, ignored -> new ConcurrentHashMap<>());
        }
    }

    private static Way geometry(EarthGenerator earth, RoadSegment road) {
        var grid = earth.grid();
        var points = new ArrayList<Point>(road.centerline().length);
        for (var p : road.centerline()) points.add(new Point(grid.blockX(p), grid.blockZ(p)));
        int direction = 0;
        for (int a = 0, b = points.size() - 1; a < b && direction == 0; a++, b--) {
            direction = Double.compare(points.get(a).x(), points.get(b).x());
            if (direction == 0) direction = Double.compare(points.get(a).z(), points.get(b).z());
        }
        if (direction > 0) java.util.Collections.reverse(points);
        return new Way(road.wayId(), road.pavedHalfWidthBlocks(), List.copyOf(points));
    }

    /** Called only for storage misses, after the original generation/clearance/other mixins. */
    public static boolean apply(EarthGenerator earth, int cx, int cy, int cz, LevelChunkSection section) {
        // Existing readiness/provider configuration is unchanged. Lookups below only read cached ways.
        if (!ENABLED) return false;
        var grid = earth.grid();
        int x0 = cx << 4, y0 = cy << 4, z0 = cz << 4;
        Map<Way, Plan> sectionPlans = new HashMap<>();
        Map<RoadSegment, Way> sectionGeometry = new IdentityHashMap<>();
        var accepted = plans(earth);
        boolean changed = false;
        // MINECRAFT-586: one scoped cache for this whole 256-column section loop -- every column in
        // a 16x16 section ordinarily falls in the exact same RoadNetwork.CELL_DEGREES cell, so without
        // this the loop would otherwise repeat the identical disk-cache reads up to 256 times. See
        // RoadNetwork.ScopedLookupCache's own doc for why reusing it only within this one call (never
        // across sections) carries no staleness risk.
        RoadNetwork.ScopedLookupCache cache = RoadNetwork.freshScopedLookupCache();
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            int x = x0 + lx, z = z0 + lz;
            RoadSegment road = RoadNetwork.segmentCoveringColumn(grid, x, z, cache);
            if (road == null || road.wayId() <= 0 || road.bridge() || road.tunnel()
                    || road.pavedHalfWidthBlocks() > 8.0 || road.centerline().length > MAX_NODES) continue;
            var zone = road.crossSectionAt(grid, x, z);
            // Native shoulders stay at ground, with no added raised shoulder material or supports.
            if (zone != RoadSegment.CrossSection.PAVED && zone != RoadSegment.CrossSection.CENTERLINE) continue;
            Way way = sectionGeometry.computeIfAbsent(road, segment -> geometry(earth, segment));
            Plan plan = sectionPlans.computeIfAbsent(way, key -> {
                Plan ready = accepted.get(key);
                if (ready != null) return ready;
                // No shared lock during sampling. Only a complete immutable solution is published.
                Plan made = planWay(earth, road, key);
                if (made == SKIP || accepted.size() >= MAX_CACHED_WAYS) return made;
                Plan winner = accepted.putIfAbsent(key, made);
                return winner == null ? made : winner;
            });
            if (plan == SKIP || y0 + 15 < plan.minGround() || y0 > plan.maxCap()) continue;
            int natural = earth.surfaceY(x, z);
            if (!dry(earth, x, z, natural)) continue;
            var top = plan.cap(x, z);
            if (top.topSixteenths() < 16L * (natural + 1L)) continue;
            int capY = top.blockY();
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
                BlockState state = y + y0 == capY ? cap(zone, top.layers())
                        : Blocks.STONE_BRICKS.defaultBlockState();
                changed |= put(section, lx, y, lz, state);
            }
        }
        if (changed) section.recalcBlockCounts();
        return changed;
    }

    private static int station(List<Leg> legs, int count, int x, int z) {
        double bestDistance = Double.POSITIVE_INFINITY, bestAlong = 0;
        for (Leg leg : legs) {
            double along = Math.clamp((x + .5 - leg.a().x()) * leg.ux() + (z + .5 - leg.a().z()) * leg.uz(),
                    0, leg.length());
            Point projected = leg.at(along);
            double dx = x + .5 - projected.x(), dz = z + .5 - projected.z();
            double distance = dx * dx + dz * dz;
            double absolute = leg.offset() + along;
            if (distance < bestDistance || (distance == bestDistance && absolute < bestAlong)) {
                bestDistance = distance;
                bestAlong = absolute;
            }
        }
        return Math.min(count - 1, (int) Math.floor(bestAlong));
    }

    private static Plan planWay(EarthGenerator earth, RoadSegment road, Way way) {
        var legs = new ArrayList<Leg>();
        double length = 0;
        for (int i = 1; i < way.points().size(); i++) {
            Point a = way.points().get(i - 1), b = way.points().get(i);
            double dx = b.x() - a.x(), dz = b.z() - a.z(), n = Math.hypot(dx, dz);
            if (n == 0) continue;
            legs.add(new Leg(a, dx / n, dz / n, n, length));
            length += n;
            if (!Double.isFinite(length) || length > MAX_STATIONS) return SKIP;
        }
        if (length < 2) return SKIP;
        int n = (int) Math.ceil(length);
        int[] maximum = new int[n];
        Arrays.fill(maximum, Integer.MIN_VALUE);
        Set<Long> seen = new HashSet<>();
        Map<Long, Integer> stationsByColumn = new HashMap<>();
        var grid = earth.grid();
        double reach = way.width() + 1e-6;
        boolean hasCrossSlope = false;
        int minGround = Integer.MAX_VALUE;
        // Rasterize bounded subsegments, then refine by the real road footprint. No bend holes.
        for (Leg leg : legs) for (int t = 0; t < leg.length(); t += 8) {
            Point a = leg.at(t), b = leg.at(Math.min(leg.length(), t + 8));
            for (int x = (int) Math.ceil(Math.min(a.x(), b.x()) - reach - .5);
                    x <= Math.floor(Math.max(a.x(), b.x()) + reach - .5); x++) {
                for (int z = (int) Math.ceil(Math.min(a.z(), b.z()) - reach - .5);
                        z <= Math.floor(Math.max(a.z(), b.z()) + reach - .5); z++) {
                    long cell = ((long) x << 32) | (z & 0xffffffffL);
                    if (!seen.add(cell)) continue;
                    var zone = road.crossSectionAt(grid, x, z);
                    if (zone != RoadSegment.CrossSection.PAVED && zone != RoadSegment.CrossSection.CENTERLINE) continue;
                    RoadSegment winner = RoadNetwork.segmentCoveringColumn(grid, x, z);
                    if (winner == null || winner.wayId() != way.id()) return SKIP;
                    int y = earth.surfaceY(x, z);
                    if (!dry(earth, x, z, y)) return SKIP;
                    minGround = Math.min(minGround, y);
                    int at = station(legs, n, x, z);
                    if (maximum[at] != Integer.MIN_VALUE && maximum[at] != y) hasCrossSlope = true;
                    maximum[at] = Math.max(maximum[at], y);
                    stationsByColumn.put(cell, at);
                }
            }
        }
        if (!hasCrossSlope) return SKIP;
        // A station with no raster cells is not an ineligible road gap. It imposes no floor.
        int[] floor = new int[n];
        for (int i = 0; i < n; i++) floor[i] = maximum[i] == Integer.MIN_VALUE ? -16384 * 16 + 1 : 16 * (maximum[i] + 1);
        // Inside a bend, nearby pixels may project to distant chainage stations. Bound the
        // actual 8-connected paved surface too; otherwise a corner can still have tall steps.
        List<Set<Integer>> neighbors = new ArrayList<>(n);
        for (int i = 0; i < n; i++) neighbors.add(new HashSet<>());
        for (var entry : stationsByColumn.entrySet()) {
            int x = (int) (entry.getKey() >> 32), z = (int) (long) entry.getKey();
            int here = entry.getValue();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                long cell = ((long) (x + dx) << 32) | ((z + dz) & 0xffffffffL);
                Integer other = stationsByColumn.get(cell);
                if (other != null && other != here) neighbors.get(here).add(other);
            }
        }
        int[] top = RoadStationProfile.noCutGrade(floor, MAX_GRADE_Q, neighbors);
        int maxCap = new RoadStationProfile.Cap(Arrays.stream(top).max().orElseThrow()).blockY();
        if (maxCap > 16383) return SKIP;
        return new Plan(List.copyOf(legs), top, minGround, maxCap);
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
