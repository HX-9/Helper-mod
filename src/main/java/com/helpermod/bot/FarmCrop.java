package com.helpermod.bot;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;

public enum FarmCrop {
    WHEAT("wheat", "قمح", Items.WHEAT_SEEDS, Blocks.WHEAT, Blocks.FARMLAND),
    CARROT("carrot", "جزر", Items.CARROT, Blocks.CARROTS, Blocks.FARMLAND),
    POTATO("potato", "بطاطس", Items.POTATO, Blocks.POTATOES, Blocks.FARMLAND),
    BEETROOT("beetroot", "شمندر", Items.BEETROOT_SEEDS, Blocks.BEETROOTS, Blocks.FARMLAND),
    NETHER_WART("nether_wart", "نيذر_وارت", Items.NETHER_WART, Blocks.NETHER_WART, Blocks.SOUL_SAND);

    private final String id;
    private final String arabicName;
    private final Item seedItem;
    private final Block cropBlock;
    private final Block soilBlock;

    FarmCrop(String id, String arabicName, Item seedItem, Block cropBlock, Block soilBlock) {
        this.id = id;
        this.arabicName = arabicName;
        this.seedItem = seedItem;
        this.cropBlock = cropBlock;
        this.soilBlock = soilBlock;
    }

    public String getId() {
        return id;
    }

    public String getArabicName() {
        return arabicName;
    }

    public Item getSeedItem() {
        return seedItem;
    }

    public Block getCropBlock() {
        return cropBlock;
    }

    public Block getSoilBlock() {
        return soilBlock;
    }

    public boolean isMature(BlockState state) {
        if (state.getBlock() != cropBlock) {
            return false;
        }
        if (cropBlock instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        if (cropBlock instanceof NetherWartBlock) {
            return state.getValue(NetherWartBlock.AGE) >= 3;
        }
        return false;
    }

    public static FarmCrop fromBlock(Block block) {
        for (FarmCrop crop : values()) {
            if (crop.cropBlock == block) {
                return crop;
            }
        }
        return null;
    }

    public static FarmCrop fromId(String id) {
        if (id == null) return null;
        for (FarmCrop crop : values()) {
            if (crop.id.equalsIgnoreCase(id) || crop.arabicName.equalsIgnoreCase(id)) {
                return crop;
            }
        }
        return null;
    }
}
