package com.helpermod.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * أدوات مساعدة مشتركة بين متحكمات البوت لتبديل المعدات في اليدين وشريط الأدوات وحساب الزوايا.
 */
public final class BotEquipmentHelper {

    public static final int OFFHAND_SLOT_INDEX = 40;
    public static final float CRITICAL_TOTEM_HEALTH = 7.5f;
    public static final float TOTEM_RELEASE_HEALTH = 11.0f;

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
     * درجة أفضلية السيوف (7 للنيذررايت وحتى 1 للخشبي مع دعم النحاس في 26.2، و0 لغير السيوف).
     */
    public static int getSwordTier(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        if (stack.is(Items.NETHERITE_SWORD)) return 7;
        if (stack.is(Items.DIAMOND_SWORD)) return 6;
        if (stack.is(Items.IRON_SWORD)) return 5;
        if (stack.is(Items.COPPER_SWORD)) return 4;
        if (stack.is(Items.STONE_SWORD)) return 3;
        if (stack.is(Items.GOLDEN_SWORD)) return 2;
        if (stack.is(Items.WOODEN_SWORD)) return 1;
        return stack.is(ItemTags.SWORDS) ? 1 : 0;
    }

    /**
     * درجة أفضلية الفؤوس (7 للنيذررايت وحتى 1 للخشبي مع دعم النحاس في 26.2، و0 لغير الفؤوس).
     */
    public static int getAxeTier(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        if (stack.is(Items.NETHERITE_AXE)) return 7;
        if (stack.is(Items.DIAMOND_AXE)) return 6;
        if (stack.is(Items.IRON_AXE)) return 5;
        if (stack.is(Items.COPPER_AXE)) return 4;
        if (stack.is(Items.STONE_AXE)) return 3;
        if (stack.is(Items.GOLDEN_AXE)) return 2;
        if (stack.is(Items.WOODEN_AXE)) return 1;
        return (stack.getItem() instanceof AxeItem || stack.is(ItemTags.AXES)) ? 1 : 0;
    }

    /**
     * مكافأة التطويرات القتالية وتطوير الغنائم (Looting) لتحديد السلاح الأقوى فعلياً.
     */
    public static int getWeaponEnchantBonus(ItemStack stack, boolean prioritizeLooting) {
        if (stack == null || stack.isEmpty()) return 0;
        ItemEnchantments enchantments = stack.getEnchantments();
        if (enchantments == null || enchantments.isEmpty()) return 0;

        int bonus = 0;
        for (var entry : enchantments.entrySet()) {
            var holder = entry.getKey();
            int level = entry.getIntValue();
            if (level <= 0) continue;
            if (holder.is(Enchantments.SHARPNESS)) {
                bonus += level * 4;
            } else if (holder.is(Enchantments.SMITE) || holder.is(Enchantments.BANE_OF_ARTHROPODS)) {
                bonus += level * 2;
            } else if (holder.is(Enchantments.FIRE_ASPECT)) {
                bonus += level * 3;
            } else if (holder.is(Enchantments.SWEEPING_EDGE)) {
                bonus += level * 2;
            } else if (holder.is(Enchantments.KNOCKBACK)) {
                bonus += level;
            } else if (holder.is(Enchantments.LOOTING)) {
                bonus += level * (prioritizeLooting ? 12 : 3);
            }
        }
        return bonus;
    }

    public static int getSwordCombatScore(ItemStack stack, boolean prioritizeLooting) {
        int tier = getSwordTier(stack);
        if (tier <= 0) return 0;
        return tier * 10 + getWeaponEnchantBonus(stack, prioritizeLooting);
    }

    public static int getAxeCombatScore(ItemStack stack, boolean prioritizeLooting) {
        int tier = getAxeTier(stack);
        if (tier <= 0) return 0;
        return tier * 10 + getWeaponEnchantBonus(stack, prioritizeLooting);
    }

    public static int getMeleeWeaponCombatScore(ItemStack stack, boolean prioritizeLooting) {
        return Math.max(getSwordCombatScore(stack, prioritizeLooting), getAxeCombatScore(stack, prioritizeLooting));
    }

    public static int getBestSwordScore(LocalPlayer player, boolean prioritizeLooting) {
        return getBestWeaponScore(player, stack -> getSwordCombatScore(stack, prioritizeLooting));
    }

    public static int getBestAxeScore(LocalPlayer player, boolean prioritizeLooting) {
        return getBestWeaponScore(player, stack -> getAxeCombatScore(stack, prioritizeLooting));
    }

