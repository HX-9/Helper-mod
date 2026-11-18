package com.helpermod.bot.command;

import com.helpermod.bot.FarmAreaManager;
import com.helpermod.bot.hunt.HuntConfigManager;
import com.helpermod.bot.hunt.HuntZone;
import com.helpermod.bot.hunt.MobHuntController;
import com.helpermod.bot.hunt.MobTypeRegistry;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HuntCommand {

    private HuntCommand() {}

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal("hunt")
            .executes(HuntCommand::sendHuntHelp)
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("start")
                .executes(ctx -> executeStart(ctx, null))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("zone")
                    .executes(ctx -> executeStart(ctx, HuntConfigManager.HuntMode.ZONE)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("patrol")
                    .executes(ctx -> executeStart(ctx, HuntConfigManager.HuntMode.PATROL)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("spot")
                    .executes(ctx -> executeStart(ctx, HuntConfigManager.HuntMode.SPOT))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stop")
                .executes(HuntCommand::executeStop))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status")
                .executes(HuntCommand::executeStatus))
            .then(buildTargetCommands())
            .then(buildRuleCommands())
            .then(buildLootCommands())
            .then(buildAreaCommands());
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildTargetCommands() {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal("target")
            .executes(HuntCommand::executeTargetList)
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("mob", StringArgumentType.greedyString())
                    .suggests(BotCommandHelpers.MOB_TARGETS_WITH_PRESETS)
                    .executes(HuntCommand::executeTargetAdd)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("mob", StringArgumentType.greedyString())
                    .suggests(BotCommandHelpers.HUNT_ACTIVE_TARGETS)
                    .executes(HuntCommand::executeTargetRemove)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
                .executes(HuntCommand::executeTargetList))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
                .executes(HuntCommand::executeTargetClear));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildRuleCommands() {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal("rule")
            .executes(HuntCommand::executeRuleStatus)
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("skip_babies")
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on")
                    .executes(ctx -> executeSetSkipBabies(ctx, true)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off")
                    .executes(ctx -> executeSetSkipBabies(ctx, false))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("protect_named")
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on")
                    .executes(ctx -> executeSetProtectNamed(ctx, true)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off")
                    .executes(ctx -> executeSetProtectNamed(ctx, false))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("min_keep")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("count", IntegerArgumentType.integer(0, 20))
                    .executes(HuntCommand::executeSetMinKeep)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("radius")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("blocks", IntegerArgumentType.integer(8, 128))
                    .executes(HuntCommand::executeSetRadius)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("sleep")
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("auto")
                    .executes(ctx -> executeSetNightBehavior(ctx, HuntConfigManager.NightBehavior.AUTO)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on")
                    .executes(ctx -> executeSetNightBehavior(ctx, HuntConfigManager.NightBehavior.SLEEP)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("sleep")
                    .executes(ctx -> executeSetNightBehavior(ctx, HuntConfigManager.NightBehavior.SLEEP)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off")
                    .executes(ctx -> executeSetNightBehavior(ctx, HuntConfigManager.NightBehavior.STAY_AWAKE)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stay_awake")
                    .executes(ctx -> executeSetNightBehavior(ctx, HuntConfigManager.NightBehavior.STAY_AWAKE))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("weapon")
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("auto")
                    .executes(ctx -> executeSetWeaponPref(ctx, HuntConfigManager.WeaponPreference.AUTO_SMART)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("sword")
                    .executes(ctx -> executeSetWeaponPref(ctx, HuntConfigManager.WeaponPreference.SWORD)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("axe")
                    .executes(ctx -> executeSetWeaponPref(ctx, HuntConfigManager.WeaponPreference.AXE))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildLootCommands() {
        LiteralArgumentBuilder<FabricClientCommandSource> chestNode = attachLootChestActions(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("chest")
                .executes(ctx -> executeLootChestOpen(ctx, null, false)),
            false
        );
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal("loot")
            .executes(ctx -> executeLootChestList(ctx, null))
            .then(chestNode);
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildAreaCommands() {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal("area")
            .executes(HuntCommand::executeListZones)
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("pos1")
                .executes(HuntCommand::executePos1))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("pos2")
                .executes(HuntCommand::executePos2))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("create")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .executes(HuntCommand::executeCreateZone)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("x1", IntegerArgumentType.integer())
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("y1", IntegerArgumentType.integer())
                            .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("z1", IntegerArgumentType.integer())
                                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("x2", IntegerArgumentType.integer())
                                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("y2", IntegerArgumentType.integer())
                                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("z2", IntegerArgumentType.integer())
                                            .executes(HuntCommand::executeAddCoordZone)))))))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("edit")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("zone", StringArgumentType.string())
                    .suggests(BotCommandHelpers.HUNT_ZONES)
                    .executes(HuntCommand::executeEditZoneHelp)
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("name")
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("new_name", StringArgumentType.string())
                            .executes(HuntCommand::executeEditZoneName)))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("bounds")
                        .executes(HuntCommand::executeEditZoneBounds))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("enable")
                        .executes(ctx -> executeSetSingleZoneEnabled(ctx, true, "zone")))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("disable")
                        .executes(ctx -> executeSetSingleZoneEnabled(ctx, false, "zone")))
                    .then(attachLootChestActions(
                        LiteralArgumentBuilder.<FabricClientCommandSource>literal("chest")
                            .executes(ctx -> executeLootChestOpen(ctx, "zone", false)),
                        true))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("enable")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .suggests(BotCommandHelpers.ENABLE_HUNT_ZONES)
                    .executes(ctx -> executeSetZoneEnabled(ctx, true))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("disable")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .suggests(BotCommandHelpers.DISABLE_HUNT_ZONES)
                    .executes(ctx -> executeSetZoneEnabled(ctx, false))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("select")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .suggests(BotCommandHelpers.SELECTABLE_HUNT_ZONES)
                    .executes(HuntCommand::executeSelectZone)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .suggests(BotCommandHelpers.HUNT_ZONES)
                    .executes(HuntCommand::executeRemoveZone)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
                .executes(HuntCommand::executeListZones));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> attachLootChestActions(
            LiteralArgumentBuilder<FabricClientCommandSource> node,
            boolean hasZoneArg) {
        String zoneArg = hasZoneArg ? "zone" : null;
        return node
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("open")
                .executes(ctx -> executeLootChestOpen(ctx, zoneArg, false)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("here")
                .executes(ctx -> executeLootChestHere(ctx, zoneArg, false)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
                .executes(ctx -> executeLootChestOpen(ctx, zoneArg, true))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("open")
                    .executes(ctx -> executeLootChestOpen(ctx, zoneArg, true)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("here")
                    .executes(ctx -> executeLootChestHere(ctx, zoneArg, true))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
                .executes(ctx -> executeLootChestList(ctx, zoneArg)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("index", IntegerArgumentType.integer(1))
                    .executes(ctx -> executeLootChestRemove(ctx, zoneArg))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
                .executes(ctx -> executeLootChestClear(ctx, zoneArg)));
    }

    private static HuntConfigManager getScopedConfig() {
        HuntConfigManager mgr = HuntConfigManager.getInstance();
        mgr.updateContext(Minecraft.getInstance());
        return mgr;
    }

    private static int sendHuntHelp(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BotCommandHelpers.send(source, "§6=== [أوامر وضع تجميع وصيد الموبات - /bot hunt] ===");
        BotCommandHelpers.send(source, "§e/bot hunt start [zone|patrol|spot] §7- بدء الصيد داخل الحظائر أو بدورية أو بنقطة ثابتة");
        BotCommandHelpers.send(source, "§e/bot hunt stop | status §7- إيقاف الصيد أو عرض الحالة");
        BotCommandHelpers.send(source, "§e/bot hunt target <add|remove|list|clear> <mob> §7- إدارة الحيوانات والموبات المستهدفة (يدعم animals / hostile)");
        BotCommandHelpers.send(source, "§e/bot hunt rule <skip_babies|protect_named|min_keep|radius|sleep|weapon> §7- ضبط قواعد الصيد والبحث");
        BotCommandHelpers.send(source, "§e/bot hunt loot chest <open|here|add|list|remove|clear> §7- صناديق الغنائم العامة لهذا البُعد");
        BotCommandHelpers.send(source, "§e/bot hunt area <pos1|pos2|create|add|edit|enable|disable|select|list|remove> §7- إدارة مناطق/حظائر الصيد");
        return 1;
    }

    private static int executeStart(CommandContext<FabricClientCommandSource> context, HuntConfigManager.HuntMode mode) {
        MobHuntController.getInstance().start(Minecraft.getInstance(), mode);
        return 1;
    }

    private static int executeStop(CommandContext<FabricClientCommandSource> context) {
        if (!MobHuntController.getInstance().isHunting()) {
            BotCommandHelpers.send(context.getSource(), "§e[Bot - Hunt] وضع صيد الموبات متوقف بالفعل!");
            return 1;
        }
        MobHuntController.getInstance().stop(Minecraft.getInstance());
        return 1;
    }

    private static int executeStatus(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        HuntConfigManager config = getScopedConfig();
        MobHuntController ctrl = MobHuntController.getInstance();
        boolean running = ctrl.isHunting();

        BotCommandHelpers.send(source, "§6=== [حالة وضع صيد وتجميع الموبات] ===");
        BotCommandHelpers.send(source, "§7الحالة: " + (running ? "§aيعمل الآن (" + ctrl.getActiveMode().getArabicName() + ")" : "§cمتوقف"));
        BotCommandHelpers.send(source, "§7السياق الحالي: §b" + config.getCurrentDimensionDisplayName());
        if (running) {
            BotCommandHelpers.send(source, "§7المرحلة: §b" + ctrl.getCurrentStage().getArabicName() + " §7| الصيد في الجلسة: §e" + ctrl.getKillCount());
        }
        BotCommandHelpers.send(source, "§7الأهداف المسجلة: §e" + config.getTargetMobIds().size()
            + " §7| المناطق المفعلة: §a" + config.getActiveZones().size() + "§7/" + config.getAllZones().size());
        BotCommandHelpers.send(source, "§7صناديق الغنائم العامة: §e" + BotCommandHelpers.formatCompactChests(config.getGlobalLootChests()));
        executeRuleStatus(context);
        return 1;
    }

    private static int executeTargetAdd(CommandContext<FabricClientCommandSource> context) {
        String raw = StringArgumentType.getString(context, "mob");
        List<String> resolved = MobTypeRegistry.expandPresetOrSingle(raw);
        if (resolved.isEmpty()) {
            BotCommandHelpers.send(context.getSource(), "§c[Bot - Hunt] نوع غير معروف: §e" + raw + "§c! استخدم Tab (مثل: cow, sheep, animals, hostile).");
            return 0;
        }
        HuntConfigManager config = getScopedConfig();
        int added = 0;
        for (String id : resolved) {
            if (config.addTargetMob(id)) added++;
        }
        if (resolved.size() == 1) {
            String display = MobTypeRegistry.getDisplayName(resolved.get(0));
            BotCommandHelpers.send(context.getSource(), added > 0
                ? "§a[Bot - Hunt] تم إضافة §e" + display + "§a إلى قائمة الموبات المستهدفة."
                : "§e[Bot - Hunt] §6" + display + " §eموجود بالفعل في القائمة.");
        } else {
            BotCommandHelpers.send(context.getSource(), "§a[Bot - Hunt] تم إضافة §e" + added + " §aأنواع جديدة (من أصل " + resolved.size() + ") إلى قائمة الصيد.");
        }
        return 1;
    }

    private static int executeTargetRemove(CommandContext<FabricClientCommandSource> context) {
        String raw = StringArgumentType.getString(context, "mob");
        List<String> resolved = MobTypeRegistry.expandPresetOrSingle(raw);
        if (resolved.isEmpty()) {
            BotCommandHelpers.send(context.getSource(), "§c[Bot - Hunt] نوع غير معروف: §e" + raw);
            return 0;
        }
        HuntConfigManager config = getScopedConfig();
        int removed = 0;
        for (String id : resolved) {
            if (config.removeTargetMob(id)) removed++;
        }
        BotCommandHelpers.send(context.getSource(), removed > 0
            ? "§a[Bot - Hunt] تم حذف §e" + (resolved.size() == 1 ? MobTypeRegistry.getDisplayName(resolved.get(0)) : (removed + " أنواع")) + "§a من قائمة الصيد."
            : "§e[Bot - Hunt] لم يكن مسجلاً في القائمة.");
        return 1;
    }

    private static int executeTargetList(CommandContext<FabricClientCommandSource> context) {
        Set<String> targets = getScopedConfig().getTargetMobIds();
        if (targets.isEmpty()) {
            BotCommandHelpers.send(context.getSource(), "§e[Bot - Hunt] قائمة الصيد فارغة! أضف أهدافاً عبر: §b/bot hunt target add <mob|animals|hostile>");
            return 1;
        }
        BotCommandHelpers.send(context.getSource(), "§6=== [قائمة الموبات/الحيوانات المستهدفة (" + targets.size() + ")] ===");
        for (String id : targets) {
            BotCommandHelpers.send(context.getSource(), " §7- §e" + MobTypeRegistry.getDisplayName(id));
        }
        return 1;
    }

    private static int executeTargetClear(CommandContext<FabricClientCommandSource> context) {
        getScopedConfig().clearTargetMobs();
        BotCommandHelpers.send(context.getSource(), "§c[Bot - Hunt] تم تفريغ قائمة الموبات المستهدفة بالكامل.");
        return 1;
    }

    private static int executeRuleStatus(CommandContext<FabricClientCommandSource> context) {
        HuntConfigManager c = getScopedConfig();
        FabricClientCommandSource s = context.getSource();
        BotCommandHelpers.send(s, "§7قواعد الصيد: تجاهل الصغار=" + (c.isSkipBabies() ? "§aON" : "§cOFF")
            + " §7| حماية المسمّاة والمروّضة=" + (c.isProtectNamedAndTamed() ? "§aON" : "§cOFF")
            + " §7| أقل عدد بالحظيرة=§e" + c.getMinKeepInZones()
            + " §7| نصف قطر البحث=§e" + c.getPatrolRadius());
        BotCommandHelpers.send(s, "§7السلاح=§b" + c.getWeaponPreference().getArabicName()
            + " §7| النوم الليلي=§b" + c.getNightBehavior().getArabicName());
        return 1;
    }

    private static int executeSetSkipBabies(CommandContext<FabricClientCommandSource> ctx, boolean val) {
        getScopedConfig().setSkipBabies(val);
        BotCommandHelpers.send(ctx.getSource(), "§a[Bot - Hunt] تجاهل الصغار (skip_babies): " + (val ? "§aمفعل" : "§cمعطل"));
        return 1;
    }

    private static int executeSetProtectNamed(CommandContext<FabricClientCommandSource> ctx, boolean val) {
        getScopedConfig().setProtectNamedAndTamed(val);
        BotCommandHelpers.send(ctx.getSource(), "§a[Bot - Hunt] حماية المروّضة والمسمّاة (protect_named): " + (val ? "§aمفعل" : "§cمعطل"));
        return 1;
    }

    private static int executeSetMinKeep(CommandContext<FabricClientCommandSource> ctx) {
        int val = IntegerArgumentType.getInteger(ctx, "count");
        getScopedConfig().setMinKeepInZones(val);
        BotCommandHelpers.send(ctx.getSource(), "§a[Bot - Hunt] تم ضبط الحد الأدنى للحيوانات المتروكة بالحظيرة على: §e" + val);
        return 1;
    }

    private static int executeSetRadius(CommandContext<FabricClientCommandSource> ctx) {
        int val = IntegerArgumentType.getInteger(ctx, "blocks");
        getScopedConfig().setPatrolRadius(val);
        BotCommandHelpers.send(ctx.getSource(), "§a[Bot - Hunt] تم ضبط نصف قطر البحث والدورية على: §e" + val + " بلوكة");
        return 1;
    }

    private static int executeSetNightBehavior(CommandContext<FabricClientCommandSource> ctx, HuntConfigManager.NightBehavior behavior) {
        getScopedConfig().setNightBehavior(behavior);
        BotCommandHelpers.send(ctx.getSource(), "§a[Bot - Hunt] تم ضبط سلوك الليل على: §b" + behavior.getArabicName());
        return 1;
    }

    private static int executeSetWeaponPref(CommandContext<FabricClientCommandSource> ctx, HuntConfigManager.WeaponPreference pref) {
        getScopedConfig().setWeaponPreference(pref);
        BotCommandHelpers.send(ctx.getSource(), "§a[Bot - Hunt] تم ضبط أولوية السلاح على: §b" + pref.getArabicName());
        return 1;
    }

    // ==================== صناديق الغنائم (المناطق + العام) ====================

    private static String getZoneNameArg(CommandContext<FabricClientCommandSource> ctx, String key) {
        return key != null ? StringArgumentType.getString(ctx, key).toLowerCase(Locale.ROOT) : null;
    }

    private static int executeLootChestOpen(CommandContext<FabricClientCommandSource> ctx, String zoneArgKey, boolean append) {
        HuntConfigManager mgr = getScopedConfig();
        String zoneName = getZoneNameArg(ctx, zoneArgKey);
        if (zoneName != null) {
            if (mgr.getZone(zoneName) == null) {
                BotCommandHelpers.send(ctx.getSource(), "§c[Bot - Hunt] لا توجد منطقة بهذا الاسم: §e" + zoneName);
                return 0;
            }
            mgr.setAwaitingZoneLootChest(zoneName, append);
            BotCommandHelpers.send(ctx.getSource(), "§e[Bot - Hunt] تم تفعيل وضع §bالربط بالفتح (open)§e لمنطقة §a" + zoneName + "§e: افتح أول صندوق تريده لربطه.");
        } else {
            mgr.setAwaitingGlobalLootChest(append);
            BotCommandHelpers.send(ctx.getSource(), "§e[Bot - Hunt] تم تفعيل وضع §bالربط بالفتح (open)§e لصندوق الغنائم العام: افتح أول صندوق تريده لربطه.");
        }
        return 1;
    }

    private static int executeLootChestHere(CommandContext<FabricClientCommandSource> ctx, String zoneArgKey, boolean append) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return 0;
        HuntConfigManager mgr = getScopedConfig();
        String zoneName = getZoneNameArg(ctx, zoneArgKey);

        List<BlockPos> currentList;
        if (zoneName != null) {
            HuntZone zone = mgr.getZone(zoneName);
            if (zone == null) {
                BotCommandHelpers.send(ctx.getSource(), "§c[Bot - Hunt] لا توجد منطقة بهذا الاسم: §e" + zoneName);
                return 0;
            }
            currentList = zone.getLootChests();
        } else {
            currentList = mgr.getGlobalLootChests();
        }

        BlockPos fertChest = FarmAreaManager.getInstance().getFertilizerChestPos();
        BlockPos found = BotCommandHelpers.resolveContainerFromLookOrNearby(client, check ->
            FarmAreaManager.isSameStorageContainer(client.level, check, fertChest)
                || (append && FarmAreaManager.containsStorageContainer(client.level, currentList, check)));
        if (found == null) {
            BotCommandHelpers.send(ctx.getSource(), "§c[Bot - Hunt] لم يتم العثور على صندوق أمام نظرك أو بجوارك (نطاق 3 بلوكات)!");
            return 0;
        }

        FarmAreaManager.ChestAssignResult res = zoneName != null
            ? mgr.assignZoneLootChest(client.level, zoneName, found, append)
            : mgr.assignGlobalLootChest(client.level, found, append);
        BotCommandHelpers.send(ctx.getSource(), res.message());
        return res.success() ? 1 : 0;
    }

    private static int executeLootChestList(CommandContext<FabricClientCommandSource> ctx, String zoneArgKey) {
        HuntConfigManager mgr = getScopedConfig();
        String zoneName = getZoneNameArg(ctx, zoneArgKey);
        List<BlockPos> chests;
        if (zoneName != null) {
            HuntZone zone = mgr.getZone(zoneName);
            if (zone == null) {
                BotCommandHelpers.send(ctx.getSource(), "§c[Bot - Hunt] لا توجد منطقة بهذا الاسم: §e" + zoneName);
                return 0;
            }
            chests = zone.getLootChests();
            BotCommandHelpers.send(ctx.getSource(), "§6=== [صناديق غنائم منطقة: §e" + zoneName + "§6] ===");
        } else {
            chests = mgr.getGlobalLootChests();
            BotCommandHelpers.send(ctx.getSource(), "§6=== [صناديق الغنائم العامة - " + mgr.getCurrentDimensionDisplayName() + "] ===");
        }
        if (chests.isEmpty()) {
            BotCommandHelpers.send(ctx.getSource(), "§7لا توجد صناديق مسجلة.");
            return 1;
        }
        for (int i = 0; i < chests.size(); i++) {
            BlockPos p = chests.get(i);
            BotCommandHelpers.send(ctx.getSource(), " §e#" + (i + 1) + " §7(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")");
        }
        return 1;
    }

    private static int executeLootChestRemove(CommandContext<FabricClientCommandSource> ctx, String zoneArgKey) {
        int index = IntegerArgumentType.getInteger(ctx, "index");
        HuntConfigManager mgr = getScopedConfig();
        String zoneName = getZoneNameArg(ctx, zoneArgKey);
        BlockPos removed;
        if (zoneName != null) {
            HuntZone zone = mgr.getZone(zoneName);
            if (zone == null) {
                BotCommandHelpers.send(ctx.getSource(), "§c[Bot - Hunt] لا توجد منطقة بهذا الاسم: §e" + zoneName);
                return 0;
            }
            removed = zone.removeLootChestAt(index - 1);
            if (removed != null) mgr.save();
        } else {
            removed = mgr.removeGlobalLootChestAt(index - 1);
        }
        if (removed == null) {
            BotCommandHelpers.send(ctx.getSource(), "§c[Bot - Hunt] رقم الصندوق غير صالح.");
            return 0;
        }
        BotCommandHelpers.send(ctx.getSource(), "§a[Bot - Hunt] تم حذف صندوق الغنائم رقم §e#" + index + "§a.");
        return 1;
    }

    private static int executeLootChestClear(CommandContext<FabricClientCommandSource> ctx, String zoneArgKey) {
        HuntConfigManager mgr = getScopedConfig();
        String zoneName = getZoneNameArg(ctx, zoneArgKey);
        if (zoneName != null) {
            HuntZone zone = mgr.getZone(zoneName);
            if (zone == null) {
                BotCommandHelpers.send(ctx.getSource(), "§c[Bot - Hunt] لا توجد منطقة بهذا الاسم: §e" + zoneName);
                return 0;
            }
            zone.clearLootChests();
            mgr.save();
            BotCommandHelpers.send(ctx.getSource(), "§a[Bot - Hunt] تم مسح جميع صناديق غنائم منطقة §e" + zoneName + "§a.");
        } else {
            mgr.clearGlobalLootChests();
            BotCommandHelpers.send(ctx.getSource(), "§a[Bot - Hunt] تم مسح صناديق الغنائم العامة في هذا البُعد.");
        }
        return 1;
    }

    // ==================== مناطق وحظائر الصيد ====================

    private static int executePos1(CommandContext<FabricClientCommandSource> ctx) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            BlockPos pos = client.player.blockPosition();
            getScopedConfig().setPos1(pos);
            BotCommandHelpers.send(ctx.getSource(), "§a[Bot - Hunt] تم تعيين pos1 عند: §e" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
        }
        return 1;
    }

    private static int executePos2(CommandContext<FabricClientCommandSource> ctx) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            BlockPos pos = client.player.blockPosition();
            getScopedConfig().setPos2(pos);
            BotCommandHelpers.send(ctx.getSource(), "§a[Bot - Hunt] تم تعيين pos2 عند: §e" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
        }
        return 1;
    }

    private static int executeCreateZone(CommandContext<FabricClientCommandSource> ctx) {
        HuntConfigManager mgr = getScopedConfig();
        return createZone(ctx.getSource(), StringArgumentType.getString(ctx, "name"), mgr.getPos1(), mgr.getPos2());
    }

    private static int executeAddCoordZone(CommandContext<FabricClientCommandSource> ctx) {
        BlockPos p1 = new BlockPos(
            IntegerArgumentType.getInteger(ctx, "x1"),
            IntegerArgumentType.getInteger(ctx, "y1"),
            IntegerArgumentType.getInteger(ctx, "z1")
        );
        BlockPos p2 = new BlockPos(
            IntegerArgumentType.getInteger(ctx, "x2"),
            IntegerArgumentType.getInteger(ctx, "y2"),
            IntegerArgumentType.getInteger(ctx, "z2")
        );
        return createZone(ctx.getSource(), StringArgumentType.getString(ctx, "name"), p1, p2);
    }

    private static int createZone(FabricClientCommandSource source, String rawName, BlockPos p1, BlockPos p2) {
        if (p1 == null || p2 == null) {
            BotCommandHelpers.send(source, "§c[Bot - Hunt] حدد pos1 و pos2 أولاً: §e/bot hunt area pos1 §7ثم §epos2");
            return 0;
        }
        String name = rawName.toLowerCase(Locale.ROOT);
        HuntConfigManager mgr = getScopedConfig();
        if (!mgr.addZone(name, p1, p2)) {
            BotCommandHelpers.send(source, "§c[Bot - Hunt] توجد منطقة صيد بهذا الاسم بالفعل: §e" + name);
            return 0;
        }
        BotCommandHelpers.send(source, "§a[Bot - Hunt] تم إنشاء منطقة الصيد §e" + name + "§a بنجاح في §b" + mgr.getCurrentDimensionDisplayName() + "§a!");
        return 1;
    }

    private static int executeEditZoneHelp(CommandContext<FabricClientCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "zone").toLowerCase(Locale.ROOT);
        HuntZone zone = getScopedConfig().getZone(name);
        if (zone == null) {
            BotCommandHelpers.send(ctx.getSource(), "§c[Bot - Hunt] لا توجد منطقة بهذا الاسم: §e" + name);
            return 0;
        }
        BotCommandHelpers.send(ctx.getSource(), "§6=== [تعديل منطقة الصيد: §e" + name + "§6] ===");
        BotCommandHelpers.send(ctx.getSource(), "§7الحالة: " + (zone.isEnabled() ? "§a[مفعل]" : "§c[غير مفعل]")
            + " §7| الصناديق: §e" + BotCommandHelpers.formatCompactChests(zone.getLootChests()));
        BotCommandHelpers.send(ctx.getSource(), "§7/bot hunt area edit " + name + " <name|bounds|chest [open|here|add|list|remove|clear]|enable|disable>");
        return 1;
    }

    private static int executeEditZoneName(CommandContext<FabricClientCommandSource> ctx) {
        String oldName = StringArgumentType.getString(ctx, "zone").toLowerCase(Locale.ROOT);
        String newName = StringArgumentType.getString(ctx, "new_name").toLowerCase(Locale.ROOT);
        if (!getScopedConfig().renameZone(oldName, newName)) {
            BotCommandHelpers.send(ctx.getSource(), "§c[Bot - Hunt] تعذر تغيير اسم المنطقة.");
            return 0;
        }
        BotCommandHelpers.send(ctx.getSource(), "§a[Bot - Hunt] تم تغيير اسم المنطقة إلى §e" + newName + "§a.");
        return 1;
    }

    private static int executeEditZoneBounds(CommandContext<FabricClientCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "zone").toLowerCase(Locale.ROOT);
        HuntConfigManager mgr = getScopedConfig();
        if (mgr.getPos1() == null || mgr.getPos2() == null) {
            BotCommandHelpers.send(ctx.getSource(), "§c[Bot - Hunt] حدد pos1 و pos2 أولاً.");
            return 0;
        }
        if (!mgr.updateZoneBounds(name, mgr.getPos1(), mgr.getPos2())) {
            BotCommandHelpers.send(ctx.getSource(), "§c[Bot - Hunt] تعذر تحديث حدود المنطقة: §e" + name);
            return 0;
        }
        BotCommandHelpers.send(ctx.getSource(), "§a[Bot - Hunt] تم تحديث حدود منطقة §e" + name + "§a.");
        return 1;
    }

    private static int executeSetSingleZoneEnabled(CommandContext<FabricClientCommandSource> ctx, boolean enabled, String argKey) {
        String name = StringArgumentType.getString(ctx, argKey).toLowerCase(Locale.ROOT);
        if (!getScopedConfig().setZoneEnabled(name, enabled)) {
            BotCommandHelpers.send(ctx.getSource(), "§c[Bot - Hunt] لا توجد منطقة بهذا الاسم: §e" + name);
            return 0;
        }
        BotCommandHelpers.send(ctx.getSource(), "§a[Bot - Hunt] تم ضبط منطقة §e" + name + "§a إلى " + (enabled ? "§a[مفعل]" : "§c[غير مفعل]") + "§a.");
        return 1;
    }

    private static int executeSetZoneEnabled(CommandContext<FabricClientCommandSource> ctx, boolean enabled) {
        String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
        HuntConfigManager mgr = getScopedConfig();
        if (name.equals("all") || name.equals("الكل")) {
            mgr.setAllZonesEnabled(enabled);
            BotCommandHelpers.send(ctx.getSource(), enabled ? "§a[Bot - Hunt] تم تفعيل جميع مناطق الصيد." : "§c[Bot - Hunt] تم تعطيل جميع مناطق الصيد.");
            return 1;
        }
        return executeSetSingleZoneEnabled(ctx, enabled, "name");
    }

    private static int executeSelectZone(CommandContext<FabricClientCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
        HuntConfigManager mgr = getScopedConfig();
        if (name.equals("all") || name.equals("الكل")) {
            mgr.setAllZonesEnabled(true);
            BotCommandHelpers.send(ctx.getSource(), "§a[Bot - Hunt] تم تفعيل جميع مناطق الصيد.");
            return 1;
        }
        if (name.equals("none") || name.equals("off") || name.equals("لا_أحد") || name.equals("لاأحد")) {
            mgr.setAllZonesEnabled(false);
            BotCommandHelpers.send(ctx.getSource(), "§c[Bot - Hunt] تم تعطيل جميع مناطق الصيد.");
            return 1;
        }
        if (mgr.getZone(name) == null) {
            BotCommandHelpers.send(ctx.getSource(), "§c[Bot - Hunt] لا توجد منطقة بهذا الاسم: §e" + name);
            return 0;
        }
        mgr.selectOnlyZone(name);
        BotCommandHelpers.send(ctx.getSource(), "§a[Bot - Hunt] تم تفعيل منطقة §e" + name + "§a فقط.");
        return 1;
    }

    private static int executeRemoveZone(CommandContext<FabricClientCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
        if (!getScopedConfig().removeZone(name)) {
            BotCommandHelpers.send(ctx.getSource(), "§c[Bot - Hunt] المنطقة غير موجودة: §e" + name);
            return 0;
        }
        BotCommandHelpers.send(ctx.getSource(), "§a[Bot - Hunt] تم حذف منطقة الصيد §e" + name + "§a.");
        return 1;
    }

    private static int executeListZones(CommandContext<FabricClientCommandSource> ctx) {
        HuntConfigManager mgr = getScopedConfig();
        Collection<HuntZone> zones = mgr.getAllZones();
        if (zones.isEmpty()) {
            BotCommandHelpers.send(ctx.getSource(), "§e[Bot - Hunt] لا توجد مناطق صيد مسجلة في §b" + mgr.getCurrentDimensionDisplayName() + "§e.");
            return 1;
        }
        BotCommandHelpers.send(ctx.getSource(), "§6=== [مناطق الصيد - " + mgr.getCurrentDimensionDisplayName() + "] ===");
        for (HuntZone z : zones) {
            String badge = z.isEnabled() ? "§a[مفعل]" : "§c[غير مفعل]";
            String chestInfo = !z.getLootChests().isEmpty()
                ? (" §6[صناديق: " + BotCommandHelpers.formatCompactChests(z.getLootChests()) + "]")
                : "";
            BotCommandHelpers.send(ctx.getSource(), badge + " §b" + z.getName() + chestInfo);
        }
        return 1;
    }
}
