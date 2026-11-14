package com.helpermod.bot.combat;

import com.helpermod.bot.BotEquipmentHelper;
import com.helpermod.bot.BotFollowController;
import com.helpermod.bot.friend.BotFriendManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * مدير ومنسق المعارك والقتال التكتيكي المستقل للبوت (BotCombatManager)
 * - يفصل منطق القتال التكتيكي تماماً عن منطق الملاحة وبناء المسارات
 * - يفضل الفأس للأعداء القليلة والمتفرقة مع قفزة Crit محسوبة
 * - يفضل السيف عند وجود أكثر من عدوين في نفس الجهة لضربة Sweeping شاملة وبدون قفز إطلاقاً
 * - نظام تثبيت الهدف (Target Lock) لمنع التوهان بين الأعداء الكثر
 */
@SuppressWarnings("unused")
public class BotCombatManager {
    private static final BotCombatManager INSTANCE = new BotCombatManager();

    public static BotCombatManager getInstance() {
        return INSTANCE;
    }

    public enum WeaponMode {
        AXE,
        SWORD
    }

    // سجل الأعداء المستهدفين مع مؤقت صلاحية النبضات
    private final Map<UUID, Integer> hostileTargets = new HashMap<>();

    // نظام تثبيت الهدف لمنع التوهان بين الأعداء (Target Focus & Lock-in)
    private UUID lockedTargetUUID = null;
    private int targetLockTicks = 0;

    // حالة شحن القفز للضربة الحرجة بالفأس فقط
    private boolean isJumpingForAxeCrit = false;
    private int axeCritTicks = 0;
    private int combatJumpCooldown = 0;

    // تبريد الأسلحة والدروع ومسح الأعداء
    private int shieldSwapCooldown = 0;
    private int enemyScanCooldown = 0;
    private int arrowDefenseCooldown = 0;

    // تتبع صحة القائد وصحة البوت لرصد الاعتداءات
    private float lastCompanionHealth = -1.0f;
    private float lastPlayerHealth = -1.0f;

    // تكتيك قتال الكريبر (Hit & Run 10 Blocks with Sprint-Jump)
    public enum CreeperCombatState {
        NONE,
        APPROACH_AND_STRIKE,
        FLEE_SPRINT_JUMP,
        ASSESS
    }
    private CreeperCombatState creeperState = CreeperCombatState.NONE;
    private Vec3 creeperFleeStartPos = null;
    private Vec3 creeperFleeDir = null;
    private int creeperFleeTicks = 0;
    private int creeperAssessTicks = 0;

    // تثبيت اختيار السلاح لمنع تصفير شحن الهجوم (Attack Charge Reset)
    private WeaponMode currentWeaponChoice = WeaponMode.SWORD;
    private int weaponChoiceLockTicks = 0;

    private BotCombatManager() {}

    /**
     * المعالجة القتالية الرئيسية في كل Tick
     * @return true إذا كان البوت في حالة اشتباك أو قتال نشط، و false إذا كان الوضع آمناً ولا توجد معارك
     */
    public boolean tickCombat(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode, Player companion, double distToCompanion) {
        if (client == null || player == null || level == null || gameMode == null || player.isDeadOrDying()) {
            resetCombat(client);
            return false;
        }

        // إنقاص المؤقتات
        if (combatJumpCooldown > 0) combatJumpCooldown--;
        if (shieldSwapCooldown > 0) shieldSwapCooldown--;
        if (enemyScanCooldown > 0) enemyScanCooldown--;
        if (arrowDefenseCooldown > 0) arrowDefenseCooldown--;
        if (targetLockTicks > 0) targetLockTicks--;
        if (weaponChoiceLockTicks > 0) weaponChoiceLockTicks--;

        // تحديث سجل الأعداء والتهديدات المحيطة
        updateHostilityRegistry(client, player, level, companion);

        // اختيار الهدف المستهدف مع نظام تثبيت الهدف
        LivingEntity target = getActiveHostileTarget(level, player, companion, distToCompanion);

        if (target == null) {
            // لا يوجد أعداء نشطون: خفض الدرع وتصفير تكتيك الكريبر
            if (client.options.keyUse.isDown()) {
                client.options.keyUse.setDown(false);
            }
            if (player.isUsingItem() && player.getUseItem().is(Items.SHIELD)) {
                gameMode.releaseUsingItem(player);
                player.stopUsingItem();
            }
            if (creeperState != CreeperCombatState.NONE) {
                creeperState = CreeperCombatState.NONE;
                creeperFleeStartPos = null;
                creeperFleeDir = null;
            }
            isJumpingForAxeCrit = false;
            axeCritTicks = 0;
            return false;
        }

        double distToEnemy = player.distanceTo(target);

        // مصفوفة القرار للاشتباك:
        // إذا ابتعد القائد مسافة كبيرة (> 14.0 بلوكة)، فإن اللحاق به له الأولوية القصوى ولا نشتبك إلا إذا كان العدو ملاصقاً تماماً للبوت!
        boolean hasSight = player.hasLineOfSight(target);
        boolean shouldEngage;
        if (distToCompanion > 14.0) {
            // القائد مبتعد جداً: نشتبك فقط إذا كان العدو مكشوفاً وملاصقاً للبوت جداً (<= 3.2 بلوكة) للدفاع عن النفس
            shouldEngage = hasSight && distToEnemy <= 3.2;
        } else {
            // القائد ضمن نطاق الحراسة الطبيعي (<= 14.0 بلوكة): البوت يحمي القائد والمنطقة طالما أن العدو مكشوف
            shouldEngage = hasSight && (distToEnemy <= 10.0 || (companion != null && companion.distanceTo(target) <= 10.0));
        }

        if (!shouldEngage) {
            // العدو بعيد أو محجوب أو القائد يبتعد: الانسحاب الفوري للحاق بالقائد
            lockedTargetUUID = null;
            // خفض الدرع فوراً لتمكين الركض السريع (Sprint) واللحاق بالقائد دون بطء
            if (client.options.keyUse.isDown()) {
                client.options.keyUse.setDown(false);
            }
            if (player.isUsingItem()) {
                gameMode.releaseUsingItem(player);
                player.stopUsingItem();
            }
            return false; // نترك لـ BotFollowController قيادة الركض السريع نحو القائد
        }

        // تنفيذ المعركة التكتيكية المستقلة
        return executeTacticalCombat(client, player, level, gameMode, companion, target, distToEnemy);
    }

