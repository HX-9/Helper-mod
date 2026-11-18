package com.helpermod.bot.hunt;

import com.helpermod.bot.BotEquipmentHelper;
import com.helpermod.bot.BotFollowController;
import com.helpermod.bot.BotGlobalManager;
import com.helpermod.bot.FarmAreaManager;
import com.helpermod.bot.FarmBotController;
import com.helpermod.bot.FightBotController;
import com.helpermod.bot.combat.RangedCombatHelper;
import com.helpermod.bot.food.BotFoodManager;
import com.helpermod.bot.friend.BotFriendManager;
import com.helpermod.bot.path.BotNavigator;
import com.helpermod.bot.path.FarmPathfinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * وحدة التحكم بوضع صيد وتجميع الموبات والحيوانات (Mob Hunt & Gathering Controller):
 * - تدعم 3 أنماط تشغيل: مناطق/حظائر محددة (ZONE)، دورية حول نقطة (PATROL)، أو نقطة تجميع ثابتة (SPOT).
 * - تبحث بنظام موجات متدرج موزون وتتفادى الأهداف المحجوزة في الكهوف.
 * - تحترم قواعد حماية الصغار والمروّضة والمسمّاة وإبقاء زوجين داخل الحظائر.
 * - تجمع الغنائم تلقائياً وتفرغها في الصناديق المخصصة مع حماية الأسلحة والأدوات والطعام والدرع والتوتم.
 */
public class MobHuntController {

    private static final MobHuntController INSTANCE = new MobHuntController();
    private static final float HUNT_ROTATION_SPEED = 36.0f;
    private static final int FOOD_HOTBAR_SLOT = 8;

    public enum HuntStage {
        SEARCH_AND_HUNT("1. البحث والصيد"),
        COLLECT_DROPS("2. تجميع الغنائم"),
        PATROL_SWEEP("3. الدورية والتمشيط"),
        DEPOSIT_LOOT("4. تفريغ الغنائم في الصندوق"),
        SLEEP("5. النوم في السرير");

        private final String arabicName;

        HuntStage(String arabicName) {
            this.arabicName = arabicName;
        }

        public String getArabicName() {
            return arabicName;
        }
    }

    private final BotNavigator huntNavigator = new BotNavigator();
    private final RangedCombatHelper rangedHelper = new RangedCombatHelper();

    private boolean running = false;
    private HuntConfigManager.HuntMode activeMode = HuntConfigManager.HuntMode.PATROL;
    private HuntStage currentStage = HuntStage.SEARCH_AND_HUNT;
    private BlockPos homePos = null;
    private String lastContextSignature = null;

    private UUID lockedTargetUUID = null;
    private int targetLockTicks = 0;
    private UUID lastAttackedTargetUUID = null;
    private BlockPos lastAttackedTargetPos = null;
    private final Map<UUID, Integer> unreachableTargets = new HashMap<>();
    private final Map<UUID, Integer> selfDefenseAttackers = new HashMap<>();
    private BlockPos lastKillCenter = null;

    private ItemEntity lockedDrop = null;
    private int dropStuckTicks = 0;
    private int pendingDropWaitTicks = 0;
    private int ignoredDropResetTicks = 0;
    private final Set<Integer> ignoredDropIds = new HashSet<>();

    private int patrolSectorIndex = 0;
    private int zonePatrolIndex = 0;
    private BlockPos currentPatrolWaypoint = null;
    private int patrolWaypointTicks = 0;
    private int waitCooldownTicks = 0;

    private final Set<BlockPos> visitedDepositChests = new HashSet<>();
    private BlockPos targetDepositChestPos = null;
    private int depositTimeoutTicks = 0;
    private int depositCloseDelay = 0;
    private int lastDepositedCount = 0;
    private boolean fullChestWarned = false;

    private boolean isEating = false;
    private int eatingTicks = 0;
    private int lastFoodLevel = -1;
    private int hungryAlertCooldown = 0;
    private int armorCheckCooldown = 0;

    private int nightTicks = 0;
    private BlockPos targetBedPos = null;
    private boolean bedAlertSent = false;
    private int killCount = 0;

    private MobHuntController() {}

    public static MobHuntController getInstance() {
        return INSTANCE;
    }

    public boolean isHunting() {
        return running;
    }

    public HuntStage getCurrentStage() {
        return currentStage;
    }

    public HuntConfigManager.HuntMode getActiveMode() {
        return activeMode;
    }

    public BlockPos getHomePos() {
        return homePos;
    }

    public int getKillCount() {
        return killCount;
    }

    public void start(Minecraft client, HuntConfigManager.HuntMode requestedMode) {
        if (client == null || client.player == null || client.level == null) {
            return;
        }
        HuntConfigManager config = HuntConfigManager.getInstance();
        config.updateContext(client);

        if (config.getTargetMobIds().isEmpty()) {
            sendChatMessage(client, "§c[Bot - Hunt] قائمة الموبات المستهدفة فارغة! أضف أهدافاً أولاً مثل: §e/bot hunt target add animals");
            return;
        }

        List<HuntZone> activeZones = config.getActiveZones();
        HuntConfigManager.HuntMode resolvedMode = requestedMode;
        if (resolvedMode == null) {
            resolvedMode = !activeZones.isEmpty()
                ? HuntConfigManager.HuntMode.ZONE
                : HuntConfigManager.HuntMode.PATROL;
        }
        if (resolvedMode == HuntConfigManager.HuntMode.ZONE && activeZones.isEmpty()) {
            sendChatMessage(client, "§c[Bot - Hunt] لا توجد مناطق صيد مفعلة في هذا البُعد! أنشئ منطقة عبر /bot hunt area أو شغّل وضع الدورية: §b/bot hunt start patrol");
            return;
        }

        if (FarmBotController.getInstance().isFarming()) {
            FarmBotController.getInstance().stop(client);
        }
        if (BotFollowController.getInstance().isFollowing()) {
            BotFollowController.getInstance().stopFollowing(client);
        }
        FightBotController.getInstance().stopDefending(client);

        resetRuntimeState(client);
        this.running = true;
        this.activeMode = resolvedMode;
        this.homePos = client.player.blockPosition().immutable();
        this.lastContextSignature = buildContextSignature(client);
        this.killCount = 0;

        sendChatMessage(client, "§a[Bot - Hunt] تم تشغيل وضع تجميع وصيد الموبات (§e" + activeMode.getArabicName() + "§a)!"
            + " §7(الأهداف: §b" + config.getTargetMobIds().size() + "§7)");
        if (!BotGlobalManager.getInstance().isBotEnabled()) {
            sendChatMessage(client, "§e[Bot - Hunt] تنبيه: الوضع العام للمود متوقف حالياً (/bot mode off). فعّله عبر: §b/bot mode on");
        }
        BotGlobalManager.getInstance().clearUserControlPause();
    }

    /**
     * تعليق نشاط الصيد مؤقتاً عند تدخل المستخدم مع إبقاء الصيد مفعلاً للاستئناف بعد 10 ثوانٍ.
     */
    public void pauseForUserControl(Minecraft client) {
        if (!running) return;
        huntNavigator.reset(client);
        rangedHelper.reset(client);
        isEating = false;
        eatingTicks = 0;
        lastFoodLevel = -1;
        lockedTargetUUID = null;
        targetLockTicks = 0;
        lastAttackedTargetUUID = null;
        lastAttackedTargetPos = null;
        pendingDropWaitTicks = 0;
        currentPatrolWaypoint = null;
        patrolWaypointTicks = 0;
        waitCooldownTicks = 0;
    }

    public void stop(Minecraft client) {
        if (!running) {
            return;
        }
        running = false;
        resetRuntimeState(client);
        homePos = null;
        lastContextSignature = null;
        sendChatMessage(client, "§c[Bot - Hunt] تم إيقاف وضع تجميع وصيد الموبات وتحرير التحكم.");
    }

