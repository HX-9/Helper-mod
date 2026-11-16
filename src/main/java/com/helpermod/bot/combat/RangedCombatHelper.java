package com.helpermod.bot.combat;

import com.helpermod.bot.BotEquipmentHelper;
import com.helpermod.bot.BotFollowController;
import com.helpermod.bot.friend.BotFriendManager;
import com.helpermod.bot.path.BotNavigator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * مساعد القتال بعيد المدى المشترك (القوس والنشاب - Bow & Crossbow):
 * - يوازن تلقائياً بين مسافة الهدف وتوفر الذخيرة وسلامة الحلفاء على خط الرمي.
 * - يحسب ارتفاع القوس وتقدم حركة الهدف، وينتظر تكة استقرار تصويب (Server Sync) قبل الإطلاق.
 * - يتوقف فوراً عند اقتراب العدو للاشتباك القريب أو عند وجود مقذوف قادم يستلزم رفع الدرع.
 */
public class RangedCombatHelper {

    public enum RangedStatus {
        NOT_APPLICABLE,
        YIELD_FOR_SHIELD,
        CHARGING,
        FIRED
    }

    private static final double MIN_ENGAGE_DISTANCE = 5.0;
    private static final double HYSTERESIS_MELEE_BREAK_DISTANCE = 3.9;
    private static final double UNREACHABLE_MIN_DISTANCE = 3.0;
    private static final double MAX_RANGED_DISTANCE = 26.0;

    private boolean isUsingRanged = false;
    private boolean didStepBack = false;
    private int aimStableTicks = 0;
    private int postShotCooldownTicks = 0;
    private int blockedLineTicks = 0;

    public boolean isUsingRanged() {
        return isUsingRanged;
    }

    /**
     * تنفيذ دورة القتال بعيد المدى ضد هدف محدد في تكة العميل.
     */
    public RangedStatus tickRangedAttack(
            Minecraft client,
            LocalPlayer player,
            ClientLevel level,
            MultiPlayerGameMode gameMode,
            LivingEntity target,
            Player companion,
            boolean incomingProjectileThreat,
            boolean unreachableViaMelee) {
        if (client == null || player == null || level == null || gameMode == null || target == null) {
            reset(client, player, gameMode);
            return RangedStatus.NOT_APPLICABLE;
        }
        if (!target.isAlive() || target.isRemoved()) {
            reset(client, player, gameMode);
            return RangedStatus.NOT_APPLICABLE;
        }

        // عدم مقاطعة الأكل أو الشرب
        if (player.isUsingItem()
                && !BotEquipmentHelper.isRangedWeapon(player.getUseItem())
                && !player.getUseItem().is(Items.SHIELD)) {
            isUsingRanged = false;
            aimStableTicks = 0;
            return RangedStatus.NOT_APPLICABLE;
        }

        // إيقاف السحب فوراً لرفع الدرع عند وجود سهم أو مقذوف قادم
        if (incomingProjectileThreat) {
            reset(client, player, gameMode);
            return RangedStatus.YIELD_FOR_SHIELD;
        }

        // تحت الماء تفقد الأسهم سرعتها وتنحرف، وكذلك دون سلاح/ذخيرة صالحة
        if (player.isUnderWater() || !BotEquipmentHelper.hasUsableRangedWeapon(player)) {
            reset(client, player, gameMode);
            return RangedStatus.NOT_APPLICABLE;
        }

        double dist = BotEquipmentHelper.getEyeToHitboxDistance(player, target);
        double minDist = unreachableViaMelee
            ? UNREACHABLE_MIN_DISTANCE
            : (isUsingRanged ? HYSTERESIS_MELEE_BREAK_DISTANCE : MIN_ENGAGE_DISTANCE);

        if (dist < minDist || dist > MAX_RANGED_DISTANCE || !player.hasLineOfSight(target)) {
            reset(client, player, gameMode);
            return RangedStatus.NOT_APPLICABLE;
        }

        // خفض الدرع قبل سحب القوس/النشاب
        if (player.isUsingItem()
                && player.getUsedItemHand() == InteractionHand.OFF_HAND
                && player.getUseItem().is(Items.SHIELD)) {
            client.options.keyUse.setDown(false);
            gameMode.releaseUsingItem(player);
        }

        if (postShotCooldownTicks > 0) {
            postShotCooldownTicks--;
        }

        ItemStack mainHand = player.getMainHandItem();
        boolean holdingValidRanged = BotEquipmentHelper.getRangedWeaponTier(mainHand, BotEquipmentHelper.hasAmmo(player)) > 0;
        if (!holdingValidRanged) {
            if (isUsingRanged || (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND)) {
                stopMainHandUse(client, player, gameMode);
            }
            BotEquipmentHelper.ensureBestRangedInMainHand(player, gameMode, 1);
            mainHand = player.getMainHandItem();
            if (BotEquipmentHelper.getRangedWeaponTier(mainHand, BotEquipmentHelper.hasAmmo(player)) <= 0) {
                reset(client, player, gameMode);
                return RangedStatus.NOT_APPLICABLE;
            }
        } else if (!player.isUsingItem() && BotEquipmentHelper.isCrossbow(mainHand) && !CrossbowItem.isCharged(mainHand)) {
            // إذا كان هناك نشاب مشحون جاهز أو قوس أفضل، يتم تبديله قبل بدء الشحن
            BotEquipmentHelper.ensureBestRangedInMainHand(player, gameMode, 1);
            mainHand = player.getMainHandItem();
        }

        // إيقاف الركض والتقدم أثناء التصويب، مع تراجع خفيف آمن إذا كان العدو يقترب (< 8 بلوكات)
        BotNavigator.releaseJumpOrMaintainWaterBuoyancy(client, player, level, target);
        client.options.keySprint.setDown(false);
        player.setSprinting(false);
        client.options.keyUp.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);