    /**
     * تنفيذ القتال التكتيكي بالكامل
     */
    private boolean executeTacticalCombat(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode, Player companion, LivingEntity enemy, double distToEnemy) {
        // 1. فحص الكريبر كحالة تكتيكية استثنائية (ضربة ثم هروب 10 بلوكات)
        boolean isCreeper = enemy instanceof Creeper
            || (enemy instanceof Mob mob && mob.getType().getDescriptionId().contains("creeper"));

        if (isCreeper && enemy instanceof Creeper creeper) {
            return executeCreeperTactics(client, player, level, gameMode, creeper, distToEnemy);
        } else {
            if (creeperState != CreeperCombatState.NONE) {
                creeperState = CreeperCombatState.NONE;
                creeperFleeStartPos = null;
                creeperFleeDir = null;
            }
        }

        // 2. تحديد نوع السلاح التكتيكي الأنسب:
        // - أكثر من عدوين في نفس الجهة -> السيف (Sword) لضربة Sweeping Edge شاملة وبدون قفز!
        // - أعداء قليلة ومتفرقة -> الفأس (Axe) لضربة Crit بالقفز المنضبط
        WeaponMode weaponMode = determineWeaponChoice(player, level, enemy);

        if (weaponMode == WeaponMode.SWORD) {
            ensureBestSwordInMainHand(player, gameMode);
        } else {
            ensureBestAxeInMainHand(player, gameMode);
        }

        ensureShieldInOffhand(player, gameMode);

        // 3. توجيه النظر بسلاسة ودقة نحو العدو
        smoothLookAt(player, enemy.getX(), enemy.getEyeY() - 0.15, enemy.getZ(), 85.0f);

        // 4. فحص اكتمال شحن السلاح
        float charge = player.getAttackStrengthScale(0.0f);
        boolean isFullyCharged = (weaponMode == WeaponMode.SWORD) ? (charge >= 0.85f) : (charge >= 0.90f);

        if (distToEnemy <= 3.2) {
            // العدو في نطاق الضرب المباشر:
            client.options.keyDown.setDown(false);

            if (isFullyCharged) {
                // خفض الدرع عند الهجوم
                client.options.keyUse.setDown(false);
                if (player.isUsingItem() && player.getUseItem().is(Items.SHIELD)) {
                    gameMode.releaseUsingItem(player);
                    player.stopUsingItem();
                }

                // ==========================================
                // أ) القتال بالسيف (SWORD): ممنوع القفز نهائياً!
                // ==========================================
                if (weaponMode == WeaponMode.SWORD) {
                    client.options.keyJump.setDown(false);
                    isJumpingForAxeCrit = false;
                    axeCritTicks = 0;

                    smoothLookAt(player, enemy.getX(), enemy.getEyeY() - 0.15, enemy.getZ(), 100.0f);
                    gameMode.attack(player, enemy);
                    player.swing(InteractionHand.MAIN_HAND);

                    checkEnemyDefeated(enemy);
                    client.options.keyUp.setDown(distToEnemy > 1.8);
                }
                // ==========================================
                // ب) القتال بالفأس (AXE): قفزة Crit محسوبة
                // ==========================================
                else {
                    if (player.isInWater() || player.onClimbable()) {
                        smoothLookAt(player, enemy.getX(), enemy.getEyeY() - 0.15, enemy.getZ(), 90.0f);
                        gameMode.attack(player, enemy);
                        player.swing(InteractionHand.MAIN_HAND);
                        checkEnemyDefeated(enemy);
                        client.options.keyJump.setDown(false);
                        isJumpingForAxeCrit = false;
                    } else if (player.onGround() && !isJumpingForAxeCrit && combatJumpCooldown <= 0 && BotFollowController.isCeilingClear(level, player.blockPosition())) {
                        isJumpingForAxeCrit = true;
                        axeCritTicks = 0;
                        combatJumpCooldown = 12;
                        client.options.keyJump.setDown(true);
                        client.options.keyUp.setDown(distToEnemy > 1.6);
                    } else if (isJumpingForAxeCrit) {
                        client.options.keyJump.setDown(false);
                        axeCritTicks++;
                        boolean isFalling = player.getDeltaMovement().y < -0.01;
                        boolean jumpTimeout = axeCritTicks > 6;

                        if (isFalling || jumpTimeout) {
                            smoothLookAt(player, enemy.getX(), enemy.getEyeY() - 0.15, enemy.getZ(), 90.0f);
                            gameMode.attack(player, enemy);
                            player.swing(InteractionHand.MAIN_HAND);
                            checkEnemyDefeated(enemy);

                            client.options.keyJump.setDown(false);
                            client.options.keyUp.setDown(distToEnemy > 1.6);
                            isJumpingForAxeCrit = false;
                            axeCritTicks = 0;
                        }
                    } else {
                        // هجوم أرضي مباشر دون انتظار القفز لتسريع القضاء على الأعداء
                        smoothLookAt(player, enemy.getX(), enemy.getEyeY() - 0.15, enemy.getZ(), 90.0f);
                        gameMode.attack(player, enemy);
                        player.swing(InteractionHand.MAIN_HAND);
                        checkEnemyDefeated(enemy);
                        client.options.keyJump.setDown(false);
                        client.options.keyUp.setDown(distToEnemy > 1.6);
                    }
                }
            } else {
                // فترة شحن السلاح القصيرة (بين الضربات):
                client.options.keyJump.setDown(false);
                isJumpingForAxeCrit = false;
                axeCritTicks = 0;

                smoothLookAt(player, enemy.getX(), enemy.getEyeY() - 0.15, enemy.getZ(), 100.0f);

                // إذا كانت صحة البوت منخفضة جداً (< 8 قلوب)، يتراجع بالدرع
                if (player.getHealth() < 8.0f) {
                    checkShieldDisabledAndSwap(client, player, gameMode);
                    client.options.keyUse.setDown(true);
                    if (!player.isUsingItem() || !player.getUseItem().is(Items.SHIELD)) {
                        gameMode.useItem(player, InteractionHand.OFF_HAND);
                        player.startUsingItem(InteractionHand.OFF_HAND);
                    }
                    client.options.keyUp.setDown(false);
                    client.options.keyDown.setDown(true);
                } else if (distToEnemy <= 2.2) {
                    // العدو ملاصق ومستعد للضرب المضاد: رفع الدرع للصد اللحظي
                    checkShieldDisabledAndSwap(client, player, gameMode);
                    client.options.keyUse.setDown(true);
                    if (!player.isUsingItem() || !player.getUseItem().is(Items.SHIELD)) {
                        gameMode.useItem(player, InteractionHand.OFF_HAND);
                        player.startUsingItem(InteractionHand.OFF_HAND);
                    }
                    client.options.keyUp.setDown(false);
                    client.options.keyDown.setDown(false);
                } else {
                    // العدو على بعد خطوة: خفض الدرع والاقتراب السريع للاستعداد للضربة القادمة
                    if (client.options.keyUse.isDown()) client.options.keyUse.setDown(false);
                    if (player.isUsingItem() && player.getUseItem().is(Items.SHIELD)) {
                        gameMode.releaseUsingItem(player);
                        player.stopUsingItem();
                    }
                    client.options.keyDown.setDown(false);
                    client.options.keyUp.setDown(true);
                }
            }
        } else {
            // العدو أبعد من 3.2 بلوكات: الركض السريع (Sprint) نحوه فوراً
            client.options.keyDown.setDown(false);
            client.options.keyUp.setDown(true);
            client.options.keySprint.setDown(true);
            player.setSprinting(true);

            // خفض الدرع أثناء الركض نحوه إلا إذا كان هناك سهم قادم
            if (arrowDefenseCooldown <= 0) {
                if (client.options.keyUse.isDown()) client.options.keyUse.setDown(false);
                if (player.isUsingItem() && player.getUseItem().is(Items.SHIELD)) {
                    gameMode.releaseUsingItem(player);
                    player.stopUsingItem();
                }
            } else {
                checkShieldDisabledAndSwap(client, player, gameMode);
                client.options.keyUse.setDown(true);
            }

            // فحص القفز فقط عند وجود عائق أمامي حقيقي أو درجة صلبة
            if (combatJumpCooldown <= 0 && player.onGround() && BotFollowController.isCeilingClear(level, player.blockPosition())) {
                if (BotFollowController.isObstacleDirectlyAhead(player, level, enemy.getX(), enemy.getZ())
                    || BotFollowController.hasStepDirectlyAhead(player, level, enemy.getX(), enemy.getZ())) {
                    client.options.keyJump.setDown(true);
                    combatJumpCooldown = 10;
                } else {
                    client.options.keyJump.setDown(false);
                }
            } else {
                client.options.keyJump.setDown(false);
            }
            isJumpingForAxeCrit = false;
            axeCritTicks = 0;
        }

        return true;
    }

