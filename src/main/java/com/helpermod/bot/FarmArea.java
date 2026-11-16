package com.helpermod.bot;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class FarmArea {
    /**
     * تصنيف الصندوق داخل المزرعة: للمحصول المحصود أو للبذور المنفصلة.
     */
    public enum ChestRole {
        CROP("المحصول"),
        SEED("البذور");

        private final String arabicLabel;

        ChestRole(String arabicLabel) {
            this.arabicLabel = arabicLabel;
        }

        public String getArabicLabel() {
            return arabicLabel;
        }
    }

    private String name;
    private BlockPos minPos;
    private BlockPos maxPos;
    private FarmCrop targetCrop; // null indicates AUTO (replant what was there)
    private final List<BlockPos> cropChests = new ArrayList<>(); // صناديق تفريغ المحصول (يدعم أكثر من صندوق)
    private final List<BlockPos> seedChests = new ArrayList<>(); // صناديق البذور المخصصة للمحاصيل ذات البذور
    private boolean enabled = true; // هل المزرعة مفعلة للعمل أم لا
    private String worldKey; // معرف العالم أو السيرفر المرتبط بهذه المزرعة
    private String dimensionId; // معرف البُعد (مثل minecraft:overworld)
    private final Map<BlockPos, FarmCrop> rememberedCrops = new HashMap<>();

    public FarmArea(String name, BlockPos p1, BlockPos p2, FarmCrop targetCrop) {
        this.name = name;
        this.targetCrop = targetCrop;
        updateBounds(p1, p2);
    }

    public FarmArea(String name, BlockPos minPos, BlockPos maxPos, FarmCrop targetCrop, boolean isExact) {
        this.name = name;
        this.minPos = minPos;
        this.maxPos = maxPos;
        this.targetCrop = targetCrop;
    }

    /**
     * تحديث حدود المنطقة مع توسيع نطاق Y تلقائياً ليشمل التربة والمحاصيل فوقها.
     */
    public void updateBounds(BlockPos p1, BlockPos p2) {
        int minX = Math.min(p1.getX(), p2.getX());
        int minY = Math.min(p1.getY(), p2.getY()) - 2;
        int minZ = Math.min(p1.getZ(), p2.getZ());
        int maxX = Math.max(p1.getX(), p2.getX());
        int maxY = Math.max(p1.getY(), p2.getY()) + 3;
        int maxZ = Math.max(p1.getZ(), p2.getZ());

        this.minPos = new BlockPos(minX, minY, minZ);
        this.maxPos = new BlockPos(maxX, maxY, maxZ);
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
        return cropChests.isEmpty() ? null : cropChests.get(0);
    }

    public void setChestPos(BlockPos chestPos) {
        setSingleChest(ChestRole.CROP, chestPos);
    }

    private List<BlockPos> getMutableChests(ChestRole role) {
        return role == ChestRole.SEED ? seedChests : cropChests;
    }

    public List<BlockPos> getChests(ChestRole role) {
        return Collections.unmodifiableList(getMutableChests(role));
    }

    public List<BlockPos> getCropChests() {
        return Collections.unmodifiableList(cropChests);
    }

    public List<BlockPos> getSeedChests() {
        return Collections.unmodifiableList(seedChests);
    }

    public void setSingleChest(ChestRole role, BlockPos chestPos) {
        List<BlockPos> target = getMutableChests(role);
        target.clear();
        if (chestPos != null) {
            target.add(chestPos.immutable());
        }
    }

    public boolean addChest(ChestRole role, BlockPos chestPos) {
        if (chestPos == null) return false;
        BlockPos immutable = chestPos.immutable();
        List<BlockPos> target = getMutableChests(role);
        if (target.contains(immutable)) {
            return false;
        }
        target.add(immutable);
        return true;
    }

    public BlockPos removeChestAt(ChestRole role, int zeroBasedIndex) {
        List<BlockPos> target = getMutableChests(role);
        if (zeroBasedIndex < 0 || zeroBasedIndex >= target.size()) {
            return null;
        }
        return target.remove(zeroBasedIndex);
    }

    public void clearChests(ChestRole role) {
        getMutableChests(role).clear();
    }

    public boolean hasAnyChest() {
        return !cropChests.isEmpty() || !seedChests.isEmpty();
    }

    public boolean supportsSeparateSeedChest() {
        return targetCrop == null || targetCrop.hasDistinctSeedItem();
    }

    public boolean hasSeparateSeedChestsForCrop(FarmCrop crop) {
        return !seedChests.isEmpty() && (crop == null || crop.hasDistinctSeedItem());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getWorldKey() {
        return worldKey;
    }

    public void setWorldKey(String worldKey) {
        this.worldKey = worldKey;
    }

    public String getDimensionId() {
        return dimensionId;
    }

    public void setDimensionId(String dimensionId) {
        this.dimensionId = dimensionId;
    }

    public boolean matchesContext(String currentWorldKey, String currentDimensionId) {
        if (currentWorldKey == null || currentDimensionId == null) {
            return true;
        }
        return Objects.equals(this.worldKey, currentWorldKey)
            && Objects.equals(this.dimensionId, currentDimensionId);
    }

    public String getDimensionDisplayName() {
        return formatDimensionDisplayName(dimensionId);
    }

    public static String formatDimensionDisplayName(String dimId) {
        if (dimId == null || dimId.isBlank()) {
            return "غير محدد";
        }
        return switch (dimId) {
            case "minecraft:overworld" -> "العالم العادي (Overworld)";
            case "minecraft:the_nether" -> "النذر (The Nether)";
            case "minecraft:the_end" -> "الإند (The End)";
            default -> dimId;
        };
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
