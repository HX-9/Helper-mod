package com.helpermod.bot;
import com.helpermod.bot.combat.BotCombatManager;
import com.helpermod.bot.food.BotFoodManager;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * وحدة التحكم الذكية بوضع الاتباع (Smart Companion Follow Controller):
 * 1. الركض السريع (Sprint) من بعيد (> 10 بلوكات) حتى الوصول لخلف الصديق لليمين على بعد 3 بلوكات.
 * 2. عند الوصول: التوقف التام ومطابقة زاوية نظر الصديق (Yaw & Pitch).
 * 3. الثبات التام في المكان طالما أن الصديق لم يتحرك بقدميه (حتى لو دار برأسه والتفت يميناً ويساراً 360 درجة).
 * 4. عند تحرك الصديق ومشيه: مرافقة مرنة والبقاء قريباً منه بمسافة ~3 بلوكات من أي جهة دون دوران دائري أو تشنج.
 * 5. خوارزميات ذكية لمنع العلوق (Anti-Stuck): مناورة جانبية، قفز ذكي، فتح الأبواب تلقائياً، وإعادة حساب A* فورية.
 * 6. التوافق التام مع نظام الدفاع بالدرع (Shield Defense) دون تعارض.
 */
public class BotFollowController {

    private static final BotFollowController INSTANCE = new BotFollowController();

    public static BotFollowController getInstance() {
        return INSTANCE;
    }

    private enum FollowState {
        SPRINT_FROM_DISTANCE,   // الركض لمسافة طويلة (> 10 بلوكات) للوصول لخلف اليمين
        STATIONARY_MATCHING,    // الاستقرار في المكان ومطابقة زاوية نظر الصديق
        FLEXIBLE_ESCORT         // المرافقة المرنة عند حركة الصديق للمحافظة على مسافة ~3 بلوكات
    }

    private boolean following = false;
    private String targetPlayerName = null;
    private UUID targetPlayerUUID = null;

    private FollowState currentState = FollowState.SPRINT_FROM_DISTANCE;

    // موضع تثبيت الصديق عند الاستقرار لمراقبة هل تحركت أقدامه أم التفت فقط
    private Vec3 companionAnchorPos = null;

    // الملاح الموحد (A* + فتح الأبواب + فحص المخاطر والسقوط)
    private final BotNavigator navigator = new BotNavigator();

    // عدادات الاستقرار والأكل
    private int companionStationaryTicks = 0;

    // الأولوية رقم 1: نظام الأكل والعلاج الذاتي التلقائي أثناء الاتباع
    private boolean isEatingWhileFollowing = false;
    private int followEatingTicks = 0;
    private int lastFollowFoodLevel = -1;
    private int hungryAlertCooldown = 0;
    private int followEatSuppressionTicks = 0;
    private int preEatWeaponSlot = -1;

    // حالة النوم بجانب القائد عند استلقائه وتوفر سرير قريب متاح
    private BlockPos targetSleepBedPos = null;
    private int sleepScanCooldown = 0;
    private int sleepInteractCooldown = 0;
    private int sleepAttemptCount = 0;
    private final Map<BlockPos, Integer> failedBedCooldowns = new HashMap<>();
    private boolean sleepingWithCompanion = false;
    private int preSleepSlot = -1;
    private int sleepReenterCooldown = 0;
    private boolean sleepMessageSent = false;

    private BotFollowController() {}

    public boolean isFollowing() {
        return following;
    }

    public String getTargetPlayerName() {
        return targetPlayerName;
    }

    public UUID getTargetPlayerUUID() {
        return targetPlayerUUID;
    }

    /**
     * التحقق مما إذا كان اللاعب المحدد هو المرافق المتبع حالياً
     */
    public boolean isFollowingPlayer(Player player) {
        if (!following || player == null) return false;
        if (targetPlayerUUID != null && targetPlayerUUID.equals(player.getUUID())) {
            return true;
        }
        if (targetPlayerName != null) {
            String name1 = player.getScoreboardName();
            String name2 = player.getName().getString();
            return targetPlayerName.equalsIgnoreCase(name1) || targetPlayerName.equalsIgnoreCase(name2);
        }
        return false;
    }

    public boolean isFollowingPlayer(String name) {
        if (!following || name == null || targetPlayerName == null) return false;
        return targetPlayerName.equalsIgnoreCase(name);
    }

