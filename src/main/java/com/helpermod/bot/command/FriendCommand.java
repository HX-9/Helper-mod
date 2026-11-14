package com.helpermod.bot.command;

import com.helpermod.bot.friend.BotFriendManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;

import java.util.Collection;
import java.util.List;

public final class FriendCommand {

    private FriendCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal("friend")
            .executes(FriendCommand::executeFriendList)
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
                .executes(FriendCommand::executeFriendAddListPrompt)
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .suggests(BotCommandHelpers.ONLINE_PLAYERS)
                    .executes(FriendCommand::executeFriendAdd)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
                .executes(FriendCommand::executeFriendList)
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.string())
                    .suggests(BotCommandHelpers.FRIENDS)
                    .executes(FriendCommand::executeFriendRemove)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
                .executes(FriendCommand::executeFriendList));
    }

    private static int executeFriendAddListPrompt(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        List<String> onlineNames = BotCommandHelpers.getOnlineOtherPlayerNames(Minecraft.getInstance());

        if (onlineNames.isEmpty()) {
            BotCommandHelpers.send(source, "§e[Bot] لا يوجد لاعبون آخرون متصلون في السيرفر حالياً!");
            BotCommandHelpers.send(source, "§7يمكنك كتابة الاسم يدوياً عبر: §b/bot friend add <name>");
        } else {
            BotCommandHelpers.send(source, "§6=== [قائمة اللاعبين المتصلين في السيرفر للاختيار] ===");
            BotCommandHelpers.send(source, "§a" + String.join("§7, §a", onlineNames));
            BotCommandHelpers.send(source, "§7اضغط Tab بعد الأمر لاختيار الاسم تلقائياً أو اكتب: §b/bot friend add <name>");
        }
        return 1;
    }

    private static int executeFriendAdd(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name");
        boolean added = BotFriendManager.getInstance().addFriend(name);
        if (added) {
            BotCommandHelpers.send(context.getSource(), "§a[Bot] تم إضافة §e" + name + "§a كصديق للبوت! لن يتم الحذر أو الدفاع ضده إطلاقاً.");
        } else {
            BotCommandHelpers.send(context.getSource(), "§e[Bot] اللاعب §f" + name + "§e مضاف بالفعل كصديق مسبقاً.");
        }
        return 1;
    }

    private static int executeFriendRemove(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name");
        boolean removed = BotFriendManager.getInstance().removeFriend(name);
        if (removed) {
            BotCommandHelpers.send(context.getSource(), "§c[Bot] تم إزالة §e" + name + "§c من قائمة الأصدقاء.");
        } else {
            BotCommandHelpers.send(context.getSource(), "§e[Bot] اللاعب §f" + name + "§e ليس موجوداً في قائمة الأصدقاء.");
        }
        return 1;
    }

    private static int executeFriendList(CommandContext<FabricClientCommandSource> context) {
        Collection<String> friends = BotFriendManager.getInstance().getFriends();
        if (friends.isEmpty()) {
            BotCommandHelpers.send(context.getSource(), "§e[Bot] قائمة الأصدقاء فارغة حالياً. أضف صديقاً عبر: §b/bot friend add <name>");
        } else {
            BotCommandHelpers.send(context.getSource(), "§a[Bot] قائمة الأصدقاء الحالية (" + friends.size() + "): §e" + String.join("§7, §e", friends));
        }
        return 1;
    }
}
