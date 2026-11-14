package com.helpermod.bot.path;

import com.helpermod.bot.BotEquipmentHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * وحدة ملاحة مشتركة وموحدة تستخدم خوارزمية A* المحسنة لوضع الاتباع والقتال والهروب:
 * - تفتح الأبواب والبوابات استباقياً قبل الاصطدام بها
 * - تمنع السقوط في الحفر والمخاطر وقص الزوايا الضيقة
 * - تمنع الاندفاع في خط مستقيم عند تعذر وجود مسار سالك
 */
@SuppressWarnings("unused")
public class BotNavigator {

    public enum NavResult {
        ARRIVED,
        MOVING,
        BLOCKED_UNREACHABLE
    }

    private final List<BlockPos> currentPath = new ArrayList<>();
    private BlockPos lastGoalPos = null;
    private int pathRecalcCooldown = 0;
    private int doorInteractCooldown = 0;
    private int jumpCooldown = 0;

    // كاشف التعثر بنافذة زمنية (10 تكات) لمنع التصفير بسبب التذبذب الطفيف عند الحواف
    private Vec3 stuckWindowAnchorPos = null;
    private int stuckWindowTicks = 0;
    private int stuckStages = 0;
    private int strafeTicks = 0;
    private boolean strafeRight = true;

    // حالة استعادة الهواء والتنفس تحت الماء عند انخفاض الأكسجين
    private boolean recoveringAir = false;
    private final List<BlockPos> airEscapePath = new ArrayList<>();
    private int airEscapeRecalcCooldown = 0;

    public void tickCooldowns() {
        if (pathRecalcCooldown > 0) pathRecalcCooldown--;
        if (doorInteractCooldown > 0) doorInteractCooldown--;
        if (jumpCooldown > 0) jumpCooldown--;
        if (airEscapeRecalcCooldown > 0) airEscapeRecalcCooldown--;
    }

    public void reset(Minecraft client) {
        currentPath.clear();
        lastGoalPos = null;
        pathRecalcCooldown = 0;
        doorInteractCooldown = 0;
        jumpCooldown = 0;
        stuckWindowAnchorPos = null;
        stuckWindowTicks = 0;
        stuckStages = 0;
        strafeTicks = 0;
        recoveringAir = false;
        airEscapePath.clear();
        airEscapeRecalcCooldown = 0;
        BotEquipmentHelper.releaseMovementKeys(client);
    }

    public void clearPathOnly() {
        currentPath.clear();
        lastGoalPos = null;
        pathRecalcCooldown = 0;
    }