    private void resetRuntimeState(Minecraft client) {
        huntNavigator.reset(client);
        rangedHelper.reset(client);
        if (client != null && client.options != null) {
            BotEquipmentHelper.releaseMovementKeys(client);
            client.options.keyUse.setDown(false);
        }
        LocalPlayer player = client != null ? client.player : null;
        if (player != null && client.gameMode != null && player.isUsingItem()) {
            client.gameMode.releaseUsingItem(player);
            player.stopUsingItem();
        }

        currentStage = HuntStage.SEARCH_AND_HUNT;
        lockedTargetUUID = null;
        targetLockTicks = 0;
        lastAttackedTargetUUID = null;
        lastAttackedTargetPos = null;
        unreachableTargets.clear();
        selfDefenseAttackers.clear();
        lastKillCenter = null;
        lockedDrop = null;
        dropStuckTicks = 0;
        pendingDropWaitTicks = 0;
        ignoredDropResetTicks = 0;
        ignoredDropIds.clear();
        patrolSectorIndex = 0;
        zonePatrolIndex = 0;
        currentPatrolWaypoint = null;
        patrolWaypointTicks = 0;
        waitCooldownTicks = 0;
        visitedDepositChests.clear();
        targetDepositChestPos = null;
        depositTimeoutTicks = 0;
        depositCloseDelay = 0;
        lastDepositedCount = 0;
        fullChestWarned = false;
        isEating = false;
        eatingTicks = 0;
        lastFoodLevel = -1;
        hungryAlertCooldown = 0;
        armorCheckCooldown = 0;
        nightTicks = 0;
        targetBedPos = null;
        bedAlertSent = false;
    }

    private String buildContextSignature(Minecraft client) {
        HuntConfigManager config = HuntConfigManager.getInstance();
        config.updateContext(client);
        return config.getCurrentWorldKey() + "|" + config.getCurrentDimensionId();
    }

    public void onTick(Minecraft client) {
        if (!running || client == null) return;
        if (client.isPaused()) {
            BotEquipmentHelper.releaseMovementKeys(client);
            return;
        }

        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        MultiPlayerGameMode gameMode = client.gameMode;
        if (player == null || level == null || gameMode == null || player.isDeadOrDying()) {
            huntNavigator.reset(client);
            rangedHelper.reset(client);
            return;
        }

        String currentContextSig = buildContextSignature(client);
        if (lastContextSignature != null && !lastContextSignature.equals(currentContextSig)) {
            sendChatMessage(client, "§e[Bot - Hunt] تم تغيير العالم/البُعد؛ إيقاف وضع الصيد تلقائياً لحماية البوت.");
            stop(client);
            return;
        }
        lastContextSignature = currentContextSig;

        tickTimers();
        huntNavigator.tickCooldowns();

        // 0. حراسة التنفس تحت الماء قبل أي حركة أو نوم
        if (player.isSleeping() && BotNavigator.isEyesInWater(level, player) && BotNavigator.getAirRatio(player) <= 0.35) {
            player.stopSleepInBed(true, true);
        }
        if (!player.isSleeping() && huntNavigator.handleUrgentWaterBreathing(client, player, level, gameMode)) {
            rangedHelper.reset(client, player, gameMode);
            return;
        }

        // إكمال دورة إغلاق بوابة الحظيرة/الباب خلف البوت لحماية الحيوانات من الخروج
        if (huntNavigator.isBusyClosingDoor()) {
            BlockPos closeGoal = lastKillCenter != null ? lastKillCenter : homePos;
            huntNavigator.navigateTo(
                client, player, level, gameMode,
                closeGoal != null ? Vec3.atCenterOf(closeGoal) : player.position(),
                false, 0.5, isEating, HUNT_ROTATION_SPEED, true, 1200,
                FarmPathfinder.PathOptions.COMBAT_FOLLOW, false, true, null
            );
            return;
        }

        // صيانة التوتم والدروع
        boolean checkArmorNow = armorCheckCooldown <= 0 && !player.isUsingItem() && !rangedHelper.isUsingRanged();
        if (checkArmorNow) {
            armorCheckCooldown = 25;
        }
        boolean totemEmergency = BotEquipmentHelper.maintainCombatSurvivalGear(player, gameMode, checkArmorNow, true);

        // مراقبة المهاجمين المباشرين للدفاع عن النفس حتى لو لم يكونوا ضمن قائمة الصيد
        trackSelfDefenseAttackers(player);

        // معالجة مرحلة النوم إن كانت نشطة
        if (currentStage == HuntStage.SLEEP) {
            handleSleepStage(client, player, level, gameMode);
            return;
        }

        // مراقبة الليل للاستيقاظ أو التوجه للنوم حسب إعدادات القائمة
        HuntConfigManager config = HuntConfigManager.getInstance();
        boolean isNight = FarmAreaManager.isBedDimensionSupported(level) && (level.isThundering() || level.getSkyDarken() >= 4);
        if (isNight && shouldSleepAtNight(config)) {
            nightTicks++;
            if (nightTicks >= 400 && currentStage != HuntStage.DEPOSIT_LOOT && currentStage != HuntStage.COLLECT_DROPS) {
                currentStage = HuntStage.SLEEP;
                rangedHelper.reset(client, player, gameMode);
                huntNavigator.reset(client);
                return;
            }
        } else {
            nightTicks = 0;
            bedAlertSent = false;
        }

        // معالجة مرحلة تفريغ الغنائم في الصندوق
        if (currentStage == HuntStage.DEPOSIT_LOOT) {
            handleDepositLootStage(client, player, level, gameMode, config);
            return;
        }

        // فحص امتلاء الحقيبة للتوجه للصندوق
        if (isInventoryFull(player) && currentStage != HuntStage.COLLECT_DROPS) {
            if (tryStartDepositCycle(client, player, level, config)) {
                return;
            }
        }

        // تناول الطعام عند الحاجة (من قائمة الطعام المسموحة فقط)
        if (!totemEmergency && currentStage != HuntStage.DEPOSIT_LOOT && !rangedHelper.isUsingRanged()) {
            if (handleHuntingEating(client, player, gameMode)) {
                return;
            }
        }

        // استقرار سريع بين الدورات
        if (waitCooldownTicks > 0) {
            waitCooldownTicks--;
            BotNavigator.maintainWaterBuoyancy(client, player, level, null);
            return;
        }

        // تنفيذ المرحلة الحالية للصيد
        switch (currentStage) {
            case COLLECT_DROPS -> handleCollectDropsStage(client, player, level, config);
            case PATROL_SWEEP -> handlePatrolSweepStage(client, player, level, gameMode, config);
            default -> handleSearchAndHuntStage(client, player, level, gameMode, config);
        }
    }

    private void tickTimers() {
        if (targetLockTicks > 0) targetLockTicks--;
        if (armorCheckCooldown > 0) armorCheckCooldown--;
        if (hungryAlertCooldown > 0) hungryAlertCooldown--;
        ignoredDropResetTicks++;
        if (ignoredDropResetTicks >= 600) {
            ignoredDropResetTicks = 0;
            ignoredDropIds.clear();
        }
        decrementExpiringMap(unreachableTargets);
        decrementExpiringMap(selfDefenseAttackers);
    }