    /**
     * بدء وضع الاتباع
     */
    public void startFollowing(Minecraft client, String playerName) {
        this.targetPlayerName = playerName;
        this.targetPlayerUUID = null;
        this.following = true;
        this.currentState = FollowState.SPRINT_FROM_DISTANCE;
        this.companionAnchorPos = null;
        this.navigator.reset(client);
        this.companionStationaryTicks = 0;
        this.isEatingWhileFollowing = false;
        this.followEatingTicks = 0;
        this.lastFollowFoodLevel = -1;
        this.hungryAlertCooldown = 0;
        this.followEatSuppressionTicks = 0;
        this.preEatWeaponSlot = -1;
        this.resetSleepState();

        // تصفير أي قتال أو دفاع معلق في الكنترولر القتالي
        BotCombatManager.getInstance().resetCombat(client);
        FightBotController.getInstance().stopDefending(client);

        // إيقاف بوت المزرعة لمنع أي تضارب حركي
        if (FarmBotController.getInstance().isFarming()) {
            FarmBotController.getInstance().stop(client);
        }

        if (client != null && client.level != null) {
            Player p = findPlayerByName(client.level, playerName);
            if (p != null) {
                this.targetPlayerUUID = p.getUUID();
            }
        }

        if (client != null && client.player != null) {
            client.player.sendSystemMessage(Component.literal("§a[Bot] تم تفعيل وضع الاتباع الذكي والحارس الشخصي للاعب: §e" + playerName + "§a!"));
            if (!BotGlobalManager.getInstance().isBotEnabled()) {
                client.player.sendSystemMessage(Component.literal("§e[Bot] تنبيه: الوضع العام للمود متوقف حالياً (/bot mode off). فعّله عبر: §b/bot mode on"));
            }
        }
    }

    /**
     * إيقاف وضع الاتباع
     */
    public void stopFollowing(Minecraft client) {
        this.following = false;
        this.targetPlayerName = null;
        this.targetPlayerUUID = null;
        this.currentState = FollowState.SPRINT_FROM_DISTANCE;
        this.companionAnchorPos = null;
        this.navigator.reset(client);
        this.companionStationaryTicks = 0;
        this.isEatingWhileFollowing = false;
        this.followEatingTicks = 0;
        this.lastFollowFoodLevel = -1;
        this.hungryAlertCooldown = 0;
        this.followEatSuppressionTicks = 0;
        this.preEatWeaponSlot = -1;
        if (client != null && client.player != null && sleepingWithCompanion && client.player.isSleeping()) {
            client.player.stopSleepInBed(true, true);
            restorePostSleepWeapon(client.player, client.gameMode);
        }
        this.resetSleepState();

        BotCombatManager.getInstance().resetCombat(client);
        releaseMovement(client);
        if (client != null) {
            if (client.player != null && client.gameMode != null && client.player.isUsingItem()) {
                client.gameMode.releaseUsingItem(client.player);
                client.player.stopUsingItem();
            }
            if (client.options != null) {
                client.options.keyUse.setDown(false);
            }
        }

        if (client != null && client.player != null) {
            client.player.sendSystemMessage(Component.literal("§c[Bot] تم إيقاف وضع الاتباع."));
        }
    }