        boolean shouldStepBack = !unreachableViaMelee && dist < 8.0
            && !BotNavigator.isHazardOrDropAhead(level, player, player.getX() - target.getX(), player.getZ() - target.getZ());
        client.options.keyDown.setDown(shouldStepBack);
        didStepBack = shouldStepBack;

        // حساب زاوية التصويب الباليستية مع تعويض السقوط والسرعة
        Vec3 aimPoint = computeBallisticAimPoint(player, target, BotEquipmentHelper.isCrossbow(mainHand));
        AimResult aim = applySmoothBallisticAim(player, aimPoint, 42.0f);
        if (aim.aligned) {
            aimStableTicks++;
        } else {
            aimStableTicks = 0;
        }

        boolean friendlyBlocking = hasFriendlyInLineOfFire(level, player, target, companion, aimPoint, dist);
        if (friendlyBlocking) {
            blockedLineTicks++;
            if (blockedLineTicks > 30) {
                reset(client, player, gameMode);
                return RangedStatus.NOT_APPLICABLE;
            }
        } else {
            blockedLineTicks = 0;
        }

        // منطق النشاب (Crossbow)
        if (BotEquipmentHelper.isCrossbow(mainHand)) {
            if (CrossbowItem.isCharged(mainHand)) {
                if (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
                    stopMainHandUse(client, player, gameMode);
                    return RangedStatus.CHARGING;
                }
                client.options.keyUse.setDown(false);
                if (postShotCooldownTicks == 0 && aimStableTicks >= 1 && !friendlyBlocking) {
                    gameMode.useItem(player, InteractionHand.MAIN_HAND);
                    client.options.keyUse.setDown(false);
                    isUsingRanged = false;
                    aimStableTicks = 0;
                    postShotCooldownTicks = 3;
                    return RangedStatus.FIRED;
                }
                return RangedStatus.CHARGING;
            }

            // شحن النشاب غير المشحون
            if (!BotEquipmentHelper.hasAmmo(player)) {
                reset(client, player, gameMode);
                return RangedStatus.NOT_APPLICABLE;
            }

            if (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
                isUsingRanged = true;
                if (player.getTicksUsingItem() >= 27) {
                    stopMainHandUse(client, player, gameMode);
                }
            } else if (postShotCooldownTicks == 0) {
                startMainHandUse(client, player, gameMode);
            }
            return RangedStatus.CHARGING;
        }

        // منطق القوس (Bow)
        if (!BotEquipmentHelper.hasAmmo(player)) {
            reset(client, player, gameMode);
            return RangedStatus.NOT_APPLICABLE;
        }

        if (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
            isUsingRanged = true;
            int chargeTicks = player.getTicksUsingItem();
            if (chargeTicks >= 20 && aimStableTicks >= 1 && !friendlyBlocking && postShotCooldownTicks == 0) {
                stopMainHandUse(client, player, gameMode);
                aimStableTicks = 0;
                postShotCooldownTicks = 2;
                return RangedStatus.FIRED;
            }
        } else if (postShotCooldownTicks == 0) {
            startMainHandUse(client, player, gameMode);
        }