    private static void decrementExpiringMap(Map<UUID, Integer> map) {
        var it = map.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            int next = entry.getValue() - 1;
            if (next <= 0) {
                it.remove();
            } else {
                entry.setValue(next);
            }
        }
    }

    private void trackSelfDefenseAttackers(LocalPlayer player) {
        if (player.hurtTime > 0 && player.getLastHurtByMob() != null) {
            LivingEntity attacker = player.getLastHurtByMob();
            if (attacker != player && attacker.isAlive() && !attacker.isRemoved() && !isFriendlyProtected(attacker)) {
                selfDefenseAttackers.put(attacker.getUUID(), 300);
            }
        }
    }

    private boolean isFriendlyProtected(LivingEntity entity) {
        if (entity == null) return true;
        if (entity instanceof Player p) {
            return BotFriendManager.getInstance().isFriend(p)
                || BotFollowController.getInstance().isFollowingPlayer(p);
        }
        if (entity instanceof net.minecraft.world.entity.decoration.ArmorStand) return true;
        if (HuntConfigManager.getInstance().isProtectNamedAndTamed()) {
            if (entity.hasCustomName()) return true;
            if (entity instanceof TamableAnimal tamed && tamed.isTame()) return true;
        }
        return false;
    }

    private boolean shouldSleepAtNight(HuntConfigManager config) {
        return switch (config.getNightBehavior()) {
            case SLEEP -> true;
            case STAY_AWAKE -> false;
            case AUTO -> !config.hasNightMonsterTargets();
        };
    }

    // =========================================================================
    // 1. البحث عن الهدف الموزون والصيد والقتال
    // =========================================================================

    private void handleSearchAndHuntStage(
            Minecraft client,
            LocalPlayer player,
            ClientLevel level,
            MultiPlayerGameMode gameMode,
            HuntConfigManager config) {
        if (tryTransitionToPendingKillDrops(client, player, level, gameMode, config)) {
            return;
        }

        LivingEntity target = findBestTarget(player, level, config);
        if (target == null) {
            rangedHelper.reset(client, player, gameMode);
            lockedTargetUUID = null;

            // قبل التجول أو الانتظار: التقط أي دروب متناثرة قريبة
            ItemEntity nearbyDrop = findNearestDrop(player, level, config);
            if (nearbyDrop != null) {
                lockedDrop = nearbyDrop;
                dropStuckTicks = 0;
                pendingDropWaitTicks = 6;
                currentStage = HuntStage.COLLECT_DROPS;
                return;
            }

            if (activeMode == HuntConfigManager.HuntMode.PATROL
                    || (activeMode == HuntConfigManager.HuntMode.ZONE && !config.getActiveZones().isEmpty())) {
                currentStage = HuntStage.PATROL_SWEEP;
                patrolWaypointTicks = 0;
                return;
            }

            if (activeMode == HuntConfigManager.HuntMode.SPOT && homePos != null
                    && player.distanceToSqr(homePos.getX() + 0.5, homePos.getY(), homePos.getZ() + 0.5) > 2.0 * 2.0) {
                huntNavigator.navigateTo(
                    client, player, level, gameMode, Vec3.atCenterOf(homePos),
                    false, 1.0, false, HUNT_ROTATION_SPEED, true, 1200,
                    FarmPathfinder.PathOptions.COMBAT_FOLLOW, false, true, null
                );
                return;
            }

            huntNavigator.clearPathOnly();
            BotEquipmentHelper.releaseMovementKeys(client);
            BotNavigator.maintainWaterBuoyancy(client, player, level, null);
            waitCooldownTicks = 10;
            return;
        }

        executeHuntEngagement(client, player, level, gameMode, config, target);
    }

    private boolean tryTransitionToPendingKillDrops(
            Minecraft client,
            LocalPlayer player,
            ClientLevel level,
            MultiPlayerGameMode gameMode,
            HuntConfigManager config) {
        if (lastAttackedTargetUUID == null) {
            return false;
        }
        LivingEntity attacked = findEntityByUuid(level, player, lastAttackedTargetUUID, 48.0);
        if (attacked != null && attacked.isAlive() && !attacked.isRemoved() && !attacked.isDeadOrDying() && attacked.getHealth() > 0) {
            return false;
        }
        BlockPos killPos = attacked != null
            ? attacked.blockPosition()
            : (lastAttackedTargetPos != null ? lastAttackedTargetPos : player.blockPosition());
        killCount++;
        lastKillCenter = killPos;
        unreachableTargets.remove(lastAttackedTargetUUID);
        selfDefenseAttackers.remove(lastAttackedTargetUUID);
        if (lockedTargetUUID != null && lockedTargetUUID.equals(lastAttackedTargetUUID)) {
            lockedTargetUUID = null;
            targetLockTicks = 0;
        }
        lastAttackedTargetUUID = null;
        lastAttackedTargetPos = null;
        rangedHelper.reset(client, player, gameMode);
        currentStage = HuntStage.COLLECT_DROPS;
        pendingDropWaitTicks = 30;
        dropStuckTicks = 0;
        lockedDrop = null;
        return true;
    }

    private LivingEntity findBestTarget(LocalPlayer player, ClientLevel level, HuntConfigManager config) {
        List<HuntZone> activeZones = config.getActiveZones();
        if (activeMode == HuntConfigManager.HuntMode.ZONE && activeZones.isEmpty()) {
            return null;
        }

        Map<String, Map<EntityType<?>, Integer>> zoneAdultCounts = activeMode == HuntConfigManager.HuntMode.ZONE
            ? countAdultsPerZone(level, activeZones)
            : Map.of();

        double maxSearchRadius;
        if (activeMode == HuntConfigManager.HuntMode.ZONE) {
            double maxZoneDist = 64.0;
            for (HuntZone z : activeZones) {
                BlockPos c = z.getCenterPos();
                double d = Math.sqrt(player.distanceToSqr(c.getX() + 0.5, player.getY(), c.getZ() + 0.5)) + 24.0;
                if (d > maxZoneDist) {
                    maxZoneDist = d;
                }
            }
            maxSearchRadius = Math.min(128.0, maxZoneDist);
        } else if (activeMode == HuntConfigManager.HuntMode.PATROL) {
            maxSearchRadius = config.getPatrolRadius();
        } else {
            maxSearchRadius = 10.0;
        }

        // 1. فحص الهدف المثبت حالياً أولاً لمنع التشتت
        if (lockedTargetUUID != null && targetLockTicks > 0) {
            LivingEntity current = findEntityByUuid(level, player, lockedTargetUUID, maxSearchRadius + 8.0);
            if (isValidHuntCandidate(player, current, config, activeZones, zoneAdultCounts, maxSearchRadius)) {
                return current;
            }
            lockedTargetUUID = null;
        }

        // 2. مسح الكيانات بنظام الموجات الثلاث (<= 14 ثم <= 32 ثم <= maxSearchRadius)
        double[] rings = {14.0, 32.0, Math.max(32.0, maxSearchRadius)};
        AABB scanBox = player.getBoundingBox().inflate(maxSearchRadius + 4.0);
        List<LivingEntity> pool = level.getEntitiesOfClass(LivingEntity.class, scanBox);

        for (double ringRadius : rings) {
            LivingEntity bestInRing = null;
            double bestScore = Double.MAX_VALUE;

            for (LivingEntity candidate : pool) {
                if (!isValidHuntCandidate(player, candidate, config, activeZones, zoneAdultCounts, maxSearchRadius)) {
                    continue;
                }
                double dx = candidate.getX() - player.getX();
                double dz = candidate.getZ() - player.getZ();
                double distXZ = Math.sqrt(dx * dx + dz * dz);
                if (distXZ > ringRadius) {
                    continue;
                }

                double dy = Math.abs(candidate.getY() - player.getY());
                boolean selfDefense = selfDefenseAttackers.containsKey(candidate.getUUID());
                boolean hasLos = player.hasLineOfSight(candidate);

                double score = distXZ + (dy * 3.0) + (hasLos ? 0.0 : 8.0) - (selfDefense ? 10.0 : 0.0);
                if (score < bestScore) {
                    bestScore = score;
                    bestInRing = candidate;
                }
            }

            if (bestInRing != null) {
                lockedTargetUUID = bestInRing.getUUID();
                targetLockTicks = 40;
                return bestInRing;
            }
        }

        return null;
    }

    private Map<String, Map<EntityType<?>, Integer>> countAdultsPerZone(ClientLevel level, List<HuntZone> activeZones) {
        Map<String, Map<EntityType<?>, Integer>> counts = new HashMap<>();
        for (HuntZone zone : activeZones) {
            AABB box = new AABB(
                zone.getMinPos().getX(), zone.getMinPos().getY(), zone.getMinPos().getZ(),
                zone.getMaxPos().getX() + 1.0, zone.getMaxPos().getY() + 1.0, zone.getMaxPos().getZ() + 1.0
            );
            Map<EntityType<?>, Integer> typeMap = new HashMap<>();
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
                if (entity.isAlive() && !entity.isRemoved() && !entity.isBaby() && zone.contains(entity.blockPosition())) {
                    typeMap.merge(entity.getType(), 1, Integer::sum);
                }
            }
            counts.put(zone.getName(), typeMap);
        }
        return counts;
    }

    private boolean isValidHuntCandidate(
            LocalPlayer player,
            LivingEntity entity,
            HuntConfigManager config,
            List<HuntZone> activeZones,
            Map<String, Map<EntityType<?>, Integer>> zoneAdultCounts,
            double maxSearchRadius) {
        if (entity == null || entity == player || !entity.isAlive() || entity.isRemoved() || entity.isDeadOrDying()) {
            return false;
        }
        if (isFriendlyProtected(entity)) {
            return false;
        }

        boolean selfDefense = selfDefenseAttackers.containsKey(entity.getUUID());
        if (!selfDefense && !config.isTargetedMob(entity)) {
            return false;
        }
        if (!selfDefense && config.isSkipBabies() && entity.isBaby()) {
            return false;
        }

        if (unreachableTargets.containsKey(entity.getUUID())
                && !(BotEquipmentHelper.getEyeToHitboxDistance(player, entity) <= 2.95 && player.hasLineOfSight(entity))) {
            return false;
        }

        if (activeMode == HuntConfigManager.HuntMode.ZONE) {
            HuntZone containingZone = null;
            for (HuntZone z : activeZones) {
                if (z.contains(entity.blockPosition())) {
                    containingZone = z;
                    break;
                }
            }
            if (containingZone == null && !selfDefense) {
                return false;
            }
            if (containingZone != null && !selfDefense && config.getMinKeepInZones() > 0 && isProtectedBreedingAnimal(entity)) {
                int adultCount = zoneAdultCounts.getOrDefault(containingZone.getName(), Map.of())
                    .getOrDefault(entity.getType(), 0);
                if (adultCount <= config.getMinKeepInZones()) {
                    return false;
                }
            }
        } else if (activeMode == HuntConfigManager.HuntMode.PATROL) {
            BlockPos anchor = homePos != null ? homePos : player.blockPosition();
            double dx = entity.getX() - (anchor.getX() + 0.5);
            double dz = entity.getZ() - (anchor.getZ() + 0.5);
            double dy = Math.abs(entity.getY() - anchor.getY());
            if (!selfDefense && (dx * dx + dz * dz > maxSearchRadius * maxSearchRadius || dy > 24.0)) {
                return false;
            }
        } else if (activeMode == HuntConfigManager.HuntMode.SPOT) {
            BlockPos anchor = homePos != null ? homePos : player.blockPosition();
            double distFromSpot = entity.distanceToSqr(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
            if (!selfDefense && distFromSpot > 10.0 * 10.0) {
                return false;
            }
            if (!selfDefense && BotEquipmentHelper.getEyeToHitboxDistance(player, entity) > 3.2
                    && !(BotEquipmentHelper.hasUsableRangedWeapon(player) && player.hasLineOfSight(entity))) {
                return false;
            }
        }

        return true;
    }

    private static boolean isProtectedBreedingAnimal(LivingEntity entity) {
        return entity instanceof Animal
            || entity.getType().getCategory() == net.minecraft.world.entity.MobCategory.CREATURE;
    }

    private void executeHuntEngagement(
            Minecraft client,
            LocalPlayer player,
            ClientLevel level,
            MultiPlayerGameMode gameMode,
            HuntConfigManager config,
            LivingEntity target) {
        double distToTarget = player.distanceTo(target);
        double eyeDist = BotEquipmentHelper.getEyeToHitboxDistance(player, target);
        boolean inMeleeReach = eyeDist <= 2.95 && distToTarget <= 3.6;

        // 1. استخدام السلاح بعيد المدى فقط للوحوش المعادية البعيدة أو في وضع SPOT أو عند تعذر الوصول البري،
        // أما الحيوانات المسالمة فيُفضل مطاردتها بالسيف مباشرة لاكتساب Looting والبقاء فوق الغنائم
        boolean unreachableMelee = unreachableTargets.containsKey(target.getUUID());
        boolean preferRanged = activeMode == HuntConfigManager.HuntMode.SPOT
            || unreachableMelee
            || (target instanceof Monster && distToTarget >= 5.5);
        if (!inMeleeReach && preferRanged) {
            RangedCombatHelper.RangedStatus rangedStatus = rangedHelper.tickRangedAttack(
                client, player, level, gameMode, target, null, false, unreachableMelee
            );
            if (rangedStatus == RangedCombatHelper.RangedStatus.CHARGING
                    || rangedStatus == RangedCombatHelper.RangedStatus.FIRED) {
                huntNavigator.clearPathOnly();
                if (rangedStatus == RangedCombatHelper.RangedStatus.FIRED) {
                    lastAttackedTargetUUID = target.getUUID();
                    lastAttackedTargetPos = target.blockPosition();
                }
                checkHuntTargetDefeated(client, player, level, gameMode, target);
                return;
            }
        } else {
            rangedHelper.reset(client, player, gameMode);
        }

        // 2. تجهيز السلاح القريب المناسب (الأقوى + Looting مع حماية الحلفاء من Sweeping Edge)
        boolean useSword = selectHuntMeleeWeapon(player, level, gameMode, config, target);

        if (inMeleeReach) {
            huntNavigator.clearPathOnly();
            BotEquipmentHelper.releaseMovementKeys(client);
            BotEquipmentHelper.smoothLookAt(player, target.getX(), target.getEyeY() - 0.15, target.getZ(), 48.0f);
            BotNavigator.maintainWaterBuoyancy(client, player, level, target);

            float charge = player.getAttackStrengthScale(0.0f);
            float reqCharge = useSword ? 0.85f : 0.90f;
            if (charge >= reqCharge) {
                if (player.isUsingItem() && player.getUseItem().is(Items.SHIELD)) {
                    gameMode.releaseUsingItem(player);
                    player.stopUsingItem();
                }
                client.options.keyUse.setDown(false);
                gameMode.attack(player, target);
                player.swing(InteractionHand.MAIN_HAND);
                lastKillCenter = target.blockPosition();
                lastAttackedTargetUUID = target.getUUID();
                lastAttackedTargetPos = target.blockPosition();

                // هروب سريع للخلف إن كان الهدف Creeper متفجر قريب جداً
                if (target instanceof Creeper creeper && creeper.getSwellDir() > 0 && distToTarget < 4.5) {
                    Vec3 away = player.position().subtract(target.position()).normalize();
                    client.options.keyDown.setDown(false);
                    float yaw = (float) (Math.atan2(away.z, away.x) * 180.0 / Math.PI) - 90.0f;
                    player.setYRot(yaw);
                    client.options.keyUp.setDown(true);
                    client.options.keySprint.setDown(true);
                }

                checkHuntTargetDefeated(client, player, level, gameMode, target);
            }
            return;
        }

        // 3. في وضع SPOT الثابت لا نطارد أهدافاً بعيدة مشياً
        if (activeMode == HuntConfigManager.HuntMode.SPOT) {
            BotEquipmentHelper.releaseMovementKeys(client);
            BotNavigator.maintainWaterBuoyancy(client, player, level, target);
            return;
        }

        // 4. التحرك نحو الهدف عبر BotNavigator مع إغلاق بوابات الحظائر خلف البوت تلقائياً
        BotNavigator.NavResult navRes = huntNavigator.navigateTo(
            client, player, level, gameMode, target.position(), true, 1.55,
            false, HUNT_ROTATION_SPEED, true, 4500,
            FarmPathfinder.PathOptions.COMBAT_FOLLOW, false, true, null
        );
        if (navRes == BotNavigator.NavResult.BLOCKED_UNREACHABLE) {
            RangedCombatHelper.RangedStatus fallbackRanged = rangedHelper.tickRangedAttack(
                client, player, level, gameMode, target, null, false, true
            );
            if (fallbackRanged == RangedCombatHelper.RangedStatus.CHARGING
                    || fallbackRanged == RangedCombatHelper.RangedStatus.FIRED) {
                huntNavigator.clearPathOnly();
                if (fallbackRanged == RangedCombatHelper.RangedStatus.FIRED) {
                    lastAttackedTargetUUID = target.getUUID();
                    lastAttackedTargetPos = target.blockPosition();
                }
                return;
            }
            unreachableTargets.put(target.getUUID(), 45);
            if (target.getUUID().equals(lastAttackedTargetUUID)) {
                lastAttackedTargetUUID = null;
                lastAttackedTargetPos = null;
            }
            lockedTargetUUID = null;
            targetLockTicks = 0;
            huntNavigator.clearPathOnly();
        }
    }

    private boolean selectHuntMeleeWeapon(
            LocalPlayer player,
            ClientLevel level,
            MultiPlayerGameMode gameMode,
            HuntConfigManager config,
            LivingEntity primaryTarget) {
        boolean prioritizeLooting = true;
        int swordScore = BotEquipmentHelper.getBestSwordScore(player, prioritizeLooting);
        int axeScore = BotEquipmentHelper.getBestAxeScore(player, false);

        boolean friendNear = hasProtectedEntityNearSweep(level, player, primaryTarget);
        boolean chooseSword = switch (config.getWeaponPreference()) {
            case SWORD -> swordScore > 0;
            case AXE -> axeScore <= 0 && swordScore > 0;
            case AUTO_SMART -> {
                if (friendNear && axeScore > 0) {
                    yield false;
                }
                yield swordScore > 0 && (swordScore >= axeScore);
            }
        };

        if (chooseSword) {
            if (!BotEquipmentHelper.ensureBestSwordInMainHand(player, gameMode, prioritizeLooting, 0)) {
                BotEquipmentHelper.ensureBestAxeInMainHand(player, gameMode, false, 0);
                return false;
            }
            return true;
        } else {
            if (!BotEquipmentHelper.ensureBestAxeInMainHand(player, gameMode, false, 0)) {
                return BotEquipmentHelper.ensureBestSwordInMainHand(player, gameMode, prioritizeLooting, 0);
            }
            return false;
        }
    }

    private boolean hasProtectedEntityNearSweep(ClientLevel level, LocalPlayer player, LivingEntity primaryTarget) {
        AABB box = player.getBoundingBox().inflate(4.0);
        for (LivingEntity le : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (le == player || le == primaryTarget || !le.isAlive() || le.isRemoved()) continue;
            if (isFriendlyProtected(le) || (le.isBaby() && HuntConfigManager.getInstance().isSkipBabies())) {
                if (le.distanceTo(primaryTarget) <= 2.6 || le.distanceTo(player) <= 3.2) {
                    return true;
                }
            }
        }
        return false;
    }

    private void checkHuntTargetDefeated(
            Minecraft client,
            LocalPlayer player,
            ClientLevel level,
            MultiPlayerGameMode gameMode,
            LivingEntity target) {
        if (!target.isAlive() || target.isRemoved() || target.isDeadOrDying() || target.getHealth() <= 0) {
            killCount++;
            lastKillCenter = target.blockPosition();
            unreachableTargets.remove(target.getUUID());
            selfDefenseAttackers.remove(target.getUUID());
            if (lockedTargetUUID != null && lockedTargetUUID.equals(target.getUUID())) {
                lockedTargetUUID = null;
                targetLockTicks = 0;
            }
            if (target.getUUID().equals(lastAttackedTargetUUID)) {
                lastAttackedTargetUUID = null;
                lastAttackedTargetPos = null;
            }
            rangedHelper.reset(client, player, gameMode);
            currentStage = HuntStage.COLLECT_DROPS;
            pendingDropWaitTicks = 30;
            dropStuckTicks = 0;
            lockedDrop = null;
        }
    }

    // =========================================================================
    // 2. تجميع الغنائم الساقطة (COLLECT_DROPS)
    // =========================================================================

    private void handleCollectDropsStage(
            Minecraft client,
            LocalPlayer player,
            ClientLevel level,
            HuntConfigManager config) {
        // إذا كان هناك عدو فوري يهاجم البوت أثناء التجميع، نعطي الأولوية للدفاع/الصيد القريب
        if (!selfDefenseAttackers.isEmpty()) {
            pendingDropWaitTicks = 0;
            currentStage = HuntStage.SEARCH_AND_HUNT;
            return;
        }

        if (lockedDrop != null && (!lockedDrop.isAlive() || lockedDrop.isRemoved() || ignoredDropIds.contains(lockedDrop.getId()))) {
            boolean wasCollected = !lockedDrop.isAlive() || lockedDrop.isRemoved();
            lockedDrop = null;
            dropStuckTicks = 0;
            if (wasCollected) {
                pendingDropWaitTicks = Math.max(pendingDropWaitTicks, 6);
            }
        }
        if (lockedDrop == null) {
            lockedDrop = findNearestDrop(player, level, config);
            dropStuckTicks = 0;
        }

        if (lockedDrop == null) {
            if (pendingDropWaitTicks > 0) {
                // الاقتراب لموقع القتل أثناء انتظار انتهاء أنيميشن الموت وسقوط اللوت (~20 تكة)
                if (lastKillCenter != null && activeMode != HuntConfigManager.HuntMode.SPOT) {
                    double killDistSq = player.distanceToSqr(lastKillCenter.getX() + 0.5, lastKillCenter.getY(), lastKillCenter.getZ() + 0.5);
                    if (killDistSq > 3.5 * 3.5 && killDistSq <= 28.0 * 28.0 && dropStuckTicks < 75) {
                        dropStuckTicks++;
                        pendingDropWaitTicks = Math.max(pendingDropWaitTicks, 15);
                        BotNavigator.NavResult walkRes = huntNavigator.navigateTo(
                            client, player, level, client.gameMode, Vec3.atBottomCenterOf(lastKillCenter),
                            true, 2.0, false, HUNT_ROTATION_SPEED, true, 3000,
                            FarmPathfinder.PathOptions.COMBAT_FOLLOW, false, true, null
                        );
                        if (walkRes != BotNavigator.NavResult.BLOCKED_UNREACHABLE) {
                            return;
                        }
                    }
                }
                pendingDropWaitTicks--;
                huntNavigator.clearPathOnly();
                BotEquipmentHelper.releaseMovementKeys(client);
                BotNavigator.maintainWaterBuoyancy(client, player, level, null);
                return;
            }
            currentStage = HuntStage.SEARCH_AND_HUNT;
            huntNavigator.clearPathOnly();
            return;
        }

        if (!canInventoryAcceptItem(player, lockedDrop.getItem())) {
            if (isInventoryFull(player) && tryStartDepositCycle(client, player, level, config)) {
                lockedDrop = null;
                dropStuckTicks = 0;
                pendingDropWaitTicks = 0;
                return;
            }
            ignoredDropIds.add(lockedDrop.getId());
            lockedDrop = null;
            dropStuckTicks = 0;
            return;
        }

        double dx = lockedDrop.getX() - player.getX();
        double dz = lockedDrop.getZ() - player.getZ();
        double dy = Math.abs(lockedDrop.getY() - player.getY());
        double horizDistSq = dx * dx + dz * dz;
        double distSq = horizDistSq + dy * dy;

        // لا نتوقف بعيداً عن اللوت؛ نتوقف فقط عند ملامسته تماماً (< 0.40 بلوكة) لإتمام الالتقاط الآلي
        if (distSq <= 0.40 * 0.40) {
            dropStuckTicks++;
            BotEquipmentHelper.releaseMovementKeys(client);
            BotNavigator.maintainWaterBuoyancy(client, player, level, null);
            if (dropStuckTicks > 40) {
                ignoredDropIds.add(lockedDrop.getId());
                lockedDrop = null;
                dropStuckTicks = 0;
            }
            return;
        }

        dropStuckTicks++;
        if (dropStuckTicks > 95) {
            ignoredDropIds.add(lockedDrop.getId());
            lockedDrop = null;
            dropStuckTicks = 0;
            return;
        }

        if (activeMode == HuntConfigManager.HuntMode.SPOT && homePos != null
                && lockedDrop.distanceToSqr(homePos.getX() + 0.5, homePos.getY(), homePos.getZ() + 0.5) > 5.0 * 5.0) {
            ignoredDropIds.add(lockedDrop.getId());
            lockedDrop = null;
            return;
        }

        // خطوة مباشرة سريعة نحو الدروب إذا كان ملاصقاً ومكشوفاً بدون سياج أو حفرة لتسريع التقاطه
        if (horizDistSq <= 2.4 * 2.4 && dy <= 1.6
                && BotNavigator.isDirectGroundStepSafe(level, player.position(), lockedDrop.position(), 2.8)) {
            huntNavigator.clearPathOnly();
            BotEquipmentHelper.smoothLookAt(player, lockedDrop.getX(), lockedDrop.getY(), lockedDrop.getZ(), HUNT_ROTATION_SPEED);
            client.options.keyUp.setDown(true);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keySprint.setDown(false);
            player.setSprinting(false);
            BotNavigator.maintainWaterBuoyancy(client, player, level, null);
            return;
        }

        BotNavigator.NavResult navRes = huntNavigator.navigateTo(
            client, player, level, client.gameMode, lockedDrop.position(), false, 0.25,
            false, HUNT_ROTATION_SPEED, true, 2500,
            FarmPathfinder.PathOptions.COMBAT_FOLLOW, false, true, null
        );
        if (navRes == BotNavigator.NavResult.BLOCKED_UNREACHABLE) {
            ignoredDropIds.add(lockedDrop.getId());
            lockedDrop = null;
            dropStuckTicks = 0;
        }
    }

    private boolean canInventoryAcceptItem(LocalPlayer player, ItemStack dropStack) {
        if (player == null || dropStack == null || dropStack.isEmpty()) return false;
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack existing = inv.getItem(i);
            if (existing.isEmpty()) {
                return true;
            }
            if (existing.is(dropStack.getItem())
                    && existing.getCount() < existing.getMaxStackSize()
                    && existing.getEnchantments().equals(dropStack.getEnchantments())) {
                return true;
            }
        }
        return false;
    }

    private ItemEntity findNearestDrop(LocalPlayer player, ClientLevel level, HuntConfigManager config) {
        double radius = activeMode == HuntConfigManager.HuntMode.SPOT ? 5.0 : 20.0;
        BlockPos playerCenter = player.blockPosition();
        BlockPos killCenter = lastKillCenter != null ? lastKillCenter : playerCenter;
        double minX = Math.min(playerCenter.getX(), killCenter.getX()) - radius;
        double minY = Math.min(playerCenter.getY(), killCenter.getY()) - 6.0;
        double minZ = Math.min(playerCenter.getZ(), killCenter.getZ()) - radius;
        double maxX = Math.max(playerCenter.getX(), killCenter.getX()) + radius + 1.0;
        double maxY = Math.max(playerCenter.getY(), killCenter.getY()) + 6.0;
        double maxZ = Math.max(playerCenter.getZ(), killCenter.getZ()) + radius + 1.0;
        AABB box = new AABB(minX, minY, minZ, maxX, maxY, maxZ);

        List<HuntZone> activeZones = config.getActiveZones();
        ItemEntity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (!item.isAlive() || item.isRemoved() || ignoredDropIds.contains(item.getId())) continue;
            if (!canInventoryAcceptItem(player, item.getItem())) continue;
            if (activeMode == HuntConfigManager.HuntMode.ZONE && !isNearAnyActiveZone(item.blockPosition(), activeZones, 3)) {
                continue;
            }
            double distSq = player.distanceToSqr(item);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = item;
            }
        }
        return best;
    }

    private static boolean isNearAnyActiveZone(BlockPos pos, List<HuntZone> zones, int margin) {
        if (pos == null || zones == null) return false;
        for (HuntZone z : zones) {
            BlockPos min = z.getMinPos();
            BlockPos max = z.getMaxPos();
            if (pos.getX() >= min.getX() - margin && pos.getX() <= max.getX() + margin
                    && pos.getY() >= min.getY() - 2 && pos.getY() <= max.getY() + 2
                    && pos.getZ() >= min.getZ() - margin && pos.getZ() <= max.getZ() + margin) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // 3. الدورية والتمشيط عند خلو المحيط (PATROL_SWEEP)
    // =========================================================================

    private void handlePatrolSweepStage(
            Minecraft client,
            LocalPlayer player,
            ClientLevel level,
            MultiPlayerGameMode gameMode,
            HuntConfigManager config) {
        // فحص مستمر للأهداف أو الغنائم أثناء التمشيط
        LivingEntity spotted = findBestTarget(player, level, config);
        if (spotted != null) {
            currentPatrolWaypoint = null;
            currentStage = HuntStage.SEARCH_AND_HUNT;
            return;
        }
        ItemEntity drop = findNearestDrop(player, level, config);
        if (drop != null) {
            currentPatrolWaypoint = null;
            lockedDrop = drop;
            currentStage = HuntStage.COLLECT_DROPS;
            return;
        }

        if (currentPatrolWaypoint == null) {
            currentPatrolWaypoint = pickNextPatrolWaypoint(level, player, config);
            patrolWaypointTicks = 0;
        }
        if (currentPatrolWaypoint == null) {
            waitCooldownTicks = 20;
            currentStage = HuntStage.SEARCH_AND_HUNT;
            return;
        }

        patrolWaypointTicks++;
        if (patrolWaypointTicks > 240 || player.distanceToSqr(Vec3.atCenterOf(currentPatrolWaypoint)) <= 2.8 * 2.8) {
            advancePatrolIndex(config);
            currentPatrolWaypoint = null;
            patrolWaypointTicks = 0;
            waitCooldownTicks = 8;
            return;
        }

        BotNavigator.NavResult navRes = huntNavigator.navigateTo(
            client, player, level, gameMode, Vec3.atCenterOf(currentPatrolWaypoint),
            true, 2.0, false, HUNT_ROTATION_SPEED, true, 4000,
            FarmPathfinder.PathOptions.COMBAT_FOLLOW, false, true, null
        );
        if (navRes == BotNavigator.NavResult.BLOCKED_UNREACHABLE) {
            advancePatrolIndex(config);
            currentPatrolWaypoint = null;
            patrolWaypointTicks = 0;
        }
    }

    private void advancePatrolIndex(HuntConfigManager config) {
        if (activeMode == HuntConfigManager.HuntMode.ZONE && !config.getActiveZones().isEmpty()) {
            zonePatrolIndex = (zonePatrolIndex + 1) % config.getActiveZones().size();
        } else {
            patrolSectorIndex = (patrolSectorIndex + 1) % 24;
        }
    }

    private BlockPos pickNextPatrolWaypoint(ClientLevel level, LocalPlayer player, HuntConfigManager config) {
        if (activeMode == HuntConfigManager.HuntMode.ZONE) {
            List<HuntZone> activeZones = config.getActiveZones();
            if (activeZones.isEmpty()) return null;
            HuntZone z = activeZones.get(zonePatrolIndex % activeZones.size());
            BlockPos center = z.getCenterPos();
            BlockPos surface = findSafePatrolSurface(level, center.getX(), player.blockPosition().getY(), center.getZ());
            return surface != null ? surface : center;
        }

        BlockPos anchor = homePos != null ? homePos : player.blockPosition();
        int maxR = config.getPatrolRadius();
        int refY = player.blockPosition().getY();
        double[] ringFractions = {0.35, 0.68, 0.94};

        for (int attempt = 0; attempt < 24; attempt++) {
            int idx = (patrolSectorIndex + attempt) % 24;
            int sector = idx % 8;
            int ringIdx = (idx / 8) % ringFractions.length;
            double angle = sector * (Math.PI / 4.0) + (ringIdx * Math.PI / 12.0);
            int radius = Math.max(8, (int) Math.round(maxR * ringFractions[ringIdx]));

            int wx = anchor.getX() + (int) Math.round(Math.cos(angle) * radius);
            int wz = anchor.getZ() + (int) Math.round(Math.sin(angle) * radius);
            BlockPos surface = findSafePatrolSurface(level, wx, refY, wz);
            if (surface != null) {
                patrolSectorIndex = idx;
                return surface;
            }
        }
        return null;
    }

    private BlockPos findSafePatrolSurface(ClientLevel level, int x, int refY, int z) {
        for (int dy = 14; dy >= -14; dy--) {
            BlockPos feet = new BlockPos(x, refY + dy, z);
            BlockState floor = level.getBlockState(feet.below());
            if (!FarmPathfinder.isHazard(floor)
                    && !FarmPathfinder.isHazard(level.getBlockState(feet))
                    && hasSolidWalkableFloor(level, feet.below(), floor)
                    && FarmPathfinder.isPassable(level, feet)
                    && FarmPathfinder.isPassable(level, feet.above())) {
                return feet;
            }
        }
        return null;
    }

    private boolean hasSolidWalkableFloor(ClientLevel level, BlockPos floorPos, BlockState floorState) {
        if (floorState.isAir()
                || floorState.is(BlockTags.FENCES)
                || floorState.is(BlockTags.WALLS)) {
            return false;
        }
        VoxelShape floorShape = floorState.getCollisionShape(level, floorPos);
        return !floorShape.isEmpty() && floorShape.max(Direction.Axis.Y) >= 0.45;
    }

    // =========================================================================
    // 4. تفريغ الغنائم في الصناديق (DEPOSIT_LOOT)
    // =========================================================================

    private boolean isInventoryFull(LocalPlayer player) {
        Inventory inv = player.getInventory();
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private List<BlockPos> getEligibleLootChests(HuntConfigManager config) {
        List<BlockPos> ordered = new ArrayList<>();
        if (activeMode == HuntConfigManager.HuntMode.ZONE) {
            for (HuntZone zone : config.getActiveZones()) {
                ordered.addAll(zone.getLootChests());
            }
        }
        ordered.addAll(config.getGlobalLootChests());
        return ordered;
    }

    private BlockPos findNextLootChest(ClientLevel level, HuntConfigManager config) {
        for (BlockPos chest : getEligibleLootChests(config)) {
            if (!FarmAreaManager.containsStorageContainer(level, visitedDepositChests, chest)) {
                return chest;
            }
        }
        return null;
    }

    private boolean tryStartDepositCycle(
            Minecraft client,
            LocalPlayer player,
            ClientLevel level,
            HuntConfigManager config) {
        visitedDepositChests.clear();
        BlockPos chest = findNextLootChest(level, config);
        if (chest == null) {
            if (!fullChestWarned) {
                fullChestWarned = true;
                sendChatMessage(client, "§e[Bot - Hunt] الحقيبة ممتلئة ولم يتم تحديد صندوق غنائم! حدده عبر: §b/bot hunt loot chest open");
            }
            return false;
        }
        fullChestWarned = false;
        targetDepositChestPos = chest;
        depositTimeoutTicks = 0;
        depositCloseDelay = 0;
        currentStage = HuntStage.DEPOSIT_LOOT;
        huntNavigator.reset(client);
        sendChatMessage(client, "§e[Bot - Hunt] الحقيبة ممتلئة! يتوجه البوت لتفريغ الغنائم في الصندوق...");
        return true;
    }

    private void handleDepositLootStage(
            Minecraft client,
            LocalPlayer player,
            ClientLevel level,
            MultiPlayerGameMode gameMode,
            HuntConfigManager config) {
        if (targetDepositChestPos == null) {
            currentStage = HuntStage.SEARCH_AND_HUNT;
            return;
        }

        if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            BotEquipmentHelper.releaseMovementKeys(client);
            if (depositCloseDelay == 0) {
                lastDepositedCount = depositHuntLootIntoOpenChest(player, gameMode);
                depositCloseDelay = 4;
                return;
            }
            if (depositCloseDelay > 1) {
                depositCloseDelay--;
                return;
            }

            depositCloseDelay = 0;
            player.closeContainer();
            markChestVisited(level, visitedDepositChests, targetDepositChestPos);

            if (lastDepositedCount > 0) {
                sendChatMessage(client, "§a[Bot - Hunt] تم تفريغ §e" + lastDepositedCount + " §aحزمة غنائم في الصندوق بنجاح!");
            }

            if (isInventoryFull(player)) {
                BlockPos nextChest = findNextLootChest(level, config);
                if (nextChest != null) {
                    sendChatMessage(client, "§6[Bot - Hunt] الصندوق ممتلئ وما زالت الحقيبة ممتلئة؛ يتوجه للصندوق التالي...");
                    targetDepositChestPos = nextChest;
                    depositTimeoutTicks = 0;
                    huntNavigator.reset(client);
                    return;
                }
                sendChatMessage(client, "§c[Bot - Hunt] جميع صناديق الغنائم ممتلئة! إيقاف الصيد لحين تفريغ الصناديق.");
                stop(client);
                return;
            }

            targetDepositChestPos = null;
            visitedDepositChests.clear();
            ignoredDropIds.clear();
            currentStage = HuntStage.SEARCH_AND_HUNT;
            waitCooldownTicks = 15;
            return;
        } else {
            depositCloseDelay = 0;
        }

        depositTimeoutTicks++;
        double cx = targetDepositChestPos.getX() + 0.5;
        double cy = targetDepositChestPos.getY() + 0.5;
        double cz = targetDepositChestPos.getZ() + 0.5;
        double distSq = player.distanceToSqr(cx, cy, cz);
        boolean chestMissing = distSq <= 8.0 * 8.0 && !FarmAreaManager.isStorageContainerBlock(level.getBlockState(targetDepositChestPos));

        if (chestMissing || depositTimeoutTicks > 400) {
            markChestVisited(level, visitedDepositChests, targetDepositChestPos);
            BlockPos nextChest = findNextLootChest(level, config);
            if (nextChest != null) {
                sendChatMessage(client, "§e[Bot - Hunt] تعذر فتح الصندوق الحالي؛ الانتقال لصندوق الغنائم التالي...");
                targetDepositChestPos = nextChest;
                depositTimeoutTicks = 0;
                huntNavigator.reset(client);
                return;
            }
            sendChatMessage(client, "§c[Bot - Hunt] تعذر الوصول لأي صندوق غنائم! إيقاف الصيد.");
            stop(client);
            return;
        }

        if (distSq > 2.8 * 2.8) {
            huntNavigator.navigateTo(
                client, player, level, gameMode, new Vec3(cx, cy, cz),
                false, 2.4, false, HUNT_ROTATION_SPEED, true, 2500,
                FarmPathfinder.PathOptions.COMBAT_FOLLOW, false, true, null
            );
        } else {
            huntNavigator.clearPathOnly();
            BotEquipmentHelper.releaseMovementKeys(client);
            BotEquipmentHelper.smoothLookAt(player, cx, cy, cz, HUNT_ROTATION_SPEED);
            BlockHitResult hit = new BlockHitResult(new Vec3(cx, cy, cz), Direction.UP, targetDepositChestPos, false);
            gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            waitCooldownTicks = 15;
        }
    }

    private int depositHuntLootIntoOpenChest(LocalPlayer player, MultiPlayerGameMode gameMode) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) return 0;

        boolean hasRanged = BotEquipmentHelper.hasMatchingItem(player, BotEquipmentHelper::isRangedWeapon, true);
        int keptAmmoSlot = -1;
        if (hasRanged) {
            int maxAmmoCount = -1;
            for (int slotId = 0; slotId < menu.slots.size(); slotId++) {
                var slot = menu.getSlot(slotId);
                if (slot.container != player.getInventory()) continue;
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty() && BotEquipmentHelper.isAmmoItem(stack) && stack.getCount() > maxAmmoCount) {
                    maxAmmoCount = stack.getCount();
                    keptAmmoSlot = slotId;
                }
            }
        }

        int deposited = 0;
        for (int slotId = 0; slotId < menu.slots.size(); slotId++) {
            var slot = menu.getSlot(slotId);
            if (slot.container != player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            if (BotEquipmentHelper.hasKeepableGearOrTool(player, stack)) continue;
            if (BotFoodManager.getInstance().isAllowed(stack)) continue;
            if (slotId == keptAmmoSlot) continue;

            gameMode.handleContainerInput(menu.containerId, slotId, 0, ContainerInput.QUICK_MOVE, player);
            deposited++;
        }
        return deposited;
    }

    private static void markChestVisited(ClientLevel level, Set<BlockPos> visited, BlockPos pos) {
        if (pos == null || visited == null) return;
        visited.add(pos.immutable());
        BlockPos half = FarmAreaManager.getConnectedChestHalf(level, pos);
        if (half != null) visited.add(half);
    }

    // =========================================================================
    // 5. الطعام والنوم الليلي
    // =========================================================================

    private boolean handleHuntingEating(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        int food = player.getFoodData().getFoodLevel();
        float health = player.getHealth();
        boolean needFood = food <= 14 || (isEating && player.getFoodData().needsFood()) || (health < 14.0f && player.getFoodData().needsFood());

        if (!needFood) {
            stopHuntingEating(client, player, gameMode);
            return false;
        }

        BotFoodManager foodMgr = BotFoodManager.getInstance();
        if (!foodMgr.hasEdibleAllowedFood(player)) {
            stopHuntingEating(client, player, gameMode);
            if (hungryAlertCooldown <= 0) {
                sendChatMessage(client, "§e[Bot - Hunt] أنا جائع ولا يوجد طعام مسموح في الحقيبة! (/bot food add)");
                hungryAlertCooldown = 1200;
            }
            return false;
        }

        int foodSlot = foodMgr.ensureBestFoodInHotbar(player, gameMode, FOOD_HOTBAR_SLOT, -1);
        if (foodSlot == -1) {
            stopHuntingEating(client, player, gameMode);
            return false;
        }

        BotEquipmentHelper.selectHotbarSlot(player, foodSlot);
        if (isEating && lastFoodLevel != -1 && food > lastFoodLevel) {
            eatingTicks = 0;
        }
        lastFoodLevel = food;
        isEating = true;
        eatingTicks++;

        if (player.isUsingItem() && player.getUseItem().is(Items.SHIELD)) {
            gameMode.releaseUsingItem(player);
            player.stopUsingItem();
        }
        client.options.keyUse.setDown(true);
        if (!player.isUsingItem()) {
            gameMode.useItem(player, InteractionHand.MAIN_HAND);
            player.startUsingItem(InteractionHand.MAIN_HAND);
        }

        if (eatingTicks > 45) {
            stopHuntingEating(client, player, gameMode);
        }
        return true;
    }

    private void stopHuntingEating(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        if (!isEating) return;
        isEating = false;
        eatingTicks = 0;
        lastFoodLevel = -1;
        if (client != null && client.options != null) {
            client.options.keyUse.setDown(false);
        }
        if (player != null && player.isUsingItem() && !player.getUseItem().is(Items.SHIELD)) {
            if (gameMode != null) gameMode.releaseUsingItem(player);
            player.stopUsingItem();
        }
    }

    private void handleSleepStage(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode) {
        if (!FarmAreaManager.isBedDimensionSupported(level)) {
            currentStage = HuntStage.SEARCH_AND_HUNT;
            return;
        }
        if (player.isSleeping()) {
            BotEquipmentHelper.releaseMovementKeys(client);
            if (client.options != null) client.options.keyUse.setDown(false);
            return;
        }

        boolean stillNight = level.isThundering() || level.getSkyDarken() >= 4;
        if (!stillNight) {
            targetBedPos = null;
            bedAlertSent = false;
            currentStage = HuntStage.SEARCH_AND_HUNT;
            return;
        }

        BlockPos bedPos = FarmAreaManager.getInstance().getBedPos();
        if (bedPos != null && !isUnoccupiedBed(level, bedPos)) {
            bedPos = null;
        }
        if (bedPos == null) {
            if (targetBedPos == null || !isUnoccupiedBed(level, targetBedPos)) {
                targetBedPos = findNearbyUnoccupiedBed(player, level, 36);
            }
            bedPos = targetBedPos;
        }

        if (bedPos == null) {
            if (!bedAlertSent) {
                sendChatMessage(client, "§e[Bot - Hunt] حل الليل ولا يوجد سرير متاح قريب؛ استئناف الصيد الحذر.");
                bedAlertSent = true;
            }
            currentStage = HuntStage.SEARCH_AND_HUNT;
            return;
        }

        double bx = bedPos.getX() + 0.5;
        double by = bedPos.getY() + 0.5;
        double bz = bedPos.getZ() + 0.5;
        double distSq = player.distanceToSqr(bx, by, bz);

        if (distSq > 2.6 * 2.6) {
            BotNavigator.NavResult navRes = huntNavigator.navigateTo(
                client, player, level, gameMode, new Vec3(bx, by, bz),
                false, 2.2, false, HUNT_ROTATION_SPEED, true, 2500,
                FarmPathfinder.PathOptions.COMBAT_FOLLOW, false, true, null
            );
            if (navRes == BotNavigator.NavResult.BLOCKED_UNREACHABLE) {
                targetBedPos = null;
                currentStage = HuntStage.SEARCH_AND_HUNT;
            }
        } else {
            huntNavigator.clearPathOnly();
            BotEquipmentHelper.releaseMovementKeys(client);
            int safeSlot = BotEquipmentHelper.findSafeInteractionSlot(player);
            if (safeSlot != -1) {
                BotEquipmentHelper.selectHotbarSlot(player, safeSlot);
            }
            BotEquipmentHelper.smoothLookAt(player, bx, by, bz, HUNT_ROTATION_SPEED);
            BlockHitResult hit = new BlockHitResult(new Vec3(bx, by, bz), Direction.UP, bedPos, false);
            gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            waitCooldownTicks = 20;
        }
    }

    private BlockPos findNearbyUnoccupiedBed(LocalPlayer player, ClientLevel level, int radius) {
        BlockPos origin = player.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dy = -4; dy <= 5; dy++) {
            for (int dx = -radius; dx <= radius; dx += 1) {
                for (int dz = -radius; dz <= radius; dz += 1) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (isUnoccupiedBed(level, pos)) {
                        double d = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                        if (d < bestDist) {
                            bestDist = d;
                            best = pos.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    private boolean isUnoccupiedBed(ClientLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock) && !state.is(BlockTags.BEDS)) {
            return false;
        }
        if (state.hasProperty(BedBlock.OCCUPIED) && state.getValue(BedBlock.OCCUPIED)) {
            return false;
        }
        if (state.hasProperty(BedBlock.FACING) && state.hasProperty(BedBlock.PART)) {
            Direction facing = state.getValue(BedBlock.FACING);
            BedPart part = state.getValue(BedBlock.PART);
            BlockPos partnerPos = part == BedPart.HEAD ? pos.relative(facing.getOpposite()) : pos.relative(facing);
            BlockState partnerState = level.getBlockState(partnerPos);
            if (partnerState.hasProperty(BedBlock.OCCUPIED) && partnerState.getValue(BedBlock.OCCUPIED)) {
                return false;
            }
        }
        return true;
    }

    private LivingEntity findEntityByUuid(ClientLevel level, LocalPlayer player, UUID uuid, double radius) {
        AABB box = player.getBoundingBox().inflate(radius);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (e.getUUID().equals(uuid)) {
                return e;
            }
        }
        return null;
    }

    private void sendChatMessage(Minecraft client, String msg) {
        if (client != null && client.player != null) {
            client.player.sendSystemMessage(Component.literal(msg));
        }
    }
}
