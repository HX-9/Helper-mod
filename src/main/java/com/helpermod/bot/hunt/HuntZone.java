package com.helpermod.bot.hunt;

import com.helpermod.bot.FarmArea;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * منطقة أو حظيرة صيد وتجميع موبات مرتبطة بالعالم والبُعد وصناديق غنائم متعددة.
 */
public class HuntZone {
    private String name;
    private BlockPos minPos;
    private BlockPos maxPos;
    private String worldKey;
    private String dimensionId;
    private boolean enabled = true;
    private final List<BlockPos> lootChests = new ArrayList<>();

    public HuntZone(String name, BlockPos p1, BlockPos p2) {
        this.name = name;
        updateBounds(p1, p2);
    }

    public HuntZone(String name, BlockPos minPos, BlockPos maxPos, boolean exact) {
        this.name = name;
        this.minPos = minPos;
        this.maxPos = maxPos;
    }

    public void updateBounds(BlockPos p1, BlockPos p2) {
        int minX = Math.min(p1.getX(), p2.getX());
        int minY = Math.min(p1.getY(), p2.getY()) - 1;
        int minZ = Math.min(p1.getZ(), p2.getZ());
        int maxX = Math.max(p1.getX(), p2.getX());
        int maxY = Math.max(p1.getY(), p2.getY()) + 4;
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean matchesContext(String currentWorldKey, String currentDimensionId) {
        if (currentWorldKey == null || currentDimensionId == null) {
            return true;
        }
        return Objects.equals(this.worldKey, currentWorldKey)
            && Objects.equals(this.dimensionId, currentDimensionId);
    }

    public String getDimensionDisplayName() {
        return FarmArea.formatDimensionDisplayName(dimensionId);
    }

    public boolean contains(BlockPos pos) {
        if (pos == null || minPos == null || maxPos == null) return false;
        return pos.getX() >= minPos.getX() && pos.getX() <= maxPos.getX()
            && pos.getY() >= minPos.getY() && pos.getY() <= maxPos.getY()
            && pos.getZ() >= minPos.getZ() && pos.getZ() <= maxPos.getZ();
    }

    public BlockPos getCenterPos() {
        return new BlockPos(
            (minPos.getX() + maxPos.getX()) / 2,
            (minPos.getY() + maxPos.getY()) / 2,
            (minPos.getZ() + maxPos.getZ()) / 2
        );
    }

    public List<BlockPos> getLootChests() {
        return Collections.unmodifiableList(lootChests);
    }

    public void setSingleLootChest(BlockPos pos) {
        lootChests.clear();
        if (pos != null) {
            lootChests.add(pos.immutable());
        }
    }

    public boolean addLootChest(BlockPos pos) {
        if (pos == null) return false;
        BlockPos immutable = pos.immutable();
        if (lootChests.contains(immutable)) {
            return false;
        }
        lootChests.add(immutable);
        return true;
    }

    public BlockPos removeLootChestAt(int zeroBasedIndex) {
        if (zeroBasedIndex < 0 || zeroBasedIndex >= lootChests.size()) {
            return null;
        }
        return lootChests.remove(zeroBasedIndex);
    }

    public void clearLootChests() {
        lootChests.clear();
    }
}
