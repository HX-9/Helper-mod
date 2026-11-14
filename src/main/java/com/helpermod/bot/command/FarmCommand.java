package com.helpermod.bot.command;

import com.helpermod.bot.FarmArea;
import com.helpermod.bot.FarmAreaManager;
import com.helpermod.bot.FarmBotController;
import com.helpermod.bot.FarmCrop;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.Locale;

public final class FarmCommand {

    private FarmCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal("farm")
            .executes(FarmCommand::sendFarmHelp)
            // /bot farm start
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("start")
                .executes(FarmCommand::executeStart))
            // /bot farm stop
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stop")
                .executes(FarmCommand::executeStop))
            // /bot farm status
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status")
                .executes(FarmCommand::executeStatus))
            // /bot farm sleep [on|off]
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("sleep")
                .executes(FarmCommand::executeToggleSleep)
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on")
                    .executes(ctx -> executeSetSleep(ctx, true)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off")
                    .executes(ctx -> executeSetSleep(ctx, false))))
            // /bot farm bed [set|clear]
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("bed")
                .executes(FarmCommand::executeBedStatus)
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("set")
                    .executes(FarmCommand::executeSetBed))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
                    .executes(FarmCommand::executeClearBed)))
            // /bot farm fertilizer chest [set|clear]
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("fertilizer")
                .executes(FarmCommand::executeFertilizerChestStatus)
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("chest")
                    .executes(FarmCommand::executeFertilizerChestStatus)
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("set")
                        .executes(FarmCommand::executeSetFertilizerChest))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
                        .executes(FarmCommand::executeClearFertilizerChest))))
            // /bot farm area ...
            .then(buildAreaCommands());
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildAreaCommands() {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal("area")
            .executes(FarmCommand::sendAreaHelp)
            // pos1
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("pos1")
                .executes(FarmCommand::executePos1))
            // pos2
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("pos2")
                .executes(FarmCommand::executePos2))
            // create <name> [crop]
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("create")
                .executes(FarmCommand::executeCreateHelp)
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .executes(FarmCommand::executeCreateArea)
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("crop", StringArgumentType.string())
                        .suggests(BotCommandHelpers.CROPS)
                        .executes(FarmCommand::executeCreateAreaWithCrop))))
            // add <name> <x1> <y1> <z1> <x2> <y2> <z2> [crop]
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
                .executes(FarmCommand::executeAddHelp)
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("x1", IntegerArgumentType.integer())
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("y1", IntegerArgumentType.integer())
                            .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("z1", IntegerArgumentType.integer())
                                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("x2", IntegerArgumentType.integer())
                                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("y2", IntegerArgumentType.integer())
                                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("z2", IntegerArgumentType.integer())
                                            .executes(FarmCommand::executeAddCoordinatesArea)
                                            .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("crop", StringArgumentType.string())
                                                .suggests(BotCommandHelpers.CROPS)
                                                .executes(FarmCommand::executeAddCoordinatesAreaWithCrop))))))))))
            // edit [farm] [name|crop|chest|bounds] [value]
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("edit")
                .executes(FarmCommand::executeEditGeneralHelp)
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("farm", StringArgumentType.string())
                    .suggests(BotCommandHelpers.FARM_AREAS)
                    .executes(FarmCommand::executeEditAreaHelp)
                    // edit <farm> name <new_name>
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("name")
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("new_name", StringArgumentType.string())
                            .executes(FarmCommand::executeEditName)))
                    // edit <farm> crop <crop>
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("crop")
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("crop", StringArgumentType.string())
                            .suggests(BotCommandHelpers.CROPS)
                            .executes(FarmCommand::executeEditCrop)))
                    // edit <farm> chest [here|clear]
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("chest")
                        .executes(FarmCommand::executeEditChestPrompt)
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("here")
                            .executes(FarmCommand::executeEditChestHere))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
                            .executes(FarmCommand::executeEditChestClear)))
                    // edit <farm> bounds
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("bounds")
                        .executes(FarmCommand::executeEditBounds))
                    // edit <farm> enable / disable
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("enable")
                        .executes(ctx -> executeEditAreaEnabled(ctx, true)))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("disable")
                        .executes(ctx -> executeEditAreaEnabled(ctx, false)))))
            // enable <name|all>
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("enable")
                .executes(FarmCommand::executeListAreaStates)
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .suggests(BotCommandHelpers.ENABLE_AREA_TARGETS)
                    .executes(ctx -> executeSetAreaEnabled(ctx, true))))
            // disable <name|all>
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("disable")
                .executes(FarmCommand::executeListAreaStates)
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .suggests(BotCommandHelpers.DISABLE_AREA_TARGETS)
                    .executes(ctx -> executeSetAreaEnabled(ctx, false))))
            // remove <name>
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
                .executes(FarmCommand::executeListAreas)
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .suggests(BotCommandHelpers.FARM_AREAS)
                    .executes(FarmCommand::executeRemoveArea)))
            // list
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
                .executes(FarmCommand::executeListAreas))
            // select / select <name>
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("select")
                .executes(FarmCommand::executeListSelectableAreas)
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .suggests(BotCommandHelpers.SELECTABLE_AREAS)
                    .executes(FarmCommand::executeSelectArea)));
    }

    private static int sendFarmHelp(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BotCommandHelpers.send(source, "§6=== [أوامر بوت الزراعة - /bot farm] ===");
        BotCommandHelpers.send(source, "§e/bot farm start §7- بدء العمل الزراعي التلقائي");
        BotCommandHelpers.send(source, "§e/bot farm stop §7- إيقاف بوت الزراعة");
        BotCommandHelpers.send(source, "§e/bot farm status §7- عرض حالة المزرعة والسرير والسماد");
        BotCommandHelpers.send(source, "§e/bot farm sleep [on|off] §7- تبديل أو ضبط النوم الليلي التلقائي");
        BotCommandHelpers.send(source, "§e/bot farm bed [set|clear] §7- عرض/تحديد/إلغاء سرير النوم");
        BotCommandHelpers.send(source, "§e/bot farm fertilizer chest [set|clear] §7- إدارة صندوق السماد (Bone Meal)");
        BotCommandHelpers.send(source, "§e/bot farm area §7- عرض أوامر إدارة المناطق الزراعية");
        return 1;
    }

    private static int sendAreaHelp(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BotCommandHelpers.send(source, "§6=== [أوامر المناطق الزراعية - /bot farm area] ===");
        BotCommandHelpers.send(source, "§e/bot farm area pos1 §7و §epos2 §7- تحديد ركني المزرعة من موقعك");
        BotCommandHelpers.send(source, "§e/bot farm area create <اسم> [محصول] §7- إنشاء منطقة من pos1 و pos2");
        BotCommandHelpers.send(source, "§e/bot farm area add <اسم> <x1> <y1> <z1> <x2> <y2> <z2> [محصول] §7- إضافة بالإحداثيات");
        BotCommandHelpers.send(source, "§e/bot farm area edit <اسم> <name|crop|chest|bounds|enable|disable> §7- تعديل المزرعة أو تفعيلها/تعطيلها");
        BotCommandHelpers.send(source, "§e/bot farm area enable <اسم|all> §7- تفعيل مزرعة محددة (دون المساس ببقية المزارع)");
        BotCommandHelpers.send(source, "§e/bot farm area disable <اسم|all> §7- تعطيل مزرعة محددة (دون المساس ببقية المزارع)");
        BotCommandHelpers.send(source, "§e/bot farm area select [اسم|all|none] §7- تحديد مزرعة واحدة فقط أو الكل أو لا أحد");
        BotCommandHelpers.send(source, "§e/bot farm area list §7- عرض جميع المناطق وحالتها | §c/bot farm area remove <اسم> §7- حذف منطقة");
        return 1;
    }

    private static int executeCreateHelp(CommandContext<FabricClientCommandSource> context) {
        BotCommandHelpers.send(context.getSource(),
            "§e[Bot] الصيغة: §b/bot farm area create <اسم_المنطقة> [wheat|carrot|potato|beetroot|nether_wart|auto]");
        return 1;
    }

    private static int executeAddHelp(CommandContext<FabricClientCommandSource> context) {
        BotCommandHelpers.send(context.getSource(),
            "§e[Bot] الصيغة: §b/bot farm area add <اسم> <x1> <y1> <z1> <x2> <y2> <z2> [محصول]");
        return 1;
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
        BotCommandHelpers.send(source, "§6=== [حالة بوت الزراعة HelperMod] ===");
        BotCommandHelpers.send(source, "§7الحالة الحالية: " + (running ? "§aشغال (يعمل الآن)" : "§cمتوقف"));
        FarmAreaManager areaManager = FarmAreaManager.getInstance();
        BotCommandHelpers.send(source, "§7المناطق المفعلة: §e" + areaManager.getActiveAreasSummary());
        BotCommandHelpers.send(source, "§7عدد المناطق: §a" + areaManager.getActiveAreas().size() + " مفعلة §7من أصل §b" + areaManager.getAllAreas().size());
        boolean sleepOpt = FarmAreaManager.getInstance().isSleepAtNight();
        BlockPos bedPos = FarmAreaManager.getInstance().getBedPos();
        BotCommandHelpers.send(source, "§7النوم التلقائي عند الليل: " + (sleepOpt ? "§aمفعل (بعد 20 ثانية من حلول الليل)" : "§cمعطل"));
        BotCommandHelpers.send(source, "§7موقع السرير (Respawn Bed): §e" + (bedPos != null ? (bedPos.getX() + ", " + bedPos.getY() + ", " + bedPos.getZ()) : "تلقائي (البحث عن أقرب سرير)"));
        BlockPos fertChest = FarmAreaManager.getInstance().getFertilizerChestPos();
        BotCommandHelpers.send(source, "§7صندوق السماد (Bone Meal): §e" + (fertChest != null ? (fertChest.getX() + ", " + fertChest.getY() + ", " + fertChest.getZ()) : "غير محدد (/bot farm fertilizer chest set)"));
        if (running) {
            String currentAreaName = controller.getCurrentAreaName();
            if (currentAreaName != null) {
                BotCommandHelpers.send(source, "§7المزرعة قيد العمل: §e" + currentAreaName + " §8(رقم " + (controller.getCurrentAreaIndex() + 1) + " من " + controller.getTotalActiveAreas() + ")");
            }
            BotCommandHelpers.send(source, "§7المرحلة الحالية: §b" + controller.getCurrentStage().getArabicName());
            BotCommandHelpers.send(source, "§8ترتيب العمل: [1. الحصاد -> 2. تجميع المحصول -> 3. إصلاح الأرض -> 4. زراعة الأرض]");
            if (controller.getNightTicks() > 0) {
                int sec = controller.getNightTicks() / 20;
                BotCommandHelpers.send(source, "§8[الليل حالياً: مضى عليه " + sec + " ثانية" + (sec >= 20 ? " - حان وقت النوم والتوجه للسرير فوراً" : "") + "]");
            }
        }
        return 1;
    }

    private static int executePos1(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            BlockPos pos = client.player.blockPosition();
            FarmAreaManager.getInstance().setPos1(pos);
            BotCommandHelpers.send(context.getSource(), "§a[Bot] تم تعيين النقطة الأولى (pos1) عند: §e" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
        }
        return 1;
    }

    private static int executePos2(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            BlockPos pos = client.player.blockPosition();
            FarmAreaManager.getInstance().setPos2(pos);
            BotCommandHelpers.send(context.getSource(), "§a[Bot] تم تعيين النقطة الثانية (pos2) عند: §e" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
            BotCommandHelpers.send(context.getSource(), "§7يمكنك الآن إنشاء المنطقة عبر الأمر: §b/bot farm area create <اسم_المنطقة>");
        }
        return 1;
    }

    private static int executeCreateArea(CommandContext<FabricClientCommandSource> context) {
        return createOrAddArea(
            context.getSource(),
            StringArgumentType.getString(context, "name"),
            FarmAreaManager.getInstance().getPos1(),
            FarmAreaManager.getInstance().getPos2(),
            null,
            false
        );
    }

    private static int executeCreateAreaWithCrop(CommandContext<FabricClientCommandSource> context) {
        return createOrAddArea(
            context.getSource(),
            StringArgumentType.getString(context, "name"),
            FarmAreaManager.getInstance().getPos1(),
            FarmAreaManager.getInstance().getPos2(),
            StringArgumentType.getString(context, "crop"),
            false
        );
    }

    private static int executeAddCoordinatesArea(CommandContext<FabricClientCommandSource> context) {
        return createOrAddArea(
            context.getSource(),
            StringArgumentType.getString(context, "name"),
            readCoords(context, "x1", "y1", "z1"),
            readCoords(context, "x2", "y2", "z2"),
            null,
            true
        );
    }

    private static int executeAddCoordinatesAreaWithCrop(CommandContext<FabricClientCommandSource> context) {
        return createOrAddArea(
            context.getSource(),
            StringArgumentType.getString(context, "name"),
            readCoords(context, "x1", "y1", "z1"),
            readCoords(context, "x2", "y2", "z2"),
            StringArgumentType.getString(context, "crop"),
            true
        );
    }

    private static BlockPos readCoords(
            CommandContext<FabricClientCommandSource> context,
            String xKey,
            String yKey,
            String zKey) {
        return new BlockPos(
            IntegerArgumentType.getInteger(context, xKey),
            IntegerArgumentType.getInteger(context, yKey),
            IntegerArgumentType.getInteger(context, zKey)
        );
    }

    private static int createOrAddArea(
            FabricClientCommandSource source,
            String rawName,
            BlockPos p1,
            BlockPos p2,
            String cropArg,
            boolean isCoordinateAdd) {
        String name = rawName.toLowerCase(Locale.ROOT);
        if (p1 == null || p2 == null) {
            BotCommandHelpers.send(source, "§c[Bot] يجب تحديد pos1 و pos2 أولاً بالوقوف على النقطتين وكتابة:");
            BotCommandHelpers.send(source, "§e/bot farm area pos1 §7ثم §e/bot farm area pos2");
            return 0;
        }

        BotCommandHelpers.CropParseResult cropResult = BotCommandHelpers.parseCropArgument(source, cropArg, false);
        if (!cropResult.isValid()) {
            return 0;
        }
        FarmCrop crop = cropResult.crop();

        boolean added = FarmAreaManager.getInstance().addArea(name, p1, p2, crop);
        if (!added) {
            BotCommandHelpers.send(source, "§c[Bot] توجد مزرعة مسجلة بالفعل بالاسم: §e" + name + "§c!");
            BotCommandHelpers.send(source, "§7لتحديث حدودها من pos1/pos2 استخدم: §b/bot farm area edit " + name + " bounds §7أو احذفها عبر: §c/bot farm area remove " + name);
            return 0;
        }

        FarmArea area = FarmAreaManager.getInstance().getArea(name);
        BlockPos min = area.getMinPos();
        BlockPos max = area.getMaxPos();
        String verb = isCoordinateAdd ? "إضافة" : "إنشاء";
        BotCommandHelpers.send(source, "§a[Bot] تم " + verb + " المنطقة الزراعية §e" + name + "§a بنجاح!");
        BotCommandHelpers.send(source, "§7النطاق: من (" + min.getX() + "," + min.getY() + "," + min.getZ() + ") إلى (" + max.getX() + "," + max.getY() + "," + max.getZ() + ")");
        BotCommandHelpers.send(source, "§7المحصول المحدد: §e" + area.getCropDisplayName());
        BotCommandHelpers.send(source, "§7لتعديل المزرعة مستقبلاً: §b/bot farm area edit " + name + " <name|crop|chest|bounds>");
        return 1;
    }

    private static int executeRemoveArea(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name").toLowerCase(Locale.ROOT);
        boolean removed = FarmAreaManager.getInstance().removeArea(name);
        if (removed) {
            BotCommandHelpers.send(context.getSource(), "§a[Bot] تم حذف المنطقة §e" + name + "§a بنجاح.");
        } else {
            BotCommandHelpers.send(context.getSource(), "§c[Bot] المنطقة §e" + name + "§c غير موجودة!");
        }
        return 1;
    }

    private static int executeListAreas(CommandContext<FabricClientCommandSource> context) {
        Collection<FarmArea> areas = FarmAreaManager.getInstance().getAllAreas();
        if (areas.isEmpty()) {
            BotCommandHelpers.send(context.getSource(), "§e[Bot] لا توجد أي مناطق زراعية مسجلة حالياً.");
            return 1;
        }

        BotCommandHelpers.send(context.getSource(), "§6=== [قائمة المناطق الزراعية] ===");
        for (FarmArea area : areas) {
            BlockPos min = area.getMinPos();
            BlockPos max = area.getMaxPos();
            String coords = String.format("(%d,%d,%d) إلى (%d,%d,%d)", min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
            String stateBadge = area.isEnabled() ? "§a[مفعل]" : "§c[غير مفعل]";
            String chestInfo = area.getChestPos() != null ? (" §6[صندوق: " + area.getChestPos().getX() + "," + area.getChestPos().getY() + "," + area.getChestPos().getZ() + "]") : "";
            BotCommandHelpers.send(context.getSource(), stateBadge + " §b" + area.getName() + " §7[" + coords + "] - المحصول: §e" + area.getCropDisplayName() + chestInfo);
        }
        return 1;
    }

    private static int executeListAreaStates(CommandContext<FabricClientCommandSource> context) {
        return executeListSelectableAreas(context);
    }

    private static int executeListSelectableAreas(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Collection<FarmArea> areas = FarmAreaManager.getInstance().getAllAreas();
        if (areas.isEmpty()) {
            BotCommandHelpers.send(source, "§e[Bot] لا توجد أي مزارع مسجلة حالياً للاختيار منها.");
            BotCommandHelpers.send(source, "§7أنشئ مزرعة جديدة عبر: §b/bot farm area pos1 §7ثم §bpos2 §7ثم §bcreate <اسم>");
            return 1;
        }

        BotCommandHelpers.send(source, "§6=== [حالة تفعيل المزارع] ===");
        for (FarmArea area : areas) {
            boolean enabled = area.isEnabled();
            String badge = enabled ? "§a[مفعل]" : "§c[غير مفعل]";
            String bullet = enabled ? "§a► " : "§7• ";
            BotCommandHelpers.send(source, bullet + badge + " §b" + area.getName() + " §7(المحصول: §e" + area.getCropDisplayName() + "§7)");
        }
        BotCommandHelpers.send(source, "§7الملخص الحالي: §e" + FarmAreaManager.getInstance().getActiveAreasSummary());
        BotCommandHelpers.send(source, "§7لتفعيل مزرعة (مع إبقاء البقية): §b/bot farm area enable <الاسم|all>");
        BotCommandHelpers.send(source, "§7لتعطيل مزرعة (مع إبقاء البقية): §b/bot farm area disable <الاسم|all>");
        BotCommandHelpers.send(source, "§7لتحديد واحدة فقط / الكل / لا أحد: §b/bot farm area select <الاسم|all|none>");
        return 1;
    }

    private static int executeSelectArea(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name").toLowerCase(Locale.ROOT);
        if (name.equals("all") || name.equals("الكل")) {
            FarmAreaManager.getInstance().setAllAreasEnabled(true);
            BotCommandHelpers.send(source, "§a[Bot] تم تفعيل §eجميع المناطق§a للعمل (" + FarmAreaManager.getInstance().getActiveAreas().size() + " مفعلة).");
            return 1;
        }
        if (name.equals("none") || name.equals("off") || name.equals("لا_أحد") || name.equals("لاأحد")) {
            FarmAreaManager.getInstance().setAllAreasEnabled(false);
            BotCommandHelpers.send(source, "§c[Bot] تم تعطيل §eجميع المناطق§c (لا توجد مزارع مفعلة حالياً).");
            return 1;
        }

        FarmArea area = FarmAreaManager.getInstance().getArea(name);
        if (area == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد منطقة بهذا الاسم: §e" + name);
            executeListSelectableAreas(context);
            return 0;
        }

        FarmAreaManager.getInstance().setActiveAreaName(name);
        BotCommandHelpers.send(source, "§a[Bot] تم تفعيل مزرعة §e" + name + "§a فقط وتعطيل باقي المزارع §7(المحصول: §e" + area.getCropDisplayName() + "§7).");
        BotCommandHelpers.send(source, "§7لإضافة مزرعة أخرى للعمل معها استخدم: §b/bot farm area enable <الاسم>");
        return 1;
    }

    private static int executeSetAreaEnabled(CommandContext<FabricClientCommandSource> context, boolean enabled) {
        FabricClientCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name").toLowerCase(Locale.ROOT);
        FarmAreaManager manager = FarmAreaManager.getInstance();

        if (name.equals("all") || name.equals("الكل")) {
            if (manager.getAllAreas().isEmpty()) {
                BotCommandHelpers.send(source, "§e[Bot] لا توجد أي مزارع مسجلة حالياً.");
                return 0;
            }
            manager.setAllAreasEnabled(enabled);
            BotCommandHelpers.send(source, enabled
                ? "§a[Bot] تم تعيين جميع المزارع كـ §a[مفعل]§a (" + manager.getActiveAreas().size() + " مزرعة)."
                : "§c[Bot] تم تعيين جميع المزارع كـ §c[غير مفعل]§c.");
            return 1;
        }

        FarmArea area = manager.getArea(name);
        if (area == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + name);
            executeListSelectableAreas(context);
            return 0;
        }

        boolean alreadySameState = (area.isEnabled() == enabled);
        manager.setAreaEnabled(name, enabled);
        if (alreadySameState) {
            BotCommandHelpers.send(source, "§e[Bot] المزرعة §b" + name + " §eبالفعل " + (enabled ? "§a[مفعل]" : "§c[غير مفعل]") + "§e. (" + manager.getActiveAreasSummary() + ")");
        } else {
            BotCommandHelpers.send(source, "§a[Bot] تم تغيير حالة المزرعة §e" + name + "§a إلى: " + (enabled ? "§a[مفعل]" : "§c[غير مفعل]") + "§7 | " + manager.getActiveAreasSummary());
        }
        return 1;
    }

    private static int executeEditAreaEnabled(CommandContext<FabricClientCommandSource> context, boolean enabled) {
        FabricClientCommandSource source = context.getSource();
        String farmName = StringArgumentType.getString(context, "farm").toLowerCase(Locale.ROOT);
        FarmArea area = FarmAreaManager.getInstance().getArea(farmName);
        if (area == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName);
            return 0;
        }
        FarmAreaManager.getInstance().setAreaEnabled(farmName, enabled);
        BotCommandHelpers.send(source, "§a[Bot] تم ضبط حالة المزرعة §e" + farmName + "§a إلى: " + (enabled ? "§a[مفعل]" : "§c[غير مفعل]") + "§7 | " + FarmAreaManager.getInstance().getActiveAreasSummary());
        return 1;
    }

    private static int executeEditGeneralHelp(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BotCommandHelpers.send(source, "§6=== [تعديل المزارع المحفوظة] ===");
        BotCommandHelpers.send(source, "§7لتغيير اسم المزرعة: §b/bot farm area edit <اسم_المزرعة> name <الاسم_الجديد>");
        BotCommandHelpers.send(source, "§7لتغيير محصول المزرعة: §b/bot farm area edit <اسم_المزرعة> crop <المحصول|auto>");
        BotCommandHelpers.send(source, "§7لربط صندوق المحصول: §b/bot farm area edit <اسم_المزرعة> chest §7(ثم فتح الصندوق لربطه)");
        BotCommandHelpers.send(source, "§7لتحديث حدود المزرعة من pos1/pos2: §b/bot farm area edit <اسم_المزرعة> bounds");
        executeListAreas(context);
        return 1;
    }

    private static int executeEditAreaHelp(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String farmName = StringArgumentType.getString(context, "farm").toLowerCase(Locale.ROOT);
        FarmArea area = FarmAreaManager.getInstance().getArea(farmName);
        if (area == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName);
            return 0;
        }
        BotCommandHelpers.send(source, "§6=== [خيارات تعديل المزرعة: §e" + farmName + "§6] ===");
        BotCommandHelpers.send(source, "§7الحالة: " + (area.isEnabled() ? "§a[مفعل]" : "§c[غير مفعل]") + " §7| المحصول الحالي: §e" + area.getCropDisplayName());
        BlockPos chest = area.getChestPos();
        BotCommandHelpers.send(source, "§7صندوق المحصول: §e" + (chest != null ? (chest.getX() + ", " + chest.getY() + ", " + chest.getZ()) : "غير محدد (بدون صندوق)"));
        BotCommandHelpers.send(source, "§71. تغيير الاسم: §b/bot farm area edit " + farmName + " name <الاسم_الجديد>");
        BotCommandHelpers.send(source, "§72. تغيير المحصول: §b/bot farm area edit " + farmName + " crop <wheat|carrot|potato|beetroot|nether_wart|auto>");
        BotCommandHelpers.send(source, "§73. ربط صندوق: §b/bot farm area edit " + farmName + " chest §7(افتح الصندوق بعدها) أو §bhere§7/§bclear");
        BotCommandHelpers.send(source, "§74. تحديث الحدود: §b/bot farm area edit " + farmName + " bounds §7(باستخدام pos1 و pos2 الحاليين)");
        BotCommandHelpers.send(source, "§75. التفعيل/التعطيل: §b/bot farm area edit " + farmName + " <enable|disable>");
        return 1;
    }

    private static int executeEditName(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String oldName = StringArgumentType.getString(context, "farm").toLowerCase(Locale.ROOT);
        String newName = StringArgumentType.getString(context, "new_name").toLowerCase(Locale.ROOT);

        if (oldName.equals(newName)) {
            BotCommandHelpers.send(source, "§e[Bot] الاسم الجديد مطابق للاسم الحالي للمزرعة!");
            return 1;
        }

        FarmArea area = FarmAreaManager.getInstance().getArea(oldName);
        if (area == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + oldName);
            return 0;
        }

        if (FarmAreaManager.getInstance().getArea(newName) != null) {
            BotCommandHelpers.send(source, "§c[Bot] يوجد بالفعل مزرعة أخرى تحمل الاسم: §e" + newName);
            return 0;
        }

        boolean renamed = FarmAreaManager.getInstance().renameArea(oldName, newName);
        if (renamed) {
            BotCommandHelpers.send(source, "§a[Bot] تم تغيير اسم المزرعة من §e" + oldName + "§a إلى §e" + newName + "§a بنجاح!");
        } else {
            BotCommandHelpers.send(source, "§c[Bot] تعذر تغيير اسم المزرعة.");
        }
        return 1;
    }

    private static int executeEditCrop(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String farmName = StringArgumentType.getString(context, "farm").toLowerCase(Locale.ROOT);
        String cropId = StringArgumentType.getString(context, "crop");

        FarmArea area = FarmAreaManager.getInstance().getArea(farmName);
        if (area == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName);
            return 0;
        }

        BotCommandHelpers.CropParseResult cropResult = BotCommandHelpers.parseCropArgument(source, cropId, true);
        if (!cropResult.isValid()) {
            return 0;
        }

        FarmCrop crop = cropResult.crop();
        area.setTargetCrop(crop);
        FarmAreaManager.getInstance().save();
        if (crop == null) {
            BotCommandHelpers.send(source, "§a[Bot] تم تعديل محصول المزرعة §e" + farmName + "§a إلى نمط: §bتلقائي (أي محصول متاح معك).");
        } else {
            BotCommandHelpers.send(source, "§a[Bot] تم تعديل محصول المزرعة §e" + farmName + "§a إلى: §e" + crop.getArabicName() + " (" + crop.getId() + ")");
        }
        return 1;
    }

    private static int executeEditChestPrompt(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String farmName = StringArgumentType.getString(context, "farm").toLowerCase(Locale.ROOT);
        FarmArea area = FarmAreaManager.getInstance().getArea(farmName);
        if (area == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName);
            return 0;
        }

        FarmAreaManager.getInstance().setAwaitingChestFarm(farmName);
        BotCommandHelpers.send(source, "§e[Bot] تم تفعيل وضع ربط الصندوق لمزرعة §a" + farmName + "§e!");
        BotCommandHelpers.send(source, "§6الآن افتح (اضغط كليك يمين على) الصندوق الذي تريد تخزين محصول هذه المزرعة فيه ليتم ربطه تلقائياً.");
        return 1;
    }

    private static int executeEditChestHere(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String farmName = StringArgumentType.getString(context, "farm").toLowerCase(Locale.ROOT);
        FarmArea area = FarmAreaManager.getInstance().getArea(farmName);
        if (area == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName);
            return 0;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0;
        }

        BlockPos playerPos = client.player.blockPosition();
        BlockPos fertilizerChest = FarmAreaManager.getInstance().getFertilizerChestPos();
        BlockPos targetChest = findNearbyContainer(client, playerPos, 1, 1, 1, check -> check.equals(fertilizerChest));

        if (targetChest == null) {
            if (playerPos.equals(fertilizerChest)) {
                BotCommandHelpers.send(source, "§c[Bot] هذا الموقع مخصص لصندوق السماد (Bone Meal). قف بجانب صندوق آخر أو استخدم /bot farm area edit " + farmName + " chest.");
                return 0;
            }
            targetChest = playerPos;
        }

        area.setChestPos(targetChest);
        FarmAreaManager.getInstance().save();
        FarmAreaManager.getInstance().setAwaitingChestFarm(null);
        BotCommandHelpers.send(source, "§a[Bot] تم تعيين صندوق مزرعة §e" + farmName + "§a عند: §e" + targetChest.getX() + ", " + targetChest.getY() + ", " + targetChest.getZ());
        return 1;
    }

    private static int executeEditChestClear(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String farmName = StringArgumentType.getString(context, "farm").toLowerCase(Locale.ROOT);
        FarmArea area = FarmAreaManager.getInstance().getArea(farmName);
        if (area == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName);
            return 0;
        }

        area.setChestPos(null);
        FarmAreaManager.getInstance().save();
        BotCommandHelpers.send(source, "§a[Bot] تم إلغاء ربط الصندوق بمزرعة §e" + farmName + "§a والعودة للوضع الافتراضي (بدون صندوق).");
        return 1;
    }

    private static int executeEditBounds(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String farmName = StringArgumentType.getString(context, "farm").toLowerCase(Locale.ROOT);
        FarmArea existing = FarmAreaManager.getInstance().getArea(farmName);
        if (existing == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName);
            return 0;
        }

        BlockPos p1 = FarmAreaManager.getInstance().getPos1();
        BlockPos p2 = FarmAreaManager.getInstance().getPos2();
        if (p1 == null || p2 == null) {
            BotCommandHelpers.send(source, "§c[Bot] يجب تحديد pos1 و pos2 أولاً بالوقوف على النقطتين وكتابة:");
            BotCommandHelpers.send(source, "§e/bot farm area pos1 §7ثم §e/bot farm area pos2");
            return 0;
        }

        boolean updated = FarmAreaManager.getInstance().updateAreaBounds(farmName, p1, p2);
        if (!updated) {
            BotCommandHelpers.send(source, "§c[Bot] تعذر تحديث حدود المزرعة: §e" + farmName);
            return 0;
        }

        BlockPos min = existing.getMinPos();
        BlockPos max = existing.getMaxPos();
        BotCommandHelpers.send(source, "§a[Bot] تم تحديث حدود المزرعة §e" + farmName + "§a بنجاح!");
        BotCommandHelpers.send(source, "§7النطاق الجديد: من (" + min.getX() + "," + min.getY() + "," + min.getZ() + ") إلى (" + max.getX() + "," + max.getY() + "," + max.getZ() + ")");
        return 1;
    }

    private static int executeToggleSleep(CommandContext<FabricClientCommandSource> context) {
        boolean next = !FarmAreaManager.getInstance().isSleepAtNight();
        FarmAreaManager.getInstance().setSleepAtNight(next);
        BotCommandHelpers.send(context.getSource(), "§a[Bot] تم " + (next ? "§aتفعيل" : "§cتعطيل") + "§a ميزة النوم التلقائي عند حلول الليل.");
        return 1;
    }

    private static int executeSetSleep(CommandContext<FabricClientCommandSource> context, boolean enable) {
        FarmAreaManager.getInstance().setSleepAtNight(enable);
        BotCommandHelpers.send(context.getSource(), "§a[Bot] تم " + (enable ? "§aتفعيل" : "§cتعطيل") + "§a ميزة النوم التلقائي عند حلول الليل.");
        return 1;
    }

    private static int executeBedStatus(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BlockPos bedPos = FarmAreaManager.getInstance().getBedPos();
        if (bedPos != null) {
            BotCommandHelpers.send(source, "§a[Bot] السرير المعتمد لنقطة الإحياء محدد عند: §e" + bedPos.getX() + ", " + bedPos.getY() + ", " + bedPos.getZ());
        } else {
            BotCommandHelpers.send(source, "§e[Bot] لم يتم تحديد سرير مخصص يدوياً (سيبحث البوت تلقائياً عن أقرب سرير عند حلول الليل).");
            BotCommandHelpers.send(source, "§7لتحديد سريرك الحالي: قف عند السرير واكتب: §b/bot farm bed set");
        }
        return 1;
    }

    private static int executeSetBed(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0;
        }

        BlockPos playerPos = client.player.blockPosition();
        BlockPos targetBed = null;
        for (int dx = -1; dx <= 1 && targetBed == null; dx++) {
            for (int dy = -1; dy <= 1 && targetBed == null; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos check = playerPos.offset(dx, dy, dz);
                    if (client.level != null) {
                        BlockState state = client.level.getBlockState(check);
                        if (state.getBlock() instanceof BedBlock || state.is(BlockTags.BEDS)) {
                            targetBed = check;
                            break;
                        }
                    }
                }
            }
        }

        if (targetBed == null) {
            targetBed = playerPos;
        }

        FarmAreaManager.getInstance().setBedPos(targetBed);
        BotCommandHelpers.send(source, "§a[Bot] تم تعيين سرير نقطة الإحياء بنجاح عند: §e" + targetBed.getX() + ", " + targetBed.getY() + ", " + targetBed.getZ());
        return 1;
    }

    private static int executeClearBed(CommandContext<FabricClientCommandSource> context) {
        FarmAreaManager.getInstance().setBedPos(null);
        BotCommandHelpers.send(context.getSource(), "§a[Bot] تم إلغاء السرير المخصص والعودة للبحث التلقائي عن أقرب سرير.");
        return 1;
    }

    private static int executeFertilizerChestStatus(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BlockPos chestPos = FarmAreaManager.getInstance().getFertilizerChestPos();
        if (chestPos != null) {
            BotCommandHelpers.send(source, "§a[Bot] صندوق السماد (Bone Meal) محدد عند: §e" + chestPos.getX() + ", " + chestPos.getY() + ", " + chestPos.getZ());
        } else {
            BotCommandHelpers.send(source, "§e[Bot] لم يتم تحديد صندوق مخصص لوضع السماد فيه بعد!");
            BotCommandHelpers.send(source, "§7لتحديد الصندوق: قف بجانبه واكتب: §b/bot farm fertilizer chest set §7أو انقر عليه كليك يمين بعد كتابة الأمر.");
        }
        return 1;
    }

    private static int executeSetFertilizerChest(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0;
        }

        BlockPos playerPos = client.player.blockPosition();
        BlockPos targetChest = findNearbyContainer(
            client,
            playerPos,
            2,
            2,
            2,
            check -> FarmAreaManager.getInstance().findAreaUsingChest(check) != null
        );

        if (targetChest != null) {
            FarmAreaManager.getInstance().setFertilizerChestPos(targetChest);
            FarmAreaManager.getInstance().setAwaitingFertilizerChest(false);
            BotCommandHelpers.send(source, "§a[Bot] تم تعيين صندوق السماد (Bone Meal) بنجاح عند: §e" + targetChest.getX() + ", " + targetChest.getY() + ", " + targetChest.getZ());
        } else {
            FarmAreaManager.getInstance().setAwaitingFertilizerChest(true);
            BotCommandHelpers.send(source, "§e[Bot] تم تفعيل وضع ربط صندوق السماد!");
            BotCommandHelpers.send(source, "§6الآن افتح (اضغط كليك يمين على) الصندوق الذي تريد تخزين السماد فيه ليتم ربطه تلقائياً.");
        }
        return 1;
    }

    private static int executeClearFertilizerChest(CommandContext<FabricClientCommandSource> context) {
        FarmAreaManager.getInstance().setFertilizerChestPos(null);
        FarmAreaManager.getInstance().setAwaitingFertilizerChest(false);
        BotCommandHelpers.send(context.getSource(), "§a[Bot] تم إلغاء تحديد صندوق السماد بنجاح.");
        return 1;
    }

    private static BlockPos findNearbyContainer(
            Minecraft client,
            BlockPos center,
            int rx,
            int ryUp,
            int rz,
            java.util.function.Predicate<BlockPos> shouldSkip) {
        if (client.level == null) {
            return null;
        }
        int minDy = -1;
        for (int dx = -rx; dx <= rx; dx++) {
            for (int dy = minDy; dy <= ryUp; dy++) {
                for (int dz = -rz; dz <= rz; dz++) {
                    BlockPos check = center.offset(dx, dy, dz);
                    if (shouldSkip != null && shouldSkip.test(check)) {
                        continue;
                    }
                    if (isContainerBlock(client.level.getBlockState(check))) {
                        return check;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isContainerBlock(BlockState state) {
        var block = state.getBlock();
        return block instanceof ChestBlock
            || block instanceof BarrelBlock
            || block instanceof EnderChestBlock
            || block instanceof ShulkerBoxBlock;
    }
}
