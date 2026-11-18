package com.helpermod.bot.hunt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.helpermod.HelperMod;
import com.helpermod.bot.FarmArea;
import com.helpermod.bot.FarmAreaManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * مدير قائمة الحيوانات/الموبات المستهدفة، القواعد، ومناطق وصناديق الصيد المرتبطة بالعالم والبُعد.
 */
public class HuntConfigManager {

    public enum HuntMode {
        ZONE("داخل المناطق/الحظائر"),
        PATROL("دورية حول نقطة الارتكاز"),
        SPOT("نقطة تجميع ثابتة (Grinder)");

        private final String arabicName;

        HuntMode(String arabicName) {
            this.arabicName = arabicName;
        }

        public String getArabicName() {
            return arabicName;
        }
    }

    public enum NightBehavior {
        AUTO("تلقائي ذكي"),
        SLEEP("نوم ليلاً"),
        STAY_AWAKE("سهر دائم");

        private final String arabicName;

        NightBehavior(String arabicName) {
            this.arabicName = arabicName;
        }

        public String getArabicName() {
            return arabicName;
        }
    }

    public enum WeaponPreference {
        AUTO_SMART("تلقائي ذكي (يفضّل الأقوى وLooting)"),
        SWORD("تفضيل السيف"),
        AXE("تفضيل الفأس");

        private final String arabicName;

        WeaponPreference(String arabicName) {
            this.arabicName = arabicName;
        }

        public String getArabicName() {
            return arabicName;
        }
    }

    private static final HuntConfigManager INSTANCE = new HuntConfigManager();

    private final Set<String> targetMobIds = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final List<HuntZone> zones = new ArrayList<>();
    private final Map<String, List<BlockPos>> scopedLootChests = new LinkedHashMap<>();

    private boolean skipBabies = true;
    private boolean protectNamedAndTamed = true;
    private int minKeepInZones = 2;
    private int patrolRadius = 64;
    private WeaponPreference weaponPreference = WeaponPreference.AUTO_SMART;
    private NightBehavior nightBehavior = NightBehavior.AUTO;

    private String currentWorldKey = null;
    private String currentDimensionId = null;
    private BlockPos pos1 = null;
    private BlockPos pos2 = null;

    private String awaitingLootZoneName = null;
    private boolean awaitingGlobalLootChest = false;
    private boolean awaitingLootAppend = false;

    private HuntConfigManager() {
        load();
    }

    public static HuntConfigManager getInstance() {
        return INSTANCE;
    }

