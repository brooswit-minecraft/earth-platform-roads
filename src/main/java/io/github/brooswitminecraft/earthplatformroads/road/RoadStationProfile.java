package io.github.brooswitminecraft.earthplatformroads.road;

import java.util.List;

/** Pure road profile: one physical top per station, independent of lateral position and local ground. */
final class RoadStationProfile {
    private static final int LAYERS_PER_BLOCK = 16;

    /** Already resolved centerline control: distance along the road and physical top, not block Y. */
    record Knot(double station, double topY) {
        Knot {
            if (!Double.isFinite(station) || station < 0) {
                throw new IllegalArgumentException("station must be finite and nonnegative");
            }
            quantize(topY);
        }
    }

    record Cap(int topSixteenths) {
        /** Inclusive block Y containing the cap, including at exact integral tops and negative Y. */
        int blockY() {
            return (int) Math.floorDiv((long) topSixteenths - 1, LAYERS_PER_BLOCK);
        }

        int layers() {
            return Math.floorMod((long) topSixteenths - 1, LAYERS_PER_BLOCK) + 1;
        }
    }

    private final List<Knot> knots;

    RoadStationProfile(List<Knot> knots) {
        this.knots = List.copyOf(knots);
        if (this.knots.size() < 2) {
            throw new IllegalArgumentException("at least two knots required");
        }
        for (int i = 1; i < this.knots.size(); i++) {
            if (!(this.knots.get(i - 1).station() < this.knots.get(i).station())) {
                throw new IllegalArgumentException("stations must increase strictly");
            }
        }
    }

    /**
     * Interpolates the grade along the centerline, then quantizes once. Interpolation never
     * overshoots either control. Every lateral column at the same station must use this same cap.
     * Outside this profile's owned interval is a caller error, not an instruction to extend it.
     */
    Cap capAt(double station) {
        int lo = 0, hi = knots.size() - 1;
        if (!Double.isFinite(station) || station < knots.get(lo).station()
                || station > knots.get(hi).station()) {
            throw new IllegalArgumentException("station outside profile");
        }
        while (hi - lo > 1) {
            int mid = lo + (hi - lo) / 2;
            if (station < knots.get(mid).station()) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        Knot a = knots.get(lo), b = knots.get(hi);
        if (station == a.station()) return new Cap(quantize(a.topY()));
        if (station == b.station()) return new Cap(quantize(b.topY()));
        double t = (station - a.station()) / (b.station() - a.station());
        double y = Math.fma(t, b.topY() - a.topY(), a.topY());
        return new Cap(quantize(Math.clamp(y, Math.min(a.topY(), b.topY()), Math.max(a.topY(), b.topY()))));
    }

    static Cap capAtPhysicalTop(double topY) {
        return new Cap(quantize(topY));
    }

    /** Least no-cut envelope with adjacent station differences bounded in sixteenths. */
    static int[] noCutGrade(int[] floors, int maxStep) {
        if (maxStep <= 0) throw new IllegalArgumentException("positive grade limit required");
        int[] top = floors.clone();
        for (int i = 1; i < top.length; i++) top[i] = (int) Math.max(top[i], (long) top[i - 1] - maxStep);
        for (int i = top.length - 2; i >= 0; i--) top[i] = (int) Math.max(top[i], (long) top[i + 1] - maxStep);
        return top;
    }

    /** The same envelope, also bounding directly adjacent paved pixels at a bend. */
    static int[] noCutGrade(int[] floors, int maxStep, List<? extends java.util.Set<Integer>> neighbors) {
        if (neighbors.size() != floors.length) throw new IllegalArgumentException("station adjacency size");
        int[] top = noCutGrade(floors, maxStep);
        record Candidate(int station, int height) {}
        var pending = new java.util.PriorityQueue<Candidate>(
                java.util.Comparator.comparingInt(Candidate::height).reversed());
        for (int i = 0; i < top.length; i++) pending.add(new Candidate(i, top[i]));
        while (!pending.isEmpty()) {
            Candidate next = pending.remove();
            int i = next.station();
            if (next.height() != top[i]) continue;
            long minimum = (long) next.height() - maxStep;
            if (i > 0 && minimum > top[i - 1]) {
                top[i - 1] = (int) minimum;
                pending.add(new Candidate(i - 1, top[i - 1]));
            }
            if (i + 1 < top.length && minimum > top[i + 1]) {
                top[i + 1] = (int) minimum;
                pending.add(new Candidate(i + 1, top[i + 1]));
            }
            for (int other : neighbors.get(i)) if (minimum > top[other]) {
                top[other] = (int) minimum;
                pending.add(new Candidate(other, top[other]));
            }
        }
        return top;
    }

    private static int quantize(double y) {
        double scaled = y * LAYERS_PER_BLOCK;
        if (!Double.isFinite(scaled) || scaled < Integer.MIN_VALUE || scaled > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("non-finite or out-of-range deck height");
        }
        // Tie toward positive infinity, consistently even for negative stations' heights.
        return (int) Math.round(scaled);
    }
}
