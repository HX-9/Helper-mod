package com.helpermod.bot.command;

import com.helpermod.bot.BotFollowController;
import com.helpermod.bot.BotSettingsManager;
import com.helpermod.bot.combat.BotCombatManager;
import com.helpermod.bot.hunt.MobTypeRegistry;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Set;

public final class FollowCommand {

    private FollowCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal("follow")
            .executes(FollowCommand::executeFollowPrompt)
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stop")
                .executes(FollowCommand::executeFollowStop))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status")
                .executes(FollowCommand::executeFollowStatus))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("ban")
                .executes(FollowCommand::executeBanList)
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("mob", StringArgumentType.greedyString())
                        .suggests(BotCommandHelpers.MOB_TARGETS_WITH_PRESETS)
                        .executes(FollowCommand::executeBanAdd)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("mob", StringArgumentType.greedyString())
                        .suggests(BotCommandHelpers.FOLLOW_BANNED_MOBS)
                        .executes(FollowCommand::executeBanRemove)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
                    .executes(FollowCommand::executeBanList))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
                    .executes(FollowCommand::executeBanClear)))
            .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                .suggests(BotCommandHelpers.ONLINE_PLAYERS)
                .executes(FollowCommand::executeFollowStart));
    }

    private static int executeFollowStart(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name");
        BotFollowController.getInstance().startFollowing(Minecraft.getInstance(), name);
        return 1;
    }

    private static int executeFollowStop(CommandContext<FabricClientCommandSource> context) {
        BotFollowController.getInstance().stopFollowing(Minecraft.getInstance());
        return 1;
    }

    private static int executeFollowStatus(CommandContext<FabricClientCommandSource> context) {
        BotFollowController controller = BotFollowController.getInstance();
        if (controller.isFollowing()) {
            BotCommandHelpers.send(context.getSource(),
                "§a[Bot] وضع الاتباع نشط حالياً يتبع اللاعب: §e" + controller.getTargetPlayerName()
                    + "§a (خلفه في اليمين على بعد 3 بلوكات وبمسار آمن).");
        } else {
            BotCommandHelpers.send(context.getSource(),
                "§7[Bot] وضع الاتباع غير نشط حالياً. للبدء اكتب: §b/bot follow <name>");
        }
        Set<String> banned = BotSettingsManager.getInstance().getFollowBannedMobs();
        if (!banned.isEmpty()) {
            BotCommandHelpers.send(context.getSource(), "§7الموبات المحظور قتالها إلا بعد هجوم القائد (§e" + banned.size() + "§7): §b/bot follow ban list");
        }
        return 1;
    }

    private static int executeBanAdd(CommandContext<FabricClientCommandSource> context) {
        String raw = StringArgumentType.getString(context, "mob");
        List<String> resolved = MobTypeRegistry.expandPresetOrSingle(raw);
        if (resolved.isEmpty()) {
            BotCommandHelpers.send(context.getSource(), "§c[Bot - Follow] نوع الموب غير معروف: §e" + raw + "§c! استخدم Tab للاختيار (مثل: enderman, creeper, zombified_piglin, hostile).");
            return 0;
        }
        BotSettingsManager settings = BotSettingsManager.getInstance();
        int addedCount = 0;
        for (String id : resolved) {
            if (settings.addFollowBannedMob(id)) {
                addedCount++;
            }
        }
        if (addedCount > 0) {
            BotCombatManager.getInstance().purgeFollowBannedTargets();
        }
        if (resolved.size() == 1) {
            String display = MobTypeRegistry.getDisplayName(resolved.get(0));
            BotCommandHelpers.send(context.getSource(), addedCount > 0
                ? "§a[Bot - Follow] تم حظر قتال §e" + display + "§a تلقائياً في وضع الاتباع؛ لن يقاتله البوت إلا إذا هاجمتَ أنت عليه أولاً."
                : "§e[Bot - Follow] الموب §6" + display + "§e محظور بالفعل في وضع الاتباع.");
        } else {
            BotCommandHelpers.send(context.getSource(), "§a[Bot - Follow] تم حظر §e" + addedCount + " §aأنواع جديدة (من أصل " + resolved.size() + ") في وضع الاتباع؛ لن تُقاتَل إلا إذا هاجم القائد عليها.");
        }
        return 1;
    }

    private static int executeBanRemove(CommandContext<FabricClientCommandSource> context) {
        String raw = StringArgumentType.getString(context, "mob");
        List<String> resolved = MobTypeRegistry.expandPresetOrSingle(raw);
        if (resolved.isEmpty()) {
            BotCommandHelpers.send(context.getSource(), "§c[Bot - Follow] نوع الموب غير معروف: §e" + raw);
            return 0;
        }
        BotSettingsManager settings = BotSettingsManager.getInstance();
        int removedCount = 0;
        for (String id : resolved) {
            if (settings.removeFollowBannedMob(id)) {
                removedCount++;
            }
        }
        BotCommandHelpers.send(context.getSource(), removedCount > 0
            ? "§a[Bot - Follow] تم إزالة الحظر عن §e" + (resolved.size() == 1 ? MobTypeRegistry.getDisplayName(resolved.get(0)) : (removedCount + " أنواع")) + "§a."
            : "§e[Bot - Follow] لم يكن مسجلاً في قائمة الحظر.");
        return 1;
    }

    private static int executeBanList(CommandContext<FabricClientCommandSource> context) {
        Set<String> banned = BotSettingsManager.getInstance().getFollowBannedMobs();
        if (banned.isEmpty()) {
            BotCommandHelpers.send(context.getSource(), "§7[Bot - Follow] لا توجد موبات محظورة حالياً في وضع الاتباع.");
            BotCommandHelpers.send(context.getSource(), "§7لإضافة موب (لن يقاتله البوت إلا إذا هاجمتَ عليه): §b/bot follow ban add <mob>");
            return 1;
        }
        BotCommandHelpers.send(context.getSource(), "§6=== [قائمة حظر القتال في وضع الاتباع (" + banned.size() + ")] ===");
        BotCommandHelpers.send(context.getSource(), "§7(لا يقاتلها البوت تلقائياً، ويهجم عليها فقط إذا هاجم القائد عليها)");
        for (String id : banned) {
            BotCommandHelpers.send(context.getSource(), " §7- §e" + MobTypeRegistry.getDisplayName(id));
        }
        return 1;
    }

    private static int executeBanClear(CommandContext<FabricClientCommandSource> context) {
        BotSettingsManager.getInstance().clearFollowBannedMobs();
        BotCommandHelpers.send(context.getSource(), "§a[Bot - Follow] تم تفريغ قائمة الموبات المحظورة في وضع الاتباع.");
        return 1;
    }

    private static int executeFollowPrompt(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        List<String> onlineNames = BotCommandHelpers.getOnlineOtherPlayerNames(Minecraft.getInstance());

        if (onlineNames.isEmpty()) {
            BotCommandHelpers.send(source, "§e[Bot] لا يوجد لاعبون آخرون متصلون في السيرفر حالياً!");
            BotCommandHelpers.send(source, "§7للاتباع اكتب: §b/bot follow <اسم_اللاعب>");
            BotCommandHelpers.send(source, "§7لحظر موبات معينة إلا عند هجومك: §b/bot follow ban <add|remove|list|clear>");
        } else {
            BotCommandHelpers.send(source, "§6=== [قائمة اللاعبين المتاحين للاتباع] ===");
            BotCommandHelpers.send(source, "§a" + String.join("§7, §a", onlineNames));
            BotCommandHelpers.send(source, "§7اضغط Tab بعد الأمر لاختيار الاسم تلقائياً أو اكتب: §b/bot follow <name>");
            BotCommandHelpers.send(source, "§7حظر موبات من القتال التلقائي: §b/bot follow ban <add|remove|list|clear>");
            BotCommandHelpers.send(source, "§7للإيقاف اكتب: §c/bot follow stop");
        }
        return 1;
    }
}
