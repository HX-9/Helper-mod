package com.helpermod.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.helpermod.HelperMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FarmAreaManager {
    private static final FarmAreaManager INSTANCE = new FarmAreaManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, FarmArea> areas = new LinkedHashMap<>();
    private BlockPos pos1;
    private BlockPos pos2;
    private String activeAreaName;
    private boolean sleepAtNight = true;
    private BlockPos bedPos = null;
    private String awaitingChestFarmName = null;
    private BlockPos fertilizerChestPos = null;
    private boolean awaitingFertilizerChest = false;

    private FarmAreaManager() {
        load();
    }

    public static FarmAreaManager getInstance() {
        return INSTANCE;
    }

    public BlockPos getPos1() {
        return pos1;
    }

    public void setPos1(BlockPos pos1) {
        this.pos1 = pos1 != null ? pos1.immutable() : null;
    }

    public BlockPos getPos2() {
        return pos2;
    }

    public void setPos2(BlockPos pos2) {
        this.pos2 = pos2 != null ? pos2.immutable() : null;
    }

    public boolean isSleepAtNight() {
        return sleepAtNight;
    }

    public void setSleepAtNight(boolean sleepAtNight) {
        this.sleepAtNight = sleepAtNight;
        save();
    }

    public BlockPos getBedPos() {
        return bedPos;
    }

    public void setBedPos(BlockPos bedPos) {
        this.bedPos = bedPos != null ? bedPos.immutable() : null;
        save();
    }

    public String getAwaitingChestFarm() {
        return awaitingChestFarmName;
    }

    public void setAwaitingChestFarm(String farmName) {
        this.awaitingChestFarmName = farmName != null ? farmName.toLowerCase() : null;
    }

    public BlockPos getFertilizerChestPos() {
        return fertilizerChestPos;
    }

    public void setFertilizerChestPos(BlockPos fertilizerChestPos) {
        this.fertilizerChestPos = fertilizerChestPos != null ? fertilizerChestPos.immutable() : null;
        save();
    }

    public boolean isAwaitingFertilizerChest() {
        return awaitingFertilizerChest;
    }

    public void setAwaitingFertilizerChest(boolean awaitingFertilizerChest) {
        this.awaitingFertilizerChest = awaitingFertilizerChest;
    }

    public boolean addArea(String name, BlockPos p1, BlockPos p2, FarmCrop crop) {
        if (name == null || name.isBlank() || p1 == null || p2 == null) {
            return false;
        }
        FarmArea area = new FarmArea(name.toLowerCase(), p1, p2, crop);
        areas.put(name.toLowerCase(), area);
        save();
        return true;
    }

    public boolean removeArea(String name) {
        if (name == null) return false;
        String key = name.toLowerCase();
        if (key.equals(activeAreaName)) {
            activeAreaName = null;
        }
        boolean removed = areas.remove(key) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    public boolean renameArea(String oldName, String newName) {
        if (oldName == null || newName == null || newName.isBlank()) {
            return false;
        }
        String oldKey = oldName.toLowerCase();
        String newKey = newName.toLowerCase();
        FarmArea area = areas.get(oldKey);
        if (area == null) {
            return false;
        }
        if (!oldKey.equals(newKey)) {
            if (areas.containsKey(newKey)) {
                return false; // الاسم الجديد مستخدم بالفعل
            }
            areas.remove(oldKey);
            area.setName(newKey);
            areas.put(newKey, area);

            if (oldKey.equals(activeAreaName)) {
                activeAreaName = newKey;
            }
        }
        save();
        return true;
    }

    public FarmArea getArea(String name) {
        if (name == null) return null;
        return areas.get(name.toLowerCase());
    }

    public Collection<FarmArea> getAllAreas() {
        return Collections.unmodifiableCollection(areas.values());
    }

    public String getActiveAreaName() {
        return activeAreaName;
    }

    public void setActiveAreaName(String activeAreaName) {
        this.activeAreaName = activeAreaName != null ? activeAreaName.toLowerCase() : null;
        save();
    }

    public List<FarmArea> getActiveAreas() {
        if (activeAreaName != null) {
            FarmArea single = areas.get(activeAreaName);
            if (single != null) {
                return List.of(single);
            }
        }
        return new ArrayList<>(areas.values());
    }

    public FarmArea findAreaContaining(BlockPos pos) {
        for (FarmArea area : getActiveAreas()) {
            if (area.contains(pos)) {
                return area;
            }
        }
        return null;
    }

    private Path getConfigFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("helpermod_areas.json");
    }

    public synchronized void save() {
        try {
            Path file = getConfigFile();
            JsonObject root = new JsonObject();
            if (activeAreaName != null) {
                root.addProperty("activeArea", activeAreaName);
            }
            root.addProperty("sleepAtNight", sleepAtNight);
            if (bedPos != null) {
                JsonObject b = new JsonObject();
                b.addProperty("x", bedPos.getX());
                b.addProperty("y", bedPos.getY());
                b.addProperty("z", bedPos.getZ());
                root.add("bedPos", b);
            }
            if (fertilizerChestPos != null) {
                JsonObject fc = new JsonObject();
                fc.addProperty("x", fertilizerChestPos.getX());
                fc.addProperty("y", fertilizerChestPos.getY());
                fc.addProperty("z", fertilizerChestPos.getZ());
                root.add("fertilizerChestPos", fc);
            }

            JsonArray array = new JsonArray();
            for (FarmArea area : areas.values()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("name", area.getName());

                BlockPos min = area.getMinPos();
                obj.addProperty("minX", min.getX());
                obj.addProperty("minY", min.getY());
                obj.addProperty("minZ", min.getZ());

                BlockPos max = area.getMaxPos();
                obj.addProperty("maxX", max.getX());
                obj.addProperty("maxY", max.getY());
                obj.addProperty("maxZ", max.getZ());

                if (area.getTargetCrop() != null) {
                    obj.addProperty("targetCrop", area.getTargetCrop().getId());
                }

                if (area.getChestPos() != null) {
                    JsonObject c = new JsonObject();
                    c.addProperty("x", area.getChestPos().getX());
                    c.addProperty("y", area.getChestPos().getY());
                    c.addProperty("z", area.getChestPos().getZ());
                    obj.add("chestPos", c);
                }

                array.add(obj);
            }
            root.add("areas", array);

            Files.createDirectories(file.getParent());
            Path tempFile = file.resolveSibling(file.getFileName().toString() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tempFile)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(tempFile, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException e) {
                Files.move(tempFile, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            HelperMod.LOGGER.error("Failed to save helpermod_areas.json", e);
        }
    }

    public synchronized void load() {
        Path file = getConfigFile();
        if (!Files.exists(file)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("activeArea")) {
                this.activeAreaName = root.get("activeArea").getAsString();
            }
            if (root.has("sleepAtNight")) {
                this.sleepAtNight = root.get("sleepAtNight").getAsBoolean();
            }
            if (root.has("bedPos") && root.get("bedPos").isJsonObject()) {
                JsonObject b = root.getAsJsonObject("bedPos");
                this.bedPos = new BlockPos(b.get("x").getAsInt(), b.get("y").getAsInt(), b.get("z").getAsInt());
            }
            if (root.has("fertilizerChestPos") && root.get("fertilizerChestPos").isJsonObject()) {
                JsonObject fc = root.getAsJsonObject("fertilizerChestPos");
                this.fertilizerChestPos = new BlockPos(fc.get("x").getAsInt(), fc.get("y").getAsInt(), fc.get("z").getAsInt());
            }

            if (root.has("areas") && root.get("areas").isJsonArray()) {
                areas.clear();
                for (JsonElement el : root.getAsJsonArray("areas")) {
                    JsonObject obj = el.getAsJsonObject();
                    String name = obj.get("name").getAsString();
                    int minX = obj.get("minX").getAsInt();
                    int minY = obj.get("minY").getAsInt();
                    int minZ = obj.get("minZ").getAsInt();
                    int maxX = obj.get("maxX").getAsInt();
                    int maxY = obj.get("maxY").getAsInt();
                    int maxZ = obj.get("maxZ").getAsInt();

                    FarmCrop crop = null;
                    if (obj.has("targetCrop") && !obj.get("targetCrop").isJsonNull()) {
                        crop = FarmCrop.fromId(obj.get("targetCrop").getAsString());
                    }

                    BlockPos min = new BlockPos(minX, minY, minZ);
                    BlockPos max = new BlockPos(maxX, maxY, maxZ);

                    FarmArea area = new FarmArea(name, min, max, crop, true);
                    if (obj.has("chestPos") && obj.get("chestPos").isJsonObject()) {
                        JsonObject c = obj.getAsJsonObject("chestPos");
                        area.setChestPos(new BlockPos(c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt()));
                    }
                    areas.put(name.toLowerCase(), area);
                }
            }
        } catch (Exception e) {
            HelperMod.LOGGER.error("Failed to load helpermod_areas.json", e);
        }
    }
}
