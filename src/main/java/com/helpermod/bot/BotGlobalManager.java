package com.helpermod.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * مدير البوت العام والمفتاح الرئيسي للمود (Master Bot Manager):
 * يتيح تفعيل وإلغاء عمل المود في المطلق (Bot mode on / off)،
 * وإلغاء كافة الأنشطة التلقائية وتحرير أزرار التحكم تماماً.
 */
public class BotGlobalManager {

    private static final BotGlobalManager INSTANCE = new BotGlobalManager();

    public static BotGlobalManager getInstance() {
        return INSTANCE;
    }

    private boolean botEnabled = true;

    private BotGlobalManager() {}

    public boolean isBotEnabled() {
        return botEnabled;
    }

    public void setBotEnabled(Minecraft client, boolean enabled) {
        this.botEnabled = enabled;
        if (!enabled) {
            stopAll(client);
            sendMessage(client, "§c[Bot] تم إيقاف عمل البوت والمود بالكامل (Bot Mode: OFF). تم إيقاف جميع الأنشطة التلقائية وتحرير التحكم بالكامل.");
        } else {
            sendMessage(client, "§a[Bot] تم تفعيل وضع البوت (Bot Mode: ON). المود الآن في وضع الجاهزية لاستقبال المهام والأوامر.");
        }
    }

    public void toggleBotMode(Minecraft client) {
        setBotEnabled(client, !botEnabled);
    }

    /**
     * إيقاف كل ما يرتبط بالمود في المطلق:
     * - إيقاف الزراعة التلقائية
     * - إيقاف وضع الاتباع التكتيكي
     * - إيقاف وإلغاء وضع الدفاع في المطلق
     * - تحرير كافة مفاتيح التحكم والأزرار والركض
     */
    public void stopAll(Minecraft client) {
        // 1. إيقاف وضع المزرعة
        if (FarmBotController.getInstance().isFarming()) {
            FarmBotController.getInstance().stop(client);
        }

        // 2. إيقاف وضع الاتباع
        if (BotFollowController.getInstance().isFollowing()) {
            BotFollowController.getInstance().stopFollowing(client);
        }

        // 3. إلغاء وضع الدفاع في المطلق
        FightBotController.getInstance().disableDefenseCompletely(client);

        // 4. تحرير كافة مفاتيح الإدخال والتحكم
        releaseAllControls(client);
    }

    /**
     * تحرير كافة أزرار الحركة والمفاتيح لضمان عدم بقاء أي مفتاح مضغوطاً
     */
    public void releaseAllControls(Minecraft client) {
        if (client == null) return;
        Options opt = client.options;
        if (opt != null) {
            opt.keyUp.setDown(false);
            opt.keyDown.setDown(false);
            opt.keyLeft.setDown(false);
            opt.keyRight.setDown(false);
            opt.keyJump.setDown(false);
            opt.keySprint.setDown(false);
            opt.keyShift.setDown(false);
            opt.keyUse.setDown(false);
            opt.keyAttack.setDown(false);
        }
        LocalPlayer player = client.player;
        if (player != null) {
            player.setSprinting(false);
            if (client.gameMode != null && player.isUsingItem()) {
                client.gameMode.releaseUsingItem(player);
                player.stopUsingItem();
            }
        }
    }

    private void sendMessage(Minecraft client, String text) {
        if (client != null && client.player != null) {
            client.player.sendSystemMessage(Component.literal(text));
        }
    }
}
