package io.github.brooswitminecraft.earthplatformroads.road;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.MercatorGrid;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The prototype's whole road network: one pinned, bounded OpenStreetMap extract (see class javadoc
 * on {@link RoadSegment}), loaded once from a resource baked into the mod jar. Nothing here makes a
 * network request; the extract was fetched and pinned ahead of time (see
 * {@code data/earthplatformroads/roads/southside_drive_yosemite.json} and
 * {@code docs/road-data-provenance.md}).
 */
public final class RoadNetwork {
    private static final String RESOURCE = "/data/earthplatformroads/roads/southside_drive_yosemite.json";
    /** Southside Drive carries two lanes of traffic; six blocks covers both lanes with a shoulder either side. */
    private static final double HALF_WIDTH_BLOCKS = 3.0;

    private static volatile List<RoadSegment> loaded;

    private RoadNetwork() {}

    public static List<RoadSegment> segments() {
        List<RoadSegment> cached = loaded;
        if (cached != null) return cached;
        synchronized (RoadNetwork.class) {
            if (loaded == null) loaded = List.of(loadPinnedSegment());
            return loaded;
        }
    }

    public static boolean coversColumn(MercatorGrid grid, int x, int z) {
        for (RoadSegment segment : segments()) if (segment.coversColumn(grid, x, z)) return true;
        return false;
    }

    private static RoadSegment loadPinnedSegment() {
        try (InputStream in = RoadNetwork.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing bundled resource " + RESOURCE);
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            String name = root.get("way_tags").getAsJsonObject().get("name").getAsString();
            JsonArray centerlineJson = root.getAsJsonArray("centerline");
            GeoPoint[] centerline = new GeoPoint[centerlineJson.size()];
            for (int i = 0; i < centerlineJson.size(); i++) {
                JsonElement point = centerlineJson.get(i);
                JsonObject obj = point.getAsJsonObject();
                centerline[i] = new GeoPoint(obj.get("lat").getAsDouble(), obj.get("lon").getAsDouble());
            }
            return new RoadSegment(name, centerline, HALF_WIDTH_BLOCKS);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read bundled resource " + RESOURCE, e);
        }
    }
}