    public NavResult navigateTo(
            Minecraft client,
            LocalPlayer player,
            ClientLevel level,
            MultiPlayerGameMode gameMode,
            Vec3 destination,
            boolean allowSprint,
            double stopDist,
            boolean isEating,
            float maxTurnSpeed,
            boolean rotateLook,
            int maxNodes) {
        if (client == null || player == null || level == null || destination == null) {
            return NavResult.BLOCKED_UNREACHABLE;
        }

        // 0. حارس التنفس تحت الماء: إذا احتاج البوت للأكسجين يعطي الأولوية للسطح/الجيب الهوائي ثم يعاود
        if (handleUrgentWaterBreathing(client, player, level, gameMode)) {
            return NavResult.MOVING;
        }

        // 1. التحقق من الوصول ثلاثي الأبعاد للهدف
        double finalDx = destination.x - player.getX();
        double finalDz = destination.z - player.getZ();
        double finalDy = Math.abs(destination.y - player.getY());
        double horizDistSq = finalDx * finalDx + finalDz * finalDz;
        if (horizDistSq <= stopDist * stopDist && finalDy <= Math.max(1.8, stopDist + 0.5)) {
            BotEquipmentHelper.releaseMovementKeys(client);
            if (isEntityInWater(level, player)) {
                if (destination.y < player.getY() - 0.8 && getAirRatio(player) > 0.45) {
                    client.options.keyJump.setDown(false);
                } else {
                    maintainWaterBuoyancy(client, player, level, null);
                }
            }
            currentPath.clear();
            lastGoalPos = null;
            stuckStages = 0;
            stuckWindowTicks = 0;
            stuckWindowAnchorPos = null;
            return NavResult.ARRIVED;
        }

        BlockPos goalPos = BlockPos.containing(destination.x, destination.y, destination.z);

        // 2. كاشف التعثر بنافذة زمنية (كل 10 تكات)
        updateStuckDetector(client, player, level, gameMode, destination);

        // 3. التحقق من صلاحية المسار الحالي وإعادة الحساب عند الحاجة
        boolean pathInvalid = isCurrentPathBlocked(level);
        boolean goalChanged = lastGoalPos == null
            || goalPos.distSqr(lastGoalPos) > 2.25
            || Math.abs(goalPos.getY() - lastGoalPos.getY()) >= 1;
        boolean needNewPath = currentPath.isEmpty() || pathInvalid || (pathRecalcCooldown <= 0 && goalChanged);

        if (needNewPath && pathRecalcCooldown <= 0) {
            lastGoalPos = goalPos;
            pathRecalcCooldown = 8;
            FarmPathfinder.PathResult res = FarmPathfinder.findPathDetailed(
                level,
                player.blockPosition(),
                goalPos,
                maxNodes,
                FarmPathfinder.PathOptions.COMBAT_FOLLOW
            );
            currentPath.clear();
            if (!res.path().isEmpty()) {
                currentPath.addAll(res.path());
                // إزالة العقدة الأولى إن كان البوت واقفاً فوق مركزها بالفعل ومعه عقدة تالية
                while (currentPath.size() > 1) {
                    BlockPos n0 = currentPath.get(0);
                    double d0x = player.getX() - (n0.getX() + 0.5);
                    double d0z = player.getZ() - (n0.getZ() + 0.5);
                    if (d0x * d0x + d0z * d0z < 0.25 && Math.abs(player.getY() - n0.getY()) < 0.75) {
                        currentPath.remove(0);
                    } else {
                        break;
                    }
                }
            } else {
                pathRecalcCooldown = 12;
            }
        }

        // 4. تحديد الهدف اللحظي التالي دون السقوط في خط مستقيم أعمى عند انسداد الطريق
        boolean inWater = isEntityInWater(level, player);
        Vec3 nextTarget;
        if (!currentPath.isEmpty()) {
            // تنعيم آمن: تخطي العقدة 0 فقط إذا كانت العقدة 1 قريبة وآمنة ومكشوفة بدون حفر أو حوائط
            if (currentPath.size() >= 2) {
                BlockPos node1 = currentPath.get(1);
                double d1x = player.getX() - (node1.getX() + 0.5);
                double d1z = player.getZ() - (node1.getZ() + 0.5);
                if (d1x * d1x + d1z * d1z < 1.15
                    && Math.abs(player.getY() - node1.getY()) < 0.6
                    && isDirectGroundStepSafe(level, player.position(), Vec3.atBottomCenterOf(node1), 2.5)) {
                    currentPath.remove(0);
                }
            }

            BlockPos nextNode = currentPath.get(0);
            double nx = nextNode.getX() + 0.5;
            double ny = nextNode.getY();
            double nz = nextNode.getZ() + 0.5;

            double ndx = player.getX() - nx;
            double ndz = player.getZ() - nz;
            double reachThresholdSq = (currentPath.size() == 1) ? 0.49 : (allowSprint ? 0.56 : 0.42);
            double yReachLimit = inWater ? 0.75 : 1.2;
            if (ndx * ndx + ndz * ndz < reachThresholdSq && Math.abs(player.getY() - ny) < yReachLimit) {
                currentPath.remove(0);
                if (!currentPath.isEmpty()) {
                    nextNode = currentPath.get(0);
                    nextTarget = new Vec3(nextNode.getX() + 0.5, nextNode.getY(), nextNode.getZ() + 0.5);
                } else if (isDirectGroundStepSafe(level, player.position(), destination, Math.max(stopDist + 3.0, 5.0))) {
                    nextTarget = destination;
                } else {
                    BotEquipmentHelper.releaseMovementKeys(client);
                    maintainWaterBuoyancy(client, player, level, null);
                    return NavResult.BLOCKED_UNREACHABLE;
                }
            } else {
                nextTarget = new Vec3(nx, ny, nz);
            }
        } else {
            // إذا لم يجد A* أي مسار، لا نتجه بخط مستقيم إلا إذا كان الخط المباشر قصيراً وآمناً بالكامل
            if (isDirectGroundStepSafe(level, player.position(), destination, 6.0)) {
                nextTarget = destination;
            } else {
                BotEquipmentHelper.releaseMovementKeys(client);
                maintainWaterBuoyancy(client, player, level, null);
                return NavResult.BLOCKED_UNREACHABLE;
            }
        }

        // 5. فتح الأبواب وبوابات السياج استباقياً قبل التصادم
        openDoorsAhead(player, level, gameMode, nextTarget.x, nextTarget.z);

        // 6. تنفيذ الحركة الآمنة مع فحص المخاطر والسقوط والسباحة والغوص
        executeMove(client, player, level, nextTarget.x, nextTarget.y, nextTarget.z, allowSprint, isEating, maxTurnSpeed, rotateLook);
        return NavResult.MOVING;
    }

