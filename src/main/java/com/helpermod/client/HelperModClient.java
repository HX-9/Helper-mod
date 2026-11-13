package com.helpermod.client;

import com.helpermod.HelperMod;
import com.helpermod.bot.FarmArea;
import com.helpermod.bot.FarmAreaManager;
import com.helpermod.bot.FarmBotController;
import com.helpermod.bot.command.BotCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

public class HelperModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // تحميل إعدادات المزارع والأصدقاء المحفوظة
        FarmAreaManager.getInstance().load();
        com.helpermod.bot.friend.BotFriendManager.getInstance().load();

        // تسجيل أوامر البوت في الكلاينت
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            BotCommand.register(dispatcher);
        });

        // تشغيل محرك البوت ونظام الدفاع القتالي ووضع الاتباع مع كل نبضة (Tick) من الكلاينت
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!com.helpermod.bot.BotGlobalManager.getInstance().isBotEnabled()) {
                return;
            }
            com.helpermod.bot.FightBotController.getInstance().onTick(client);
            FarmBotController.getInstance().onTick(client);
            com.helpermod.bot.BotFollowController.getInstance().onTick(client);
        });

        // رصد أول صندوق يفتحه اللاعب عند تفعيل وضع ربط الصندوق لمزرعة معينة أو لصندوق السماد
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide()) {
                BlockPos clickedPos = hitResult.getBlockPos();
                BlockState state = world.getBlockState(clickedPos);
                boolean isContainer = state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock
                    || state.getBlock() instanceof EnderChestBlock || state.getBlock() instanceof ShulkerBoxBlock;

                if (isContainer) {
                    FarmAreaManager manager = FarmAreaManager.getInstance();
                    if (manager.isAwaitingFertilizerChest()) {
                        FarmArea existingCropFarm = manager.findAreaUsingChest(clickedPos);
                        if (existingCropFarm != null) {
                            if (player != null) {
                                player.sendSystemMessage(Component.literal("§c[Bot] هذا الصندوق مخصص لمحاصيل مزرعة §e" + existingCropFarm.getName() + "§c. اختر صندوقاً آخر للسماد (Bone Meal)."));
                            }
                        } else {
                            manager.setFertilizerChestPos(clickedPos);
                            manager.setAwaitingFertilizerChest(false);
                            if (player != null) {
                                player.sendSystemMessage(Component.literal("§a[Bot] تم بنجاح ربط صندوق السماد (Bone Meal) عند: §e" + clickedPos.getX() + ", " + clickedPos.getY() + ", " + clickedPos.getZ() + "§a!"));
                            }
                        }
                    } else {
                        String awaitingFarm = manager.getAwaitingChestFarm();
                        if (awaitingFarm != null) {
                            FarmArea area = manager.getArea(awaitingFarm);
                            if (area != null) {
                                if (clickedPos.equals(manager.getFertilizerChestPos())) {
                                    if (player != null) {
                                        player.sendSystemMessage(Component.literal("§c[Bot] هذا الصندوق مخصص للسماد (Bone Meal). اختر صندوقاً آخر لمزرعة §e" + area.getName() + "§c."));
                                    }
                                } else {
                                    area.setChestPos(clickedPos);
                                    manager.save();
                                    manager.setAwaitingChestFarm(null);
                                    if (player != null) {
                                        player.sendSystemMessage(Component.literal("§a[Bot] تم بنجاح ربط الصندوق عند: §e" + clickedPos.getX() + ", " + clickedPos.getY() + ", " + clickedPos.getZ() + " §aبمزرعة §e" + area.getName() + "§a!"));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return InteractionResult.PASS;
        });

        HelperMod.LOGGER.info("HelperMod Farming Bot Client initialized successfully!");
    }
}
