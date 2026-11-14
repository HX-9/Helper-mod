package com.helpermod.bot.command;

import com.helpermod.bot.BotFollowController;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;

import java.util.List;

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
        return 1;
    }

    private static int executeFollowPrompt(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        List<String> onlineNames = BotCommandHelpers.getOnlineOtherPlayerNames(Minecraft.getInstance());

        if (onlineNames.isEmpty()) {
            BotCommandHelpers.send(source, "§e[Bot] لا يوجد لاعبون آخرون متصلون في السيرفر حالياً!");
            BotCommandHelpers.send(source, "§7للاتباع اكتب: §b/bot follow <اسم_اللاعب>");
        } else {
            BotCommandHelpers.send(source, "§6=== [قائمة اللاعبين المتاحين للاتباع] ===");
            BotCommandHelpers.send(source, "§a" + String.join("§7, §a", onlineNames));
            BotCommandHelpers.send(source, "§7اضغط Tab بعد الأمر لاختيار الاسم تلقائياً أو اكتب: §b/bot follow <name>");
            BotCommandHelpers.send(source, "§7للإيقاف اكتب: §c/bot follow stop");
        }
        return 1;
    }
}