    private void updateStuckDetector(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode, Vec3 destination) {
        if (stuckWindowAnchorPos == null) {
            stuckWindowAnchorPos = player.position();
            stuckWindowTicks = 0;
            return;
        }

        stuckWindowTicks++;
        if (stuckWindowTicks >= 10) {
            double movedSq = player.position().distanceToSqr(stuckWindowAnchorPos);
            stuckWindowAnchorPos = player.position();
            stuckWindowTicks = 0;
            if (movedSq < 0.12) {
                stuckStages++;
            } else {
                stuckStages = 0;
                strafeTicks = 0;
            }
        }

        if (stuckStages >= 1) {
            openDoorsAhead(player, level, gameMode, destination.x, destination.z);

            boolean hasObs = isObstacleDirectlyAhead(player, level, destination.x, destination.z)
                || hasStepDirectlyAhead(player, level, destination.x, destination.z);
            if (player.onGround() && hasObs && isCeilingClear(level, player.blockPosition()) && jumpCooldown <= 0) {
                client.options.keyJump.setDown(true);
                jumpCooldown = 12;
            }

            // مناورة جانبية آمنة فقط إذا لم تكن الجهة الجانبية خطرة أو هاوية
            strafeTicks++;
            double lookRad = Math.toRadians(player.getYRot());
            double rightX = -Math.cos(lookRad);
            double rightZ = -Math.sin(lookRad);
            double checkSideX = strafeRight ? rightX : -rightX;
            double checkSideZ = strafeRight ? rightZ : -rightZ;
            if (!isHazardOrDropAhead(level, player, checkSideX, checkSideZ)) {
                client.options.keyRight.setDown(strafeRight);
                client.options.keyLeft.setDown(!strafeRight);
            } else {
                client.options.keyRight.setDown(false);
                client.options.keyLeft.setDown(false);
                strafeRight = !strafeRight;
            }

            if (strafeTicks > 8) {
                strafeRight = !strafeRight;
                strafeTicks = 0;
            }

            if (stuckStages >= 2) {
                currentPath.clear();
                lastGoalPos = null;
                pathRecalcCooldown = 0;
                stuckStages = 0;
                client.options.keyJump.setDown(false);
            }
        } else {
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
        }
    }

    private boolean isCurrentPathBlocked(ClientLevel level) {
        if (currentPath.isEmpty()) return false;
        int limit = Math.min(2, currentPath.size());
        for (int i = 0; i < limit; i++) {
            BlockPos node = currentPath.get(i);
            if (!FarmPathfinder.isFeetPassable(level, node)
                || !FarmPathfinder.isPassable(level, node.above())
                || FarmPathfinder.isHazard(level.getBlockState(node))
                || FarmPathfinder.isHazard(level.getBlockState(node.below()))
                || FarmPathfinder.isWaterColumnHazardous(level, node)) {
                return true;
            }
        }
        return false;
    }

    private void executeMove(
            Minecraft client,
            LocalPlayer player,
            ClientLevel level,
            double tx,
            double ty,
            double tz,
            boolean sprint,
            boolean isEating,
            float maxTurnSpeed,
            boolean rotateLook) {
        boolean inWater = isEntityInWater(level, player);
        boolean eyesInWater = isEyesInWater(level, player);

        double dx = tx - player.getX();
        double dz = tz - player.getZ();
        double distSq = dx * dx + dz * dz;
        double dyToTarget = ty - player.getY();

        if (inWater) {
            if (rotateLook && distSq > 0.01) {
                double lookAimY = (dyToTarget < -0.5) ? (ty + 0.35) : (dyToTarget > 0.6 ? ty + 1.2 : player.getEyeY());
                BotEquipmentHelper.smoothLookAt(player, tx, lookAimY, tz, maxTurnSpeed);
            }

            if (distSq >= 0.04) {
                double moveLen = Math.sqrt(distSq);
                double normX = dx / moveLen;
                double normZ = dz / moveLen;
                BlockPos aheadPos = BlockPos.containing(player.getX() + normX * 0.85, player.getY() + 0.1, player.getZ() + normZ * 0.85);
                if (FarmPathfinder.isHazard(level.getBlockState(aheadPos))
                    || FarmPathfinder.isHazard(level.getBlockState(aheadPos.below()))
                    || FarmPathfinder.isWaterColumnHazardous(level, aheadPos)) {
                    client.options.keyUp.setDown(false);
                    client.options.keySprint.setDown(false);
                    player.setSprinting(false);
                    currentPath.clear();
                    lastGoalPos = null;
                    pathRecalcCooldown = 0;
                    return;
                }
                client.options.keyUp.setDown(true);
                boolean canSprint = sprint && !isEating && player.getFoodData().getFoodLevel() > 6;
                client.options.keySprint.setDown(canSprint);
                player.setSprinting(canSprint);
            } else {
                client.options.keyUp.setDown(false);
                client.options.keySprint.setDown(false);
                player.setSprinting(false);
            }

            boolean airSafeForDive = getAirRatio(player) > 0.38;
            if (airSafeForDive && dyToTarget < -0.65) {
                client.options.keyJump.setDown(false);
                client.options.keyShift.setDown(distSq < 1.6 && dyToTarget < -1.1);
            } else {
                client.options.keyShift.setDown(false);
                boolean hasCeilingBlock = !isCeilingClear(level, player.blockPosition());
                boolean stepOutUp = dyToTarget > 0.45;
                boolean shouldFloat = (eyesInWater && !hasCeilingBlock)
                    || stepOutUp
                    || (!player.onGround() && !hasCeilingBlock && dyToTarget >= -0.35);
                if (shouldFloat) {
                    client.options.keyJump.setDown(true);
                } else {
                    client.options.keyJump.setDown(
                        (isObstacleDirectlyAhead(player, level, tx, tz) || hasStepDirectlyAhead(player, level, tx, tz))
                            && !hasCeilingBlock
                    );
                }
            }
            return;
        }

        client.options.keyShift.setDown(false);

        if (distSq < 0.04) {
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            player.setSprinting(false);
            return;
        }

        if (rotateLook) {
            BotEquipmentHelper.smoothLookAt(player, tx, player.getEyeY(), tz, maxTurnSpeed);
        }

        double moveLen = Math.sqrt(distSq);
        double normX = dx / moveLen;
        double normZ = dz / moveLen;

        // فحص المخاطر والسقوط بعرض جسم البوت قبل التقدم مباشرة
        if (isHazardOrDropAhead(level, player, normX, normZ)) {
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            player.setSprinting(false);
            currentPath.clear();
            lastGoalPos = null;
            pathRecalcCooldown = 0;
            return;
        }

        client.options.keyUp.setDown(true);
        boolean canSprint = sprint && !isEating && player.getFoodData().getFoodLevel() > 6;
        client.options.keySprint.setDown(canSprint);
        player.setSprinting(canSprint);

        boolean isNodeUp = !currentPath.isEmpty() && (ty - player.getY() >= 0.62);
        boolean ceilingOk = isCeilingClear(level, player.blockPosition());
        boolean obstacleAhead = isObstacleDirectlyAhead(player, level, tx, tz) || hasStepDirectlyAhead(player, level, tx, tz);
        boolean needsJump = (isNodeUp || obstacleAhead) && player.onGround() && ceilingOk && jumpCooldown <= 0;

        if (needsJump) {
            client.options.keyJump.setDown(true);
            jumpCooldown = 10;
        } else if (stuckStages == 0) {
            client.options.keyJump.setDown(false);
        }
    }