    /**
     * فحص وتحديد السلاح التكتيكي الأنسب مع تثبيت الاختيار لمنع تصفير شحن الهجوم:
     * - إن كان هناك أكثر من عدوين في نفس الجهة (Crowd/Swarm): يفضل السيف.
     * - إن كان الأعداء قليلة ومتفرقة: يفضل الفأس.
     */
    public WeaponMode determineWeaponChoice(LocalPlayer player, ClientLevel level, LivingEntity primaryTarget) {
        if (primaryTarget == null) return WeaponMode.AXE;

        // إذا كان السلاح مثبتاً ولم تنتهِ مهلة التثبيت بعد، نحافظ عليه لمنع تصفير شحن الهجوم (Charge Reset)
        if (weaponChoiceLockTicks > 0) {
            return currentWeaponChoice;
        }

        // البحث عن الأعداء في محيط 5.0 بلوكات من البوت
        List<LivingEntity> nearbyHostiles = getNearbyHostiles(level, player, 5.0);

        WeaponMode chosen = WeaponMode.AXE;
        if (nearbyHostiles.size() >= 2) {
            // فحص هل هناك أكثر من عدوين يقعان في نفس الجهة أمام البوت؟
            Vec3 botPos = player.position();
            Vec3 botLook = player.getViewVector(1.0f);
            int enemiesInSameDir = 0;

            for (LivingEntity enemy : nearbyHostiles) {
                Vec3 toEnemy = enemy.position().subtract(botPos).normalize();
                double dot = botLook.dot(toEnemy);
                if (dot >= 0.35 || enemy.distanceTo(primaryTarget) <= 2.5) {
                    enemiesInSameDir++;
                }
            }

            if (enemiesInSameDir >= 2 && hasSword(player)) {
                chosen = WeaponMode.SWORD;
            } else if (hasAxe(player)) {
                chosen = WeaponMode.AXE;
            } else {
                chosen = hasSword(player) ? WeaponMode.SWORD : WeaponMode.AXE;
            }
        } else {
            // أعداء قليلة ومتفرقة: تفضيل الفأس
            if (hasAxe(player)) {
                chosen = WeaponMode.AXE;
            } else {
                chosen = hasSword(player) ? WeaponMode.SWORD : WeaponMode.AXE;
            }
        }

        currentWeaponChoice = chosen;
        weaponChoiceLockTicks = 30; // تثبيت السلاح لمدة ثانية ونصف
        return chosen;
    }

