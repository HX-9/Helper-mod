package com.helpermod.bot.command;

import com.helpermod.bot.food.BotFoodManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.Collection;

public final class FoodCommand {

    private FoodCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal("food")
            .executes(FoodCommand::executeFoodList)
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
                .executes(FoodCommand::executeFoodAddPrompt)
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("food_name", StringArgumentType.string())
                    .suggests(BotCommandHelpers.KNOWN_FOODS)
                    .executes(FoodCommand::executeFoodAdd)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
                .executes(FoodCommand::executeFoodClear))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
                .executes(FoodCommand::executeFoodList)
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("food_name", StringArgumentType.string())
                    .suggests(BotCommandHelpers.ALLOWED_FOODS)
                    .executes(FoodCommand::executeFoodRemove)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
                .executes(FoodCommand::executeFoodList));
    }

    private static int executeFoodAddPrompt(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BotCommandHelpers.send(source, "§e[Bot - Food] الصيغة: §b/bot food add <اسم_الطعام>");
        BotCommandHelpers.send(source, "§7اضغط §eTab §7لاختيار من الأطعمة المعروفة مثل: §ecooked_beef, bread, golden_carrot, cooked_porkchop, baked_potato");
        return 1;
    }

    private static int executeFoodAdd(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "food_name");
        BotFoodManager manager = BotFoodManager.getInstance();
        BotFoodManager.AddFoodResult result = manager.addFoodChecked(name);
        String display = manager.getDisplayName(name);

        switch (result) {
            case ADDED -> BotCommandHelpers.send(context.getSource(),
                "§a[Bot - Food] تم بنجاح إضافة طعام: §e" + display + " §aإلى قائمة الأطعمة المسموحة!");
            case ALREADY_EXISTS -> BotCommandHelpers.send(context.getSource(),
                "§e[Bot - Food] الطعام: §6" + display + " §eموجود بالفعل في قائمة الأطعمة المسموحة.");
            case INVALID -> {
                BotCommandHelpers.send(context.getSource(),
                    "§c[Bot - Food] العنصر: §e" + name + " §cليس طعاماً معروفاً أو صالحاً للأكل!");
                BotCommandHelpers.send(context.getSource(),
                    "§7استخدم §eTab §7للاختيار من الأطعمة المعروفة مثل: §ecooked_beef, bread, golden_carrot, cooked_porkchop");
            }
        }
        return 1;
    }

    private static int executeFoodClear(CommandContext<FabricClientCommandSource> context) {
        BotFoodManager.getInstance().clearFoods();
        BotCommandHelpers.send(context.getSource(),
            "§c[Bot - Food] تم تفريغ قائمة الأطعمة المسموحة بالكامل! لن يتناول البوت أي طعام حتى تضيف أطعمة مسموحة عبر: §e/bot food add <اسم>");
        return 1;
    }

    private static int executeFoodRemove(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "food_name");
        boolean removed = BotFoodManager.getInstance().removeFood(name);
        if (removed) {
            BotCommandHelpers.send(context.getSource(),
                "§e[Bot - Food] تم حذف: §6" + BotFoodManager.getInstance().getDisplayName(name) + " §eمن قائمة الأطعمة المسموحة.");
        } else {
            BotCommandHelpers.send(context.getSource(),
                "§c[Bot - Food] لم يتم العثور على: §e" + name + " §cفي قائمة الأطعمة المسموحة.");
        }
        return 1;
    }

    private static int executeFoodList(CommandContext<FabricClientCommandSource> context) {
        Collection<String> foods = BotFoodManager.getInstance().getAllowedFoods();
        if (foods.isEmpty()) {
            BotCommandHelpers.send(context.getSource(),
                "§c[Bot - Food] قائمة الأطعمة المسموحة فارغة حالياً! أضف أطعمة باستخدام: §e/bot food add <اسم_الطعام>");
            return 1;
        }

        BotCommandHelpers.send(context.getSource(),
            "§a=== قائمة الأطعمة المسموحة للبوت (" + foods.size() + ") ===");
        for (String food : foods) {
            String display = BotFoodManager.getInstance().getDisplayName(food);
            BotCommandHelpers.send(context.getSource(), " §7- §e" + display);
        }
        return 1;
    }
}
