package io.github.brooswitminecraft.earthplatformroads.road;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.earthplatform.geography.GeoPoint;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a live Overpass {@code [out:json]`...`out geom;} response into {@link RoadSegment}s,
 * reusing {@link RoadClass#fromOsmHighway} so a live fetch rejects foot-only ways exactly like a
 * pinned, bundled extract does. {@code out geom} includes each way's real node IDs ({@code nodes})
 * and its resolved centerline ({@code geometry}) directly, so no separate node lookup is needed.
 */
final class OverpassWayParser {
    private OverpassWayParser() {}

    static List<RoadSegment> parseWays(String overpassJsonResponse) {
        JsonObject root = JsonParser.parseString(overpassJsonResponse).getAsJsonObject();
        List<RoadSegment> ways = new ArrayList<>();
        for (var element : root.getAsJsonArray("elements")) {
            JsonObject obj = element.getAsJsonObject();
            if (!"way".equals(obj.get("type").getAsString())) continue;
            if (!obj.has("tags") || !obj.has("geometry")) continue; // an incomplete way (missing geometry) is not usable
            JsonObject tags = obj.getAsJsonObject("tags");
            if (!tags.has("highway")) continue;
            String highway = tags.get("highway").getAsString();
            RoadClass roadClass = RoadClass.fromOsmHighway(highway);
            if (roadClass == null) continue; // a foot-only or otherwise non-vehicular way: not ingested, same as a bundled extract

            JsonArray geometry = obj.getAsJsonArray("geometry");
            GeoPoint[] centerline = new GeoPoint[geometry.size()];
            for (int i = 0; i < geometry.size(); i++) {
                JsonObject point = geometry.get(i).getAsJsonObject();
                centerline[i] = new GeoPoint(point.get("lat").getAsDouble(), point.get("lon").getAsDouble());
            }
            if (centerline.length < 2) continue; // RoadSegment requires at least two points

            long[] nodeIds = new long[0];
            if (obj.has("nodes")) {
                JsonArray nodesJson = obj.getAsJsonArray("nodes");
                nodeIds = new long[nodesJson.size()];
                for (int i = 0; i < nodesJson.size(); i++) nodeIds[i] = nodesJson.get(i).getAsLong();
                if (nodeIds.length != centerline.length) nodeIds = new long[0]; // mismatched: opt out of junction detection rather than misalign
            }

            int lanes = tags.has("lanes") ? parseLanes(tags.get("lanes").getAsString()) : 0;
            boolean bridge = tags.has("bridge") && !"no".equals(tags.get("bridge").getAsString());
            boolean tunnel = tags.has("tunnel") && !"no".equals(tags.get("tunnel").getAsString());
            int layer = tags.has("layer") ? parseLayer(tags.get("layer").getAsString()) : 0;

            ways.add(new RoadSegment(obj.get("id").getAsLong(), name(tags, obj), roadClass, centerline, nodeIds,
                    roadClass.pavedHalfWidthBlocks(lanes), bridge, tunnel, layer));
        }
        return ways;
    }

    private static String name(JsonObject tags, JsonObject way) {
        return tags.has("name") ? tags.get("name").getAsString() : "way " + way.get("id").getAsLong();
    }

    private static int parseLanes(String lanes) {
        try {
            return Integer.parseInt(lanes.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parseLayer(String layer) {
        try {
            return Integer.parseInt(layer.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
