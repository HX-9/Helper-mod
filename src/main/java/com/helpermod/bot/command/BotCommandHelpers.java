package com.helpermod.bot.command;

import com.helpermod.bot.BotGlobalManager;
import com.helpermod.bot.BotSettingsManager;
import com.helpermod.bot.FarmArea;
import com.helpermod.bot.FarmAreaManager;
import com.helpermod.bot.FarmCrop;
import com.helpermod.bot.food.BotFoodManager;
import com.helpermod.bot.friend.BotFriendManager;
import com.helpermod.bot.hunt.HuntConfigManager;
import com.helpermod.bot.hunt.HuntZone;
import com.helpermod.bot.hunt.MobTypeRegistry;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

public final class BotCommandHelpers {

    public enum CropParseStatus {
        AUTO,
        CROP,
        INVALID
    }

    public record CropParseResult(CropParseStatus status, FarmCrop crop) {
        public boolean isValid() {
            return status != CropParseStatus.INVALID;
        }
    }

    private static final List<String> CROP_SUGGESTIONS = List.of(
        "wheat", "carrot", "potato", "beetroot", "nether_wart", "auto",
        "قمح", "جزر", "بطاطس", "شمندر", "نيذر_وارت", "تلقائي"
    );

    public static final SuggestionProvider<FabricClientCommandSource> ONLINE_PLAYERS = (ctx, builder) ->
        suggestMatching(getOnlineOtherPlayerNames(Minecraft.getInstance()), builder);

    public static final SuggestionProvider<FabricClientCommandSource> CROPS = (ctx, builder) ->
        suggestMatching(CROP_SUGGESTIONS, builder);

    public static final SuggestionProvider<FabricClientCommandSource> FARM_AREAS = (ctx, builder) -> {
        FarmAreaManager.getInstance().updateContext(Minecraft.getInstance());
        List<String> names = new ArrayList<>();
        for (FarmArea area : FarmAreaManager.getInstance().getAllAreas()) {
            names.add(area.getName());
        }
        return suggestMatching(names, builder);
    };

    public static final SuggestionProvider<FabricClientCommandSource> SELECTABLE_AREAS = (ctx, builder) -> {
        FarmAreaManager.getInstance().updateContext(Minecraft.getInstance());
        List<String> names = new ArrayList<>();
        names.add("all");
        names.add("none");
        names.add("الكل");
        names.add("لا_أحد");
        for (FarmArea area : FarmAreaManager.getInstance().getAllAreas()) {
            names.add(area.getName());
        }
        return suggestMatching(names, builder);
    };

    public static final SuggestionProvider<FabricClientCommandSource> ENABLE_AREA_TARGETS = (ctx, builder) ->
        suggestAreaStateTargets(false, builder);

    public static final SuggestionProvider<FabricClientCommandSource> DISABLE_AREA_TARGETS = (ctx, builder) ->
        suggestAreaStateTargets(true, builder);

    public static final SuggestionProvider<FabricClientCommandSource> FRIENDS = (ctx, builder) ->
        suggestMatching(BotFriendManager.getInstance().getFriends(), builder);

    public static final SuggestionProvider<FabricClientCommandSource> KNOWN_FOODS = (ctx, builder) ->
        suggestMatching(BotFoodManager.FOOD_NAMES.keySet(), builder);

    public static final SuggestionProvider<FabricClientCommandSource> ALLOWED_FOODS = (ctx, builder) ->
        suggestMatching(BotFoodManager.getInstance().getAllowedFoods(), builder);

    public static final SuggestionProvider<FabricClientCommandSource> MOB_TARGETS_WITH_PRESETS = (ctx, builder) ->
        suggestMatching(MobTypeRegistry.getAllSuggestionTokens(), builder);

    public static final SuggestionProvider<FabricClientCommandSource> FOLLOW_BANNED_MOBS = (ctx, builder) ->
        suggestShortMobIds(BotSettingsManager.getInstance().getFollowBannedMobs(), builder);

    public static final SuggestionProvider<FabricClientCommandSource> HUNT_ACTIVE_TARGETS = (ctx, builder) ->
        suggestShortMobIds(HuntConfigManager.getInstance().getTargetMobIds(), builder);

    public static final SuggestionProvider<FabricClientCommandSource> HUNT_ZONES = (ctx, builder) -> {
        HuntConfigManager.getInstance().updateContext(Minecraft.getInstance());
        List<String> names = new ArrayList<>();
        for (HuntZone zone : HuntConfigManager.getInstance().getAllZones()) {
            names.add(zone.getName());
        }
        return suggestMatching(names, builder);
    };

    public static final SuggestionProvider<FabricClientCommandSource> SELECTABLE_HUNT_ZONES = (ctx, builder) -> {
        HuntConfigManager.getInstance().updateContext(Minecraft.getInstance());
        List<String> names = new ArrayList<>();
        names.add("all");
        names.add("none");
        names.add("الكل");
        names.add("لا_أحد");
        for (HuntZone zone : HuntConfigManager.getInstance().getAllZones()) {
            names.add(zone.getName());
        }
        return suggestMatching(names, builder);
    };

    public static final SuggestionProvider<FabricClientCommandSource> ENABLE_HUNT_ZONES = (ctx, builder) ->
        suggestHuntZoneStateTargets(false, builder);

    public static final SuggestionProvider<FabricClientCommandSource> DISABLE_HUNT_ZONES = (ctx, builder) ->
        suggestHuntZoneStateTargets(true, builder);