    /**
     * تكتيك الكريبر: ضربة ثم هروب 10 بلوكات بأقصى سرعة
     */
    private boolean executeCreeperTactics(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode, Creeper creeper, double distToCreeper) {
        if (!creeper.isAlive() || creeper.isRemoved() || creeper.isDeadOrDying() || creeper.getHealth() <= 0) {
            hostileTargets.remove(creeper.getUUID());
            if (lockedTargetUUID != null && lockedTargetUUID.equals(creeper.getUUID())) {
                lockedTargetUUID = null;
            }
            creeperState = CreeperCombatState.NONE;
            return false;
        }

        if (creeperState == CreeperCombatState.NONE) {
            creeperState = CreeperCombatState.APPROACH_AND_STRIKE;
        }

        if (creeperState == CreeperCombatState.APPROACH_AND_STRIKE) {
            ensureBestAxeInMainHand(player, gameMode);
            client.options.keyUse.setDown(false);
            if (player.isUsingItem() && player.getUseItem().is(Items.SHIELD)) {
                gameMode.releaseUsingItem(player);
                player.stopUsingItem();
            }

            smoothLookAt(player, creeper.getX(), creeper.getEyeY() - 0.15, creeper.getZ(), 60.0f);
            boolean isSwelling = creeper.getSwellDir() > 0;

            if (distToCreeper <= 3.2) {
                if (player.onGround() && !isJumpingForAxeCrit && combatJumpCooldown <= 0 && BotFollowController.isCeilingClear(level, player.blockPosition())) {
                    isJumpingForAxeCrit = true;
                    axeCritTicks = 0;
                    combatJumpCooldown = 12;
                    client.options.keyJump.setDown(true);
                    client.options.keyUp.setDown(distToCreeper > 1.8);
                } else {
                    client.options.keyJump.setDown(false);
                    axeCritTicks++;
                    boolean isFalling = player.getDeltaMovement().y < -0.01;
                    boolean jumpTimeout = axeCritTicks > 12;

                    if (isFalling || jumpTimeout || isSwelling) {
                        smoothLookAt(player, creeper.getX(), creeper.getEyeY() - 0.15, creeper.getZ(), 90.0f);
                        gameMode.attack(player, creeper);
                        player.swing(InteractionHand.MAIN_HAND);

                        if (!creeper.isAlive() || creeper.isRemoved() || creeper.isDeadOrDying() || creeper.getHealth() <= 0) {
                            hostileTargets.remove(creeper.getUUID());
                            if (lockedTargetUUID != null && lockedTargetUUID.equals(creeper.getUUID())) {
                                lockedTargetUUID = null;
                            }
                            creeperState = CreeperCombatState.NONE;
                            return false;
                        }

                        // فور الضربة: الهروب السريع 10 بلوكات في الجهة المعاكسة
                        creeperState = CreeperCombatState.FLEE_SPRINT_JUMP;
                        creeperFleeStartPos = player.position();
                        creeperFleeDir = BotFollowController.calculateCreeperEscapeDirection(level, player, creeper.position());
                        creeperFleeTicks = 0;
                        isJumpingForAxeCrit = false;
                        axeCritTicks = 0;
                    }
                }
            } else {
                client.options.keyDown.setDown(false);
                client.options.keyUp.setDown(true);
                client.options.keySprint.setDown(true);
                player.setSprinting(true);
                if (combatJumpCooldown <= 0 && BotFollowController.isObstacleDirectlyAhead(player, level, creeper.getX(), creeper.getZ()) && player.onGround() && BotFollowController.isCeilingClear(level, player.blockPosition())) {
                    client.options.keyJump.setDown(true);
                    combatJumpCooldown = 10;
                } else {
                    client.options.keyJump.setDown(false);
                }
                isJumpingForAxeCrit = false;
                axeCritTicks = 0;
            }
            return true;
        }

        if (creeperState == CreeperCombatState.FLEE_SPRINT_JUMP) {
            if (creeperFleeStartPos == null || creeperFleeDir == null) {
                creeperFleeStartPos = player.position();
                creeperFleeDir = BotFollowController.calculateCreeperEscapeDirection(level, player, creeper.position());
            }

            double fledDist = player.position().distanceTo(creeperFleeStartPos);
            creeperFleeTicks++;

            if (client.options.keyUse.isDown()) client.options.keyUse.setDown(false);
            if (player.isUsingItem()) {
                gameMode.releaseUsingItem(player);
                player.stopUsingItem();
            }

            float runYaw = (float) (Math.atan2(creeperFleeDir.z, creeperFleeDir.x) * 180.0 / Math.PI) - 90.0f;
            player.setYRot(runYaw);
            player.yHeadRot = runYaw;
            player.yBodyRot = runYaw;
            player.setXRot(0.0f);

            client.options.keyUp.setDown(true);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keySprint.setDown(true);
            player.setSprinting(true);

            if (player.onGround() && combatJumpCooldown <= 0 && BotFollowController.isCeilingClear(level, player.blockPosition())) {
                client.options.keyJump.setDown(true);
                combatJumpCooldown = 8;
            } else {
                client.options.keyJump.setDown(false);
            }

            if (fledDist >= 10.0 || distToCreeper >= 12.0 || creeperFleeTicks > 45) {
                client.options.keyUp.setDown(false);
                client.options.keyJump.setDown(false);
                client.options.keySprint.setDown(false);
                player.setSprinting(false);
                creeperState = CreeperCombatState.ASSESS;
                creeperAssessTicks = 0;
            }
            return true;
        }

        if (creeperState == CreeperCombatState.ASSESS) {
            client.options.keyUp.setDown(false);
            client.options.keyJump.setDown(false);
            client.options.keySprint.setDown(false);
            player.setSprinting(false);

            smoothLookAt(player, creeper.getX(), creeper.getEyeY() - 0.15, creeper.getZ(), 85.0f);

            if (!creeper.isAlive() || creeper.isRemoved() || creeper.isDeadOrDying() || creeper.getHealth() <= 0) {
                hostileTargets.remove(creeper.getUUID());
                if (lockedTargetUUID != null && lockedTargetUUID.equals(creeper.getUUID())) {
                    lockedTargetUUID = null;
                }
                creeperState = CreeperCombatState.NONE;
                return false;
            }

            creeperAssessTicks++;
            float charge = player.getAttackStrengthScale(0.0f);
            if (charge >= 0.90f || creeperAssessTicks >= 10) {
                creeperState = CreeperCombatState.APPROACH_AND_STRIKE;
            }
            return true;
        }

        return true;
    }