    private static int getBestWeaponScore(LocalPlayer player, ToIntFunction<ItemStack> scorer) {
        if (player == null) return 0;
        int best = 0;
        for (int i = 0; i < 36; i++) {
            int score = scorer.applyAsInt(player.getInventory().getItem(i));
            if (score > best) {
                best = score;
            }
        }
        return best;
    }

    /**
     * التقييم المتوازن لأقوى سلاح قريب (سيف أو فأس) بناءً على الخامة والتطويرات بدون انحياز قسري لفأس أضعف.
     */
    public static int getAxeOrSwordTier(ItemStack stack) {
        int score = getMeleeWeaponCombatScore(stack, false);
        return score > 0 ? score : -1;
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
        // حماية التوتم أثناء الخطر الحرج حتى لا يستبدله الدرع أو الفأس تلقائياً
        if (player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) && isCriticalHealthForTotem(player)) {
            return false;
        }
        if (player.containerMenu != player.inventoryMenu) {
            return false;
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
        if (player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) && isCriticalHealthForTotem(player)) {
            return false;
        }
        if (player.containerMenu != player.inventoryMenu) {
            return false;
        }

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

    /**
     * التحقق من جاهزية الدرع في اليد اليسرى وعدم وجوده في فترة التبريد (Cooldown).
     */
    public static boolean isShieldReady(LocalPlayer player) {
        if (player == null) return false;
        ItemStack offhand = player.getOffhandItem();
        return !offhand.isEmpty() && offhand.is(Items.SHIELD) && !player.getCooldowns().isOnCooldown(offhand);
    }

    /**
     * التحقق مما إذا كان الدرع في اليد اليسرى على وشك الكسر (<= 15 نقطة متانة).
     */
    public static boolean isOffhandShieldLowDurability(LocalPlayer player) {
        if (player == null) return false;
        ItemStack offhand = player.getOffhandItem();
        return !offhand.isEmpty()
            && offhand.is(Items.SHIELD)
            && offhand.isDamageableItem()
            && (offhand.getMaxDamage() - offhand.getDamageValue()) <= 15;
    }

    /**
     * تحرير مفاتيح الحركة وإيقاف الركض بأمان.
     */
    public static void releaseMovementKeys(Minecraft client) {
        if (client == null || client.options == null) return;
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyShift.setDown(false);
        if (client.player != null) {
            client.player.setSprinting(false);
        }
    }

    /**
     * توجيه النظر تدريجياً وسلساً نحو نقطة محددة بحد أقصى للالتفاف في كل تكة.
     */
    public static void smoothLookAt(LocalPlayer player, double targetX, double targetY, double targetZ, float maxTurnDegrees) {
        if (player == null) return;
        double dx = targetX - player.getX();
        double dy = targetY - player.getEyeY();
        double dz = targetZ - player.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float targetPitch = (float) -(Math.atan2(dy, distXZ) * 180.0 / Math.PI);

        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();

        float yawDiff = wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = wrapDegrees(targetPitch - currentPitch);

        float clampedYaw = Math.max(-maxTurnDegrees, Math.min(maxTurnDegrees, yawDiff));
        float clampedPitch = Math.max(-maxTurnDegrees, Math.min(maxTurnDegrees, pitchDiff));

        player.setYRot(currentYaw + clampedYaw);
        player.setXRot(currentPitch + clampedPitch);
        player.yHeadRot = player.getYRot();
        player.yBodyRot = player.getYRot();
    }

    /**
     * حساب المسافة الفعلية من موضع عيني البوت إلى أقرب نقطة على صندوق تصادم الهدف (AABB).
     */
    public static double getEyeToHitboxDistanceSq(Entity viewer, Entity target) {
        if (viewer == null || target == null) return Double.MAX_VALUE;
        Vec3 eye = viewer.getEyePosition();
        AABB box = target.getBoundingBox();
        double cx = Math.max(box.minX, Math.min(eye.x, box.maxX));
        double cy = Math.max(box.minY, Math.min(eye.y, box.maxY));
        double cz = Math.max(box.minZ, Math.min(eye.z, box.maxZ));
        return eye.distanceToSqr(cx, cy, cz);
    }

    public static double getEyeToHitboxDistance(Entity viewer, Entity target) {
        double sq = getEyeToHitboxDistanceSq(viewer, target);
        return sq == Double.MAX_VALUE ? Double.MAX_VALUE : Math.sqrt(sq);
    }