    public synchronized boolean updateContext(Minecraft client) {
        String nextWorld = FarmAreaManager.resolveWorldKey(client);
        String nextDim = (client != null && client.level != null) ? FarmAreaManager.resolveDimensionId(client.level) : null;
        boolean changed = !Objects.equals(this.currentWorldKey, nextWorld)
            || !Objects.equals(this.currentDimensionId, nextDim);
        if (changed) {
            this.currentWorldKey = nextWorld;
            this.currentDimensionId = nextDim;
            this.pos1 = null;
            this.pos2 = null;
            this.awaitingLootZoneName = null;
            this.awaitingGlobalLootChest = false;
            this.awaitingLootAppend = false;
        }
        return changed;
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

    private String currentScopeKey() {
        return FarmAreaManager.makeScopeKey(currentWorldKey, currentDimensionId);
    }

    // ==================== الأهداف المستهدفة ====================

    public synchronized Set<String> getTargetMobIds() {
        return Collections.unmodifiableSet(new TreeSet<>(targetMobIds));
    }

    public synchronized boolean isTargetedMob(Entity entity) {
        if (entity == null || targetMobIds.isEmpty()) return false;
        String canonical = MobTypeRegistry.getCanonicalMobId(entity);
        return canonical != null && targetMobIds.contains(canonical);
    }

    public synchronized boolean addTargetMob(String canonicalId) {
        if (canonicalId == null || canonicalId.isBlank()) return false;
        boolean added = targetMobIds.add(canonicalId.trim().toLowerCase(Locale.ROOT));
        if (added) save();
        return added;
    }

    public synchronized boolean removeTargetMob(String canonicalId) {
        if (canonicalId == null || canonicalId.isBlank()) return false;
        boolean removed = targetMobIds.remove(canonicalId.trim().toLowerCase(Locale.ROOT));
        if (removed) save();
        return removed;
    }

    public synchronized void clearTargetMobs() {
        if (!targetMobIds.isEmpty()) {
            targetMobIds.clear();
            save();
        }
    }

    public synchronized boolean hasNightMonsterTargets() {
        for (String id : targetMobIds) {
            if (MobTypeRegistry.isNightMonsterId(id)) {
                return true;
            }
        }
        return false;
    }

    // ==================== القواعد ====================

    public synchronized boolean isSkipBabies() {
        return skipBabies;
    }

    public synchronized void setSkipBabies(boolean skipBabies) {
        this.skipBabies = skipBabies;
        save();
    }

    public synchronized boolean isProtectNamedAndTamed() {
        return protectNamedAndTamed;
    }

    public synchronized void setProtectNamedAndTamed(boolean protectNamedAndTamed) {
        this.protectNamedAndTamed = protectNamedAndTamed;
        save();
    }

    public synchronized int getMinKeepInZones() {
        return minKeepInZones;
    }

    public synchronized void setMinKeepInZones(int minKeepInZones) {
        this.minKeepInZones = Math.max(0, Math.min(20, minKeepInZones));
        save();
    }

    public synchronized int getPatrolRadius() {
        return patrolRadius;
    }

    public synchronized void setPatrolRadius(int patrolRadius) {
        this.patrolRadius = Math.max(8, Math.min(128, patrolRadius));
        save();
    }

    public synchronized WeaponPreference getWeaponPreference() {
        return weaponPreference;
    }

    public synchronized void setWeaponPreference(WeaponPreference weaponPreference) {
        this.weaponPreference = weaponPreference != null ? weaponPreference : WeaponPreference.AUTO_SMART;
        save();
    }

    public synchronized NightBehavior getNightBehavior() {
        return nightBehavior;
    }

    public synchronized void setNightBehavior(NightBehavior nightBehavior) {
        this.nightBehavior = nightBehavior != null ? nightBehavior : NightBehavior.AUTO;
        save();
    }

    // ==================== المناطق والصناديق ====================

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

    private List<HuntZone> getScopedZonesInternal() {
        List<HuntZone> result = new ArrayList<>();
        for (HuntZone zone : zones) {
            if (zone.matchesContext(currentWorldKey, currentDimensionId)) {
                result.add(zone);
            }
        }
        return result;
    }

    public synchronized Collection<HuntZone> getAllZones() {
        return Collections.unmodifiableCollection(getScopedZonesInternal());
    }

    public synchronized List<HuntZone> getActiveZones() {
        List<HuntZone> active = new ArrayList<>();
        for (HuntZone zone : getScopedZonesInternal()) {
            if (zone.isEnabled()) {
                active.add(zone);
            }
        }
        return active;
    }

    public synchronized HuntZone getZone(String name) {
        if (name == null) return null;
        for (HuntZone zone : getScopedZonesInternal()) {
            if (zone.getName().equalsIgnoreCase(name)) {
                return zone;
            }
        }
        return null;
    }

    public synchronized boolean addZone(String name, BlockPos p1, BlockPos p2) {
        if (name == null || name.isBlank() || p1 == null || p2 == null) return false;
        if (currentWorldKey == null || currentDimensionId == null) {
            updateContext(Minecraft.getInstance());
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (getZone(key) != null) return false;

        HuntZone zone = new HuntZone(key, p1, p2);
        zone.setWorldKey(currentWorldKey);
        zone.setDimensionId(currentDimensionId);
        zones.add(zone);
        save();
        return true;
    }

    public synchronized boolean updateZoneBounds(String name, BlockPos p1, BlockPos p2) {
        HuntZone zone = getZone(name);
        if (zone == null || p1 == null || p2 == null) return false;
        zone.updateBounds(p1, p2);
        save();
        return true;
    }

    public synchronized boolean removeZone(String name) {
        HuntZone zone = getZone(name);
        if (zone == null) return false;
        boolean removed = zones.remove(zone);
        if (removed) save();
        return removed;
    }

    public synchronized boolean renameZone(String oldName, String newName) {
        if (oldName == null || newName == null || newName.isBlank()) return false;
        String newKey = newName.toLowerCase(Locale.ROOT);
        HuntZone zone = getZone(oldName);
        if (zone == null) return false;
        if (!zone.getName().equalsIgnoreCase(newKey) && getZone(newKey) != null) {
            return false;
        }
        zone.setName(newKey);
        save();
        return true;
    }

    public synchronized boolean setZoneEnabled(String name, boolean enabled) {
        HuntZone zone = getZone(name);
        if (zone == null) return false;
        zone.setEnabled(enabled);
        save();
        return true;
    }

    public synchronized void setAllZonesEnabled(boolean enabled) {
        for (HuntZone zone : getScopedZonesInternal()) {
            zone.setEnabled(enabled);
        }
        save();
    }

    public synchronized void selectOnlyZone(String name) {
        if (getZone(name) == null) return;
        for (HuntZone zone : getScopedZonesInternal()) {
            zone.setEnabled(zone.getName().equalsIgnoreCase(name));
        }
        save();
    }

    public synchronized List<BlockPos> getGlobalLootChests() {
        String scope = currentScopeKey();
        if (scope == null) return Collections.emptyList();
        List<BlockPos> list = scopedLootChests.get(scope);
        return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
    }

    public synchronized BlockPos removeGlobalLootChestAt(int zeroBasedIndex) {
        String scope = currentScopeKey();
        if (scope == null) return null;
        List<BlockPos> list = scopedLootChests.get(scope);
        if (list == null || zeroBasedIndex < 0 || zeroBasedIndex >= list.size()) {
            return null;
        }
        BlockPos removed = list.remove(zeroBasedIndex);
        if (list.isEmpty()) {
            scopedLootChests.remove(scope);
        }
        save();
        return removed;
    }

    public synchronized void clearGlobalLootChests() {
        String scope = currentScopeKey();
        if (scope != null && scopedLootChests.remove(scope) != null) {
            save();
        }
    }

    // ==================== الربط بالفتح (open) ====================

    public String getAwaitingLootZoneName() {
        return awaitingLootZoneName;
    }

    public boolean isAwaitingGlobalLootChest() {
        return awaitingGlobalLootChest;
    }

    public boolean isAwaitingAnyLootChest() {
        return awaitingGlobalLootChest || awaitingLootZoneName != null;
    }

    public boolean isAwaitingLootAppend() {
        return awaitingLootAppend;
    }

    public void setAwaitingZoneLootChest(String zoneName, boolean append) {
        this.awaitingLootZoneName = zoneName != null ? zoneName.toLowerCase(Locale.ROOT) : null;
        this.awaitingGlobalLootChest = false;
        this.awaitingLootAppend = append;
        if (this.awaitingLootZoneName != null) {
            FarmAreaManager.getInstance().setAwaitingChestFarm(null);
            FarmAreaManager.getInstance().setAwaitingFertilizerChest(false);
        }
    }

    public void setAwaitingGlobalLootChest(boolean append) {
        this.awaitingGlobalLootChest = true;
        this.awaitingLootZoneName = null;
        this.awaitingLootAppend = append;
        FarmAreaManager.getInstance().setAwaitingChestFarm(null);
        FarmAreaManager.getInstance().setAwaitingFertilizerChest(false);
    }

    public void clearAwaitingChestSelection() {
        this.awaitingGlobalLootChest = false;
        this.awaitingLootZoneName = null;
        this.awaitingLootAppend = false;
    }

    public synchronized FarmAreaManager.ChestAssignResult assignZoneLootChest(
            ClientLevel level,
            String zoneName,
            BlockPos pos,
            boolean append) {
        if (pos == null) {
            return new FarmAreaManager.ChestAssignResult(false, "§c[Bot - Hunt] إحداثيات الصندوق غير صالحة.", true);
        }
        HuntZone zone = getZone(zoneName);
        if (zone == null) {
            clearAwaitingChestSelection();
            return new FarmAreaManager.ChestAssignResult(false, "§c[Bot - Hunt] المنطقة §e" + zoneName + "§c غير موجودة في هذا البُعد.", false);
        }
        BlockPos fert = FarmAreaManager.getInstance().getFertilizerChestPos();
        if (FarmAreaManager.isSameStorageContainer(level, pos, fert)) {
            return new FarmAreaManager.ChestAssignResult(false, "§c[Bot - Hunt] هذا الصندوق مخصص للسماد (Bone Meal). اختر صندوقاً آخر.", true);
        }
        BlockPos immutablePos = pos.immutable();
        if (append) {
            if (FarmAreaManager.containsStorageContainer(level, zone.getLootChests(), immutablePos)) {
                return new FarmAreaManager.ChestAssignResult(false, "§e[Bot - Hunt] هذا الصندوق مسجل بالفعل لمنطقة §e" + zone.getName() + "§e.", true);
            }
            zone.addLootChest(immutablePos);
        } else {
            zone.setSingleLootChest(immutablePos);
        }
        save();
        clearAwaitingChestSelection();
        boolean isDouble = FarmAreaManager.getConnectedChestHalf(level, immutablePos) != null;
        String doubleTag = isDouble ? " §7[صندوق مزدوج]" : "";
        return new FarmAreaManager.ChestAssignResult(true,
            "§a[Bot - Hunt] تم بنجاح " + (append ? "إضافة" : "تعيين") + " صندوق غنائم لمنطقة §e" + zone.getName()
                + "§a عند: §e" + immutablePos.getX() + ", " + immutablePos.getY() + ", " + immutablePos.getZ()
                + doubleTag + " §7(الإجمالي: §e" + zone.getLootChests().size() + "§7)",
            false);
    }

    public synchronized FarmAreaManager.ChestAssignResult assignGlobalLootChest(
            ClientLevel level,
            BlockPos pos,
            boolean append) {
        if (pos == null) {
            return new FarmAreaManager.ChestAssignResult(false, "§c[Bot - Hunt] إحداثيات الصندوق غير صالحة.", true);
        }
        String scope = currentScopeKey();
        if (scope == null) {
            clearAwaitingChestSelection();
            return new FarmAreaManager.ChestAssignResult(false, "§c[Bot - Hunt] تعذر تحديد العالم/البُعد الحالي.", false);
        }
        BlockPos fert = FarmAreaManager.getInstance().getFertilizerChestPos();
        if (FarmAreaManager.isSameStorageContainer(level, pos, fert)) {
            return new FarmAreaManager.ChestAssignResult(false, "§c[Bot - Hunt] هذا الصندوق مخصص للسماد (Bone Meal). اختر صندوقاً آخر.", true);
        }
        BlockPos immutablePos = pos.immutable();
        List<BlockPos> list = scopedLootChests.computeIfAbsent(scope, k -> new ArrayList<>());
        if (append) {
            if (FarmAreaManager.containsStorageContainer(level, list, immutablePos)) {
                return new FarmAreaManager.ChestAssignResult(false, "§e[Bot - Hunt] هذا الصندوق مسجل بالفعل في صناديق الغنائم العامة لهذا البُعد.", true);
            }
            list.add(immutablePos);
        } else {
            list.clear();
            list.add(immutablePos);
        }
        save();
        clearAwaitingChestSelection();
        boolean isDouble = FarmAreaManager.getConnectedChestHalf(level, immutablePos) != null;
        String doubleTag = isDouble ? " §7[صندوق مزدوج]" : "";
        return new FarmAreaManager.ChestAssignResult(true,
            "§a[Bot - Hunt] تم بنجاح " + (append ? "إضافة" : "تعيين") + " صندوق الغنائم العام في §b" + getCurrentDimensionDisplayName()
                + "§a عند: §e" + immutablePos.getX() + ", " + immutablePos.getY() + ", " + immutablePos.getZ()
                + doubleTag + " §7(الإجمالي: §e" + list.size() + "§7)",
            false);
    }

    // ==================== الحفظ والتحميل ====================

    private Path getConfigFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("helpermod_hunt.json");
    }

    public synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("skipBabies", skipBabies);
            root.addProperty("protectNamedAndTamed", protectNamedAndTamed);
            root.addProperty("minKeepInZones", minKeepInZones);
            root.addProperty("patrolRadius", patrolRadius);
            root.addProperty("weaponPreference", weaponPreference.name());
            root.addProperty("nightBehavior", nightBehavior.name());

            JsonArray targetsArr = new JsonArray();
            for (String mobId : targetMobIds) {
                targetsArr.add(mobId);
            }
            root.add("targetMobs", targetsArr);

            if (!scopedLootChests.isEmpty()) {
                JsonObject lootObj = new JsonObject();
                for (Map.Entry<String, List<BlockPos>> entry : scopedLootChests.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                        lootObj.add(entry.getKey(), FarmAreaManager.posListToJson(entry.getValue()));
                    }
                }
                root.add("scopedLootChests", lootObj);
            }

