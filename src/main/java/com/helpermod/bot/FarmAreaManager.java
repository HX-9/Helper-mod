package com.helpermod.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.helpermod.HelperMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

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
import java.util.Objects;

public class FarmAreaManager {
    private static final FarmAreaManager INSTANCE = new FarmAreaManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final List<FarmArea> areas = new ArrayList<>();
    private final Map<String, BlockPos> scopedBeds = new LinkedHashMap<>();
    private final Map<String, BlockPos> scopedFertilizerChests = new LinkedHashMap<>();
    private BlockPos legacyBedPos = null;
    private BlockPos legacyFertilizerChestPos = null;
    private String currentWorldKey = null;
    private String currentDimensionId = null;

    private BlockPos pos1;
    private BlockPos pos2;
    private boolean sleepAtNight = true;
    private String awaitingChestFarmName = null;
    private FarmArea.ChestRole awaitingChestRole = FarmArea.ChestRole.CROP;
    private boolean awaitingChestAppend = false;
    private boolean awaitingFertilizerChest = false;

    public record ChestAssignResult(boolean success, String message, boolean keepAwaiting) {}

    public static boolean isStorageContainerBlock(BlockState state) {
        if (state == null) return false;
        var block = state.getBlock();
        return block instanceof ChestBlock
            || block instanceof BarrelBlock
            || block instanceof EnderChestBlock
            || block instanceof ShulkerBoxBlock;
    }