    /**
     * اختيار الهدف المستهدف مع نظام تثبيت الهدف (Target Focus Lock) لمنع التشتت بين الأعداء
     */
    public LivingEntity getActiveHostileTarget(ClientLevel level, LocalPlayer player, Player companion, double distToCompanion) {
        if (hostileTargets.isEmpty()) {
            lockedTargetUUID = null;
            return null;
        }

        // إذا كان القائد يبتعد مسافة كبيرة جداً (> 14.0 بلوكة)، نتجاهل الأعداء البعيدين ولا ننظر إلا لعدو ملاصق ومكشوف للبوت للدفاع السريع
        if (distToCompanion > 14.0) {
            lockedTargetUUID = null;
            LivingEntity immediateMouth = null;
            double minDistSq = 3.2 * 3.2;
            for (UUID uuid : hostileTargets.keySet()) {
                LivingEntity target = findLivingEntityByUUID(level, player, uuid);
                if (target != null && target.isAlive() && !target.isRemoved() && !target.isDeadOrDying() && target.getHealth() > 0 && !isFriendOrCompanion(target, player)) {
                    double dSq = player.distanceToSqr(target);
                    if (dSq <= minDistSq && player.hasLineOfSight(target)) {
                        minDistSq = dSq;
                        immediateMouth = target;
                    }
                }
            }
            return immediateMouth; // إذا لم يكن هناك عدو ملاصق، نرجع null فوراً ليلحق بالقائد!
        }

        // 1. إن كان هناك هدف مثبت وما زال في مدى الاشتباك والتركيز ومكشوفاً:
        if (lockedTargetUUID != null && targetLockTicks > 0) {
            LivingEntity current = findLivingEntityByUUID(level, player, lockedTargetUUID);
            if (current != null && current.isAlive() && !current.isRemoved() && !current.isDeadOrDying() && current.getHealth() > 0 && !isFriendOrCompanion(current, player)) {
                if (player.distanceTo(current) <= 12.0 && player.hasLineOfSight(current)) {
                    return current;
                }
            }
            lockedTargetUUID = null;
        }

        // 2. فحص الخطر المباشر الملاصق المكشوف (عدو في مدى <= 4.0 بلوكات من البوت أو القائد)
        LivingEntity immediateThreat = null;
        double minImmediateDistSq = 4.0 * 4.0;

        Iterator<Map.Entry<UUID, Integer>> it = hostileTargets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            UUID uuid = entry.getKey();
            if (player != null && uuid.equals(player.getUUID())) {
                it.remove();
                continue;
            }
            LivingEntity target = findLivingEntityByUUID(level, player, uuid);
            if (target == null || target == player || target.getId() == player.getId() || target.isRemoved() || !target.isAlive() || target.isDeadOrDying() || target.getHealth() <= 0 || isFriendOrCompanion(target, player)) {
                it.remove();
                continue;
            }
            double distToBotSq = player.distanceToSqr(target);
            double distToCompSq = companion != null ? companion.distanceToSqr(target) : Double.MAX_VALUE;

            if (distToBotSq > 400.0 && distToCompSq > 400.0) {
                it.remove();
                continue;
            }

            // التأكد من وجود خط رؤية واضح للعدو حتى لا يشتبك مع أعداء في كهوف تحت الأرض أو خلف جدران مصمتة
            boolean sight = player.hasLineOfSight(target) || (companion != null && companion.hasLineOfSight(target));
            if (!sight) continue;

            if (distToBotSq < minImmediateDistSq || distToCompSq < minImmediateDistSq) {
                immediateThreat = target;
                minImmediateDistSq = Math.min(distToBotSq, distToCompSq);
            }
        }

