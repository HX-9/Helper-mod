package com.helpermod.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.helpermod.HelperMod;
import com.helpermod.bot.hunt.MobTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.Entity;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * مدير حفظ الإعدادات العامة للمود (حالة التفعيل العام ونمط الدفاع) في config/helpermod_settings.json.
 */
public class BotSettingsManager {

    private static final BotSettingsManager INSTANCE = new BotSettingsManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private boolean botEnabled = true;
    private FightBotController.DefenseMode defenseMode = FightBotController.DefenseMode.CAUTIOUS;
    private final Set<String> followBannedMobs = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    private BotSettingsManager() {
        load();
    }

    public static BotSettingsManager getInstance() {
        return INSTANCE;
    }

    public synchronized boolean isBotEnabled() {
        return botEnabled;
    }

    public synchronized void setBotEnabled(boolean botEnabled) {
        if (this.botEnabled != botEnabled) {
            this.botEnabled = botEnabled;
            save();
        }
    }

    public synchronized FightBotController.DefenseMode getDefenseMode() {
        return defenseMode;
    }

    public synchronized void setDefenseMode(FightBotController.DefenseMode defenseMode) {
        FightBotController.DefenseMode resolved = defenseMode != null
            ? defenseMode
            : FightBotController.DefenseMode.CAUTIOUS;
        if (this.defenseMode != resolved) {
            this.defenseMode = resolved;
            save();
        }
    }

    public synchronized Set<String> getFollowBannedMobs() {
        return Collections.unmodifiableSet(new TreeSet<>(followBannedMobs));
    }

    public synchronized boolean addFollowBannedMob(String canonicalId) {
        if (canonicalId == null || canonicalId.isBlank()) return false;
        boolean added = followBannedMobs.add(canonicalId.trim());
        if (added) {
            save();
        }
        return added;
    }

    public synchronized boolean removeFollowBannedMob(String canonicalId) {
        if (canonicalId == null || canonicalId.isBlank()) return false;
        boolean removed = followBannedMobs.remove(canonicalId.trim());
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized void clearFollowBannedMobs() {
        if (!followBannedMobs.isEmpty()) {
            followBannedMobs.clear();
            save();
        }
    }

    public synchronized boolean isFollowMobBanned(Entity entity) {
        if (entity == null || followBannedMobs.isEmpty()) return false;
        String canonical = MobTypeRegistry.getCanonicalMobId(entity);
        return canonical != null && followBannedMobs.contains(canonical);
    }

    private Path getConfigFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("helpermod_settings.json");
    }

    public synchronized void save() {
        try {
            Path file = getConfigFile();
            JsonObject root = new JsonObject();
            root.addProperty("botEnabled", botEnabled);
            root.addProperty("defenseMode", defenseMode.name());
            if (!followBannedMobs.isEmpty()) {
                JsonArray bannedArr = new JsonArray();
                for (String mobId : followBannedMobs) {
                    bannedArr.add(mobId);
                }
                root.add("followBannedMobs", bannedArr);
            }

            Files.createDirectories(file.getParent());
            Path tempFile = file.resolveSibling(file.getFileName().toString() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tempFile)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException e) {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            HelperMod.LOGGER.error("Failed to save helpermod_settings.json", e);
        }
    }

    public synchronized void load() {
        Path file = getConfigFile();
        if (!Files.exists(file)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root != null) {
                if (root.has("botEnabled") && root.get("botEnabled").isJsonPrimitive()) {
                    this.botEnabled = root.get("botEnabled").getAsBoolean();
                }
                if (root.has("defenseMode") && root.get("defenseMode").isJsonPrimitive()) {
                    String rawMode = root.get("defenseMode").getAsString().trim().toUpperCase();
                    try {
                        this.defenseMode = FightBotController.DefenseMode.valueOf(rawMode);
                    } catch (IllegalArgumentException ignored) {
                        this.defenseMode = FightBotController.DefenseMode.CAUTIOUS;
                    }
                }
                followBannedMobs.clear();
                if (root.has("followBannedMobs") && root.get("followBannedMobs").isJsonArray()) {
                    for (JsonElement el : root.getAsJsonArray("followBannedMobs")) {
                        if (el.isJsonPrimitive()) {
                            String normalized = MobTypeRegistry.normalizeSingleMobId(el.getAsString());
                            if (normalized != null) {
                                followBannedMobs.add(normalized);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            HelperMod.LOGGER.error("Failed to load helpermod_settings.json", e);
        }
    }
}
