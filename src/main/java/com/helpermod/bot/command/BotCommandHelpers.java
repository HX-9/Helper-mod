package com.helpermod.bot.command;

import com.helpermod.bot.FarmArea;
import com.helpermod.bot.FarmAreaManager;
import com.helpermod.bot.FarmCrop;
import com.helpermod.bot.food.BotFoodManager;
import com.helpermod.bot.friend.BotFriendManager;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

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
        List<String> names = new ArrayList<>();
        for (FarmArea area : FarmAreaManager.getInstance().getAllAreas()) {
            names.add(area.getName());
        }
        return suggestMatching(names, builder);
    };

    public static final SuggestionProvider<FabricClientCommandSource> SELECTABLE_AREAS = (ctx, builder) -> {
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

    private BotCommandHelpers() {
    }

    private static CompletableFuture<Suggestions> suggestAreaStateTargets(
            boolean currentEnabledState,
            SuggestionsBuilder builder) {
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
}
