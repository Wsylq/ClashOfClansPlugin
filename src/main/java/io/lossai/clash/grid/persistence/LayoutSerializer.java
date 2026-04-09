package io.lossai.clash.grid.persistence;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.lossai.clash.grid.model.GridCoord;
import io.lossai.clash.grid.model.PlacedBuilding;
import io.lossai.clash.grid.occupancy.FootprintRegistry;
import io.lossai.clash.grid.occupancy.OccupancyMap;
import io.lossai.clash.model.BuildingType;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Serialises and deserialises an {@link OccupancyMap} to/from a JSON file at
 * {@code <dataFolder>/layouts/<uuid>.json}.
 *
 * <p>JSON format:
 * <pre>{@code
 * {
 *   "buildings": [
 *     { "type": "TOWNHALL", "anchorX": 20, "anchorZ": 20, "level": 1 }
 *   ]
 * }
 * }</pre>
 * </p>
 */
public class LayoutSerializer {

    private final File layoutsDir;
    private final Logger logger;
    private final Gson gson = new Gson();

    /**
     * @param dataFolder the plugin's data folder (e.g. {@code plugins/clash})
     * @param logger     logger used for warnings on missing/malformed files
     */
    public LayoutSerializer(File dataFolder, Logger logger) {
        this.layoutsDir = new File(dataFolder, "layouts");
        this.logger = logger;
    }

    /**
     * Serialises the given {@link OccupancyMap} to
     * {@code <dataFolder>/layouts/<playerId>.json}.
     *
     * <p>Deduplicates by anchor before writing — since the map stores one entry per
     * tile, multiple tiles belonging to the same building are collapsed to a single
     * JSON object keyed by the building's anchor.</p>
     */
    public void save(UUID playerId, OccupancyMap map, FootprintRegistry registry) {
        // Deduplicate: collect unique PlacedBuilding instances by anchor coord.
        Map<GridCoord, PlacedBuilding> byAnchor = new LinkedHashMap<>();
        for (PlacedBuilding building : map.snapshot().values()) {
            byAnchor.put(building.anchor(), building);
        }

        JsonArray buildings = new JsonArray();
        for (PlacedBuilding b : byAnchor.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", b.type().name());
            obj.addProperty("anchorX", b.anchor().x());
            obj.addProperty("anchorZ", b.anchor().z());
            obj.addProperty("level", b.level());
            buildings.add(obj);
        }

        JsonObject root = new JsonObject();
        root.add("buildings", buildings);

        layoutsDir.mkdirs();
        File file = layoutFile(playerId);
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(root, writer);
        } catch (IOException e) {
            logger.warning("[LayoutSerializer] Failed to save layout for " + playerId + ": " + e.getMessage());
        }
    }

    /**
     * Deserialises the layout file for {@code playerId} and reconstructs an
     * {@link OccupancyMap} by re-expanding each building's footprint.
     *
     * <p>Returns an empty {@link OccupancyMap} and logs a WARNING if the file is
     * missing or the JSON is malformed.</p>
     */
    public OccupancyMap load(UUID playerId, FootprintRegistry registry) {
        File file = layoutFile(playerId);
        if (!file.exists()) {
            logger.warning("[LayoutSerializer] Layout file not found for " + playerId + ": " + file.getPath());
            return new OccupancyMap();
        }

        try (FileReader reader = new FileReader(file)) {
            JsonObject root = gson.fromJson(reader, JsonObject.class);
            if (root == null || !root.has("buildings")) {
                logger.warning("[LayoutSerializer] Malformed layout file for " + playerId + " (missing 'buildings' key)");
                return new OccupancyMap();
            }

            JsonArray buildings = root.getAsJsonArray("buildings");
            OccupancyMap map = new OccupancyMap();

            for (JsonElement element : buildings) {
                JsonObject obj = element.getAsJsonObject();
                String typeName = obj.get("type").getAsString();
                int anchorX = obj.get("anchorX").getAsInt();
                int anchorZ = obj.get("anchorZ").getAsInt();
                int level = obj.get("level").getAsInt();

                BuildingType type = BuildingType.valueOf(typeName);
                GridCoord anchor = new GridCoord(anchorX, anchorZ);
                PlacedBuilding building = new PlacedBuilding(type, anchor, level);

                if (map.canPlace(type, anchor)) {
                    map.place(building);
                } else {
                    logger.warning("[LayoutSerializer] Skipping overlapping/OOB building " + typeName
                            + " at (" + anchorX + "," + anchorZ + ") for " + playerId);
                }
            }

            return map;

        } catch (IOException | JsonParseException | IllegalArgumentException e) {
            logger.warning("[LayoutSerializer] Malformed layout file for " + playerId + ": " + e.getMessage());
            return new OccupancyMap();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private File layoutFile(UUID playerId) {
        return new File(layoutsDir, playerId.toString() + ".json");
    }
}