    /**
     * اختيار خانة آمنة في شريط الأدوات للتفاعل مع الكتل (مثل الأسرّة والأبواب):
     * يفضّل اليد الفارغة، ثم السيف، ثم أي عنصر ليس كتلة بناء أو فأساً.
     */
    public static int findSafeInteractionSlot(LocalPlayer player) {
        if (player == null) return -1;
        Inventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (getSwordTier(inv.getItem(i)) > 0) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            var item = inv.getItem(i).getItem();
            if (!(item instanceof BlockItem)
                    && !(item instanceof AxeItem)
                    && !(item instanceof BowItem)
                    && !(item instanceof CrossbowItem)) {
                return i;
            }
        }
        return -1;
    }

    // =========================================================================
    // أسلحة المدى (القوس والنشاب) والدروع وتوتم البقاء
    // =========================================================================

    public static boolean isBow(ItemStack stack) {
        return stack != null && !stack.isEmpty() && (stack.is(Items.BOW) || stack.getItem() instanceof BowItem);
    }

    public static boolean isCrossbow(ItemStack stack) {
        return stack != null && !stack.isEmpty() && (stack.is(Items.CROSSBOW) || stack.getItem() instanceof CrossbowItem);
    }

    public static boolean isRangedWeapon(ItemStack stack) {
        return isBow(stack) || isCrossbow(stack);
    }

    public static boolean isAmmoItem(ItemStack stack) {
        return stack != null && !stack.isEmpty()
            && (stack.is(Items.ARROW) || stack.is(Items.SPECTRAL_ARROW) || stack.is(Items.TIPPED_ARROW));
    }

    public static boolean hasAmmo(LocalPlayer player) {
        if (player == null) return false;
        return player.isCreative() || hasMatchingItem(player, BotEquipmentHelper::isAmmoItem, true);
    }

    /**
     * تقييم السلاح بعيد المدى المتاح: النشاب المشحون (12+) ثم القوس (10+) ثم النشاب غير المشحون (8+).
     */
    public static int getRangedWeaponTier(ItemStack stack, boolean hasAmmoOrCreative) {
        if (stack == null || stack.isEmpty()) return 0;
        int enchantBonus = stack.isEnchanted() ? 1 : 0;
        if (isCrossbow(stack)) {
            if (CrossbowItem.isCharged(stack)) {
                return 12 + enchantBonus;
            }
            return hasAmmoOrCreative ? (8 + enchantBonus) : 0;
        }
        if (isBow(stack)) {
            return hasAmmoOrCreative ? (10 + enchantBonus) : 0;
        }
        return 0;
    }

    /**
     * التحقق من امتلاك قوس بذخيرة أو نشاب مشحون أو بذخيرة في الحقيبة.
     */
    public static boolean hasUsableRangedWeapon(LocalPlayer player) {
        if (player == null) return false;
        boolean canFire = hasAmmo(player);
        for (int i = 0; i < 36; i++) {
            if (getRangedWeaponTier(player.getInventory().getItem(i), canFire) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * تجهيز أفضل سلاح بعيد مدى صالح للإطلاق في اليد الرئيسية.
     */
    public static boolean ensureBestRangedInMainHand(
            LocalPlayer player,
            MultiPlayerGameMode gameMode,
            int fallbackHotbarSlot) {
        if (player == null || gameMode == null) return false;
        boolean canFire = hasAmmo(player);
        return ensureBestInMainHand(player, gameMode, stack -> getRangedWeaponTier(stack, canFire), 1, fallbackHotbarSlot);
    }

    public static boolean hasTotemInInventory(LocalPlayer player) {
        return hasMatchingItem(player, stack -> stack.is(Items.TOTEM_OF_UNDYING), true);
    }

    /**
     * تفعيل وضع الخطر الحرج لتوتم البقاء مع هيستيريسيس بين 7.5 و 11.0 لتفادي التذبذب السريع مع الدرع.
     */
    public static boolean isCriticalHealthForTotem(LocalPlayer player) {
        if (player == null || player.isCreative() || !hasTotemInInventory(player)) {
            return false;
        }
        float health = player.getHealth();
        if (health <= CRITICAL_TOTEM_HEALTH) {
            return true;
        }
        return player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) && health < TOTEM_RELEASE_HEALTH;
    }

    public static boolean ensureTotemInOffhand(LocalPlayer player, MultiPlayerGameMode gameMode) {
        return ensureInOffhand(player, gameMode, stack -> stack.is(Items.TOTEM_OF_UNDYING));
    }

    /**
     * إرجاع رقم خانة الدرع داخل قائمة InventoryMenu (5 للرأس حتى 8 للقدمين).
     */
    public static int getArmorMenuSlot(EquipmentSlot slot) {
        if (slot == null) return -1;
        return switch (slot) {
            case HEAD -> 5;
            case CHEST -> 6;
            case LEGS -> 7;
            case FEET -> 8;
            default -> -1;
        };
    }

    /**
     * تقييم جودة قطعة الدرع للخانة المحددة دون الاعتماد على فئة ArmorItem المحذوفة.
     */
    public static int getArmorTier(ItemStack stack, EquipmentSlot slot) {
        if (stack == null || stack.isEmpty() || slot == null) return 0;
        int baseTier = switch (slot) {
            case HEAD -> {
                if (stack.is(Items.NETHERITE_HELMET)) yield 70;
                if (stack.is(Items.DIAMOND_HELMET)) yield 60;
                if (stack.is(Items.IRON_HELMET)) yield 45;
                if (stack.is(Items.TURTLE_HELMET)) yield 42;
                if (stack.is(Items.CHAINMAIL_HELMET)) yield 30;
                if (stack.is(Items.GOLDEN_HELMET)) yield 22;
                if (stack.is(Items.LEATHER_HELMET)) yield 10;
                yield getDynamicVanillaArmorTier(stack, "_helmet");
            }
            case CHEST -> {
                if (stack.is(Items.NETHERITE_CHESTPLATE)) yield 70;
                if (stack.is(Items.DIAMOND_CHESTPLATE)) yield 60;
                if (stack.is(Items.IRON_CHESTPLATE)) yield 45;
                if (stack.is(Items.CHAINMAIL_CHESTPLATE)) yield 30;
                if (stack.is(Items.GOLDEN_CHESTPLATE)) yield 22;
                if (stack.is(Items.LEATHER_CHESTPLATE)) yield 10;
                yield getDynamicVanillaArmorTier(stack, "_chestplate");
            }
            case LEGS -> {
                if (stack.is(Items.NETHERITE_LEGGINGS)) yield 70;
                if (stack.is(Items.DIAMOND_LEGGINGS)) yield 60;
                if (stack.is(Items.IRON_LEGGINGS)) yield 45;
                if (stack.is(Items.CHAINMAIL_LEGGINGS)) yield 30;
                if (stack.is(Items.GOLDEN_LEGGINGS)) yield 22;
                if (stack.is(Items.LEATHER_LEGGINGS)) yield 10;
                yield getDynamicVanillaArmorTier(stack, "_leggings");
            }
            case FEET -> {
                if (stack.is(Items.NETHERITE_BOOTS)) yield 70;
                if (stack.is(Items.DIAMOND_BOOTS)) yield 60;
                if (stack.is(Items.IRON_BOOTS)) yield 45;
                if (stack.is(Items.CHAINMAIL_BOOTS)) yield 30;
                if (stack.is(Items.GOLDEN_BOOTS)) yield 22;
                if (stack.is(Items.LEATHER_BOOTS)) yield 10;
                yield getDynamicVanillaArmorTier(stack, "_boots");
            }
            default -> 0;
        };
        if (baseTier <= 0) return 0;
        return baseTier + (stack.isEnchanted() ? 5 : 0);
    }

    private static int getDynamicVanillaArmorTier(ItemStack stack, String suffix) {
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id != null && "minecraft".equals(id.getNamespace())) {
            String path = id.getPath();
            if (("copper" + suffix).equals(path)) {
                return 35;
            }
        }
        return 0;
    }

    /**
     * لبس أقوى درع متاح في الحقيبة لكل خانة (الرأس، الصدر، الساق، القدم) مع احترام الإليترا المرتداة.
     * يبدل قطعة واحدة كحد أقصى في النداء لتجنب إرسال حزم كثيفة للسيرفر دفعة واحدة.
     */
    public static boolean equipBestArmor(LocalPlayer player, MultiPlayerGameMode gameMode) {
        if (player == null || gameMode == null || player.isUsingItem()) return false;
        if (player.containerMenu != player.inventoryMenu) return false;
        if (!player.inventoryMenu.getCarried().isEmpty()) return false;

        int containerId = player.inventoryMenu.containerId;
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

        for (EquipmentSlot slot : slots) {
            int armorMenuSlot = getArmorMenuSlot(slot);
            if (armorMenuSlot < 0) continue;

            ItemStack worn = player.getItemBySlot(slot);
            if (slot == EquipmentSlot.CHEST && worn.is(Items.ELYTRA)) {
                continue;
            }

            int wornTier = getArmorTier(worn, slot);
            int bestIdx = -1;
            int bestTier = wornTier;

            for (int i = 0; i < 36; i++) {
                ItemStack candidate = player.getInventory().getItem(i);
                int tier = getArmorTier(candidate, slot);
                if (tier > bestTier) {
                    bestTier = tier;
                    bestIdx = i;
                }
            }

            if (bestIdx != -1) {
                int sourceMenuSlot = bestIdx < 9 ? 36 + bestIdx : bestIdx;
                if (worn.isEmpty()) {
                    gameMode.handleContainerInput(containerId, sourceMenuSlot, 0, ContainerInput.QUICK_MOVE, player);
                } else {
                    gameMode.handleContainerInput(containerId, sourceMenuSlot, 0, ContainerInput.PICKUP, player);
                    gameMode.handleContainerInput(containerId, armorMenuSlot, 0, ContainerInput.PICKUP, player);
                    gameMode.handleContainerInput(containerId, sourceMenuSlot, 0, ContainerInput.PICKUP, player);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * صيانة بقاء البوت: الأولوية القصوى لتوتم البقاء عند انخفاض الصحة، وإعادة الدرع عند التعافي، وارتداء أقوى الدروع.
     *
     * @return true إذا كان التوتم مطلوباً ومُفعلاً في اليد اليسرى الآن بسبب الخطر الحرج.
     */
    public static boolean maintainCombatSurvivalGear(
            LocalPlayer player,
            MultiPlayerGameMode gameMode,
            boolean tryEquipArmor,
            boolean restoreShieldWhenHealthy) {
        if (player == null || gameMode == null) return false;

        boolean totemCrisis = false;
        if (isCriticalHealthForTotem(player)) {
            ensureTotemInOffhand(player, gameMode);
            totemCrisis = player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || hasTotemInInventory(player);
        } else if (restoreShieldWhenHealthy && player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
            if (!ensureInOffhand(player, gameMode, stack -> stack.is(Items.SHIELD) && !player.getCooldowns().isOnCooldown(stack))) {
                ensureInOffhand(player, gameMode, stack -> stack.is(Items.SHIELD));
            }
        }

        if (tryEquipArmor) {
            equipBestArmor(player, gameMode);
        }
        return totemCrisis;
    }

    public static boolean ensureBestSwordInMainHand(
            LocalPlayer player,
            MultiPlayerGameMode gameMode,
            boolean prioritizeLooting,
            int fallbackHotbarSlot) {
        return ensureBestInMainHand(player, gameMode, stack -> getSwordCombatScore(stack, prioritizeLooting), 1, fallbackHotbarSlot);
    }

    public static boolean ensureBestAxeInMainHand(
            LocalPlayer player,
            MultiPlayerGameMode gameMode,
            boolean prioritizeLooting,
            int fallbackHotbarSlot) {
        return ensureBestInMainHand(player, gameMode, stack -> getAxeCombatScore(stack, prioritizeLooting), 1, fallbackHotbarSlot);
    }

    public static boolean hasKeepableGearOrTool(LocalPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.is(Items.TOTEM_OF_UNDYING) || stack.is(Items.SHIELD) || stack.is(Items.ELYTRA) || stack.is(Items.LEAD)) {
            return true;
        }
        if (stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)
                || stack.is(ItemTags.SPEARS) || stack.is(Items.MACE) || stack.is(Items.TRIDENT)) {
            return true;
        }
        if (getSwordTier(stack) >= 6 || getAxeTier(stack) >= 6 || stack.isEnchanted()) {
            return true;
        }
        if (getSwordTier(stack) > 0) {
            return getSwordCombatScore(stack, true) >= getBestSwordScore(player, true);
        }
        if (getAxeTier(stack) > 0) {
            return getAxeCombatScore(stack, false) >= getBestAxeScore(player, false);
        }
        if (isRangedWeapon(stack)) {
            int myScore = getRangedWeaponTier(stack, true);
            int bestRanged = 0;
            if (player != null) {
                for (int i = 0; i < 36; i++) {
                    ItemStack invStack = player.getInventory().getItem(i);
                    if (isRangedWeapon(invStack)) {
                        bestRanged = Math.max(bestRanged, getRangedWeaponTier(invStack, true));
                    }
                }
            }
            return myScore >= bestRanged && myScore > 0;
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            int tier = getArmorTier(stack, slot);
            if (tier >= 55) { // احتفاظ بدرع دايموند/نيذررايت أو مطوّر
                return true;
            }
        }
        return false;
    }
}
