package io.github.brooswitminecraft.earthplatformroads.road;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a response from the official OpenStreetMap API's bounded map export
 * ({@code GET api.openstreetmap.org/api/0.6/map.json?bbox=...}) into the same {@code "ways"} JSON
 * array shape {@code RoadNetwork.parseWay}/{@code RoadSegment} already expect (a {@code "geometry"}
 * array of {@code {lat,lon}} points per way, plus its {@code tags} and real OSM {@code node_ids}).
 *
 * <p>Why this exists: the live public Overpass endpoint was unreachable three times in one session
 * (confirmed as genuine server-side overload, not a network-level block -- see the MINECRAFT-566
 * ticket comments), while this plain bounded GET against the official API succeeded immediately.
 * Unlike Overpass's {@code out geom}, the official API returns raw, separate {@code "node"} and
 * {@code "way"} elements -- a way only lists its nodes' IDs, not their coordinates -- so this class
 * resolves each way's node ID list against the response's own nodes to build its centerline,
 * exactly like the one-off conversion this mod's test fixture used, now as the real fetch path.
 */
final class OsmApiMapParser {
    private OsmApiMapParser() {}

    /** The real OSM node IDs and lat/lon this response carries for each way, already resolved -- ready for {@code RoadNetwork.parseWay}. */
    static List<JsonObject> resolveWays(String osmApiMapJsonResponse) {
        JsonObject root = JsonParser.parseString(osmApiMapJsonResponse).getAsJsonObject();
        JsonArray elements = root.getAsJsonArray("elements");
        Map<Long, JsonObject> nodesById = new HashMap<>();
        List<JsonObject> rawWays = new ArrayList<>();
        for (var element : elements) {
            JsonObject obj = element.getAsJsonObject();
            String type = obj.get("type").getAsString();
            if ("node".equals(type)) {
                nodesById.put(obj.get("id").getAsLong(), obj);
            } else if ("way".equals(type)) {
                rawWays.add(obj);
            }
        }

        List<JsonObject> resolved = new ArrayList<>();
        for (JsonObject way : rawWays) {
            if (!way.has("tags") || !way.getAsJsonObject("tags").has("highway")) continue; // not a road at all
            JsonArray nodeIdsJson = way.getAsJsonArray("nodes");
            GeoNode[] points = new GeoNode[nodeIdsJson.size()];
            boolean complete = true;
            for (int i = 0; i < nodeIdsJson.size(); i++) {
                long nodeId = nodeIdsJson.get(i).getAsLong();
                JsonObject node = nodesById.get(nodeId);
                if (node == null) {
                    complete = false; // a way referencing a node outside this bbox export: skip rather than guess its position
                    break;
                }
                points[i] = new GeoNode(nodeId, node.get("lat").getAsDouble(), node.get("lon").getAsDouble());
            }
            if (!complete || points.length < 2) continue;

            JsonObject way2 = new JsonObject();
            way2.addProperty("type", "way");
            way2.addProperty("id", way.get("id").getAsLong());
            way2.add("tags", way.getAsJsonObject("tags"));
            JsonArray nodeIdsOut = new JsonArray();
            JsonArray geometry = new JsonArray();
            for (GeoNode point : points) {
                nodeIdsOut.add(point.id());
                JsonObject p = new JsonObject();
                p.addProperty("lat", point.lat());
                p.addProperty("lon", point.lon());
                geometry.add(p);
            }
            way2.add("nodes", nodeIdsOut);
            way2.add("geometry", geometry);
            resolved.add(way2);
        }
        return resolved;
    }

    private record GeoNode(long id, double lat, double lon) {}
}
