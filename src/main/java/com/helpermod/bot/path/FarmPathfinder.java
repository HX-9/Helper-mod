package com.helpermod.bot.path;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
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
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public class FarmPathfinder {

    private static final int[][] CARDINAL_OFFSETS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    /**
     * خوارزمية A* الذكية للعثور على أقرب مسار يتفادى الأسوار والجدران ويعتبر المحاصيل سالكة تماماً دون القفز فوقها
     */
    public static List<BlockPos> findPath(ClientLevel level, BlockPos start, BlockPos goal, int maxNodes) {
        if (level == null || start == null || goal == null) {
            return new ArrayList<>();
        }

        // ضبط نقطة البداية والنهاية لتكون عند مستوى وقوف وحركة اللاعب (فوق التربة)
        if (isSoilBlock(level.getBlockState(start))) {
            start = start.above();
        }
        if (isSoilBlock(level.getBlockState(goal))) {
            goal = goal.above();
        }

        // إذا كان اللاعب بالفعل في نفس البلوكة مع الهدف
        if (start.equals(goal)) {
            List<BlockPos> direct = new ArrayList<>();
            direct.add(goal);
            return direct;
        }

        PriorityQueue<PathNode> openSet = new PriorityQueue<>();
        Map<BlockPos, PathNode> allNodes = new HashMap<>();

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

            // تخطي العقد القديمة الأقل كفاءة بنمط التقييم الكسول السريع لمنع هبوط الفريمات
            PathNode recorded = allNodes.get(current.pos);
            if (recorded != null && current.gCost > recorded.gCost) {
                continue;
            }

            // فحص هل وصلنا لمسافة كافية للتفاعل مع الهدف (يجب أن تكون خطوة بعد نقطة البداية)
            if (current != startNode && isCloseEnoughToGoal(level, current.pos, goal)) {
                return reconstructPath(current);
            }

            if (current.hCost < closestDist) {
                closestDist = current.hCost;
                bestNode = current;
            }

            for (int[] offset : CARDINAL_OFFSETS) {
                int nx = current.pos.getX() + offset[0];
                int nz = current.pos.getZ() + offset[1];

                // فحص المشي المستوي أولاً (0)، ثم الصعود، ثم النزول (بما في ذلك درجات وجسور الهبوط حتى 3 بلوكات)
                int[] yOffsets = {0, 1, -1, -2, -3};
                for (int dy : yOffsets) {
                    BlockPos candidate = new BlockPos(nx, current.pos.getY() + dy, nz);

                    if (isValidStep(level, current.pos, candidate, dy)) {
                        double stepCost = (dy != 0 ? 1.5 : 1.0);

                        // السير والسباحة على سطح الماء: إعطاء تكلفة عالية (16.0) بحيث يفضل الجسور والطرق الأرضية دائماً
                        // حتى لو كان طريق الماء أقرب مسافة، ولا يسلك الماء إلا في حال عدم وجود أي جسر أو طريق بديل نهائياً
                        if (isWater(level, candidate) || isWater(level, candidate.below())) {
                            stepCost += 16.0;
                        }

                        // الأبواب وبوابات السياج: تكلفة طفيفة لفتحها لتفضيل الممرات المفتوحة إن وجدت
                        BlockState candState = level.getBlockState(candidate);
                        if ((isOpenableDoor(candState) && !candState.getValue(DoorBlock.OPEN))
                            || (isOpenableGate(candState) && !candState.getValue(FenceGateBlock.OPEN))) {
                            stepCost += 1.5;
                        }

                        // تفضيل الطرق والممرات الأكثر إنارة ليلاً (المضاءة بالمشاعل والفوانيس)
                        // بدلاً من الممرات المظلمة المعتمة التي تترسبن فيها الوحوش
                        if (isNightTime) {
                            int blockLight = Math.max(
                                level.getBrightness(LightLayer.BLOCK, candidate),
                                level.getBrightness(LightLayer.BLOCK, candidate.above())
                            );
                            if (blockLight < 8) {
                                stepCost += (8 - blockLight) * 1.5;
                            }
                        }

                        // تفضيل الممرات البعيدة عن شجيرات التوت إن وجدت
                        for (int[] offsetBerry : CARDINAL_OFFSETS) {
                            BlockPos adj = candidate.offset(offsetBerry[0], 0, offsetBerry[1]);
                            if (level.getBlockState(adj).is(Blocks.SWEET_BERRY_BUSH) || level.getBlockState(adj.below()).is(Blocks.SWEET_BERRY_BUSH)) {
                                stepCost += 4.0;
                                break;
                            }
                        }

                        double tentativeGCost = current.gCost + stepCost;

                        PathNode neighbor = allNodes.get(candidate);
                        if (neighbor == null || tentativeGCost < neighbor.gCost) {
                            PathNode nextNode = new PathNode(candidate, current, tentativeGCost, distance(candidate, goal));
                            allNodes.put(candidate, nextNode);
                            openSet.add(nextNode);
                        }
                    }
                }
            }
        }

        // إذا لم نصل إلى الهدف تماماً بسبب طول المسافة أو تعقيد التضاريس،
        // نعيد أفضل مسار جزئي يقرب اللاعب نحو الهدف حتى يتقدم في رحلته ثم يعيد التقييم من مسافة أقرب
        if (bestNode != startNode) {
            return reconstructPath(bestNode);
        }

        return new ArrayList<>();
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

    private static boolean isValidStep(ClientLevel level, BlockPos from, BlockPos to, int dy) {
        // 1. فحص أرضية الوقوف
        BlockPos floorPos = to.below();
        BlockState floorState = level.getBlockState(floorPos);

        // منع الحمم والنار والسقوط القاتل والمخاطر البيئية
        if (isHazard(floorState) || isHazard(level.getBlockState(to)) || isHazard(level.getBlockState(to.above()))) {
            return false;
        }

        // تجنب الدوس على التوت الحلو مباشرة أو المرور داخله
        if (level.getBlockState(to).is(Blocks.SWEET_BERRY_BUSH) || floorState.is(Blocks.SWEET_BERRY_BUSH) || level.getBlockState(to.above()).is(Blocks.SWEET_BERRY_BUSH)) {
            return false;
        }

        boolean isFloorWater = isWater(level, floorPos);
        if (isFloorWater) {
            // السير والسباحة على سطح الماء: التأكد من خلو مساحة الرأس في الهواء لمنع الغرق
            if (isWater(level, to.above()) || !isPassable(level, to.above())) {
                return false;
            }
        } else {
            // أرضية صلبة عادية: منع الفراغ والأسوار
            if (floorState.isAir() || isFenceOrWall(floorState)) {
                return false;
            }
            // منع الصعود والمشي فوق الصناديق والحاويات حتى لا يعلق فوقها أو يقفز منها فوق الأسوار ويتلف المحاصيل
            if (isChestOrContainer(floorState)) {
                return false;
            }
            VoxelShape floorShape = floorState.getCollisionShape(level, floorPos);
            if (floorShape.isEmpty()) {
                return false;
            }
        }

        // 2. فحص مساحة جسم اللاعب (القدمين والرأس)
        if (!isPassable(level, to)) {
            return false;
        }
        if (!isPassable(level, to.above())) {
            return false;
        }

        // 3. في حالة الصعود، التأكد من خلو مساحة الرأس في نقطة الوصول (وهي مفحوصة بالفعل في to.above)
        // ولا نشترط 3 بلوكات فراغ فوق from حتى يتسنى الصعود تحت عتبات الأبواب والأسقف بارتفاع بلوكتين

        // 4. في حالة النزول (الهبوط من الجسر أو المرتفعات)
        if (dy < 0) {
            // منع السقوط أو الهبوط من ارتفاع درجتين أو أكثر مباشرة على التربة الزراعية لحمايتها من السحق والتلف
            if (dy < -1 && level.getBlockState(to.below()).is(Blocks.FARMLAND)) {
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
            || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.WITHER_ROSE)
            || state.is(Blocks.COBWEB)
            || state.is(Blocks.POWDER_SNOW);
    }

    public static boolean isWater(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.WATER) || state.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
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
        double dx = a.getX() - b.getX();
        double dy = (a.getY() - b.getY()) * 1.5;
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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