    /**
     * التحديث الدوري مع كل Tick
     */
    public void onTick(Minecraft client) {
        if (!following || client == null) return;

        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        MultiPlayerGameMode gameMode = client.gameMode;

        if (player == null || level == null || player.isDeadOrDying()) {
            resetSleepState();
            releaseMovement(client);
            return;
        }

        navigator.tickCooldowns();

        Player companion = findTargetPlayer(level);
        if (companion == null) {
            if (sleepingWithCompanion && player.isSleeping()) {
                player.stopSleepInBed(true, true);
                restorePostSleepWeapon(player, gameMode);
            }
            resetSleepState();
            releaseMovement(client);
            return;
        }
        this.targetPlayerUUID = companion.getUUID();

        if (hungryAlertCooldown > 0) hungryAlertCooldown--;
        if (followEatSuppressionTicks > 0) followEatSuppressionTicks--;
        if (sleepScanCooldown > 0) sleepScanCooldown--;
        if (sleepInteractCooldown > 0) sleepInteractCooldown--;
        if (sleepReenterCooldown > 0) sleepReenterCooldown--;
        failedBedCooldowns.entrySet().removeIf(entry -> {
            int rem = entry.getValue() - 1;
            if (rem <= 0) return true;
            entry.setValue(rem);
            return false;
        });

        // تحرير القفز التلقائي فوراً بمجرد الارتفاع عن الأرض في الهواء لمنع تكرار القفز العشوائي
        boolean inWater = BotNavigator.isEntityInWater(level, player);
        if (!player.onGround() && !inWater) {
            client.options.keyJump.setDown(false);
        }

        // حساب المسافة ثلاثية الأبعاد وفرق الارتفاع لضمان اللحاق بالقائد عند صعوده أو نزوله
        double dx = player.getX() - companion.getX();
        double dy = player.getY() - companion.getY();
        double dz = player.getZ() - companion.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        double dist3D = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double verticalGap = Math.abs(dy);

        // ==============================================================
        // الأولوية 0: حراسة التنفس الطارئة في الماء (الطفو أو الهروب من السقف قبل الغرق مع السماح بالغوص الحر)
        // ==============================================================
        if (player.isSleeping() && BotNavigator.isEyesInWater(level, player) && BotNavigator.getAirRatio(player) <= 0.35) {
            player.stopSleepInBed(true, true);
            restorePostSleepWeapon(player, gameMode);
            resetSleepState();
        }
        if (navigator.handleUrgentWaterBreathing(client, player, level, gameMode)) {
            stopFollowEating(client, player, gameMode);
            navigator.clearPathOnly();
            return;
        }

        // إدارة حالة النوم المتزامن مع القائد (البقاء نائماً معه أو الاستيقاظ فور قيامه)
        boolean companionSleeping = companion.isSleeping();
        if (!companionSleeping) {
            if (sleepingWithCompanion || targetSleepBedPos != null || preSleepSlot != -1) {
                if (sleepingWithCompanion && player.isSleeping()) {
                    player.stopSleepInBed(true, true);
                }
                restorePostSleepWeapon(player, gameMode);
                resetSleepState();
            }
        } else if (player.isSleeping()) {
            sleepingWithCompanion = true;
            sleepReenterCooldown = 0;
            targetSleepBedPos = null;
            sleepAttemptCount = 0;
            stopFollowEating(client, player, gameMode);
            navigator.clearPathOnly();
            releaseMovement(client);
            if (client.options != null) {
                client.options.keyUse.setDown(false);
            }
            return;
        } else if (sleepingWithCompanion) {
            // البوت استيقظ للتو (غالباً لتخطي الليل أو تلقّي ضربة): ننتظر وجيزاً قبل إعادة المحاولة حتى يستيقظ القائد أيضاً
            sleepingWithCompanion = false;
            sleepReenterCooldown = 25;
            restorePostSleepWeapon(player, gameMode);
        }

        // ==============================================================
        // الأولوية 0: حراسة التنفس الطارئة في الماء (الطفو أو الهروب من السقف قبل الغرق مع السماح بالغوص الحر)
        // ==============================================================
        if (navigator.handleUrgentWaterBreathing(client, player, level, gameMode)) {
            stopFollowEating(client, player, gameMode);
            navigator.clearPathOnly();
            return;
        }

        // ==============================================================
        // الأولوية 1: القتال والدفاع التكتيكي الحاسم (Combat & Defense)
        // ==============================================================
        boolean engagedInCombat = BotCombatManager.getInstance().tickCombat(client, player, level, gameMode, companion, dist3D);
        if (engagedInCombat) {
            stopFollowEating(client, player, gameMode);
            navigator.clearPathOnly();
            return;
        }

        // ==============================================================
        // الأولوية 1.5: النوم بجوار القائد إذا نام وكان بجواره سرير متاح
        // ==============================================================
        if (companionSleeping && sleepReenterCooldown <= 0
            && handleSleepWithCompanion(client, player, level, gameMode, companion)) {
            return;
        }

        // ==============================================================
        // الأولوية 2: اللحاق بالقائد والتمركز الصحيح (Follow Catch-up)
        // ==============================================================
        if (dist3D > 4.5) {
            if (isEatingWhileFollowing) {
                stopFollowEating(client, player, gameMode);
                followEatSuppressionTicks = 35;
            }
            if (client.options.keyUse.isDown()) {
                client.options.keyUse.setDown(false);
            }
            if (player.isUsingItem()) {
                gameMode.releaseUsingItem(player);
                player.stopUsingItem();
            }
        }

        // ==============================================================
        // الأولوية 3: تناول الطعام والعلاج التلقائي (Safe Eating & Healing)
        // ==============================================================
        if (!engagedInCombat && dist3D <= 4.0 && followEatSuppressionTicks <= 0) {
            handleFollowEating(client, player, gameMode);
        }

        // فحص هل الصديق تحركت أقدامه فعلياً من مكانه أم أنه واقف ويلتفت فقط
        boolean companionStepped = companionAnchorPos == null
            || companion.position().distanceTo(companionAnchorPos) > 1.2;

        if (!companionStepped) {
            companionStationaryTicks++;
        } else {
            companionStationaryTicks = 0;
        }

        // أ) إذا ابتعد القائد مسافة (> 5.5 بلوكات): الركض السريع الفوري نحو القائد دون تأخير!
        if (dist3D > 5.5) {
            currentState = FollowState.SPRINT_FROM_DISTANCE;
            companionAnchorPos = null;
        }

        double waterAwareGapLimit = inWater ? 0.85 : 1.8;

        switch (currentState) {
            case SPRINT_FROM_DISTANCE: {
                if (distXZ <= 3.2 && verticalGap <= waterAwareGapLimit) {
                    currentState = FollowState.FLEXIBLE_ESCORT;
                    releaseMovement(client);
                    BotNavigator.maintainWaterBuoyancy(client, player, level, companion);
                    break;
                }

                Vec3 targetPos = companion.position();
                navigator.navigateTo(client, player, level, gameMode, targetPos, true, 2.5, isEatingWhileFollowing, 45.0f, true, 4000);
                break;
            }

            case STATIONARY_MATCHING: {
                if (companionStepped || distXZ > 3.4 || verticalGap > waterAwareGapLimit) {
                    currentState = FollowState.FLEXIBLE_ESCORT;
                    companionAnchorPos = null;
                    break;
                }

                releaseMovement(client);
                BotNavigator.maintainWaterBuoyancy(client, player, level, companion);
                smoothLookTowards(player, companion.getYRot(), companion.getXRot(), 18.0f);
                break;
            }

            case FLEXIBLE_ESCORT: {
                if (!companionStepped && companionStationaryTicks >= 40 && distXZ <= 3.6 && verticalGap <= waterAwareGapLimit) {
                    currentState = FollowState.STATIONARY_MATCHING;
                    companionAnchorPos = companion.position();
                    releaseMovement(client);
                    BotNavigator.maintainWaterBuoyancy(client, player, level, companion);
                    smoothLookTowards(player, companion.getYRot(), companion.getXRot(), 20.0f);
                    return;
                }

                // إذا كان القائد فوق/تحت البوت بفرق ارتفاع ملحوظ، نستهدف موقعه لإيجاد الدرج أو المسار الرأسي أو الغوص/الصعود المائي
                if (verticalGap > waterAwareGapLimit) {
                    Vec3 climbTarget = distXZ <= 3.2 ? companion.position() : calculateFlexibleEscortPos(player, companion, level);
                    boolean sprint = dist3D > 4.5;
                    navigator.navigateTo(client, player, level, gameMode, climbTarget, sprint, distXZ <= 3.2 ? (inWater ? 0.9 : 2.0) : 0.8, isEatingWhileFollowing, 45.0f, true, 4000);
                } else if (distXZ < 2.2) {
                    releaseMovement(client);
                    BotNavigator.maintainWaterBuoyancy(client, player, level, companion);
                    smoothLookTowards(player, companion.getYRot(), companion.getXRot(), 15.0f);
                } else if (distXZ > 3.2) {
                    Vec3 escortTarget = calculateFlexibleEscortPos(player, companion, level);
                    boolean sprint = distXZ > 4.5;
                    navigator.navigateTo(client, player, level, gameMode, escortTarget, sprint, 0.7, isEatingWhileFollowing, 45.0f, true, 4000);
                } else {
                    releaseMovement(client);
                    BotNavigator.maintainWaterBuoyancy(client, player, level, companion);
                    smoothLookTowards(player, companion.getYRot(), companion.getXRot(), 15.0f);
                }
                break;
            }
        }
    }

