package com.helpermod.bot;

import com.helpermod.bot.food.BotFoodManager;
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
    private int stuckTicks = 0;
    private int hoeAlertTicks = 0;
    private int doorInteractCooldown = 0;

    // تتبع حالة فتح وعبور وإغلاق الأبواب وبوابات السياج (التوقف والالتفاف والإغلاق ثم المتابعة)
    private enum DoorHandlingState {
        NONE,
        STEPPING_THROUGH,
        TURNING_AND_CLOSING,
        CONFIRMING
    }

    private BlockPos activeDoorPos = null;
    private Vec3 activeDoorEntryPos = null;
    private DoorHandlingState doorState = DoorHandlingState.NONE;
    private int doorStateTicks = 0;
    private int doorConfirmTicks = 0;
    private BlockPos lastClosedDoorPos = null;

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

    // مسار التحرك الذكي لتفادي الأسوار والجدران
    private List<BlockPos> currentPath = null;
    private BlockPos currentGoal = null;
    private int pathRecalcCooldown = 0;

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

    // تتبع تفريغ المحاصيل في الصندوق
    private FarmArea targetDepositArea = null;
    private int depositTimeoutTicks = 0;
    private final Set<BlockPos> visitedDepositChests = new HashSet<>();
    private final Set<FarmCrop> lastDepositTargetCrops = EnumSet.noneOf(FarmCrop.class);
    private final Set<FarmCrop> lastDepositExcludedCrops = EnumSet.noneOf(FarmCrop.class);
    private boolean lastDepositAllowUnmappedFarmItems = false;

    // تتبع تحويل الفائض لسماد في الكومبوستر عند امتلاء الصناديق
    private final Set<FarmCrop> fullChestCrops = new HashSet<>();
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
        List<FarmArea> areas = FarmAreaManager.getInstance().getActiveAreas();
        if (areas.isEmpty()) {
            if (FarmAreaManager.getInstance().getAllAreas().isEmpty()) {
                sendChatMessage(client, "§c[Bot] لا توجد مناطق زراعية محددة! أضف منطقة أولاً باستخدام: /bot farm area pos1 ثم pos2 ثم create <اسم>");
            } else {
                sendChatMessage(client, "§c[Bot] جميع المزارع المسجلة حالياً غير مفعلة! فعّل مزرعة أو الكل عبر: §e/bot farm area enable <الاسم|all>");
            }
            return;
        }
        running = true;
        // إيقاف وضع الاتباع تلقائياً لمنع أي تعارض حركي
        if (BotFollowController.getInstance().isFollowing()) {
            BotFollowController.getInstance().stopFollowing(client);
        }
        fullChestPausedFarms.clear();
        visitedDepositChests.clear();
        lastDepositTargetCrops.clear();
        lastDepositExcludedCrops.clear();
        lastDepositAllowUnmappedFarmItems = false;
        depositCloseDelay = 0;
        withdrawCloseDelay = 0;
        isDriving = false;
        cooldownTicks = 0;
        stuckTicks = 0;
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
        currentPath = null;
        currentGoal = null;
        nightTicks = 0;
        bedAlertSent = false;
        targetDepositArea = null;
        depositTimeoutTicks = 0;
        targetWithdrawArea = null;
        targetWithdrawCrop = null;
        withdrawTimeoutTicks = 0;
        areasOutOfSeeds.clear();
        resetDoorState(client);
        waitingAlertTicks = 1100;
        lastPlayerPos = null;
        physicalStuckTicks = 0;
        doingFinalVerification = false;
        finalVerificationTicks = 0;
        // وضع الفأس الزراعي في اليد اليسرى فور تشغيل البوت
        if (client.player != null && client.gameMode != null) {
            FightBotController.getInstance().ensureHoeInOffhand(client, client.player, client.gameMode);
        }

        // فحص هل الوقت ليل عند بدء/العودة لوضع الزراعة للتفكير بالنوم فوراً
        if (client.level != null) {
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

    public void stop(Minecraft client) {
        if (!running) {
            sendChatMessage(client, "§e[Bot] المساعد الآلي متوقف بالفعل!");
            return;
        }
        running = false;
        currentPath = null;
        currentGoal = null;
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
        targetDepositArea = null;
        depositTimeoutTicks = 0;
        visitedDepositChests.clear();
        lastDepositTargetCrops.clear();
        lastDepositExcludedCrops.clear();
        lastDepositAllowUnmappedFarmItems = false;
        targetWithdrawArea = null;
        targetWithdrawCrop = null;
        withdrawTimeoutTicks = 0;
        areasOutOfSeeds.clear();
        fullChestCrops.clear();
        fullChestPausedFarms.clear();
        depositCloseDelay = 0;
        withdrawCloseDelay = 0;
        targetComposterPos = null;
        compostTimeoutTicks = 0;
        compostActionCooldown = 0;
        collectBoneMealTicks = 0;
        fertilizerTimeoutTicks = 0;
        resetDoorState(client);
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
        FightBotController.getInstance().stopDefending(client);
        FightBotController.getInstance().releaseEmergencyRun(client);
        if (isDriving) {
            releaseControls(client);
            isDriving = false;
        }
        sendChatMessage(client, "§c[Bot] تم إيقاف المساعد الآلي وإنهاء التحكم باللاعب بالكامل. (الحالة: متوقف)");
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
            return;
        }

        // أولاً وقبل أي مهمة أو كولداون أو عمل زراعي: فحص وضع الدفاع ورصد أي تهديد للصد الفوري والمناورة
        if (FightBotController.getInstance().isDefenseMode()) {
            FightBotController.ThreatInfo threat = FightBotController.getInstance().detectThreat(player, level);
            if (threat != null || isEvadingThreat) {
                handleThreatEvadeAndEscape(client, player, level, gameMode, threat);
                return;
            }
        }

        // التأكد دائماً من وجود الفأس الزراعي في اليد اليسرى (Offhand) طالما لا يوجد خطر دفاعي
        if (!FightBotController.getInstance().isDefending() && !isEvadingThreat) {
            FightBotController.getInstance().ensureHoeInOffhand(client, player, gameMode);
        }

        if (doorInteractCooldown > 0) {
            doorInteractCooldown--;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        if (hoeAlertTicks > 0) {
            hoeAlertTicks--;
        }
        if (hungryAlertCooldown > 0) {
            hungryAlertCooldown--;
        }

        // التعامل مع إغلاق الأبواب بعد العبور (التوقف التام والالتفاف وإغلاق الباب ثم مواصلة الطريق)
        if (handleDoorState(client, player, level, gameMode)) {
            return;
        }

        // مراقبة الليل وشروق الشمس بدقة (الظلام أو العواصف الرعدية)
        boolean isNightTime = level.isThundering() || (level.getSkyDarken() >= 4);
        if (isNightTime) {
            nightTicks++;
        } else {
            nightTicks = 0;
            bedAlertSent = false;
            // الاستيقاظ التلقائي فور شروق الشمس وانتهاء الليل
            if (currentStage == FarmStage.SLEEP) {
                if (player.isSleeping()) {
                    player.stopSleepInBed(true, true);
                }
                sendChatMessage(client, "§a[Bot] صباح الخير! أشرقت الشمس واستيقظ البوت، يستأنف الآن دورة العمل في المزارع...");
                currentStage = FarmStage.HARVEST;
                actionsInCurrentFarm = 0;
                consecutiveEmptyFarms = 0;
                unreachablePositions.clear();
                areasOutOfSeeds.clear();
                currentPath = null;
                currentGoal = null;
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
            player.getSleepingPos().ifPresent(pos -> {
                if (FarmAreaManager.getInstance().getBedPos() == null) {
                    FarmAreaManager.getInstance().setBedPos(pos);
                }
            });
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
        if (FarmAreaManager.getInstance().isSleepAtNight() && isNightTime && nightTicks >= 400) {
            sendChatMessage(client, "§e[Bot] مر على الليل 20 ثانية! يتوجه البوت فوراً للسرير للنوم حتى تطلع الشمس...");
            currentStage = FarmStage.SLEEP;
            currentPath = null;
            currentGoal = null;
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
                currentPath = null;
                currentGoal = null;
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
                                    visitedDepositChests.clear();
                                    FarmArea depositFarm = findAreaNeedingDeposit(player, level, activeAreas);
                                    if (depositFarm != null) {
                                        sendChatMessage(client, "§e[Bot] المخزون ممتلئ أثناء تجميع المحاصيل! يتوجه لتفريغ المحاصيل بدءاً بصندوق مزرعة §a" + depositFarm.getName() + "§e مع الاحتفاظ بـ 64 بذرة...");
                                        targetDepositArea = depositFarm;
                                        currentStage = FarmStage.DEPOSIT;
                                        currentPath = null;
                                        currentGoal = null;
                                        depositTimeoutTicks = 0;
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
                            BlockPos navTarget = getBestNavTargetForItem(level, player.blockPosition(), lockedItem);
                            navigateTowards(client, player, level, navTarget, lockedItem.getX(), lockedItem.getY(), lockedItem.getZ(), isItemAtFence ? 1.0 : 0.65);
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

                        // التوجه المباشر إذا كان العنصر قريباً وفي نفس الارتفاع ولا يقع في بلوكة سور صلبة
                        if (!isItemAtFence && hDistSq <= 3.5 * 3.5 && Math.abs(dy) <= 1.5) {
                            moveTo(client, player, level, lockedItem.getX(), lockedItem.getY(), lockedItem.getZ(), 0.5);
                            return;
                        }

                        // التنقل الذكي إلى أقرب بلوكة ملاصقة للسور أو للمحصول
                        BlockPos navTarget = getBestNavTargetForItem(level, player.blockPosition(), lockedItem);
                        navigateTowards(client, player, level, navTarget, lockedItem.getX(), lockedItem.getY(), lockedItem.getZ(), isItemAtFence ? 1.0 : 0.65);
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
                            if (currentArea.getChestPos() != null) {
                                sendChatMessage(client, "§e[Bot] نفذت بذور §a" + neededCrop.getArabicName() + "§e من الحقيبة! يتوجه البوت لجلب البذور من صندوق مزرعة §a" + currentArea.getName() + "§e...");
                                targetWithdrawArea = currentArea;
                                targetWithdrawCrop = neededCrop;
                                currentStage = FarmStage.WITHDRAW;
                                currentPath = null;
                                currentGoal = null;
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

                    // إذا كان خيار النوم مفعل ومر على الليل 20 ثانية (400 tick)، يتوجه البوت للنوم بعد انتهاء آخر مزرعة
                    if (FarmAreaManager.getInstance().isSleepAtNight() && nightTicks >= 400) {
                        sendChatMessage(client, "§e[Bot] مر على الليل 20 ثانية واكتملت المزرعة الحالية. يتوجه البوت الآن للسرير للنوم حتى تطلع الشمس...");
                        currentStage = FarmStage.SLEEP;
                        currentPath = null;
                        currentGoal = null;
                        if (isDriving) {
                            releaseControls(client);
                            isDriving = false;
                        }
                        return;
                    }

                    // إذا امتلأ المخزون بالكامل، ينهي المزرعة الحالية ثم يتوجه للصناديق لتفريغ المحاصيل والاحتفاظ بـ 64 حبة
                    if (isInventoryFull(player)) {
                        visitedDepositChests.clear();
                        FarmArea depositFarm = findAreaNeedingDeposit(player, level, areas);
                        if (depositFarm != null) {
                            sendChatMessage(client, "§e[Bot] المخزون ممتلئ واكتملت المزرعة الحالية! يتوجه لتفريغ المحاصيل بدءاً بصندوق مزرعة §a" + depositFarm.getName() + "§e...");
                            targetDepositArea = depositFarm;
                            currentStage = FarmStage.DEPOSIT;
                            currentPath = null;
                            currentGoal = null;
                            depositTimeoutTicks = 0;
                            if (isDriving) {
                                releaseControls(client);
                                isDriving = false;
                            }
                            return;
                        }
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

                        if (FarmAreaManager.getInstance().isSleepAtNight() && nightTicks >= 400) {
                            sendChatMessage(client, "§e[Bot] مر على الليل 20 ثانية وانتهت جميع الأعمال. يتوجه البوت الآن للسرير للنوم حتى تطلع الشمس...");
                            currentStage = FarmStage.SLEEP;
                            currentPath = null;
                            currentGoal = null;
                            if (isDriving) {
                                releaseControls(client);
                                isDriving = false;
                            }
                            return;
                        }
                        if (isInventoryFull(player)) {
                            visitedDepositChests.clear();
                            FarmArea depositFarm = findAreaNeedingDeposit(player, level, areas);
                            if (depositFarm != null) {
                                sendChatMessage(client, "§e[Bot] المخزون ممتلئ! يتوجه لتفريغ المحاصيل بدءاً بصندوق مزرعة §a" + depositFarm.getName() + "§e...");
                                targetDepositArea = depositFarm;
                                currentStage = FarmStage.DEPOSIT;
                                currentPath = null;
                                currentGoal = null;
                                depositTimeoutTicks = 0;
                                if (isDriving) {
                                    releaseControls(client);
                                    isDriving = false;
                                }
                                return;
                            }
                        }
                        cooldownTicks = 60; // استراحة لمدة 3 ثوانٍ قبل فحص المزارع مرة أخرى
                        currentPath = null;
                        currentGoal = null;
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
        currentPath = null;
        currentGoal = null;
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
            // التحرك الذكي نحو الهدف مع تفادي الأسوار والدخول عبر البوابة
            navigateTowards(client, player, level, target.pos, targetCenterX, targetCenterY, targetCenterZ, reachDistance);
        } else {
            // التوقف عن المشي فور الاقتراب الكافي
            currentPath = null;
            currentGoal = null;
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
                    if (hoeAlertTicks <= 0) {
                        sendChatMessage(client, "§c[Bot] لا يوجد أي فأس زراعي (Hoe) في الحقيبة لإصلاح التربة! يرجى توفير فأس زراعي.");
                        hoeAlertTicks = 200;
                    }
                    cooldownTicks = 2;
                }
            } else if (target.type == ActionType.PLANT) {
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

    /**
     * التنقل الذكي عبر نقاط المسار المحسوبة لتفادي الأسوار والجدران والماء وفتح الأبواب وصعود/هبوط الجسور
     */
    private void navigateTowards(Minecraft client, LocalPlayer player, ClientLevel level, BlockPos goalPos, double targetCenterX, double targetCenterY, double targetCenterZ, double reachDistance) {
        if (pathRecalcCooldown > 0) {
            pathRecalcCooldown--;
        }

        // إذا كان البوت يلتف ويغلق الباب حالياً، نوقف متابعة الحركة ريثما ينتهي
        if (handleDoorState(client, player, level, client.gameMode)) {
            return;
        }

        // فحص وفتح أي أبواب أو بوابات سياج مغلقة في طريق البوت دائماً للدخول والخروج بسلاسة
        checkAndOpenDoors(client, player, level, client.gameMode);

        // تتبع الحركة الفعلية للاعب لمنع التعليق
        Vec3 currentPos = player.position();
        if (lastPlayerPos != null && isDriving) {
            double movedDistSq = currentPos.distanceToSqr(lastPlayerPos);
            if (movedDistSq < 0.005) {
                physicalStuckTicks++;
            } else {
                physicalStuckTicks = 0;
            }
        } else {
            physicalStuckTicks = 0;
        }
        lastPlayerPos = currentPos;

        if (goalPos.equals(lastTargetPos)) {
            // التعليق الفعلي: اللاعب يحاول المشي منذ 70 نبضة (3.5 ثوانٍ) دون حراك فيزيائي
            if (physicalStuckTicks > 70) {
                unreachablePositions.add(goalPos);
                currentPath = null;
                currentGoal = null;
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

        // إعادة حساب المسار عند تغير الهدف أو انتهاء المسار السابق
        if (currentGoal == null || !currentGoal.equals(goalPos) || currentPath == null || currentPath.isEmpty()) {
            currentGoal = goalPos;
            int distManhattan = Math.abs(player.blockPosition().getX() - goalPos.getX()) + Math.abs(player.blockPosition().getZ() - goalPos.getZ());
            // رفع الحد الأقصى للعقد إلى 15000 عقدة لدعم مسارات الجسور الطويلة والالتفافات من البيت إلى المزرعة
            int maxNodes = distManhattan > 25 ? 15000 : 4000;
            List<BlockPos> computed = FarmPathfinder.findPath(level, player.blockPosition(), goalPos, maxNodes);
            if (computed != null && !computed.isEmpty()) {
                currentPath = new ArrayList<>(computed);
                targetStuckTicks = 0;
            } else {
                currentPath = new ArrayList<>();
                targetStuckTicks++;
                // إذا تعذر العثور على أي مسار 4 مرات متتالية (~4 ثوانٍ)
                if (targetStuckTicks > 4) {
                    // لا نحظر المحصول نهائياً إلا إذا كان اللاعب قريباً منه (أقل من 15 بلوكة)،
                    // حتى لا نحظر محاصيل المزرعة عندما يكون اللاعب بعيداً في المنزل
                    if (distManhattan <= 15) {
                        unreachablePositions.add(goalPos);
                    }
                    currentPath = null;
                    currentGoal = null;
                    lastTargetPos = null;
                    targetStuckTicks = 0;
                    if (isDriving) {
                        releaseControls(client);
                        isDriving = false;
                    }
                    return;
                }
            }
            pathRecalcCooldown = 20; // إعادة التقييم بعد ثانية كحد أقصى
        }

        if (currentPath != null && !currentPath.isEmpty()) {
            // فحص هل اللاعب في الماء (للسير والسباحة على السطح دون غرق)
            boolean inWater = player.isInWater()
                || level.getBlockState(player.blockPosition()).is(Blocks.WATER)
                || level.getBlockState(player.blockPosition().above()).is(Blocks.WATER);

            BlockPos nextWp = currentPath.get(0);
            double wpX = nextWp.getX() + 0.5;
            double wpZ = nextWp.getZ() + 0.5;

            double dx = player.getX() - wpX;
            double dz = player.getZ() - wpZ;
            double hDistSq = dx * dx + dz * dz;

            // التحقق من وصول اللاعب أفقياً وعمودياً (عتبة مرنة وانسيابية للسبرنت السريع)
            boolean sprintActive = shouldSprint(player);
            double reachThreshold = sprintActive ? 0.95 : 0.75;

            boolean yReached = inWater || player.getY() >= nextWp.getY() - 0.5;
            if (hDistSq < reachThreshold * reachThreshold && yReached) {
                currentPath.remove(0);
                if (!currentPath.isEmpty()) {
                    nextWp = currentPath.get(0);
                    wpX = nextWp.getX() + 0.5;
                    wpZ = nextWp.getZ() + 0.5;
                }
            }

            if (currentPath.isEmpty()) {
                boolean fenceBetween = FarmPathfinder.isPathBlockedByFence(level, player.position(), new Vec3(targetCenterX, targetCenterY, targetCenterZ));
                if (fenceBetween) {
                    releaseControls(client);
                } else {
                    moveTo(client, player, level, targetCenterX, targetCenterY, targetCenterZ, reachDistance, true);
                }
            } else {
                moveTo(client, player, level, wpX, nextWp.getY(), wpZ, 0.4, false);
            }

            // التحكم بالقفز والسباحة دون غرق
            if (inWater) {
                // إبقاء الرأس دائماً فوق الماء لمنع الغرق، والاندفاع السريع للسباحة
                client.options.keyJump.setDown(true);
                client.options.keySprint.setDown(true);
            } else {
                boolean onFarmSoil = isStandingOnFarmSoil(player, level);
                boolean onChest = isStandingOnChest(player, level);
                boolean facingFence = isBlockedByFenceOrWall(player, level);
                double yDiff = nextWp.getY() - player.getY();

                // منع القفز تماماً إذا كان أمامه سياج أو صندوق أو إذا كان يقف فوق صندوق حتى لا يقفز فوق السور ويسحق المحاصيل
                if (facingFence || onChest) {
                    client.options.keyJump.setDown(false);
                    checkAndOpenDoors(client, player, level, client.gameMode);
                } else if (yDiff >= 0.3 && !onFarmSoil && player.onGround()) {
                    client.options.keyJump.setDown(true);
                } else if (player.horizontalCollision && player.onGround() && !onFarmSoil && yDiff >= 0.1) {
                    client.options.keyJump.setDown(true);
                } else {
                    client.options.keyJump.setDown(false);
                }
            }
        } else {
            // خط مباشر كاحتياط في حال تعذر حساب المسار شريطة عدم وجود سياج يعترضه
            boolean fenceBetween = FarmPathfinder.isPathBlockedByFence(level, player.position(), new Vec3(targetCenterX, targetCenterY, targetCenterZ));
            if (fenceBetween) {
                releaseControls(client);
            } else {
                moveTo(client, player, level, targetCenterX, targetCenterY, targetCenterZ, reachDistance, true);
            }
        }
    }

    /**
     * فحص وفتح أي أبواب خشبية أو بوابات سياج مغلقة تعترض طريق البوت
     */
    private void checkAndOpenDoors(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode) {
        if (gameMode == null) return;
        if (doorInteractCooldown > 0) return;
        if (doorState != DoorHandlingState.NONE) return;

        BlockPos feet = player.blockPosition();

        // تنظيف آخر باب تم إغلاقه إذا ابتعد عنه البوت مسافة كافية (> 4.5 بلوكات)
        if (lastClosedDoorPos != null) {
            double distSqToClosed = player.distanceToSqr(
                lastClosedDoorPos.getX() + 0.5,
                lastClosedDoorPos.getY() + 0.5,
                lastClosedDoorPos.getZ() + 0.5
            );
            if (distSqToClosed > 4.5 * 4.5) {
                lastClosedDoorPos = null;
            }
        }

        Vec3 look = player.getViewVector(1.0F);
        double lookHorizLen = Math.sqrt(look.x * look.x + look.z * look.z);

        // 1. أولاً: فحص الأبواب وبوابات السياج المغلقة أمام البوت مباشرة لفتحها للعبور
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos checkPos = feet.offset(dx, dy, dz);

                    // استثناء الباب الذي تم إغلاقه للتو منعاً لإعادة فتحه وهو خلف ظهر اللاعب
                    if (lastClosedDoorPos != null && checkPos.equals(lastClosedDoorPos)) {
                        continue;
                    }

                    BlockState state = level.getBlockState(checkPos);
                    boolean isClosedDoor = FarmPathfinder.isOpenableDoor(state) && !state.getValue(DoorBlock.OPEN);
                    boolean isClosedGate = FarmPathfinder.isOpenableGate(state) && !state.getValue(FenceGateBlock.OPEN);

                    if (isClosedDoor || isClosedGate) {
                        double toDoorX = (checkPos.getX() + 0.5) - player.getX();
                        double toDoorZ = (checkPos.getZ() + 0.5) - player.getZ();
                        double horizDist = Math.sqrt(toDoorX * toDoorX + toDoorZ * toDoorZ);

                        // التحقق من أن الباب أمام البوت في اتجاه حركته ونظره وليس خلفه أو بجانبه
                        if (horizDist > 0.1 && lookHorizLen > 0.01) {
                            double dot = (look.x * toDoorX + look.z * toDoorZ) / (lookHorizLen * horizDist);
                            if (dot < 0.4) {
                                // الباب خلف اللاعب أو بزاوية حادة، لا يتم فتحه إطلاقاً
                                continue;
                            }
                        }

                        double dSq = player.distanceToSqr(checkPos.getX() + 0.5, checkPos.getY() + 0.5, checkPos.getZ() + 0.5);
                        if (dSq <= 2.8 * 2.8) {
                            int safeSlot = findSafeInteractionSlot(player);
                            if (safeSlot != -1 && safeSlot != player.getInventory().getSelectedSlot()) {
                                selectHotbarSlot(player, safeSlot);
                            }

                            smoothLookAt(player, checkPos.getX() + 0.5, checkPos.getY() + 0.5, checkPos.getZ() + 0.5, 60.0f);

                            Vec3 hitVec = Vec3.atCenterOf(checkPos);
                            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, checkPos, false);
                            gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                            player.swing(InteractionHand.MAIN_HAND);

                            doorInteractCooldown = 10;
                            activeDoorPos = checkPos.immutable();
                            activeDoorEntryPos = player.position();
                            doorState = DoorHandlingState.STEPPING_THROUGH;
                            doorStateTicks = 0;
                            return;
                        }
                    }
                }
            }
        }

        // 2. ثانياً: فحص الأبواب المفتوحة مسبقاً إذا كان البوت يمر عبرها لتسجيلها وإغلاقها بعد التخطي
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos checkPos = feet.offset(dx, dy, dz);

                    // استثناء الباب المغلق للتو
                    if (lastClosedDoorPos != null && checkPos.equals(lastClosedDoorPos)) {
                        continue;
                    }

                    BlockState state = level.getBlockState(checkPos);
                    boolean isOpenDoor = FarmPathfinder.isOpenableDoor(state) && state.getValue(DoorBlock.OPEN);
                    boolean isOpenGate = FarmPathfinder.isOpenableGate(state) && state.getValue(FenceGateBlock.OPEN);

                    if (isOpenDoor || isOpenGate) {
                        double toDoorX = (checkPos.getX() + 0.5) - player.getX();
                        double toDoorZ = (checkPos.getZ() + 0.5) - player.getZ();
                        double horizDist = Math.sqrt(toDoorX * toDoorX + toDoorZ * toDoorZ);

                        if (horizDist > 0.1 && lookHorizLen > 0.01) {
                            double dot = (look.x * toDoorX + look.z * toDoorZ) / (lookHorizLen * horizDist);
                            if (dot < 0.2) {
                                continue;
                            }
                        }

                        double dSq = player.distanceToSqr(checkPos.getX() + 0.5, checkPos.getY() + 0.5, checkPos.getZ() + 0.5);
                        if (dSq <= 1.25 * 1.25) {
                            activeDoorPos = checkPos.immutable();
                            activeDoorEntryPos = player.position();
                            doorState = DoorHandlingState.STEPPING_THROUGH;
                            doorStateTicks = 0;
                            return;
                        }
                    }
                }
            }
        }
    }

    /**
     * التعامل مع دورة إغلاق الباب: التوقف التام عند تخطي الباب، الالتفاف نحوه، إغلاقه، ثم استئناف السير.
     * تعود بـ true إذا كان البوت يقوم حالياً بإغلاق الباب لإيقاف الحركة المعتادة مؤقتاً.
     */
    private boolean handleDoorState(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode) {
        if (doorInteractCooldown > 0) {
            doorInteractCooldown--;
        }

        if (doorState == DoorHandlingState.NONE || activeDoorPos == null) {
            return false;
        }

        doorStateTicks++;

        // مهلة أمان زمنية: إذا مضت 4 ثوانٍ دون إتمام العبور، نلغي الحالة لتفادي التعليق
        if (doorStateTicks > 80) {
            resetDoorState(client);
            return false;
        }

        BlockState state = level.getBlockState(activeDoorPos);
        boolean isOpen = (FarmPathfinder.isOpenableDoor(state) && state.getValue(DoorBlock.OPEN))
            || (FarmPathfinder.isOpenableGate(state) && state.getValue(FenceGateBlock.OPEN));

        // إذا كان الباب قد أُغلق بالفعل من جهة أخرى
        if (!isOpen && doorState != DoorHandlingState.CONFIRMING) {
            resetDoorState(client);
            return false;
        }

        double doorCenterX = activeDoorPos.getX() + 0.5;
        double doorCenterY = activeDoorPos.getY() + 0.5;
        double doorCenterZ = activeDoorPos.getZ() + 0.5;

        double currX = player.getX() - doorCenterX;
        double currZ = player.getZ() - doorCenterZ;
        double hDistSq = currX * currX + currZ * currZ;
        double hDist = Math.sqrt(hDistSq);

        if (doorState == DoorHandlingState.STEPPING_THROUGH) {
            if (activeDoorEntryPos == null) {
                activeDoorEntryPos = player.position();
            }

            double entryX = doorCenterX - activeDoorEntryPos.x;
            double entryZ = doorCenterZ - activeDoorEntryPos.z;
            double crossDot = entryX * currX + entryZ * currZ;

            // إذا ابتعد كثيراً (> 4.2 بلوكات) نلغي التتبع
            if (hDist > 4.2) {
                resetDoorState(client);
                return false;
            }

            // تحقق تخطي الباب والعبور للجانب المقابل:
            // crossDot > 0.05 للدلالة على الانتقال للجهة الأخرى من الباب
            // و hDist >= 1.35 لضمان ابتعاد جسم اللاعب عن إطار الباب بالكامل
            if (crossDot > 0.05 && hDist >= 1.35) {
                // إيقاف الركض والحركة فوراً وبشكل حاسم!
                releaseControls(client);
                isDriving = false;
                doorState = DoorHandlingState.TURNING_AND_CLOSING;
                doorStateTicks = 0;
                return true;
            }

            // لا يزال يعبر الباب أثناء السير العادي
            return false;
        }

        if (doorState == DoorHandlingState.TURNING_AND_CLOSING) {
            // إيقاف أي حركة تماماً
            releaseControls(client);
            isDriving = false;

            // الالتفاف مباشرة نحو الباب
            lookAtTarget(player, doorCenterX, doorCenterY, doorCenterZ);

            // النقر على الباب لإغلاقه
            if (isOpen) {
                int safeSlot = findSafeInteractionSlot(player);
                if (safeSlot != -1 && safeSlot != player.getInventory().getSelectedSlot()) {
                    selectHotbarSlot(player, safeSlot);
                }

                Vec3 hitVec = Vec3.atCenterOf(activeDoorPos);
                BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, activeDoorPos, false);
                gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                player.swing(InteractionHand.MAIN_HAND);
            }

            doorState = DoorHandlingState.CONFIRMING;
            doorConfirmTicks = 3; // انتظار 3 نبضات للتأكد من انغلاق الباب قبل مواصلة الطريق
            return true;
        }

        if (doorState == DoorHandlingState.CONFIRMING) {
            releaseControls(client);
            isDriving = false;

            if (doorConfirmTicks > 0) {
                doorConfirmTicks--;
                return true;
            }

            // اكتمل الإغلاق بنجاح! ننهي التتبع ونستأنف السير
            doorInteractCooldown = 15;
            lastClosedDoorPos = activeDoorPos;
            resetDoorState(client);
            return false;
        }

        return false;
    }

    private void lookAtTarget(LocalPlayer player, double targetX, double targetY, double targetZ) {
        double dx = targetX - player.getX();
        double dy = targetY - player.getEyeY();
        double dz = targetZ - player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float targetPitch = (float) (-(Math.atan2(dy, dist) * 180.0 / Math.PI));

        player.setYRot(targetYaw);
        player.setXRot(targetPitch);
    }

    private void resetDoorState(Minecraft client) {
        activeDoorPos = null;
        activeDoorEntryPos = null;
        doorState = DoorHandlingState.NONE;
        doorStateTicks = 0;
        doorConfirmTicks = 0;
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
        Inventory inv = player.getInventory();
        // البحث أولاً عن خانة فارغة في شريط الأدوات (Hotbar)
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) {
                return i;
            }
        }
        // البحث عن خانة لا تحتوي على بلوكة ولا فأس شجر لعدم إعاقة التفاعل
        for (int i = 0; i < 9; i++) {
            var item = inv.getItem(i).getItem();
            if (!(item instanceof net.minecraft.world.item.BlockItem) && !(item instanceof net.minecraft.world.item.AxeItem)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isBlockedByFenceOrWall(LocalPlayer player, ClientLevel level) {
        Vec3 look = player.getLookAngle();
        BlockPos feet = player.blockPosition();

        int ox = (int) Math.round(look.x);
        int oz = (int) Math.round(look.z);
        if (ox != 0 || oz != 0) {
            BlockPos front = feet.offset(ox, 0, oz);
            if (isFenceOrClosedGate(level, front) || isFenceOrClosedGate(level, front.above()) || isFenceOrClosedGate(level, front.below())) {
                return true;
            }
        }

        if (player.horizontalCollision) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos side = feet.relative(dir);
                if (isFenceOrClosedGate(level, side) || isFenceOrClosedGate(level, side.above())) {
                    double dot = look.x * dir.getStepX() + look.z * dir.getStepZ();
                    if (dot > -0.2) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static boolean isFenceOrClosedGate(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (FarmPathfinder.isFenceOrWall(state)) return true;
        if (FarmPathfinder.isOpenableGate(state) && !state.getValue(FenceGateBlock.OPEN)) return true;
        // معاملة الصناديق والحاويات كحواجز لمنع التسلق والقفز فوقها بجوار الأسوار
        if (FarmPathfinder.isChestOrContainer(state)) return true;
        return false;
    }

    private boolean isStandingOnChest(LocalPlayer player, ClientLevel level) {
        BlockPos feet = player.blockPosition();
        BlockState atFeet = level.getBlockState(feet);
        BlockState belowFeet = level.getBlockState(feet.below());
        return FarmPathfinder.isChestOrContainer(atFeet) || FarmPathfinder.isChestOrContainer(belowFeet);
    }

    private boolean isStandingOnFarmSoil(LocalPlayer player, ClientLevel level) {
        BlockPos feet = player.blockPosition();
        BlockState atFeet = level.getBlockState(feet);
        BlockState belowFeet = level.getBlockState(feet.below());
        return atFeet.is(Blocks.FARMLAND) || atFeet.is(Blocks.SOUL_SAND)
            || belowFeet.is(Blocks.FARMLAND) || belowFeet.is(Blocks.SOUL_SAND)
            || atFeet.getBlock() instanceof CropBlock
            || belowFeet.getBlock() instanceof CropBlock
            || atFeet.getBlock() instanceof NetherWartBlock
            || belowFeet.getBlock() instanceof NetherWartBlock;
    }

    private void selectHotbarSlot(LocalPlayer player, int slot) {
        BotEquipmentHelper.selectHotbarSlot(player, slot);
    }

    private void moveTo(Minecraft client, LocalPlayer player, ClientLevel level, double tx, double ty, double tz, double stopDist) {
        moveTo(client, player, level, tx, ty, tz, stopDist, true);
    }

    private void moveTo(Minecraft client, LocalPlayer player, ClientLevel level, double tx, double ty, double tz, double stopDist, boolean controlJump) {
        double dx = tx - player.getX();
        double dz = tz - player.getZ();
        double distSq = dx * dx + dz * dz;

        // التوقف عند الوصول للمسافة المطلوبة لمنع تجاوز النقطة والالتفاف حولها
        if (stopDist > 0 && distSq <= stopDist * stopDist) {
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            player.setSprinting(false);
            isDriving = false;
            return;
        }

        isDriving = true;

        boolean inWater = player.isInWater() || level.getBlockState(player.blockPosition()).is(Blocks.WATER);

        if (inWater) {
            // السباحة فوق سطح الماء دون غرق
            client.options.keyJump.setDown(true);
            client.options.keySprint.setDown(true);
        }

        float diffYaw = calculateYawDiff(player, tx, tz);
        smoothLookAt(player, tx, player.getEyeY(), tz, ROTATION_SPEED);

        boolean targetBlockedByFence = FarmPathfinder.isPathBlockedByFence(level, player.position(), new Vec3(tx, ty, tz));
        boolean collisionWithFence = player.horizontalCollision && isBlockedByFenceOrWall(player, level);

        if (targetBlockedByFence || collisionWithFence) {
            // مواجهة سياج في مسار الحركة المباشر: إيقاف الضغط للأمام والقفز والسبرنت
            client.options.keyUp.setDown(false);
            client.options.keyJump.setDown(false);
            client.options.keySprint.setDown(false);
            player.setSprinting(false);

            // محاولة فتح بوابة السياج إن كانت قريبة
            checkAndOpenDoors(client, player, level, client.gameMode);

            stuckTicks++;
            if (stuckTicks >= 6) {
                // تراجع طفيف لفك الالتصاق بالسياج وإفساح المجال للالتفاف
                client.options.keyDown.setDown(true);
            }
            if (stuckTicks >= 8) {
                client.options.keyDown.setDown(false);
                // إعادة تقييم المسار فوراً لاتباع المسار الحقيقي المفتوح أو البوابة
                currentPath = null;
                currentGoal = null;
                pathRecalcCooldown = 0;
                stuckTicks = 0;
            }
            return;
        } else {
            if (client.options.keyDown.isDown()) {
                client.options.keyDown.setDown(false);
            }
        }

        boolean canSprint = shouldSprint(player);

        // السماح بالحركة أثناء الالتفاف لجعل الانعطاف طبيعياً وسلساً دون توقف متكرر، وتفعيل السبرنت التلقائي خارج المزرعة
        if (Math.abs(diffYaw) < 75.0f) {
            client.options.keyUp.setDown(true);
            if (canSprint && Math.abs(diffYaw) < 40.0f) {
                client.options.keySprint.setDown(true);
                player.setSprinting(true);
            } else {
                client.options.keySprint.setDown(false);
                player.setSprinting(false);
            }
        } else {
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            player.setSprinting(false);
        }

        if (controlJump && !inWater) {
            // تفادي القفز العشوائي وعدم القفز إطلاقاً على التربة الزراعية أو فوق الصناديق لحمايتها من التلف
            boolean onFarmSoil = isStandingOnFarmSoil(player, level);
            boolean onChest = isStandingOnChest(player, level);

            if (onChest) {
                // منع القفز نهائياً عند الوقوف فوق صندوق لحمايته من القفز فوق السور والسقوط على المحاصيل
                client.options.keyJump.setDown(false);
            } else if (player.horizontalCollision && player.onGround() && !onFarmSoil) {
                stuckTicks++;
                if (stuckTicks >= 3) {
                    client.options.keyJump.setDown(true);
                }
            } else {
                stuckTicks = 0;
                client.options.keyJump.setDown(false);
            }
        }
    }

    private void releaseControls(Minecraft client) {
        if (client.options != null) {
            client.options.keyUp.setDown(false);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keyJump.setDown(false);
            client.options.keySprint.setDown(false);
            if (!FightBotController.getInstance().isDefending()) {
                client.options.keyUse.setDown(false);
            }
        }
        if (client.player != null) {
            client.player.setSprinting(false);
        }
        stuckTicks = 0;
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

    private float calculateYawDiff(LocalPlayer player, double targetX, double targetZ) {
        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        float targetYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        return wrapDegrees(targetYaw - player.getYRot());
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
                        BlockState above = level.getBlockState(pos.above());
                        if (above.isAir() || above.canBeReplaced()) {
                            dirtPos = pos;
                        }
                    } else if (state.isAir()) {
                        BlockPos below = pos.below();
                        BlockState belowState = level.getBlockState(below);
                        if (isTillableSoil(belowState)) {
                            dirtPos = below;
                        }
                    }

                    if (dirtPos != null) {
                        if (unreachablePositions.contains(dirtPos)) continue;
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
                    BlockState state = level.getBlockState(pos);

                    if (state.isAir()) {
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
            BlockPos bedPos = FarmAreaManager.getInstance().getBedPos();
            if (bedPos != null && player.distanceToSqr(bedPos.getX() + 0.5, bedPos.getY() + 0.5, bedPos.getZ() + 0.5) <= 6.0 * 6.0) {
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
     * معالجة الذهاب إلى السرير والنوم عند حلول الليل
     */
    private void handleSleepStage(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode) {
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

        // تحديد موقع السرير (سواء المحفوظ مسبقاً أو البحث التلقائي عن أقرب سرير)
        BlockPos bedPos = FarmAreaManager.getInstance().getBedPos();
        if (bedPos == null) {
            bedPos = findNearbyBed(player, level, 48);
            if (bedPos != null) {
                FarmAreaManager.getInstance().setBedPos(bedPos);
                sendChatMessage(client, "§a[Bot] تم العثور تلقائياً على سرير قريب عند: §e" + bedPos.getX() + ", " + bedPos.getY() + ", " + bedPos.getZ());
            }
        }

        if (bedPos == null) {
            if (!bedAlertSent) {
                sendChatMessage(client, "§c[Bot] حان وقت النوم ولكن لم يتم العثور على أي سرير! يرجى تعيين السرير عبر: §e/bot farm bed set §cأو وضع سرير بالقرب منك.");
                bedAlertSent = true;
            }
            // في حال عدم وجود أي سرير، يواصل العمل في المزارع حتى لا يتوقف
            currentStage = FarmStage.HARVEST;
            return;
        }

        double bedCenterX = bedPos.getX() + 0.5;
        double bedCenterY = bedPos.getY() + 0.5;
        double bedCenterZ = bedPos.getZ() + 0.5;
        double distSq = player.distanceToSqr(bedCenterX, bedCenterY, bedCenterZ);

        if (distSq > 2.8 * 2.8) {
            // التوجه الذكي نحو السرير مع تفادي العقبات والماء وفتح الأبواب
            navigateTowards(client, player, level, bedPos, bedCenterX, bedCenterY, bedCenterZ, 2.5);

            // رسم خط مسار الذهاب إلى السرير بجزيئات مرئية خضراء متألقة فوق الجسور وبالممرات
            if (currentPath != null && !currentPath.isEmpty()) {
                int drawLimit = Math.min(currentPath.size(), 24);
                for (int i = 0; i < drawLimit; i++) {
                    BlockPos p = currentPath.get(i);
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
            currentPath = null;
            currentGoal = null;
            if (isDriving) {
                releaseControls(client);
                isDriving = false;
            }

            // فحص هل يوجد باب مفتوح في محيط الغرفة (ضمن مدى التفاعل حتى 4.2 بلوكات)
            BlockPos openDoorPos = findNearbyOpenDoor(player, level, 4.2);
            if (openDoorPos != null && doorInteractCooldown <= 0) {
                int safeSlot = findSafeInteractionSlot(player);
                if (safeSlot != -1 && safeSlot != player.getInventory().getSelectedSlot()) {
                    selectHotbarSlot(player, safeSlot);
                }

                smoothLookAt(player, openDoorPos.getX() + 0.5, openDoorPos.getY() + 0.5, openDoorPos.getZ() + 0.5, 60.0f);
                Vec3 hitVec = Vec3.atCenterOf(openDoorPos);
                BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, openDoorPos, false);
                gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                player.swing(InteractionHand.MAIN_HAND);
                doorInteractCooldown = 4;
                resetDoorState(client);
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

    private BlockPos findNearbyBed(LocalPlayer player, ClientLevel level, int radius) {
        BlockPos origin = player.blockPosition();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int y = -8; y <= 8; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof BedBlock || state.is(BlockTags.BEDS)) {
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
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
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
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
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

    /**
     * معالجة الذهاب إلى الصندوق لجلب البذور عند نفادها من الحقيبة
     */
    private void handleWithdrawStage(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode) {
        if (targetWithdrawArea == null || targetWithdrawArea.getChestPos() == null || targetWithdrawCrop == null) {
            targetWithdrawArea = null;
            targetWithdrawCrop = null;
            currentStage = FarmStage.PLANT;
            return;
        }

        BlockPos chestPos = targetWithdrawArea.getChestPos();
        double chestCenterX = chestPos.getX() + 0.5;
        double chestCenterY = chestPos.getY() + 0.5;
        double chestCenterZ = chestPos.getZ() + 0.5;

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
            } else {
                sendChatMessage(client, "§c[Bot] لا توجد بذور (" + targetWithdrawCrop.getArabicName() + ") في صندوق مزرعة §e" + targetWithdrawArea.getName() + "§c! تجاوز مرحلة الزراعة لهذه الدورة.");
                areasOutOfSeeds.add(targetWithdrawArea.getName());
                currentStage = FarmStage.HARVEST;
                currentAreaIndex = (currentAreaIndex + 1) % FarmAreaManager.getInstance().getActiveAreas().size();
            }

            targetWithdrawArea = null;
            targetWithdrawCrop = null;
            withdrawTimeoutTicks = 0;
            cooldownTicks = 15;
            return;
        } else {
            withdrawCloseDelay = 0;
        }

        // مهلة الوصول للصندوق (20 ثانية = 400 نبضة)
        withdrawTimeoutTicks++;
        if (withdrawTimeoutTicks > 400) {
            sendChatMessage(client, "§c[Bot] تعذر فتح صندوق مزرعة §e" + targetWithdrawArea.getName() + "§c لسحب البذور! تجاوز المزرعة.");
            areasOutOfSeeds.add(targetWithdrawArea.getName());
            targetWithdrawArea = null;
            targetWithdrawCrop = null;
            withdrawTimeoutTicks = 0;
            currentStage = FarmStage.HARVEST;
            currentAreaIndex = (currentAreaIndex + 1) % FarmAreaManager.getInstance().getActiveAreas().size();
            return;
        }

        double distSq = player.distanceToSqr(chestCenterX, chestCenterY, chestCenterZ);
        if (distSq > 2.8 * 2.8) {
            navigateTowards(client, player, level, chestPos, chestCenterX, chestCenterY, chestCenterZ, 2.5);
        } else {
            currentPath = null;
            currentGoal = null;
            if (isDriving) {
                releaseControls(client);
                isDriving = false;
            }

            smoothLookAt(player, chestCenterX, chestCenterY, chestCenterZ, ROTATION_SPEED);

            BlockHitResult hit = new BlockHitResult(
                new Vec3(chestCenterX, chestCenterY, chestCenterZ),
                Direction.UP,
                chestPos,
                false
            );
            gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            cooldownTicks = 15; // انتظار استجابة السيرفر وفتح الحاوية
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

    /**
     * معالجة الذهاب إلى الصندوق وتفريغ المحاصيل الزائدة مع الاحتفاظ بـ 64 حبة،
     * مع التأكد التام من وضع كل نوع محصول في صندوقه المخصص دون خلط.
     */
    private void handleDepositStage(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode) {
        if (targetDepositArea == null || targetDepositArea.getChestPos() == null) {
            targetDepositArea = null;
            visitedDepositChests.clear();
            currentStage = FarmStage.HARVEST;
            return;
        }

        BlockPos chestPos = targetDepositArea.getChestPos();
        double chestCenterX = chestPos.getX() + 0.5;
        double chestCenterY = chestPos.getY() + 0.5;
        double chestCenterZ = chestPos.getZ() + 0.5;

        // إذا كان الصندوق مفتوحاً بالفعل (شاشة الحاوية مفتوحة)
        if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            if (isDriving) {
                releaseControls(client);
                isDriving = false;
            }

            if (depositCloseDelay == 0) {
                List<FarmArea> activeAreas = FarmAreaManager.getInstance().getActiveAreas();
                DepositPlan plan = buildDepositPlan(targetDepositArea, activeAreas, level, visitedDepositChests);
                lastDepositTargetCrops.clear();
                lastDepositTargetCrops.addAll(plan.targetCrops());
                lastDepositExcludedCrops.clear();
                lastDepositExcludedCrops.addAll(plan.excludedCropsWithOtherChests());
                lastDepositAllowUnmappedFarmItems = plan.allowUnmappedFarmItems();
                lastDepositedCount = depositSurplusIntoOpenChest(player, gameMode, plan);
                depositCloseDelay = 4; // انتظار 4 نبضات لضمان معالجة السيرفر لحزم الإيداع
                return;
            }

            if (depositCloseDelay > 1) {
                depositCloseDelay--;
                return;
            }

            depositCloseDelay = 0;
            // إغلاق الصندوق بعد الانتهاء من إيداع المحاصيل المحددة له
            player.closeContainer();

            int deposited = lastDepositedCount;

            // فحص هل بقي فائض لأي من المحاصيل التي كان ينبغي وضعها في هذا الصندوق (الصندوق ممتلئ)
            Set<FarmCrop> checkedCrops = EnumSet.noneOf(FarmCrop.class);
            checkedCrops.addAll(lastDepositTargetCrops);
            if (lastDepositAllowUnmappedFarmItems) {
                for (FarmCrop c : FarmCrop.values()) {
                    if (!lastDepositExcludedCrops.contains(c)) {
                        checkedCrops.add(c);
                    }
                }
            }
            for (FarmCrop crop : checkedCrops) {
                if (hasCropSurplus(player, crop)) {
                    fullChestCrops.add(crop);
                    sendChatMessage(client, "§6[Bot] صندوق مزرعة §e" + targetDepositArea.getName() + "§6 ممتلئ ولا يتسع لفائض محصول §e" + crop.getArabicName() + "§6!");
                }
            }

            if (deposited > 0) {
                sendChatMessage(client, "§a[Bot] تم تفريغ فائض المحاصيل (" + deposited + " حزمة) في صندوق مزرعة §e" + targetDepositArea.getName() + "§a بنجاح مع الاحتفاظ بـ 64 بذرة!");
            } else {
                sendChatMessage(client, "§e[Bot] تم فحص صندوق مزرعة §e" + targetDepositArea.getName() + "§e؛ لا توجد محاصيل إضافية لتفريغها أو الصندوق ممتلئ!");
            }

            // إضافة هذا الصندوق لقائمة الصناديق التي تمت زيارتها في هذه الجولة لمنع تكراره
            visitedDepositChests.add(chestPos);

            // فحص هل ما زال لدى اللاعب محاصيل أخرى فائضة تنتمي لصناديق مزارع أخرى لم نزرها بعد
            List<FarmArea> activeAreas = FarmAreaManager.getInstance().getActiveAreas();
            FarmArea nextFarm = findAreaNeedingDeposit(player, level, activeAreas, visitedDepositChests);

            if (nextFarm != null) {
                // الانتقال فوراً للصندوق التالي
                sendChatMessage(client, "§6[Bot] يتوجه الآن إلى صندوق مزرعة §e" + nextFarm.getName() + "§6 لتفريغ باقي المحاصيل...");
                targetDepositArea = nextFarm;
                depositTimeoutTicks = 0;
                currentPath = null;
                currentGoal = null;
                cooldownTicks = 15;
                return;
            }

            handlePostDepositFullChests(client, player, level, activeAreas);
            return;
        } else {
            depositCloseDelay = 0;
        }

        // إذا لم يكن الصندوق مفتوحاً بعد، نتحرك نحوه مع مراقبة الوقت
        depositTimeoutTicks++;
        if (depositTimeoutTicks > 400) { // بعد 20 ثانية دون الوصول
            sendChatMessage(client, "§c[Bot] تعذر فتح صندوق مزرعة §e" + targetDepositArea.getName() + "§c! فحص الصناديق التالية...");
            visitedDepositChests.add(chestPos);
            List<FarmArea> activeAreas = FarmAreaManager.getInstance().getActiveAreas();
            FarmArea nextFarm = findAreaNeedingDeposit(player, level, activeAreas, visitedDepositChests);
            if (nextFarm != null) {
                targetDepositArea = nextFarm;
                depositTimeoutTicks = 0;
                currentPath = null;
                currentGoal = null;
                return;
            }
            handlePostDepositFullChests(client, player, level, activeAreas);
            return;
        }

        double distSq = player.distanceToSqr(chestCenterX, chestCenterY, chestCenterZ);
        if (distSq > 2.8 * 2.8) {
            navigateTowards(client, player, level, chestPos, chestCenterX, chestCenterY, chestCenterZ, 2.5);
        } else {
            currentPath = null;
            currentGoal = null;
            if (isDriving) {
                releaseControls(client);
                isDriving = false;
            }

            smoothLookAt(player, chestCenterX, chestCenterY, chestCenterZ, ROTATION_SPEED);

            BlockHitResult hit = new BlockHitResult(
                new Vec3(chestCenterX, chestCenterY, chestCenterZ),
                Direction.UP,
                chestPos,
                false
            );
            gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            cooldownTicks = 15; // انتظار فتح الحاوية
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

    private void handlePostDepositFullChests(
            Minecraft client,
            LocalPlayer player,
            ClientLevel level,
            List<FarmArea> activeAreas) {
        if (!fullChestCrops.isEmpty() && hasAnyCompostableSurplus(player, fullChestCrops)) {
            BlockPos composter = findNearbyComposter(player, level, 36);
            if (composter != null) {
                sendChatMessage(client, "§e[Bot] لم يجد مساحة في صندوق المورد! يتوجه الآن إلى أقرب كومبوستر لتحويل الفائض إلى سماد...");
                targetComposterPos = composter;
                currentStage = FarmStage.COMPOST;
                compostTimeoutTicks = 0;
                currentPath = null;
                currentGoal = null;
                visitedDepositChests.clear();
                lastDepositTargetCrops.clear();
                lastDepositExcludedCrops.clear();
                lastDepositAllowUnmappedFarmItems = false;
                targetDepositArea = null;
                depositTimeoutTicks = 0;
                cooldownTicks = 15;
                return;
            }
            sendChatMessage(client, "§c[Bot] صناديق الموارد ممتلئة ولكن لم يتم العثور على أي كومبوستر قريب في المحيط (نطاق 36 بلوكة)!");
        }

        if (!fullChestCrops.isEmpty()) {
            for (FarmArea area : activeAreas) {
                Set<FarmCrop> areaCrops = getCropsInArea(level, area);
                for (FarmCrop c : areaCrops) {
                    if (fullChestCrops.contains(c)) {
                        fullChestPausedFarms.add(area.getName());
                        sendChatMessage(client, "§c[Bot] تم إيقاف العمل في مزرعة §e" + area.getName() + "§c (" + c.getArabicName() + ") مؤقتاً لامتلاء صندوقها وعدم توفر أداة سماد لتفريغ الفائض.");
                        break;
                    }
                }
            }
            fullChestCrops.clear();

            if (fullChestPausedFarms.containsAll(activeAreas.stream().map(FarmArea::getName).toList())) {
                sendChatMessage(client, "§c[Bot] تم إيقاف جميع المزارع مؤقتاً لامتلاء صناديقها وعدم توفر أداة سماد! إيقاف البوت لحين إفراغ الصناديق.");
                stop(client);
                return;
            }
        }

        fullChestCrops.clear();
        visitedDepositChests.clear();
        lastDepositTargetCrops.clear();
        lastDepositExcludedCrops.clear();
        lastDepositAllowUnmappedFarmItems = false;
        targetDepositArea = null;
        depositTimeoutTicks = 0;
        currentStage = FarmStage.HARVEST;
        cooldownTicks = 20;
        sendChatMessage(client, "§a[Bot] اكتمل تفريغ كافة المحاصيل المحددة في الصناديق! استئناف دورة الزراعة...");
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
            FarmArea targetArea,
            List<FarmArea> activeAreas,
            ClientLevel level,
            Set<BlockPos> excludedChests) {
        if (targetArea == null || targetArea.getChestPos() == null) {
            return new DepositPlan(EnumSet.noneOf(FarmCrop.class), EnumSet.noneOf(FarmCrop.class), false);
        }

        BlockPos chestPos = targetArea.getChestPos();
        Set<FarmCrop> cropsForChest = EnumSet.noneOf(FarmCrop.class);
        boolean hasAutoAreaOnChest = false;

        if (activeAreas != null) {
            for (FarmArea area : activeAreas) {
                if (fullChestPausedFarms.contains(area.getName())) continue;
                if (chestPos.equals(area.getChestPos())) {
                    cropsForChest.addAll(getCropsInArea(level, area));
                    if (area.getTargetCrop() == null) {
                        hasAutoAreaOnChest = true;
                    }
                }
            }
        }

        Set<FarmCrop> cropsWithOtherChests = EnumSet.noneOf(FarmCrop.class);
        if (activeAreas != null) {
            for (FarmArea area : activeAreas) {
                if (fullChestPausedFarms.contains(area.getName())) continue;
                BlockPos otherChest = area.getChestPos();
                if (otherChest != null
                        && !otherChest.equals(chestPos)
                        && (excludedChests == null || !excludedChests.contains(otherChest))) {
                    cropsWithOtherChests.addAll(getCropsInArea(level, area));
                }
            }
        }

        // إضافة كل المحاصيل التي يعمل عليها البوت الآن طالما لا تملك صندوقاً مستقلاً آخر غير مزور
        for (FarmCrop workingCrop : getActiveWorkingCrops(level, activeAreas)) {
            if (!cropsWithOtherChests.contains(workingCrop)) {
                cropsForChest.add(workingCrop);
            }
        }

        return new DepositPlan(cropsForChest, cropsWithOtherChests, hasAutoAreaOnChest);
    }

    private boolean shouldDepositItemWithPlan(Item item, DepositPlan plan) {
        for (FarmCrop crop : plan.targetCrops()) {
            if (doesItemBelongToCrop(item, crop)) {
                return true;
            }
        }
        if (plan.allowUnmappedFarmItems()) {
            for (FarmCrop excludedCrop : plan.excludedCropsWithOtherChests()) {
                if (doesItemBelongToCrop(item, excludedCrop)) {
                    return false;
                }
            }
            return true;
        }
        return false;
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
     * البحث عن خانة تحتوي على عناصر أو بذور فائضة تنتمي لمحاصيل امتلأت صناديقها
     * بشرط أن تكون قابلة للتحويل لسماد، مع حماية 64 حبة للبذور القابلة للزراعة
     */
    private int findCompostableSurplusSlot(LocalPlayer player, Set<FarmCrop> fullCrops) {
        if (player == null || fullCrops == null || fullCrops.isEmpty()) return -1;
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (item == Items.BAKED_POTATO || BotFoodManager.getInstance().isAllowed(stack)) continue; // حماية طعام البوت دائماً
            if (!ComposterBlock.COMPOSTABLES.containsKey(item)) continue; // عنصر يقبله الكومبوستر

            // فحص هل العنصر ينتمي لأحد المحاصيل التي امتلأت صناديقها
            boolean belongsToFullCrop = false;
            for (FarmCrop crop : fullCrops) {
                if (doesItemBelongToCrop(item, crop)) {
                    belongsToFullCrop = true;
                    break;
                }
            }
            if (!belongsToFullCrop) continue;

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

    private boolean hasAnyCompostableSurplus(LocalPlayer player, Set<FarmCrop> fullCrops) {
        return findCompostableSurplusSlot(player, fullCrops) != -1;
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
                moveTo(client, player, level, droppedBoneMeal.getX(), droppedBoneMeal.getY(), droppedBoneMeal.getZ(), 0.2);
                return;
            }
            collectBoneMealTicks = 0;
            sendChatMessage(client, "§e[Bot] تم جمع ستاك كامل (64) من السماد! يتوجه البوت الآن لتفريغه في صندوق السماد...");
            currentStage = FarmStage.DEPOSIT_FERTILIZER;
            fertilizerTimeoutTicks = 0;
            currentPath = null;
            currentGoal = null;
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
                fullChestCrops.clear();
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
                fullChestCrops.clear();
                targetComposterPos = null;
                currentStage = FarmStage.HARVEST;
                compostTimeoutTicks = 0;
            }
            return;
        }

        currentPath = null;
        currentGoal = null;
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
        int slot = findCompostableSurplusSlot(player, fullChestCrops);
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
                    moveTo(client, player, level, droppedBoneMeal.getX(), droppedBoneMeal.getY(), droppedBoneMeal.getZ(), 0.2);
                } else {
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
            fullChestCrops.clear();
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

        double cx = chestPos.getX() + 0.5;
        double cy = chestPos.getY() + 0.5;
        double cz = chestPos.getZ() + 0.5;

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
            if (!fullChestCrops.isEmpty() && hasAnyCompostableSurplus(player, fullChestCrops)) {
                currentStage = FarmStage.COMPOST;
                compostTimeoutTicks = 0;
            } else {
                fullChestCrops.clear();
                currentStage = FarmStage.HARVEST;
            }
            return;
        }

        fertilizerTimeoutTicks++;
        if (fertilizerTimeoutTicks > 400) {
            sendChatMessage(client, "§c[Bot] تعذر فتح صندوق السماد بعد 20 ثانية! العودة للزراعة...");
            currentStage = FarmStage.HARVEST;
            fertilizerTimeoutTicks = 0;
            return;
        }

        double distSq = player.distanceToSqr(cx, cy, cz);
        if (distSq > 2.8 * 2.8) {
            navigateTowards(client, player, level, chestPos, cx, cy, cz, 2.5);
        } else {
            currentPath = null;
            currentGoal = null;
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
            cooldownTicks = 15;
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

    private FarmArea findAreaNeedingDeposit(LocalPlayer player, ClientLevel level, List<FarmArea> areas, Set<BlockPos> excludedChests) {
        if (areas == null || areas.isEmpty() || player == null) return null;

        // 1. فحص المزرعة الحالية أولاً إذا كان لها صندوق غير مزور وفيه فائض من المحاصيل التي يقبلها هذا الصندوق
        if (currentAreaIndex < areas.size()) {
            FarmArea current = areas.get(currentAreaIndex);
            if (canAreaAcceptDeposit(player, level, current, areas, excludedChests)) {
                return current;
            }
        }

        // 2. فحص بقية المزارع المفعلة المرتبطة بصناديق متاحة لأي محصول يعمل عليه البوت الآن
        for (FarmArea area : areas) {
            if (canAreaAcceptDeposit(player, level, area, areas, excludedChests)) {
                return area;
            }
        }

        return null;
    }

    private FarmArea findAreaNeedingDeposit(LocalPlayer player, ClientLevel level, List<FarmArea> areas) {
        return findAreaNeedingDeposit(player, level, areas, visitedDepositChests);
    }

    private boolean canAreaAcceptDeposit(
            LocalPlayer player,
            ClientLevel level,
            FarmArea area,
            List<FarmArea> activeAreas,
            Set<BlockPos> excludedChests) {
        if (area == null || area.getChestPos() == null || fullChestPausedFarms.contains(area.getName())) {
            return false;
        }
        if (excludedChests != null && excludedChests.contains(area.getChestPos())) {
            return false;
        }

        DepositPlan plan = buildDepositPlan(area, activeAreas, level, excludedChests);
        for (FarmCrop crop : plan.targetCrops()) {
            if (hasCropSurplus(player, crop)) {
                return true;
            }
        }
        if (plan.allowUnmappedFarmItems()) {
            for (FarmCrop crop : FarmCrop.values()) {
                if (!plan.excludedCropsWithOtherChests().contains(crop) && hasCropSurplus(player, crop)) {
                    return true;
                }
            }
        }
        return false;
    }

    private record DepositPlan(
            Set<FarmCrop> targetCrops,
            Set<FarmCrop> excludedCropsWithOtherChests,
            boolean allowUnmappedFarmItems) {}

    private boolean hasCropSurplus(LocalPlayer player, FarmCrop crop) {
        if (crop == null || player == null) return false;
        return switch (crop) {
            case WHEAT -> countItemInInventory(player, net.minecraft.world.item.Items.WHEAT) > 0
                || countItemInInventory(player, net.minecraft.world.item.Items.WHEAT_SEEDS) > 64;
            case BEETROOT -> countItemInInventory(player, net.minecraft.world.item.Items.BEETROOT) > 0
                || countItemInInventory(player, net.minecraft.world.item.Items.BEETROOT_SEEDS) > 64;
            case POTATO -> countItemInInventory(player, net.minecraft.world.item.Items.POTATO) > 64
                || countItemInInventory(player, net.minecraft.world.item.Items.POISONOUS_POTATO) > 0;
            case CARROT -> countItemInInventory(player, net.minecraft.world.item.Items.CARROT) > 64;
            case NETHER_WART -> countItemInInventory(player, net.minecraft.world.item.Items.NETHER_WART) > 64;
        };
    }

    private int countItemInInventory(LocalPlayer player, net.minecraft.world.item.Item item) {
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
            currentPath = null;
            currentGoal = null;
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
