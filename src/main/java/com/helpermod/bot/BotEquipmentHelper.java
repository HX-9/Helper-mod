package com.helpermod.bot;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * أدوات مساعدة مشتركة بين متحكمات البوت لتبديل المعدات في اليدين وشريط الأدوات وحساب الزوايا.
 */
public final class BotEquipmentHelper {

    public static final int OFFHAND_SLOT_INDEX = 40;

    private BotEquipmentHelper() {}

    /**
     * تطبيع الزاوية إلى النطاق [-180, 180).
     */
    public static float wrapDegrees(float degrees) {
        float f = degrees % 360.0f;
        if (f >= 180.0f) f -= 360.0f;
        if (f < -180.0f) f += 360.0f;
        return f;
    }

    /**
     * اختيار خانة في شريط الأدوات (0-8) ومزامنتها مع السيرفر.
     */
    public static void selectHotbarSlot(LocalPlayer player, int slot) {
        if (player == null || slot < 0 || slot >= 9) return;
        player.getInventory().setSelectedSlot(slot);
        if (player.connection != null) {
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        }
    }

    /**
     * درجة أفضلية السيوف (6 للنيذررايت وحتى 1 للخشبي، و0 لغير السيوف).
     */
    public static int getSwordTier(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        if (stack.is(Items.NETHERITE_SWORD)) return 6;
        if (stack.is(Items.DIAMOND_SWORD)) return 5;
        if (stack.is(Items.IRON_SWORD)) return 4;
        if (stack.is(Items.STONE_SWORD)) return 3;
        if (stack.is(Items.GOLDEN_SWORD)) return 2;
        if (stack.is(Items.WOODEN_SWORD)) return 1;
        return 0;
    }

    /**
     * درجة أفضلية الفؤوس (6 للنيذررايت وحتى 1 للخشبي، و0 لغير الفؤوس).
     */
    public static int getAxeTier(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        if (stack.is(Items.NETHERITE_AXE)) return 6;
        if (stack.is(Items.DIAMOND_AXE)) return 5;
        if (stack.is(Items.IRON_AXE)) return 4;
        if (stack.is(Items.STONE_AXE)) return 3;
        if (stack.is(Items.GOLDEN_AXE)) return 2;
        if (stack.is(Items.WOODEN_AXE)) return 1;
        return 0;
    }

    /**
     * درجة أفضلية الفأس مع السيف كبديل احتياطي لوضع الدفاع المستقل.
     */
    public static int getAxeOrSwordTier(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        if (stack.getItem() instanceof AxeItem) {
            int tier = getAxeTier(stack);
            return tier > 0 ? tier + 1 : 2;
        }
        if (stack.is(ItemTags.SWORDS)) {
            int tier = getSwordTier(stack);
            return tier > 0 ? tier : 1;
        }
        return -1;
    }

    /**
     * التحقق من وجود عنصر مطابق في الحقيبة (مع خيار فحص اليد اليسرى أيضاً).
     */
    public static boolean hasMatchingItem(LocalPlayer player, Predicate<ItemStack> predicate, boolean includeOffhand) {
        if (player == null || predicate == null) return false;
        if (includeOffhand && predicate.test(player.getOffhandItem())) {
            return true;
        }
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ضمان وجود عنصر مطابق في اليد اليسرى (Offhand) عبر البحث في الهوتبار ثم الحقيبة الرئيسية.
     */
    public static boolean ensureInOffhand(LocalPlayer player, MultiPlayerGameMode gameMode, Predicate<ItemStack> predicate) {
        if (player == null || gameMode == null || predicate == null) return false;
        if (predicate.test(player.getOffhandItem())) {
            return true;
        }

        int containerId = player.inventoryMenu.containerId;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                gameMode.handleContainerInput(containerId, 36 + i, OFFHAND_SLOT_INDEX, ContainerInput.SWAP, player);
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                gameMode.handleContainerInput(containerId, i, OFFHAND_SLOT_INDEX, ContainerInput.SWAP, player);
                return true;
            }
        }
        return false;
    }

    /**
     * تبديل اليد اليسرى مع أول درع متاح (بحسب ترتيب البحث المطلوب وحالة التبريد).
     */
    public static boolean swapBackupShieldToOffhand(
            LocalPlayer player,
            MultiPlayerGameMode gameMode,
            boolean hotbarFirst,
            boolean requireReadyCooldown) {
        if (player == null || gameMode == null) return false;

        int containerId = player.inventoryMenu.containerId;
        if (hotbarFirst) {
            if (trySwapShieldRange(player, gameMode, containerId, 0, 9, requireReadyCooldown)) return true;
            return trySwapShieldRange(player, gameMode, containerId, 9, 36, requireReadyCooldown);
        } else {
            if (trySwapShieldRange(player, gameMode, containerId, 9, 36, requireReadyCooldown)) return true;
            return trySwapShieldRange(player, gameMode, containerId, 0, 9, requireReadyCooldown);
        }
    }

    private static boolean trySwapShieldRange(
            LocalPlayer player,
            MultiPlayerGameMode gameMode,
            int containerId,
            int startInclusive,
            int endExclusive,
            boolean requireReadyCooldown) {
        for (int i = startInclusive; i < endExclusive; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.SHIELD)) {
                if (requireReadyCooldown && player.getCooldowns().isOnCooldown(stack)) {
                    continue;
                }
                int slotIndex = i < 9 ? 36 + i : i;
                gameMode.handleContainerInput(containerId, slotIndex, OFFHAND_SLOT_INDEX, ContainerInput.SWAP, player);
                return true;
            }
        }
        return false;
    }

    /**
     * اختيار وتجهيز أفضل عنصر في اليد الرئيسية بناءً على دالة تقييم رقمية.
     */
    public static boolean ensureBestInMainHand(
            LocalPlayer player,
            MultiPlayerGameMode gameMode,
            ToIntFunction<ItemStack> scoreFn,
            int minValidScore,
            int fallbackHotbarSlot) {
        if (player == null || gameMode == null || scoreFn == null) return false;

        ItemStack current = player.getMainHandItem();
        int bestSlot = -1;
        int bestScore = scoreFn.applyAsInt(current);

        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            int score = scoreFn.applyAsInt(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        if (bestSlot != -1) {
            selectHotbarSlot(player, bestSlot);
            return true;
        }
        if (bestScore >= minValidScore) {
            return true;
        }

        int invBestSlot = -1;
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            int score = scoreFn.applyAsInt(stack);
            if (score > bestScore) {
                bestScore = score;
                invBestSlot = i;
            }
        }

        if (invBestSlot != -1) {
            int containerId = player.inventoryMenu.containerId;
            gameMode.handleContainerInput(containerId, invBestSlot, fallbackHotbarSlot, ContainerInput.SWAP, player);
            selectHotbarSlot(player, fallbackHotbarSlot);
            return true;
        }

        return false;
    }
}