    /**
     * حساب موضع المرافقة المرنة (~3 بلوكات من الصديق من أقرب جهة سالكة دون دوران)
     */
    private Vec3 calculateFlexibleEscortPos(LocalPlayer player, Player companion, ClientLevel level) {
        double dx = player.getX() - companion.getX();
        double dz = player.getZ() - companion.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);

        Vec3 dir;
        if (len > 0.1) {
            dir = new Vec3(dx / len, 0, dz / len);
        } else {
            // كاحتياط خلف اللاعب
            float yaw = companion.getYRot();
            double rad = Math.toRadians(yaw);
            dir = new Vec3(Math.sin(rad), 0, -Math.cos(rad));
        }

        // النقطة المستهدفة على بعد 2.8 بلوكة مع بديل آمن من الجوار أو موضع القائد
        double targetX = companion.getX() + dir.x * 2.8;
        double targetZ = companion.getZ() + dir.z * 2.8;
        BlockPos base = BlockPos.containing(targetX, companion.getY(), targetZ);
        return findNearestSafeStandPos(level, base, companion.position());
    }

    /**
     * حساب موضع خلف الصديق في اليمين على بعد 3 بلوكات بالضبط (yaw + 135°)
     */
    public static Vec3 calculateBehindRightPost(Player companion, ClientLevel level) {
        float yaw = companion.getYRot();
        double angleRad = Math.toRadians(yaw + 135.0f);

        double offsetX = -Math.sin(angleRad) * 3.0;
        double offsetZ = Math.cos(angleRad) * 3.0;

        double targetX = companion.getX() + offsetX;
        double targetZ = companion.getZ() + offsetZ;
        BlockPos basePos = BlockPos.containing(targetX, companion.getY(), targetZ);
        return findNearestSafeStandPos(level, basePos, new Vec3(targetX, companion.getY(), targetZ));
    }

    private static Vec3 findNearestSafeStandPos(ClientLevel level, BlockPos basePos, Vec3 fallback) {
        int[] yOffsets = {0, 1, -1, 2, -2};
        for (int dy : yOffsets) {
            BlockPos cand = basePos.above(dy);
            if (isSafeStandPosition(level, cand)) {
                return new Vec3(cand.getX() + 0.5, cand.getY(), cand.getZ() + 0.5);
            }
        }

        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (ox == 0 && oz == 0) continue;
                for (int dy : yOffsets) {
                    BlockPos cand = basePos.offset(ox, dy, oz);
                    if (isSafeStandPosition(level, cand)) {
                        return new Vec3(cand.getX() + 0.5, cand.getY(), cand.getZ() + 0.5);
                    }
                }
            }
        }
        return fallback;
    }

    /**
     * فحص هل سقف الغرفة سالك للقفز دون ارتطام الرأس
     */
    public static boolean isCeilingClear(ClientLevel level, BlockPos pos) {
        return BotNavigator.isCeilingClear(level, pos);
    }

    /**
     * فحص هل يوجد عائق صلب حقيقي بارتفاع بلوكة واحدة أمام قدمي البوت في اتجاه هدفه وسالك فوقه للصعود
     */
    public static boolean isObstacleDirectlyAhead(LocalPlayer player, ClientLevel level, double tx, double tz) {
        return BotNavigator.isObstacleDirectlyAhead(player, level, tx, tz);
    }

    /**
     * فحص هل توجد درجة أو حافة بارتفاع بلوكة أمام البوت مباشرة وسالكة فوقها للصعود السلس دون انتظار التصادم
     */
    public static boolean hasStepDirectlyAhead(LocalPlayer player, ClientLevel level, double tx, double tz) {
        return BotNavigator.hasStepDirectlyAhead(player, level, tx, tz);
    }

    /**
     * التحقق من سلامة موقع الوقوف
     */
    private static boolean isSafeStandPosition(ClientLevel level, BlockPos pos) {
        if (!FarmPathfinder.isFeetPassable(level, pos) || !FarmPathfinder.isPassable(level, pos.above())) {
            return false;
        }
        BlockPos floorPos = pos.below();
        BlockState floor = level.getBlockState(floorPos);
        if (floor.isAir()
            || FarmPathfinder.isHazard(floor)
            || FarmPathfinder.isHazard(level.getBlockState(pos))
            || FarmPathfinder.isHazard(level.getBlockState(pos.above()))) {
            return false;
        }
        if (FarmPathfinder.isFenceOrWall(floor) || FarmPathfinder.isChestOrContainer(floor)) {
            return false;
        }
        return !floor.getCollisionShape(level, floorPos).isEmpty();
    }

    private void smoothLookTowards(LocalPlayer player, float targetYaw, float targetPitch, float maxStep) {
        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();

        float diffYaw = wrapDegrees(targetYaw - currentYaw);
        float stepYaw = Math.max(-maxStep, Math.min(maxStep, diffYaw));
        float newYaw = currentYaw + stepYaw;
        player.setYRot(newYaw);
        player.yHeadRot = newYaw;
        player.yBodyRot = newYaw;

        float diffPitch = targetPitch - currentPitch;
        float stepPitch = Math.max(-maxStep, Math.min(maxStep, diffPitch));
        player.setXRot(currentPitch + stepPitch);
    }

    private static float wrapDegrees(float degrees) {
        return BotEquipmentHelper.wrapDegrees(degrees);
    }

    private void releaseMovement(Minecraft client) {
        BotEquipmentHelper.releaseMovementKeys(client);
    }

    private Player findTargetPlayer(ClientLevel level) {
        if (targetPlayerUUID != null) {
            Player p = level.getPlayerByUUID(targetPlayerUUID);
            if (p != null) return p;
        }
        if (targetPlayerName != null) {
            for (Player p : level.players()) {
                if (p.getScoreboardName().equalsIgnoreCase(targetPlayerName) || p.getName().getString().equalsIgnoreCase(targetPlayerName)) {
                    return p;
                }
            }
        }
        return null;
    }

    private Player findPlayerByName(ClientLevel level, String name) {
        for (Player p : level.players()) {
            if (p.getScoreboardName().equalsIgnoreCase(name) || p.getName().getString().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    private void ensureShieldInOffhand(LocalPlayer player, MultiPlayerGameMode gameMode) {
        BotEquipmentHelper.ensureInOffhand(player, gameMode, stack -> stack.is(Items.SHIELD));
    }

    private boolean hasAllowedGoldenApple(LocalPlayer player) {
        BotFoodManager foodMgr = BotFoodManager.getInstance();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE))) {
                if (foodMgr.isAllowed(stack)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean ensureFoodInHand(LocalPlayer player, MultiPlayerGameMode gameMode) {
        BotFoodManager foodMgr = BotFoodManager.getInstance();
        if (foodMgr.canEatNow(player, player.getMainHandItem())) {
            return true;
        }

        int avoidSlot = (preEatWeaponSlot >= 0 && preEatWeaponSlot < 9) ? preEatWeaponSlot : 0;
        int fallbackSlot = (avoidSlot == 8) ? 7 : 8;
        int foodSlot = foodMgr.ensureBestFoodInHotbar(player, gameMode, fallbackSlot, avoidSlot);
        if (foodSlot != -1) {
            BotEquipmentHelper.selectHotbarSlot(player, foodSlot);
            return true;
        }
        return false;
    }

    /**
     * الأولوية رقم 1 للبوت: الأكل والعلاج الذاتي التلقائي أثناء الاتباع
     */
    private boolean handleFollowEating(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        int food = player.getFoodData().getFoodLevel();
        float health = player.getHealth();
        boolean needsFood = player.getFoodData().needsFood();
        boolean isFullFood = !needsFood;

        if (isFullFood) {
            if (health >= 20.0f) {
                stopFollowEating(client, player, gameMode);
                return false;
            }
            if (!hasAllowedGoldenApple(player)) {
                stopFollowEating(client, player, gameMode);
                return false;
            }
        }

        if (!isEatingWhileFollowing && preEatWeaponSlot == -1) {
            preEatWeaponSlot = player.getInventory().getSelectedSlot();
        }

        // محاولة سحب أفضل طعام مسموح لليد الرئيسية مع حفظ خانة السلاح السابقة
        boolean hasFood = ensureFoodInHand(player, gameMode);
        if (!hasFood) {
            preEatWeaponSlot = -1;
            stopFollowEating(client, player, gameMode);

            if (needsFood && hungryAlertCooldown <= 0) {
                hungryAlertCooldown = 1200;
                if (player.connection != null) {
                    player.connection.sendChat("أنا جائع!");
                }
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.literal("§c[Bot] أنا جائع ولا أملك أي طعام مسموح به في الحقيبة!"));
                }
            }
            return false;
        }

        if (isEatingWhileFollowing && lastFollowFoodLevel != -1 && food > lastFollowFoodLevel) {
            followEatingTicks = 0;
        }
        lastFollowFoodLevel = food;

        isEatingWhileFollowing = true;
        followEatingTicks++;

        if (player.isUsingItem() && player.getUseItem().is(Items.SHIELD)) {
            gameMode.releaseUsingItem(player);
            player.stopUsingItem();
        }

        client.options.keyUse.setDown(true);
        if (!player.isUsingItem()) {
            client.options.keyUse.setDown(false);
            gameMode.useItem(player, InteractionHand.MAIN_HAND);
            player.startUsingItem(InteractionHand.MAIN_HAND);
            client.options.keyUse.setDown(true);
        }

        if (followEatingTicks > 45) {
            followEatingTicks = 0;
            if (!player.getFoodData().needsFood() && player.getHealth() >= 20.0f) {
                stopFollowEating(client, player, gameMode);
                return false;
            }
        }

        return true;
    }

    private void stopFollowEating(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        if (!isEatingWhileFollowing) {
            preEatWeaponSlot = -1;
            return;
        }
        isEatingWhileFollowing = false;
        followEatingTicks = 0;
        lastFollowFoodLevel = -1;

        if (player != null && player.isUsingItem()) {
            ItemStack usingItem = player.getUseItem();
            if (!usingItem.is(Items.SHIELD)) {
                if (gameMode != null) gameMode.releaseUsingItem(player);
                player.stopUsingItem();
            }
        }

        if (client != null && client.options != null) {
            client.options.keyUse.setDown(false);
        }

        // إعادة تجهيز السلاح السابق (سيف أو فأس) والدرع باليد اليسرى فور الانتهاء من الأكل
        if (player != null && gameMode != null) {
            boolean restored = false;
            if (preEatWeaponSlot >= 0 && preEatWeaponSlot < 9) {
                ItemStack prevStack = player.getInventory().getItem(preEatWeaponSlot);
                if (BotEquipmentHelper.getAxeOrSwordTier(prevStack) > 0) {
                    BotEquipmentHelper.selectHotbarSlot(player, preEatWeaponSlot);
                    restored = true;
                }
            }
            if (!restored) {
                BotEquipmentHelper.ensureBestInMainHand(player, gameMode, BotEquipmentHelper::getAxeOrSwordTier, 1, 0);
            }
            preEatWeaponSlot = -1;
            ensureShieldInOffhand(player, gameMode);
        }
    }

    public static Vec3 calculateCreeperEscapeDirection(ClientLevel level, LocalPlayer player, Vec3 creeperPos) {
        double awayX = player.getX() - creeperPos.x;
        double awayZ = player.getZ() - creeperPos.z;
        double awayLen = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (awayLen > 0.001) {
            awayX /= awayLen;
            awayZ /= awayLen;
        } else {
            Vec3 look = player.getLookAngle();
            awayX = -look.x;
            awayZ = -look.z;
            double len = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (len > 0.001) {
                awayX /= len;
                awayZ /= len;
            } else {
                awayX = 0;
                awayZ = 1;
            }
        }

        // قياس المسافة بالأمتار في الاتجاه المعاكس المباشر
        double distDirect = FightBotController.measureDistanceToObstacle(level, player, awayX, awayZ, 15.0);
        if (distDirect >= 10.0) {
            return new Vec3(awayX, 0, awayZ);
        }

        // إذا كان هناك جدار أو عائق (< 10 بلوكات)، فحص الزوايا لاختيار أطول مسار سالك وخالٍ من العوائق
        double baseAngle = Math.atan2(awayZ, awayX);
        double bestDist = distDirect;
        Vec3 bestDir = new Vec3(awayX, 0, awayZ);

        for (int i = 1; i <= 8; i++) {
            double offset = i * (Math.PI / 12.0); // 15 درجة لكل خطوة
            // فحص جهة اليمين
            double angleR = baseAngle + offset;
            double dirXR = Math.cos(angleR);
            double dirZR = Math.sin(angleR);
            double distR = FightBotController.measureDistanceToObstacle(level, player, dirXR, dirZR, 15.0);
            if (distR > bestDist) {
                bestDist = distR;
                bestDir = new Vec3(dirXR, 0, dirZR);
                if (bestDist >= 10.0) break;
            }
            // فحص جهة اليسار
            double angleL = baseAngle - offset;
            double dirXL = Math.cos(angleL);
            double dirZL = Math.sin(angleL);
            double distL = FightBotController.measureDistanceToObstacle(level, player, dirXL, dirZL, 15.0);
            if (distL > bestDist) {
                bestDist = distL;
                bestDir = new Vec3(dirXL, 0, dirZL);
                if (bestDist >= 10.0) break;
            }
        }

        return bestDir;
    }

    // =========================================================================
    // منطق النوم بجانب القائد عند نومه وتوفر سرير قريب غير مشغول
    // =========================================================================

    private void resetSleepState() {
        targetSleepBedPos = null;
        sleepScanCooldown = 0;
        sleepInteractCooldown = 0;
        sleepAttemptCount = 0;
        failedBedCooldowns.clear();
        sleepingWithCompanion = false;
        preSleepSlot = -1;
        sleepReenterCooldown = 0;
        sleepMessageSent = false;
    }

    private boolean handleSleepWithCompanion(
            Minecraft client,
            LocalPlayer player,
            ClientLevel level,
            MultiPlayerGameMode gameMode,
            Player companion) {
        if (player.isSleeping()) {
            sleepingWithCompanion = true;
            targetSleepBedPos = null;
            sleepAttemptCount = 0;
            navigator.clearPathOnly();
            releaseMovement(client);
            return true;
        }

        if (targetSleepBedPos != null) {
            if (!isBedBlock(level, targetSleepBedPos)
                || failedBedCooldowns.containsKey(targetSleepBedPos)
                || isBedOccupiedOrCompanionBed(level, targetSleepBedPos, companion)
                || !isBedNearCompanion(targetSleepBedPos, companion, 14.0)) {
                targetSleepBedPos = null;
                sleepAttemptCount = 0;
                navigator.clearPathOnly();
            }
        }

        if (targetSleepBedPos == null) {
            if (sleepScanCooldown <= 0) {
                targetSleepBedPos = findAvailableBedNearCompanion(level, player, companion, 10);
                sleepScanCooldown = 12;
                sleepAttemptCount = 0;
                if (targetSleepBedPos != null && !sleepMessageSent && client.player != null) {
                    client.player.sendSystemMessage(Component.literal("§a[Bot] القائد نائم؛ يتوجه البوت للنوم في السرير المجاور..."));
                    sleepMessageSent = true;
                }
            }
            if (targetSleepBedPos == null) {
                return false;
            }
        }

        // إيقاف أي تناول طعام قبل التوجه والتفاعل مع السرير
        stopFollowEating(client, player, gameMode);

        double bx = targetSleepBedPos.getX() + 0.5;
        double by = targetSleepBedPos.getY() + 0.45;
        double bz = targetSleepBedPos.getZ() + 0.5;
        double distSq = player.distanceToSqr(bx, by, bz);
        double dyBed = Math.abs(targetSleepBedPos.getY() - player.getY());
        boolean hasClearView = hasClearInteractionLineToBed(level, player, targetSleepBedPos, bx, by, bz);

        if (distSq > 2.65 * 2.65 || dyBed > 2.2 || !hasClearView) {
            double navStopDist = hasClearView ? 2.1 : 1.0;
            BotNavigator.NavResult navRes = navigator.navigateTo(
                client,
                player,
                level,
                gameMode,
                new Vec3(bx, targetSleepBedPos.getY(), bz),
                true,
                navStopDist,
                false,
                45.0f,
                true,
                3500
            );
            if (navRes == BotNavigator.NavResult.BLOCKED_UNREACHABLE
                || (navRes == BotNavigator.NavResult.ARRIVED && !hasClearView)) {
                markBedFailed(targetSleepBedPos);
                navigator.reset(client);
                return false;
            }
            return true;
        }

        // وصل البوت للسرير وبخط رؤية مفتوح: التوقف، إيقاف الدرع أو السنيك، والنوم في السرير
        navigator.clearPathOnly();
        releaseMovement(client);
        if (client.options != null) {
            client.options.keyUse.setDown(false);
        }
        if (gameMode != null && player.isUsingItem()) {
            gameMode.releaseUsingItem(player);
            player.stopUsingItem();
        }

        BotEquipmentHelper.smoothLookAt(player, bx, by, bz, 50.0f);

        if (sleepInteractCooldown <= 0 && gameMode != null) {
            ItemStack mainHand = player.getMainHandItem();
            boolean heldSafe = mainHand.isEmpty() || BotEquipmentHelper.getSwordTier(mainHand) > 0;
            if (!heldSafe) {
                int safeSlot = BotEquipmentHelper.findSafeInteractionSlot(player);
                if (safeSlot != -1 && safeSlot != player.getInventory().getSelectedSlot()) {
                    if (preSleepSlot == -1) {
                        preSleepSlot = player.getInventory().getSelectedSlot();
                    }
                    BotEquipmentHelper.selectHotbarSlot(player, safeSlot);
                }
            }

            BlockHitResult hit = new BlockHitResult(
                new Vec3(bx, by, bz),
                Direction.UP,
                targetSleepBedPos,
                false
            );
            gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            sleepInteractCooldown = 14;
            sleepAttemptCount++;

            if (sleepAttemptCount >= 4) {
                markBedFailed(targetSleepBedPos);
                restorePostSleepWeapon(player, gameMode);
                navigator.clearPathOnly();
                return false;
            }
        }

        return true;
    }

    private void markBedFailed(BlockPos bedPos) {
        if (bedPos != null) {
            failedBedCooldowns.put(bedPos.immutable(), 100);
        }
        targetSleepBedPos = null;
        sleepAttemptCount = 0;
        sleepScanCooldown = 0;
    }

    private void restorePostSleepWeapon(LocalPlayer player, MultiPlayerGameMode gameMode) {
        if (player == null) {
            preSleepSlot = -1;
            return;
        }
        if (preSleepSlot >= 0 && preSleepSlot < 9 && preSleepSlot != player.getInventory().getSelectedSlot()) {
            ItemStack prevStack = player.getInventory().getItem(preSleepSlot);
            if (!prevStack.isEmpty()) {
                BotEquipmentHelper.selectHotbarSlot(player, preSleepSlot);
            }
        }
        preSleepSlot = -1;
        if (gameMode != null) {
            ensureShieldInOffhand(player, gameMode);
        }
    }

    private static boolean isBedBlock(ClientLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof BedBlock || state.is(BlockTags.BEDS);
    }

    private static boolean isBedNearCompanion(BlockPos bedPos, Player companion, double maxRadius) {
        if (bedPos == null || companion == null) return false;
        BlockPos origin = companion.getSleepingPos().orElse(companion.blockPosition());
        double dx = (bedPos.getX() + 0.5) - (origin.getX() + 0.5);
        double dy = (bedPos.getY() + 0.5) - (origin.getY() + 0.5);
        double dz = (bedPos.getZ() + 0.5) - (origin.getZ() + 0.5);
        return (dx * dx + dz * dz) <= maxRadius * maxRadius && Math.abs(dy) <= 5.0;
    }

    private static boolean isBedOccupiedOrCompanionBed(ClientLevel level, BlockPos pos, Player companion) {
        BlockState state = level.getBlockState(pos);
        if (state.getOptionalValue(BedBlock.OCCUPIED).orElse(false)) {
            return true;
        }
        if (companion != null) {
            BlockPos leaderBedPos = companion.getSleepingPos().orElse(null);
            if (leaderBedPos != null) {
                if (pos.equals(leaderBedPos)) {
                    return true;
                }
                BlockState leaderState = level.getBlockState(leaderBedPos);
                Direction leaderFacing = leaderState.getOptionalValue(BedBlock.FACING).orElse(null);
                if (leaderFacing != null && pos.equals(leaderBedPos.relative(leaderFacing.getOpposite()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private BlockPos findAvailableBedNearCompanion(ClientLevel level, LocalPlayer player, Player companion, int radius) {
        BlockPos origin = companion.getSleepingPos().orElse(companion.blockPosition());
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int dy = -3; dy <= 4; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (failedBedCooldowns.containsKey(pos)) {
                        continue;
                    }
                    if (!isBedBlock(level, pos) || isBedOccupiedOrCompanionBed(level, pos, companion)) {
                        continue;
                    }
                    BlockState aboveState = level.getBlockState(pos.above());
                    if (FarmPathfinder.isHazard(aboveState)
                        || (!FarmPathfinder.isPassable(level, pos.above()) && !isBedBlock(level, pos.above()))) {
                        continue;
                    }

                    double botDistSq = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    double compDistSq = origin.distSqr(pos);
                    double score = botDistSq + compDistSq * 0.25;
                    if (score < bestScore) {
                        bestScore = score;
                        best = pos.immutable();
                    }
                }
            }
        }
        return best;
    }

    private static boolean hasClearInteractionLineToBed(
            ClientLevel level,
            LocalPlayer player,
            BlockPos bedPos,
            double bx,
            double by,
            double bz) {
        Vec3 eye = player.getEyePosition();
        Vec3 target = new Vec3(bx, by, bz);
        Vec3 delta = target.subtract(eye);
        double dist = delta.length();
        if (dist < 0.2) return true;

        Direction bedFacing = level.getBlockState(bedPos).getOptionalValue(BedBlock.FACING).orElse(null);
        BlockPos adj1 = bedFacing != null ? bedPos.relative(bedFacing) : null;
        BlockPos adj2 = bedFacing != null ? bedPos.relative(bedFacing.getOpposite()) : null;

        int steps = Math.max(4, (int) Math.ceil(dist / 0.25));
        for (int i = 1; i < steps; i++) {
            double f = (double) i / steps;
            Vec3 sample = eye.add(delta.scale(f));
            BlockPos p = BlockPos.containing(sample.x, sample.y, sample.z);
            if (p.equals(bedPos)) {
                continue;
            }
            if ((p.equals(adj1) || p.equals(adj2)) && isBedBlock(level, p)) {
                continue;
            }

            BlockState state = level.getBlockState(p);
            if (state.isAir()) {
                continue;
            }
            if (FarmPathfinder.isOpenableDoor(state) && !state.getValue(DoorBlock.OPEN)) {
                return false;
            }
            if (FarmPathfinder.isOpenableGate(state) && !state.getValue(FenceGateBlock.OPEN)) {
                return false;
            }
            if (FarmPathfinder.isPassable(level, p)) {
                continue;
            }
            VoxelShape shape = state.getCollisionShape(level, p);
            if (!shape.isEmpty()) {
                double localY = sample.y - p.getY();
                if (localY <= shape.max(Direction.Axis.Y) + 0.05) {
                    return false;
                }
            }
        }
        return true;
    }
}
