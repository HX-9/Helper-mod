package com.helpermod.bot.path;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public class FarmPathfinder {

    private static final int[][] CARDINAL_OFFSETS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    private static final int[][] DIAGONAL_OFFSETS = {
        {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /**
     * خيارات حساب المسار للتمييز بين وضع الزراعة ووضع الاتباع والقتال (مع دعم السباحة والغوص تحت الماء).
     */
    public record PathOptions(
            boolean allowDiagonal,
            boolean checkStepHeadroom,
            boolean returnPartialOnBlocked,
            boolean avoidCactusAdjacent,
            boolean allowUnderwater) {
        public static final PathOptions FARM_DEFAULT = new PathOptions(true, true, false, true, false);
        public static final PathOptions COMBAT_FOLLOW = new PathOptions(true, true, false, true, true);
        public static final PathOptions UNDERWATER_RESCUE = new PathOptions(true, false, true, true, true);

        public PathOptions(
                boolean allowDiagonal,
                boolean checkStepHeadroom,
                boolean returnPartialOnBlocked,
                boolean avoidCactusAdjacent) {
            this(allowDiagonal, checkStepHeadroom, returnPartialOnBlocked, avoidCactusAdjacent, false);
        }
    }

    /**
     * نتيجة البحث التفصيلية: تميّز بين الوصول الفعلي للهدف، المسار الجزئي عند نفاد العقد، أو الانسداد التام.
     */
    public record PathResult(List<BlockPos> path, boolean reachedGoal, boolean partial) {}

    /**
     * خوارزمية A* الذكية للعثور على أقرب مسار يتفادى الأسوار والجدران ويعتبر المحاصيل سالكة تماماً دون القفز فوقها
     */
    public static List<BlockPos> findPath(ClientLevel level, BlockPos start, BlockPos goal, int maxNodes) {
        return findPathDetailed(level, start, goal, maxNodes, PathOptions.FARM_DEFAULT).path();
    }

    /**
     * البحث التفصيلي بالمسار مع خيارات الأقطار والتحكم في المسار الجزئي والأسقف.
     */
    public static PathResult findPathDetailed(ClientLevel level, BlockPos start, BlockPos goal, int maxNodes, PathOptions options) {
        if (level == null || start == null || goal == null) {
            return new PathResult(new ArrayList<>(), false, false);
        }
        PathOptions opts = options != null ? options : PathOptions.FARM_DEFAULT;

        // ضبط نقطة البداية والنهاية لتكون عند مستوى وقوف وحركة اللاعب (فوق التربة والدرجات)
        start = normalizeStartPos(level, start);
        goal = normalizeGoalPos(level, goal);

        // إذا كان اللاعب بالفعل في نفس البلوكة مع الهدف
        if (start.equals(goal)) {
            List<BlockPos> direct = new ArrayList<>();
            direct.add(goal);
            return new PathResult(direct, true, false);
        }

        PriorityQueue<PathNode> openSet = new PriorityQueue<>();
        Map<BlockPos, PathNode> allNodes = new HashMap<>();
        Set<BlockPos> closedSet = new HashSet<>();

        // مراقبة الليل أو العواصف الرعدية لتفضيل الطرق المضاءة بالمشاعل والفوانيس
        boolean isNightTime = level.isThundering() || (level.getSkyDarken() >= 4);

        PathNode startNode = new PathNode(start, null, 0, distance(start, goal));
        openSet.add(startNode);
        allNodes.put(start, startNode);

        PathNode bestNode = startNode;
        double closestDist = startNode.hCost;

        int iterations = 0;
        while (!openSet.isEmpty() && iterations < maxNodes) {
            iterations++;
            PathNode current = openSet.poll();

            if (closedSet.contains(current.pos)) {
                continue;
            }
            PathNode recorded = allNodes.get(current.pos);
            if (recorded != null && current.gCost > recorded.gCost) {
                continue;
            }
            closedSet.add(current.pos);

            // فحص هل وصلنا لمسافة كافية للتفاعل مع الهدف (يجب أن تكون خطوة بعد نقطة البداية)
            if (current != startNode && isCloseEnoughToGoal(level, current.pos, goal)) {
                return new PathResult(reconstructPath(current), true, false);
            }

            if (current.hCost < closestDist) {
                closestDist = current.hCost;
                bestNode = current;
            }

            // 1. فحص الجيران في الاتجاهات الأربعة (مع الصعود والهبوط)
            for (int[] offset : CARDINAL_OFFSETS) {
                int nx = current.pos.getX() + offset[0];
                int nz = current.pos.getZ() + offset[1];

                // فحص المشي المستوي أولاً (0)، ثم الصعود، ثم النزول (بما في ذلك درجات وجسور الهبوط حتى 3 بلوكات)
                int[] yOffsets = {0, 1, -1, -2, -3};
                for (int dy : yOffsets) {
                    BlockPos candidate = new BlockPos(nx, current.pos.getY() + dy, nz);
                    if (closedSet.contains(candidate)) {
                        continue;
                    }

                    if (isValidStep(level, current.pos, candidate, dy, opts.checkStepHeadroom(), opts.allowUnderwater())) {
                        double stepCost = computeStepCost(level, candidate, dy != 0 ? 1.5 : 1.0, isNightTime, opts.avoidCactusAdjacent());
                        relaxNeighbor(current, candidate, stepCost, goal, allNodes, openSet);
                    }
                }
            }

            // 2. فحص الخطوات القطرية الآمنة على نفس المستوى لتقليل التعرج وتسريع الوصول
            if (opts.allowDiagonal()) {
                for (int[] offset : DIAGONAL_OFFSETS) {
                    int dx = offset[0];
                    int dz = offset[1];
                    BlockPos candidate = current.pos.offset(dx, 0, dz);
                    if (closedSet.contains(candidate)) {
                        continue;
                    }

                    BlockPos cornerA = current.pos.offset(dx, 0, 0);
                    BlockPos cornerB = current.pos.offset(0, 0, dz);
                    if (isSafeDiagonalCorner(level, current.pos, cornerA, opts.allowUnderwater())
                        && isSafeDiagonalCorner(level, current.pos, cornerB, opts.allowUnderwater())
                        && isValidStep(level, current.pos, candidate, 0, opts.checkStepHeadroom(), opts.allowUnderwater())) {
                        double stepCost = computeStepCost(level, candidate, 1.414, isNightTime, opts.avoidCactusAdjacent());
                        relaxNeighbor(current, candidate, stepCost, goal, allNodes, openSet);
                    }
                }
            }
        }

        // إذا لم نصل للهدف بسبب نفاد ميزانية العقد مع بقاء مسارات حية مفتوحة، نختار أفضل نقطة في الواجهة المفتوحة
        if (!openSet.isEmpty()) {
            PathNode bestFrontier = pickBestFrontierNode(openSet, closedSet);
            if (bestFrontier != null && bestFrontier != startNode && bestFrontier.hCost < startNode.hCost + 6.0) {
                return new PathResult(reconstructPath(bestFrontier), false, true);
            }
        }

        // إذا فرغت المجموعة المفتوحة تماماً فهذا يعني أن الهدف محاصر أو مسدود بالكامل (نهاية مسدودة)
        if (opts.returnPartialOnBlocked() && bestNode != startNode) {
            return new PathResult(reconstructPath(bestNode), false, true);
        }

        return new PathResult(new ArrayList<>(), false, false);
    }

    private static void relaxNeighbor(
            PathNode current,
            BlockPos candidate,
            double stepCost,
            BlockPos goal,
            Map<BlockPos, PathNode> allNodes,
            PriorityQueue<PathNode> openSet) {
        double tentativeGCost = current.gCost + stepCost;
        PathNode neighbor = allNodes.get(candidate);
        if (neighbor == null || tentativeGCost < neighbor.gCost) {
            PathNode nextNode = new PathNode(candidate, current, tentativeGCost, distance(candidate, goal));
            allNodes.put(candidate, nextNode);
            openSet.add(nextNode);
        }
    }

    private static PathNode pickBestFrontierNode(PriorityQueue<PathNode> openSet, Set<BlockPos> closedSet) {
        PathNode best = null;
        double bestScore = Double.MAX_VALUE;
        for (PathNode node : openSet) {
            if (closedSet.contains(node.pos)) continue;
            double score = node.hCost + node.gCost * 0.15;
            if (score < bestScore) {
                bestScore = score;
                best = node;
            }
        }
        return best;
    }

    private static double computeStepCost(ClientLevel level, BlockPos candidate, double baseCost, boolean isNightTime, boolean avoidCactusAdjacent) {
        double stepCost = baseCost;

        // السير والسباحة أو الغوص: تفضيل الجسور واليابسة أولاً، ثم السطح، ثم الغوص عند الحاجة دون مبالغة تمنع عبور الأنهار
        boolean feetWater = isWater(level, candidate);
        boolean headWater = isWater(level, candidate.above());
        boolean belowWater = isWater(level, candidate.below());
        if (headWater) {
            stepCost += 7.0;
        } else if (feetWater || belowWater) {
            stepCost += 4.5;
        }
        if (isWaterColumnHazardous(level, candidate)) {
            stepCost += 50.0;
        }

        // الأبواب وبوابات السياج: تكلفة طفيفة لفتحها لتفضيل الممرات المفتوحة إن وجدت
        BlockState candState = level.getBlockState(candidate);
        if ((isOpenableDoor(candState) && !candState.getValue(DoorBlock.OPEN))
            || (isOpenableGate(candState) && !candState.getValue(FenceGateBlock.OPEN))) {
            stepCost += 1.5;
        }

        // تفضيل الطرق والممرات الأكثر إنارة ليلاً (المضاءة بالمشاعل والفوانيس)
        if (isNightTime) {
            int blockLight = Math.max(
                level.getBrightness(LightLayer.BLOCK, candidate),
                level.getBrightness(LightLayer.BLOCK, candidate.above())
            );
            if (blockLight < 8) {
                stepCost += (8 - blockLight) * 1.5;
            }
        }

        // تفضيل الممرات البعيدة عن شجيرات التوت والصبار إن وجدت
        for (int[] offsetAdj : CARDINAL_OFFSETS) {
            BlockPos adj = candidate.offset(offsetAdj[0], 0, offsetAdj[1]);
            BlockState adjState = level.getBlockState(adj);
            BlockState adjBelow = level.getBlockState(adj.below());
            if (adjState.is(Blocks.SWEET_BERRY_BUSH) || adjBelow.is(Blocks.SWEET_BERRY_BUSH)) {
                stepCost += 4.0;
                break;
            }
            if (avoidCactusAdjacent && (adjState.is(Blocks.CACTUS) || adjBelow.is(Blocks.CACTUS) || level.getBlockState(adj.above()).is(Blocks.CACTUS))) {
                stepCost += 10.0;
                break;
            }
        }

        return stepCost;
    }

    private static boolean isSafeDiagonalCorner(ClientLevel level, BlockPos from, BlockPos corner, boolean allowUnderwater) {
        BlockState state = level.getBlockState(corner);
        if ((isOpenableDoor(state) && !state.getValue(DoorBlock.OPEN))
            || (isOpenableGate(state) && !state.getValue(FenceGateBlock.OPEN))) {
            return false;
        }
        return isValidStep(level, from, corner, 0, true, allowUnderwater);
    }

    private static boolean isCloseEnoughToGoal(ClientLevel level, BlockPos pos, BlockPos goal) {
        if (pos.equals(goal)) {
            return true;
        }
        int dx = Math.abs(pos.getX() - goal.getX());
        int dy = Math.abs(pos.getY() - goal.getY());
        int dz = Math.abs(pos.getZ() - goal.getZ());

        // الوقوف في بلوكة مجاورة مباشرة كافية للتفاعل مع الهدف، مع التأكد التام من عدم وجود سياج يفصل بينهما
        if (dx + dz == 1 && dy <= 1) {
            BlockState posState = level.getBlockState(pos);
            BlockState goalState = level.getBlockState(goal);
            return !isFenceOrWall(posState) && !isFenceOrWall(goalState)
                && !isPathBlockedByFence(level, Vec3.atCenterOf(pos), Vec3.atCenterOf(goal));
        }
        return false;
    }

    private static BlockPos normalizeStartPos(ClientLevel level, BlockPos pos) {
        if (isWater(level, pos)) {
            return pos;
        }
        BlockState state = level.getBlockState(pos);
        if (isSoilBlock(state)) {
            return pos.above();
        }
        if (!state.isAir() && !isFenceOrWall(state) && !isHazard(state)) {
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (!shape.isEmpty()) {
                double maxY = shape.max(Direction.Axis.Y);
                if (maxY >= 0.45 && maxY <= 1.0 && isPassable(level, pos.above())) {
                    return pos.above();
                }
            }
        }
        return pos;
    }

    private static BlockPos normalizeGoalPos(ClientLevel level, BlockPos pos) {
        if (isWater(level, pos)) {
            return pos;
        }
        BlockState state = level.getBlockState(pos);
        if (isSoilBlock(state)) {
            return pos.above();
        }
        if (!state.isAir() && !isFenceOrWall(state) && !isHazard(state) && !isChestOrContainer(state)) {
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (!shape.isEmpty()) {
                double maxY = shape.max(Direction.Axis.Y);
                if (maxY >= 0.45 && maxY <= 1.0 && isPassable(level, pos.above())) {
                    return pos.above();
                }
            }
        }
        return pos;
    }

    private static boolean isValidStep(ClientLevel level, BlockPos from, BlockPos to, int dy, boolean checkStepHeadroom, boolean allowUnderwater) {
        // 1. فحص أرضية الوقوف والمخاطر البيئية وتيارات الماغما الغاطسة
        BlockPos floorPos = to.below();
        BlockState floorState = level.getBlockState(floorPos);
        BlockState toState = level.getBlockState(to);
        BlockState toAboveState = level.getBlockState(to.above());

        if (isHazard(floorState) || isHazard(toState) || isHazard(toAboveState) || isWaterColumnHazardous(level, to)) {
            return false;
        }

        boolean toFeetWater = isWater(level, to);
        boolean toHeadWater = isWater(level, to.above());
        boolean toFloorWater = isWater(level, floorPos);

        if (toHeadWater && !allowUnderwater) {
            return false;
        }

        // 2. العقد المائية (العوم على السطح أو الغوص تحت الماء)
        if (toFeetWater || toFloorWater) {
            if (!isFeetPassable(level, to) || !isPassable(level, to.above())) {
                return false;
            }
            if (dy > 0 && !toFeetWater && checkStepHeadroom && !isPassable(level, from.above(2))) {
                return false;
            }
            if (dy < 0) {
                for (int y = from.getY(); y > to.getY(); y--) {
                    BlockPos dropPos = new BlockPos(to.getX(), y, to.getZ());
                    if (!isPassable(level, dropPos)) {
                        return false;
                    }
                }
            }
            return true;
        }

        // 3. أرضية صلبة عادية: منع الفراغ والأسوار والصناديق
        if (floorState.isAir() || isFenceOrWall(floorState) || isChestOrContainer(floorState)) {
            return false;
        }
        VoxelShape floorShape = floorState.getCollisionShape(level, floorPos);
        if (floorShape.isEmpty() || floorShape.max(Direction.Axis.Y) < 0.45) {
            return false;
        }

        // 4. فحص مساحة جسم اللاعب (القدمين يسمح بالتغطيات الأرضية المنخفضة كالسجاد، والرأس يشترط خلوه بالكامل)
        if (!isFeetPassable(level, to) || !isPassable(level, to.above())) {
            return false;
        }

        // 5. في حالة الصعود: حساب فرق ارتفاع الوقوف الفعلي
        if (dy > 0) {
            double fromOffset = getEffectiveStandOffset(level, from);
            double toOffset = getEffectiveStandOffset(level, to);
            double effectiveClimb = dy + (toOffset - fromOffset);
            if (effectiveClimb > 1.22) {
                return false;
            }
            if (checkStepHeadroom && effectiveClimb > 0.6 && !isPassable(level, from.above(2))) {
                return false;
            }
        }

        // 6. في حالة النزول (الهبوط من الجسر أو المرتفعات)
        if (dy < 0) {
            if (dy < -1 && floorState.is(Blocks.FARMLAND)) {
                return false;
            }
            for (int y = from.getY(); y > to.getY(); y--) {
                BlockPos dropPos = new BlockPos(to.getX(), y, to.getZ());
                if (!isPassable(level, dropPos)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static double getEffectiveStandOffset(ClientLevel level, BlockPos feetPos) {
        BlockState feetState = level.getBlockState(feetPos);
        if (!feetState.isAir() && !isWater(level, feetPos) && !isFenceOrWall(feetState) && !isChestOrContainer(feetState)) {
            VoxelShape feetShape = feetState.getCollisionShape(level, feetPos);
            if (!feetShape.isEmpty() && feetShape.max(Direction.Axis.Y) <= 0.15) {
                return feetShape.max(Direction.Axis.Y);
            }
        }
        BlockPos below = feetPos.below();
        BlockState belowState = level.getBlockState(below);
        if (belowState.isAir()) return -1.0;
        VoxelShape belowShape = belowState.getCollisionShape(level, below);
        if (belowShape.isEmpty()) {
            return isWater(level, below) ? -0.1 : -1.0;
        }
        return belowShape.max(Direction.Axis.Y) - 1.0;
    }

    /**
     * فحص السماح عند مستوى القدمين: يسمح بالسجاد وطبقات الثلج الرقيقة والتغطيات المنخفضة (<= 0.15 بلوكة)
     * دون تعطيل فحص الرأس الصارم.
     */
    public static boolean isFeetPassable(ClientLevel level, BlockPos pos) {
        if (isPassable(level, pos)) {
            return true;
        }
        BlockState state = level.getBlockState(pos);
        if (isHazard(state) || isFenceOrWall(state) || isChestOrContainer(state)) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 0.15;
    }

    public static boolean isPassable(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        // منع المخاطر البيئية القاتلة أو المعطلة للحركة
        if (isHazard(state)) {
            return false;
        }

        // المحاصيل والنيذر وارت والهواء سالكة تماماً وليست عوائق
        if (state.isAir() || state.getBlock() instanceof CropBlock || state.getBlock() instanceof NetherWartBlock) {
            return true;
        }

        // الأبواب الخشبية وبوابات السياج سالكة لأن البوت قادر على فتحها والتفاعل معها
        if (isOpenableDoor(state) || isOpenableGate(state)) {
            return true;
        }

        // الأسوار والجدران غير سالكة على الإطلاق لمنع القفز عليها
        if (isFenceOrWall(state)) {
            return false;
        }

        VoxelShape shape = state.getCollisionShape(level, pos);
        return shape.isEmpty();
    }

    /**
     * فحص ما إذا كان هناك سياج أو جدار يعترض الخط المباشر بين نقطتين
     */
    public static boolean isPathBlockedByFence(ClientLevel level, Vec3 from, Vec3 to) {
        double dist = from.distanceTo(to);
        if (dist < 0.2) return false;

        int steps = (int) Math.ceil(dist / 0.35);
        for (int i = 1; i <= steps; i++) {
            double factor = (double) i / steps;
            double x = from.x + (to.x - from.x) * factor;
            double y = from.y + (to.y - from.y) * factor;
            double z = from.z + (to.z - from.z) * factor;

            BlockPos checkPos = BlockPos.containing(x, y, z);
            BlockState state = level.getBlockState(checkPos);
            if (isFenceOrWall(state)) {
                return true;
            }
            BlockState belowState = level.getBlockState(checkPos.below());
            if (isFenceOrWall(belowState) && Math.abs(y - checkPos.getY()) < 0.8) {
                return true;
            }
        }
        return false;
    }

    /**
     * فحص ما إذا كان هناك سياج أو جدار يعترض مسار اللاعب للوصول إلى المحصول،
     * مع استثناء الخطوة الأخيرة إذا كان المحصول نفسه مستقراً في نفس بلوكة السور أو على حافتها.
     */
    public static boolean isItemPathBlockedByFence(ClientLevel level, Vec3 from, Vec3 to) {
        double dist = from.distanceTo(to);
        if (dist < 0.5) return false;

        int steps = (int) Math.ceil(dist / 0.35);
        for (int i = 1; i < steps; i++) {
            double factor = (double) i / steps;
            double x = from.x + (to.x - from.x) * factor;
            double y = from.y + (to.y - from.y) * factor;
            double z = from.z + (to.z - from.z) * factor;

            BlockPos checkPos = BlockPos.containing(x, y, z);
            BlockState state = level.getBlockState(checkPos);
            if (isFenceOrWall(state)) {
                return true;
            }
            BlockState belowState = level.getBlockState(checkPos.below());
            if (isFenceOrWall(belowState) && Math.abs(y - checkPos.getY()) < 0.8) {
                return true;
            }
        }
        return false;
    }

    public static boolean isOpenableDoor(BlockState state) {
        return state.getBlock() instanceof DoorBlock && !state.is(Blocks.IRON_DOOR);
    }

    public static boolean isOpenableGate(BlockState state) {
        return state.getBlock() instanceof FenceGateBlock;
    }

    public static boolean isHazard(BlockState state) {
        if (state == null || state.isAir()) return false;
        return state.is(Blocks.LAVA)
            || state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)
            || state.is(Blocks.FIRE)
            || state.is(Blocks.SOUL_FIRE)
            || state.is(Blocks.CAMPFIRE)
            || state.is(Blocks.SOUL_CAMPFIRE)
            || state.is(Blocks.MAGMA_BLOCK)
            || state.is(Blocks.CACTUS)
            || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.WITHER_ROSE)
            || state.is(Blocks.COBWEB)
            || state.is(Blocks.POWDER_SNOW);
    }

    public static boolean isWater(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.WATER) || state.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
    }

    /**
     * التحقق من عدم وجود كتلة ماغما أو عمود فقاعات ساحب فوق ماغما يسحب البوت للأسفل ويغرقه.
     */
    public static boolean isWaterColumnHazardous(ClientLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.LAVA) || state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
            return true;
        }
        if (state.is(Blocks.BUBBLE_COLUMN) || isWater(level, pos)) {
            for (int depth = 1; depth <= 8; depth++) {
                BlockPos check = pos.below(depth);
                BlockState belowState = level.getBlockState(check);
                if (belowState.is(Blocks.MAGMA_BLOCK)) {
                    return true;
                }
                if (!isWater(level, check) && !belowState.is(Blocks.BUBBLE_COLUMN)) {
                    break;
                }
            }
        }
        return false;
    }

    public static boolean isFenceOrWall(BlockState state) {
        return state.getBlock() instanceof FenceBlock
            || state.getBlock() instanceof WallBlock;
    }

    public static boolean isChestOrContainer(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
            || state.getBlock() instanceof net.minecraft.world.level.block.EnderChestBlock
            || state.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock
            || state.getBlock() instanceof net.minecraft.world.level.block.ComposterBlock
            || state.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock;
    }

    private static boolean isSoilBlock(BlockState state) {
        return state.is(Blocks.FARMLAND) || state.is(Blocks.SOUL_SAND)
            || state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)
            || state.is(Blocks.DIRT_PATH) || state.is(Blocks.COARSE_DIRT);
    }

    private static double distance(BlockPos a, BlockPos b) {
        double ax = Math.abs(a.getX() - b.getX());
        double ay = Math.abs(a.getY() - b.getY());
        double az = Math.abs(a.getZ() - b.getZ());
        double dmin = Math.min(ax, az);
        double dmax = Math.max(ax, az);
        return (dmax - dmin) + 1.414 * dmin + ay * 0.75;
    }

    private static List<BlockPos> reconstructPath(PathNode endNode) {
        List<BlockPos> path = new ArrayList<>();
        PathNode curr = endNode;
        while (curr != null) {
            path.add(curr.pos);
            curr = curr.parent;
        }
        Collections.reverse(path);
        if (path.size() > 1) {
            path.remove(0);
        }
        return path;
    }

    private static class PathNode implements Comparable<PathNode> {
        final BlockPos pos;
        PathNode parent;
        double gCost;
        double hCost;

        PathNode(BlockPos pos, PathNode parent, double gCost, double hCost) {
            this.pos = pos;
            this.parent = parent;
            this.gCost = gCost;
            this.hCost = hCost;
        }

        double fCost() {
            return gCost + hCost;
        }

        @Override
        public int compareTo(PathNode o) {
            return Double.compare(this.fCost(), o.fCost());
        }
    }
}
