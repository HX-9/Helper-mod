package com.helpermod.bot;

import com.helpermod.bot.food.BotFoodManager;
import com.helpermod.bot.hunt.MobHuntController;
import com.helpermod.bot.path.BotNavigator;
import com.helpermod.bot.path.FarmPathfinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FarmBotController {
    private static final FarmBotController INSTANCE = new FarmBotController();

    private boolean running = false;
    private boolean isDriving = false;
    private int cooldownTicks = 0;
    private int hoeAlertTicks = 0;
    private int sleepDoorCooldown = 0;

    // تتبع حالة المناورة والهروب التكتيكي بالدرع عند وجود تهديد
    private boolean isEvadingThreat = false;
    private int evadeTicks = 0;
    private int noThreatTicks = 0;
    private boolean isRetreatingInFarm = false;
    private boolean isEmergencyRunningFarm = false;
    private Vec3 emergencyRunStartPosFarm = null;
    private Vec3 emergencyRunDirFarm = null;
    private int emergencyRunTicksFarm = 0;

    // تتبع حالة التحقق المزدوج من اكتمال المزرعة قبل الانتقال للتالية
    private boolean doingFinalVerification = false;
    private int finalVerificationTicks = 0;
    private static final int FINAL_VERIFICATION_WAIT = 5; // انتظار 5 نبضات قبل التحقق النهائي

    // المزارع المعلقة مؤقتاً لامتلاء صندوقها وعدم توفر أداة سماد
    private final Set<String> fullChestPausedFarms = new HashSet<>();

    // تأخير إغلاق الحاويات لمنع desync في السيرفرات
    private int depositCloseDelay = 0;
    private int withdrawCloseDelay = 0;
    private int lastDepositedCount = 0;
    private int lastWithdrawnCount = 0;

    public boolean isEvadingThreat() {
        return isEvadingThreat;
    }

    // الملاحة الموحدة عبر BotNavigator وتتبّع سياق العالم والبُعد الحالي
    private final BotNavigator farmNavigator = new BotNavigator();
    private BlockPos trackedGoalPos = null;
    private String lastContextSignature = null;
    private boolean dimensionSleepNoticeSent = false;

    // سرعة دوران الكاميرا السلسة والمتجاوبة أثناء الزراعة (درجات في كل نبضة/tick)
    // أبطأ وأسلس من وضع الدفاع لتجنب الالتفاف المتشنج فوق التربة الزراعية
    private static final float ROTATION_SPEED = 18.0f;

    // الخانة المخصصة للبذور في شريط الأدوات: الزر رقم 7 في لوحة المفاتيح يقابل الفهرس 6
    private static final int SEED_HOTBAR_SLOT = 6;

    // الخانة المخصصة للطعام في شريط الأدوات: الزر رقم 9 في لوحة المفاتيح يقابل الفهرس 8
    private static final int FOOD_HOTBAR_SLOT = 8;

    // تتبع تناول الطعام المسموح عبر BotFoodManager
    private boolean isEating = false;
    private int eatingTicks = 0;
    private int lastFoodLevel = -1;
    private int hungryAlertCooldown = 0;

    // تسلسل مراحل العمل المنظمة لكل مزرعة
    public enum FarmStage {
        HARVEST("1. الحصاد"),
        COLLECT("2. تجميع المحصول"),
        TILL("3. إصلاح الأرض"),
        PLANT("4. زراعة الأرض"),
        WITHDRAW("5. جلب البذور من الصندوق"),
        SLEEP("6. النوم في السرير"),
        DEPOSIT("7. تفريغ المحصول في الصندوق"),
        COMPOST("8. تحويل الفائض لسماد"),
        DEPOSIT_FERTILIZER("9. تفريغ السماد في صندوق السماد");

        private final String arabicName;

        FarmStage(String arabicName) {
            this.arabicName = arabicName;
        }

        public String getArabicName() {
            return arabicName;
        }
    }

    private int currentAreaIndex = 0;
    private FarmStage currentStage = FarmStage.HARVEST;
    private int actionsInCurrentFarm = 0;
    private int consecutiveEmptyFarms = 0;
    private int waitingAlertTicks = 0;

    // تتبع وقت الليل والنوم التلقائي
    private int nightTicks = 0;
    private boolean bedAlertSent = false;

    // تتبع تفريغ المحاصيل والبذور الفائضة في الصناديق (يدعم تعدد الصناديق وفصل البذور عن المحصول)
    private FarmArea targetDepositArea = null;
    private BlockPos targetDepositChestPos = null;
    private FarmArea.ChestRole targetDepositRole = FarmArea.ChestRole.CROP;
    private int depositTimeoutTicks = 0;
    private final Set<BlockPos> visitedDepositChests = new HashSet<>();
    private DepositPlan activeDepositPlan = null;
    private final Set<FarmCrop> attemptedOutputChestsCrops = EnumSet.noneOf(FarmCrop.class);
    private final Set<FarmCrop> attemptedSeedChestsCrops = EnumSet.noneOf(FarmCrop.class);

    // تتبع تحويل الفائض لسماد في الكومبوستر عند امتلاء الصناديق المخصصة له
    private final Set<FarmCrop> fullOutputChestCrops = EnumSet.noneOf(FarmCrop.class);
    private final Set<FarmCrop> fullSeedChestCrops = EnumSet.noneOf(FarmCrop.class);
    private BlockPos targetComposterPos = null;
    private int compostTimeoutTicks = 0;
    private int compostActionCooldown = 0;
    private int collectBoneMealTicks = 0;

    // تتبع تفريغ ستاك السماد في صندوق السماد
    private int fertilizerTimeoutTicks = 0;
    private int fertilizerAlertCooldown = 0;

    // تتبع سحب البذور من الصندوق عند نفادها من الحقيبة
    private FarmArea targetWithdrawArea = null;
    private FarmCrop targetWithdrawCrop = null;
    private BlockPos targetWithdrawChestPos = null;
    private final Set<BlockPos> visitedWithdrawChests = new HashSet<>();
    private int withdrawTimeoutTicks = 0;
    private final Set<String> areasOutOfSeeds = new HashSet<>();

    // تتبع الحركة الفعلية للاعب لمنع التعليق
    private Vec3 lastPlayerPos = null;
    private int physicalStuckTicks = 0;

    // تتبع العناصر الملقاة لتفادي التعليق عليها إن كانت الحقيبة ممتلئة وتثبيت الهدف لمنع التشتت
    private ItemEntity lockedItem = null;
    private int itemStuckTicks = 0;
    private final Set<Integer> ignoredItemIds = new HashSet<>();

    // تتبع الأهداف غير القابلة للوصول (المحبوسة خلف سور أو لا يمكن بلوغها)
    private final Set<BlockPos> unreachablePositions = new HashSet<>();
    private BlockPos lastTargetPos = null;
    private int targetStuckTicks = 0;

    private FarmBotController() {}

    public static FarmBotController getInstance() {
        return INSTANCE;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isFarming() {
        return running;
    }

    public FarmStage getCurrentStage() {
        return currentStage;
    }

    public String getCurrentAreaName() {
        List<FarmArea> areas = FarmAreaManager.getInstance().getActiveAreas();
        if (areas.isEmpty() || currentAreaIndex >= areas.size()) {
            return null;
        }
        return areas.get(currentAreaIndex).getName();
    }

    public int getCurrentAreaIndex() {
        return currentAreaIndex;
    }

    public int getTotalActiveAreas() {
        return FarmAreaManager.getInstance().getActiveAreas().size();
    }

    public int getNightTicks() {
        return nightTicks;
    }

    public void start(Minecraft client) {
        if (running) {
            sendChatMessage(client, "§e[Bot] المساعد الآلي يعمل بالفعل!");
            return;
        }
        String contextSig = buildContextSignature(client);
        List<FarmArea> areas = FarmAreaManager.getInstance().getActiveAreas();
        if (areas.isEmpty()) {
            if (FarmAreaManager.getInstance().getAllAreas().isEmpty()) {
                sendChatMessage(client, "§c[Bot] لا توجد مناطق زراعية محددة في هذا العالم/البُعد! أضف منطقة أولاً باستخدام: /bot farm area pos1 ثم pos2 ثم create <اسم>");
            } else {
                sendChatMessage(client, "§c[Bot] جميع المزارع المسجلة في هذا البُعد غير مفعلة حالياً! فعّل مزرعة أو الكل عبر: §e/bot farm area enable <الاسم|all>");
            }
            return;
        }
        running = true;
        lastContextSignature = contextSig;
        // إيقاف وضع الاتباع وصيد الموبات تلقائياً لمنع أي تعارض حركي
        if (BotFollowController.getInstance().isFollowing()) {
            BotFollowController.getInstance().stopFollowing(client);
        }
        if (MobHuntController.getInstance().isHunting()) {
            MobHuntController.getInstance().stop(client);
        }
        resetChestWorkflowState(true);
        isDriving = false;
        cooldownTicks = 0;
        sleepDoorCooldown = 0;
        currentAreaIndex = 0;
        currentStage = FarmStage.HARVEST;
        actionsInCurrentFarm = 0;
        consecutiveEmptyFarms = 0;
        lockedItem = null;
        itemStuckTicks = 0;
        ignoredItemIds.clear();
        unreachablePositions.clear();
        lastTargetPos = null;
        targetStuckTicks = 0;
        isEating = false;
        eatingTicks = 0;
        lastFoodLevel = -1;
        hungryAlertCooldown = 0;
        nightTicks = 0;
        bedAlertSent = false;
        dimensionSleepNoticeSent = false;
        farmNavigator.reset(client);
        trackedGoalPos = null;
        waitingAlertTicks = 1100;
        lastPlayerPos = null;
        physicalStuckTicks = 0;
        doingFinalVerification = false;
        finalVerificationTicks = 0;
        FightBotController.getInstance().resetFarmCombat(client);
        // وضع الفأس الزراعي في اليد اليسرى فور تشغيل البوت (إلا إذا كان التوتم مطلوباً لحالة صحية حرجة)
        if (client.player != null && client.gameMode != null && !BotEquipmentHelper.isCriticalHealthForTotem(client.player)) {
            FightBotController.getInstance().ensureHoeInOffhand(client, client.player, client.gameMode);
        }
        BotGlobalManager.getInstance().clearUserControlPause();

        // فحص هل الوقت ليل عند بدء/العودة لوضع الزراعة للتفكير بالنوم فوراً (في الأبعاد التي تدعم الأسرة فقط)
        if (client.level != null && FarmAreaManager.isBedDimensionSupported(client.level)) {
            boolean isNightNow = client.level.isThundering() || (client.level.getSkyDarken() >= 4);
            if (isNightNow && FarmAreaManager.getInstance().isSleepAtNight()) {
                nightTicks = 400; // ضبط العداد على 20 ثانية للتفكير بالنوم والتوجه للسرير فوراً
                currentStage = FarmStage.SLEEP;
                sendChatMessage(client, "§e[Bot] تم تفعيل وضع الزراعة في وقت الليل! يتوجه البوت فوراً للسرير للنوم حتى تطلع الشمس...");
                return;
            }
        }

        sendChatMessage(client, "§a[Bot] تم تشغيل المساعد الآلي للزراعة! بدء دورة العمل المنظمة بالمزارع...");
        if (!BotGlobalManager.getInstance().isBotEnabled()) {
            sendChatMessage(client, "§e[Bot] تنبيه: الوضع العام للمود متوقف حالياً (/bot mode off). فعّله عبر: §b/bot mode on");
        }
    }

    /**
     * تعليق نشاط الزراعة مؤقتاً عند تدخل المستخدم مع الحفاظ على مرحلة المزرعة الحالية.
     */
    public void pauseForUserControl(Minecraft client) {
        if (!running) return;
        isDriving = false;
        cooldownTicks = 0;
        isEating = false;
        eatingTicks = 0;
        lastFoodLevel = -1;
        doingFinalVerification = false;
        finalVerificationTicks = 0;
        isEvadingThreat = false;
        isRetreatingInFarm = false;
        isEmergencyRunningFarm = false;
        emergencyRunStartPosFarm = null;
        emergencyRunDirFarm = null;
        emergencyRunTicksFarm = 0;
        evadeTicks = 0;
        noThreatTicks = 0;
        trackedGoalPos = null;
        farmNavigator.reset(client);
        FightBotController.getInstance().pauseForUserControl(client);
    }

    public void stop(Minecraft client) {
        if (!running) {
            sendChatMessage(client, "§e[Bot] المساعد الآلي متوقف بالفعل!");
            return;
        }
        running = false;
        lastContextSignature = null;
        trackedGoalPos = null;
        sleepDoorCooldown = 0;
        dimensionSleepNoticeSent = false;
        lockedItem = null;
        itemStuckTicks = 0;
        ignoredItemIds.clear();
        unreachablePositions.clear();
        lastTargetPos = null;
        targetStuckTicks = 0;
        isEating = false;
        eatingTicks = 0;
        lastFoodLevel = -1;
        hungryAlertCooldown = 0;
        nightTicks = 0;
        bedAlertSent = false;
        resetChestWorkflowState(true);
        isEvadingThreat = false;
        isRetreatingInFarm = false;
        isEmergencyRunningFarm = false;
        emergencyRunStartPosFarm = null;
        emergencyRunDirFarm = null;
        emergencyRunTicksFarm = 0;
        evadeTicks = 0;
        noThreatTicks = 0;
        doingFinalVerification = false;
        finalVerificationTicks = 0;
        waitingAlertTicks = 0;
        lastPlayerPos = null;
        physicalStuckTicks = 0;
        FightBotController.getInstance().resetFarmCombat(client);
        FightBotController.getInstance().stopDefending(client);
        FightBotController.getInstance().releaseEmergencyRun(client);
        farmNavigator.reset(client);
        if (isDriving) {
            releaseControls(client);
            isDriving = false;
        }
        sendChatMessage(client, "§c[Bot] تم إيقاف المساعد الآلي وإنهاء التحكم باللاعب بالكامل. (الحالة: متوقف)");
    }

    private void resetChestWorkflowState(boolean clearPausedFarms) {
        targetDepositArea = null;
        targetDepositChestPos = null;
        targetDepositRole = FarmArea.ChestRole.CROP;
        depositTimeoutTicks = 0;
        depositCloseDelay = 0;
        visitedDepositChests.clear();
        activeDepositPlan = null;
        attemptedOutputChestsCrops.clear();
        attemptedSeedChestsCrops.clear();
        fullOutputChestCrops.clear();
        fullSeedChestCrops.clear();
        targetWithdrawArea = null;
        targetWithdrawCrop = null;
        targetWithdrawChestPos = null;
        visitedWithdrawChests.clear();
        withdrawTimeoutTicks = 0;
        withdrawCloseDelay = 0;
        areasOutOfSeeds.clear();
        targetComposterPos = null;
        compostTimeoutTicks = 0;
        compostActionCooldown = 0;
        collectBoneMealTicks = 0;
        fertilizerTimeoutTicks = 0;
        if (clearPausedFarms) {
            fullChestPausedFarms.clear();
        }
    }

    private void resetNavPath() {
        farmNavigator.clearPathOnly();
        trackedGoalPos = null;
    }

    private String buildContextSignature(Minecraft client) {
        FarmAreaManager manager = FarmAreaManager.getInstance();
        manager.updateContext(client);
        return manager.getCurrentWorldKey() + "|" + manager.getCurrentDimensionId();
    }

    private boolean resetSessionProgressOnContextChange(Minecraft client) {
        LocalPlayer player = client != null ? client.player : null;
        if (player != null && player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        if (isEating && client != null && client.options != null) {
            client.options.keyUse.setDown(false);
        }
        isEating = false;
        eatingTicks = 0;
        lastFoodLevel = -1;

        farmNavigator.reset(client);
        trackedGoalPos = null;
        cooldownTicks = 0;
        sleepDoorCooldown = 0;
        lockedItem = null;
        itemStuckTicks = 0;
        ignoredItemIds.clear();
        unreachablePositions.clear();
        lastTargetPos = null;
        targetStuckTicks = 0;
        lastPlayerPos = null;
        physicalStuckTicks = 0;
        resetChestWorkflowState(true);
        currentAreaIndex = 0;
        currentStage = FarmStage.HARVEST;
        actionsInCurrentFarm = 0;
        consecutiveEmptyFarms = 0;
        doingFinalVerification = false;
        finalVerificationTicks = 0;
        nightTicks = 0;
        bedAlertSent = false;
        dimensionSleepNoticeSent = false;

        FightBotController fightCtrl = FightBotController.getInstance();
        fightCtrl.resetFarmCombat(client);
        fightCtrl.stopDefending(client);
        fightCtrl.releaseRetreatMovement(client);
        fightCtrl.releaseEmergencyRun(client);
        isEvadingThreat = false;
        isRetreatingInFarm = false;
        isEmergencyRunningFarm = false;
        emergencyRunStartPosFarm = null;
        emergencyRunDirFarm = null;
        emergencyRunTicksFarm = 0;
        evadeTicks = 0;
        noThreatTicks = 0;

        if (isDriving) {
            releaseControls(client);
            isDriving = false;
        }

        FarmAreaManager manager = FarmAreaManager.getInstance();
        List<FarmArea> newAreas = manager.getActiveAreas();
        if (newAreas.isEmpty()) {
            sendChatMessage(client, "§e[Bot] تم الانتقال إلى §b" + manager.getCurrentDimensionDisplayName() + "§e ولا توجد مزارع مفعلة في هذا البُعد؛ تم إيقاف وضع الزراعة.");
            stop(client);
            return false;
        }

        sendChatMessage(client, "§b[Bot] تم تبديل سياق الزراعة تلقائياً إلى: §e" + manager.getCurrentDimensionDisplayName());
        cooldownTicks = 10;
        return true;
    }

    public void onTick(Minecraft client) {
        if (!running) return;

        if (client.isPaused()) {
            if (isDriving) {
                releaseControls(client);
                isDriving = false;
            }
            return;
        }

        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        MultiPlayerGameMode gameMode = client.gameMode;

        if (player == null || level == null || gameMode == null || player.isDeadOrDying()) {
            farmNavigator.reset(client);
            trackedGoalPos = null;
            return;
        }

        // تحديث سياق العالم والبُعد ومعالجة الانتقال بين الأبعاد فورياً
        String contextSig = buildContextSignature(client);
        if (lastContextSignature == null) {
            lastContextSignature = contextSig;
        } else if (!lastContextSignature.equals(contextSig)) {
            lastContextSignature = contextSig;
            if (!resetSessionProgressOnContextChange(client)) {
                return;
            }
        }

        farmNavigator.tickCooldowns();
        if (sleepDoorCooldown > 0) {
            sleepDoorCooldown--;
        }

        // حراسة التنفس الطارئة في الماء (الطفو أو الهروب من السقف قبل الغرق مع السماح بالغوص الحر)
        if (!player.isSleeping() || BotNavigator.isEyesInWater(level, player)) {
            if (player.isSleeping() && BotNavigator.isEyesInWater(level, player) && BotNavigator.getAirRatio(player) <= 0.35) {
                player.stopSleepInBed(true, true);
            }
            if (!player.isSleeping() && farmNavigator.handleUrgentWaterBreathing(client, player, level, gameMode)) {
                isDriving = true;
                return;
            }
        }

        // أولاً وقبل أي مهمة أو كولداون أو عمل زراعي: معالجة الدفاع أو القتال الفعلي وفق النمط المختار
        FightBotController fightCtrl = FightBotController.getInstance();
        FightBotController.DefenseMode defenseMode = fightCtrl.getDefenseMode();
        if (defenseMode == FightBotController.DefenseMode.COMBAT) {
            if (isEvadingThreat) {
                isEvadingThreat = false;
                isRetreatingInFarm = false;
                isEmergencyRunningFarm = false;
                emergencyRunStartPosFarm = null;
                emergencyRunDirFarm = null;
                emergencyRunTicksFarm = 0;
                evadeTicks = 0;
                noThreatTicks = 0;
                fightCtrl.releaseRetreatMovement(client);
                fightCtrl.releaseEmergencyRun(client);
            }
            if (fightCtrl.tickFarmCombat(client, player, level, gameMode)) {
                if (isEating) {
                    client.options.keyUse.setDown(false);
                    isEating = false;
                    eatingTicks = 0;
                    lastFoodLevel = -1;
                }
                cooldownTicks = 0;
                resetNavPath();
                isDriving = false;
                return;
            }
        } else if (defenseMode == FightBotController.DefenseMode.CAUTIOUS) {
            if (fightCtrl.isInFarmCombat()) {
                fightCtrl.resetFarmCombat(client);
            }
            FightBotController.ThreatInfo threat = fightCtrl.detectThreat(player, level);
            if (threat != null || isEvadingThreat) {
                handleThreatEvadeAndEscape(client, player, level, gameMode, threat);
                return;
            }
        } else {
            if (fightCtrl.isInFarmCombat()) {
                fightCtrl.resetFarmCombat(client);
            }
        }

        // التأكد دائماً من وجود الفأس الزراعي في اليد اليسرى (Offhand) طالما لا يوجد خطر قتالي ولا أزمة توتم
        if (!fightCtrl.isDefending() && !fightCtrl.isInFarmCombat() && !isEvadingThreat
                && !BotEquipmentHelper.isCriticalHealthForTotem(player)) {
            fightCtrl.ensureHoeInOffhand(client, player, gameMode);
        }

        // إكمال دورة إغلاق الباب الجارية عبر الملاحة الموحدة قبل مواصلة الزراعة أو التفاعل
        boolean hasOpenContainer = player.containerMenu != null && player.containerMenu != player.inventoryMenu;
        if (!player.isSleeping() && !hasOpenContainer && farmNavigator.isBusyClosingDoor()) {
            BlockPos fallbackGoal = trackedGoalPos != null ? trackedGoalPos : player.blockPosition();
            farmNavigator.navigateTo(
                client,
                player,
                level,
                gameMode,
                Vec3.atBottomCenterOf(fallbackGoal),
                false,
                0.5,
                isEating,
                ROTATION_SPEED,
                true,
                4000,
                FarmPathfinder.PathOptions.FARM_DEFAULT,
                true,
                true,
                null
            );
            isDriving = true;
            return;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            if (!player.isSleeping()) {
                BotNavigator.maintainWaterBuoyancy(client, player, level, null);
            }
            return;
        }

        if (hoeAlertTicks > 0) {
            hoeAlertTicks--;
        }
        if (hungryAlertCooldown > 0) {
            hungryAlertCooldown--;
        }

        boolean canSleepInDimension = FarmAreaManager.isBedDimensionSupported(level);

        // مراقبة الليل وشروق الشمس بدقة (في الأبعاد التي تدعم الأسرة فقط)
        boolean isNightTime = canSleepInDimension && (level.isThundering() || (level.getSkyDarken() >= 4));
        if (isNightTime) {
            nightTicks++;
        } else {
            nightTicks = 0;
            bedAlertSent = false;
            // الاستيقاظ التلقائي فور شروق الشمس أو الخروج من بعد يدعم النوم
            if (currentStage == FarmStage.SLEEP) {
                if (player.isSleeping()) {
                    player.stopSleepInBed(true, true);
                }
                if (canSleepInDimension) {
                    sendChatMessage(client, "§a[Bot] صباح الخير! أشرقت الشمس واستيقظ البوت، يستأنف الآن دورة العمل في المزارع...");
                }
                currentStage = FarmStage.HARVEST;
                actionsInCurrentFarm = 0;
                consecutiveEmptyFarms = 0;
                unreachablePositions.clear();
                areasOutOfSeeds.clear();
                resetNavPath();
                lastTargetPos = null;
                targetStuckTicks = 0;
                physicalStuckTicks = 0;
                cooldownTicks = 20;
                return;
            }
        }

        // إذا كان الصباح قد حل ولا يزال اللاعب في السرير
        if (!isNightTime && player.isSleeping()) {
            player.stopSleepInBed(true, true);
        }

        // إذا كان اللاعب نائماً بالفعل في السرير
        if (player.isSleeping()) {
            if (isDriving) {
                releaseControls(client);
                isDriving = false;
            }
            if (canSleepInDimension) {
                player.getSleepingPos().ifPresent(pos -> {
                    if (FarmAreaManager.getInstance().getBedPos() == null) {
                        FarmAreaManager.getInstance().setBedPos(pos);
                    }
                });
            }
            // طالما أن الليل مستمر، ننتظر بهدوء حتى الصباح
            if (isNightTime) {
                return;
            }
        }

        // معالجة تناول الطعام المسموح إذا كان الجوع 14 أو أقل، أو الاستمرار في الأكل، أو العلاج بالتفاح الذهبي المسموح
        int currentFood = player.getFoodData().getFoodLevel();
        boolean shouldEatNow = currentFood <= 14 || isEating || (player.getHealth() <= 14.0f && BotFoodManager.getInstance().hasEdibleAllowedFood(player));
        if (shouldEatNow) {
            if (BotFoodManager.getInstance().hasEdibleAllowedFood(player)) {
                boolean eating = handleEating(client, player, gameMode);
                if (eating) {
                    return;
                }
            } else if (player.getFoodData().needsFood() && currentFood <= 14 && hungryAlertCooldown <= 0) {
                hungryAlertCooldown = 1200; // دقيقة واحدة
                sendChatMessage(client, "§c[Bot] أنا جائع ولا أملك أي طعام مسموح به في الحقيبة! أضف طعاماً عبر: §e/bot food add <name>");
            }
        }

        // معالجة مرحلة النوم
        if (currentStage == FarmStage.SLEEP) {
            handleSleepStage(client, player, level, gameMode);
            return;
        }

        // التفكير في النوم فوراً والعودة للسرير بعد مرور 20 ثانية على الليل (400 tick)
        if (canSleepInDimension && FarmAreaManager.getInstance().isSleepAtNight() && isNightTime && nightTicks >= 400) {
            sendChatMessage(client, "§e[Bot] مر على الليل 20 ثانية! يتوجه البوت فوراً للسرير للنوم حتى تطلع الشمس...");
            currentStage = FarmStage.SLEEP;
            resetNavPath();
            if (isDriving) {
                releaseControls(client);
                isDriving = false;
            }
            handleSleepStage(client, player, level, gameMode);
            return;
        }

        // معالجة مرحلة تفريغ المحصول في الصندوق
        if (currentStage == FarmStage.DEPOSIT) {
            handleDepositStage(client, player, level, gameMode);
            return;
        }

        // معالجة مرحلة جلب البذور من الصندوق عند نفادها
        if (currentStage == FarmStage.WITHDRAW) {
            handleWithdrawStage(client, player, level, gameMode);
            return;
        }

        // معالجة مرحلة تحويل الفائض إلى سماد في الكومبوستر
        if (currentStage == FarmStage.COMPOST) {
            handleCompostStage(client, player, level, gameMode);
            return;
        }

        // معالجة مرحلة تفريغ السماد في صندوق السماد المخصص
        if (currentStage == FarmStage.DEPOSIT_FERTILIZER) {
            handleDepositFertilizerStage(client, player, level, gameMode);
            return;
        }

        // التحقق من جمع ستاك كامل (64) من السماد للذهاب لتفريغه في صندوق السماد
        if (countItemInInventory(player, Items.BONE_MEAL) >= 64) {
            BlockPos fertChest = FarmAreaManager.getInstance().getFertilizerChestPos();
            if (fertChest != null) {
                sendChatMessage(client, "§e[Bot] تم جمع ستاك كامل (64) من السماد! يتوجه البوت الآن لتفريغه في صندوق السماد...");
                currentStage = FarmStage.DEPOSIT_FERTILIZER;
                fertilizerTimeoutTicks = 0;
                resetNavPath();
                if (isDriving) {
                    releaseControls(client);
                    isDriving = false;
                }
                return;
            } else {
                fertilizerAlertCooldown++;
                if (fertilizerAlertCooldown >= 1200) {
                    fertilizerAlertCooldown = 0;
                    sendChatMessage(client, "§e[Bot] حقيبتك تحتوي على ستاك سماد كامل (Bone Meal)، لكن لم تحدد صندوق السماد بعد! حدده عبر: §b/bot farm fertilizer chest set");
                }
            }
        }

        List<FarmArea> areas = FarmAreaManager.getInstance().getActiveAreas();
        if (areas.isEmpty()) {
            stop(client);
            return;
        }

        // فحص هل توقفت جميع المزارع بسبب امتلاء الصناديق وعدم توفر أداة سماد
        if (!fullChestPausedFarms.isEmpty() && fullChestPausedFarms.containsAll(areas.stream().map(FarmArea::getName).toList())) {
            sendChatMessage(client, "§c[Bot] جميع صناديق المزارع ممتلئة ولا توجد أداة سماد لتفريغ الفائض! إيقاف عمل البوت لحين إفراغ الصناديق.");
            stop(client);
            return;
        }

        if (currentAreaIndex >= areas.size()) {
            currentAreaIndex = 0;
            currentStage = FarmStage.HARVEST;
        }

        // تخطي أي مزرعة تم تعليقها مؤقتاً لامتلاء صندوقها
        if (fullChestPausedFarms.contains(areas.get(currentAreaIndex).getName())) {
            currentAreaIndex = (currentAreaIndex + 1) % areas.size();
            currentStage = FarmStage.HARVEST;
            return;
        }

        // تنفيذ خطة العمل المنظمة لكل مزرعة على حدة:
        // 1- الحصاد -> 2- تجميع المحصول -> 3- إصلاح الأرض -> 4- زراعة الأرض
        int stageChecks = 0;
        while (stageChecks < 4) {
            FarmArea currentArea = areas.get(currentAreaIndex);

            switch (currentStage) {
                case HARVEST: {
                    TargetAction harvestTarget = findClosestHarvestTargetInArea(player, level, currentArea);
                    if (harvestTarget != null) {
                        consecutiveEmptyFarms = 0;
                        executeAction(client, player, level, gameMode, harvestTarget);
                        return;
                    }
                    // انتهى الحصاد في هذه المزرعة، الانتقال للمرحلة 2: تجميع المحصول
                    currentStage = FarmStage.COLLECT;
                    stageChecks++;
                    break;
                }

                case COLLECT: {
                    // التحقق من صحة العنصر المستهدف حالياً
                    if (lockedItem != null) {
                        if (lockedItem.isRemoved() || !lockedItem.isAlive() || ignoredItemIds.contains(lockedItem.getId())) {
                            lockedItem = null;
                            itemStuckTicks = 0;
                        }
                    }

                    if (lockedItem == null) {
                        lockedItem = findClosestDroppedItemInArea(player, level, currentArea);
                        itemStuckTicks = 0;
                    }

                    if (lockedItem != null) {
                        consecutiveEmptyFarms = 0;

                        double dx = player.getX() - lockedItem.getX();
                        double dy = player.getY() - lockedItem.getY();
                        double dz = player.getZ() - lockedItem.getZ();
                        double hDistSq = dx * dx + dz * dz;
                        double distSq = hDistSq + dy * dy;

                        // فحص هل المحصول في بلوكة سياج/جدار أو ملاصق له مباشرة
                        BlockPos itemBlock = lockedItem.blockPosition();
                        boolean isItemAtFence = FarmPathfinder.isFenceOrWall(level.getBlockState(itemBlock))
                            || FarmPathfinder.isFenceOrWall(level.getBlockState(itemBlock.below()))
                            || isNearFence(level, itemBlock);

                        // إذا كان اللاعب قريباً بما يكفي لالتقاط المحصول وامتصاصه في الحقيبة تلقائياً:
                        // 1- أقل من 0.75 بلوكة في الحالات العادية.
                        // 2- أو إذا كان المحصول عند السور أو حدث اصطدام بالسياج وأصبح اللاعب في نطاق الالتقاط (أقل من 1.45 بلوكة أفقياً).
                        boolean closeEnoughToPickup = distSq < 0.75 * 0.75
                            || (isItemAtFence && hDistSq <= 1.45 * 1.45 && Math.abs(dy) <= 1.5)
                            || (player.horizontalCollision && hDistSq <= 1.5 * 1.5 && Math.abs(dy) <= 1.5);

                        if (closeEnoughToPickup) {
                            if (isDriving) {
                                releaseControls(client);
                                isDriving = false;
                            }
                            actionsInCurrentFarm++;
                            itemStuckTicks++;
                            if (itemStuckTicks > 40) { // انتظار لثانيتين لالتقاطه (أو تفريغه إن كانت الحقيبة ممتلئة تماماً)
                                if (isInventoryFull(player)) {
                                    List<FarmArea> activeAreas = FarmAreaManager.getInstance().getActiveAreas();
                                    if (tryStartDepositCycle(client, player, level, activeAreas, "المخزون ممتلئ أثناء تجميع المحاصيل")) {
                                        itemStuckTicks = 0;
                                        lockedItem = null;
                                        return;
                                    }
                                }
                                ignoredItemIds.add(lockedItem.getId());
                                lockedItem = null;
                                itemStuckTicks = 0;
                            }
                            return;
                        }

                        // فحص ما إذا كان هناك سياج أو جدار يعترض مسار اللاعب نحو المحصول
                        // (مع استثناء بلوكة المحصول نفسها إذا استقر عند حافة السور)
                        boolean blockedByFence = FarmPathfinder.isItemPathBlockedByFence(level, player.position(), lockedItem.position());
                        if (blockedByFence) {
                            itemStuckTicks++;
                            // إذا كان المحصول خلف سياج ولا يمكن الوصول إليه سريعاً، نتخطاه لحماية البوت من العلوق
                            if (itemStuckTicks > 40) {
                                ignoredItemIds.add(lockedItem.getId());
                                lockedItem = null;
                                itemStuckTicks = 0;
                                return;
                            }
                            // تحديد أقرب بلوكة مجاورة سالكة في المزرعة للتحرك نحوها عبر البوابة
                            navigateTowardsCollectedItem(client, player, level, lockedItem, isItemAtFence);
                            return;
                        }

                        // مراقبة التعليق العام إن كان العنصر غير قابل للوصول
                        itemStuckTicks++;
                        if (itemStuckTicks > 80) {
                            ignoredItemIds.add(lockedItem.getId());
                            lockedItem = null;
                            itemStuckTicks = 0;
                            return;
                        }

                        // التوجه المباشر الآمن إذا كان العنصر قريباً وفي نفس الارتفاع ولا يقع في بلوكة سور صلبة
                        if (!isItemAtFence && hDistSq <= 3.5 * 3.5 && Math.abs(dy) <= 1.5) {
                            navigateTowards(client, player, level, lockedItem.blockPosition(), lockedItem.getX(), lockedItem.getY(), lockedItem.getZ(), 0.5);
                            return;
                        }

                        // التنقل الذكي إلى أقرب بلوكة ملاصقة للسور أو للمحصول
                        navigateTowardsCollectedItem(client, player, level, lockedItem, isItemAtFence);
                        return;
                    }

                    // انتهى تجميع المحصول، الانتقال للمرحلة 3: إصلاح الأرض
                    lockedItem = null;
                    itemStuckTicks = 0;
                    currentStage = FarmStage.TILL;
                    stageChecks++;
                    break;
                }

                case TILL: {
                    TargetAction tillTarget = findClosestTillTargetInArea(player, level, currentArea);
                    if (tillTarget != null) {
                        consecutiveEmptyFarms = 0;
                        executeAction(client, player, level, gameMode, tillTarget);
                        return;
                    }
                    // انتهى إصلاح الأرض، الانتقال للمرحلة 4: زراعة الأرض
                    currentStage = FarmStage.PLANT;
                    stageChecks++;
                    break;
                }

                case PLANT: {
                    TargetAction plantTarget = findClosestPlantTargetInArea(player, level, currentArea);
                    if (plantTarget != null) {
                        consecutiveEmptyFarms = 0;
                        executeAction(client, player, level, gameMode, plantTarget);
                        return;
                    }

                    // التحقق مما إذا كانت المزرعة تحتوي على تربة فارغة بحاجة لبذور ولكن نفدت من الحقيبة
                    if (hasUnplantedSoilInArea(level, currentArea)) {
                        FarmCrop neededCrop = currentArea.getTargetCrop();
                        if (neededCrop == null) {
                            neededCrop = findAnyCropNeededInArea(level, currentArea);
                        }

                        if (neededCrop != null && !hasSeedInInventory(player, neededCrop) && !areasOutOfSeeds.contains(currentArea.getName())) {
                            BlockPos withdrawChest = findNextWithdrawChest(level, currentArea, neededCrop, Collections.emptySet());
                            if (withdrawChest != null) {
                                FarmArea.ChestRole roleLabel = currentArea.hasSeparateSeedChestsForCrop(neededCrop)
                                    ? FarmArea.ChestRole.SEED
                                    : FarmArea.ChestRole.CROP;
                                sendChatMessage(client, "§e[Bot] نفذت بذور §a" + neededCrop.getArabicName() + "§e من الحقيبة! يتوجه البوت لجلب البذور من صندوق §b" + roleLabel.getArabicLabel() + "§e لمزرعة §a" + currentArea.getName() + "§e...");
                                visitedWithdrawChests.clear();
                                targetWithdrawArea = currentArea;
                                targetWithdrawCrop = neededCrop;
                                targetWithdrawChestPos = withdrawChest;
                                currentStage = FarmStage.WITHDRAW;
                                resetNavPath();
                                withdrawTimeoutTicks = 0;
                                if (isDriving) {
                                    releaseControls(client);
                                    isDriving = false;
                                }
                                return;
                            } else {
                                sendChatMessage(client, "§c[Bot] نفذت بذور §e" + neededCrop.getArabicName() + "§c في مزرعة §e" + currentArea.getName() + "§c ولا يوجد صندوق مرتبط بها!");
                                areasOutOfSeeds.add(currentArea.getName());
                            }
                        }
                    }

                    // === التحقق المزدوج من اكتمال المزرعة قبل الانتقال للتالية ===
                    // ننتظر بضع نبضات للتأكد من أن كل المحاصيل والتربة قد عولجت
                    if (!doingFinalVerification) {
                        doingFinalVerification = true;
                        finalVerificationTicks = 0;
                        return; // نعود في النبضة التالية للتحقق
                    }

                    finalVerificationTicks++;
                    if (finalVerificationTicks < FINAL_VERIFICATION_WAIT) {
                        return; // انتظار قبل الفحص النهائي
                    }

                    TargetAction recheckHarvest = findClosestHarvestTargetInArea(player, level, currentArea);
                    if (recheckHarvest != null) {
                        // يوجد محصول ناضج لم يُحصد! نعود للحصاد
                        doingFinalVerification = false;
                        finalVerificationTicks = 0;
                        currentStage = FarmStage.HARVEST;
                        return;
                    }
                    TargetAction recheckTill = findClosestTillTargetInArea(player, level, currentArea);
                    if (recheckTill != null) {
                        // يوجد تربة لم تُصلح! نعود لإصلاح الأرض
                        doingFinalVerification = false;
                        finalVerificationTicks = 0;
                        currentStage = FarmStage.TILL;
                        return;
                    }
                    TargetAction recheckPlant = findClosestPlantTargetInArea(player, level, currentArea);
                    if (recheckPlant != null) {
                        // يوجد تربة فارغة لم تُزرع! نعود للزراعة
                        doingFinalVerification = false;
                        finalVerificationTicks = 0;
                        currentStage = FarmStage.PLANT;
                        return;
                    }

                    // اجتاز التحقق المزدوج: المزرعة مكتملة فعلاً!
                    doingFinalVerification = false;
                    finalVerificationTicks = 0;

                    // اكتملت المراحل الأربعة في هذه المزرعة بالكامل!
                    if (actionsInCurrentFarm > 0) {
                        sendChatMessage(client, "§a[Bot] اكتمل العمل في مزرعة §e" + currentArea.getName() + "§a (1.حصاد -> 2.تجميع -> 3.إصلاح -> 4.زراعة). الانتقال للمزرعة التالية...");
                    } else {
                        consecutiveEmptyFarms++;
                    }

                    actionsInCurrentFarm = 0;
                    currentStage = FarmStage.HARVEST;
                    ignoredItemIds.clear();
                    unreachablePositions.clear();
                    lastTargetPos = null;
                    targetStuckTicks = 0;
                    physicalStuckTicks = 0;

                    // إذا كان جوعه ناقصاً عن الامتلاء التام ولديه طعام مسموح، يتناول الطعام بعد انتهاء المزرعة
                    if (player.getFoodData().needsFood() && BotFoodManager.getInstance().hasEdibleAllowedFood(player)) {
                        boolean eating = handleEating(client, player, gameMode);
                        if (eating) {
                            return;
                        }
                    }

                    currentAreaIndex = (currentAreaIndex + 1) % areas.size();

                    // إذا كان خيار النوم مفعل والبعد يدعم الأسرة ومر على الليل 20 ثانية (400 tick)، يتوجه البوت للنوم بعد انتهاء آخر مزرعة
                    if (FarmAreaManager.isBedDimensionSupported(level) && FarmAreaManager.getInstance().isSleepAtNight() && nightTicks >= 400) {
                        sendChatMessage(client, "§e[Bot] مر على الليل 20 ثانية واكتملت المزرعة الحالية. يتوجه البوت الآن للسرير للنوم حتى تطلع الشمس...");
                        currentStage = FarmStage.SLEEP;
                        resetNavPath();
                        if (isDriving) {
                            releaseControls(client);
                            isDriving = false;
                        }
                        return;
                    }

                    // إذا امتلأ المخزون بالكامل، ينهي المزرعة الحالية ثم يتوجه للصناديق لتفريغ المحاصيل والاحتفاظ بـ 64 حبة
                    if (isInventoryFull(player) && tryStartDepositCycle(client, player, level, areas, "المخزون ممتلئ واكتملت المزرعة الحالية")) {
                        return;
                    }

                    // إذا انتهت دورة كاملة على جميع المزارع دون وجود أي عمل مطلوب، ننتظر قليلاً بهدوء أو ننام إن كان ليلاً
                    if (consecutiveEmptyFarms >= areas.size()) {
                        consecutiveEmptyFarms = 0;
                        areasOutOfSeeds.clear();
                        unreachablePositions.clear(); // إتاحة إعادة المحاولة للمسار في الدورة التالية دون حظر دائم

                        waitingAlertTicks += 60;
                        if (waitingAlertTicks >= 1200) { // كل دقيقة تقريباً
                            waitingAlertTicks = 0;
                            sendChatMessage(client, "§e[Bot] المحاصيل في المزارع لم تنضج بعد. البوت في وضع الاستعداد بانتظار نموها...");
                            sendServerChatMessage(player, "المجد لمملكة الصيادين");
                        }

                        if (FarmAreaManager.isBedDimensionSupported(level) && FarmAreaManager.getInstance().isSleepAtNight() && nightTicks >= 400) {
                            sendChatMessage(client, "§e[Bot] مر على الليل 20 ثانية وانتهت جميع الأعمال. يتوجه البوت الآن للسرير للنوم حتى تطلع الشمس...");
                            currentStage = FarmStage.SLEEP;
                            resetNavPath();
                            if (isDriving) {
                                releaseControls(client);
                                isDriving = false;
                            }
                            return;
                        }
                        if (isInventoryFull(player) && tryStartDepositCycle(client, player, level, areas, "المخزون ممتلئ")) {
                            return;
                        }
                        cooldownTicks = 60; // استراحة لمدة 3 ثوانٍ قبل فحص المزارع مرة أخرى
                        resetNavPath();
                        if (isDriving) {
                            releaseControls(client);
                            isDriving = false;
                        }
                        return;
                    }

                    stageChecks++;
                    break;
                }
                default:
                    break;
            }
        }

        // تحرير الأزرار عند عدم وجود أي عمل
        resetNavPath();
        if (isDriving) {
            releaseControls(client);
            isDriving = false;
        }
    }

    private void executeAction(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode, TargetAction target) {
        BlockPos pos = target.pos;
        double targetCenterX = pos.getX() + 0.5;
        double targetCenterY = target.type == ActionType.TILL ? pos.getY() + 1.0 : pos.getY() + 0.5;
        double targetCenterZ = pos.getZ() + 0.5;

        double distSq = player.distanceToSqr(targetCenterX, targetCenterY, targetCenterZ);
        double reachDistance = 3.4; // مسافة وصول مريحة وآمنة

        // التحقق مما إذا كان هناك سياج يفصل اللاعب عن الهدف لمنع التفاعل من خلف السور
        boolean blockedByFence = FarmPathfinder.isPathBlockedByFence(level, player.position(), new Vec3(targetCenterX, targetCenterY, targetCenterZ));

        if (distSq > reachDistance * reachDistance || blockedByFence) {
            // التحرك الذكي نحو الهدف عبر BotNavigator مع تفادي الأسوار والدخول عبر البوابة
            navigateTowards(client, player, level, target.pos, targetCenterX, targetCenterY, targetCenterZ, reachDistance);
        } else {
            // التوقف عن المشي فور الاقتراب الكافي
            resetNavPath();
            if (isDriving) {
                releaseControls(client);
                isDriving = false;
            }

            if (target.type == ActionType.HARVEST) {
                // تدوير الكاميرا بنعومة نحو المحصول
                boolean aimed = smoothLookAt(player, targetCenterX, targetCenterY, targetCenterZ, ROTATION_SPEED);
                if (!aimed) {
                    return;
                }

                // حفظ نوع المحصول قبل تدميره لضمان إعادة زراعة نفس النوع
                BlockState state = level.getBlockState(pos);
                FarmCrop crop = FarmCrop.fromBlock(state.getBlock());
                if (crop != null && target.area != null) {
                    target.area.rememberCrop(pos, crop);
                }

                player.swing(InteractionHand.MAIN_HAND);
                gameMode.startDestroyBlock(pos, Direction.UP);
                gameMode.destroyBlock(pos);
                actionsInCurrentFarm++;
                cooldownTicks = 4;
            } else if (target.type == ActionType.TILL) {
                if (!isValidTillableSpot(level, target.area, pos)) {
                    resetNavPath();
                    cooldownTicks = 1;
                    return;
                }

                // توجيه الكاميرا بنعومة نحو سطح التربة التالفة
                boolean aimed = smoothLookAt(player, targetCenterX, targetCenterY, targetCenterZ, ROTATION_SPEED);
                if (!aimed) {
                    return;
                }

                // التأكد من وجود الفأس الزراعي في اليد اليسرى (Offhand)
                boolean hasHoe = FightBotController.getInstance().ensureHoeInOffhand(client, player, gameMode);
                if (hasHoe && player.getOffhandItem().getItem() instanceof HoeItem) {
                    // اختيار خانة آمنة/فارغة في اليد الرئيسية لمنع أي تعارض أثناء استخدام اليد اليسرى
                    int safeSlot = findSafeInteractionSlot(player);
                    if (safeSlot != -1 && safeSlot != player.getInventory().getSelectedSlot()) {
                        selectHotbarSlot(player, safeSlot);
                    }

                    BlockHitResult hit = new BlockHitResult(
                        new Vec3(targetCenterX, targetCenterY, targetCenterZ),
                        Direction.UP,
                        pos,
                        false
                    );
                    gameMode.useItemOn(player, InteractionHand.OFF_HAND, hit);
                    player.swing(InteractionHand.OFF_HAND);
                    actionsInCurrentFarm++;
                    cooldownTicks = 5;
                } else {
                    // في حال عدم وجود أي فأس، يتم إرسال تنبيه وتخطي إصلاح التربة
                    if (hoeAlertTicks <= 0 && !hasHoeInInventory(player)) {
                        sendChatMessage(client, "§c[Bot] لا يوجد أي فأس زراعي (Hoe) في الحقيبة لإصلاح التربة! يرجى توفير فأس زراعي.");
                        hoeAlertTicks = 200;
                    }
                    cooldownTicks = 2;
                }
            } else if (target.type == ActionType.PLANT) {
                if (!isAirDrySpace(level, pos)) {
                    resetNavPath();
                    cooldownTicks = 1;
                    return;
                }

                // تدوير الكاميرا بنعومة نحو سطح التربة
                boolean aimed = smoothLookAt(player, targetCenterX, pos.getY(), targetCenterZ, ROTATION_SPEED);
                if (!aimed) {
                    return;
                }

                FarmCrop cropToPlant = target.cropToPlant;
                if (cropToPlant == null) return;

                // التأكد من وجود البذور في شريط الأدوات (ونقلها تلقائياً إلى الزر 7 إن كانت في الحقيبة)
                int hotbarSlot = ensureSeedInHotbar(client, player, gameMode, cropToPlant);
                if (hotbarSlot == -1 && target.area.getTargetCrop() == null) {
                    for (FarmCrop fallback : FarmCrop.values()) {
                        if (hasSeedInInventory(player, fallback)) {
                            hotbarSlot = ensureSeedInHotbar(client, player, gameMode, fallback);
                            if (hotbarSlot != -1) {
                                cropToPlant = fallback;
                                break;
                            }
                        }
                    }
                }

                if (hotbarSlot != -1) {
                    selectHotbarSlot(player, hotbarSlot);

                    // التحقق القطعي من أن اليد الرئيسية تحمل البذور فعلياً قبل النقر لمنع النقر باليد الفارغة
                    ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
                    if (held.isEmpty() || !held.is(cropToPlant.getSeedItem())) {
                        cooldownTicks = 2; // انتظار نبضة حتى يكتمل التزامن وتحديث اليد
                        return;
                    }

                    BlockPos soilPos = pos.below();
                    BlockHitResult hit = new BlockHitResult(
                        new Vec3(targetCenterX, pos.getY(), targetCenterZ),
                        Direction.UP,
                        soilPos,
                        false
                    );
                    gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                    player.swing(InteractionHand.MAIN_HAND);
                    actionsInCurrentFarm++;
                    cooldownTicks = 4;
                } else {
                    // لم يعد يملك بذوراً لهذا المحصول في اليد أو الحقيبة، تأخير بسيط للمرور للمزرعة التالية
                    cooldownTicks = 2;
                }
            }
        }
    }

    private void navigateTowardsCollectedItem(Minecraft client, LocalPlayer player, ClientLevel level, ItemEntity item, boolean isItemAtFence) {
        BlockPos navTarget = getBestNavTargetForItem(level, player.blockPosition(), item);
        boolean atExactItem = navTarget.equals(item.blockPosition());
        double destX = atExactItem ? item.getX() : (navTarget.getX() + 0.5);
        double destY = atExactItem ? item.getY() : navTarget.getY();
        double destZ = atExactItem ? item.getZ() : (navTarget.getZ() + 0.5);
        navigateTowards(client, player, level, navTarget, destX, destY, destZ, isItemAtFence ? 1.0 : 0.65);
    }

    /**
     * التنقل الذكي الموحد عبر BotNavigator لتفادي الأسوار والجدران والماء وفتح وإغلاق الأبواب وحماية التربة الزراعية.
     */
    private void navigateTowards(Minecraft client, LocalPlayer player, ClientLevel level, BlockPos goalPos, double targetCenterX, double targetCenterY, double targetCenterZ, double reachDistance) {
        trackedGoalPos = goalPos;

        Vec3 currentPos = player.position();
        if (lastTargetPos != null && goalPos.equals(lastTargetPos)) {
            if (!farmNavigator.isBusyClosingDoor() && lastPlayerPos != null && isDriving) {
                double movedDistSq = currentPos.distanceToSqr(lastPlayerPos);
                if (movedDistSq < 0.005) {
                    physicalStuckTicks++;
                } else {
                    physicalStuckTicks = 0;
                }
            } else {
                physicalStuckTicks = 0;
            }
            if (physicalStuckTicks > 70) {
                unreachablePositions.add(goalPos);
                resetNavPath();
                lastTargetPos = null;
                targetStuckTicks = 0;
                physicalStuckTicks = 0;
                if (isDriving) {
                    releaseControls(client);
                    isDriving = false;
                }
                return;
            }
        } else {
            lastTargetPos = goalPos;
            targetStuckTicks = 0;
            physicalStuckTicks = 0;
        }
        lastPlayerPos = currentPos;

        Vec3 destVec = new Vec3(targetCenterX, targetCenterY, targetCenterZ);
        boolean blockedByFence = FarmPathfinder.isPathBlockedByFence(level, currentPos, destVec);
        double effectiveStopDist = blockedByFence ? 0.6 : Math.max(0.45, Math.min(reachDistance - 0.35, 2.2));
        int distManhattan = Math.abs(player.blockPosition().getX() - goalPos.getX())
            + Math.abs(player.blockPosition().getZ() - goalPos.getZ());
        int maxNodes = distManhattan > 25 ? 15000 : 4000;

        BotNavigator.NavResult navResult = farmNavigator.navigateTo(
            client,
            player,
            level,
            client.gameMode,
            destVec,
            shouldSprint(player),
            effectiveStopDist,
            isEating,
            ROTATION_SPEED,
            true,
            maxNodes,
            FarmPathfinder.PathOptions.FARM_DEFAULT,
            true,
            true,
            null
        );

        if (navResult == BotNavigator.NavResult.BLOCKED_UNREACHABLE || farmNavigator.didLastStepFailUnreachable()) {
            targetStuckTicks++;
            if (targetStuckTicks > 4) {
                if (distManhattan <= 15) {
                    unreachablePositions.add(goalPos);
                }
                resetNavPath();
                lastTargetPos = null;
                targetStuckTicks = 0;
                physicalStuckTicks = 0;
                if (isDriving) {
                    releaseControls(client);
                    isDriving = false;
                }
                return;
            }
        } else {
            targetStuckTicks = 0;
        }

        isDriving = (navResult == BotNavigator.NavResult.MOVING);
    }

    /**
     * إيجاد أفضل بلوكة مجاورة سالكة في المزرعة للتحرك نحو المحصول إذا كان ساقطاً في بلوكة سور صلبة
     */
    private BlockPos getBestNavTargetForItem(ClientLevel level, BlockPos playerPos, ItemEntity item) {
        BlockPos itemPos = item.blockPosition();
        BlockState state = level.getBlockState(itemPos);
        BlockState belowState = level.getBlockState(itemPos.below());

        // إذا لم يكن في بلوكة سور، فالهدف هو بلوكة المحصول نفسها
        if (!FarmPathfinder.isFenceOrWall(state) && !FarmPathfinder.isFenceOrWall(belowState)) {
            return itemPos;
        }

        // إذا كان المحصول في بلوكة سور، نبحث عن أقرب بلوكة مجاورة سالكة في الاتجاهات الأفقية الأربعة
        BlockPos bestNeighbor = null;
        double bestDist = Double.MAX_VALUE;

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = itemPos.relative(dir);
            BlockState nBelow = level.getBlockState(neighbor.below());

            // التأكد من أن الجار سالك للمشي وليس سياجاً وله أرضية صلبة
            if (FarmPathfinder.isPassable(level, neighbor) && !FarmPathfinder.isFenceOrWall(nBelow) && !nBelow.isAir()) {
                double dist = neighbor.distSqr(playerPos);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestNeighbor = neighbor;
                }
            }
        }

        return bestNeighbor != null ? bestNeighbor : itemPos;
    }

    private static boolean isNearFence(ClientLevel level, BlockPos pos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.relative(dir);
            if (FarmPathfinder.isFenceOrWall(level.getBlockState(neighbor))
                || FarmPathfinder.isFenceOrWall(level.getBlockState(neighbor.below()))) {
                return true;
            }
        }
        return false;
    }

    private int findSafeInteractionSlot(LocalPlayer player) {
        return BotEquipmentHelper.findSafeInteractionSlot(player);
    }

    private void selectHotbarSlot(LocalPlayer player, int slot) {
        BotEquipmentHelper.selectHotbarSlot(player, slot);
    }

    private void releaseControls(Minecraft client) {
        if (client.options != null) {
            client.options.keyUp.setDown(false);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keyJump.setDown(false);
            client.options.keySprint.setDown(false);
            if (!FightBotController.getInstance().isDefending() && !FightBotController.getInstance().isInFarmCombat()) {
                client.options.keyUse.setDown(false);
            }
        }
        if (client.player != null) {
            client.player.setSprinting(false);
        }
    }

    private boolean smoothLookAt(LocalPlayer player, double targetX, double targetY, double targetZ, float maxSpeed) {
        double dx = targetX - player.getX();
        double dy = targetY - player.getEyeY();
        double dz = targetZ - player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float targetPitch = (float) (-(Math.atan2(dy, dist) * 180.0 / Math.PI));

        float currentYaw = player.getYRot();
        float diffYaw = wrapDegrees(targetYaw - currentYaw);

        float currentPitch = player.getXRot();
        float diffPitch = targetPitch - currentPitch;

        float stepYaw = Math.max(-maxSpeed, Math.min(maxSpeed, diffYaw));
        float stepPitch = Math.max(-maxSpeed, Math.min(maxSpeed, diffPitch));

        player.setYRot(currentYaw + stepYaw);
        player.setXRot(currentPitch + stepPitch);

        return Math.abs(diffYaw) < 30.0f && Math.abs(diffPitch) < 30.0f;
    }

    private float wrapDegrees(float degrees) {
        return BotEquipmentHelper.wrapDegrees(degrees);
    }

    private TargetAction findClosestHarvestTargetInArea(LocalPlayer player, ClientLevel level, FarmArea area) {
        TargetAction best = null;
        double bestDistSq = Double.MAX_VALUE;

        BlockPos min = area.getMinPos();
        BlockPos max = area.getMaxPos();

        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (unreachablePositions.contains(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    boolean mature = false;

                    FarmCrop crop = FarmCrop.fromBlock(state.getBlock());
                    if (crop != null && crop.isMature(state)) {
                        mature = true;
                    } else if (state.getBlock() instanceof CropBlock cropBlock) {
                        if (cropBlock.isMaxAge(state)) {
                            mature = true;
                        }
                    } else if (state.getBlock() instanceof NetherWartBlock) {
                        if (state.getValue(NetherWartBlock.AGE) >= 3) {
                            mature = true;
                        }
                    }

                    if (mature) {
                        double dist = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                        if (dist < bestDistSq) {
                            bestDistSq = dist;
                            best = new TargetAction(ActionType.HARVEST, pos, area, null);
                        }
                    }
                }
            }
        }
        return best;
    }

    private TargetAction findClosestTillTargetInArea(LocalPlayer player, ClientLevel level, FarmArea area) {
        if (area.getTargetCrop() == FarmCrop.NETHER_WART) {
            return null;
        }

        boolean canTill = (player.getMainHandItem().getItem() instanceof HoeItem) || (findHoeInHotbar(player) != -1) || hasHoeInInventory(player);
        if (!canTill) {
            return null;
        }

        TargetAction best = null;
        double bestDistSq = Double.MAX_VALUE;

        BlockPos min = area.getMinPos();
        BlockPos max = area.getMaxPos();

        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (unreachablePositions.contains(pos)) continue;
                    BlockState state = level.getBlockState(pos);

                    BlockPos dirtPos = null;
                    if (isTillableSoil(state)) {
                        dirtPos = pos;
                    } else if (isAirDrySpace(level, pos)) {
                        dirtPos = pos.below();
                    }

                    if (dirtPos != null) {
                        if (unreachablePositions.contains(dirtPos) || !isValidTillableSpot(level, area, dirtPos)) continue;
                        double dist = player.distanceToSqr(dirtPos.getX() + 0.5, dirtPos.getY() + 1.0, dirtPos.getZ() + 0.5);
                        if (dist < bestDistSq) {
                            bestDistSq = dist;
                            best = new TargetAction(ActionType.TILL, dirtPos, area, null);
                        }
                    }
                }
            }
        }
        return best;
    }

    private boolean isTillableSoil(BlockState state) {
        return state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT);
    }

    /**
     * التحقق من أن الموقع عبارة عن هواء جاف خالٍ من الماء أو أي سائل
     */
    private boolean isAirDrySpace(ClientLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir() && level.getFluidState(pos).isEmpty() && !FarmPathfinder.isWater(level, pos);
    }

    /**
     * التحقق من صلاحية التربة للحرث: تربة صالحة وفوقها هواء جاف خالٍ من الماء
     */
    private boolean isValidTillableSpot(ClientLevel level, FarmArea area, BlockPos dirtPos) {
        if (area != null && area.getTargetCrop() == FarmCrop.NETHER_WART) {
            return false;
        }
        if (!isTillableSoil(level.getBlockState(dirtPos))) {
            return false;
        }
        if (!level.getFluidState(dirtPos).isEmpty() || FarmPathfinder.isWater(level, dirtPos)) {
            return false;
        }
        return isAirDrySpace(level, dirtPos.above());
    }

    private TargetAction findClosestPlantTargetInArea(LocalPlayer player, ClientLevel level, FarmArea area) {
        TargetAction best = null;
        double bestDistSq = Double.MAX_VALUE;

        BlockPos min = area.getMinPos();
        BlockPos max = area.getMaxPos();

        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (unreachablePositions.contains(pos)) continue;

                    if (isAirDrySpace(level, pos)) {
                        BlockState soilState = level.getBlockState(pos.below());
                        FarmCrop cropToPlant = area.getTargetCrop();
                        if (cropToPlant == null) {
                            cropToPlant = area.getRememberedCrop(pos);
                        }

                        if (cropToPlant != null) {
                            if (soilState.getBlock() == cropToPlant.getSoilBlock()) {
                                if (hasSeedInInventory(player, cropToPlant)) {
                                    double dist = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                                    if (dist < bestDistSq) {
                                        bestDistSq = dist;
                                        best = new TargetAction(ActionType.PLANT, pos, area, cropToPlant);
                                    }
                                }
                            }
                        } else {
                            if (soilState.is(Blocks.FARMLAND)) {
                                FarmCrop anySeed = findAnyAvailableCrop(player);
                                if (anySeed != null) {
                                    double dist = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                                    if (dist < bestDistSq) {
                                        bestDistSq = dist;
                                        best = new TargetAction(ActionType.PLANT, pos, area, anySeed);
                                    }
                                }
                            } else if (soilState.is(Blocks.SOUL_SAND) && hasSeedInInventory(player, FarmCrop.NETHER_WART)) {
                                double dist = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                                if (dist < bestDistSq) {
                                    bestDistSq = dist;
                                    best = new TargetAction(ActionType.PLANT, pos, area, FarmCrop.NETHER_WART);
                                }
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private ItemEntity findClosestDroppedItemInArea(LocalPlayer player, ClientLevel level, FarmArea area) {
        ItemEntity best = null;
        double bestDistSq = Double.MAX_VALUE;

        // توسيع نطاق التقاط المحصول بمقدار 2 بلوكة إضافية خارج حدود المزرعة في جميع الاتجاهات الأفقية
        AABB box = new AABB(
            area.getMinPos().getX() - 2.0, area.getMinPos().getY() - 1.0, area.getMinPos().getZ() - 2.0,
            area.getMaxPos().getX() + 3.0, area.getMaxPos().getY() + 2.0, area.getMaxPos().getZ() + 3.0
        );
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box);
        for (ItemEntity item : items) {
            if (item.isAlive() && !ignoredItemIds.contains(item.getId()) && isFarmItem(item)) {
                double dist = player.distanceToSqr(item);
                if (dist < bestDistSq) {
                    bestDistSq = dist;
                    best = item;
                }
            }
        }
        return best;
    }

    private boolean isFarmItem(ItemEntity item) {
        ItemStack stack = item.getItem();
        if (stack.isEmpty()) return false;
        return stack.is(net.minecraft.world.item.Items.WHEAT)
            || stack.is(net.minecraft.world.item.Items.WHEAT_SEEDS)
            || stack.is(net.minecraft.world.item.Items.CARROT)
            || stack.is(net.minecraft.world.item.Items.POTATO)
            || stack.is(net.minecraft.world.item.Items.BAKED_POTATO)
            || stack.is(net.minecraft.world.item.Items.POISONOUS_POTATO)
            || stack.is(net.minecraft.world.item.Items.BEETROOT)
            || stack.is(net.minecraft.world.item.Items.BEETROOT_SEEDS)
            || stack.is(net.minecraft.world.item.Items.NETHER_WART)
            || stack.is(net.minecraft.world.item.Items.MELON_SLICE)
            || stack.is(net.minecraft.world.item.Items.PUMPKIN);
    }

    private int findHoeInHotbar(LocalPlayer player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof HoeItem) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasHoeInInventory(LocalPlayer player) {
        if (player.getMainHandItem().getItem() instanceof HoeItem || player.getOffhandItem().getItem() instanceof HoeItem) {
            return true;
        }
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof HoeItem) {
                return true;
            }
        }
        return false;
    }

    private int findSeedInHotbar(LocalPlayer player, FarmCrop crop) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(crop.getSeedItem())) {
                return i;
            }
        }
        return -1;
    }

    private int findSeedInMainInventory(LocalPlayer player, FarmCrop crop) {
        Inventory inv = player.getInventory();
        for (int i = 9; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(crop.getSeedItem())) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasSeedInInventory(LocalPlayer player, FarmCrop crop) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(crop.getSeedItem())) {
                return true;
            }
        }
        return false;
    }

    /**
     * التأكد من وجود البذور في شريط الأدوات (Hotbar).
     * إذا لم تكن موجودة في أي من الخانات الـ 9 وكانت بالحقيبة، يتم نقلها تلقائياً إلى الزر 7 (الفهرس 6)
     * وتبديل ما كان في الزر 7 إلى مكانها في الحقيبة.
     */
    private int ensureSeedInHotbar(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode, FarmCrop crop) {
        // 1. أولاً: البحث في خانات شريط الأدوات الـ 9
        int existingHotbarSlot = findSeedInHotbar(player, crop);
        if (existingHotbarSlot != -1) {
            return existingHotbarSlot;
        }

        int containerId = player.inventoryMenu.containerId;

        // 2. ثانياً: البحث في الحقيبة الرئيسية (الخانات 9 إلى 35)
        int bagSlot = findSeedInMainInventory(player, crop);
        if (bagSlot != -1) {
            // تبديل البذور مع خانة الزر رقم 7 (الفهرس 6)
            gameMode.handleContainerInput(containerId, bagSlot, SEED_HOTBAR_SLOT, ContainerInput.SWAP, player);
            return SEED_HOTBAR_SLOT;
        }

        // 3. ثالثاً: فحص اليد اليسرى (Offhand) إذا كانت تحمل البذور
        if (!player.getOffhandItem().isEmpty() && player.getOffhandItem().is(crop.getSeedItem())) {
            // خانة اليد اليسرى في InventoryMenu هي 45
            gameMode.handleContainerInput(containerId, 45, SEED_HOTBAR_SLOT, ContainerInput.SWAP, player);
            return SEED_HOTBAR_SLOT;
        }

        return -1;
    }

    private FarmCrop findAnyAvailableCrop(LocalPlayer player) {
        for (FarmCrop crop : FarmCrop.values()) {
            if (hasSeedInInventory(player, crop)) {
                return crop;
            }
        }
        return null;
    }

    /**
     * العثور على أفضل طعام مسموح وصالح للأكل في شريط الأدوات أو نقله للخانة 9 (الفهرس 8).
     */
    private int ensureFoodInHotbar(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        return BotFoodManager.getInstance().ensureBestFoodInHotbar(player, gameMode, FOOD_HOTBAR_SLOT, SEED_HOTBAR_SLOT);
    }

    /**
     * معالجة تناول الطعام المسموح حبة تلو الأخرى حتى الامتلاء أو انتهاء الحاجة للأكل.
     */
    private boolean handleEating(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        int foodLevel = player.getFoodData().getFoodLevel();
        BotFoodManager foodMgr = BotFoodManager.getInstance();

        if (!foodMgr.hasEdibleAllowedFood(player)) {
            if (isEating) {
                client.options.keyUse.setDown(false);
                isEating = false;
                eatingTicks = 0;
                lastFoodLevel = -1;
            }
            return false;
        }

        // إيقاف الحركة تماماً أثناء تناول الطعام
        if (isDriving) {
            releaseControls(client);
            isDriving = false;
        }

        int foodSlot = ensureFoodInHotbar(client, player, gameMode);
        if (foodSlot == -1) {
            return false;
        }

        if (player.getInventory().getSelectedSlot() != foodSlot) {
            selectHotbarSlot(player, foodSlot);
        }

        // إذا تم ابتلاع حبة بنجاح (زاد مستوى الجوع): تصفير عداد الحبة الحالية للبدء بالحبة التالية
        if (isEating && lastFoodLevel != -1 && foodLevel > lastFoodLevel) {
            eatingTicks = 0;
        }
        lastFoodLevel = foodLevel;

        isEating = true;
        eatingTicks++;

        // الضغط على زر الاستخدام وتجديد الأكل لكل حبة تالية حتى الشبع التام
        client.options.keyUse.setDown(true);
        if (!player.isUsingItem()) {
            client.options.keyUse.setDown(false); // تحرير لحظي للزر لمحاكاة نقرة جديدة للحبة التالية
            gameMode.useItem(player, InteractionHand.MAIN_HAND);
            player.startUsingItem(InteractionHand.MAIN_HAND);
            client.options.keyUse.setDown(true);
        }

        // مهلة أمان للحبة الواحدة (أكثر من 45 نبضة دون ابتلاعها)
        if (eatingTicks > 45) {
            client.options.keyUse.setDown(false);
            isEating = false;
            eatingTicks = 0;
            lastFoodLevel = -1;
            return false;
        }

        return true;
    }

    private FarmArea getCurrentArea() {
        List<FarmArea> areas = FarmAreaManager.getInstance().getActiveAreas();
        if (areas.isEmpty() || currentAreaIndex >= areas.size()) {
            return null;
        }
        return areas.get(currentAreaIndex);
    }

    private boolean shouldSprint(LocalPlayer player) {
        // لا يمكن السبرنت إذا كان الجوع 6 أو أقل في ماينكرافت
        if (player.getFoodData().getFoodLevel() <= 6) {
            return false;
        }

        // السباحة السريعة على سطح الماء
        if (player.isInWater()) {
            return true;
        }

        // عند الانتقال للسرير
        if (currentStage == FarmStage.SLEEP) {
            BlockPos sleepTarget = trackedGoalPos != null ? trackedGoalPos : FarmAreaManager.getInstance().getBedPos();
            if (sleepTarget != null && player.distanceToSqr(sleepTarget.getX() + 0.5, sleepTarget.getY() + 0.5, sleepTarget.getZ() + 0.5) <= 6.0 * 6.0) {
                // التهدئة والمشي الطبيعي عند الاقتراب من السرير والمنزل لتفادي الاندفاع ولإغلاق الباب بدقة
                return false;
            }
            return true;
        }

        // عند الانتقال للصندوق لتفريغ المحصول أو سحب البذور أو الكومبوستر أو صندوق السماد
        if (currentStage == FarmStage.DEPOSIT || currentStage == FarmStage.WITHDRAW || currentStage == FarmStage.COMPOST || currentStage == FarmStage.DEPOSIT_FERTILIZER) {
            return true;
        }

        FarmArea currentArea = getCurrentArea();
        // عند الانتقال بين المزارع أو إذا كان البوت خارج حدود المزرعة الحالية
        if (currentArea == null || !currentArea.contains(player.blockPosition())) {
            return true;
        }

        // داخل حدود المزرعة الحالية أثناء العمل الزراعي: حركة طبيعية بدون سبرنت
        return false;
    }

    /**
     * معالجة الذهاب إلى السرير والنوم عند حلول الليل (في الأبعاد التي تدعم الأسرة فقط)
     */
    private void handleSleepStage(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode) {
        if (!FarmAreaManager.isBedDimensionSupported(level)) {
            if (!dimensionSleepNoticeSent) {
                sendChatMessage(client, "§e[Bot] البُعد الحالي (" + FarmAreaManager.getInstance().getCurrentDimensionDisplayName() + ") لا يدعم النوم بالسرير! تجاهل النوم والاستمرار بالزراعة.");
                dimensionSleepNoticeSent = true;
            }
            nightTicks = 0;
            resetNavPath();
            currentStage = FarmStage.HARVEST;
            return;
        }

        if (player.isSleeping()) {
            if (isDriving) {
                releaseControls(client);
                isDriving = false;
            }
            player.getSleepingPos().ifPresent(pos -> {
                if (FarmAreaManager.getInstance().getBedPos() == null) {
                    FarmAreaManager.getInstance().setBedPos(pos);
                }
            });
            return;
        }

        // تحديد موقع السرير (التحقق من صلاحية المحفوظ وعدم انشغاله، أو البحث عن سرير شاغر قريب)
        BlockPos bedPos = FarmAreaManager.getInstance().getBedPos();
        if (bedPos != null) {
            BlockState savedState = level.getBlockState(bedPos);
            if (!isBedBlock(savedState)) {
                FarmAreaManager.getInstance().setBedPos(null);
                bedPos = null;
            } else if (isBedOccupied(level, bedPos)) {
                BlockPos alternateBed = findNearbyBed(player, level, 48);
                if (alternateBed != null) {
                    bedPos = alternateBed;
                } else {
                    if (!bedAlertSent) {
                        sendChatMessage(client, "§e[Bot] السرير المحدد مشغول حالياً ولا يوجد سرير آخر شاغر! يواصل البوت الزراعة حتى يتفرغ السرير.");
                        bedAlertSent = true;
                    }
                    resetNavPath();
                    currentStage = FarmStage.HARVEST;
                    return;
                }
            }
        }

        if (bedPos == null) {
            bedPos = findNearbyBed(player, level, 48);
            if (bedPos != null) {
                FarmAreaManager.getInstance().setBedPos(bedPos);
                sendChatMessage(client, "§a[Bot] تم العثور تلقائياً على سرير قريب عند: §e" + bedPos.getX() + ", " + bedPos.getY() + ", " + bedPos.getZ());
            }
        }

        if (bedPos == null) {
            if (!bedAlertSent) {
                sendChatMessage(client, "§c[Bot] حان وقت النوم ولكن لم يتم العثور على أي سرير شاغر! يرجى تعيين السرير عبر: §e/bot farm bed set §cأو وضع سرير بالقرب منك.");
                bedAlertSent = true;
            }
            // في حال عدم وجود أي سرير، يواصل العمل في المزارع حتى لا يتوقف
            resetNavPath();
            currentStage = FarmStage.HARVEST;
            return;
        }

        double bedCenterX = bedPos.getX() + 0.5;
        double bedCenterY = bedPos.getY() + 0.5;
        double bedCenterZ = bedPos.getZ() + 0.5;
        double distSq = player.distanceToSqr(bedCenterX, bedCenterY, bedCenterZ);

        if (distSq > 2.8 * 2.8) {
            // التوجه الذكي نحو السرير مع تفادي العقبات والماء وفتح وإغلاق الأبواب
            navigateTowards(client, player, level, bedPos, bedCenterX, bedCenterY, bedCenterZ, 2.5);

            // رسم خط مسار الذهاب إلى السرير بجزيئات مرئية خضراء متألقة فوق الجسور وبالممرات
            List<BlockPos> activePath = farmNavigator.getCurrentPath();
            if (!activePath.isEmpty()) {
                int drawLimit = Math.min(activePath.size(), 24);
                for (int i = 0; i < drawLimit; i++) {
                    BlockPos p = activePath.get(i);
                    level.addParticle(
                        ParticleTypes.HAPPY_VILLAGER,
                        p.getX() + 0.5,
                        p.getY() + 0.15,
                        p.getZ() + 0.5,
                        0.0, 0.02, 0.0
                    );
                }
            }
        } else {
            // الوصول إلى السرير: التوقف والتأكد من إغلاق أي باب مفتوح في الغرفة قبل الاستلقاء للنوم
            resetNavPath();
            if (isDriving) {
                releaseControls(client);
                isDriving = false;
            }

            // فحص هل يوجد باب مفتوح في محيط الغرفة (ضمن مدى التفاعل حتى 4.2 بلوكات)
            BlockPos openDoorPos = findNearbyOpenDoor(player, level, 4.2);
            if (openDoorPos != null && sleepDoorCooldown <= 0) {
                int safeSlot = findSafeInteractionSlot(player);
                if (safeSlot != -1 && safeSlot != player.getInventory().getSelectedSlot()) {
                    selectHotbarSlot(player, safeSlot);
                }

                smoothLookAt(player, openDoorPos.getX() + 0.5, openDoorPos.getY() + 0.5, openDoorPos.getZ() + 0.5, 60.0f);
                Vec3 hitVec = Vec3.atCenterOf(openDoorPos);
                BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, openDoorPos, false);
                gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                player.swing(InteractionHand.MAIN_HAND);
                sleepDoorCooldown = 6;
                farmNavigator.resetDoorState(client);
                cooldownTicks = 5;
                return;
            }

            smoothLookAt(player, bedCenterX, bedCenterY, bedCenterZ, ROTATION_SPEED);

            BlockHitResult hit = new BlockHitResult(
                new Vec3(bedCenterX, bedCenterY, bedCenterZ),
                Direction.UP,
                bedPos,
                false
            );
            gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            cooldownTicks = 20; // راحة ثانية بين محاولات التفاعل
        }
    }

    /**
     * البحث عن أي باب أو بوابة مفتوحة في محيط اللاعب ضمن مسافة تفاعل آمنة
     */
    private BlockPos findNearbyOpenDoor(LocalPlayer player, ClientLevel level, double maxDist) {
        BlockPos playerPos = player.blockPosition();
        int r = (int) Math.ceil(maxDist);
        BlockPos bestDoor = null;
        double bestDistSq = maxDist * maxDist;

        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = playerPos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(p);
                    boolean isOpen = (FarmPathfinder.isOpenableDoor(state) && state.getValue(DoorBlock.OPEN))
                                  || (FarmPathfinder.isOpenableGate(state) && state.getValue(FenceGateBlock.OPEN));
                    if (isOpen) {
                        double dSq = player.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
                        if (dSq <= bestDistSq) {
                            bestDistSq = dSq;
                            bestDoor = p.immutable();
                        }
                    }
                }
            }
        }
        return bestDoor;
    }

    private static boolean isBedBlock(BlockState state) {
        return state != null && (state.getBlock() instanceof BedBlock || state.is(BlockTags.BEDS));
    }

    private static boolean isBedOccupied(ClientLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        BlockState state = level.getBlockState(pos);
        if (state.getOptionalValue(BedBlock.OCCUPIED).orElse(false)) {
            return true;
        }
        Direction facing = state.getOptionalValue(BedBlock.FACING).orElse(null);
        BedPart part = state.getOptionalValue(BedBlock.PART).orElse(null);
        if (facing != null && part != null) {
            BlockPos partner = (part == BedPart.FOOT) ? pos.relative(facing) : pos.relative(facing.getOpposite());
            BlockState partnerState = level.getBlockState(partner);
            if (isBedBlock(partnerState) && partnerState.getOptionalValue(BedBlock.OCCUPIED).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    private BlockPos findNearbyBed(LocalPlayer player, ClientLevel level, int radius) {
        BlockPos origin = player.blockPosition();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int y = -8; y <= 8; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (isBedBlock(state)
                            && !isBedOccupied(level, pos)
                            && !FarmPathfinder.isHazard(level.getBlockState(pos.above()))) {
                        double dist = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                        if (dist < bestDistSq) {
                            bestDistSq = dist;
                            best = pos.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * التحقق مما إذا كانت هناك أي تربة فارغة بحاجة لزراعة في المزرعة
     */
    private boolean hasUnplantedSoilInArea(ClientLevel level, FarmArea area) {
        BlockPos min = area.getMinPos();
        BlockPos max = area.getMaxPos();
        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (unreachablePositions.contains(pos)) continue;
                    if (isAirDrySpace(level, pos)) {
                        BlockState below = level.getBlockState(pos.below());
                        if (below.is(Blocks.FARMLAND) || below.is(Blocks.SOUL_SAND)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * تحديد نوع المحصول المطلوب زراعته في المزرعة بناءً على التربة والذاكرة
     */
    private FarmCrop findAnyCropNeededInArea(ClientLevel level, FarmArea area) {
        if (area.getTargetCrop() != null) return area.getTargetCrop();
        BlockPos min = area.getMinPos();
        BlockPos max = area.getMaxPos();
        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isAirDrySpace(level, pos)) {
                        FarmCrop rem = area.getRememberedCrop(pos);
                        if (rem != null) return rem;
                        BlockState below = level.getBlockState(pos.below());
                        if (below.is(Blocks.SOUL_SAND)) return FarmCrop.NETHER_WART;
                        if (below.is(Blocks.FARMLAND)) return FarmCrop.WHEAT;
                    }
                }
            }
        }
        return FarmCrop.WHEAT;
    }

    private static void markChestVisited(ClientLevel level, Set<BlockPos> visited, BlockPos pos) {
        if (pos == null || visited == null) return;
        visited.add(pos.immutable());
        BlockPos half = FarmAreaManager.getConnectedChestHalf(level, pos);
        if (half != null) {
            visited.add(half);
        }
    }

    private static boolean isChestVisited(ClientLevel level, Set<BlockPos> visited, BlockPos pos) {
        return pos != null && visited != null && FarmAreaManager.containsStorageContainer(level, visited, pos);
    }

    /**
     * التحرك نحو حاوية وفتحها، مع إرجاع false فوراً إذا ثبت عدم وجود حاوية في الموقع القريب.
     */
    private boolean navigateAndOpenContainer(
            Minecraft client,
            LocalPlayer player,
            ClientLevel level,
            MultiPlayerGameMode gameMode,
            BlockPos chestPos) {
        if (chestPos == null) return false;
        double cx = chestPos.getX() + 0.5;
        double cy = chestPos.getY() + 0.5;
        double cz = chestPos.getZ() + 0.5;
        double distSq = player.distanceToSqr(cx, cy, cz);

        if (distSq <= 8.0 * 8.0 && !FarmAreaManager.isStorageContainerBlock(level.getBlockState(chestPos))) {
            return false;
        }

        if (distSq > 2.8 * 2.8) {
            navigateTowards(client, player, level, chestPos, cx, cy, cz, 2.5);
        } else {
            resetNavPath();
            if (isDriving) {
                releaseControls(client);
                isDriving = false;
            }

            smoothLookAt(player, cx, cy, cz, ROTATION_SPEED);

            BlockHitResult hit = new BlockHitResult(
                new Vec3(cx, cy, cz),
                Direction.UP,
                chestPos,
                false
            );
            gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            cooldownTicks = 15; // انتظار استجابة السيرفر وفتح الحاوية
        }
        return true;
    }

    private BlockPos findNextWithdrawChest(
            ClientLevel level,
            FarmArea area,
            FarmCrop crop,
            Set<BlockPos> excludedChests) {
        if (area == null || crop == null) return null;
        List<BlockPos> candidates = area.hasSeparateSeedChestsForCrop(crop)
            ? area.getSeedChests()
            : area.getCropChests();
        for (BlockPos pos : candidates) {
            if (!isChestVisited(level, excludedChests, pos)) {
                return pos;
            }
        }
        return null;
    }

    private void advanceOrFinishWithdraw(
            Minecraft client,
            ClientLevel level,
            String emptyOrErrorMsg) {
        if (targetWithdrawChestPos != null) {
            markChestVisited(level, visitedWithdrawChests, targetWithdrawChestPos);
        }
        BlockPos nextChest = findNextWithdrawChest(level, targetWithdrawArea, targetWithdrawCrop, visitedWithdrawChests);
        if (nextChest != null) {
            sendChatMessage(client, "§e[Bot] " + emptyOrErrorMsg + " ينتقل البوت الآن للصندوق التالي للمزرعة...");
            targetWithdrawChestPos = nextChest;
            withdrawTimeoutTicks = 0;
            resetNavPath();
            cooldownTicks = 10;
            return;
        }

        if (targetWithdrawArea != null) {
            sendChatMessage(client, "§c[Bot] " + emptyOrErrorMsg + " في جميع صناديق مزرعة §e" + targetWithdrawArea.getName() + "§c! تجاوز مرحلة الزراعة لهذه الدورة.");
            areasOutOfSeeds.add(targetWithdrawArea.getName());
        }
        targetWithdrawArea = null;
        targetWithdrawCrop = null;
        targetWithdrawChestPos = null;
        visitedWithdrawChests.clear();
        withdrawTimeoutTicks = 0;
        cooldownTicks = 15;
        currentStage = FarmStage.HARVEST;
        List<FarmArea> active = FarmAreaManager.getInstance().getActiveAreas();
        if (!active.isEmpty()) {
            currentAreaIndex = (currentAreaIndex + 1) % active.size();
        }
    }

    /**
     * معالجة الذهاب إلى الصندوق لجلب البذور عند نفادها من الحقيبة (مع دعم تعدد الصناديق وصندوق البذور المنفصل)
     */
    private void handleWithdrawStage(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode) {
        if (targetWithdrawArea == null || targetWithdrawChestPos == null || targetWithdrawCrop == null) {
            targetWithdrawArea = null;
            targetWithdrawCrop = null;
            targetWithdrawChestPos = null;
            visitedWithdrawChests.clear();
            currentStage = FarmStage.PLANT;
            return;
        }

        // إذا كان الصندوق مفتوحاً بالفعل (شاشة الحاوية مفتوحة)
        if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            if (isDriving) {
                releaseControls(client);
                isDriving = false;
            }

            if (withdrawCloseDelay == 0) {
                lastWithdrawnCount = withdrawSeedsFromOpenChest(player, gameMode, targetWithdrawCrop);
                withdrawCloseDelay = 4; // انتظار 4 نبضات لضمان معالجة السيرفر لحزم النقل
                return;
            }

            if (withdrawCloseDelay > 1) {
                withdrawCloseDelay--;
                return;
            }

            withdrawCloseDelay = 0;
            player.closeContainer();

            if (lastWithdrawnCount > 0) {
                sendChatMessage(client, "§a[Bot] تم سحب §e" + lastWithdrawnCount + " §aحبة بذور (" + targetWithdrawCrop.getArabicName() + ") من الصندوق بنجاح! استئناف الزراعة...");
                currentStage = FarmStage.PLANT;
                targetWithdrawArea = null;
                targetWithdrawCrop = null;
                targetWithdrawChestPos = null;
                visitedWithdrawChests.clear();
                withdrawTimeoutTicks = 0;
                cooldownTicks = 15;
            } else {
                advanceOrFinishWithdraw(client, level, "لا توجد بذور (" + targetWithdrawCrop.getArabicName() + ") في هذا الصندوق!");
            }
            return;
        } else {
            withdrawCloseDelay = 0;
        }

        // مهلة الوصول للصندوق (20 ثانية = 400 نبضة)
        withdrawTimeoutTicks++;
        if (withdrawTimeoutTicks > 400) {
            advanceOrFinishWithdraw(client, level, "تعذر فتح هذا الصندوق لسحب البذور!");
            return;
        }

        if (!navigateAndOpenContainer(client, player, level, gameMode, targetWithdrawChestPos)) {
            advanceOrFinishWithdraw(client, level, "الصندوق المحدد لسحب البذور غير موجود في مكانه!");
        }
    }

    /**
     * سحب البذور من الصندوق المفتوح ونقلها لحقيبة اللاعب عبر Quick Move
     */
    private int withdrawSeedsFromOpenChest(LocalPlayer player, MultiPlayerGameMode gameMode, FarmCrop crop) {
        var menu = player.containerMenu;
        if (menu == null) return 0;

        int totalWithdrawn = 0;
        int maxToTake = 128; // سحب ما يصل إلى حزمتين (128 بذرة)

        for (int slotId = 0; slotId < menu.slots.size(); slotId++) {
            if (totalWithdrawn >= maxToTake) break;

            var slot = menu.getSlot(slotId);
            // البحث فقط في خانات الصندوق (وليس في حقيبة اللاعب)
            if (slot.container != player.getInventory()) {
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty() && stack.is(crop.getSeedItem())) {
                    int count = stack.getCount();
                    gameMode.handleContainerInput(menu.containerId, slotId, 0, ContainerInput.QUICK_MOVE, player);
                    totalWithdrawn += count;
                }
            }
        }
        return totalWithdrawn;
    }

    private boolean tryStartDepositCycle(
            Minecraft client,
            LocalPlayer player,
            ClientLevel level,
            List<FarmArea> activeAreas,
            String reasonPrefix) {
        visitedDepositChests.clear();
        activeDepositPlan = null;
        attemptedOutputChestsCrops.clear();
        attemptedSeedChestsCrops.clear();
        clearOverflowFullCrops();

        DepositStop stop = findNextDepositStop(player, level, activeAreas, visitedDepositChests);
        if (stop == null) {
            return false;
        }

        applyDepositStop(stop);
        currentStage = FarmStage.DEPOSIT;
        resetNavPath();
        depositTimeoutTicks = 0;
        if (isDriving) {
            releaseControls(client);
            isDriving = false;
        }
        sendChatMessage(client, "§e[Bot] " + reasonPrefix + "! يتوجه الآن إلى صندوق §b"
            + stop.role().getArabicLabel() + "§e لمزرعة §a" + stop.area().getName()
            + "§e مع الاحتفاظ بـ 64 بذرة...");
        return true;
    }

    private void applyDepositStop(DepositStop stop) {
        targetDepositArea = stop.area();
        targetDepositChestPos = stop.chestPos();
        targetDepositRole = stop.role();
        activeDepositPlan = stop.plan();
    }

    private void recordAttemptedDepositPlan(DepositPlan plan, List<FarmArea> activeAreas, ClientLevel level) {
        if (plan == null) return;
        attemptedOutputChestsCrops.addAll(plan.targetOutputCrops());
        attemptedSeedChestsCrops.addAll(plan.targetSeedCrops());
        if (plan.allowUnmappedOutputs() || plan.allowUnmappedSeeds()) {
            for (FarmCrop workingCrop : getActiveWorkingCrops(level, activeAreas)) {
                if (plan.allowUnmappedOutputs() && !plan.excludedOutputCropsWithOtherChests().contains(workingCrop)) {
                    attemptedOutputChestsCrops.add(workingCrop);
                }
                if (plan.allowUnmappedSeeds() && workingCrop.hasDistinctSeedItem()
                        && !plan.excludedSeedCropsWithOtherChests().contains(workingCrop)) {
                    attemptedSeedChestsCrops.add(workingCrop);
                }
            }
        }
    }

    private void clearOverflowFullCrops() {
        fullOutputChestCrops.clear();
        fullSeedChestCrops.clear();
    }

    private boolean hasOverflowFullCrops() {
        return !fullOutputChestCrops.isEmpty() || !fullSeedChestCrops.isEmpty();
    }

    /**
     * معالجة الذهاب إلى الصندوق وتفريغ المحاصيل والبذور الفائضة مع الاحتفاظ بـ 64 بذرة،
     * ودعم تعدد الصناديق وفصل صندوق البذور عن صندوق المحصول دون خلط.
     */
    private void handleDepositStage(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode) {
        if (targetDepositArea == null || targetDepositChestPos == null) {
            targetDepositArea = null;
            targetDepositChestPos = null;
            activeDepositPlan = null;
            visitedDepositChests.clear();
            currentStage = FarmStage.HARVEST;
            return;
        }

        // إذا كان الصندوق مفتوحاً بالفعل (شاشة الحاوية مفتوحة)
        if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            if (isDriving) {
                releaseControls(client);
                isDriving = false;
            }

            List<FarmArea> activeAreas = FarmAreaManager.getInstance().getActiveAreas();
            if (depositCloseDelay == 0) {
                DepositPlan plan = buildDepositPlan(targetDepositChestPos, activeAreas, level, visitedDepositChests);
                activeDepositPlan = plan;
                lastDepositedCount = depositSurplusIntoOpenChest(player, gameMode, plan);
                depositCloseDelay = 4; // انتظار 4 نبضات لضمان معالجة السيرفر لحزم الإيداع
                return;
            }

            if (depositCloseDelay > 1) {
                depositCloseDelay--;
                return;
            }

            depositCloseDelay = 0;
            player.closeContainer();

            DepositPlan usedPlan = activeDepositPlan != null
                ? activeDepositPlan
                : buildDepositPlan(targetDepositChestPos, activeAreas, level, visitedDepositChests);
            recordAttemptedDepositPlan(usedPlan, activeAreas, level);
            markChestVisited(level, visitedDepositChests, targetDepositChestPos);

            if (lastDepositedCount > 0) {
                sendChatMessage(client, "§a[Bot] تم تفريغ (" + lastDepositedCount + " حزمة) في صندوق §b"
                    + targetDepositRole.getArabicLabel() + "§a لمزرعة §e" + targetDepositArea.getName() + "§a بنجاح!");
            } else {
                sendChatMessage(client, "§e[Bot] تم فحص صندوق §b" + targetDepositRole.getArabicLabel()
                    + "§e لمزرعة §e" + targetDepositArea.getName() + "§e؛ لا توجد عناصر مناسبة إضافية أو الصندوق ممتلئ!");
            }

            DepositStop nextStop = findNextDepositStop(player, level, activeAreas, visitedDepositChests);
            if (nextStop != null) {
                sendChatMessage(client, "§6[Bot] يتوجه الآن إلى صندوق §b" + nextStop.role().getArabicLabel()
                    + "§6 التالي لمزرعة §e" + nextStop.area().getName() + "§6 لتفريغ الباقي...");
                applyDepositStop(nextStop);
                depositTimeoutTicks = 0;
                resetNavPath();
                cooldownTicks = 15;
                return;
            }

            handlePostDepositFullChests(client, player, level, activeAreas);
            return;
        } else {
            depositCloseDelay = 0;
        }

        // إذا لم يكن الصندوق مفتوحاً بعد، نتحرك نحوه مع مراقبة الوقت وتوفر البلوكة
        depositTimeoutTicks++;
        boolean blockOk = navigateAndOpenContainer(client, player, level, gameMode, targetDepositChestPos);
        if (!blockOk || depositTimeoutTicks > 400) {
            String reason = !blockOk ? "الصندوق غير موجود في مكانه!" : "تعذر فتح الصندوق!";
            sendChatMessage(client, "§c[Bot] " + reason + " (صندوق §b" + targetDepositRole.getArabicLabel()
                + "§c لمزرعة §e" + targetDepositArea.getName() + "§c) - فحص الصناديق التالية...");
            List<FarmArea> activeAreas = FarmAreaManager.getInstance().getActiveAreas();
            DepositPlan skippedPlan = activeDepositPlan != null
                ? activeDepositPlan
                : buildDepositPlan(targetDepositChestPos, activeAreas, level, visitedDepositChests);
            recordAttemptedDepositPlan(skippedPlan, activeAreas, level);
            markChestVisited(level, visitedDepositChests, targetDepositChestPos);

            DepositStop nextStop = findNextDepositStop(player, level, activeAreas, visitedDepositChests);
            if (nextStop != null) {
                applyDepositStop(nextStop);
                depositTimeoutTicks = 0;
                resetNavPath();
                return;
            }
            handlePostDepositFullChests(client, player, level, activeAreas);
        }
    }

    /**
     * تحديد نوع المحصول الفعلي لمزرعة معينة (سواء من المحصول المخصص targetCrop أو المحاصيل المسجلة)
     */
    public static FarmCrop getEffectiveCrop(FarmArea area) {
        if (area == null) return null;
        if (area.getTargetCrop() != null) return area.getTargetCrop();
        // إذا كان النمط AUTO، نتحقق من المحاصيل المسجلة لهذه المزرعة
        Map<FarmCrop, Integer> counts = new HashMap<>();
        for (FarmCrop crop : area.getRememberedCrops().values()) {
            if (crop != null) {
                counts.put(crop, counts.getOrDefault(crop, 0) + 1);
            }
        }
        FarmCrop dominant = null;
        int max = 0;
        for (var entry : counts.entrySet()) {
            if (entry.getValue() > max) {
                max = entry.getValue();
                dominant = entry.getKey();
            }
        }
        return dominant;
    }

    /**
     * التحقق مما إذا كان عنصر معين ينتمي لنوع محصول محدد
     */
    public static boolean doesItemBelongToCrop(net.minecraft.world.item.Item item, FarmCrop crop) {
        if (item == null || crop == null) return false;
        return switch (crop) {
            case WHEAT -> item == net.minecraft.world.item.Items.WHEAT
                       || item == net.minecraft.world.item.Items.WHEAT_SEEDS;
            case CARROT -> item == net.minecraft.world.item.Items.CARROT;
            case POTATO -> item == net.minecraft.world.item.Items.POTATO
                        || item == net.minecraft.world.item.Items.POISONOUS_POTATO;
            case BEETROOT -> item == net.minecraft.world.item.Items.BEETROOT
                          || item == net.minecraft.world.item.Items.BEETROOT_SEEDS;
            case NETHER_WART -> item == net.minecraft.world.item.Items.NETHER_WART;
        };
    }

    /**
     * فحص هل العنصر من محاصيل المزرعة ومنتجات الحصاد التي يتم إيداعها في الصندوق
     */
    private boolean isFarmDepositItem(net.minecraft.world.item.Item item) {
        return item == net.minecraft.world.item.Items.WHEAT
            || item == net.minecraft.world.item.Items.BEETROOT
            || item == net.minecraft.world.item.Items.POISONOUS_POTATO
            || item == net.minecraft.world.item.Items.WHEAT_SEEDS
            || item == net.minecraft.world.item.Items.BEETROOT_SEEDS
            || item == net.minecraft.world.item.Items.CARROT
            || item == net.minecraft.world.item.Items.POTATO
            || item == net.minecraft.world.item.Items.NETHER_WART
            || item == net.minecraft.world.item.Items.MELON_SLICE
            || item == net.minecraft.world.item.Items.PUMPKIN
            || item == net.minecraft.world.item.Items.PUMPKIN_SEEDS
            || item == net.minecraft.world.item.Items.MELON_SEEDS
            || item == net.minecraft.world.item.Items.TORCHFLOWER_SEEDS
            || item == net.minecraft.world.item.Items.PITCHER_POD;
    }

    /**
     * فحص هل العنصر بذرة زراعية ينبغي الاحتفاظ بحزمة واحدة منها (64 حبة) لإعادة الزراعة
     */
    private boolean isPlantedSeed(net.minecraft.world.item.Item item) {
        return item == net.minecraft.world.item.Items.WHEAT_SEEDS
            || item == net.minecraft.world.item.Items.BEETROOT_SEEDS
            || item == net.minecraft.world.item.Items.CARROT
            || item == net.minecraft.world.item.Items.POTATO
            || item == net.minecraft.world.item.Items.NETHER_WART;
    }

    private static boolean isOtherSeedMiscItem(Item item) {
        return item == Items.PUMPKIN_SEEDS
            || item == Items.MELON_SEEDS
            || item == Items.TORCHFLOWER_SEEDS
            || item == Items.PITCHER_POD;
    }

    private static FarmCrop getCropForOutputItem(Item item) {
        if (item == null) return null;
        if (item == Items.WHEAT) return FarmCrop.WHEAT;
        if (item == Items.BEETROOT) return FarmCrop.BEETROOT;
        if (item == Items.CARROT) return FarmCrop.CARROT;
        if (item == Items.POTATO || item == Items.POISONOUS_POTATO) return FarmCrop.POTATO;
        if (item == Items.NETHER_WART) return FarmCrop.NETHER_WART;
        return null;
    }

    private static FarmCrop getCropForDistinctSeedItem(Item item) {
        if (item == null) return null;
        if (item == Items.WHEAT_SEEDS) return FarmCrop.WHEAT;
        if (item == Items.BEETROOT_SEEDS) return FarmCrop.BEETROOT;
        return null;
    }

    private void handlePostDepositFullChests(
            Minecraft client,
            LocalPlayer player,
            ClientLevel level,
            List<FarmArea> activeAreas) {
        clearOverflowFullCrops();
        for (FarmCrop c : attemptedOutputChestsCrops) {
            if (hasCropOutputSurplus(player, c)) {
                fullOutputChestCrops.add(c);
                sendChatMessage(client, "§6[Bot] جميع صناديق محصول §e" + c.getArabicName() + "§6 ممتلئة أو متعذر فتحها!");
            }
        }
        for (FarmCrop c : attemptedSeedChestsCrops) {
            if (hasCropSeedSurplus(player, c)) {
                fullSeedChestCrops.add(c);
                sendChatMessage(client, "§6[Bot] جميع صناديق بذور §e" + c.getArabicName() + "§6 ممتلئة أو متعذر فتحها!");
            }
        }

        if (hasOverflowFullCrops() && hasAnyCompostableSurplus(player)) {
            BlockPos composter = findNearbyComposter(player, level, 36);
            if (composter != null) {
                sendChatMessage(client, "§e[Bot] لم يجد مساحة في الصناديق المخصصة! يتوجه الآن إلى أقرب كومبوستر لتحويل الفائض إلى سماد...");
                targetComposterPos = composter;
                currentStage = FarmStage.COMPOST;
                compostTimeoutTicks = 0;
                resetNavPath();
                visitedDepositChests.clear();
                activeDepositPlan = null;
                attemptedOutputChestsCrops.clear();
                attemptedSeedChestsCrops.clear();
                targetDepositArea = null;
                targetDepositChestPos = null;
                depositTimeoutTicks = 0;
                cooldownTicks = 15;
                return;
            }
            sendChatMessage(client, "§c[Bot] الصناديق المخصصة ممتلئة ولكن لم يتم العثور على أي كومبوستر قريب في المحيط (نطاق 36 بلوكة)!");
        }

        if (hasOverflowFullCrops()) {
            for (FarmArea area : activeAreas) {
                Set<FarmCrop> areaCrops = getCropsInArea(level, area);
                for (FarmCrop c : areaCrops) {
                    boolean outputAffected = !area.getCropChests().isEmpty() && fullOutputChestCrops.contains(c);
                    boolean seedAffected = fullSeedChestCrops.contains(c)
                        && (area.hasSeparateSeedChestsForCrop(c) || !area.getCropChests().isEmpty());
                    if (outputAffected || seedAffected) {
                        fullChestPausedFarms.add(area.getName());
                        sendChatMessage(client, "§c[Bot] تم إيقاف العمل في مزرعة §e" + area.getName() + "§c (" + c.getArabicName() + ") مؤقتاً لامتلاء الصناديق وعدم توفر أداة سماد لتفريغ الفائض.");
                        break;
                    }
                }
            }
            clearOverflowFullCrops();

            if (fullChestPausedFarms.containsAll(activeAreas.stream().map(FarmArea::getName).toList())) {
                sendChatMessage(client, "§c[Bot] تم إيقاف جميع المزارع مؤقتاً لامتلاء صناديقها وعدم توفر أداة سماد! إيقاف البوت لحين إفراغ الصناديق.");
                stop(client);
                return;
            }
        }

        clearOverflowFullCrops();
        visitedDepositChests.clear();
        activeDepositPlan = null;
        attemptedOutputChestsCrops.clear();
        attemptedSeedChestsCrops.clear();
        targetDepositArea = null;
        targetDepositChestPos = null;
        depositTimeoutTicks = 0;
        currentStage = FarmStage.HARVEST;
        cooldownTicks = 20;
        sendChatMessage(client, "§a[Bot] اكتمل تفريغ كافة المحاصيل والبذور في الصناديق! استئناف دورة الزراعة...");
    }

    /**
     * جمع كافة أنواع المحاصيل المزروعة أو المخصصة في مزرعة معينة.
     */
    private Set<FarmCrop> getCropsInArea(ClientLevel level, FarmArea area) {
        Set<FarmCrop> crops = EnumSet.noneOf(FarmCrop.class);
        if (area == null) return crops;

        if (area.getTargetCrop() != null) {
            crops.add(area.getTargetCrop());
            return crops;
        }

        for (FarmCrop remembered : area.getRememberedCrops().values()) {
            if (remembered != null) {
                crops.add(remembered);
            }
        }

        if (level != null) {
            BlockPos min = area.getMinPos();
            BlockPos max = area.getMaxPos();
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int x = min.getX(); x <= max.getX(); x++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        FarmCrop crop = FarmCrop.fromBlock(level.getBlockState(new BlockPos(x, y, z)).getBlock());
                        if (crop != null) {
                            crops.add(crop);
                        }
                    }
                }
            }
        }
        return crops;
    }

    /**
     * جمع كل المحاصيل التي يعمل عليها البوت الآن في المزارع المفعلة.
     */
    private Set<FarmCrop> getActiveWorkingCrops(ClientLevel level, List<FarmArea> activeAreas) {
        Set<FarmCrop> working = EnumSet.noneOf(FarmCrop.class);
        if (activeAreas == null) return working;
        for (FarmArea area : activeAreas) {
            if (fullChestPausedFarms.contains(area.getName())) continue;
            working.addAll(getCropsInArea(level, area));
        }
        return working;
    }

    private DepositPlan buildDepositPlan(
            BlockPos chestPos,
            List<FarmArea> activeAreas,
            ClientLevel level,
            Set<BlockPos> excludedChests) {
        if (chestPos == null) {
            return new DepositPlan(
                EnumSet.noneOf(FarmCrop.class),
                EnumSet.noneOf(FarmCrop.class),
                EnumSet.noneOf(FarmCrop.class),
                EnumSet.noneOf(FarmCrop.class),
                false,
                false
            );
        }

        Set<FarmCrop> targetOutputCrops = EnumSet.noneOf(FarmCrop.class);
        Set<FarmCrop> targetSeedCrops = EnumSet.noneOf(FarmCrop.class);
        boolean actsAsCropChest = false;
        boolean actsAsSeedChest = false;
        boolean allowUnmappedOutputs = false;
        boolean allowUnmappedSeeds = false;

        Set<FarmCrop> outputsWithOtherUnvisitedChests = EnumSet.noneOf(FarmCrop.class);
        Set<FarmCrop> seedsWithOtherUnvisitedChests = EnumSet.noneOf(FarmCrop.class);
        Set<FarmCrop> cropsWithDedicatedSeedChests = EnumSet.noneOf(FarmCrop.class);

        if (activeAreas != null) {
            for (FarmArea area : activeAreas) {
                if (fullChestPausedFarms.contains(area.getName())) continue;
                Set<FarmCrop> areaCrops = getCropsInArea(level, area);
                boolean hasDedicatedSeedChests = !area.getSeedChests().isEmpty();
                boolean chestInCropList = FarmAreaManager.containsStorageContainer(level, area.getCropChests(), chestPos);
                boolean chestInSeedList = FarmAreaManager.containsStorageContainer(level, area.getSeedChests(), chestPos);

                if (chestInCropList) {
                    actsAsCropChest = true;
                    targetOutputCrops.addAll(areaCrops);
                    if (!hasDedicatedSeedChests) {
                        for (FarmCrop c : areaCrops) {
                            if (c.hasDistinctSeedItem()) {
                                targetSeedCrops.add(c);
                            }
                        }
                    }
                    if (area.getTargetCrop() == null) {
                        allowUnmappedOutputs = true;
                        if (!hasDedicatedSeedChests) {
                            allowUnmappedSeeds = true;
                        }
                    }
                }

                if (chestInSeedList) {
                    actsAsSeedChest = true;
                    for (FarmCrop c : areaCrops) {
                        if (c.hasDistinctSeedItem()) {
                            targetSeedCrops.add(c);
                        }
                    }
                    if (area.getTargetCrop() == null) {
                        allowUnmappedSeeds = true;
                    }
                }

                boolean hasOtherUnvisitedCropChest = area.getCropChests().stream()
                    .anyMatch(c -> !FarmAreaManager.isSameStorageContainer(level, c, chestPos)
                        && !isChestVisited(level, excludedChests, c));
                if (hasOtherUnvisitedCropChest) {
                    outputsWithOtherUnvisitedChests.addAll(areaCrops);
                    if (!hasDedicatedSeedChests) {
                        for (FarmCrop c : areaCrops) {
                            if (c.hasDistinctSeedItem()) {
                                seedsWithOtherUnvisitedChests.add(c);
                            }
                        }
                    }
                }

                if (hasDedicatedSeedChests) {
                    for (FarmCrop c : areaCrops) {
                        if (c.hasDistinctSeedItem()) {
                            cropsWithDedicatedSeedChests.add(c);
                        }
                    }
                    boolean hasOtherUnvisitedSeedChest = area.getSeedChests().stream()
                        .anyMatch(c -> !FarmAreaManager.isSameStorageContainer(level, c, chestPos)
                            && !isChestVisited(level, excludedChests, c));
                    if (hasOtherUnvisitedSeedChest) {
                        for (FarmCrop c : areaCrops) {
                            if (c.hasDistinctSeedItem()) {
                                seedsWithOtherUnvisitedChests.add(c);
                            }
                        }
                    }
                }
            }
        }

        // إضافة المحاصيل العاملة التي لا تملك صندوقاً آخراً مستقلاً في هذه الجولة، مع احترام فصل البذور عن المحصول
        Set<FarmCrop> workingCrops = getActiveWorkingCrops(level, activeAreas);
        for (FarmCrop workingCrop : workingCrops) {
            if (actsAsCropChest && !outputsWithOtherUnvisitedChests.contains(workingCrop)) {
                targetOutputCrops.add(workingCrop);
            }
            if (workingCrop.hasDistinctSeedItem() && !seedsWithOtherUnvisitedChests.contains(workingCrop)) {
                if (actsAsSeedChest || (actsAsCropChest && !cropsWithDedicatedSeedChests.contains(workingCrop))) {
                    targetSeedCrops.add(workingCrop);
                }
            }
        }

        Set<FarmCrop> excludedSeeds = EnumSet.copyOf(seedsWithOtherUnvisitedChests);
        if (!actsAsSeedChest) {
            excludedSeeds.addAll(cropsWithDedicatedSeedChests);
        }

        return new DepositPlan(
            targetOutputCrops,
            targetSeedCrops,
            outputsWithOtherUnvisitedChests,
            excludedSeeds,
            allowUnmappedOutputs,
            allowUnmappedSeeds
        );
    }

    private boolean shouldDepositItemWithPlan(Item item, DepositPlan plan) {
        FarmCrop distinctSeedCrop = getCropForDistinctSeedItem(item);
        if (distinctSeedCrop != null) {
            if (plan.targetSeedCrops().contains(distinctSeedCrop)) {
                return true;
            }
            return plan.allowUnmappedSeeds() && !plan.excludedSeedCropsWithOtherChests().contains(distinctSeedCrop);
        }

        FarmCrop outputCrop = getCropForOutputItem(item);
        if (outputCrop != null) {
            if (plan.targetOutputCrops().contains(outputCrop)) {
                return true;
            }
            return plan.allowUnmappedOutputs() && !plan.excludedOutputCropsWithOtherChests().contains(outputCrop);
        }

        if (isOtherSeedMiscItem(item)) {
            return plan.allowUnmappedSeeds();
        }
        return plan.allowUnmappedOutputs();
    }

    /**
     * اختيار خانات البذور المحجوزة للاحتفاظ بـ 64 بذرة فقط دون ترك ستاكات متناثرة.
     */
    private Set<Integer> findSeedReserveSlotIds(LocalPlayer player, AbstractContainerMenu menu) {
        Set<Integer> reserved = new HashSet<>();
        Map<Item, List<Integer>> slotsBySeedItem = new HashMap<>();

        for (int slotId = 0; slotId < menu.slots.size(); slotId++) {
            var slot = menu.getSlot(slotId);
            if (slot.container != player.getInventory()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !isPlantedSeed(stack.getItem())) {
                continue;
            }
            slotsBySeedItem.computeIfAbsent(stack.getItem(), k -> new ArrayList<>()).add(slotId);
        }

        for (List<Integer> slotIds : slotsBySeedItem.values()) {
            int fullStackSlot = -1;
            for (int i = slotIds.size() - 1; i >= 0; i--) {
                int slotId = slotIds.get(i);
                ItemStack stack = menu.getSlot(slotId).getItem();
                if (stack.getCount() >= stack.getMaxStackSize()) {
                    fullStackSlot = slotId;
                    break;
                }
            }
            if (fullStackSlot != -1) {
                reserved.add(fullStackSlot);
                continue;
            }

            slotIds.sort(Comparator
                .<Integer>comparingInt(id -> menu.getSlot(id).getItem().getCount())
                .reversed()
                .thenComparing(Comparator.<Integer>naturalOrder().reversed()));

            int kept = 0;
            for (int slotId : slotIds) {
                int count = menu.getSlot(slotId).getItem().getCount();
                if (kept == 0 || kept + count <= 64) {
                    reserved.add(slotId);
                    kept += count;
                }
            }
        }
        return reserved;
    }

    /**
     * تفريغ المحاصيل الفائضة في الصندوق المفتوح لجميع المحاصيل العاملة المسموحة في هذا الصندوق
     * مع الاحتفاظ بـ 64 حبة فقط من البذور لإعادة الزراعة.
     */
    private int depositSurplusIntoOpenChest(LocalPlayer player, MultiPlayerGameMode gameMode, DepositPlan plan) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || plan == null) return 0;

        Set<Integer> reservedSeedSlots = findSeedReserveSlotIds(player, menu);
        int totalDeposited = 0;

        for (int slotId = 0; slotId < menu.slots.size(); slotId++) {
            var slot = menu.getSlot(slotId);
            if (slot.container != player.getInventory()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();
            if (!isFarmDepositItem(item)) {
                continue; // ليس محصولاً أو غرضاً زراعياً
            }

            if (!shouldDepositItemWithPlan(item, plan)) {
                continue; // ينتمي لصندوق مخصص آخر لم يُزر بعد في هذه الجولة
            }

            if (reservedSeedSlots.contains(slotId)) {
                continue; // الاحتفاظ بـ 64 بذرة لإعادة الزراعة
            }

            // نقل الستاك بالكامل إلى الصندوق عبر Shift-Click (Quick Move)
            gameMode.handleContainerInput(menu.containerId, slotId, 0, ContainerInput.QUICK_MOVE, player);
            totalDeposited++;
        }

        return totalDeposited;
    }

    /**
     * البحث عن أقرب كومبوستر (Composter) في المحيط ضمن نصف القطر المحدد
     */
    private BlockPos findNearbyComposter(LocalPlayer player, ClientLevel level, int radius) {
        BlockPos playerPos = player.blockPosition();
        BlockPos bestPos = null;
        double bestDistSq = Double.MAX_VALUE;

        int r = Math.min(radius, 48);
        for (int y = -4; y <= 5; y++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(Blocks.COMPOSTER)) {
                        double d = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                        if (d < bestDistSq) {
                            bestDistSq = d;
                            bestPos = pos;
                        }
                    }
                }
            }
        }
        return bestPos;
    }

    /**
     * البحث عن خانة تحتوي على عناصر أو بذور فائضة تنتمي لمحاصيل أو بذور امتلأت صناديقها
     * بشرط أن تكون قابلة للتحويل لسماد، مع حماية 64 حبة للبذور القابلة للزراعة
     */
    private int findCompostableSurplusSlot(LocalPlayer player) {
        if (player == null || !hasOverflowFullCrops()) return -1;
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (item == Items.BAKED_POTATO || BotFoodManager.getInstance().isAllowed(stack)) continue; // حماية طعام البوت دائماً
            if (!ComposterBlock.COMPOSTABLES.containsKey(item)) continue; // عنصر يقبله الكومبوستر

            // التمييز بين امتلاء صناديق البذور وامتلاء صناديق المحصول
            FarmCrop distinctSeedCrop = getCropForDistinctSeedItem(item);
            if (distinctSeedCrop != null) {
                if (!fullSeedChestCrops.contains(distinctSeedCrop)) {
                    continue;
                }
            } else {
                FarmCrop outputCrop = getCropForOutputItem(item);
                if (outputCrop == null || !fullOutputChestCrops.contains(outputCrop)) {
                    continue;
                }
            }

            // إذا كان المحصول بذرة أو يزرع في الأرض، نحتفظ بـ 64 حبة على الأقل
            if (isPlantedSeed(item)) {
                int totalCount = countItemInInventory(player, item);
                if (totalCount <= 64) {
                    continue; // حماية الـ 64 بذرة لإعادة الزراعة
                }
            }

            return i;
        }
        return -1;
    }

    private boolean hasAnyCompostableSurplus(LocalPlayer player) {
        return findCompostableSurplusSlot(player) != -1;
    }

    /**
     * وضع العنصر الفائض في يد اللاعب للتعامل مع الكومبوستر
     */
    private void ensureCompostItemInHand(LocalPlayer player, MultiPlayerGameMode gameMode, int invSlot) {
        if (invSlot >= 0 && invSlot < 9) {
            selectHotbarSlot(player, invSlot);
        } else if (invSlot >= 9 && invSlot < 36) {
            int containerId = player.inventoryMenu.containerId;
            int hotbarIndex = SEED_HOTBAR_SLOT;
            gameMode.handleContainerInput(containerId, invSlot, hotbarIndex, ContainerInput.SWAP, player);
            selectHotbarSlot(player, hotbarIndex);
        }
    }

    /**
     * البحث عن أي سماد (Bone Meal) ملقى على الأرض بجوار الكومبوستر لجمعه في النهاية
     */
    private ItemEntity findNearbyDroppedBoneMeal(LocalPlayer player, ClientLevel level, BlockPos composterPos, double radius) {
        if (composterPos == null || level == null) return null;
        AABB box = new AABB(
            composterPos.getX() - radius, composterPos.getY() - 1.0, composterPos.getZ() - radius,
            composterPos.getX() + radius + 1.0, composterPos.getY() + 3.0, composterPos.getZ() + radius + 1.0
        );
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box);
        ItemEntity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (ItemEntity item : items) {
            if (item.isAlive() && item.getItem().is(Items.BONE_MEAL)) {
                double dist = player.distanceToSqr(item);
                if (dist < bestDistSq) {
                    bestDistSq = dist;
                    best = item;
                }
            }
        }
        return best;
    }

    private void approachDroppedBoneMeal(Minecraft client, LocalPlayer player, ClientLevel level, ItemEntity droppedBoneMeal) {
        if (droppedBoneMeal == null) return;
        trackedGoalPos = droppedBoneMeal.blockPosition();
        BotNavigator.NavResult navRes = farmNavigator.navigateTo(
            client,
            player,
            level,
            client.gameMode,
            droppedBoneMeal.position(),
            false,
            0.25,
            isEating,
            ROTATION_SPEED,
            true,
            1500,
            FarmPathfinder.PathOptions.FARM_DEFAULT,
            true,
            true,
            null
        );
        isDriving = (navRes == BotNavigator.NavResult.MOVING);
    }

    /**
     * معالجة مرحلة الذهاب إلى الكومبوستر ووضع البذور/العناصر الفائضة لإنتاج السماد وجمعه في النهاية
     */
    private void handleCompostStage(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode) {
        // فحص هل تم جمع ستاك كامل (64) من السماد للذهاب لتفريغه في صندوق السماد
        if (countItemInInventory(player, Items.BONE_MEAL) >= 64 && FarmAreaManager.getInstance().getFertilizerChestPos() != null) {
            // التحقق من جمع أي سماد متبق بجوار الكومبوستر أولاً قبل المغادرة
            ItemEntity droppedBoneMeal = findNearbyDroppedBoneMeal(player, level, targetComposterPos, 5.0);
            if (droppedBoneMeal != null && collectBoneMealTicks < 60) {
                collectBoneMealTicks++;
                smoothLookAt(player, droppedBoneMeal.getX(), droppedBoneMeal.getY() + 0.2, droppedBoneMeal.getZ(), ROTATION_SPEED);
                approachDroppedBoneMeal(client, player, level, droppedBoneMeal);
                return;
            }
            collectBoneMealTicks = 0;
            sendChatMessage(client, "§e[Bot] تم جمع ستاك كامل (64) من السماد! يتوجه البوت الآن لتفريغه في صندوق السماد...");
            currentStage = FarmStage.DEPOSIT_FERTILIZER;
            fertilizerTimeoutTicks = 0;
            resetNavPath();
            if (isDriving) {
                releaseControls(client);
                isDriving = false;
            }
            return;
        }

        if (targetComposterPos == null) {
            targetComposterPos = findNearbyComposter(player, level, 36);
            if (targetComposterPos == null) {
                sendChatMessage(client, "§c[Bot] تعذر العثور على أي كومبوستر في المحيط (نطاق 36 بلوكة)!");
                clearOverflowFullCrops();
                currentStage = FarmStage.HARVEST;
                return;
            }
        }

        BlockState compState = level.getBlockState(targetComposterPos);
        if (!compState.is(Blocks.COMPOSTER)) {
            targetComposterPos = null;
            return;
        }

        double compCenterX = targetComposterPos.getX() + 0.5;
        double compCenterY = targetComposterPos.getY() + 0.5;
        double compCenterZ = targetComposterPos.getZ() + 0.5;
        double distSq = player.distanceToSqr(compCenterX, compCenterY, compCenterZ);

        if (distSq > 2.6 * 2.6) {
            navigateTowards(client, player, level, targetComposterPos, compCenterX, compCenterY, compCenterZ, 2.3);
            compostTimeoutTicks++;
            if (compostTimeoutTicks > 400) {
                sendChatMessage(client, "§c[Bot] تعذر الوصول للكومبوستر بعد 20 ثانية! إلغاء واستئناف العمل...");
                clearOverflowFullCrops();
                targetComposterPos = null;
                currentStage = FarmStage.HARVEST;
                compostTimeoutTicks = 0;
            }
            return;
        }

        resetNavPath();
        if (isDriving) {
            releaseControls(client);
            isDriving = false;
        }

        smoothLookAt(player, compCenterX, compCenterY + 0.5, compCenterZ, ROTATION_SPEED);

        if (compostActionCooldown > 0) {
            compostActionCooldown--;
            return;
        }

        int compLevel = compState.getValue(ComposterBlock.LEVEL);

        // إذا كان الكومبوستر مكتملاً (المستوى 8) وجاهزاً لحصاد السماد
        if (compLevel >= 8) {
            BlockHitResult hit = new BlockHitResult(
                new Vec3(compCenterX, compCenterY + 0.8, compCenterZ),
                Direction.UP,
                targetComposterPos,
                false
            );
            gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            compostActionCooldown = 6;
            return;
        }

        // فحص هل توجد عناصر أو بذور فائضة لإضافتها في الكومبوستر (< 8)
        int slot = findCompostableSurplusSlot(player);
        if (slot == -1) {
            // لم يتبق أي فائض من المحاصيل لوضعه في الكومبوستر.
            // 1. مرحلة النهاية: استخراج السماد إن كان الكومبوستر مكتملاً (المستوى 8)
            if (compLevel >= 8) {
                BlockHitResult hit = new BlockHitResult(
                    new Vec3(compCenterX, compCenterY + 0.8, compCenterZ),
                    Direction.UP,
                    targetComposterPos,
                    false
                );
                gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                player.swing(InteractionHand.MAIN_HAND);
                compostActionCooldown = 6;
                return;
            }

            // 2. جمع كافة السماد (Bone Meal) الملقى على الأرض أو فوق الكومبوستر في النهاية
            ItemEntity droppedBoneMeal = findNearbyDroppedBoneMeal(player, level, targetComposterPos, 6.0);
            if (droppedBoneMeal != null && collectBoneMealTicks < 80) {
                collectBoneMealTicks++;
                double itemDistSq = player.distanceToSqr(droppedBoneMeal);
                smoothLookAt(player, droppedBoneMeal.getX(), droppedBoneMeal.getY() + 0.2, droppedBoneMeal.getZ(), ROTATION_SPEED);
                if (itemDistSq > 0.4 * 0.4) {
                    approachDroppedBoneMeal(client, player, level, droppedBoneMeal);
                } else {
                    resetNavPath();
                    if (isDriving) {
                        releaseControls(client);
                        isDriving = false;
                    }
                }
                return;
            }

            // 3. اكتملت العملية بالكامل وتم جمع كل السماد في النهاية!
            collectBoneMealTicks = 0;
            if (isDriving) {
                releaseControls(client);
                isDriving = false;
            }

            sendChatMessage(client, "§a[Bot] تم تحويل كامل الفائض وجمع السماد من الكومبوستر بنجاح! (" + countItemInInventory(player, Items.BONE_MEAL) + " حبة سماد)");
            clearOverflowFullCrops();
            targetComposterPos = null;
            compostTimeoutTicks = 0;
            cooldownTicks = 20;

            // فحص هل تم جمع ستاك سماد كامل (64)
            if (countItemInInventory(player, Items.BONE_MEAL) >= 64 && FarmAreaManager.getInstance().getFertilizerChestPos() != null) {
                sendChatMessage(client, "§e[Bot] أصبح لديك ستاك كامل (64) من السماد! يتوجه الآن لتفريغه في صندوق السماد...");
                currentStage = FarmStage.DEPOSIT_FERTILIZER;
                fertilizerTimeoutTicks = 0;
            } else {
                currentStage = FarmStage.HARVEST;
            }
            return;
        }

        // تجهيز العنصر الفائض في اليد اليمنى
        ensureCompostItemInHand(player, gameMode, slot);

        // وضع العنصر في الكومبوستر
        BlockHitResult hit = new BlockHitResult(
            new Vec3(compCenterX, compCenterY + 0.8, compCenterZ),
            Direction.UP,
            targetComposterPos,
            false
        );
        gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
        compostActionCooldown = 4;
    }

    /**
     * تفريغ ستاكات السماد (Bone Meal) في صندوق السماد المفتوح
     */
    private int depositBoneMealIntoOpenChest(LocalPlayer player, MultiPlayerGameMode gameMode) {
        var menu = player.containerMenu;
        if (menu == null) return 0;

        int count = 0;
        for (int slotId = 0; slotId < menu.slots.size(); slotId++) {
            var slot = menu.getSlot(slotId);
            if (slot.container == player.getInventory()) {
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty() && stack.is(Items.BONE_MEAL)) {
                    gameMode.handleContainerInput(menu.containerId, slotId, 0, ContainerInput.QUICK_MOVE, player);
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * معالجة الذهاب لصندوق السماد وتفريغ ستاك السماد (Bone Meal) فيه
     */
    private void handleDepositFertilizerStage(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode) {
        BlockPos chestPos = FarmAreaManager.getInstance().getFertilizerChestPos();
        if (chestPos == null) {
            currentStage = FarmStage.HARVEST;
            return;
        }

        // إذا كانت الحاوية مفتوحة بالفعل
        if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            if (isDriving) {
                releaseControls(client);
                isDriving = false;
            }

            int depositedStacks = depositBoneMealIntoOpenChest(player, gameMode);
            player.closeContainer();

            if (depositedStacks > 0) {
                sendChatMessage(client, "§a[Bot] تم بنجاح تفريغ ستاك السماد (Bone Meal) في صندوق السماد المخصص! (" + depositedStacks + " حزمة)");
            } else {
                sendChatMessage(client, "§e[Bot] تم فحص صندوق السماد؛ الصندوق ممتلئ أو لا يوجد سماد للتفريغ.");
            }

            fertilizerTimeoutTicks = 0;
            cooldownTicks = 20;

            // إذا كان لا يزال هناك فائض محاصيل بحاجة للتحويل لسماد نعود للكومبوستر
            if (hasOverflowFullCrops() && hasAnyCompostableSurplus(player)) {
                currentStage = FarmStage.COMPOST;
                compostTimeoutTicks = 0;
            } else {
                clearOverflowFullCrops();
                currentStage = FarmStage.HARVEST;
            }
            return;
        }

        fertilizerTimeoutTicks++;
        boolean blockOk = navigateAndOpenContainer(client, player, level, gameMode, chestPos);
        if (!blockOk || fertilizerTimeoutTicks > 400) {
            sendChatMessage(client, !blockOk
                ? "§c[Bot] صندوق السماد المحدد غير موجود في مكانه! العودة للزراعة..."
                : "§c[Bot] تعذر فتح صندوق السماد بعد 20 ثانية! العودة للزراعة...");
            currentStage = FarmStage.HARVEST;
            fertilizerTimeoutTicks = 0;
        }
    }

    private boolean isInventoryFull(LocalPlayer player) {
        Inventory inv = player.getInventory();
        // فحص خانات الحقيبة من 9 إلى 35
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private DepositStop findNextDepositStop(
            LocalPlayer player,
            ClientLevel level,
            List<FarmArea> areas,
            Set<BlockPos> excludedChests) {
        if (areas == null || areas.isEmpty() || player == null) return null;

        // 1. فحص صناديق المزرعة الحالية أولاً بالترتيب (المحصول ثم البذور)
        if (currentAreaIndex < areas.size()) {
            DepositStop currentStop = findFirstEligibleStopInArea(player, level, areas.get(currentAreaIndex), areas, excludedChests);
            if (currentStop != null) {
                return currentStop;
            }
        }

        // 2. فحص صناديق بقية المزارع المفعلة
        for (FarmArea area : areas) {
            DepositStop stop = findFirstEligibleStopInArea(player, level, area, areas, excludedChests);
            if (stop != null) {
                return stop;
            }
        }

        return null;
    }

    private DepositStop findFirstEligibleStopInArea(
            LocalPlayer player,
            ClientLevel level,
            FarmArea area,
            List<FarmArea> activeAreas,
            Set<BlockPos> excludedChests) {
        if (area == null || fullChestPausedFarms.contains(area.getName())) {
            return null;
        }
        for (BlockPos cropChest : area.getCropChests()) {
            if (isChestVisited(level, excludedChests, cropChest)) continue;
            DepositPlan plan = buildDepositPlan(cropChest, activeAreas, level, excludedChests);
            if (doesPlanMatchInventorySurplus(player, plan)) {
                return new DepositStop(area, cropChest, FarmArea.ChestRole.CROP, plan);
            }
        }
        for (BlockPos seedChest : area.getSeedChests()) {
            if (isChestVisited(level, excludedChests, seedChest)) continue;
            DepositPlan plan = buildDepositPlan(seedChest, activeAreas, level, excludedChests);
            if (doesPlanMatchInventorySurplus(player, plan)) {
                return new DepositStop(area, seedChest, FarmArea.ChestRole.SEED, plan);
            }
        }
        return null;
    }

    private boolean doesPlanMatchInventorySurplus(LocalPlayer player, DepositPlan plan) {
        if (player == null || plan == null) return false;
        for (FarmCrop crop : FarmCrop.values()) {
            boolean acceptsOutput = plan.targetOutputCrops().contains(crop)
                || (plan.allowUnmappedOutputs() && !plan.excludedOutputCropsWithOtherChests().contains(crop));
            if (acceptsOutput && hasCropOutputSurplus(player, crop)) {
                return true;
            }
            boolean acceptsSeed = crop.hasDistinctSeedItem() && (plan.targetSeedCrops().contains(crop)
                || (plan.allowUnmappedSeeds() && !plan.excludedSeedCropsWithOtherChests().contains(crop)));
            if (acceptsSeed && hasCropSeedSurplus(player, crop)) {
                return true;
            }
        }
        if (plan.allowUnmappedOutputs() || plan.allowUnmappedSeeds()) {
            Inventory inv = player.getInventory();
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                Item item = stack.getItem();
                if (isFarmDepositItem(item)
                        && getCropForOutputItem(item) == null
                        && getCropForDistinctSeedItem(item) == null
                        && !isPlantedSeed(item)) {
                    boolean miscSeed = isOtherSeedMiscItem(item);
                    if ((miscSeed && plan.allowUnmappedSeeds()) || (!miscSeed && plan.allowUnmappedOutputs())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private record DepositPlan(
            Set<FarmCrop> targetOutputCrops,
            Set<FarmCrop> targetSeedCrops,
            Set<FarmCrop> excludedOutputCropsWithOtherChests,
            Set<FarmCrop> excludedSeedCropsWithOtherChests,
            boolean allowUnmappedOutputs,
            boolean allowUnmappedSeeds) {}

    private record DepositStop(
            FarmArea area,
            BlockPos chestPos,
            FarmArea.ChestRole role,
            DepositPlan plan) {}

    private static boolean hasCropOutputSurplus(LocalPlayer player, FarmCrop crop) {
        if (crop == null || player == null) return false;
        return switch (crop) {
            case WHEAT -> countItemInInventory(player, Items.WHEAT) > 0;
            case BEETROOT -> countItemInInventory(player, Items.BEETROOT) > 0;
            case POTATO -> countItemInInventory(player, Items.POTATO) > 64
                || countItemInInventory(player, Items.POISONOUS_POTATO) > 0;
            case CARROT -> countItemInInventory(player, Items.CARROT) > 64;
            case NETHER_WART -> countItemInInventory(player, Items.NETHER_WART) > 64;
        };
    }

    private static boolean hasCropSeedSurplus(LocalPlayer player, FarmCrop crop) {
        if (crop == null || player == null || !crop.hasDistinctSeedItem()) return false;
        return switch (crop) {
            case WHEAT -> countItemInInventory(player, Items.WHEAT_SEEDS) > 64;
            case BEETROOT -> countItemInInventory(player, Items.BEETROOT_SEEDS) > 64;
            default -> false;
        };
    }

    private static int countItemInInventory(LocalPlayer player, net.minecraft.world.item.Item item) {
        if (item == null) return 0;
        Inventory inv = player.getInventory();
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void sendChatMessage(Minecraft client, String message) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal(message));
        }
    }

    private void sendServerChatMessage(LocalPlayer player, String message) {
        if (player != null && player.connection != null && message != null && !message.isBlank()) {
            player.connection.sendChat(message);
        }
    }

    /**
     * معالجة المناورة والهروب التكتيكي عند رصد خطر في مساحة 5*5:
     * 1- رفع الدرع فوراً في اليد اليسرى والتصدي للمهاجم وقطع أي مهمة زراعية جارية مهما كان الوضع.
     * 2- تخطي المزرعة الحالية والانتقال للمزرعة التالية فوراً.
     * 3- المناورة والالتفاف حول المهاجم (Strafe & Backpedal) مع إبقاء الوجه والدرع دائماً قبال المهاجم.
     * 4- الخروج من نطاق الخطر (أكثر من 5.5 بلوكات) والتوجه للمزرعة الجديدة ثم استئناف العمل.
     */
    private void handleThreatEvadeAndEscape(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode, FightBotController.ThreatInfo threat) {
        // إذا زال التهديد المباشر (أشاح اللاعب بنظره عنه أو ابتعد): خفض الدرع واستئناف الزراعة فوراً
        if (threat == null) {
            noThreatTicks++;
            if (noThreatTicks >= 3) {
                isEvadingThreat = false;
                isRetreatingInFarm = false;
                isEmergencyRunningFarm = false;
                emergencyRunStartPosFarm = null;
                emergencyRunDirFarm = null;
                emergencyRunTicksFarm = 0;
                evadeTicks = 0;
                noThreatTicks = 0;
                FightBotController.getInstance().stopDefending(client);
                FightBotController.getInstance().releaseRetreatMovement(client);
                FightBotController.getInstance().releaseEmergencyRun(client);
                FightBotController.getInstance().ensureHoeInOffhand(client, player, gameMode);
                releaseControls(client);
                isDriving = false;
                List<FarmArea> areas = FarmAreaManager.getInstance().getActiveAreas();
                if (!areas.isEmpty() && currentAreaIndex < areas.size()) {
                    sendChatMessage(client, "§a[Bot] زال الخطر وانصرف التهديد! استئناف العمل بالفأس الزراعي في مزرعة §e" + areas.get(currentAreaIndex).getName() + "§a.");
                } else {
                    sendChatMessage(client, "§a[Bot] زال الخطر وانصرف التهديد! استئناف العمل بالفأس الزراعي.");
                }
                return;
            }
        } else {
            noThreatTicks = 0;
        }

        // قطع أي عملية تناول طعام جارية فوراً
        if (isEating) {
            client.options.keyUse.setDown(false);
            isEating = false;
            eatingTicks = 0;
            lastFoodLevel = -1;
        }

        // إلغاء تكسير أي بلوكة زراعية أو كولداون
        cooldownTicks = 0;

        // تفعيل ورفع الدرع والتصدي الفوري
        FightBotController.getInstance().startDefending(client, player, gameMode, threat);

        // تخطي المزرعة الحالية عند أول رصد للتهديد
        List<FarmArea> areas = FarmAreaManager.getInstance().getActiveAreas();
        if (!isEvadingThreat) {
            isEvadingThreat = true;
            evadeTicks = 0;
            resetNavPath();
            lockedItem = null;
            lastTargetPos = null;
            doingFinalVerification = false;
            finalVerificationTicks = 0;

            if (areas.size() > 1) {
                FarmArea skippedArea = areas.get(currentAreaIndex);
                currentAreaIndex = (currentAreaIndex + 1) % areas.size();
                FarmArea nextArea = areas.get(currentAreaIndex);
                currentStage = FarmStage.HARVEST;
                sendChatMessage(client, "§c[Bot - Defend] رصد خطر في مزرعة §e" + skippedArea.getName() + "§c! رفع الدرع فوراً والالتفاف لتفادي المهاجم والتوجه لمزرعة §a" + nextArea.getName() + "§c...");
            } else {
                sendChatMessage(client, "§c[Bot - Defend] رصد خطر مقترب! رفع الدرع فوراً والمناورة بالالتفاف حول المهاجم للخروج من نطاق الخطر...");
            }
        }

        // تحديد موقع التهديد
        Vec3 threatPos = threat != null ? threat.pos() : FightBotController.getInstance().getCurrentThreatPos();
        if (threatPos == null) {
            threatPos = player.position().add(player.getViewVector(1.0f).scale(2.0));
        }

        // توجيه وجه ورأس وجسد اللاعب والدرع دائماً نحو المهاجم قبال وجهه لصد كل الضربات
        FightBotController.getInstance().lookAt(player, threatPos.x, threatPos.y, threatPos.z);
        client.options.keyUse.setDown(true);

        // تحديد وجهة الهروب الآمنة (المزرعة الجديدة أو نقطة بعيدة عن الخصم)
        BlockPos escapeGoal = null;
        if (!areas.isEmpty() && currentAreaIndex < areas.size()) {
            escapeGoal = areas.get(currentAreaIndex).getCenterPos();
        }
        if (escapeGoal == null) {
            Vec3 away = player.position().subtract(threatPos).normalize();
            escapeGoal = BlockPos.containing(player.position().add(away.scale(7.0)));
        }

        double toGoalX = escapeGoal.getX() + 0.5 - player.getX();
        double toGoalZ = escapeGoal.getZ() + 0.5 - player.getZ();
        double goalDist = Math.sqrt(toGoalX * toGoalX + toGoalZ * toGoalZ);
        if (goalDist > 0.01) {
            toGoalX /= goalDist;
            toGoalZ /= goalDist;
        }

        double toThreatX = threatPos.x - player.getX();
        double toThreatZ = threatPos.z - player.getZ();
        double threatDist = Math.sqrt(toThreatX * toThreatX + toThreatZ * toThreatZ);

        // 1. إذا كان الخصم قريباً لمقدار بلوكة واحدة (<= 1.2): الجري في أطول مسار بعيداً عنه لـ 5 بلوكات ثم إعادة النظر للأولوية
        if (threatDist <= 1.2 && !isEmergencyRunningFarm) {
            isEmergencyRunningFarm = true;
            emergencyRunStartPosFarm = player.position();
            emergencyRunDirFarm = FightBotController.getInstance().findLongestPathAway(player, level, threatPos);
            emergencyRunTicksFarm = 0;
            isRetreatingInFarm = false;
            FightBotController.getInstance().releaseRetreatMovement(client);
        }

        if (isEmergencyRunningFarm) {
            double fled = emergencyRunStartPosFarm != null ? player.position().distanceTo(emergencyRunStartPosFarm) : 0;
            if (fled >= 5.0 || threatDist >= 5.0 || emergencyRunTicksFarm > 60) {
                // انتهاء الجري لـ 5 بلوكات: إعادة النظر للأولوية
                isEmergencyRunningFarm = false;
                emergencyRunStartPosFarm = null;
                emergencyRunDirFarm = null;
                FightBotController.getInstance().releaseEmergencyRun(client);
            } else {
                emergencyRunTicksFarm++;
                FightBotController.getInstance().applyEmergencyRun(client, player, emergencyRunDirFarm);
                isDriving = true;
                evadeTicks++;
                return;
            }
        }

        // 2. إذا اقترب الخصم لمسافة أقل من بلوكتين (< 2.0): الأولوية للتراجع الذكي وتفادي الجدران حتى يصنع مسافة 5 بلوكات
        if (threatDist < 2.0) {
            isRetreatingInFarm = true;
        } else if (threatDist >= 5.0) {
            isRetreatingInFarm = false;
        }

        if (isRetreatingInFarm) {
            Vec3 retreatDir = FightBotController.getInstance().calculateSmartRetreatDirection(player, level, threatPos);
            FightBotController.getInstance().applyRetreatMovement(client, player, retreatDir.x, retreatDir.z);
            isDriving = true;
            evadeTicks++;
            return;
        }

        // إذا نجح البوت في الخروج من مساحة الخطر (أكثر من 6.5 بلوكات) ومرت 15 نبضة:
        if (threatDist > 6.5 && evadeTicks > 15) {
            isEvadingThreat = false;
            isRetreatingInFarm = false;
            isEmergencyRunningFarm = false;
            emergencyRunStartPosFarm = null;
            emergencyRunDirFarm = null;
            emergencyRunTicksFarm = 0;
            evadeTicks = 0;
            noThreatTicks = 0;
            FightBotController.getInstance().stopDefending(client);
            FightBotController.getInstance().releaseRetreatMovement(client);
            FightBotController.getInstance().releaseEmergencyRun(client);
            FightBotController.getInstance().ensureHoeInOffhand(client, player, gameMode);
            releaseControls(client);
            isDriving = false;
            if (!areas.isEmpty() && currentAreaIndex < areas.size()) {
                sendChatMessage(client, "§a[Bot] تم الخروج من مساحة الخطر بنجاح! استئناف العمل بالفأس الزراعي في مزرعة §e" + areas.get(currentAreaIndex).getName() + "§a.");
            } else {
                sendChatMessage(client, "§a[Bot] تم الخروج من مساحة الخطر بنجاح! استئناف العمل بالفأس الزراعي.");
            }
            return;
        }

        // متجه الابتعاد عن الخصم
        double awayX = -toThreatX / (threatDist + 0.001);
        double awayZ = -toThreatZ / (threatDist + 0.001);

        // متجه الالتفاف المماسي حول الخصم (Tangential Circling)
        double perpX = -awayZ;
        double perpZ = awayX;
        if (perpX * toGoalX + perpZ * toGoalZ < 0) {
            perpX = -perpX;
            perpZ = -perpZ;
        }

        // دمج المتجهات لتأمين مسار مناورة يلتف حول الخصم ويتجه نحو المزرعة الجديدة
        double moveX = toGoalX * 0.4 + awayX * 0.6 + perpX * 0.5;
        double moveZ = toGoalZ * 0.4 + awayZ * 0.6 + perpZ * 0.5;

        float moveYaw = (float) (Math.atan2(moveZ, moveX) * 180.0 / Math.PI) - 90.0f;
        float diff = wrapDegrees(moveYaw - player.getYRot());

        // التحكم الذكي في أزرار الحركة (Strafe & Backpedal) ليبقى الدرع موجهاً للخصم قبال وجهه طوال المسير
        client.options.keyUp.setDown(diff > -67.5f && diff < 67.5f);
        client.options.keyDown.setDown(diff > 112.5f || diff < -112.5f);
        client.options.keyLeft.setDown(diff > -157.5f && diff < -22.5f);
        client.options.keyRight.setDown(diff > 22.5f && diff < 157.5f);
        isDriving = true;

        // القفز التلقائي عند مواجهة أي عائق أو درجة أثناء التراجع
        if (player.horizontalCollision && player.onGround()) {
            client.options.keyJump.setDown(true);
        } else {
            client.options.keyJump.setDown(false);
        }

        evadeTicks++;
    }

    private enum ActionType {
        HARVEST,
        TILL,
        PLANT
    }

    private record TargetAction(ActionType type, BlockPos pos, FarmArea area, FarmCrop cropToPlant) {}
}
