package com.helpermod.bot.hunt;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Shared registry & resolver for entity/mob IDs via BuiltInRegistries.ENTITY_TYPE.
 * Supports canonical Minecraft entity IDs and English category presets (animals / hostile / nether).
 */
public final class MobTypeRegistry {

    private static final List<String> ANIMAL_PRESET = List.of(
        "minecraft:cow",
        "minecraft:mooshroom",
        "minecraft:sheep",
        "minecraft:pig",
        "minecraft:chicken",
        "minecraft:rabbit"
    );

    private static final List<String> HOSTILE_PRESET = List.of(
        "minecraft:zombie",
        "minecraft:husk",
        "minecraft:drowned",
        "minecraft:skeleton",
        "minecraft:stray",
        "minecraft:bogged",
        "minecraft:creeper",
        "minecraft:spider",
        "minecraft:cave_spider",
        "minecraft:enderman",
        "minecraft:witch",
        "minecraft:slime",
        "minecraft:pillager",
        "minecraft:vindicator",
        "minecraft:phantom"
    );

    private static final List<String> NETHER_PRESET = List.of(
        "minecraft:blaze",
        "minecraft:wither_skeleton",
        "minecraft:magma_cube",
        "minecraft:ghast",
        "minecraft:hoglin",
        "minecraft:piglin"
    );

    private MobTypeRegistry() {}

    public static boolean isValidMobTargetType(EntityType<?> type) {
        if (type == null) return false;
        Identifier key = EntityType.getKey(type);
        String keyStr = key != null ? key.toString() : "";
        if ("minecraft:player".equals(keyStr) || "minecraft:armor_stand".equals(keyStr)) {
            return false;
        }
        if (type.getCategory() != MobCategory.MISC) {
            return true;
        }
        return "minecraft:iron_golem".equals(keyStr)
            || "minecraft:snow_golem".equals(keyStr)
            || "minecraft:villager".equals(keyStr)
            || "minecraft:wandering_trader".equals(keyStr);
    }

    public static String normalizeSingleMobId(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) return null;
        String trimmed = rawInput.trim().toLowerCase(Locale.ROOT);
        String withNamespace = trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
        Identifier id = Identifier.tryParse(withNamespace);
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            return null;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
        return isValidMobTargetType(type) ? id.toString() : null;
    }

    public static List<String> expandPresetOrSingle(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return Collections.emptyList();
        }
        String key = rawInput.trim().toLowerCase(Locale.ROOT);
        if (key.equals("animals") || key.equals("passive")) {
            return filterRegistered(ANIMAL_PRESET);
        }
        if (key.equals("hostile") || key.equals("monsters")) {
            return filterRegistered(HOSTILE_PRESET);
        }
        if (key.equals("nether")) {
            return filterRegistered(NETHER_PRESET);
        }
        String single = normalizeSingleMobId(rawInput);
        return single != null ? List.of(single) : Collections.emptyList();
    }

    private static List<String> filterRegistered(List<String> candidates) {
        List<String> result = new ArrayList<>();
        for (String candidate : candidates) {
            String normalized = normalizeSingleMobId(candidate);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return result;
    }

    public static String getCanonicalMobId(Entity entity) {
        if (entity == null) return null;
        Identifier key = EntityType.getKey(entity.getType());
        return key != null ? key.toString() : null;
    }

    public static boolean isNightMonsterId(String canonicalId) {
        if (canonicalId == null) return false;
        Identifier id = Identifier.tryParse(canonicalId);
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return false;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
        return type.getCategory() == MobCategory.MONSTER;
    }

    /**
     * Format canonical entity ID strictly in English (never localized to Arabic).
     */
    public static String getDisplayName(String canonicalId) {
        if (canonicalId == null || canonicalId.isBlank()) return "unknown";
        String shortId = canonicalId.startsWith("minecraft:")
            ? canonicalId.substring("minecraft:".length())
            : canonicalId;
        String path = canonicalId.contains(":")
            ? canonicalId.substring(canonicalId.indexOf(':') + 1)
            : canonicalId;

        String[] parts = path.split("_");
        StringBuilder title = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (title.length() > 0) title.append(' ');
            title.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        if (title.isEmpty()) {
            return shortId;
        }
        return title + " (" + shortId + ")";
    }

    public static List<String> getAllSuggestionTokens() {
        List<String> list = new ArrayList<>();
        list.add("animals");
        list.add("hostile");
        list.add("nether");
        for (Identifier id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
            if (isValidMobTargetType(type)) {
                list.add("minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString());
            }
        }
        return list;
    }

    public static List<String> getAllSingleMobSuggestionTokens() {
        List<String> list = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
            if (isValidMobTargetType(type)) {
                list.add("minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString());
            }
        }
        return list;
    }
}