    public static BlockPos getConnectedChestHalf(ClientLevel level, BlockPos pos) {
        if (level == null || pos == null) return null;
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock
                && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return ChestBlock.getConnectedBlockPos(pos, state).immutable();
        }
        return null;
    }

    public static boolean isSameStorageContainer(ClientLevel level, BlockPos a, BlockPos b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        BlockPos half = getConnectedChestHalf(level, a);
        return half != null && half.equals(b);
    }

    public static boolean containsStorageContainer(ClientLevel level, Collection<BlockPos> positions, BlockPos target) {
        if (positions == null || positions.isEmpty() || target == null) return false;
        for (BlockPos existing : positions) {
            if (isSameStorageContainer(level, existing, target)) {
                return true;
            }
        }
        return false;
    }

    private FarmAreaManager() {
        load();
    }

    public static FarmAreaManager getInstance() {
        return INSTANCE;
    }

    public String getCurrentWorldKey() {
        return currentWorldKey;
    }

    public String getCurrentDimensionId() {
        return currentDimensionId;
    }

    public String getCurrentDimensionDisplayName() {
        return FarmArea.formatDimensionDisplayName(currentDimensionId);
    }

    public static boolean isBedDimensionSupported(ClientLevel level) {
        if (level == null) return false;
        return !Level.NETHER.equals(level.dimension()) && !Level.END.equals(level.dimension());
    }

    public static String resolveWorldKey(Minecraft client) {
        if (client == null || client.level == null) return null;
        if (client.getSingleplayerServer() != null) {
            String levelName = client.getSingleplayerServer().getWorldData().getLevelName();
            return "local:" + (levelName != null && !levelName.isBlank() ? levelName.trim() : "default");
        }
        if (client.getCurrentServer() != null && client.getCurrentServer().ip != null && !client.getCurrentServer().ip.isBlank()) {
            return "server:" + client.getCurrentServer().ip.trim();
        }
        return "remote:default";
    }

    public static String resolveDimensionId(ClientLevel level) {
        if (level == null) return null;
        var dim = level.dimension();
        if (Level.OVERWORLD.equals(dim)) return "minecraft:overworld";
        if (Level.NETHER.equals(dim)) return "minecraft:the_nether";
        if (Level.END.equals(dim)) return "minecraft:the_end";
        String raw = dim.toString();
        int sep = raw.indexOf(" / ");
        int end = raw.lastIndexOf(']');
        if (sep >= 0 && end > sep + 3) {
            return raw.substring(sep + 3, end).trim();
        }
        return raw;
    }

    public static String makeScopeKey(String worldKey, String dimensionId) {
        if (worldKey == null || worldKey.isBlank() || dimensionId == null || dimensionId.isBlank()) {
            return null;
        }
        return worldKey + "|" + dimensionId;
    }

    private String currentScopeKey() {
        return makeScopeKey(currentWorldKey, currentDimensionId);
    }

    /**
     * تحديث سياق العالم والبُعد الحاليين للبوت، وإرجاع true عند حدوث انتقال فعلي بين عالم أو بُعد.
     */
    public synchronized boolean updateContext(Minecraft client) {
        String nextWorld = resolveWorldKey(client);
        String nextDim = (client != null && client.level != null) ? resolveDimensionId(client.level) : null;
        boolean changed = !Objects.equals(this.currentWorldKey, nextWorld)
            || !Objects.equals(this.currentDimensionId, nextDim);

        if (changed) {
            this.currentWorldKey = nextWorld;
            this.currentDimensionId = nextDim;
            this.pos1 = null;
            this.pos2 = null;
            this.awaitingChestFarmName = null;
            this.awaitingChestRole = FarmArea.ChestRole.CROP;
            this.awaitingChestAppend = false;
            this.awaitingFertilizerChest = false;
        }

        if (this.currentWorldKey != null && this.currentDimensionId != null) {
            adoptLegacyEntriesIfNeeded();
        }
        return changed;
    }

    private void adoptLegacyEntriesIfNeeded() {
        boolean modified = false;
        for (FarmArea area : areas) {
            if (area.getWorldKey() == null || area.getWorldKey().isBlank()) {
                area.setWorldKey(currentWorldKey);
                if (area.getDimensionId() == null || area.getDimensionId().isBlank()) {
                    String defaultDim = (area.getTargetCrop() == FarmCrop.NETHER_WART)
                        ? "minecraft:the_nether"
                        : "minecraft:overworld";
                    area.setDimensionId(defaultDim);
                }
                modified = true;
            } else if (area.getDimensionId() == null || area.getDimensionId().isBlank()) {
                area.setDimensionId(area.getTargetCrop() == FarmCrop.NETHER_WART
                    ? "minecraft:the_nether"
                    : "minecraft:overworld");
                modified = true;
            }
        }
        if (legacyBedPos != null) {
            String bedScope = makeScopeKey(currentWorldKey, "minecraft:overworld");
            if (bedScope != null) {
                scopedBeds.putIfAbsent(bedScope, legacyBedPos);
                legacyBedPos = null;
                modified = true;
            }
        }
        if (legacyFertilizerChestPos != null) {
            String scope = currentScopeKey();
            if (scope != null) {
                scopedFertilizerChests.putIfAbsent(scope, legacyFertilizerChestPos);
                legacyFertilizerChestPos = null;
                modified = true;
            }
        }
        if (modified) {
            save();
        }
    }

    private List<FarmArea> getScopedAreasInternal() {
        List<FarmArea> result = new ArrayList<>();
        for (FarmArea area : areas) {
            if (area.matchesContext(currentWorldKey, currentDimensionId)) {
                result.add(area);
            }
        }
        return result;
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

    public synchronized BlockPos getBedPos() {
        String scope = currentScopeKey();
        return scope != null ? scopedBeds.get(scope) : null;
    }

    public synchronized void setBedPos(BlockPos bedPos) {
        String scope = currentScopeKey();
        if (scope == null) return;
        if (bedPos == null) {
            scopedBeds.remove(scope);
        } else {
            scopedBeds.put(scope, bedPos.immutable());
        }
        save();
    }

    public String getAwaitingChestFarm() {
        return awaitingChestFarmName;
    }

    public FarmArea.ChestRole getAwaitingChestRole() {
        return awaitingChestRole;
    }

    public boolean isAwaitingChestAppend() {
        return awaitingChestAppend;
    }

    public void setAwaitingChestFarm(String farmName) {
        setAwaitingChestTarget(farmName, FarmArea.ChestRole.CROP, false);
    }

    public void setAwaitingChestTarget(String farmName, FarmArea.ChestRole role, boolean append) {
        this.awaitingChestFarmName = farmName != null ? farmName.toLowerCase() : null;
        this.awaitingChestRole = role != null ? role : FarmArea.ChestRole.CROP;
        this.awaitingChestAppend = append;
        if (this.awaitingChestFarmName != null) {
            this.awaitingFertilizerChest = false;
            com.helpermod.bot.hunt.HuntConfigManager.getInstance().clearAwaitingChestSelection();
        }
    }

    public synchronized BlockPos getFertilizerChestPos() {
        String scope = currentScopeKey();
        return scope != null ? scopedFertilizerChests.get(scope) : null;
    }

    public synchronized void setFertilizerChestPos(BlockPos fertilizerChestPos) {
        String scope = currentScopeKey();
        if (scope == null) return;
        if (fertilizerChestPos == null) {
            scopedFertilizerChests.remove(scope);
        } else {
            scopedFertilizerChests.put(scope, fertilizerChestPos.immutable());
        }
        save();
    }

    public synchronized FarmArea findAreaUsingChest(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        return findAreaUsingChest(client != null ? client.level : null, pos);
    }

    public synchronized FarmArea findAreaUsingChest(ClientLevel level, BlockPos pos) {
        if (pos == null) return null;
        for (FarmArea area : getScopedAreasInternal()) {
            if (containsStorageContainer(level, area.getCropChests(), pos)
                    || containsStorageContainer(level, area.getSeedChests(), pos)) {
                return area;
            }
        }
        return null;
    }

    public synchronized ChestAssignResult assignAreaChest(
            ClientLevel level,
            String farmName,
            FarmArea.ChestRole role,
            BlockPos clickedPos,
            boolean append) {
        if (clickedPos == null) {
            return new ChestAssignResult(false, "§c[Bot] إحداثيات الصندوق غير صالحة.", true);
        }
        FarmArea area = getArea(farmName);
        if (area == null) {
            this.awaitingChestFarmName = null;
            return new ChestAssignResult(false, "§c[Bot] لم يتم العثور على المزرعة §e" + farmName + "§c في هذا العالم/البُعد.", false);
        }

        FarmArea.ChestRole targetRole = role != null ? role : FarmArea.ChestRole.CROP;
        if (targetRole == FarmArea.ChestRole.SEED && !area.supportsSeparateSeedChest()) {
            this.awaitingChestFarmName = null;
            return new ChestAssignResult(false,
                "§e[Bot] محصول مزرعة §b" + area.getName() + "§e (" + area.getCropDisplayName() + ") ليس له بذور منفصلة؛ يُستخدم صندوق المحصول نفسه لكليهما.",
                false);
        }

        BlockPos immutablePos = clickedPos.immutable();
        if (isSameStorageContainer(level, immutablePos, getFertilizerChestPos())) {
            return new ChestAssignResult(false,
                "§c[Bot] هذا الصندوق مخصص للسماد (Bone Meal). اختر صندوقاً آخر لمزرعة §e" + area.getName() + "§c.",
                true);
        }

        FarmArea.ChestRole otherRole = (targetRole == FarmArea.ChestRole.CROP)
            ? FarmArea.ChestRole.SEED
            : FarmArea.ChestRole.CROP;
        if (containsStorageContainer(level, area.getChests(otherRole), immutablePos)) {
            return new ChestAssignResult(false,
                "§c[Bot] هذا الصندوق مخصص بالفعل كصندوق §e" + otherRole.getArabicLabel() + "§c في مزرعة §e" + area.getName() + "§c! اختر صندوقاً مستقلاً لـ §b" + targetRole.getArabicLabel() + "§c.",
                true);
        }

        if (append) {
            if (containsStorageContainer(level, area.getChests(targetRole), immutablePos)) {
                return new ChestAssignResult(false,
                    "§e[Bot] هذا الصندوق (أو النصف الآخر منه) مسجل بالفعل في قائمة صناديق §b" + targetRole.getArabicLabel() + "§e لمزرعة §e" + area.getName() + "§e.",
                    true);
            }
            area.addChest(targetRole, immutablePos);
        } else {
            area.setSingleChest(targetRole, immutablePos);
        }

        save();
        this.awaitingChestFarmName = null;

        boolean isDouble = getConnectedChestHalf(level, immutablePos) != null;
        int totalInRole = area.getChests(targetRole).size();
        String verb = append ? "إضافة" : "تعيين";
        String doubleTag = isDouble ? " §7[صندوق مزدوج]" : "";
        String msg = "§a[Bot] تم بنجاح " + verb + " صندوق §b" + targetRole.getArabicLabel()
            + "§a لمزرعة §e" + area.getName() + "§a عند: §e"
            + immutablePos.getX() + ", " + immutablePos.getY() + ", " + immutablePos.getZ()
            + doubleTag + " §7(الإجمالي في هذا الدور: §e" + totalInRole + "§7)";
        return new ChestAssignResult(true, msg, false);
    }

    public synchronized ChestAssignResult assignFertilizerChest(ClientLevel level, BlockPos clickedPos) {
        if (clickedPos == null) {
            return new ChestAssignResult(false, "§c[Bot] إحداثيات الصندوق غير صالحة.", true);
        }
        FarmArea existingFarm = findAreaUsingChest(level, clickedPos);
        if (existingFarm != null) {
            return new ChestAssignResult(false,
                "§c[Bot] هذا الصندوق مخصص لمحاصيل أو بذور مزرعة §e" + existingFarm.getName() + "§c. اختر صندوقاً آخر للسماد (Bone Meal).",
                true);
        }
        BlockPos immutablePos = clickedPos.immutable();
        setFertilizerChestPos(immutablePos);
        setAwaitingFertilizerChest(false);
        boolean isDouble = getConnectedChestHalf(level, immutablePos) != null;
        String doubleTag = isDouble ? " §7[صندوق مزدوج]" : "";
        return new ChestAssignResult(true,
            "§a[Bot] تم بنجاح ربط صندوق السماد (Bone Meal) عند: §e"
                + immutablePos.getX() + ", " + immutablePos.getY() + ", " + immutablePos.getZ() + doubleTag + "§a!",
            false);
    }

    public boolean isAwaitingFertilizerChest() {
        return awaitingFertilizerChest;
    }

    public void setAwaitingFertilizerChest(boolean awaitingFertilizerChest) {
        this.awaitingFertilizerChest = awaitingFertilizerChest;
        if (awaitingFertilizerChest) {
            this.awaitingChestFarmName = null;
            this.awaitingChestRole = FarmArea.ChestRole.CROP;
            this.awaitingChestAppend = false;
            com.helpermod.bot.hunt.HuntConfigManager.getInstance().clearAwaitingChestSelection();
        }
    }

    public synchronized boolean addArea(String name, BlockPos p1, BlockPos p2, FarmCrop crop) {
        if (name == null || name.isBlank() || p1 == null || p2 == null) {
            return false;
        }
        if (currentWorldKey == null || currentDimensionId == null) {
            updateContext(Minecraft.getInstance());
        }
        String key = name.toLowerCase();
        if (getArea(key) != null) {
            return false;
        }
        FarmArea area = new FarmArea(key, p1, p2, crop);
        area.setWorldKey(currentWorldKey);
        area.setDimensionId(currentDimensionId);
        areas.add(area);
        save();
        return true;
    }

    /**
     * تحديث إحداثيات منطقة قائمة مع الاحتفاظ بالمحصول والصندوق المرتبط بها.
     */
    public synchronized boolean updateAreaBounds(String name, BlockPos p1, BlockPos p2) {
        if (name == null || p1 == null || p2 == null) {
            return false;
        }
        FarmArea area = getArea(name);
        if (area == null) {
            return false;
        }
        area.updateBounds(p1, p2);
        save();
        return true;
    }

    public synchronized boolean removeArea(String name) {
        if (name == null) return false;
        FarmArea target = getArea(name);
        if (target == null) {
            return false;
        }
        boolean removed = areas.remove(target);
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized boolean renameArea(String oldName, String newName) {
        if (oldName == null || newName == null || newName.isBlank()) {
            return false;
        }
        String newKey = newName.toLowerCase();
        FarmArea area = getArea(oldName);
        if (area == null) {
            return false;
        }
        if (!area.getName().equalsIgnoreCase(newKey)) {
            if (getArea(newKey) != null) {
                return false; // الاسم الجديد مستخدم بالفعل في هذا العالم والبُعد
            }
            area.setName(newKey);
        }
        save();
        return true;
    }

    public synchronized FarmArea getArea(String name) {
        if (name == null) return null;
        for (FarmArea area : getScopedAreasInternal()) {
            if (area.getName().equalsIgnoreCase(name)) {
                return area;
            }
        }
        return null;
    }

    public synchronized Collection<FarmArea> getAllAreas() {
        return Collections.unmodifiableCollection(getScopedAreasInternal());
    }

    public synchronized boolean setAreaEnabled(String name, boolean enabled) {
        if (name == null) return false;
        FarmArea area = getArea(name);
        if (area == null) {
            return false;
        }
        area.setEnabled(enabled);
        save();
        return true;
    }

    public synchronized void setAllAreasEnabled(boolean enabled) {
        for (FarmArea area : getScopedAreasInternal()) {
            area.setEnabled(enabled);
        }
        save();
    }

    public synchronized String getActiveAreaName() {
        List<FarmArea> active = getActiveAreas();
        if (active.size() == 1) {
            return active.get(0).getName();
        }
        return null;
    }

    public synchronized void setActiveAreaName(String activeAreaName) {
        if (activeAreaName == null) {
            setAllAreasEnabled(true);
            return;
        }
        String key = activeAreaName.toLowerCase();
        if (getArea(key) == null) {
            return;
        }
        for (FarmArea area : getScopedAreasInternal()) {
            area.setEnabled(area.getName().equalsIgnoreCase(key));
        }
        save();
    }

    public synchronized List<FarmArea> getActiveAreas() {
        List<FarmArea> active = new ArrayList<>();
        for (FarmArea area : getScopedAreasInternal()) {
            if (area.isEnabled()) {
                active.add(area);
            }
        }
        return active;
    }

    public synchronized String getActiveAreasSummary() {
        List<FarmArea> scoped = getScopedAreasInternal();
        if (scoped.isEmpty()) {
            return "لا توجد مزارع مسجلة في هذا العالم/البُعد";
        }
        List<String> enabledNames = new ArrayList<>();
        for (FarmArea area : scoped) {
            if (area.isEnabled()) {
                enabledNames.add(area.getName());
            }
        }
        if (enabledNames.isEmpty()) {
            return "لا توجد مزارع مفعلة (0 من " + scoped.size() + ")";
        }
        if (enabledNames.size() == scoped.size()) {
            return "جميع المناطق (" + scoped.size() + " مفعلة)";
        }
        return String.join("، ", enabledNames) + " (" + enabledNames.size() + " من " + scoped.size() + " مفعلة)";
    }

    public synchronized FarmArea findAreaContaining(BlockPos pos) {
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

    public static JsonObject posToJson(BlockPos pos) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", pos.getX());
        obj.addProperty("y", pos.getY());
        obj.addProperty("z", pos.getZ());
        return obj;
    }

    public static BlockPos jsonToPos(JsonObject obj) {
        if (obj == null || !obj.has("x") || !obj.has("y") || !obj.has("z")) return null;
        return new BlockPos(obj.get("x").getAsInt(), obj.get("y").getAsInt(), obj.get("z").getAsInt());
    }

    public static JsonArray posListToJson(List<BlockPos> list) {
        JsonArray arr = new JsonArray();
        for (BlockPos pos : list) {
            if (pos != null) {
                arr.add(posToJson(pos));
            }
        }
        return arr;
    }

    public static List<BlockPos> readPosList(JsonObject obj, String key) {
        List<BlockPos> result = new ArrayList<>();
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
            return result;
        }
        for (JsonElement el : obj.getAsJsonArray(key)) {
            if (el.isJsonObject()) {
                BlockPos pos = jsonToPos(el.getAsJsonObject());
                if (pos != null) {
                    result.add(pos.immutable());
                }
            }
        }
        return result;
    }

    public static void atomicWriteJson(Path file, JsonObject root) throws java.io.IOException {
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
    }

    private static void loadChestArray(JsonObject obj, String key, FarmArea area, FarmArea.ChestRole role) {
        for (BlockPos pos : readPosList(obj, key)) {
            area.addChest(role, pos);
        }
    }

    public synchronized void save() {
        try {
            Path file = getConfigFile();
            JsonObject root = new JsonObject();
            root.addProperty("sleepAtNight", sleepAtNight);

            if (legacyBedPos != null) {
                root.add("bedPos", posToJson(legacyBedPos));
            }
            if (legacyFertilizerChestPos != null) {
                root.add("fertilizerChestPos", posToJson(legacyFertilizerChestPos));
            }

            if (!scopedBeds.isEmpty()) {
                JsonObject bedsObj = new JsonObject();
                for (Map.Entry<String, BlockPos> entry : scopedBeds.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        bedsObj.add(entry.getKey(), posToJson(entry.getValue()));
                    }
                }
                root.add("scopedBeds", bedsObj);
            }

            if (!scopedFertilizerChests.isEmpty()) {
                JsonObject fertObj = new JsonObject();
                for (Map.Entry<String, BlockPos> entry : scopedFertilizerChests.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        fertObj.add(entry.getKey(), posToJson(entry.getValue()));
                    }
                }
                root.add("scopedFertilizerChests", fertObj);
            }

            JsonArray array = new JsonArray();
            for (FarmArea area : areas) {
                JsonObject obj = new JsonObject();
                obj.addProperty("name", area.getName());

                if (area.getWorldKey() != null) {
                    obj.addProperty("worldKey", area.getWorldKey());
                }
                if (area.getDimensionId() != null) {
                    obj.addProperty("dimensionId", area.getDimensionId());
                }

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

                if (!area.getCropChests().isEmpty()) {
                    obj.add("cropChests", posListToJson(area.getCropChests()));
                }
                if (!area.getSeedChests().isEmpty()) {
                    obj.add("seedChests", posListToJson(area.getSeedChests()));
                }

                obj.addProperty("enabled", area.isEnabled());

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
            String legacyActiveArea = null;
            if (root.has("activeArea") && !root.get("activeArea").isJsonNull()) {
                legacyActiveArea = root.get("activeArea").getAsString();
            }
            if (root.has("sleepAtNight")) {
                this.sleepAtNight = root.get("sleepAtNight").getAsBoolean();
            }

            this.scopedBeds.clear();
            this.scopedFertilizerChests.clear();
            this.legacyBedPos = null;
            this.legacyFertilizerChestPos = null;

            if (root.has("bedPos") && root.get("bedPos").isJsonObject()) {
                this.legacyBedPos = jsonToPos(root.getAsJsonObject("bedPos"));
            }
            if (root.has("fertilizerChestPos") && root.get("fertilizerChestPos").isJsonObject()) {
                this.legacyFertilizerChestPos = jsonToPos(root.getAsJsonObject("fertilizerChestPos"));
            }

            if (root.has("scopedBeds") && root.get("scopedBeds").isJsonObject()) {
                JsonObject bedsObj = root.getAsJsonObject("scopedBeds");
                for (Map.Entry<String, JsonElement> entry : bedsObj.entrySet()) {
                    if (entry.getValue().isJsonObject()) {
                        BlockPos pos = jsonToPos(entry.getValue().getAsJsonObject());
                        if (pos != null) {
                            this.scopedBeds.put(entry.getKey(), pos);
                        }
                    }
                }
            }

            if (root.has("scopedFertilizerChests") && root.get("scopedFertilizerChests").isJsonObject()) {
                JsonObject fertObj = root.getAsJsonObject("scopedFertilizerChests");
                for (Map.Entry<String, JsonElement> entry : fertObj.entrySet()) {
                    if (entry.getValue().isJsonObject()) {
                        BlockPos pos = jsonToPos(entry.getValue().getAsJsonObject());
                        if (pos != null) {
                            this.scopedFertilizerChests.put(entry.getKey(), pos);
                        }
                    }
                }
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
                    if (obj.has("worldKey") && !obj.get("worldKey").isJsonNull()) {
                        area.setWorldKey(obj.get("worldKey").getAsString());
                    }
                    if (obj.has("dimensionId") && !obj.get("dimensionId").isJsonNull()) {
                        area.setDimensionId(obj.get("dimensionId").getAsString());
                    }
                    if (obj.has("chestPos") && obj.get("chestPos").isJsonObject()) {
                        BlockPos legacyChest = jsonToPos(obj.getAsJsonObject("chestPos"));
                        if (legacyChest != null) {
                            area.addChest(FarmArea.ChestRole.CROP, legacyChest);
                        }
                    }
                    loadChestArray(obj, "cropChests", area, FarmArea.ChestRole.CROP);
                    loadChestArray(obj, "seedChests", area, FarmArea.ChestRole.SEED);
                    if (obj.has("enabled") && !obj.get("enabled").isJsonNull()) {
                        area.setEnabled(obj.get("enabled").getAsBoolean());
                    } else if (legacyActiveArea != null) {
                        area.setEnabled(name.equalsIgnoreCase(legacyActiveArea));
                    } else {
                        area.setEnabled(true);
                    }
                    areas.add(area);
                }
            }
        } catch (Exception e) {
            HelperMod.LOGGER.error("Failed to load helpermod_areas.json", e);
        }
    }
}
