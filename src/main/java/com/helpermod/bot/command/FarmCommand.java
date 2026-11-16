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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Collection;
import java.util.List;
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
            // /bot farm fertilizer chest [set|open|here|clear]
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("fertilizer")
                .executes(FarmCommand::executeFertilizerChestStatus)
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("chest")
                    .executes(FarmCommand::executeFertilizerChestStatus)
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("set")
                        .executes(FarmCommand::executeSetFertilizerChest))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("open")
                        .executes(FarmCommand::executeOpenFertilizerChest))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("here")
                        .executes(FarmCommand::executeHereFertilizerChest))
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
                    // edit <farm> chest [open|here|add|list|remove|clear|crop|seed]
                    .then(buildChestCommand())
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

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildChestCommand() {
        LiteralArgumentBuilder<FabricClientCommandSource> chestNode = LiteralArgumentBuilder.<FabricClientCommandSource>literal("chest")
            .executes(ctx -> executeEditChestOpen(ctx, FarmArea.ChestRole.CROP, false, true));

        attachChestRoleActions(chestNode, FarmArea.ChestRole.CROP, null);
        chestNode.then(attachChestRoleActions(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("crop")
                .executes(ctx -> executeEditChestList(ctx, FarmArea.ChestRole.CROP)),
            FarmArea.ChestRole.CROP,
            FarmArea.ChestRole.CROP
        ));
        chestNode.then(attachChestRoleActions(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("seed")
                .executes(ctx -> executeEditChestList(ctx, FarmArea.ChestRole.SEED)),
            FarmArea.ChestRole.SEED,
            FarmArea.ChestRole.SEED
        ));
        return chestNode;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> attachChestRoleActions(
            LiteralArgumentBuilder<FabricClientCommandSource> node,
            FarmArea.ChestRole role,
            FarmArea.ChestRole listRole) {
        return node
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("open")
                .executes(ctx -> executeEditChestOpen(ctx, role, false, false)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("here")
                .executes(ctx -> executeEditChestHere(ctx, role, false)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
                .executes(ctx -> executeEditChestOpen(ctx, role, true, false))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("open")
                    .executes(ctx -> executeEditChestOpen(ctx, role, true, false)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("here")
                    .executes(ctx -> executeEditChestHere(ctx, role, true))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
                .executes(ctx -> executeEditChestList(ctx, listRole)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("index", IntegerArgumentType.integer(1))
                    .executes(ctx -> executeEditChestRemove(ctx, role))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
                .executes(ctx -> executeEditChestClear(ctx, role)));
    }

    private static int sendFarmHelp(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BotCommandHelpers.send(source, "§6=== [أوامر بوت الزراعة - /bot farm] ===");
        BotCommandHelpers.send(source, "§e/bot farm start §7- بدء العمل الزراعي التلقائي");
        BotCommandHelpers.send(source, "§e/bot farm stop §7- إيقاف بوت الزراعة");
        BotCommandHelpers.send(source, "§e/bot farm status §7- عرض حالة المزرعة والسرير والسماد");
        BotCommandHelpers.send(source, "§e/bot farm sleep [on|off] §7- تبديل أو ضبط النوم الليلي التلقائي");
        BotCommandHelpers.send(source, "§e/bot farm bed [set|clear] §7- عرض/تحديد/إلغاء سرير النوم");
        BotCommandHelpers.send(source, "§e/bot farm fertilizer chest [set|open|here|clear] §7- إدارة صندوق السماد (Bone Meal)");
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

    private static FarmAreaManager getScopedManager() {
        FarmAreaManager manager = FarmAreaManager.getInstance();
        manager.updateContext(Minecraft.getInstance());
        return manager;
    }

    private static int executeStatus(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        FarmBotController controller = FarmBotController.getInstance();
        boolean running = controller.isRunning();
        BotCommandHelpers.send(source, "§6=== [حالة بوت الزراعة HelperMod] ===");
        BotCommandHelpers.send(source, "§7الحالة الحالية: " + (running ? "§aشغال (يعمل الآن)" : "§cمتوقف"));
        FarmAreaManager areaManager = getScopedManager();
        BotCommandHelpers.send(source, "§7السياق الحالي: §b" + areaManager.getCurrentDimensionDisplayName());
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
            getScopedManager().setPos1(pos);
            BotCommandHelpers.send(context.getSource(), "§a[Bot] تم تعيين النقطة الأولى (pos1) عند: §e" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
        }
        return 1;
    }

    private static int executePos2(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            BlockPos pos = client.player.blockPosition();
            getScopedManager().setPos2(pos);
            BotCommandHelpers.send(context.getSource(), "§a[Bot] تم تعيين النقطة الثانية (pos2) عند: §e" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
            BotCommandHelpers.send(context.getSource(), "§7يمكنك الآن إنشاء المنطقة عبر الأمر: §b/bot farm area create <اسم_المنطقة>");
        }
        return 1;
    }

    private static int executeCreateArea(CommandContext<FabricClientCommandSource> context) {
        FarmAreaManager manager = getScopedManager();
        return createOrAddArea(
            context.getSource(),
            StringArgumentType.getString(context, "name"),
            manager.getPos1(),
            manager.getPos2(),
            null,
            false
        );
    }

    private static int executeCreateAreaWithCrop(CommandContext<FabricClientCommandSource> context) {
        FarmAreaManager manager = getScopedManager();
        return createOrAddArea(
            context.getSource(),
            StringArgumentType.getString(context, "name"),
            manager.getPos1(),
            manager.getPos2(),
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
        FarmAreaManager manager = getScopedManager();

        boolean added = manager.addArea(name, p1, p2, crop);
        if (!added) {
            BotCommandHelpers.send(source, "§c[Bot] توجد مزرعة مسجلة بالفعل بالاسم: §e" + name + "§c في هذا العالم/البُعد!");
            BotCommandHelpers.send(source, "§7لتحديث حدودها من pos1/pos2 استخدم: §b/bot farm area edit " + name + " bounds §7أو احذفها عبر: §c/bot farm area remove " + name);
            return 0;
        }

        FarmArea area = manager.getArea(name);
        BlockPos min = area.getMinPos();
        BlockPos max = area.getMaxPos();
        String verb = isCoordinateAdd ? "إضافة" : "إنشاء";
        BotCommandHelpers.send(source, "§a[Bot] تم " + verb + " المنطقة الزراعية §e" + name + "§a بنجاح!");
        BotCommandHelpers.send(source, "§7البُعد المرتبط: §b" + area.getDimensionDisplayName());
        BotCommandHelpers.send(source, "§7النطاق: من (" + min.getX() + "," + min.getY() + "," + min.getZ() + ") إلى (" + max.getX() + "," + max.getY() + "," + max.getZ() + ")");
        BotCommandHelpers.send(source, "§7المحصول المحدد: §e" + area.getCropDisplayName());
        BotCommandHelpers.send(source, "§7لتعديل المزرعة مستقبلاً: §b/bot farm area edit " + name + " <name|crop|chest|bounds>");
        return 1;
    }

    private static int executeRemoveArea(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name").toLowerCase(Locale.ROOT);
        boolean removed = getScopedManager().removeArea(name);
        if (removed) {
            BotCommandHelpers.send(context.getSource(), "§a[Bot] تم حذف المنطقة §e" + name + "§a بنجاح من هذا العالم/البُعد.");
        } else {
            BotCommandHelpers.send(context.getSource(), "§c[Bot] المنطقة §e" + name + "§c غير موجودة في هذا العالم/البُعد!");
        }
        return 1;
    }

    private static int executeListAreas(CommandContext<FabricClientCommandSource> context) {
        FarmAreaManager manager = getScopedManager();
        Collection<FarmArea> areas = manager.getAllAreas();
        if (areas.isEmpty()) {
            BotCommandHelpers.send(context.getSource(), "§e[Bot] لا توجد أي مناطق زراعية مسجلة حالياً في §b" + manager.getCurrentDimensionDisplayName() + "§e.");
            return 1;
        }

        BotCommandHelpers.send(context.getSource(), "§6=== [قائمة المناطق الزراعية - " + manager.getCurrentDimensionDisplayName() + "] ===");
        for (FarmArea area : areas) {
            BlockPos min = area.getMinPos();
            BlockPos max = area.getMaxPos();
            String coords = String.format("(%d,%d,%d) إلى (%d,%d,%d)", min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
            String stateBadge = area.isEnabled() ? "§a[مفعل]" : "§c[غير مفعل]";
            StringBuilder chestInfo = new StringBuilder();
            if (!area.getCropChests().isEmpty()) {
                chestInfo.append(" §6[محصول: ").append(formatCompactChests(area.getCropChests())).append("]");
            }
            if (!area.getSeedChests().isEmpty()) {
                chestInfo.append(" §e[بذور: ").append(formatCompactChests(area.getSeedChests())).append("]");
            }
            BotCommandHelpers.send(context.getSource(), stateBadge + " §b" + area.getName() + " §7[" + coords + "] - المحصول: §e" + area.getCropDisplayName() + chestInfo);
        }
        return 1;
    }

    private static int executeListAreaStates(CommandContext<FabricClientCommandSource> context) {
        return executeListSelectableAreas(context);
    }

    private static int executeListSelectableAreas(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        FarmAreaManager manager = getScopedManager();
        Collection<FarmArea> areas = manager.getAllAreas();
        if (areas.isEmpty()) {
            BotCommandHelpers.send(source, "§e[Bot] لا توجد أي مزارع مسجلة حالياً في هذا العالم/البُعد للاختيار منها.");
            BotCommandHelpers.send(source, "§7أنشئ مزرعة جديدة عبر: §b/bot farm area pos1 §7ثم §bpos2 §7ثم §bcreate <اسم>");
            return 1;
        }

        BotCommandHelpers.send(source, "§6=== [حالة تفعيل المزارع - " + manager.getCurrentDimensionDisplayName() + "] ===");
        for (FarmArea area : areas) {
            boolean enabled = area.isEnabled();
            String badge = enabled ? "§a[مفعل]" : "§c[غير مفعل]";
            String bullet = enabled ? "§a► " : "§7• ";
            BotCommandHelpers.send(source, bullet + badge + " §b" + area.getName() + " §7(المحصول: §e" + area.getCropDisplayName() + "§7)");
        }
        BotCommandHelpers.send(source, "§7الملخص الحالي: §e" + manager.getActiveAreasSummary());
        BotCommandHelpers.send(source, "§7لتفعيل مزرعة (مع إبقاء البقية): §b/bot farm area enable <الاسم|all>");
        BotCommandHelpers.send(source, "§7لتعطيل مزرعة (مع إبقاء البقية): §b/bot farm area disable <الاسم|all>");
        BotCommandHelpers.send(source, "§7لتحديد واحدة فقط / الكل / لا أحد: §b/bot farm area select <الاسم|all|none>");
        return 1;
    }

    private static int executeSelectArea(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name").toLowerCase(Locale.ROOT);
        FarmAreaManager manager = getScopedManager();
        if (name.equals("all") || name.equals("الكل")) {
            manager.setAllAreasEnabled(true);
            BotCommandHelpers.send(source, "§a[Bot] تم تفعيل §eجميع المناطق§a للعمل (" + manager.getActiveAreas().size() + " مفعلة).");
            return 1;
        }
        if (name.equals("none") || name.equals("off") || name.equals("لا_أحد") || name.equals("لاأحد")) {
            manager.setAllAreasEnabled(false);
            BotCommandHelpers.send(source, "§c[Bot] تم تعطيل §eجميع المناطق§c (لا توجد مزارع مفعلة حالياً).");
            return 1;
        }

        FarmArea area = manager.getArea(name);
        if (area == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد منطقة بهذا الاسم: §e" + name);
            executeListSelectableAreas(context);
            return 0;
        }

        manager.setActiveAreaName(name);
        BotCommandHelpers.send(source, "§a[Bot] تم تفعيل مزرعة §e" + name + "§a فقط وتعطيل باقي المزارع §7(المحصول: §e" + area.getCropDisplayName() + "§7).");
        BotCommandHelpers.send(source, "§7لإضافة مزرعة أخرى للعمل معها استخدم: §b/bot farm area enable <الاسم>");
        return 1;
    }

    private static int executeSetAreaEnabled(CommandContext<FabricClientCommandSource> context, boolean enabled) {
        FabricClientCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name").toLowerCase(Locale.ROOT);
        FarmAreaManager manager = getScopedManager();

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
        FarmAreaManager manager = getScopedManager();
        FarmArea area = manager.getArea(farmName);
        if (area == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName);
            return 0;
        }
        manager.setAreaEnabled(farmName, enabled);
        BotCommandHelpers.send(source, "§a[Bot] تم ضبط حالة المزرعة §e" + farmName + "§a إلى: " + (enabled ? "§a[مفعل]" : "§c[غير مفعل]") + "§7 | " + manager.getActiveAreasSummary());
        return 1;
    }

    private static int executeEditGeneralHelp(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BotCommandHelpers.send(source, "§6=== [تعديل المزارع المحفوظة] ===");
        BotCommandHelpers.send(source, "§7لتغيير اسم المزرعة: §b/bot farm area edit <اسم_المزرعة> name <الاسم_الجديد>");
        BotCommandHelpers.send(source, "§7لتغيير محصول المزرعة: §b/bot farm area edit <اسم_المزرعة> crop <المحصول|auto>");
        BotCommandHelpers.send(source, "§7لربط الصناديق: §b/bot farm area edit <اسم_المزرعة> chest <open|here|add|seed|crop|list|remove|clear>");
        BotCommandHelpers.send(source, "§7لتحديث حدود المزرعة من pos1/pos2: §b/bot farm area edit <اسم_المزرعة> bounds");
        executeListAreas(context);
        return 1;
    }

    private static int executeEditAreaHelp(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String farmName = StringArgumentType.getString(context, "farm").toLowerCase(Locale.ROOT);
        FarmArea area = getScopedManager().getArea(farmName);
        if (area == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName);
            return 0;
        }
        BotCommandHelpers.send(source, "§6=== [خيارات تعديل المزرعة: §e" + farmName + "§6] ===");
        BotCommandHelpers.send(source, "§7الحالة: " + (area.isEnabled() ? "§a[مفعل]" : "§c[غير مفعل]") + " §7| المحصول الحالي: §e" + area.getCropDisplayName());
        sendFarmChestSummary(source, area, null);
        BotCommandHelpers.send(source, "§71. تغيير الاسم: §b/bot farm area edit " + farmName + " name <الاسم_الجديد>");
        BotCommandHelpers.send(source, "§72. تغيير المحصول: §b/bot farm area edit " + farmName + " crop <wheat|carrot|potato|beetroot|nether_wart|auto>");
        BotCommandHelpers.send(source, "§73. ربط أول صندوق تفتحه (للمحصول): §b/bot farm area edit " + farmName + " chest open");
        BotCommandHelpers.send(source, "§74. ربط الصندوق أمام نظرك أو بجانبك: §b/bot farm area edit " + farmName + " chest here");
        BotCommandHelpers.send(source, "§75. إضافة صندوق إضافي (أكثر من صندوق للمحصول الواحد): §b/bot farm area edit " + farmName + " chest add [open|here]");
        BotCommandHelpers.send(source, "§76. فصل صناديق البذور عن المحصول (القمح/الشمندر): §b/bot farm area edit " + farmName + " chest seed <open|here|add|list|remove|clear>");
        BotCommandHelpers.send(source, "§77. عرض/حذف/مسح الصناديق: §b... chest <list|remove <رقم>|clear>");
        BotCommandHelpers.send(source, "§78. تحديث الحدود: §b/bot farm area edit " + farmName + " bounds §7(باستخدام pos1 و pos2 الحاليين)");
        BotCommandHelpers.send(source, "§79. التفعيل/التعطيل: §b/bot farm area edit " + farmName + " <enable|disable>");
        return 1;
    }

    private static int executeEditName(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String oldName = StringArgumentType.getString(context, "farm").toLowerCase(Locale.ROOT);
        String newName = StringArgumentType.getString(context, "new_name").toLowerCase(Locale.ROOT);
        FarmAreaManager manager = getScopedManager();

        if (oldName.equals(newName)) {
            BotCommandHelpers.send(source, "§e[Bot] الاسم الجديد مطابق للاسم الحالي للمزرعة!");
            return 1;
        }

        FarmArea area = manager.getArea(oldName);
        if (area == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + oldName);
            return 0;
        }

        if (manager.getArea(newName) != null) {
            BotCommandHelpers.send(source, "§c[Bot] يوجد بالفعل مزرعة أخرى تحمل الاسم: §e" + newName);
            return 0;
        }

        boolean renamed = manager.renameArea(oldName, newName);
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
        FarmAreaManager manager = getScopedManager();

        FarmArea area = manager.getArea(farmName);
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
        manager.save();
        if (crop == null) {
            BotCommandHelpers.send(source, "§a[Bot] تم تعديل محصول المزرعة §e" + farmName + "§a إلى نمط: §bتلقائي (أي محصول متاح معك).");
        } else {
            BotCommandHelpers.send(source, "§a[Bot] تم تعديل محصول المزرعة §e" + farmName + "§a إلى: §e" + crop.getArabicName() + " (" + crop.getId() + ")");
        }
        return 1;
    }

    private static String formatCompactChests(List<BlockPos> chests) {
        if (chests == null || chests.isEmpty()) return "غير محدد";
        if (chests.size() == 1) {
            BlockPos p = chests.get(0);
            return p.getX() + "," + p.getY() + "," + p.getZ();
        }
        BlockPos first = chests.get(0);
        return chests.size() + " صناديق (الأول: " + first.getX() + "," + first.getY() + "," + first.getZ() + ")";
    }

    private static void sendFarmChestSummary(
            FabricClientCommandSource source,
            FarmArea area,
            FarmArea.ChestRole filterRole) {
        if (filterRole == null || filterRole == FarmArea.ChestRole.CROP) {
            sendRoleChestLines(source, area, FarmArea.ChestRole.CROP);
        }
        if (filterRole == null || filterRole == FarmArea.ChestRole.SEED) {
            if (area.supportsSeparateSeedChest()) {
                sendRoleChestLines(source, area, FarmArea.ChestRole.SEED);
            } else if (filterRole == FarmArea.ChestRole.SEED) {
                BotCommandHelpers.send(source, "§e[Bot] محصول مزرعة §b" + area.getName() + "§e (" + area.getCropDisplayName() + ") ليس له بذور منفصلة؛ يُستخدم صندوق المحصول نفسه لكليهما.");
            }
        }
    }

    private static void sendRoleChestLines(
            FabricClientCommandSource source,
            FarmArea area,
            FarmArea.ChestRole role) {
        List<BlockPos> list = area.getChests(role);
        String title = role == FarmArea.ChestRole.SEED ? "صناديق البذور" : "صناديق المحصول";
        if (list.isEmpty()) {
            BotCommandHelpers.send(source, "§7" + title + ": §eغير محدد");
            return;
        }
        BotCommandHelpers.send(source, "§7" + title + " (§a" + list.size() + "§7):");
        for (int i = 0; i < list.size(); i++) {
            BlockPos p = list.get(i);
            BotCommandHelpers.send(source, "  §e#" + (i + 1) + " §7(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")");
        }
    }

    private static int executeEditChestOpen(
            CommandContext<FabricClientCommandSource> context,
            FarmArea.ChestRole role,
            boolean append,
            boolean showQuickTip) {
        FabricClientCommandSource source = context.getSource();
        String farmName = StringArgumentType.getString(context, "farm").toLowerCase(Locale.ROOT);
        FarmAreaManager manager = getScopedManager();
        FarmArea area = manager.getArea(farmName);
        if (area == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName);
            return 0;
        }

        if (role == FarmArea.ChestRole.SEED && !area.supportsSeparateSeedChest()) {
            BotCommandHelpers.send(source,
                "§e[Bot] محصول مزرعة §b" + farmName + "§e (" + area.getCropDisplayName() + ") ليس له بذور منفصلة؛ يُستخدم صندوق المحصول نفسه لكليهما.");
            return 0;
        }

        manager.setAwaitingChestTarget(farmName, role, append);
        String actionText = append ? "إضافة صندوق جديد إلى قائمة" : "تعيين";
        BotCommandHelpers.send(source, "§e[Bot] تم تفعيل وضع §bالربط بالفتح (open)§e: " + actionText + " صناديق §b" + role.getArabicLabel() + "§e لمزرعة §a" + farmName + "§e!");
        BotCommandHelpers.send(source, "§6الآن افتح (كليك يمين على) أول صندوق تريده ليتم ربطه تلقائياً بمجرد فتحه.");
        if (showQuickTip) {
            BotCommandHelpers.send(source, "§7خيارات أخرى: §bhere §7(أمام نظرك/بجانبك) | §badd [open|here] §7(صندوق إضافي) | §bseed §7(للبذور) | §blist§7/§bclear");
        }
        return 1;
    }

    private static int executeEditChestHere(
            CommandContext<FabricClientCommandSource> context,
            FarmArea.ChestRole role,
            boolean append) {
        FabricClientCommandSource source = context.getSource();
        String farmName = StringArgumentType.getString(context, "farm").toLowerCase(Locale.ROOT);
        FarmAreaManager manager = getScopedManager();
        FarmArea area = manager.getArea(farmName);
        if (area == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName);
            return 0;
        }

        if (role == FarmArea.ChestRole.SEED && !area.supportsSeparateSeedChest()) {
            BotCommandHelpers.send(source,
                "§e[Bot] محصول مزرعة §b" + farmName + "§e (" + area.getCropDisplayName() + ") ليس له بذور منفصلة؛ يُستخدم صندوق المحصول نفسه لكليهما.");
            return 0;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return 0;
        }

        BlockPos fertChest = manager.getFertilizerChestPos();
        FarmArea.ChestRole otherRole = (role == FarmArea.ChestRole.CROP)
            ? FarmArea.ChestRole.SEED
            : FarmArea.ChestRole.CROP;

        BlockPos targetChest = resolveContainerFromLookOrNearby(client, check ->
            FarmAreaManager.isSameStorageContainer(client.level, check, fertChest)
                || FarmAreaManager.containsStorageContainer(client.level, area.getChests(otherRole), check)
                || (append && FarmAreaManager.containsStorageContainer(client.level, area.getChests(role), check)));

        if (targetChest == null) {
            BotCommandHelpers.send(source, "§c[Bot] لم يتم العثور على أي صندوق أو برميل أو شلكر أمام نظرك أو بجوارك (نطاق 3 بلوكات)!");
            BotCommandHelpers.send(source, "§7طريقة عمل §bhere§7: وجّه مؤشر التصويب نحو الصندوق أو قف بجانبه ثم نفّذ الأمر.");
            BotCommandHelpers.send(source, "§7أو استخدم §b/bot farm area edit " + farmName + " chest " + (append ? "add open" : "open") + " §7لربط أول صندوق تفتحه.");
            return 0;
        }

        FarmAreaManager.ChestAssignResult result = manager.assignAreaChest(client.level, farmName, role, targetChest, append);
        BotCommandHelpers.send(source, result.message());
        return result.success() ? 1 : 0;
    }

    private static int executeEditChestList(
            CommandContext<FabricClientCommandSource> context,
            FarmArea.ChestRole filterRole) {
        FabricClientCommandSource source = context.getSource();
        String farmName = StringArgumentType.getString(context, "farm").toLowerCase(Locale.ROOT);
        FarmAreaManager manager = getScopedManager();
        FarmArea area = manager.getArea(farmName);
        if (area == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName);
            return 0;
        }

        BotCommandHelpers.send(source, "§6=== [صناديق مزرعة: §e" + farmName + "§6] ===");
        sendFarmChestSummary(source, area, filterRole);
        String prefix = "/bot farm area edit " + farmName + " chest" + (filterRole == FarmArea.ChestRole.SEED ? " seed" : (filterRole == FarmArea.ChestRole.CROP ? " crop" : ""));
        BotCommandHelpers.send(source, "§7للتعيين بالفتح: §b" + prefix + " open §7| أمامك: §bhere §7| لإضافة صندوق إضافي: §badd [open|here]");
        return 1;
    }

    private static int executeEditChestRemove(
            CommandContext<FabricClientCommandSource> context,
            FarmArea.ChestRole role) {
        FabricClientCommandSource source = context.getSource();
        String farmName = StringArgumentType.getString(context, "farm").toLowerCase(Locale.ROOT);
        int oneBasedIndex = IntegerArgumentType.getInteger(context, "index");
        FarmAreaManager manager = getScopedManager();
        FarmArea area = manager.getArea(farmName);
        if (area == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName);
            return 0;
        }

        List<BlockPos> list = area.getChests(role);
        if (list.isEmpty()) {
            BotCommandHelpers.send(source, "§e[Bot] لا توجد صناديق §b" + role.getArabicLabel() + "§e مسجلة في مزرعة §e" + farmName + "§e.");
            return 0;
        }

        if (oneBasedIndex < 1 || oneBasedIndex > list.size()) {
            BotCommandHelpers.send(source, "§c[Bot] الرقم §e" + oneBasedIndex + "§c غير صالح! الأرقام المتاحة: من §e1§c إلى §e" + list.size() + "§c.");
            return 0;
        }

        BlockPos removed = area.removeChestAt(role, oneBasedIndex - 1);
        manager.save();
        BotCommandHelpers.send(source, "§a[Bot] تم حذف صندوق §b" + role.getArabicLabel() + "§a رقم §e#" + oneBasedIndex
            + " §7(" + removed.getX() + ", " + removed.getY() + ", " + removed.getZ() + ") §aمن مزرعة §e" + farmName
            + "§a. المتبقي: §e" + area.getChests(role).size());
        return 1;
    }

    private static int executeEditChestClear(
            CommandContext<FabricClientCommandSource> context,
            FarmArea.ChestRole role) {
        FabricClientCommandSource source = context.getSource();
        String farmName = StringArgumentType.getString(context, "farm").toLowerCase(Locale.ROOT);
        FarmAreaManager manager = getScopedManager();
        FarmArea area = manager.getArea(farmName);
        if (area == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName);
            return 0;
        }

        if (area.getChests(role).isEmpty()) {
            BotCommandHelpers.send(source, "§e[Bot] لا توجد صناديق §b" + role.getArabicLabel() + "§e مرتبطة بمزرعة §e" + farmName + "§e بالفعل.");
            return 1;
        }

        area.clearChests(role);
        manager.save();
        BotCommandHelpers.send(source, "§a[Bot] تم مسح جميع صناديق §b" + role.getArabicLabel() + "§a لمزرعة §e" + farmName + "§a بنجاح.");
        return 1;
    }

    private static int executeEditBounds(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String farmName = StringArgumentType.getString(context, "farm").toLowerCase(Locale.ROOT);
        FarmAreaManager manager = getScopedManager();
        FarmArea existing = manager.getArea(farmName);
        if (existing == null) {
            BotCommandHelpers.send(source, "§c[Bot] لا توجد مزرعة بهذا الاسم: §e" + farmName);
            return 0;
        }

        BlockPos p1 = manager.getPos1();
        BlockPos p2 = manager.getPos2();
        if (p1 == null || p2 == null) {
            BotCommandHelpers.send(source, "§c[Bot] يجب تحديد pos1 و pos2 أولاً بالوقوف على النقطتين وكتابة:");
            BotCommandHelpers.send(source, "§e/bot farm area pos1 §7ثم §e/bot farm area pos2");
            return 0;
        }

        boolean updated = manager.updateAreaBounds(farmName, p1, p2);
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
        FarmAreaManager manager = getScopedManager();
        boolean next = !manager.isSleepAtNight();
        manager.setSleepAtNight(next);
        BotCommandHelpers.send(context.getSource(), "§a[Bot] تم " + (next ? "§aتفعيل" : "§cتعطيل") + "§a ميزة النوم التلقائي عند حلول الليل.");
        return 1;
    }

    private static int executeSetSleep(CommandContext<FabricClientCommandSource> context, boolean enable) {
        getScopedManager().setSleepAtNight(enable);
        BotCommandHelpers.send(context.getSource(), "§a[Bot] تم " + (enable ? "§aتفعيل" : "§cتعطيل") + "§a ميزة النوم التلقائي عند حلول الليل.");
        return 1;
    }

    private static int executeBedStatus(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        FarmAreaManager manager = getScopedManager();
        BlockPos bedPos = manager.getBedPos();
        if (bedPos != null) {
            BotCommandHelpers.send(source, "§a[Bot] السرير المعتمد في §b" + manager.getCurrentDimensionDisplayName() + "§a محدد عند: §e" + bedPos.getX() + ", " + bedPos.getY() + ", " + bedPos.getZ());
        } else {
            BotCommandHelpers.send(source, "§e[Bot] لم يتم تحديد سرير مخصص يدوياً في §b" + manager.getCurrentDimensionDisplayName() + "§e (سيبحث البوت تلقائياً عن أقرب سرير عند حلول الليل).");
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
        if (client.level != null && !FarmAreaManager.isBedDimensionSupported(client.level)) {
            BotCommandHelpers.send(source, "§c[Bot] لا يمكن تعيين أو استخدام سرير في هذا البُعد لأن الأسرّة تنفجر هنا!");
            return 0;
        }

        FarmAreaManager manager = getScopedManager();
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

        manager.setBedPos(targetBed);
        BotCommandHelpers.send(source, "§a[Bot] تم تعيين سرير نقطة الإحياء بنجاح في §b" + manager.getCurrentDimensionDisplayName() + "§a عند: §e" + targetBed.getX() + ", " + targetBed.getY() + ", " + targetBed.getZ());
        return 1;
    }

    private static int executeClearBed(CommandContext<FabricClientCommandSource> context) {
        FarmAreaManager manager = getScopedManager();
        manager.setBedPos(null);
        BotCommandHelpers.send(context.getSource(), "§a[Bot] تم إلغاء السرير المخصص في §b" + manager.getCurrentDimensionDisplayName() + "§a والعودة للبحث التلقائي عن أقرب سرير.");
        return 1;
    }

    private static int executeFertilizerChestStatus(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        FarmAreaManager manager = getScopedManager();
        BlockPos chestPos = manager.getFertilizerChestPos();
        if (chestPos != null) {
            BotCommandHelpers.send(source, "§a[Bot] صندوق السماد (Bone Meal) في §b" + manager.getCurrentDimensionDisplayName() + "§a محدد عند: §e" + chestPos.getX() + ", " + chestPos.getY() + ", " + chestPos.getZ());
        } else {
            BotCommandHelpers.send(source, "§e[Bot] لم يتم تحديد صندوق مخصص لوضع السماد في هذا العالم/البُعد بعد!");
            BotCommandHelpers.send(source, "§7لتحديد الصندوق: استخدم §b/bot farm fertilizer chest open §7لربط أول صندوق تفتحه، أو §bhere §7للصندوق أمام نظرك/بجانبك.");
        }
        return 1;
    }

    private static int executeOpenFertilizerChest(CommandContext<FabricClientCommandSource> context) {
        FarmAreaManager manager = getScopedManager();
        manager.setAwaitingFertilizerChest(true);
        BotCommandHelpers.send(context.getSource(), "§e[Bot] تم تفعيل وضع §bالربط بالفتح (open)§e لصندوق السماد!");
        BotCommandHelpers.send(context.getSource(), "§6الآن افتح (كليك يمين على) أول صندوق تريده ليتم ربطه كصندوق للسماد تلقائياً.");
        return 1;
    }

    private static int executeHereFertilizerChest(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return 0;
        }

        FarmAreaManager manager = getScopedManager();
        BlockPos targetChest = resolveContainerFromLookOrNearby(
            client,
            check -> manager.findAreaUsingChest(client.level, check) != null
        );
        if (targetChest == null) {
            BotCommandHelpers.send(source, "§c[Bot] لم يتم العثور على صندوق أمام نظرك أو بجوارك (نطاق 3 بلوكات)!");
            BotCommandHelpers.send(source, "§7طريقة عمل §bhere§7: وجّه نظرك على الصندوق أو قف بجانبه، أو استخدم §b/bot farm fertilizer chest open§7.");
            return 0;
        }
        FarmAreaManager.ChestAssignResult result = manager.assignFertilizerChest(client.level, targetChest);
        BotCommandHelpers.send(source, result.message());
        return result.success() ? 1 : 0;
    }

    private static int executeSetFertilizerChest(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return 0;
        }

        FarmAreaManager manager = getScopedManager();
        BlockPos targetChest = resolveContainerFromLookOrNearby(
            client,
            check -> manager.findAreaUsingChest(client.level, check) != null
        );

        if (targetChest != null) {
            FarmAreaManager.ChestAssignResult result = manager.assignFertilizerChest(client.level, targetChest);
            BotCommandHelpers.send(source, result.message());
            return result.success() ? 1 : 0;
        }

        return executeOpenFertilizerChest(context);
    }

    private static int executeClearFertilizerChest(CommandContext<FabricClientCommandSource> context) {
        FarmAreaManager manager = getScopedManager();
        manager.setFertilizerChestPos(null);
        manager.setAwaitingFertilizerChest(false);
        BotCommandHelpers.send(context.getSource(), "§a[Bot] تم إلغاء تحديد صندوق السماد بنجاح.");
        return 1;
    }

    private static BlockPos resolveContainerFromLookOrNearby(
            Minecraft client,
            java.util.function.Predicate<BlockPos> shouldPreferSkip) {
        if (client.level == null || client.player == null) {
            return null;
        }
        ClientLevel level = client.level;

        // الأولوية القصوى لما ينظر إليه اللاعب مباشرة تحت مؤشر التصويب (Crosshair)
        if (client.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            BlockPos lookPos = bhr.getBlockPos();
            if (FarmAreaManager.isStorageContainerBlock(level.getBlockState(lookPos))) {
                return lookPos.immutable();
            }
        }

        // البحث عن أقرب حاوية في المحيط القريب (3 بلوكات) مع تفضيل الصناديق غير المتعارضة
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
