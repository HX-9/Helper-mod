package com.helpermod.bot;

import com.helpermod.bot.hunt.MobHuntController;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * مدير البوت العام والمفتاح الرئيسي للمود (Master Bot Manager):
 * - يتيح تفعيل وإلغاء عمل المود في المطلق (Bot mode on / off).
 * - يطبّق قاعدة تعليق نشاط البوت مؤقتاً عند تدخل المستخدم (حركة/نقر/كاميرا/فتح واجهة أو شات/إصدار أمر)
 *   حتى ينتهي تحكم المستخدم بمقدار 10 ثوانٍ، ثم يستأنف البوت نشاطه تلقائياً.
 */
public class BotGlobalManager {

    private static final BotGlobalManager INSTANCE = new BotGlobalManager();
    public static final long USER_CONTROL_COOLDOWN_MS = 10_000L;

    public static BotGlobalManager getInstance() {
        return INSTANCE;
    }

    private boolean botEnabled = true;

    private boolean userControlPaused = false;
    private long userControlPauseUntilMs = 0L;
    private long ignoreEnterUntilMs = 0L;
    private long lastPauseNoticeMs = 0L;

    private boolean wasWindowActive = false;
    private boolean wasMouseGrabbed = false;
    private boolean hasMouseBaseline = false;
    private double lastMouseX = 0.0;
    private double lastMouseY = 0.0;

    private BotGlobalManager() {}

    public boolean isBotEnabled() {
        return botEnabled;
    }

    public boolean isUserControlPaused() {
        return userControlPaused && System.currentTimeMillis() < userControlPauseUntilMs;
    }

    public long getUserControlPauseRemainingSeconds() {
        if (!userControlPaused) return 0L;
        long remMs = userControlPauseUntilMs - System.currentTimeMillis();
        if (remMs <= 0L) return 0L;
        return (remMs + 999L) / 1000L;
    }

    /**
     * تطبيق حالة التفعيل المحفوظة عند تشغيل العميل دون إعادة كتابة ملف الإعدادات.
     */
    public void applyPersistedEnabled(boolean enabled) {
        this.botEnabled = enabled;
    }

    public void setBotEnabled(Minecraft client, boolean enabled) {
        this.botEnabled = enabled;
        BotSettingsManager.getInstance().setBotEnabled(enabled);
        if (!enabled) {
            stopAll(client);
            sendMessage(client, "§c[Bot] تم إيقاف عمل البوت والمود بالكامل (Bot Mode: OFF). تم إيقاف جميع الأنشطة التلقائية وتحرير التحكم بالكامل.");
        } else {
            clearUserControlPause();
            sendMessage(client, "§a[Bot] تم تفعيل وضع البوت (Bot Mode: ON). المود الآن في وضع الجاهزية لاستقبال المهام والأوامر.");
        }
    }

    public void toggleBotMode(Minecraft client) {
        setBotEnabled(client, !botEnabled);
    }

    public boolean hasAnyActiveBotActivity() {
        return FarmBotController.getInstance().isFarming()
            || MobHuntController.getInstance().isHunting()
            || BotFollowController.getInstance().isFollowing()
            || FightBotController.getInstance().isDefenseMode();
    }

    /**
     * إلغاء التعليق اليدوي عند تشغيل أمر بدء صريح (/bot farm start أو /bot hunt start أو /bot follow).
     */
    public void clearUserControlPause() {
        this.userControlPaused = false;
        this.userControlPauseUntilMs = 0L;
        this.ignoreEnterUntilMs = System.currentTimeMillis() + 600L;
    }

    /**
     * استدعاء عند تنفيذ المستخدم لأوامر أو تعديلات يدوية على البوت أثناء عمله لتأمين 10 ثوانٍ بعد انتهاء التحكم.
     */
    public void recordUserCommandInput(Minecraft client) {
        if (!botEnabled || !hasAnyActiveBotActivity()) {
            return;
        }
        long now = System.currentTimeMillis();
        this.userControlPauseUntilMs = now + USER_CONTROL_COOLDOWN_MS;
        if (!this.userControlPaused) {
            this.userControlPaused = true;
            pauseActiveControllersForUserControl(client);
            notifyPauseStarted(client, now);
        }
    }