        if (immediateThreat != null) {
            lockedTargetUUID = immediateThreat.getUUID();
            targetLockTicks = 40; // تثبيت الهدف لمدة ثانيتين
            return immediateThreat;
        }

        // 3. اختيار أقرب عدو حي ومكشوف للبوت أو القائد ضمن محيط 10.0 بلوكات
        LivingEntity nextTarget = null;
        double bestDistSq = 10.0 * 10.0;
        for (UUID uuid : hostileTargets.keySet()) {
            LivingEntity target = findLivingEntityByUUID(level, player, uuid);
            if (target != null && target.isAlive() && !target.isRemoved() && !target.isDeadOrDying() && target.getHealth() > 0 && !isFriendOrCompanion(target, player)) {
                if (player.hasLineOfSight(target)) {
                    double distSq = player.distanceToSqr(target);
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        nextTarget = target;
                    }
                }
            }
        }

        if (nextTarget != null) {
            lockedTargetUUID = nextTarget.getUUID();
            targetLockTicks = 40;
        }
        return nextTarget;
    }

    /**
     * تحديث سجل الأعداء والتهديدات المحيطة
     */
    private void updateHostilityRegistry(Minecraft client, LocalPlayer player, ClientLevel level, Player companion) {
        // إنقاص مدة صلاحية الأعداء المسجلين
        Iterator<Map.Entry<UUID, Integer>> it = hostileTargets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            int remaining = entry.getValue() - 1;
            if (remaining <= 0) {
                it.remove();
            } else {
                entry.setValue(remaining);
            }
        }

        if (companion != null) {
            float compHealth = companion.getHealth();
            boolean compDamaged = companion.hurtTime > 0 || (lastCompanionHealth > 0 && compHealth < lastCompanionHealth - 0.01f);
            boolean compAttacking = companion.swinging || companion.swingTime > 0 || companion.attackAnim > 0;

            if (compDamaged || compAttacking) {
                scanAndEnlistEnemies(client, player, level, companion, companion.position());
            }
            lastCompanionHealth = compHealth;
        }

        float botHealth = player.getHealth();
        boolean botDamaged = player.hurtTime > 0 || (lastPlayerHealth > 0 && botHealth < lastPlayerHealth - 0.01f);
        if (botDamaged) {
            scanAndEnlistEnemies(client, player, level, companion, player.position());
        }
        lastPlayerHealth = botHealth;

        // مقذوفات قادمة
        Projectile incoming = findIncomingProjectile(level, player, companion);
        if (incoming != null) {
            arrowDefenseCooldown = 12;
            if (incoming.getOwner() instanceof LivingEntity shooter && isHostileMob(shooter) && !isFriendOrCompanion(shooter, player)) {
                registerHostileTarget(client, player, shooter);
            }
        }

        // مسح مستمر للمحيط كل 10 تكات (نصف ثانية) لرصد أي وحش يقترب دون تأخير
        if (enemyScanCooldown <= 0) {
            scanAndEnlistEnemies(client, player, level, companion, player.position());
            if (companion != null) {
                scanAndEnlistEnemies(client, player, level, companion, companion.position());
            }
            enemyScanCooldown = 10;
        }
    }

    private void scanAndEnlistEnemies(Minecraft client, LocalPlayer player, ClientLevel level, Player companion, Vec3 center) {
        AABB box = new AABB(center.x - 12.0, center.y - 4.0, center.z - 12.0, center.x + 12.0, center.y + 4.0, center.z + 12.0);
        List<LivingEntity> list = level.getEntitiesOfClass(LivingEntity.class, box, e -> e != player && e != companion && e.isAlive() && !e.isRemoved() && !isFriendOrCompanion(e, player) && isHostileMob(e));
        for (LivingEntity hostile : list) {
            registerHostileTarget(client, player, hostile);
        }
    }

    public void registerHostileTarget(Minecraft client, LocalPlayer player, LivingEntity entity) {
        if (entity == null || entity == player || entity.getId() == player.getId() || isFriendOrCompanion(entity, player)) return;
        if (!entity.isAlive() || entity.isRemoved()) return;
        UUID uuid = entity.getUUID();
        hostileTargets.put(uuid, 600); // 30 ثانية
    }

    private List<LivingEntity> getNearbyHostiles(ClientLevel level, LocalPlayer player, double radius) {
        AABB box = player.getBoundingBox().inflate(radius);
        return level.getEntitiesOfClass(LivingEntity.class, box, e -> e != player && e.isAlive() && !e.isRemoved() && !isFriendOrCompanion(e, player) && isHostileMob(e));
    }

    private Projectile findIncomingProjectile(ClientLevel level, LocalPlayer player, Player companion) {
        AABB box = player.getBoundingBox().inflate(12.0);
        List<Projectile> list = level.getEntitiesOfClass(Projectile.class, box, p -> p.isAlive() && !p.isRemoved() && !p.onGround());
        for (Projectile proj : list) {
            Vec3 motion = proj.getDeltaMovement();
            if (motion.lengthSqr() < 0.05) continue;
            Vec3 toPlayer = player.position().subtract(proj.position());
            if (toPlayer.lengthSqr() > 144.0) continue;
            if (motion.normalize().dot(toPlayer.normalize()) > 0.65) {
                return proj;
            }
        }
        return null;
    }

    private void checkEnemyDefeated(LivingEntity enemy) {
        if (!enemy.isAlive() || enemy.isRemoved() || enemy.isDeadOrDying() || enemy.getHealth() <= 0) {
            hostileTargets.remove(enemy.getUUID());
            if (lockedTargetUUID != null && lockedTargetUUID.equals(enemy.getUUID())) {
                lockedTargetUUID = null;
                targetLockTicks = 0;
            }
            weaponChoiceLockTicks = 0;
        }
    }

    public void resetCombat(Minecraft client) {
        hostileTargets.clear();
        lockedTargetUUID = null;
        targetLockTicks = 0;
        weaponChoiceLockTicks = 0;
        currentWeaponChoice = WeaponMode.SWORD;
        isJumpingForAxeCrit = false;
        axeCritTicks = 0;
        combatJumpCooldown = 0;
        creeperState = CreeperCombatState.NONE;
        creeperFleeStartPos = null;
        creeperFleeDir = null;
        creeperFleeTicks = 0;
        creeperAssessTicks = 0;
        if (client != null && client.options != null) {
            client.options.keyJump.setDown(false);
            client.options.keyUse.setDown(false);
        }
    }

    // ==========================================
    // إدارة الأسلحة والدروع (Weapon & Armor Management)
    // ==========================================
    public static int getSwordTier(ItemStack stack) {
        return BotEquipmentHelper.getSwordTier(stack);
    }

    public static int getAxeTier(ItemStack stack) {
        return BotEquipmentHelper.getAxeTier(stack);
    }

    public static boolean hasSword(LocalPlayer player) {
        return BotEquipmentHelper.hasMatchingItem(player, stack -> getSwordTier(stack) > 0, false);
    }

    public static boolean hasAxe(LocalPlayer player) {
        return BotEquipmentHelper.hasMatchingItem(player, stack -> getAxeTier(stack) > 0, false);
    }

    public boolean ensureBestSwordInMainHand(LocalPlayer player, MultiPlayerGameMode gameMode) {
        return BotEquipmentHelper.ensureBestInMainHand(player, gameMode, BotEquipmentHelper::getSwordTier, 1, 0);
    }

    public boolean ensureBestAxeInMainHand(LocalPlayer player, MultiPlayerGameMode gameMode) {
        return BotEquipmentHelper.ensureBestInMainHand(player, gameMode, BotEquipmentHelper::getAxeTier, 1, 0);
    }

    public void ensureShieldInOffhand(LocalPlayer player, MultiPlayerGameMode gameMode) {
        BotEquipmentHelper.ensureInOffhand(player, gameMode, stack -> stack.is(Items.SHIELD));
    }

    public void checkShieldDisabledAndSwap(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        if (shieldSwapCooldown > 0) return;
        ItemStack offhand = player.getOffhandItem();
        boolean shieldDisabled = offhand.is(Items.SHIELD) && player.getCooldowns().isOnCooldown(offhand);
        if (!shieldDisabled) return;

        shieldSwapCooldown = 20;
        BotEquipmentHelper.swapBackupShieldToOffhand(player, gameMode, true, true);
    }

    private LivingEntity findLivingEntityByUUID(ClientLevel level, LocalPlayer player, UUID uuid) {
        Player p = level.getPlayerByUUID(uuid);
        if (p != null) return p;
        List<LivingEntity> list = level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(32.0));
        for (LivingEntity le : list) {
            if (le.getUUID().equals(uuid)) return le;
        }
        return null;
    }

    private boolean isFriendOrCompanion(LivingEntity entity, LocalPlayer player) {
        if (entity == null) return true;
        if (player != null && (entity == player || entity.getId() == player.getId() || entity.getUUID().equals(player.getUUID()))) {
            return true;
        }
        UUID targetUUID = BotFollowController.getInstance().getTargetPlayerUUID();
        if (targetUUID != null && targetUUID.equals(entity.getUUID())) {
            return true;
        }
        if (entity instanceof Player p) {
            return BotFriendManager.getInstance().isFriend(p);
        }
        if (entity instanceof net.minecraft.world.entity.decoration.ArmorStand) return true;
        if (entity instanceof net.minecraft.world.entity.TamableAnimal tamed && tamed.isTame()) return true;
        return false;
    }

    private boolean isHostileMob(LivingEntity entity) {
        if (entity instanceof Enemy || entity instanceof Monster) return true;
        if (entity instanceof Mob mob) {
            String name = mob.getType().getDescriptionId().toLowerCase();
            return name.contains("zombie") || name.contains("skeleton") || name.contains("creeper")
                || name.contains("spider") || name.contains("witch") || name.contains("slime")
                || name.contains("drowned") || name.contains("husk") || name.contains("stray")
                || name.contains("pillager") || name.contains("vindicator") || name.contains("ravager")
                || name.contains("enderman") || name.contains("blaze") || name.contains("ghast")
                || name.contains("piglin_brute") || name.contains("hoglin") || name.contains("zoglin")
                || name.contains("phantom") || name.contains("guardian");
        }
        return false;
    }

    private void smoothLookAt(LocalPlayer player, double targetX, double targetY, double targetZ, float maxTurnDegrees) {
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

    public static float wrapDegrees(float degrees) {
        return BotEquipmentHelper.wrapDegrees(degrees);
    }
}