    private static CompletableFuture<Suggestions> suggestShortMobIds(
            Collection<String> canonicalIds,
            SuggestionsBuilder builder) {
        List<String> shortIds = new ArrayList<>();
        for (String id : canonicalIds) {
            shortIds.add(id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id);
        }
        return suggestMatching(shortIds, builder);
    }

    private static CompletableFuture<Suggestions> suggestHuntZoneStateTargets(
            boolean currentEnabledState,
            SuggestionsBuilder builder) {
        HuntConfigManager.getInstance().updateContext(Minecraft.getInstance());
        List<String> names = new ArrayList<>();
        names.add("all");
        names.add("الكل");
        for (HuntZone zone : HuntConfigManager.getInstance().getAllZones()) {
            if (zone.isEnabled() == currentEnabledState) {
                names.add(zone.getName());
            }
        }
        return suggestMatching(names, builder);
    }

    private BotCommandHelpers() {
    }

    private static CompletableFuture<Suggestions> suggestAreaStateTargets(
            boolean currentEnabledState,
            SuggestionsBuilder builder) {
        FarmAreaManager.getInstance().updateContext(Minecraft.getInstance());
        List<String> names = new ArrayList<>();
        names.add("all");
        names.add("الكل");
        for (FarmArea area : FarmAreaManager.getInstance().getAllAreas()) {
            if (area.isEnabled() == currentEnabledState) {
                names.add(area.getName());
            }
        }
        return suggestMatching(names, builder);
    }

    public static CompletableFuture<Suggestions> suggestMatching(
            Iterable<String> candidates,
            SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (String candidate : candidates) {
            if (candidate != null && candidate.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(candidate);
            }
        }
        return builder.buildFuture();
    }

    public static List<String> getOnlineOtherPlayerNames(Minecraft client) {
        List<String> onlineNames = new ArrayList<>();
        if (client != null && client.getConnection() != null) {
            for (var info : client.getConnection().getOnlinePlayers()) {
                if (info.getProfile() != null && info.getProfile().name() != null) {
                    String name = info.getProfile().name();
                    if (client.player == null || !name.equalsIgnoreCase(client.player.getScoreboardName())) {
                        onlineNames.add(name);
                    }
                }
            }
        }
        return onlineNames;
    }

    public static void send(FabricClientCommandSource source, String message) {
        BotGlobalManager.getInstance().recordUserCommandInput(Minecraft.getInstance());
        source.sendFeedback(Component.literal(message));
    }

    public static CropParseResult parseCropArgument(
            FabricClientCommandSource source,
            String cropArg,
            boolean acceptNone) {
        if (cropArg == null || cropArg.isBlank()) {
            return new CropParseResult(CropParseStatus.AUTO, null);
        }

        String normalized = cropArg.toLowerCase(Locale.ROOT);
        if (normalized.equals("auto")
                || normalized.equals("any")
                || normalized.equals("تلقائي")
                || (acceptNone && normalized.equals("none"))) {
            return new CropParseResult(CropParseStatus.AUTO, null);
        }

        FarmCrop crop = FarmCrop.fromId(normalized);
        if (crop == null) {
            send(source, "§c[Bot] نوع المحصول غير معروف: §e" + cropArg + "§c! المحاصيل المتاحة:");
            send(source, "§ewheat §7(قمح), §ecarrot §7(جزر), §epotato §7(بطاطس), §ebeetroot §7(شمندر), §enether_wart §7(نيذر وارت), أو §eauto §7(تلقائي)");
            return new CropParseResult(CropParseStatus.INVALID, null);
        }

        return new CropParseResult(CropParseStatus.CROP, crop);
    }

    public static String formatCompactChests(List<BlockPos> chests) {
        if (chests == null || chests.isEmpty()) return "غير محدد";
        if (chests.size() == 1) {
            BlockPos p = chests.get(0);
            return p.getX() + "," + p.getY() + "," + p.getZ();
        }
        BlockPos first = chests.get(0);
        return chests.size() + " صناديق (الأول: " + first.getX() + "," + first.getY() + "," + first.getZ() + ")";
    }

    /**
     * إيجاد الحاوية التي ينظر إليها اللاعب تحت مؤشر التصويب أو أقرب حاوية في نطاق 3 بلوكات.
     */
    public static BlockPos resolveContainerFromLookOrNearby(
            Minecraft client,
            Predicate<BlockPos> shouldPreferSkip) {
        if (client == null || client.level == null || client.player == null) {
            return null;
        }
        ClientLevel level = client.level;

        if (client.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            BlockPos lookPos = bhr.getBlockPos();
            if (FarmAreaManager.isStorageContainerBlock(level.getBlockState(lookPos))) {
                return lookPos.immutable();
            }
        }

        BlockPos center = client.player.blockPosition();
        BlockPos bestPreferred = null;
        double bestPreferredDistSq = Double.MAX_VALUE;
        BlockPos bestAny = null;
        double bestAnyDistSq = Double.MAX_VALUE;

        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos check = center.offset(dx, dy, dz);
                    if (!FarmAreaManager.isStorageContainerBlock(level.getBlockState(check))) {
                        continue;
                    }
                    double distSq = client.player.distanceToSqr(check.getX() + 0.5, check.getY() + 0.5, check.getZ() + 0.5);
                    if (distSq < bestAnyDistSq) {
                        bestAnyDistSq = distSq;
                        bestAny = check.immutable();
                    }
                    if ((shouldPreferSkip == null || !shouldPreferSkip.test(check)) && distSq < bestPreferredDistSq) {
                        bestPreferredDistSq = distSq;
                        bestPreferred = check.immutable();
                    }
                }
            }
        }
        return bestPreferred != null ? bestPreferred : bestAny;
    }
}