            JsonArray zonesArr = new JsonArray();
            for (HuntZone zone : zones) {
                JsonObject obj = new JsonObject();
                obj.addProperty("name", zone.getName());
                if (zone.getWorldKey() != null) obj.addProperty("worldKey", zone.getWorldKey());
                if (zone.getDimensionId() != null) obj.addProperty("dimensionId", zone.getDimensionId());
                obj.addProperty("minX", zone.getMinPos().getX());
                obj.addProperty("minY", zone.getMinPos().getY());
                obj.addProperty("minZ", zone.getMinPos().getZ());
                obj.addProperty("maxX", zone.getMaxPos().getX());
                obj.addProperty("maxY", zone.getMaxPos().getY());
                obj.addProperty("maxZ", zone.getMaxPos().getZ());
                obj.addProperty("enabled", zone.isEnabled());
                if (!zone.getLootChests().isEmpty()) {
                    obj.add("lootChests", FarmAreaManager.posListToJson(zone.getLootChests()));
                }
                zonesArr.add(obj);
            }
            root.add("zones", zonesArr);

            FarmAreaManager.atomicWriteJson(getConfigFile(), root);
        } catch (Exception e) {
            HelperMod.LOGGER.error("Failed to save helpermod_hunt.json", e);
        }
    }

    public synchronized void load() {
        Path file = getConfigFile();
        if (!Files.exists(file)) {
            targetMobIds.addAll(MobTypeRegistry.expandPresetOrSingle("animals"));
            return;
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root == null) return;

            if (root.has("skipBabies")) this.skipBabies = root.get("skipBabies").getAsBoolean();
            if (root.has("protectNamedAndTamed")) this.protectNamedAndTamed = root.get("protectNamedAndTamed").getAsBoolean();
            if (root.has("minKeepInZones")) this.minKeepInZones = Math.max(0, root.get("minKeepInZones").getAsInt());
            if (root.has("patrolRadius")) this.patrolRadius = Math.max(8, Math.min(64, root.get("patrolRadius").getAsInt()));
            if (root.has("weaponPreference")) {
                try {
                    this.weaponPreference = WeaponPreference.valueOf(root.get("weaponPreference").getAsString().trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    this.weaponPreference = WeaponPreference.AUTO_SMART;
                }
            }
            if (root.has("nightBehavior")) {
                try {
                    this.nightBehavior = NightBehavior.valueOf(root.get("nightBehavior").getAsString().trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    this.nightBehavior = NightBehavior.AUTO;
                }
            }

            targetMobIds.clear();
            if (root.has("targetMobs") && root.get("targetMobs").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("targetMobs")) {
                    if (el.isJsonPrimitive()) {
                        String norm = MobTypeRegistry.normalizeSingleMobId(el.getAsString());
                        if (norm != null) targetMobIds.add(norm);
                    }
                }
            }

            scopedLootChests.clear();
            if (root.has("scopedLootChests") && root.get("scopedLootChests").isJsonObject()) {
                JsonObject lootObj = root.getAsJsonObject("scopedLootChests");
                for (Map.Entry<String, JsonElement> entry : lootObj.entrySet()) {
                    List<BlockPos> loaded = FarmAreaManager.readPosList(lootObj, entry.getKey());
                    if (!loaded.isEmpty()) {
                        scopedLootChests.put(entry.getKey(), loaded);
                    }
                }
            }

            zones.clear();
            if (root.has("zones") && root.get("zones").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("zones")) {
                    JsonObject obj = el.getAsJsonObject();
                    String name = obj.get("name").getAsString();
                    BlockPos min = new BlockPos(obj.get("minX").getAsInt(), obj.get("minY").getAsInt(), obj.get("minZ").getAsInt());
                    BlockPos max = new BlockPos(obj.get("maxX").getAsInt(), obj.get("maxY").getAsInt(), obj.get("maxZ").getAsInt());
                    HuntZone zone = new HuntZone(name, min, max, true);
                    if (obj.has("worldKey") && !obj.get("worldKey").isJsonNull()) zone.setWorldKey(obj.get("worldKey").getAsString());
                    if (obj.has("dimensionId") && !obj.get("dimensionId").isJsonNull()) zone.setDimensionId(obj.get("dimensionId").getAsString());
                    if (obj.has("enabled") && !obj.get("enabled").isJsonNull()) zone.setEnabled(obj.get("enabled").getAsBoolean());
                    for (BlockPos chest : FarmAreaManager.readPosList(obj, "lootChests")) {
                        zone.addLootChest(chest);
                    }
                    zones.add(zone);
                }
            }
        } catch (Exception e) {
            HelperMod.LOGGER.error("Failed to load helpermod_hunt.json", e);
        }
    }
}
