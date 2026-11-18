package com.helpermod.client;

import com.helpermod.HelperMod;
import com.helpermod.bot.FarmAreaManager;
import com.helpermod.bot.FarmBotController;
import com.helpermod.bot.command.BotCommand;
import com.helpermod.bot.hunt.HuntConfigManager;
import com.helpermod.bot.hunt.MobHuntController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;

public class HelperModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // تحميل إعدادات المزارع والصيد والأصدقاء والأطعمة والإعدادات العامة المحفوظة
        FarmAreaManager.getInstance().load();
        HuntConfigManager.getInstance().load();
        com.helpermod.bot.friend.BotFriendManager.getInstance().load();
        com.helpermod.bot.food.BotFoodManager.getInstance().load();
        com.helpermod.bot.BotSettingsManager settings = com.helpermod.bot.BotSettingsManager.getInstance();
        settings.load();
        com.helpermod.bot.BotGlobalManager.getInstance().applyPersistedEnabled(settings.isBotEnabled());
        com.helpermod.bot.FightBotController.getInstance().applyPersistedDefenseMode(settings.getDefenseMode());

        // تسجيل أوامر البوت في الكلاينت
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            BotCommand.register(dispatcher);
        });

        // تشغيل محركات البوت ونظام الدفاع ووضع الاتباع وصيد الموبات مع كل نبضة (Tick)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            FarmAreaManager.getInstance().updateContext(client);
            HuntConfigManager.getInstance().updateContext(client);
            if (!com.helpermod.bot.BotGlobalManager.getInstance().isBotEnabled()) {
                return;
            }
            if (com.helpermod.bot.BotGlobalManager.getInstance().handleUserControlPauseTick(client)) {
                return;
            }
            com.helpermod.bot.FightBotController.getInstance().onTick(client);
            FarmBotController.getInstance().onTick(client);
            MobHuntController.getInstance().onTick(client);
            com.helpermod.bot.BotFollowController.getInstance().onTick(client);
        });

        // رصد أول صندوق يفتحه اللاعب عند تفعيل وضع ربط الصندوق للزراعة أو السماد أو صيد الموبات
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world instanceof ClientLevel clientLevel && hand == InteractionHand.MAIN_HAND) {
                BlockPos clickedPos = hitResult.getBlockPos();
                BlockState state = clientLevel.getBlockState(clickedPos);
                if (FarmAreaManager.isStorageContainerBlock(state)) {
                    Minecraft mc = Minecraft.getInstance();
                    FarmAreaManager manager = FarmAreaManager.getInstance();
                    HuntConfigManager huntMgr = HuntConfigManager.getInstance();
                    manager.updateContext(mc);
                    huntMgr.updateContext(mc);

                    if (manager.isAwaitingFertilizerChest()) {
                        FarmAreaManager.ChestAssignResult result = manager.assignFertilizerChest(clientLevel, clickedPos);
                        if (player != null) {
                            player.sendSystemMessage(Component.literal(result.message()));
                        }
                    } else if (manager.getAwaitingChestFarm() != null) {
                        FarmAreaManager.ChestAssignResult result = manager.assignAreaChest(
                            clientLevel,
                            manager.getAwaitingChestFarm(),
                            manager.getAwaitingChestRole(),
                            clickedPos,
                            manager.isAwaitingChestAppend()
                        );
                        if (player != null) {
                            player.sendSystemMessage(Component.literal(result.message()));
                        }
                    } else if (huntMgr.isAwaitingGlobalLootChest()) {
                        FarmAreaManager.ChestAssignResult result = huntMgr.assignGlobalLootChest(
                            clientLevel,
                            clickedPos,
                            huntMgr.isAwaitingLootAppend()
                        );
                        if (player != null) {
                            player.sendSystemMessage(Component.literal(result.message()));
                        }
                    } else if (huntMgr.getAwaitingLootZoneName() != null) {
                        FarmAreaManager.ChestAssignResult result = huntMgr.assignZoneLootChest(
                            clientLevel,
                            huntMgr.getAwaitingLootZoneName(),
                            clickedPos,
                            huntMgr.isAwaitingLootAppend()
                        );
                        if (player != null) {
                            player.sendSystemMessage(Component.literal(result.message()));
                        }
                    }
                }
            }
            return InteractionResult.PASS;
        });

        HelperMod.LOGGER.info("HelperMod Farming Bot Client initialized successfully!");
    }
}