        return RangedStatus.CHARGING;
    }

    /**
     * إنهاء سحب القوس أو النشاب بأمان دون التأثير على الأكل أو الدرع.
     */
    public void reset(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        if ((isUsingRanged || didStepBack) && client != null && client.options != null) {
            client.options.keyUse.setDown(false);
            client.options.keyDown.setDown(false);
        }
        if (isUsingRanged && player != null && gameMode != null && player.isUsingItem()
                && player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && BotEquipmentHelper.isRangedWeapon(player.getUseItem())) {
            gameMode.releaseUsingItem(player);
        }
        isUsingRanged = false;
        didStepBack = false;
        aimStableTicks = 0;
        blockedLineTicks = 0;
    }

    public void reset(Minecraft client) {
        LocalPlayer player = client != null ? client.player : null;
        MultiPlayerGameMode gameMode = client != null ? client.gameMode : null;
        reset(client, player, gameMode);
        postShotCooldownTicks = 0;
    }

    private void startMainHandUse(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        client.options.keyUse.setDown(true);
        if (!player.isUsingItem()) {
            gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
        isUsingRanged = true;
    }

    private void stopMainHandUse(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        if (client != null && client.options != null) {
            client.options.keyUse.setDown(false);
        }
        if (player != null && gameMode != null && player.isUsingItem()
                && player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && BotEquipmentHelper.isRangedWeapon(player.getUseItem())) {
            gameMode.releaseUsingItem(player);
        }
        isUsingRanged = false;
    }

    private Vec3 computeBallisticAimPoint(LocalPlayer player, LivingEntity target, boolean crossbow) {
        double projectileSpeed = crossbow ? 3.15 : 3.0;
        double baseTargetY = target.getY() + target.getBbHeight() * 0.65;
        Vec3 rawTarget = new Vec3(target.getX(), baseTargetY, target.getZ());

        double dist = player.getEyePosition().distanceTo(rawTarget);
        double flightTicks = dist / projectileSpeed;

        Vec3 vel = target.getDeltaMovement();
        double leadX = target.getX() + vel.x * flightTicks * 0.85;
        double leadZ = target.getZ() + vel.z * flightTicks * 0.85;
        double leadY = baseTargetY + Math.max(-0.15, Math.min(0.35, vel.y)) * flightTicks * 0.45;

        // تعويض جاذبية السهم في ماينكرافت (g ≈ 0.05 لكل تكة)
        double gravityDrop = 0.5 * 0.05 * flightTicks * flightTicks;
        double lift = Math.min(6.0, gravityDrop);

        return new Vec3(leadX, leadY + lift, leadZ);
    }

    private record AimResult(boolean aligned) {}

    private AimResult applySmoothBallisticAim(LocalPlayer player, Vec3 aimPoint, float maxTurnDegrees) {
        double dx = aimPoint.x - player.getX();
        double dy = aimPoint.y - player.getEyeY();
        double dz = aimPoint.z - player.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float targetPitch = (float) -(Math.atan2(dy, distXZ) * 180.0 / Math.PI);

        float yawDiff = BotEquipmentHelper.wrapDegrees(targetYaw - player.getYRot());
        float pitchDiff = BotEquipmentHelper.wrapDegrees(targetPitch - player.getXRot());

        BotEquipmentHelper.smoothLookAt(player, aimPoint.x, aimPoint.y, aimPoint.z, maxTurnDegrees);

        float remainingYaw = Math.abs(BotEquipmentHelper.wrapDegrees(targetYaw - player.getYRot()));
        float remainingPitch = Math.abs(BotEquipmentHelper.wrapDegrees(targetPitch - player.getXRot()));
        boolean aligned = remainingYaw <= 3.5f && remainingPitch <= 3.5f
            && Math.abs(yawDiff) <= maxTurnDegrees + 3.5f
            && Math.abs(pitchDiff) <= maxTurnDegrees + 3.5f;
        return new AimResult(aligned);
    }

    private boolean hasFriendlyInLineOfFire(
            ClientLevel level,
            LocalPlayer player,
            LivingEntity target,
            Player companion,
            Vec3 aimPoint,
            double maxCheckDist) {
        Vec3 eye = player.getEyePosition();
        Vec3 ray = aimPoint.subtract(eye);
        double rayLen = ray.length();
        if (rayLen < 0.5) return false;
        Vec3 dir = ray.scale(1.0 / rayLen);

        AABB searchBox = new AABB(eye, aimPoint).inflate(1.5);
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, searchBox,
            e -> e != player && e != target && e.isAlive() && !e.isRemoved());

        UUID followUuid = BotFollowController.getInstance().getTargetPlayerUUID();
        for (LivingEntity entity : candidates) {
            boolean friendly = entity == companion
                || (followUuid != null && followUuid.equals(entity.getUUID()))
                || (entity instanceof Player p && BotFriendManager.getInstance().isFriend(p))
                || (entity instanceof TamableAnimal tamed && tamed.isTame());
            if (!friendly) continue;

            Vec3 center = entity.getBoundingBox().getCenter();
            Vec3 rel = center.subtract(eye);
            double along = rel.dot(dir);
            if (along > 0.3 && along < Math.min(rayLen, maxCheckDist) - 0.3) {
                double perpSq = rel.subtract(dir.scale(along)).lengthSqr();
                if (perpSq < 1.1) {
                    return true;
                }
            }
        }
        return false;
    }
}
