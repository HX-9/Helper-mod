package com.helpermod.bot.command;

import com.helpermod.bot.BotFollowController;
import com.helpermod.bot.BotGlobalManager;
import com.helpermod.bot.FarmBotController;
import com.helpermod.bot.FightBotController;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;

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
                    .executes(ctx -> executeSetBotMode(true)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off")
                    .executes(ctx -> executeSetBotMode(false)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status")
                    .executes(BotCommand::executeBotModeStatus)))
            // /bot stop
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stop")
                .executes(BotCommand::executeStopAll))
            .then(FarmCommand.build())
            .then(DefendCommand.build())
            .then(FriendCommand.build())
            .then(FollowCommand.build())
            .then(FoodCommand.build())
            .executes(context -> {
                sendHelp(context.getSource());
                return 1;
            });
    }

    private static int executeSetBotMode(boolean enabled) {
        BotGlobalManager.getInstance().setBotEnabled(Minecraft.getInstance(), enabled);
        return 1;
    }

    private static int executeBotModeStatus(CommandContext<FabricClientCommandSource> context) {
        boolean enabled = BotGlobalManager.getInstance().isBotEnabled();
        FightBotController.DefenseMode defMode = FightBotController.getInstance().getDefenseMode();
        boolean isFollowing = BotFollowController.getInstance().isFollowing();
        boolean isFarming = FarmBotController.getInstance().isFarming();

        FabricClientCommandSource source = context.getSource();
        BotCommandHelpers.send(source, "§6==== [حالة نظام البوت والمود] ====");
        BotCommandHelpers.send(source, "§7- الوضع العام (Bot Mode): " + (enabled ? "§aON (مفعّل)" : "§cOFF (معطل بالكامل في المطلق)"));
        BotCommandHelpers.send(source, "§7- وضع الدفاع (Defend): §e" + defMode.getDisplayName());
        BotCommandHelpers.send(source, "§7- وضع الاتباع (Follow): " + (isFollowing ? "§aمفعّل (يتبع: " + BotFollowController.getInstance().getTargetPlayerName() + ")" : "§7غير مفعّل"));
        BotCommandHelpers.send(source, "§7- وضع المزرعة (Farm): " + (isFarming ? "§aيعمل حالياً" : "§7متوقف"));
        BotCommandHelpers.send(source, "§eللتحكم العام: §f/bot mode on §7أو §f/bot mode off §7أو §f/bot stop");
        return 1;
    }

    private static int executeStopAll(CommandContext<FabricClientCommandSource> context) {
        BotGlobalManager.getInstance().stopAll(Minecraft.getInstance());
        BotCommandHelpers.send(context.getSource(),
            "§e[Bot] تم إيقاف جميع أنشطة البوت الجارية (الزراعة والاتباع والدفاع) وتحرير جميع مفاتيح التحكم فوراً!");
        return 1;
    }

    private static void sendHelp(FabricClientCommandSource source) {
        BotCommandHelpers.send(source, "§6=== [أوامر المساعد الآلي - HelperMod] ===");
        BotCommandHelpers.send(source, "§6/bot mode [on|off|status] §7- تشغيل أو إيقاف المود بالكامل في المطلق (Bot Mode)");
        BotCommandHelpers.send(source, "§6/bot stop §7- إيقاف الزراعة والاتباع والدفاع فوراً وتحرير الحركة");
        BotCommandHelpers.send(source, "§b/bot follow [<name>|stop|status] §7- اتباع لاعب بمسار آمن أو عرض حالة الاتباع");
        BotCommandHelpers.send(source, "§a/bot defend [cautious|combat|on|off|status] §7- أنماط الحماية والدفاع (حذر / قتال / إيقاف)");
        BotCommandHelpers.send(source, "§a/bot friend [add|remove|list] <name> §7- إدارة قائمة الأصدقاء المستثنين من الحذر والدفاع");
        BotCommandHelpers.send(source, "§d/bot food [add|remove|clear|list] <name> §7- إدارة قائمة الأطعمة المسموحة للبوت");
        BotCommandHelpers.send(source, "§e/bot farm [start|stop|status] §7- تشغيل أو إيقاف أو عرض حالة بوت الزراعة");
        BotCommandHelpers.send(source, "§e/bot farm sleep [on|off] §7- تفعيل/تعطيل النوم التلقائي بعد 20 ثانية من الليل");
        BotCommandHelpers.send(source, "§e/bot farm bed [set|clear] §7- تعيين أو إلغاء أو عرض سرير نقطة الإحياء");
        BotCommandHelpers.send(source, "§e/bot farm fertilizer [chest [set|clear]] §7- تعيين أو إلغاء أو عرض صندوق السماد (Bone Meal)");
        BotCommandHelpers.send(source, "§e/bot farm area [pos1|pos2|create|add|edit|remove|list|enable|disable|select] §7- إدارة المناطق الزراعية");
        BotCommandHelpers.send(source, "§e/bot farm area add <اسم> <x1> <y1> <z1> <x2> <y2> <z2> [محصول] §7- إضافة منطقة بالإحداثيات");
        BotCommandHelpers.send(source, "§e/bot farm area edit <اسم> <name|crop|chest [here|clear]|bounds|enable|disable> §7- تعديل المزرعة");
        BotCommandHelpers.send(source, "§e/bot farm area enable|disable <اسم|all> §7- تفعيل أو تعطيل مزارع محددة");
        BotCommandHelpers.send(source, "§e/bot farm area select [اسم|all|none] §7- تحديد مزرعة واحدة فقط أو الكل أو لا أحد");
    }
}
