package com.helpermod.bot.command;

import com.helpermod.bot.FightBotController;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.HoeItem;

public final class DefendCommand {

    private DefendCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal("defend")
            .executes(DefendCommand::executeFightStatus)
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("cautious")
                .executes(ctx -> executeSetDefenseMode(FightBotController.DefenseMode.CAUTIOUS)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("combat")
                .executes(ctx -> executeSetDefenseMode(FightBotController.DefenseMode.COMBAT)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on")
                .executes(ctx -> executeSetDefenseMode(FightBotController.DefenseMode.CAUTIOUS)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off")
                .executes(ctx -> executeSetDefenseMode(FightBotController.DefenseMode.OFF)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status")
                .executes(DefendCommand::executeFightStatus));
    }

    private static int executeSetDefenseMode(FightBotController.DefenseMode mode) {
        FightBotController.getInstance().setDefenseMode(Minecraft.getInstance(), mode);
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
            hasHoeInOffhand = client.player.getOffhandItem().getItem() instanceof HoeItem;
        }

        BotCommandHelpers.send(source, "§6=== [حالة وضع الدفاع - HelperMod] ===");
        String modeDesc;
        if (mode == FightBotController.DefenseMode.CAUTIOUS) {
            modeDesc = "§6نمط الحذر (مراقبة وصد بالدرع وتراجع عند الخطر)";
        } else if (mode == FightBotController.DefenseMode.COMBAT) {
            modeDesc = "§4نمط القتال (حماية وهجوم وقتل الوحوش والهروب من Creeper)";
        } else {
            modeDesc = "§cمعطل";
        }
        BotCommandHelpers.send(source, "§7النمط الحالي للدفاع: " + modeDesc);
        BotCommandHelpers.send(source, "§7الفأس الزراعي في اليد اليسرى: " + (hasHoeInOffhand ? "§aموجود ومجهز للزراعة" : "§eغير مجهز"));
        BotCommandHelpers.send(source, "§7الدرع المخصص للدفاع: " + (hasShield ? "§aمتوفر في الحقيبة/اليد وجاهز للتبديل الفوري عند الخطر" : "§cغير متوفر (يرجى وضع درع في الحقيبة للتبديل الفوري)"));
        BotCommandHelpers.send(source, "§eالأنماط المتاحة:");
        BotCommandHelpers.send(source, "  §a/bot defend cautious §7- نمط الحذر (مراقبة وصد وتراجع فقط)");
        BotCommandHelpers.send(source, "  §c/bot defend combat   §7- نمط القتال (هجوم وقتل الوحوش والهروب من Creeper)");
        BotCommandHelpers.send(source, "  §7/bot defend off      §7- إيقاف الدفاع بالكامل");
        return 1;
    }
}