    /**
     * التحقق الدوري في كل Tick من وجود تحكم يدوي أو شاشة أوامر مفتوحة، وإيقاف نشاط البوت مؤقتاً لمدة 10 ثوانٍ بعد انتهائه.
     *
     * @return true إذا كان البوت متوقفاً مؤقتاً حالياً لإعطاء التحكم الكامل للمستخدم
     */
    public boolean handleUserControlPauseTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            resetUserControlTracking();
            return false;
        }

        if (!botEnabled || !hasAnyActiveBotActivity()) {
            syncInputBaseline(client);
            if (userControlPaused) {
                userControlPaused = false;
                userControlPauseUntilMs = 0L;
            }
            return false;
        }

        long now = System.currentTimeMillis();
        boolean userActingNow = detectUserControlOrCommandInput(client, now);

        if (userActingNow) {
            userControlPauseUntilMs = now + USER_CONTROL_COOLDOWN_MS;
            if (!userControlPaused) {
                userControlPaused = true;
                pauseActiveControllersForUserControl(client);
                notifyPauseStarted(client, now);
            }
            return true;
        }

        if (userControlPaused) {
            if (now < userControlPauseUntilMs) {
                return true;
            }
            userControlPaused = false;
            userControlPauseUntilMs = 0L;
            syncInputBaseline(client);
            sendMessage(client, "§a[Bot] انتهى تحكمك (مضت 10 ثوانٍ دون أوامر)؛ استئناف نشاط البوت تلقائياً.");
            return false;
        }

        return false;
    }

    private boolean detectUserControlOrCommandInput(Minecraft client, long now) {
        if (!client.isWindowActive()) {
            wasWindowActive = false;
            hasMouseBaseline = false;
            return false;
        }

        boolean grabbed = client.mouseHandler.isMouseGrabbed();
        double mx = client.mouseHandler.xpos();
        double my = client.mouseHandler.ypos();

        boolean firstActiveTick = !wasWindowActive;
        boolean grabChanged = grabbed != wasMouseGrabbed;
        wasWindowActive = true;
        wasMouseGrabbed = grabbed;

        boolean mouseMoved = false;
        if (!firstActiveTick && !grabChanged && hasMouseBaseline) {
            double dx = mx - lastMouseX;
            double dy = my - lastMouseY;
            mouseMoved = (dx * dx + dy * dy) >= 9.0;
        }
        lastMouseX = mx;
        lastMouseY = my;
        hasMouseBaseline = true;

        if (client.mouseHandler.isLeftPressed()
                || client.mouseHandler.isRightPressed()
                || client.mouseHandler.isMiddlePressed()
                || mouseMoved) {
            return true;
        }

        if (!grabbed && !isBotManagedUiOrSleep(client)) {
            return true;
        }

        if (client.isPaused()) {
            return true;
        }

        return isAnyPhysicalUserKeyPressed(client.getWindow(), now);
    }

    private boolean isBotManagedUiOrSleep(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return false;

        if (player.isSleeping()) {
            if (BotFollowController.getInstance().isFollowing() && BotFollowController.getInstance().isSleepingWithCompanion()) {
                return true;
            }
            if (FarmBotController.getInstance().isFarming()
                    && FarmBotController.getInstance().getCurrentStage() == FarmBotController.FarmStage.SLEEP) {
                return true;
            }
            if (MobHuntController.getInstance().isHunting()
                    && MobHuntController.getInstance().getCurrentStage() == MobHuntController.HuntStage.SLEEP) {
                return true;
            }
        }

        if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            if (FarmBotController.getInstance().isFarming()) {
                FarmBotController.FarmStage stage = FarmBotController.getInstance().getCurrentStage();
                if (stage == FarmBotController.FarmStage.WITHDRAW
                        || stage == FarmBotController.FarmStage.DEPOSIT
                        || stage == FarmBotController.FarmStage.DEPOSIT_FERTILIZER) {
                    return true;
                }
            }
            if (MobHuntController.getInstance().isHunting()
                    && MobHuntController.getInstance().getCurrentStage() == MobHuntController.HuntStage.DEPOSIT_LOOT) {
                return true;
            }
        }

        return false;
    }

    private boolean isAnyPhysicalUserKeyPressed(Window window, long now) {
        if (window == null) return false;

        // فحص جميع مفاتيح الأحرف (WASD، E، Q، F، T، إلخ) والأرقام (1-9 اختيار الخانات)
        for (int key = 65; key <= 90; key++) {
            if (InputConstants.isKeyDown(window, key)) {
                return true;
            }
        }
        for (int key = 48; key <= 57; key++) {
            if (InputConstants.isKeyDown(window, key)) {
                return true;
            }
        }

        if (InputConstants.isKeyDown(window, InputConstants.KEY_SPACE)
                || InputConstants.isKeyDown(window, InputConstants.KEY_SLASH)
                || InputConstants.isKeyDown(window, InputConstants.KEY_ESCAPE)
                || InputConstants.isKeyDown(window, InputConstants.KEY_BACKSPACE)
                || InputConstants.isKeyDown(window, InputConstants.KEY_TAB)
                || InputConstants.isKeyDown(window, InputConstants.KEY_UP)
                || InputConstants.isKeyDown(window, InputConstants.KEY_DOWN)
                || InputConstants.isKeyDown(window, InputConstants.KEY_LEFT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RIGHT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_LCONTROL)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RCONTROL)
                || InputConstants.isKeyDown(window, InputConstants.KEY_LALT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RALT)) {
            return true;
        }

        return now >= ignoreEnterUntilMs && InputConstants.isKeyDown(window, InputConstants.KEY_RETURN);
    }

    private void syncInputBaseline(Minecraft client) {
        if (client == null || !client.isWindowActive()) {
            wasWindowActive = false;
            hasMouseBaseline = false;
            return;
        }
        wasWindowActive = true;
        wasMouseGrabbed = client.mouseHandler.isMouseGrabbed();
        lastMouseX = client.mouseHandler.xpos();
        lastMouseY = client.mouseHandler.ypos();
        hasMouseBaseline = true;
    }

    private void resetUserControlTracking() {
        userControlPaused = false;
        userControlPauseUntilMs = 0L;
        wasWindowActive = false;
        wasMouseGrabbed = false;
        hasMouseBaseline = false;
    }

    private void pauseActiveControllersForUserControl(Minecraft client) {
        FightBotController.getInstance().pauseForUserControl(client);
        FarmBotController.getInstance().pauseForUserControl(client);
        MobHuntController.getInstance().pauseForUserControl(client);
        BotFollowController.getInstance().pauseForUserControl(client);

        if (client == null) return;
        LocalPlayer player = client.player;
        if (player != null && !client.mouseHandler.isRightPressed() && player.isUsingItem()) {
            if (client.gameMode != null) {
                client.gameMode.releaseUsingItem(player);
            }
            player.stopUsingItem();
        }

        // مزامنة الأزرار مع حالة لوحة المفاتيح والفأرة الحقيقية حتى لا نلغي ضغطة المستخدم الحالية
        if (client.mouseHandler.isMouseGrabbed()) {
            KeyMapping.setAll();
        } else {
            KeyMapping.releaseAll();
        }

        if (player != null && client.options != null && !client.options.keySprint.isDown()) {
            player.setSprinting(false);
        }
    }

    private void notifyPauseStarted(Minecraft client, long now) {
        if (now - lastPauseNoticeMs >= 4_000L) {
            lastPauseNoticeMs = now;
            sendMessage(client, "§e[Bot] تم إيقاف نشاط البوت مؤقتاً استجابةً لتحكمك؛ سيُستأنف بعد انتهاء تحكمك بـ 10 ثوانٍ.");
        }
    }

    /**
     * إيقاف كل ما يرتبط بالمود في المطلق:
     * - إيقاف الزراعة التلقائية وصيد الموبات
     * - إيقاف وضع الاتباع التكتيكي
     * - إيقاف وإلغاء وضع الدفاع في المطلق
     * - تحرير كافة مفاتيح التحكم والأزرار والركض
     */
    public void stopAll(Minecraft client) {
        resetUserControlTracking();

        if (FarmBotController.getInstance().isFarming()) {
            FarmBotController.getInstance().stop(client);
        }
        if (MobHuntController.getInstance().isHunting()) {
            MobHuntController.getInstance().stop(client);
        }

        if (BotFollowController.getInstance().isFollowing()) {
            BotFollowController.getInstance().stopFollowing(client);
        }

        FightBotController.getInstance().disableDefenseCompletely(client);

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