    /**
     * فتح الأبواب وبوابات السياج المغلقة استباقياً في العقد القادمة أو أمام البوت مباشرة.
     */
    public void openDoorsAhead(LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode, double tx, double tz) {
        if (player == null || level == null || gameMode == null || doorInteractCooldown > 0) return;

        // 1. فحص العقدتين القادمتين في مسار A*
        int checkNodes = Math.min(2, currentPath.size());
        for (int i = 0; i < checkNodes; i++) {
            BlockPos node = currentPath.get(i);
            for (int oy = 0; oy <= 1; oy++) {
                BlockPos cand = node.above(oy);
                if (tryInteractDoor(player, level, gameMode, cand, 2.6)) {
                    return;
                }
            }
        }

        // 2. فحص مباشر أمام اتجاه الحركة (1.25 إلى 2.0 بلوكة)
        double dx = tx - player.getX();
        double dz = tz - player.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 0.1) {
            double normX = dx / len;
            double normZ = dz / len;
            for (double d = 0.85; d <= 1.85; d += 0.5) {
                BlockPos pFeet = BlockPos.containing(player.getX() + normX * d, player.getY() + 0.2, player.getZ() + normZ * d);
                if (tryInteractDoor(player, level, gameMode, pFeet, 2.6)
                    || tryInteractDoor(player, level, gameMode, pFeet.above(), 2.6)) {
                    return;
                }
            }
        }
    }

    private boolean tryInteractDoor(LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode, BlockPos pos, double maxDist) {
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > maxDist * maxDist) {
            return false;
        }
        BlockState st = level.getBlockState(pos);
        boolean isDoor = FarmPathfinder.isOpenableDoor(st) && !st.getValue(DoorBlock.OPEN);
        boolean isGate = FarmPathfinder.isOpenableGate(st) && !st.getValue(FenceGateBlock.OPEN);
        if (isDoor || isGate) {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            doorInteractCooldown = 8;
            return true;
        }
        return false;
    }

    /**
     * التحقق من سلامة السير في خط مستقيم قصير (لا سياج، لا جدار، لا مخاطر، ولا هاوية).
     */
    public static boolean isDirectGroundStepSafe(ClientLevel level, Vec3 from, Vec3 to, double maxDirectDist) {
        if (level == null || from == null || to == null) return false;
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        if (horizDist > maxDirectDist || Math.abs(to.y - from.y) > 2.5) {
            return false;
        }
        if (horizDist < 0.25) return true;

        int steps = Math.max(2, (int) Math.ceil(horizDist / 0.45));
        int currentSimY = (int) Math.floor(from.y + 0.1);
        for (int i = 1; i <= steps; i++) {
            double f = (double) i / steps;
            double cx = from.x + dx * f;
            double cz = from.z + dz * f;

            BlockPos feet = BlockPos.containing(cx, currentSimY, cz);
            BlockState feetState = level.getBlockState(feet);
            BlockState headState = level.getBlockState(feet.above());
            BlockState belowState = level.getBlockState(feet.below());

            if (FarmPathfinder.isHazard(feetState) || FarmPathfinder.isHazard(headState) || FarmPathfinder.isHazard(belowState)
                || FarmPathfinder.isWaterColumnHazardous(level, feet)) {
                return false;
            }
            if (FarmPathfinder.isFenceOrWall(feetState) || FarmPathfinder.isFenceOrWall(headState) || FarmPathfinder.isFenceOrWall(belowState)) {
                return false;
            }

            if (!FarmPathfinder.isFeetPassable(level, feet) || !FarmPathfinder.isPassable(level, feet.above())) {
                // تجربة الصعود لدرجة واحدة إن كانت الكتلة الحاملة صلبة وآمنة والمساحة فوقها سالكة
                BlockPos stepUp = feet.above();
                var feetShape = feetState.getCollisionShape(level, feet);
                boolean solidStepFloor = !feetShape.isEmpty()
                    && feetShape.max(Direction.Axis.Y) >= 0.45
                    && !FarmPathfinder.isChestOrContainer(feetState);
                if (solidStepFloor
                    && FarmPathfinder.isFeetPassable(level, stepUp)
                    && FarmPathfinder.isPassable(level, stepUp.above())
                    && FarmPathfinder.isPassable(level, feet.above(2))) {
                    currentSimY++;
                    continue;
                }
                return false;
            }

            // فحص عدم وجود هاوية أعمق من 3 بلوكات أو لافا/ماغما خطرة
            if (!hasSafeLandingDepth(level, feet, 3)) {
                return false;
            }
            if (belowState.isAir() && hasSafeFloorAt(level, feet.below(2))) {
                currentSimY--;
            }
        }
        return true;
    }

    /**
     * فحص وجود خطر بيئي أو سقوط خطير أمام البوت بعرض جسم اللاعب (±0.35 بلوكة).
     */
    public static boolean isHazardOrDropAhead(ClientLevel level, LocalPlayer player, double dirX, double dirZ) {
        if (level == null || player == null) return false;
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 0.001) return false;
        double nx = dirX / len;
        double nz = dirZ / len;
        double sideX = -nz;
        double sideZ = nx;

        double lookDist = 0.85;
        double[] lateralOffsets = {-0.32, 0.0, 0.32};
        for (double lat : lateralOffsets) {
            double cx = player.getX() + nx * lookDist + sideX * lat;
            double cz = player.getZ() + nz * lookDist + sideZ * lat;
            BlockPos feet = BlockPos.containing(cx, player.getY() + 0.1, cz);
            BlockPos below = feet.below();

            if (FarmPathfinder.isHazard(level.getBlockState(feet))
                || FarmPathfinder.isHazard(level.getBlockState(feet.above()))
                || FarmPathfinder.isHazard(level.getBlockState(below))
                || FarmPathfinder.isWaterColumnHazardous(level, feet)) {
                return true;
            }

            if (!hasSafeLandingDepth(level, feet, 3)) {
                return true;
            }
        }
        return false;
    }

    /**
     * التحقق من سلامة القفز للأمام (سقف خالٍ + هبوط آمن بدون مخاطر أو سقوط > 3 بلوكات) خصوصاً أثناء هروب الكريبر.
     */
    public static boolean isSafeToJumpAhead(ClientLevel level, LocalPlayer player, double dirX, double dirZ) {
        if (level == null || player == null) return false;
        if (!isCeilingClear(level, player.blockPosition())) return false;

        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 0.001) return false;
        double nx = dirX / len;
        double nz = dirZ / len;

        for (double dist : new double[]{1.2, 2.2}) {
            double cx = player.getX() + nx * dist;
            double cz = player.getZ() + nz * dist;
            BlockPos feet = BlockPos.containing(cx, player.getY() + 0.1, cz);
            if (FarmPathfinder.isHazard(level.getBlockState(feet))
                || FarmPathfinder.isHazard(level.getBlockState(feet.below()))
                || FarmPathfinder.isWaterColumnHazardous(level, feet)
                || !hasSafeLandingDepth(level, feet, 3)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasSafeLandingDepth(ClientLevel level, BlockPos feetPos, int maxDropDepth) {
        BlockState feetState = level.getBlockState(feetPos);
        if (FarmPathfinder.isHazard(feetState)) {
            return false;
        }
        if (FarmPathfinder.isWater(level, feetPos)) {
            return !FarmPathfinder.isWaterColumnHazardous(level, feetPos);
        }
        // إذا كانت البلوكة نفسها عند مستوى القدمين صلبة (مثل درجة صعود)، فهي تمثل أرضية صلبة
        if (!feetState.isAir() && !FarmPathfinder.isFeetPassable(level, feetPos)
            && !feetState.getCollisionShape(level, feetPos).isEmpty()) {
            return true;
        }
        for (int depth = 1; depth <= maxDropDepth + 1; depth++) {
            BlockPos check = feetPos.below(depth);
            BlockState st = level.getBlockState(check);
            if (FarmPathfinder.isHazard(st)) {
                return false;
            }
            if (depth <= maxDropDepth && FarmPathfinder.isWater(level, check)) {
                return !FarmPathfinder.isWaterColumnHazardous(level, check);
            }
            if (!st.isAir() && !st.getCollisionShape(level, check).isEmpty()) {
                return depth <= maxDropDepth + 1;
            }
        }
        return false;
    }

    private static boolean hasSafeFloorAt(ClientLevel level, BlockPos floorPos) {
        BlockState st = level.getBlockState(floorPos);
        return !st.isAir() && !FarmPathfinder.isHazard(st) && !st.getCollisionShape(level, floorPos).isEmpty();
    }

    public static boolean isCeilingClear(ClientLevel level, BlockPos pos) {
        return FarmPathfinder.isPassable(level, pos.above(2));
    }

    @SuppressWarnings("deprecation")
    public static boolean isObstacleDirectlyAhead(LocalPlayer player, ClientLevel level, double tx, double tz) {
        if (!player.horizontalCollision) return false;
        double dx = tx - player.getX();
        double dz = tz - player.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.05) return false;

        double dirX = dx / len;
        double dirZ = dz / len;

        BlockPos aheadPos = BlockPos.containing(player.getX() + dirX * 0.75, player.getY() + 0.2, player.getZ() + dirZ * 0.75);
        BlockState aheadState = level.getBlockState(aheadPos);
        if (aheadState.isAir()
            || !aheadState.blocksMotion()
            || FarmPathfinder.isFenceOrWall(aheadState)
            || FarmPathfinder.isOpenableDoor(aheadState)
            || FarmPathfinder.isOpenableGate(aheadState)) {
            return false;
        }
        var aheadShape = aheadState.getCollisionShape(level, aheadPos);
        if (aheadShape.isEmpty() || aheadShape.max(Direction.Axis.Y) <= 0.6) {
            return false;
        }

        BlockPos aboveAhead = aheadPos.above();
        BlockPos aboveAboveAhead = aheadPos.above(2);
        return FarmPathfinder.isFeetPassable(level, aboveAhead) && FarmPathfinder.isPassable(level, aboveAboveAhead);
    }

    @SuppressWarnings("deprecation")
    public static boolean hasStepDirectlyAhead(LocalPlayer player, ClientLevel level, double tx, double tz) {
        double dx = tx - player.getX();
        double dz = tz - player.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        if (distXZ < 0.1) return false;
        double normX = dx / distXZ;
        double normZ = dz / distXZ;

        BlockPos currentFoot = player.blockPosition();
        BlockPos aheadFoot = BlockPos.containing(player.getX() + normX * 0.75, player.getY() + 0.1, player.getZ() + normZ * 0.75);
        if (aheadFoot.equals(currentFoot)) {
            aheadFoot = BlockPos.containing(player.getX() + normX * 1.1, player.getY() + 0.1, player.getZ() + normZ * 1.1);
        }
        if (aheadFoot.equals(currentFoot)) return false;

        BlockState footState = level.getBlockState(aheadFoot);
        if (!footState.isAir()
            && footState.blocksMotion()
            && !FarmPathfinder.isFenceOrWall(footState)
            && !FarmPathfinder.isOpenableDoor(footState)
            && !FarmPathfinder.isOpenableGate(footState)) {
            var footShape = footState.getCollisionShape(level, aheadFoot);
            if (footShape.isEmpty() || footShape.max(Direction.Axis.Y) <= 0.6) {
                return false;
            }
            BlockPos aboveFoot = aheadFoot.above();
            BlockPos aboveAboveFoot = aheadFoot.above(2);
            return FarmPathfinder.isFeetPassable(level, aboveFoot) && FarmPathfinder.isPassable(level, aboveAboveFoot);
        }
        return false;
    }

    // =========================================================================
    // إدارة الغوص والعوم الآمن وحارس التنفس (منع الغرق دون تقييد الغوص)
    // =========================================================================

    public static boolean isEntityInWater(ClientLevel level, LocalPlayer player) {
        return player != null && (player.isInWater() || (level != null && FarmPathfinder.isWater(level, player.blockPosition())));
    }

    public static boolean isEyesInWater(ClientLevel level, LocalPlayer player) {
        if (level == null || player == null) return false;
        BlockPos eyePos = BlockPos.containing(player.getX(), player.getEyeY(), player.getZ());
        BlockPos slightlyBelowEye = BlockPos.containing(player.getX(), player.getEyeY() - 0.12, player.getZ());
        return FarmPathfinder.isWater(level, eyePos) || FarmPathfinder.isWater(level, slightlyBelowEye);
    }

    public static double getAirRatio(LocalPlayer player) {
        if (player == null) return 1.0;
        int maxAir = Math.max(1, player.getMaxAirSupply());
        return (double) Math.max(0, player.getAirSupply()) / maxAir;
    }

    public boolean isRecoveringAir() {
        return recoveringAir;
    }

    /**
     * التحقق من أن العمود فوق البوت مفتوح حتى الهواء بدون سقف جليدي أو جسر أو صخر يحجز الصعود.
     */
    public static boolean hasOpenWaterSurfaceAbove(ClientLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        for (int dy = 1; dy <= 24; dy++) {
            BlockPos check = pos.above(dy);
            if (FarmPathfinder.isWater(level, check)) {
                continue;
            }
            if (FarmPathfinder.isPassable(level, check) && FarmPathfinder.isPassable(level, check.above())) {
                return true;
            }
            return false;
        }
        return false;
    }

    /**
     * الحفاظ على طفو البوت فوق سطح الماء أثناء الثبات أو الانتظار أو القتال دون منعه من الغوص خلف هدف أدنى.
     */
    public static void maintainWaterBuoyancy(Minecraft client, LocalPlayer player, ClientLevel level, Entity focusEntity) {
        if (client == null || client.options == null || player == null || level == null) return;
        if (!isEntityInWater(level, player)) {
            client.options.keyShift.setDown(false);
            return;
        }

        boolean eyesInWater = isEyesInWater(level, player);
        double airRatio = getAirRatio(player);

        if (eyesInWater && airRatio <= 0.35) {
            client.options.keyShift.setDown(false);
            client.options.keyJump.setDown(true);
            return;
        }

        if (focusEntity != null && focusEntity.getY() < player.getY() - 1.2 && airRatio > 0.45) {
            client.options.keyJump.setDown(false);
            return;
        }

        client.options.keyShift.setDown(false);
        client.options.keyJump.setDown(eyesInWater || !player.onGround());
    }

    /**
     * حارس التنفس الطارئ: يترك البوت يغوص بحرية طالما الأكسجين كافٍ (> 35%)،
     * وعند انخفاضه يقوده فوراً لأقرب سطح مفتوح أو مخرج هوائي (تحت الجسور والكهوف المائية) حتى يستعيد أنفاسه (>= 85%).
     */
    public boolean handleUrgentWaterBreathing(
            Minecraft client,
            LocalPlayer player,
            ClientLevel level,
            MultiPlayerGameMode gameMode) {
        if (client == null || client.options == null || player == null || level == null) {
            recoveringAir = false;
            airEscapePath.clear();
            return false;
        }

        boolean inWater = isEntityInWater(level, player);
        boolean eyesInWater = isEyesInWater(level, player);
        double airRatio = getAirRatio(player);

        if (!inWater && !eyesInWater) {
            recoveringAir = false;
            airEscapePath.clear();
            return false;
        }

        if (eyesInWater && airRatio <= 0.35) {
            recoveringAir = true;
        } else if (!eyesInWater && airRatio >= 0.85) {
            recoveringAir = false;
            airEscapePath.clear();
        } else if (eyesInWater && airRatio >= 0.95) {
            // في حال استعاد الهواء في عمود فقاعات صاعد أو جيب هواء
            recoveringAir = false;
            airEscapePath.clear();
        }

        if (!recoveringAir) {
            return false;
        }

        // إيقاف أي عنصر مبطئ للحركة (درع أو طعام) لتسريع النجاة والتنفس
        client.options.keyUse.setDown(false);
        if (player.isUsingItem()) {
            if (gameMode != null) {
                gameMode.releaseUsingItem(player);
            }
            player.stopUsingItem();
        }
        client.options.keyShift.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);

        if (!eyesInWater) {
            // الرأس بالفعل فوق الماء ويتنفس: نحافظ على الطفو في المكان حتى يمتلئ شريط الأكسجين بأمان
            airEscapePath.clear();
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            player.setSprinting(false);
            maintainWaterBuoyancy(client, player, level, null);
            return true;
        }

        if (hasOpenWaterSurfaceAbove(level, player.blockPosition())) {
            airEscapePath.clear();
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            player.setSprinting(false);
            client.options.keyJump.setDown(true);
            return true;
        }

        // السقف مسدود فوق البوت (تحت جسر / جليد / كهف مائي): البحث عن أقرب جيب هواء أو مخرج للسطح عبر BFS
        if (airEscapePath.isEmpty() || airEscapeRecalcCooldown <= 0) {
            List<BlockPos> found = findNearestAirPocketPath(level, player.blockPosition(), 16);
            airEscapePath.clear();
            if (!found.isEmpty()) {
                airEscapePath.addAll(found);
            }
            airEscapeRecalcCooldown = 8;
        }

        if (!airEscapePath.isEmpty()) {
            followAirEscapeStep(client, player, level);
            return true;
        }

        // احتياط إذا كان في مكان مغلق خارج نطاق 16 بلوكة: الصعود مع محاولة التحرك للخارج
        client.options.keyJump.setDown(true);
        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(player.getFoodData().getFoodLevel() > 6);
        player.setSprinting(client.options.keySprint.isDown());
        return true;
    }

    private void followAirEscapeStep(Minecraft client, LocalPlayer player, ClientLevel level) {
        while (airEscapePath.size() > 1) {
            BlockPos n0 = airEscapePath.get(0);
            double dx0 = player.getX() - (n0.getX() + 0.5);
            double dz0 = player.getZ() - (n0.getZ() + 0.5);
            if (dx0 * dx0 + dz0 * dz0 < 0.55 && Math.abs(player.getY() - n0.getY()) < 0.85) {
                airEscapePath.remove(0);
            } else {
                break;
            }
        }

        if (airEscapePath.isEmpty()) {
            client.options.keyUp.setDown(false);
            client.options.keyJump.setDown(true);
            return;
        }

        BlockPos target = airEscapePath.get(0);
        double tx = target.getX() + 0.5;
        double ty = target.getY();
        double tz = target.getZ() + 0.5;
        double dx = tx - player.getX();
        double dz = tz - player.getZ();
        double horizSq = dx * dx + dz * dz;

        BotEquipmentHelper.smoothLookAt(player, tx, ty + 0.55, tz, 55.0f);

        boolean moveHoriz = horizSq > 0.04;
        client.options.keyUp.setDown(moveHoriz);
        boolean canSprint = moveHoriz && player.getFoodData().getFoodLevel() > 6;
        client.options.keySprint.setDown(canSprint);
        player.setSprinting(canSprint);

        double dy = ty - player.getY();
        if (dy > 0.25) {
            client.options.keyJump.setDown(true);
            client.options.keyShift.setDown(false);
        } else if (dy < -0.35) {
            client.options.keyJump.setDown(false);
            client.options.keyShift.setDown(horizSq < 1.0);
        } else {
            client.options.keyShift.setDown(false);
            // تحت السقف نتحاشى التصادم بالسقف حتى يظل التقدم الأفقي سريعاً، وعند خلو السقف نرتفع للسطح
            boolean hasCeiling = !isCeilingClear(level, player.blockPosition());
            client.options.keyJump.setDown(!hasCeiling);
        }
    }

    /**
     * البحث بالعرض (3D BFS) عن أقرب خانة هواء صالحة للتنفس أو فتحة سطحية مفتوحة.
     */
    private static List<BlockPos> findNearestAirPocketPath(ClientLevel level, BlockPos start, int maxRadius) {
        if (level == null || start == null) return Collections.emptyList();

        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();

        queue.add(start);
        visited.add(start);

        Direction[] dirs = {
            Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN
        };

        BlockPos airGoal = null;
        BlockPos bestFallback = null;
        double bestFallbackScore = -Double.MAX_VALUE;

        int iterations = 0;
        while (!queue.isEmpty() && iterations < 700) {
            iterations++;
            BlockPos curr = queue.poll();

            if (!curr.equals(start)) {
                BlockPos head = curr.above();
                if (!FarmPathfinder.isWater(level, head)
                    && level.getFluidState(head).isEmpty()
                    && FarmPathfinder.isFeetPassable(level, curr)
                    && FarmPathfinder.isPassable(level, head)) {
                    airGoal = curr;
                    break;
                }

                double score = (curr.getY() - start.getY()) * 2.5
                    + Math.sqrt(curr.distSqr(start)) * 0.4;
                if (score > bestFallbackScore) {
                    bestFallbackScore = score;
                    bestFallback = curr;
                }
            }

            for (Direction dir : dirs) {
                BlockPos next = curr.relative(dir);
                if (visited.contains(next)) continue;

                int dx = Math.abs(next.getX() - start.getX());
                int dz = Math.abs(next.getZ() - start.getZ());
                int dy = next.getY() - start.getY();
                if (dx > maxRadius || dz > maxRadius || dy < -4 || dy > 14) {
                    continue;
                }

                if (FarmPathfinder.isWaterColumnHazardous(level, next)
                    || FarmPathfinder.isHazard(level.getBlockState(next))
                    || FarmPathfinder.isHazard(level.getBlockState(next.above()))
                    || !FarmPathfinder.isFeetPassable(level, next)
                    || !FarmPathfinder.isPassable(level, next.above())) {
                    continue;
                }

                // إذا لم تكن الخانة مائية، نتحقق من وجود أرضية صالحة تحتها قبل السماح بالمشي عليها
                if (!FarmPathfinder.isWater(level, next) && !FarmPathfinder.isWater(level, next.below())) {
                    if (!hasSafeLandingDepth(level, next, 3)) {
                        continue;
                    }
                }

                visited.add(next);
                parent.put(next, curr);
                queue.add(next);
            }
        }

        BlockPos target = airGoal != null ? airGoal : bestFallback;
        if (target == null || target.equals(start)) {
            return Collections.emptyList();
        }

        List<BlockPos> path = new ArrayList<>();
        BlockPos step = target;
        while (step != null && !step.equals(start)) {
            path.add(step);
            step = parent.get(step);
        }
        Collections.reverse(path);
        return path;
    }

    /**
     * تحرير زر القفز على اليابسة، أو الحفاظ على الطفو المائي الآمن في المياه العميقة.
     */
    public static void releaseJumpOrMaintainWaterBuoyancy(Minecraft client, LocalPlayer player, ClientLevel level, Entity focusEntity) {
        if (client == null || client.options == null) return;
        if (player != null && level != null && isEntityInWater(level, player)) {
            maintainWaterBuoyancy(client, player, level, focusEntity);
        } else {
            client.options.keyJump.setDown(false);
        }
    }
}
