package com.helpermod.bot;

import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class FarmArea {
    private String name;
    private final BlockPos minPos;
    private final BlockPos maxPos;
    private FarmCrop targetCrop; // null indicates AUTO (replant what was there)
    private BlockPos chestPos; // صندوق تفريغ المحصول المخصص لهذه المزرعة
    private final Map<BlockPos, FarmCrop> rememberedCrops = new HashMap<>();

    public FarmArea(String name, BlockPos p1, BlockPos p2, FarmCrop targetCrop) {
        this.name = name;
        int minX = Math.min(p1.getX(), p2.getX());
        // توسيع نطاق Y تلقائياً بهامش مناسب لضمان شمول المحاصيل فوق التربة والتربة تحتها
        int minY = Math.min(p1.getY(), p2.getY()) - 2;
        int minZ = Math.min(p1.getZ(), p2.getZ());
        int maxX = Math.max(p1.getX(), p2.getX());
        int maxY = Math.max(p1.getY(), p2.getY()) + 3;
        int maxZ = Math.max(p1.getZ(), p2.getZ());

        this.minPos = new BlockPos(minX, minY, minZ);
        this.maxPos = new BlockPos(maxX, maxY, maxZ);
        this.targetCrop = targetCrop;
    }

    public FarmArea(String name, BlockPos minPos, BlockPos maxPos, FarmCrop targetCrop, boolean isExact) {
        this.name = name;
        this.minPos = minPos;
        this.maxPos = maxPos;
        this.targetCrop = targetCrop;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BlockPos getMinPos() {
        return minPos;
    }

    public BlockPos getMaxPos() {
        return maxPos;
    }

    public FarmCrop getTargetCrop() {
        return targetCrop;
    }

    public void setTargetCrop(FarmCrop targetCrop) {
        this.targetCrop = targetCrop;
    }

    public BlockPos getChestPos() {
        return chestPos;
    }

    public void setChestPos(BlockPos chestPos) {
        this.chestPos = chestPos != null ? chestPos.immutable() : null;
    }

    public boolean contains(BlockPos pos) {
        return pos.getX() >= minPos.getX() && pos.getX() <= maxPos.getX()
            && pos.getY() >= minPos.getY() && pos.getY() <= maxPos.getY()
            && pos.getZ() >= minPos.getZ() && pos.getZ() <= maxPos.getZ();
    }

    public void rememberCrop(BlockPos pos, FarmCrop crop) {
        if (crop != null) {
            rememberedCrops.put(pos.immutable(), crop);
        }
    }

    public FarmCrop getRememberedCrop(BlockPos pos) {
        return rememberedCrops.get(pos);
    }

    public Map<BlockPos, FarmCrop> getRememberedCrops() {
        return Collections.unmodifiableMap(rememberedCrops);
    }

    public String getCropDisplayName() {
        if (targetCrop != null) {
            return targetCrop.getArabicName() + " (" + targetCrop.getId() + ")";
        }
        return "تلقائي (إعادة زراعة نفس المحصول السابق)";
    }

    public BlockPos getCenterPos() {
        return new BlockPos(
            (minPos.getX() + maxPos.getX()) / 2,
            (minPos.getY() + maxPos.getY()) / 2,
            (minPos.getZ() + maxPos.getZ()) / 2
        );
    }
}
