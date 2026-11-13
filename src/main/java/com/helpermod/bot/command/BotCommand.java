package com.helpermod.bot.command;

import com.helpermod.bot.BotGlobalManager;
import com.helpermod.bot.FarmArea;
import com.helpermod.bot.FarmAreaManager;
import com.helpermod.bot.FarmBotController;
import com.helpermod.bot.FarmCrop;
import com.helpermod.bot.FightBotController;
import com.helpermod.bot.food.BotFoodManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class BotCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(buildBotCommand());
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildBotCommand() {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal("bot")
            // /bot mode [on|off|status]
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("mode")
                .executes(BotCommand::executeBotModeStatus)
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on")
                    .executes(ctx -> executeSetBotMode(ctx, true)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off")
                    .executes(ctx -> executeSetBotMode(ctx, false)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status")
                    .executes(BotCommand::executeBotModeStatus)))
            // اختصارات سريعة ومباشرة: /bot on و /bot off و /bot stop
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on")
                .executes(ctx -> executeSetBotMode(ctx, true)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off")
                .executes(ctx -> executeSetBotMode(ctx, false)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stop")
                .executes(BotCommand::executeStopAll))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("farm")
                // /bot farm start
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("start")
                    .executes(BotCommand::executeStart))
                // /bot farm stop
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stop")
                    .executes(BotCommand::executeStop))
                // /bot farm status
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status")
                    .executes(BotCommand::executeStatus))
                // /bot farm sleep [on|off]
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("sleep")
                    .executes(BotCommand::executeToggleSleep)
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on")
                        .executes(ctx -> executeSetSleep(ctx, true)))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off")
                        .executes(ctx -> executeSetSleep(ctx, false))))
                // /bot farm bed [set|clear]
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("bed")
                    .executes(BotCommand::executeBedStatus)
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("set")
                        .executes(BotCommand::executeSetBed))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
                        .executes(BotCommand::executeClearBed)))
                // /bot farm fertilizer chest [set|clear]
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("fertilizer")
                    .executes(BotCommand::executeFertilizerChestStatus)
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("chest")
                        .executes(BotCommand::executeFertilizerChestStatus)
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("set")
                            .executes(BotCommand::executeSetFertilizerChest))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
                            .executes(BotCommand::executeClearFertilizerChest))))
                // /bot farm compost chest [set|clear] (مرادف)
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("compost")
                    .executes(BotCommand::executeFertilizerChestStatus)
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("chest")
                        .executes(BotCommand::executeFertilizerChestStatus)
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("set")
                            .executes(BotCommand::executeSetFertilizerChest))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
                            .executes(BotCommand::executeClearFertilizerChest))))
                // /bot farm area ...
                .then(buildAreaCommands())
            )
            // /bot defend [cautious|combat|off|stop|cancel|status] (أنماط الدفاع)
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("defend")
                .executes(BotCommand::executeFightStatus)
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("cautious")
                    .executes(ctx -> executeSetFightDefendMode(ctx, FightBotController.DefenseMode.CAUTIOUS)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("combat")
                    .executes(ctx -> executeSetFightDefendMode(ctx, FightBotController.DefenseMode.COMBAT)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on")
                    .executes(ctx -> executeSetFightDefendMode(ctx, FightBotController.DefenseMode.CAUTIOUS)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off")
                    .executes(BotCommand::executeDisableDefenseCompletely))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stop")
                    .executes(BotCommand::executeDisableDefenseCompletely))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("cancel")
                    .executes(BotCommand::executeDisableDefenseCompletely))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status")
                    .executes(BotCommand::executeFightStatus)))
            // /bot friend [add|remove|list] (أوامر الأصدقاء)
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("friend")
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
                    .executes(BotCommand::executeFriendAddListPrompt)
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            Minecraft client = Minecraft.getInstance();
                            if (client.getConnection() != null) {
                                for (var info : client.getConnection().getOnlinePlayers()) {
                                    if (info.getProfile() != null && info.getProfile().name() != null) {
                                        String name = info.getProfile().name();
                                        if (client.player == null || !name.equalsIgnoreCase(client.player.getScoreboardName())) {
                                            builder.suggest(name);
                                        }
                                    }
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(BotCommand::executeFriendAdd)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
                    .executes(BotCommand::executeFriendList)
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            for (String friend : com.helpermod.bot.friend.BotFriendManager.getInstance().getFriends()) {
                                builder.suggest(friend);
                            }
                            return builder.buildFuture();
                        })
                        .executes(BotCommand::executeFriendRemove)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
                    .executes(BotCommand::executeFriendList))
                .executes(BotCommand::executeFriendList))
            // /bot follow <name> / stop / status
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("follow")
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stop")
                    .executes(BotCommand::executeFollowStop))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status")
                    .executes(BotCommand::executeFollowStatus))
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .suggests((ctx, builder) -> {
                        Minecraft client = Minecraft.getInstance();
                        if (client.getConnection() != null) {
                            for (var info : client.getConnection().getOnlinePlayers()) {
                                if (info.getProfile() != null && info.getProfile().name() != null) {
                                    String name = info.getProfile().name();
                                    if (client.player == null || !name.equalsIgnoreCase(client.player.getScoreboardName())) {
                                        builder.suggest(name);
                                    }
                                }
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(BotCommand::executeFollowStart))
                .executes(BotCommand::executeFollowPrompt))
            // /bot food [add|clear|list|remove] (أوامر الأطعمة المسموحة)
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("food")
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("food_name", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            for (String foodKey : BotFoodManager.FOOD_NAMES.keySet()) {
                                builder.suggest(foodKey);
                            }
                            return builder.buildFuture();
                        })
                        .executes(BotCommand::executeFoodAdd)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
                    .executes(BotCommand::executeFoodClear))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("food_name", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            for (String food : BotFoodManager.getInstance().getAllowedFoods()) {
                                builder.suggest(food);
                            }
                            return builder.buildFuture();
                        })
                        .executes(BotCommand::executeFoodRemove)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
                    .executes(BotCommand::executeFoodList))
                .executes(BotCommand::executeFoodList))
            .executes(context -> {
                sendHelp(context.getSource());
                return 1;
            });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildAreaCommands() {
        var cropSuggestions = (com.mojang.brigadier.suggestion.SuggestionProvider<FabricClientCommandSource>) (ctx, builder) -> {
            builder.suggest("wheat");
            builder.suggest("carrot");
            builder.suggest("potato");
            builder.suggest("beetroot");
            builder.suggest("nether_wart");
            builder.suggest("auto");
            builder.suggest("قمح");
            builder.suggest("جزر");
            builder.suggest("بطاطس");
            builder.suggest("شمندر");
            builder.suggest("نيذر_وارت");
            builder.suggest("تلقائي");
            return builder.buildFuture();
        };

        var areaSuggestions = (com.mojang.brigadier.suggestion.SuggestionProvider<FabricClientCommandSource>) (ctx, builder) -> {
            for (FarmArea area : FarmAreaManager.getInstance().getAllAreas()) {
                builder.suggest(area.getName());
            }
            return builder.buildFuture();
        };

        return LiteralArgumentBuilder.<FabricClientCommandSource>literal("area")
            // pos1
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("pos1")
                .executes(BotCommand::executePos1))
            // pos2
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("pos2")
                .executes(BotCommand::executePos2))
            // create <name> [crop]
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("create")
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .executes(BotCommand::executeCreateArea)
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("crop", StringArgumentType.string())
                        .suggests(cropSuggestions)
                        .executes(BotCommand::executeCreateAreaWithCrop))))
            // add <name> <x1> <y1> <z1> <x2> <y2> <z2> [crop]
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("x1", IntegerArgumentType.integer())
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("y1", IntegerArgumentType.integer())
                            .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("z1", IntegerArgumentType.integer())
                                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("x2", IntegerArgumentType.integer())
                                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("y2", IntegerArgumentType.integer())
                                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("z2", IntegerArgumentType.integer())
                                            .executes(BotCommand::executeAddCoordinatesArea)
                                            .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("crop", StringArgumentType.string())
                                                .suggests(cropSuggestions)
                                                .executes(BotCommand::executeAddCoordinatesAreaWithCrop))))))))))
            // edit [farm] [name|crop] [value]
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("edit")
                .executes(BotCommand::executeEditGeneralHelp)
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("farm", StringArgumentType.string())
                    .suggests(areaSuggestions)
                    .executes(BotCommand::executeEditAreaHelp)
                    // edit <farm> name <new_name>
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("name")
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("new_name", StringArgumentType.string())
                            .executes(BotCommand::executeEditName)))
                    // edit <farm> crop <crop>
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("crop")
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("crop", StringArgumentType.string())
                            .suggests(cropSuggestions)
                            .executes(BotCommand::executeEditCrop)))
                    // edit <farm> chest [here|clear]
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("chest")
                        .executes(BotCommand::executeEditChestPrompt)
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("here")
                            .executes(BotCommand::executeEditChestHere))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
                            .executes(BotCommand::executeEditChestClear)))))
            // remove <name>
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .suggests(areaSuggestions)
                    .executes(BotCommand::executeRemoveArea)))
            // list
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
                .executes(BotCommand::executeListAreas))
            // select / select <name>
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("select")
                .executes(BotCommand::executeListSelectableAreas)
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .suggests((ctx, builder) -> {
                        builder.suggest("all");
                        builder.suggest("الكل");
                        for (FarmArea area : FarmAreaManager.getInstance().getAllAreas()) {
                            builder.suggest(area.getName());
                        }
                        return builder.buildFuture();
                    })
                    .executes(BotCommand::executeSelectArea)))
            // setcrop <name> <crop>
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("setcrop")
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .suggests(areaSuggestions)
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("crop", StringArgumentType.string())
                        .suggests(cropSuggestions)
                        .executes(BotCommand::executeSetCrop))));
    }

    private static int executeStart(CommandContext<FabricClientCommandSource> context) {
        FarmBotController.getInstance().start(Minecraft.getInstance());
        return 1;
    }

    private static int executeStop(CommandContext<FabricClientCommandSource> context) {
        FarmBotController.getInstance().stop(Minecraft.getInstance());
        return 1;
    }

    private static int executeStatus(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        FarmBotController controller = FarmBotController.getInstance();
        boolean running = controller.isRunning();
        source.sendFeedback(Component.literal("§6=== [حالة بوت الزراعة HelperMod] ==="));
        source.sendFeedback(Component.literal("§7الحالة الحالية: " + (running ? "§aشغال (يعمل الآن)" : "§cمتوقف")));
        String active = FarmAreaManager.getInstance().getActiveAreaName();
        source.sendFeedback(Component.literal("§7المنطقة المحددة: §e" + (active == null ? "جميع المناطق (دوري بالترتيب)" : active)));
        source.sendFeedback(Component.literal("§7عدد المناطق المحفوظة: §b" + FarmAreaManager.getInstance().getAllAreas().size()));
        boolean sleepOpt = FarmAreaManager.getInstance().isSleepAtNight();
        BlockPos bedPos = FarmAreaManager.getInstance().getBedPos();
        source.sendFeedback(Component.literal("§7النوم التلقائي عند الليل: " + (sleepOpt ? "§aمفعل (بعد 20 ثانية من حلول الليل)" : "§cمعطل")));
        source.sendFeedback(Component.literal("§7موقع السرير (Respawn Bed): §e" + (bedPos != null ? (bedPos.getX() + ", " + bedPos.getY() + ", " + bedPos.getZ()) : "تلقائي (البحث عن أقرب سرير)")));
        BlockPos fertChest = FarmAreaManager.getInstance().getFertilizerChestPos();
        source.sendFeedback(Component.literal("§7صندوق السماد (Bone Meal): §e" + (fertChest != null ? (fertChest.getX() + ", " + fertChest.getY() + ", " + fertChest.getZ()) : "غير محدد (/bot farm fertilizer chest set)")));
        if (running) {
            String currentAreaName = controller.getCurrentAreaName();
            if (currentAreaName != null) {
                source.sendFeedback(Component.literal("§7المزرعة قيد العمل: §e" + currentAreaName + " §8(رقم " + (controller.getCurrentAreaIndex() + 1) + " من " + controller.getTotalActiveAreas() + ")"));
            }
            source.sendFeedback(Component.literal("§7المرحلة الحالية: §b" + controller.getCurrentStage().getArabicName()));
            source.sendFeedback(Component.literal("§8ترتيب العمل: [1. الحصاد -> 2. تجميع المحصول -> 3. إصلاح الأرض -> 4. زراعة الأرض]"));
            if (controller.getNightTicks() > 0) {
                int sec = controller.getNightTicks() / 20;
                source.sendFeedback(Component.literal("§8[الليل حالياً: مضى عليه " + sec + " ثانية" + (sec >= 20 ? " - حان وقت النوم والتوجه للسرير فوراً" : "") + "]"));
            }
        }
        return 1;
    }

    private static int executePos1(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            BlockPos pos = client.player.blockPosition();
            FarmAreaManager.getInstance().setPos1(pos);
            context.getSource().sendFeedback(Component.literal("§a[Bot] تم تعيين النقطة الأولى (pos1) عند: §e" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()));
        }
        return 1;
    }

    private static int executePos2(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            BlockPos pos = client.player.blockPosition();
            FarmAreaManager.getInstance().setPos2(pos);
            context.getSource().sendFeedback(Component.literal("§a[Bot] تم تعيين النقطة الثانية (pos2) عند: §e" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()));
            context.getSource().sendFeedback(Component.literal("§7يمكنك الآن إنشاء المنطقة عبر الأمر: §b/bot farm area create <اسم_المنطقة>"));
        }
        return 1;
    }

    private static int executeCreateArea(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name").toLowerCase();
        BlockPos p1 = FarmAreaManager.getInstance().getPos1();
        BlockPos p2 = FarmAreaManager.getInstance().getPos2();

        if (p1 == null || p2 == null) {
            context.getSource().sendFeedback(Component.literal("§c[Bot] يجب تحديد pos1 و pos2 أولاً بالوقوف على النقطتين وكتابة:"));
            context.getSource().sendFeedback(Component.literal("§e/bot farm area pos1 §7ثم §e/bot farm area pos2"));
            return 0;
        }

        boolean added = FarmAreaManager.getInstance().addArea(name, p1, p2, null);
        if (added) {
            FarmArea area = FarmAreaManager.getInstance().getArea(name);
            BlockPos min = area.getMinPos();
            BlockPos max = area.getMaxPos();
            context.getSource().sendFeedback(Component.literal("§a[Bot] تم إنشاء المنطقة الزراعية §e" + name + "§a بنجاح!"));
            context.getSource().sendFeedback(Component.literal("§7النطاق: من (" + min.getX() + "," + min.getY() + "," + min.getZ() + ") إلى (" + max.getX() + "," + max.getY() + "," + max.getZ() + ")"));
            context.getSource().sendFeedback(Component.literal("§7المحصول: §fتلقائي (أي محصول متاح معك)."));
            context.getSource().sendFeedback(Component.literal("§7لتعديل المحصول أو الاسم مستقبلاً: §b/bot farm area edit " + name + " <name|crop> <القيمة>"));
        }
        return 1;
    }

    private static int executeCreateAreaWithCrop(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name").toLowerCase();
        String cropId = StringArgumentType.getString(context, "crop").toLowerCase();
        BlockPos p1 = FarmAreaManager.getInstance().getPos1();
        BlockPos p2 = FarmAreaManager.getInstance().getPos2();

        if (p1 == null || p2 == null) {
            context.getSource().sendFeedback(Component.literal("§c[Bot] يجب تحديد pos1 و pos2 أولاً بالوقوف على النقطتين وكتابة:"));
            context.getSource().sendFeedback(Component.literal("§e/bot farm area pos1 §7ثم §e/bot farm area pos2"));
            return 0;
        }

        FarmCrop crop = null;
        if (!cropId.equals("auto") && !cropId.equals("any") && !cropId.equals("تلقائي")) {
            crop = FarmCrop.fromId(cropId);
            if (crop == null) {
                context.getSource().sendFeedback(Component.literal("§c[Bot] نوع المحصول غير معروف! المحاصيل المتاحة:"));
                context.getSource().sendFeedback(Component.literal("§ewheat, carrot, potato, beetroot, nether_wart, auto"));
                return 0;
            }
        }

        boolean added = FarmAreaManager.getInstance().addArea(name, p1, p2, crop);
        if (added) {
            FarmArea area = FarmAreaManager.getInstance().getArea(name);
            BlockPos min = area.getMinPos();
            BlockPos max = area.getMaxPos();
            context.getSource().sendFeedback(Component.literal("§a[Bot] تم إنشاء المنطقة الزراعية §e" + name + "§a بنجاح!"));
            context.getSource().sendFeedback(Component.literal("§7النطاق: من (" + min.getX() + "," + min.getY() + "," + min.getZ() + ") إلى (" + max.getX() + "," + max.getY() + "," + max.getZ() + ")"));
            context.getSource().sendFeedback(Component.literal("§7المحصول المحدد: §e" + area.getCropDisplayName()));
            context.getSource().sendFeedback(Component.literal("§7لتعديل المزرعة مستقبلاً: §b/bot farm area edit " + name + " <name|crop> <القيمة>"));
        }
        return 1;
    }

    private static int executeAddCoordinatesArea(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name").toLowerCase();
        int x1 = IntegerArgumentType.getInteger(context, "x1");
        int y1 = IntegerArgumentType.getInteger(context, "y1");
        int z1 = IntegerArgumentType.getInteger(context, "z1");
        int x2 = IntegerArgumentType.getInteger(context, "x2");
        int y2 = IntegerArgumentType.getInteger(context, "y2");
        int z2 = IntegerArgumentType.getInteger(context, "z2");

        BlockPos p1 = new BlockPos(x1, y1, z1);
        BlockPos p2 = new BlockPos(x2, y2, z2);

        FarmAreaManager.getInstance().addArea(name, p1, p2, null);
        context.getSource().sendFeedback(Component.literal("§a[Bot] تم إضافة المنطقة §e" + name + "§a بالإحداثيات المحددة بنجاح!"));
        return 1;
    }

    private static int executeAddCoordinatesAreaWithCrop(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name").toLowerCase();
        int x1 = IntegerArgumentType.getInteger(context, "x1");
        int y1 = IntegerArgumentType.getInteger(context, "y1");
        int z1 = IntegerArgumentType.getInteger(context, "z1");
        int x2 = IntegerArgumentType.getInteger(context, "x2");
        int y2 = IntegerArgumentType.getInteger(context, "y2");
        int z2 = IntegerArgumentType.getInteger(context, "z2");
        String cropId = StringArgumentType.getString(context, "crop").toLowerCase();

        FarmCrop crop = null;
        if (!cropId.equals("auto") && !cropId.equals("any") && !cropId.equals("تلقائي")) {
            crop = FarmCrop.fromId(cropId);
        }

        BlockPos p1 = new BlockPos(x1, y1, z1);
        BlockPos p2 = new BlockPos(x2, y2, z2);

        FarmAreaManager.getInstance().addArea(name, p1, p2, crop);
        context.getSource().sendFeedback(Component.literal("§a[Bot] تم إضافة المنطقة §e" + name + "§a بنجاح! المحصول: §e" + (crop != null ? crop.getArabicName() : "تلقائي")));
        return 1;
    }

    private static int executeRemoveArea(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name").toLowerCase();
        boolean removed = FarmAreaManager.getInstance().removeArea(name);
        if (removed) {
            context.getSource().sendFeedback(Component.literal("§a[Bot] تم حذف المنطقة §e" + name + "§a بنجاح."));
        } else {
            context.getSource().sendFeedback(Component.literal("§c[Bot] المنطقة §e" + name + "§c غير موجودة!"));
        }
        return 1;
    }

    private static int executeListAreas(CommandContext<FabricClientCommandSource> context) {
        Collection<FarmArea> areas = FarmAreaManager.getInstance().getAllAreas();
        if (areas.isEmpty()) {
            context.getSource().sendFeedback(Component.literal("§e[Bot] لا توجد أي مناطق زراعية مسجلة حالياً."));
            return 1;
        }

        context.getSource().sendFeedback(Component.literal("§6=== [قائمة المناطق الزراعية] ==="));
        for (FarmArea area : areas) {
            BlockPos min = area.getMinPos();
            BlockPos max = area.getMaxPos();
            String coords = String.format("(%d,%d,%d) إلى (%d,%d,%d)", min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
            String chestInfo = area.getChestPos() != null ? (" §6[صندوق: " + area.getChestPos().getX() + "," + area.getChestPos().getY() + "," + area.getChestPos().getZ() + "]") : "";
            context.getSource().sendFeedback(Component.literal("§b• " + area.getName() + " §7[" + coords + "] - المحصول: §e" + area.getCropDisplayName() + chestInfo));
        }
        return 1;
    }

    private static int executeListSelectableAreas(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Collection<FarmArea> areas = FarmAreaManager.getInstance().getAllAreas();
        if (areas.isEmpty()) {
            source.sendFeedback(Component.literal("§e[Bot] لا توجد أي مزارع مسجلة حالياً للاختيار منها."));
            source.sendFeedback(Component.literal("§7أنشئ مزرعة جديدة عبر: §b/bot farm area pos1 §7ثم §bpos2 §7ثم §bcreate <اسم>"));
            return 1;
        }

        String active = FarmAreaManager.getInstance().getActiveAreaName();
        source.sendFeedback(Component.literal("§6=== [المزارع المتاحة للاختيار (select)] ==="));
        for (FarmArea area : areas) {
            boolean isCur = area.getName().equalsIgnoreCase(active);
            source.sendFeedback(Component.literal((isCur ? "§a► " : "§7• ") + "§b" + area.getName() + " §7(المحصول: §e" + area.getCropDisplayName() + "§7)" + (isCur ? " §a[المحددة حالياً]" : "")));
        }
        source.sendFeedback(Component.literal("§7الوضع الحالي: §e" + (active == null ? "جميع المزارع (all)" : active)));
        source.sendFeedback(Component.literal("§7لتحديد مزرعة اكتب: §b/bot farm area select <الاسم> §7أو §b/bot farm area select all"));
        return 1;
    }

    private static int executeSelectArea(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name").toLowerCase();
        if (name.equalsIgnoreCase("all") || name.equalsIgnoreCase("الكل")) {
            FarmAreaManager.getInstance().setActiveAreaName(null);
            context.getSource().sendFeedback(Component.literal("§a[Bot] تم ضبط البوت للعمل في §eجميع المناطق§a."));
            return 1;
        }

        FarmArea area = FarmAreaManager.getInstance().getArea(name);
        if (area == null) {
            context.getSource().sendFeedback(Component.literal("§c[Bot] لا توجد منطقة بهذا الاسم: §e" + name));
            executeListSelectableAreas(context);
            return 0;
        }

        FarmAreaManager.getInstance().setActiveAreaName(name);
        context.getSource().sendFeedback(Component.literal("§a[Bot] تم تحديد المنطقة النشطة: §e" + name + " §7(المحصول: §e" + area.getCropDisplayName() + "§7)"));
        return 1;
    }

    private static int executeSetCrop(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name").toLowerCase();
        String cropId = StringArgumentType.getString(context, "crop").toLowerCase();

        FarmArea area = FarmAreaManager.getInstance().getArea(name);
        if (area == null) {
            context.getSource().sendFeedback(Component.literal("§c[Bot] لا توجد منطقة بهذا الاسم: §e" + name));
            return 0;
        }

        if (cropId.equals("auto") || cropId.equals("تلقائي") || cropId.equals("none")) {
            area.setTargetCrop(null);
            FarmAreaManager.getInstance().save();
            context.getSource().sendFeedback(Component.literal("§a[Bot] تم تعيين المنطقة §e" + name + "§a لتعمل بنمط: §bتلقائي (إعادة زراعة نفس المحصول السابق)."));
            return 1;
        }

        FarmCrop crop = FarmCrop.fromId(cropId);
        if (crop == null) {
            context.getSource().sendFeedback(Component.literal("§c[Bot] نوع المحصول غير معروف! المحاصيل المتاحة:"));
            context.getSource().sendFeedback(Component.literal("§ewheat §7(قمح), §ecarrot §7(جزر), §epotato §7(بطاطس), §ebeetroot §7(شمندر), §enether_wart §7(نيذر وارت), أو §eauto §7(تلقائي)"));
            return 0;
        }

        area.setTargetCrop(crop);
        FarmAreaManager.getInstance().save();
        context.getSource().sendFeedback(Component.literal("§a[Bot] تم تعيين محصول المنطقة §e" + name + "§a إلى: §e" + crop.getArabicName() + " (" + crop.getId() + ")"));
        return 1;
    }

    private static int executeEditGeneralHelp(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Component.literal("§6=== [تعديل المزارع المحفوظة] ==="));
        source.sendFeedback(Component.literal("§7لتغيير اسم المزرعة: §b/bot farm area edit <اسم_المزرعة> name <الاسم_الجديد>"));
        source.sendFeedback(Component.literal("§7لتغيير محصول المزرعة: §b/bot farm area edit <اسم_المزرعة> crop <المحصول|auto>"));
        source.sendFeedback(Component.literal("§7لربط صندوق المحصول: §b/bot farm area edit <اسم_المزرعة> chest §7(ثم فتح الصندوق لربطه)"));
        executeListAreas(context);
        return 1;
    }

    private static int executeEditAreaHelp(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String farmName = StringArgumentType.getString(context, "farm").toLowerCase();
        FarmArea area = FarmAreaManager.getInstance().getArea(farmName);
        if (area == null) {
            source.sendFeedback(Component.literal("§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName));
            return 0;
        }
        source.sendFeedback(Component.literal("§6=== [خيارات تعديل المزرعة: §e" + farmName + "§6] ==="));
        source.sendFeedback(Component.literal("§7المحصول الحالي: §e" + area.getCropDisplayName()));
        BlockPos chest = area.getChestPos();
        source.sendFeedback(Component.literal("§7صندوق المحصول: §e" + (chest != null ? (chest.getX() + ", " + chest.getY() + ", " + chest.getZ()) : "غير محدد (بدون صندوق)")));
        source.sendFeedback(Component.literal("§71. تغيير الاسم: §b/bot farm area edit " + farmName + " name <الاسم_الجديد>"));
        source.sendFeedback(Component.literal("§72. تغيير المحصول: §b/bot farm area edit " + farmName + " crop <wheat|carrot|potato|beetroot|nether_wart|auto>"));
        source.sendFeedback(Component.literal("§73. ربط صندوق: §b/bot farm area edit " + farmName + " chest §7(افتح الصندوق بعدها) أو §bclear §7للإلغاء"));
        return 1;
    }

    private static int executeEditName(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String oldName = StringArgumentType.getString(context, "farm").toLowerCase();
        String newName = StringArgumentType.getString(context, "new_name").toLowerCase();

        if (oldName.equals(newName)) {
            source.sendFeedback(Component.literal("§e[Bot] الاسم الجديد مطابق للاسم الحالي للمزرعة!"));
            return 1;
        }

        FarmArea area = FarmAreaManager.getInstance().getArea(oldName);
        if (area == null) {
            source.sendFeedback(Component.literal("§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + oldName));
            return 0;
        }

        if (FarmAreaManager.getInstance().getArea(newName) != null) {
            source.sendFeedback(Component.literal("§c[Bot] يوجد بالفعل مزرعة أخرى تحمل الاسم: §e" + newName));
            return 0;
        }

        boolean renamed = FarmAreaManager.getInstance().renameArea(oldName, newName);
        if (renamed) {
            source.sendFeedback(Component.literal("§a[Bot] تم تغيير اسم المزرعة من §e" + oldName + "§a إلى §e" + newName + "§a بنجاح!"));
        } else {
            source.sendFeedback(Component.literal("§c[Bot] تعذر تغيير اسم المزرعة."));
        }
        return 1;
    }

    private static int executeEditCrop(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String farmName = StringArgumentType.getString(context, "farm").toLowerCase();
        String cropId = StringArgumentType.getString(context, "crop").toLowerCase();

        FarmArea area = FarmAreaManager.getInstance().getArea(farmName);
        if (area == null) {
            source.sendFeedback(Component.literal("§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName));
            return 0;
        }

        if (cropId.equals("auto") || cropId.equals("any") || cropId.equals("تلقائي") || cropId.equals("none")) {
            area.setTargetCrop(null);
            FarmAreaManager.getInstance().save();
            source.sendFeedback(Component.literal("§a[Bot] تم تعديل محصول المزرعة §e" + farmName + "§a إلى نمط: §bتلقائي (أي محصول متاح معك)."));
            return 1;
        }

        FarmCrop crop = FarmCrop.fromId(cropId);
        if (crop == null) {
            source.sendFeedback(Component.literal("§c[Bot] نوع المحصول غير معروف! المحاصيل المتاحة:"));
            source.sendFeedback(Component.literal("§ewheat §7(قمح), §ecarrot §7(جزر), §epotato §7(بطاطس), §ebeetroot §7(شمندر), §enether_wart §7(نيذر وارت), أو §eauto §7(تلقائي)"));
            return 0;
        }

        area.setTargetCrop(crop);
        FarmAreaManager.getInstance().save();
        source.sendFeedback(Component.literal("§a[Bot] تم تعديل محصول المزرعة §e" + farmName + "§a إلى: §e" + crop.getArabicName() + " (" + crop.getId() + ")"));
        return 1;
    }

    private static int executeEditChestPrompt(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String farmName = StringArgumentType.getString(context, "farm").toLowerCase();
        FarmArea area = FarmAreaManager.getInstance().getArea(farmName);
        if (area == null) {
            source.sendFeedback(Component.literal("§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName));
            return 0;
        }

        FarmAreaManager.getInstance().setAwaitingChestFarm(farmName);
        source.sendFeedback(Component.literal("§e[Bot] تم تفعيل وضع ربط الصندوق لمزرعة §a" + farmName + "§e!"));
        source.sendFeedback(Component.literal("§6الآن افتح (اضغط كليك يمين على) الصندوق الذي تريد تخزين محصول هذه المزرعة فيه ليتم ربطه تلقائياً."));
        return 1;
    }

    private static int executeEditChestHere(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String farmName = StringArgumentType.getString(context, "farm").toLowerCase();
        FarmArea area = FarmAreaManager.getInstance().getArea(farmName);
        if (area == null) {
            source.sendFeedback(Component.literal("§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName));
            return 0;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            BlockPos playerPos = client.player.blockPosition();
            BlockPos targetChest = null;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos check = playerPos.offset(dx, dy, dz);
                        if (client.level != null) {
                            var state = client.level.getBlockState(check);
                            if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                                || state.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock
                                || state.getBlock() instanceof net.minecraft.world.level.block.EnderChestBlock
                                || state.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
                                targetChest = check;
                                break;
                            }
                        }
                    }
                    if (targetChest != null) break;
                }
                if (targetChest != null) break;
            }

            if (targetChest == null) {
                targetChest = playerPos;
            }

            area.setChestPos(targetChest);
            FarmAreaManager.getInstance().save();
            source.sendFeedback(Component.literal("§a[Bot] تم تعيين صندوق مزرعة §e" + farmName + "§a عند: §e" + targetChest.getX() + ", " + targetChest.getY() + ", " + targetChest.getZ()));
            return 1;
        }
        return 0;
    }

    private static int executeEditChestClear(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String farmName = StringArgumentType.getString(context, "farm").toLowerCase();
        FarmArea area = FarmAreaManager.getInstance().getArea(farmName);
        if (area == null) {
            source.sendFeedback(Component.literal("§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName));
            return 0;
        }

        area.setChestPos(null);
        FarmAreaManager.getInstance().save();
        source.sendFeedback(Component.literal("§a[Bot] تم إلغاء ربط الصندوق بمزرعة §e" + farmName + "§a والعودة للوضع الافتراضي (بدون صندوق)."));
        return 1;
    }

    private static int executeToggleSleep(CommandContext<FabricClientCommandSource> context) {
        boolean next = !FarmAreaManager.getInstance().isSleepAtNight();
        FarmAreaManager.getInstance().setSleepAtNight(next);
        context.getSource().sendFeedback(Component.literal("§a[Bot] تم " + (next ? "§aتفعيل" : "§cتعطيل") + "§a ميزة النوم التلقائي عند حلول الليل."));
        return 1;
    }

    private static int executeSetSleep(CommandContext<FabricClientCommandSource> context, boolean enable) {
        FarmAreaManager.getInstance().setSleepAtNight(enable);
        context.getSource().sendFeedback(Component.literal("§a[Bot] تم " + (enable ? "§aتفعيل" : "§cتعطيل") + "§a ميزة النوم التلقائي عند حلول الليل."));
        return 1;
    }

    private static int executeBedStatus(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BlockPos bedPos = FarmAreaManager.getInstance().getBedPos();
        if (bedPos != null) {
            source.sendFeedback(Component.literal("§a[Bot] السرير المعتمد لنقطة الإحياء محدد عند: §e" + bedPos.getX() + ", " + bedPos.getY() + ", " + bedPos.getZ()));
        } else {
            source.sendFeedback(Component.literal("§e[Bot] لم يتم تحديد سرير مخصص يدوياً (سيبحث البوت تلقائياً عن أقرب سرير عند حلول الليل)."));
            source.sendFeedback(Component.literal("§7لتحديد سريرك الحالي: قف عند السرير واكتب: §b/bot farm bed set"));
        }
        return 1;
    }

    private static int executeSetBed(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            BlockPos playerPos = client.player.blockPosition();
            BlockPos targetBed = null;

            // فحص المحيط القريب للبحث عن السرير الفعلي بدقة
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos check = playerPos.offset(dx, dy, dz);
                        if (client.level != null) {
                            var state = client.level.getBlockState(check);
                            if (state.getBlock() instanceof net.minecraft.world.level.block.BedBlock || state.is(net.minecraft.tags.BlockTags.BEDS)) {
                                targetBed = check;
                                break;
                            }
                        }
                    }
                    if (targetBed != null) break;
                }
                if (targetBed != null) break;
            }

            if (targetBed == null) {
                targetBed = playerPos;
            }

            FarmAreaManager.getInstance().setBedPos(targetBed);
            source.sendFeedback(Component.literal("§a[Bot] تم تعيين سرير نقطة الإحياء بنجاح عند: §e" + targetBed.getX() + ", " + targetBed.getY() + ", " + targetBed.getZ()));
            return 1;
        }
        return 0;
    }

    private static int executeClearBed(CommandContext<FabricClientCommandSource> context) {
        FarmAreaManager.getInstance().setBedPos(null);
        context.getSource().sendFeedback(Component.literal("§a[Bot] تم إلغاء السرير المخصص والعودة للبحث التلقائي عن أقرب سرير."));
        return 1;
    }

    private static int executeFertilizerChestStatus(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BlockPos chestPos = FarmAreaManager.getInstance().getFertilizerChestPos();
        if (chestPos != null) {
            source.sendFeedback(Component.literal("§a[Bot] صندوق السماد (Bone Meal) محدد عند: §e" + chestPos.getX() + ", " + chestPos.getY() + ", " + chestPos.getZ()));
        } else {
            source.sendFeedback(Component.literal("§e[Bot] لم يتم تحديد صندوق مخصص لوضع السماد فيه بعد!"));
            source.sendFeedback(Component.literal("§7لتحديد الصندوق: قف بجانبه واكتب: §b/bot farm fertilizer chest set §7أو انقر عليه كليك يمين بعد كتابة الأمر."));
        }
        return 1;
    }

    private static int executeSetFertilizerChest(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            BlockPos playerPos = client.player.blockPosition();
            BlockPos targetChest = null;

            // فحص المحيط القريب للبحث عن صندوق أو حاوية مجاورة
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -1; dy <= 2; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        BlockPos check = playerPos.offset(dx, dy, dz);
                        if (client.level != null) {
                            var state = client.level.getBlockState(check);
                            if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                                || state.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock
                                || state.getBlock() instanceof net.minecraft.world.level.block.EnderChestBlock
                                || state.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
                                targetChest = check;
                                break;
                            }
                        }
                    }
                    if (targetChest != null) break;
                }
                if (targetChest != null) break;
            }

            if (targetChest != null) {
                FarmAreaManager.getInstance().setFertilizerChestPos(targetChest);
                FarmAreaManager.getInstance().setAwaitingFertilizerChest(false);
                source.sendFeedback(Component.literal("§a[Bot] تم تعيين صندوق السماد (Bone Meal) بنجاح عند: §e" + targetChest.getX() + ", " + targetChest.getY() + ", " + targetChest.getZ()));
                return 1;
            } else {
                // تفعيل وضع الربط بالنقر المباشر
                FarmAreaManager.getInstance().setAwaitingFertilizerChest(true);
                source.sendFeedback(Component.literal("§e[Bot] تم تفعيل وضع ربط صندوق السماد!"));
                source.sendFeedback(Component.literal("§6الآن افتح (اضغط كليك يمين على) الصندوق الذي تريد تخزين السماد فيه ليتم ربطه تلقائياً."));
                return 1;
            }
        }
        return 0;
    }

    private static int executeClearFertilizerChest(CommandContext<FabricClientCommandSource> context) {
        FarmAreaManager.getInstance().setFertilizerChestPos(null);
        FarmAreaManager.getInstance().setAwaitingFertilizerChest(false);
        context.getSource().sendFeedback(Component.literal("§a[Bot] تم إلغاء تحديد صندوق السماد بنجاح."));
        return 1;
    }

    private static int executeSetBotMode(CommandContext<FabricClientCommandSource> context, boolean enabled) {
        Minecraft client = Minecraft.getInstance();
        BotGlobalManager.getInstance().setBotEnabled(client, enabled);
        return 1;
    }

    private static int executeBotModeStatus(CommandContext<FabricClientCommandSource> context) {
        boolean enabled = BotGlobalManager.getInstance().isBotEnabled();
        FightBotController.DefenseMode defMode = FightBotController.getInstance().getDefenseMode();
        boolean isFollowing = com.helpermod.bot.BotFollowController.getInstance().isFollowing();
        boolean isFarming = FarmBotController.getInstance().isFarming();

        context.getSource().sendFeedback(Component.literal("§6==== [حالة نظام البوت والمود] ===="));
        context.getSource().sendFeedback(Component.literal("§7- الوضع العام (Bot Mode): " + (enabled ? "§aON (مفعّل)" : "§cOFF (معطل بالكامل في المطلق)")));
        context.getSource().sendFeedback(Component.literal("§7- وضع الدفاع (Defend): §e" + defMode.getDisplayName()));
        context.getSource().sendFeedback(Component.literal("§7- وضع الاتباع (Follow): " + (isFollowing ? "§aمفعّل (يتبع: " + com.helpermod.bot.BotFollowController.getInstance().getTargetPlayerName() + ")" : "§7غير مفعّل")));
        context.getSource().sendFeedback(Component.literal("§7- وضع المزرعة (Farm): " + (isFarming ? "§aيعمل حالياً" : "§7متوقف")));
        context.getSource().sendFeedback(Component.literal("§eللتحكم العام: §f/bot mode on §7أو §f/bot mode off §7أو §f/bot stop"));
        return 1;
    }

    private static int executeStopAll(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        BotGlobalManager.getInstance().stopAll(client);
        context.getSource().sendFeedback(Component.literal("§e[Bot] تم إيقاف جميع أنشطة البوت الجارية وتحرير جميع مفاتيح التحكم فوراً!"));
        return 1;
    }

    private static int executeDisableDefenseCompletely(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        FightBotController.getInstance().disableDefenseCompletely(client);
        context.getSource().sendFeedback(Component.literal("§c[Bot] تم إلغاء وضع الدفاع في المطلق بنجاح."));
        return 1;
    }

    private static int executeSetFightDefendMode(CommandContext<FabricClientCommandSource> context, FightBotController.DefenseMode mode) {
        Minecraft client = Minecraft.getInstance();
        FightBotController.getInstance().setDefenseMode(client, mode);
        return 1;
    }

    private static int executeFightStatus(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        FightBotController.DefenseMode mode = FightBotController.getInstance().getDefenseMode();
        Minecraft client = Minecraft.getInstance();
        boolean hasShield = false;
        boolean hasHoeInOffhand = false;
        if (client.player != null) {
            hasShield = FightBotController.getInstance().hasShieldInInventory(client.player);
            hasHoeInOffhand = client.player.getOffhandItem().getItem() instanceof net.minecraft.world.item.HoeItem;
        }

        source.sendFeedback(Component.literal("§6=== [حالة وضع الدفاع - HelperMod] ==="));
        String modeDesc;
        if (mode == FightBotController.DefenseMode.CAUTIOUS) {
            modeDesc = "§6نمط الحذر (مراقبة وصد بالدرع وتراجع عند الخطر)";
        } else if (mode == FightBotController.DefenseMode.COMBAT) {
            modeDesc = "§4نمط القتال (حماية وهجوم وقتل الوحوش والهروب من الكريبر)";
        } else {
            modeDesc = "§cمعطل";
        }
        source.sendFeedback(Component.literal("§7النمط الحالي للدفاع: " + modeDesc));
        source.sendFeedback(Component.literal("§7الفأس الزراعي في اليد اليسرى: " + (hasHoeInOffhand ? "§aموجود ومجهز للزراعة" : "§eغير مجهز")));
        source.sendFeedback(Component.literal("§7الدرع المخصص للدفاع: " + (hasShield ? "§aمتوفر في الحقيبة/اليد وجاهز للتبديل الفوري عند الخطر" : "§cغير متوفر (يرجى وضع درع في الحقيبة للتبديل الفوري)")));
        source.sendFeedback(Component.literal("§eالأنماط المتاحة:"));
        source.sendFeedback(Component.literal("  §a/bot defend cautious §7- نمط الحذر (مراقبة وصد وتراجع فقط)"));
        source.sendFeedback(Component.literal("  §c/bot defend combat   §7- نمط القتال (هجوم وقتل الوحوش والهروب من الكريبر)"));
        source.sendFeedback(Component.literal("  §7/bot defend off      §7- إيقاف الدفاع بالكامل"));
        return 1;
    }

    private static int executeFriendAddListPrompt(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Minecraft client = Minecraft.getInstance();
        java.util.List<String> onlineNames = new java.util.ArrayList<>();
        if (client.getConnection() != null) {
            for (var info : client.getConnection().getOnlinePlayers()) {
                if (info.getProfile() != null && info.getProfile().name() != null) {
                    String name = info.getProfile().name();
                    if (client.player == null || !name.equalsIgnoreCase(client.player.getScoreboardName())) {
                        onlineNames.add(name);
                    }
                }
            }
        }

        if (onlineNames.isEmpty()) {
            source.sendFeedback(Component.literal("§e[Bot] لا يوجد لاعبون آخرون متصلون في السيرفر حالياً!"));
            source.sendFeedback(Component.literal("§7يمكنك كتابة الاسم يدوياً عبر: §b/bot friend add <name>"));
        } else {
            source.sendFeedback(Component.literal("§6=== [قائمة اللاعبين المتصلين في السيرفر للاختيار] ==="));
            source.sendFeedback(Component.literal("§a" + String.join("§7, §a", onlineNames)));
            source.sendFeedback(Component.literal("§7اضغط Tab بعد الأمر لاختيار الاسم تلقائياً أو اكتب: §b/bot friend add <name>"));
        }
        return 1;
    }

    private static int executeFriendAdd(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name");
        boolean added = com.helpermod.bot.friend.BotFriendManager.getInstance().addFriend(name);
        if (added) {
            context.getSource().sendFeedback(Component.literal("§a[Bot] تم إضافة §e" + name + "§a كصديق للبوت! لن يتم الحذر أو الدفاع ضده إطلاقاً."));
        } else {
            context.getSource().sendFeedback(Component.literal("§e[Bot] اللاعب §f" + name + "§e مضاف بالفعل كصديق مسبقاً."));
        }
        return 1;
    }

    private static int executeFriendRemove(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name");
        boolean removed = com.helpermod.bot.friend.BotFriendManager.getInstance().removeFriend(name);
        if (removed) {
            context.getSource().sendFeedback(Component.literal("§c[Bot] تم إزالة §e" + name + "§c من قائمة الأصدقاء."));
        } else {
            context.getSource().sendFeedback(Component.literal("§e[Bot] اللاعب §f" + name + "§e ليس موجوداً في قائمة الأصدقاء."));
        }
        return 1;
    }

    private static int executeFriendList(CommandContext<FabricClientCommandSource> context) {
        Collection<String> friends = com.helpermod.bot.friend.BotFriendManager.getInstance().getFriends();
        if (friends.isEmpty()) {
            context.getSource().sendFeedback(Component.literal("§e[Bot] قائمة الأصدقاء فارغة حالياً. أضف صديقاً عبر: §b/bot friend add <name>"));
        } else {
            context.getSource().sendFeedback(Component.literal("§a[Bot] قائمة الأصدقاء الحالية (" + friends.size() + "): §e" + String.join("§7, §e", friends)));
        }
        return 1;
    }

    private static int executeFollowStart(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name");
        Minecraft client = Minecraft.getInstance();
        com.helpermod.bot.BotFollowController.getInstance().startFollowing(client, name);
        return 1;
    }

    private static int executeFollowStop(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        com.helpermod.bot.BotFollowController.getInstance().stopFollowing(client);
        return 1;
    }

    private static int executeFollowStatus(CommandContext<FabricClientCommandSource> context) {
        com.helpermod.bot.BotFollowController controller = com.helpermod.bot.BotFollowController.getInstance();
        if (controller.isFollowing()) {
            context.getSource().sendFeedback(Component.literal("§a[Bot] وضع الاتباع نشط حالياً يتبع اللاعب: §e" + controller.getTargetPlayerName() + "§a (خلفه في اليمين على بعد 3 بلوكات وبمسار آمن)."));
        } else {
            context.getSource().sendFeedback(Component.literal("§7[Bot] وضع الاتباع غير نشط حالياً. للبدء اكتب: §b/bot follow <name>"));
        }
        return 1;
    }

    private static int executeFollowPrompt(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Minecraft client = Minecraft.getInstance();
        List<String> onlineNames = new ArrayList<>();
        if (client.getConnection() != null) {
            for (var info : client.getConnection().getOnlinePlayers()) {
                if (info.getProfile() != null && info.getProfile().name() != null) {
                    String name = info.getProfile().name();
                    if (client.player == null || !name.equalsIgnoreCase(client.player.getScoreboardName())) {
                        onlineNames.add(name);
                    }
                }
            }
        }

        if (onlineNames.isEmpty()) {
            source.sendFeedback(Component.literal("§e[Bot] لا يوجد لاعبون آخرون متصلون في السيرفر حالياً!"));
            source.sendFeedback(Component.literal("§7للاتباع اكتب: §b/bot follow <اسم_اللاعب>"));
        } else {
            source.sendFeedback(Component.literal("§6=== [قائمة اللاعبين المتاحين للاتباع] ==="));
            source.sendFeedback(Component.literal("§a" + String.join("§7, §a", onlineNames)));
            source.sendFeedback(Component.literal("§7اضغط Tab بعد الأمر لاختيار الاسم تلقائياً أو اكتب: §b/bot follow <name>"));
            source.sendFeedback(Component.literal("§7للإيقاف اكتب: §c/bot follow stop"));
        }
        return 1;
    }

    private static int executeFoodAdd(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "food_name");
        boolean added = BotFoodManager.getInstance().addFood(name);
        String display = BotFoodManager.getInstance().getDisplayName(name);
        if (added) {
            context.getSource().sendFeedback(Component.literal("§a[Bot - Food] تم بنجاح إضافة طعام: §e" + display + " §aإلى قائمة الأطعمة المسموحة!"));
        } else {
            context.getSource().sendFeedback(Component.literal("§e[Bot - Food] الطعام: §6" + display + " §eموجود بالفعل في قائمة الأطعمة المسموحة."));
        }
        return 1;
    }

    private static int executeFoodClear(CommandContext<FabricClientCommandSource> context) {
        BotFoodManager.getInstance().clearFoods();
        context.getSource().sendFeedback(Component.literal("§c[Bot - Food] تم تفريغ قائمة الأطعمة المسموحة بالكامل! لن يتناول البوت أي طعام حتى تضيف أطعمة مسموحة عبر: §e/bot food add <اسم>"));
        return 1;
    }

    private static int executeFoodRemove(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "food_name");
        boolean removed = BotFoodManager.getInstance().removeFood(name);
        if (removed) {
            context.getSource().sendFeedback(Component.literal("§e[Bot - Food] تم حذف: §6" + name + " §eمن قائمة الأطعمة المسموحة."));
        } else {
            context.getSource().sendFeedback(Component.literal("§c[Bot - Food] لم يتم العثور على: §e" + name + " §cفي قائمة الأطعمة المسموحة."));
        }
        return 1;
    }

    private static int executeFoodList(CommandContext<FabricClientCommandSource> context) {
        Collection<String> foods = BotFoodManager.getInstance().getAllowedFoods();
        if (foods.isEmpty()) {
            context.getSource().sendFeedback(Component.literal("§c[Bot - Food] قائمة الأطعمة المسموحة فارغة حالياً! أضف أطعمة باستخدام: §e/bot food add <اسم_الطعام>"));
            return 1;
        }

        context.getSource().sendFeedback(Component.literal("§a=== قائمة الأطعمة المسموحة للبوت (" + foods.size() + ") ==="));
        for (String food : foods) {
            String display = BotFoodManager.getInstance().getDisplayName(food);
            context.getSource().sendFeedback(Component.literal(" §7- §e" + display));
        }
        return 1;
    }

    private static void sendHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("§6=== [أوامر المساعد الآلي - HelperMod] ==="));
        source.sendFeedback(Component.literal("§6/bot mode [on|off|status] §7- تشغيل أو إيقاف المود بالكامل في المطلق (Bot Mode)"));
        source.sendFeedback(Component.literal("§6/bot on §7| §6/bot off §7- تشغيل/إيقاف وضع البوت العام سريعاً"));
        source.sendFeedback(Component.literal("§6/bot stop §7- إيقاف جميع الأنشطة الحالية للبوت فوراً وتحرير الحركة"));
        source.sendFeedback(Component.literal("§b/bot follow <name> §7- اتباع لاعب بمسار آمن والتمركز خلفه في اليمين بـ 3 بلوكات"));
        source.sendFeedback(Component.literal("§b/bot follow stop §7- إيقاف وضع الاتباع"));
        source.sendFeedback(Component.literal("§a/bot defend [cautious|combat|off] §7- أنماط الحماية والدفاع (حذر / قتال / إلغاء في المطلق)"));
        source.sendFeedback(Component.literal("§a/bot friend add <name> §7- إضافة لاعب كصديق لمنع الحذر والدفاع ضده"));
        source.sendFeedback(Component.literal("§a/bot friend remove <name> §7- إزالة لاعب من قائمة الأصدقاء"));
        source.sendFeedback(Component.literal("§a/bot friend list §7- عرض جميع الأصدقاء المحفوظين"));
        source.sendFeedback(Component.literal("§d/bot food add <name> §7- إضافة نوع طعام إلى قائمة الأطعمة المسموحة للبوت"));
        source.sendFeedback(Component.literal("§d/bot food clear §7- تفريغ قائمة الأطعمة المسموحة بالكامل"));
        source.sendFeedback(Component.literal("§d/bot food list §7- عرض قائمة الأطعمة المسموحة حالياً"));
        source.sendFeedback(Component.literal("§e/bot farm start §7- تشغيل البوت لبدء الحصاد وإعادة الزراعة"));
        source.sendFeedback(Component.literal("§e/bot farm stop §7- إيقاف البوت وإنهاء التحكم"));
        source.sendFeedback(Component.literal("§e/bot farm status §7- عرض حالة البوت والمناطق والسرير"));
        source.sendFeedback(Component.literal("§e/bot farm sleep [on|off] §7- تفعيل/تعطيل ميزة النوم بعد مرور 20 ثانية على الليل"));
        source.sendFeedback(Component.literal("§e/bot farm bed [set|clear] §7- تعيين أو عرض سرير نقطة الإحياء"));
        source.sendFeedback(Component.literal("§e/bot farm fertilizer chest [set|clear] §7- تعيين أو عرض صندوق تخزين السماد (Bone Meal)"));
        source.sendFeedback(Component.literal("§e/bot farm area pos1 §7- تحديد النقطة الأولى من موقعك"));
        source.sendFeedback(Component.literal("§e/bot farm area pos2 §7- تحديد النقطة الثانية من موقعك"));
        source.sendFeedback(Component.literal("§e/bot farm area create <اسم> [محصول] §7- إنشاء منطقة مع اختيار المحصول"));
        source.sendFeedback(Component.literal("§e/bot farm area edit <اسم> <name|crop> <قيمة> §7- تعديل اسم أو محصول مزرعة"));
        source.sendFeedback(Component.literal("§e/bot farm area setcrop <اسم> <المحصول> §7- تخصيص محصول لمزرعة"));
        source.sendFeedback(Component.literal("§e/bot farm area list §7- عرض جميع المناطق المسجلة"));
        source.sendFeedback(Component.literal("§e/bot farm area remove <اسم> §7- حذف منطقة"));
        source.sendFeedback(Component.literal("§e/bot farm area select [اسم|all] §7- عرض المزارع المتاحة أو تركيز العمل على واحدة"));
    }
}
