package com.helpermod.bot.combat;

import com.helpermod.bot.BotEquipmentHelper;
import com.helpermod.bot.BotFollowController;
import com.helpermod.bot.BotSettingsManager;
import com.helpermod.bot.friend.BotFriendManager;
import com.helpermod.bot.path.BotNavigator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * مدير ومنسق المعارك والقتال التكتيكي المستقل للبوت (BotCombatManager)
 * - يفصل منطق القتال التكتيكي تماماً عن منطق الملاحة وبناء المسارات
 * - يفضل الفأس للأعداء القليلة والمتفرقة مع قفزة Crit محسوبة
 * - يفضل السيف عند وجود أكثر من عدوين في نفس الجهة لضربة Sweeping شاملة وبدون قفز إطلاقاً
 * - نظام تثبيت الهدف (Target Lock) لمنع التوهان بين الأعداء الكثر
 */
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
    // مؤقت حظر مؤقت للأهداف التي يتعذر الوصول إليها عبر A* (خلف جدار أو هاوية)
    private final Map<UUID, Integer> unreachableTargets = new HashMap<>();
    // سجل الموبات المحظورة في وضع الاتباع التي قام القائد نفسه بالهجوم عليها (تُقاتَل مؤقتاً حتى تنتهي المعركة)
    private final Map<UUID, Integer> companionProvokedTargets = new HashMap<>();
    private int companionAttackGraceTicks = 0;
    private final BotNavigator combatNavigator = new BotNavigator();

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
    private int armorCheckCooldown = 0;
    private final RangedCombatHelper rangedHelper = new RangedCombatHelper();

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
        if (armorCheckCooldown > 0) armorCheckCooldown--;
        if (targetLockTicks > 0) targetLockTicks--;
        if (weaponChoiceLockTicks > 0) weaponChoiceLockTicks--;
        if (companionAttackGraceTicks > 0) companionAttackGraceTicks--;
        combatNavigator.tickCooldowns();

        // صيانة الدروع وتوتم البقاء باستمرار دون تعطيل سحب السلاح أو الأكل
        boolean checkArmorNow = armorCheckCooldown <= 0 && !player.isUsingItem() && !rangedHelper.isUsingRanged();
        if (checkArmorNow) {
            armorCheckCooldown = 25;
        }
        BotEquipmentHelper.maintainCombatSurvivalGear(player, gameMode, checkArmorNow, true);

        unreachableTargets.entrySet().removeIf(entry -> {
            int rem = entry.getValue() - 1;
            if (rem <= 0) return true;
            entry.setValue(rem);
            return false;
        });

        // حراسة التنفس الطارئة في الماء (الطفو أو الهروب من السقف قبل الغرق مع السماح بالغوص الحر)
        if (combatNavigator.handleUrgentWaterBreathing(client, player, level, gameMode)) {
            rangedHelper.reset(client, player, gameMode);
            releaseShield(client, player, gameMode);
            isJumpingForAxeCrit = false;
            axeCritTicks = 0;
            return true;
        }

        // تحديث سجل الأعداء والتهديدات المحيطة
        updateHostilityRegistry(client, player, level, companion);

        // اختيار الهدف المستهدف مع نظام تثبيت الهدف
        LivingEntity target = getActiveHostileTarget(level, player, companion, distToCompanion);

        if (target == null) {
            rangedHelper.reset(client, player, gameMode);
            releaseShield(client, player, gameMode);
            if (creeperState != CreeperCombatState.NONE) {
                creeperState = CreeperCombatState.NONE;
                creeperFleeStartPos = null;
                creeperFleeDir = null;
            }
            combatNavigator.clearPathOnly();
            isJumpingForAxeCrit = false;
            axeCritTicks = 0;
            return false;
        }

        double distToEnemy = player.distanceTo(target);
        double eyeDistToEnemy = BotEquipmentHelper.getEyeToHitboxDistance(player, target);

        // مصفوفة القرار للاشتباك:
        boolean hasSight = player.hasLineOfSight(target);
        boolean shouldEngage;
        if (distToCompanion > 14.0) {
            // القائد مبتعد جداً: نشتبك فقط إذا كان العدو مكشوفاً وفي مدى الضرب الفعلي للدفاع عن النفس
            shouldEngage = hasSight && eyeDistToEnemy <= 2.95 && distToEnemy <= 3.6;
        } else {
            // القائد ضمن نطاق الحراسة: يشمل الأعداء المكشوفين حتى 16 بلوكة إن كانوا مسجلين كمهاجمين
            boolean registeredEnemy = hostileTargets.containsKey(target.getUUID());
            shouldEngage = hasSight && (distToEnemy <= 12.0 || (registeredEnemy && distToEnemy <= 16.0) || (companion != null && companion.distanceTo(target) <= 10.0));
        }

        if (!shouldEngage) {
            rangedHelper.reset(client, player, gameMode);
            lockedTargetUUID = null;
            combatNavigator.clearPathOnly();
            releaseShield(client, player, gameMode);
            return false;
        }

        // تنفيذ المعركة التكتيكية المستقلة
        return executeTacticalCombat(client, player, level, gameMode, companion, target, distToEnemy, eyeDistToEnemy);
    }

    /**
     * تنفيذ القتال التكتيكي بالكامل
     */
    private boolean executeTacticalCombat(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode, Player companion, LivingEntity enemy, double distToEnemy, double eyeDistToEnemy) {
        boolean botIncomingProjectile = findIncomingProjectile(level, player, null) != null;

        // 1. فحص الكريبر: إن توفّر سلاح مدى وخط رؤية مفتوح من مسافة آمنة (>= 5 بلوكات) يتم قنصه قبل الاقتراب
        boolean isCreeper = enemy instanceof Creeper
            || (enemy instanceof Mob mob && mob.getType().getDescriptionId().contains("creeper"));

        if (isCreeper && enemy instanceof Creeper creeper) {
            if (creeperState != CreeperCombatState.FLEE_SPRINT_JUMP && distToEnemy >= 5.0) {
                RangedCombatHelper.RangedStatus creeperRanged = rangedHelper.tickRangedAttack(
                    client, player, level, gameMode, creeper, companion, botIncomingProjectile, false
                );
                if (creeperRanged == RangedCombatHelper.RangedStatus.CHARGING
                        || creeperRanged == RangedCombatHelper.RangedStatus.FIRED) {
                    combatNavigator.clearPathOnly();
                    ensureShieldInOffhand(player, gameMode);
                    return true;
                }
                if (creeperRanged == RangedCombatHelper.RangedStatus.YIELD_FOR_SHIELD) {
                    ensureShieldInOffhand(player, gameMode);
                    raiseShield(client, player, gameMode);
                    return true;
                }
            } else {
                rangedHelper.reset(client, player, gameMode);
            }
            return executeCreeperTactics(client, player, level, gameMode, creeper, distToEnemy, eyeDistToEnemy);
        } else {
            if (creeperState != CreeperCombatState.NONE) {
                creeperState = CreeperCombatState.NONE;
                creeperFleeStartPos = null;
                creeperFleeDir = null;
            }
        }

        // 2. القتال بعيد المدى (Bow / Crossbow) قبل الدخول في مدى الاشتباك القريب
        boolean inMeleeReach = eyeDistToEnemy <= 2.95 && distToEnemy <= 3.6;
        if (!inMeleeReach) {
            RangedCombatHelper.RangedStatus rangedStatus = rangedHelper.tickRangedAttack(
                client,
                player,
                level,
                gameMode,
                enemy,
                companion,
                botIncomingProjectile,
                unreachableTargets.containsKey(enemy.getUUID())
            );
            if (rangedStatus == RangedCombatHelper.RangedStatus.CHARGING
                    || rangedStatus == RangedCombatHelper.RangedStatus.FIRED) {
                combatNavigator.clearPathOnly();
                ensureShieldInOffhand(player, gameMode);
                return true;
            }
            if (rangedStatus == RangedCombatHelper.RangedStatus.YIELD_FOR_SHIELD) {
                ensureShieldInOffhand(player, gameMode);
                raiseShield(client, player, gameMode);
                return true;
            }
        } else {
            rangedHelper.reset(client, player, gameMode);
        }

        // 3. تحديد نوع السلاح التكتيكي مع مراعاة حماية القائد والحلفاء من ضربة Sweeping Edge
        WeaponMode weaponMode = determineWeaponChoice(player, level, companion, enemy);
        boolean avoidSwordSweep = hasFriendlyNearSweep(level, player, companion, enemy);

        if (weaponMode == WeaponMode.SWORD) {
            ensureBestSwordInMainHand(player, gameMode);
        } else {
            ensureBestAxeInMainHand(player, gameMode);
        }

        ensureShieldInOffhand(player, gameMode);
        checkShieldDisabledAndSwap(client, player, gameMode);

        // 4. فحص مدى الهجوم الحقيقي من العين لصندوق تصادم العدو
        if (inMeleeReach) {
            smoothLookAt(player, enemy.getX(), enemy.getEyeY() - 0.15, enemy.getZ(), 50.0f);
            float charge = player.getAttackStrengthScale(0.0f);
            boolean isFullyCharged = (weaponMode == WeaponMode.SWORD) ? (charge >= 0.85f) : (charge >= 0.90f);

            client.options.keyDown.setDown(false);
            combatNavigator.clearPathOnly();

            if (isFullyCharged) {
                releaseShield(client, player, gameMode);

                // أ) القتال بالسيف (SWORD): بدون قفز إلا عند الحاجة لتفادي Sweep Edge بجانب موب محظور أو حليف ولا يتوفر فأس
                if (weaponMode == WeaponMode.SWORD && !avoidSwordSweep) {
                    BotNavigator.releaseJumpOrMaintainWaterBuoyancy(client, player, level, enemy);
                    isJumpingForAxeCrit = false;
                    axeCritTicks = 0;

                    smoothLookAt(player, enemy.getX(), enemy.getEyeY() - 0.15, enemy.getZ(), 50.0f);
                    gameMode.attack(player, enemy);
                    player.swing(InteractionHand.MAIN_HAND);

                    checkEnemyDefeated(enemy);
                    client.options.keyUp.setDown(distToEnemy > 1.8 && !BotNavigator.isHazardOrDropAhead(level, player, enemy.getX() - player.getX(), enemy.getZ() - player.getZ()));
                }
                // ب) القتال بالفأس (AXE) أو ضربة ساقطة تفادياً للضربة الواسعة عند قرب الحلفاء/المحظورين:
                else {
                    if (BotNavigator.isEntityInWater(level, player) || player.onClimbable()) {
                        smoothLookAt(player, enemy.getX(), enemy.getEyeY() - 0.15, enemy.getZ(), 50.0f);
                        gameMode.attack(player, enemy);
                        player.swing(InteractionHand.MAIN_HAND);
                        checkEnemyDefeated(enemy);
                        BotNavigator.releaseJumpOrMaintainWaterBuoyancy(client, player, level, enemy);
                        isJumpingForAxeCrit = false;
                    } else if (player.onGround() && !isJumpingForAxeCrit && combatJumpCooldown <= 0 && BotNavigator.isCeilingClear(level, player.blockPosition())) {
                        isJumpingForAxeCrit = true;
                        axeCritTicks = 0;
                        combatJumpCooldown = 12;
                        client.options.keyJump.setDown(true);
                        client.options.keyUp.setDown(distToEnemy > 1.6);
                    } else if (isJumpingForAxeCrit) {
                        client.options.keyJump.setDown(false);
                        axeCritTicks++;
                        boolean isFalling = player.getDeltaMovement().y < -0.01;
                        boolean jumpTimeout = axeCritTicks > 7;

                        if (isFalling || jumpTimeout) {
                            smoothLookAt(player, enemy.getX(), enemy.getEyeY() - 0.15, enemy.getZ(), 50.0f);
                            gameMode.attack(player, enemy);
                            player.swing(InteractionHand.MAIN_HAND);
                            checkEnemyDefeated(enemy);

                            BotNavigator.releaseJumpOrMaintainWaterBuoyancy(client, player, level, enemy);
                            client.options.keyUp.setDown(false);
                            isJumpingForAxeCrit = false;
                            axeCritTicks = 0;
                        }
                    } else {
                        smoothLookAt(player, enemy.getX(), enemy.getEyeY() - 0.15, enemy.getZ(), 50.0f);
                        gameMode.attack(player, enemy);
                        player.swing(InteractionHand.MAIN_HAND);
                        checkEnemyDefeated(enemy);
                        BotNavigator.releaseJumpOrMaintainWaterBuoyancy(client, player, level, enemy);
                        client.options.keyUp.setDown(false);
                    }
                }
            } else {
                // فترة شحن السلاح القصيرة
                BotNavigator.releaseJumpOrMaintainWaterBuoyancy(client, player, level, enemy);
                isJumpingForAxeCrit = false;
                axeCritTicks = 0;

                smoothLookAt(player, enemy.getX(), enemy.getEyeY() - 0.15, enemy.getZ(), 50.0f);

                boolean shouldGuard = BotEquipmentHelper.isShieldReady(player)
                    && (player.getHealth() < 8.0f || distToEnemy <= 2.3 || arrowDefenseCooldown > 0);
                if (shouldGuard) {
                    raiseShield(client, player, gameMode);
                    boolean lowHpBackpedal = player.getHealth() < 8.0f && !BotNavigator.isHazardOrDropAhead(level, player, player.getX() - enemy.getX(), player.getZ() - enemy.getZ());
                    client.options.keyUp.setDown(false);
                    client.options.keyDown.setDown(lowHpBackpedal);
                } else {
                    releaseShield(client, player, gameMode);
                    client.options.keyDown.setDown(false);
                    client.options.keyUp.setDown(distToEnemy > 2.2 && !BotNavigator.isHazardOrDropAhead(level, player, enemy.getX() - player.getX(), enemy.getZ() - player.getZ()));
                }
            }
        } else {
            // العدو خارج مدى الضرب: التنقل الآمن عبر A* (BotNavigator) مع صد المقذوفات فقط إن كان الدرع جاهزاً
            boolean blockingArrows = arrowDefenseCooldown > 0 && BotEquipmentHelper.isShieldReady(player);
            if (blockingArrows) {
                raiseShield(client, player, gameMode);
            } else {
                releaseShield(client, player, gameMode);
            }

            isJumpingForAxeCrit = false;
            axeCritTicks = 0;

            BotNavigator.NavResult navRes = combatNavigator.navigateTo(
                client,
                player,
                level,
                gameMode,
                enemy.position(),
                !blockingArrows,
                2.3,
                false,
                45.0f,
                true,
                2500
            );

            if (navRes == BotNavigator.NavResult.BLOCKED_UNREACHABLE) {
                RangedCombatHelper.RangedStatus fallbackRanged = rangedHelper.tickRangedAttack(
                    client, player, level, gameMode, enemy, companion, botIncomingProjectile, true
                );
                if (fallbackRanged == RangedCombatHelper.RangedStatus.CHARGING
                        || fallbackRanged == RangedCombatHelper.RangedStatus.FIRED) {
                    combatNavigator.clearPathOnly();
                    return true;
                }
                if (fallbackRanged == RangedCombatHelper.RangedStatus.YIELD_FOR_SHIELD) {
                    raiseShield(client, player, gameMode);
                    return true;
                }

                // العدو خلف زجاج أو جدار أو وادٍ مقطوع دون خط رمي صالح: فك القفل مؤقتاً والعودة لمتابعة القائد
                unreachableTargets.put(enemy.getUUID(), 40);
                lockedTargetUUID = null;
                targetLockTicks = 0;
                combatNavigator.reset(client);
                releaseShield(client, player, gameMode);
                return false;
            }
        }

        return true;
    }

    /**
     * فحص وتحديد السلاح التكتيكي الأنسب مع حماية القائد والحلفاء من ضربة السيف الواسعة.
     */
    public WeaponMode determineWeaponChoice(LocalPlayer player, ClientLevel level, LivingEntity primaryTarget) {
        return determineWeaponChoice(player, level, null, primaryTarget);
    }

    public WeaponMode determineWeaponChoice(LocalPlayer player, ClientLevel level, Player companion, LivingEntity primaryTarget) {
        if (primaryTarget == null) return WeaponMode.SWORD;

        boolean needShieldBreak = (primaryTarget instanceof Player otherPlayer && otherPlayer.isBlocking());
        // حماية فورية من Sweeping Edge إذا اقترب القائد أو حليف أو موب محظور حتى لا يضربه البوت عرضياً
        boolean friendlyNearSweep = hasFriendlyNearSweep(level, player, companion, primaryTarget);
        if ((needShieldBreak || friendlyNearSweep) && hasAxe(player)) {
            currentWeaponChoice = WeaponMode.AXE;
            return WeaponMode.AXE;
        }

        if (weaponChoiceLockTicks > 0) {
            boolean validLocked = (currentWeaponChoice == WeaponMode.SWORD && hasSword(player))
                || (currentWeaponChoice == WeaponMode.AXE && hasAxe(player));
            if (validLocked) {
                return currentWeaponChoice;
            }
        }

        List<LivingEntity> nearbyHostiles = getNearbyHostiles(level, player, companion, 5.0);

        WeaponMode chosen;
        boolean groupSwordSweep = false;
        if (!friendlyNearSweep && nearbyHostiles.size() >= 2) {
            Vec3 botPos = player.position();
            Vec3 botLook = player.getViewVector(1.0f);
            int enemiesInSameDir = 0;

            for (LivingEntity enemy : nearbyHostiles) {
                Vec3 toEnemy = enemy.position().subtract(botPos);
                double dot = toEnemy.lengthSqr() > 0.001 ? botLook.dot(toEnemy.normalize()) : 1.0;
                if (dot >= 0.35 || enemy.distanceTo(primaryTarget) <= 2.5) {
                    enemiesInSameDir++;
                }
            }
            groupSwordSweep = enemiesInSameDir >= 2 && hasSword(player);
        }

        if (groupSwordSweep) {
            chosen = WeaponMode.SWORD;
        } else {
            int swordScore = BotEquipmentHelper.getBestSwordScore(player, false);
            int axeScore = BotEquipmentHelper.getBestAxeScore(player, false);
            if (swordScore <= 0 && axeScore <= 0) {
                chosen = WeaponMode.SWORD;
            } else if (swordScore >= axeScore) {
                chosen = WeaponMode.SWORD;
            } else {
                chosen = WeaponMode.AXE;
            }
        }

        currentWeaponChoice = chosen;
        weaponChoiceLockTicks = 20;
        return chosen;
    }

    private boolean hasFriendlyNearSweep(ClientLevel level, LocalPlayer player, Player companion, LivingEntity primaryTarget) {
        if (level == null || player == null || primaryTarget == null) return false;
        Vec3 botLook = player.getViewVector(1.0f);
        Vec3 botPos = player.position();
        AABB area = player.getBoundingBox().inflate(4.0);
        for (LivingEntity le : level.getEntitiesOfClass(LivingEntity.class, area)) {
            if (le == player || le == primaryTarget || !le.isAlive() || le.isRemoved()) continue;
            boolean protectedEntity = (companion != null && le == companion)
                || (le instanceof Player p && (BotFriendManager.getInstance().isFriend(p) || BotFollowController.getInstance().isFollowingPlayer(p)))
                || (le instanceof TamableAnimal tamed && tamed.isTame())
                || isBlockedByFollowBan(le);
            if (!protectedEntity) continue;

            if (le.distanceTo(primaryTarget) <= 2.6) {
                return true;
            }
            if (le.distanceTo(player) <= 3.5) {
                Vec3 toFriendly = le.position().subtract(botPos);
                if (toFriendly.lengthSqr() > 0.001 && botLook.dot(toFriendly.normalize()) >= 0.15) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * تكتيك الكريبر: ضربة ثم هروب 10 بلوكات بأقصى سرعة مع حراسة مستمرة ضد الحفر والمخاطر
     */
    private boolean executeCreeperTactics(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode, Creeper creeper, double distToCreeper, double eyeDistToCreeper) {
        if (!creeper.isAlive() || creeper.isRemoved() || creeper.isDeadOrDying() || creeper.getHealth() <= 0) {
            hostileTargets.remove(creeper.getUUID());
            if (lockedTargetUUID != null && lockedTargetUUID.equals(creeper.getUUID())) {
                lockedTargetUUID = null;
            }
            creeperState = CreeperCombatState.NONE;
            combatNavigator.clearPathOnly();
            return false;
        }

        if (creeperState == CreeperCombatState.NONE) {
            creeperState = CreeperCombatState.APPROACH_AND_STRIKE;
        }

        if (creeperState == CreeperCombatState.APPROACH_AND_STRIKE) {
            if (!ensureBestAxeInMainHand(player, gameMode)) {
                ensureBestSwordInMainHand(player, gameMode);
            }
            releaseShield(client, player, gameMode);

            boolean isSwelling = creeper.getSwellDir() > 0;
            boolean inReach = eyeDistToCreeper <= 2.95 && distToCreeper <= 3.6;

            if (inReach) {
                smoothLookAt(player, creeper.getX(), creeper.getEyeY() - 0.15, creeper.getZ(), 50.0f);
                combatNavigator.clearPathOnly();

                boolean strikeNow = false;
                if (BotNavigator.isEntityInWater(level, player) || player.onClimbable() || !BotNavigator.isCeilingClear(level, player.blockPosition())) {
                    strikeNow = true;
                    isJumpingForAxeCrit = false;
                    axeCritTicks = 0;
                } else if (player.onGround() && !isJumpingForAxeCrit && combatJumpCooldown <= 0) {
                    isJumpingForAxeCrit = true;
                    axeCritTicks = 0;
                    combatJumpCooldown = 12;
                    client.options.keyJump.setDown(true);
                    client.options.keyUp.setDown(distToCreeper > 1.8);
                } else if (isJumpingForAxeCrit) {
                    client.options.keyJump.setDown(false);
                    axeCritTicks++;
                    boolean isFalling = player.getDeltaMovement().y < -0.01;
                    boolean jumpTimeout = axeCritTicks > 10;

                    if (isFalling || jumpTimeout || isSwelling) {
                        strikeNow = true;
                    }
                } else {
                    strikeNow = true;
                }

                if (strikeNow) {
                    smoothLookAt(player, creeper.getX(), creeper.getEyeY() - 0.15, creeper.getZ(), 50.0f);
                    gameMode.attack(player, creeper);
                    player.swing(InteractionHand.MAIN_HAND);
                    BotNavigator.releaseJumpOrMaintainWaterBuoyancy(client, player, level, creeper);
                    client.options.keyUp.setDown(false);

                    if (!creeper.isAlive() || creeper.isRemoved() || creeper.isDeadOrDying() || creeper.getHealth() <= 0) {
                        hostileTargets.remove(creeper.getUUID());
                        if (lockedTargetUUID != null && lockedTargetUUID.equals(creeper.getUUID())) {
                            lockedTargetUUID = null;
                        }
                        creeperState = CreeperCombatState.NONE;
                        combatNavigator.clearPathOnly();
                        isJumpingForAxeCrit = false;
                        axeCritTicks = 0;
                        return false;
                    }

                    creeperState = CreeperCombatState.FLEE_SPRINT_JUMP;
                    creeperFleeStartPos = player.position();
                    creeperFleeDir = BotFollowController.calculateCreeperEscapeDirection(level, player, creeper.position());
                    creeperFleeTicks = 0;
                    isJumpingForAxeCrit = false;
                    axeCritTicks = 0;
                }
            } else {
                isJumpingForAxeCrit = false;
                axeCritTicks = 0;
                BotNavigator.NavResult navRes = combatNavigator.navigateTo(
                    client,
                    player,
                    level,
                    gameMode,
                    creeper.position(),
                    true,
                    2.3,
                    false,
                    50.0f,
                    true,
                    2500
                );
                if (navRes == BotNavigator.NavResult.BLOCKED_UNREACHABLE) {
                    unreachableTargets.put(creeper.getUUID(), 40);
                    lockedTargetUUID = null;
                    creeperState = CreeperCombatState.NONE;
                    combatNavigator.reset(client);
                    return false;
                }
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

            releaseShield(client, player, gameMode);

            // إعادة تقييم مسار الهروب دورياً أو عند ظهور عائق/حافة خطرة أمام البوت
            if (creeperFleeTicks % 5 == 0
                || player.horizontalCollision
                || BotNavigator.isHazardOrDropAhead(level, player, creeperFleeDir.x, creeperFleeDir.z)) {
                creeperFleeDir = BotFollowController.calculateCreeperEscapeDirection(level, player, creeper.position());
            }

            float runYaw = (float) (Math.atan2(creeperFleeDir.z, creeperFleeDir.x) * 180.0 / Math.PI) - 90.0f;
            player.setYRot(runYaw);
            player.yHeadRot = runYaw;
            player.yBodyRot = runYaw;
            player.setXRot(0.0f);

            boolean pathSafeAhead = !BotNavigator.isHazardOrDropAhead(level, player, creeperFleeDir.x, creeperFleeDir.z);
            client.options.keyUp.setDown(pathSafeAhead);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keySprint.setDown(pathSafeAhead);
            player.setSprinting(pathSafeAhead);

            if (pathSafeAhead
                && player.onGround()
                && combatJumpCooldown <= 0
                && BotNavigator.isSafeToJumpAhead(level, player, creeperFleeDir.x, creeperFleeDir.z)) {
                client.options.keyJump.setDown(true);
                combatJumpCooldown = 8;
            } else {
                BotNavigator.releaseJumpOrMaintainWaterBuoyancy(client, player, level, null);
            }

            if (fledDist >= 10.0 || distToCreeper >= 12.0 || creeperFleeTicks > 45) {
                BotEquipmentHelper.releaseMovementKeys(client);
                BotNavigator.maintainWaterBuoyancy(client, player, level, creeper);
                creeperState = CreeperCombatState.ASSESS;
                creeperAssessTicks = 0;
            }
            return true;
        }

        if (creeperState == CreeperCombatState.ASSESS) {
            BotEquipmentHelper.releaseMovementKeys(client);
            BotNavigator.maintainWaterBuoyancy(client, player, level, creeper);
            smoothLookAt(player, creeper.getX(), creeper.getEyeY() - 0.15, creeper.getZ(), 50.0f);

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

        // إذا كان القائد يبتعد مسافة كبيرة جداً (> 14.0 بلوكة)، نتجاهل الأعداء البعيدين ولا ننظر إلا لعدو ملاصق في مدى الاشتباك المباشر للدفاع عن النفس
        if (distToCompanion > 14.0) {
            lockedTargetUUID = null;
            LivingEntity immediateMouth = null;
            double minEyeDist = 2.95;
            for (UUID uuid : hostileTargets.keySet()) {
                LivingEntity target = findLivingEntityByUUID(level, player, uuid);
                if (target != null && target.isAlive() && !target.isRemoved() && !target.isDeadOrDying() && target.getHealth() > 0 && !isFriendOrCompanion(target, player) && !isBlockedByFollowBan(target)) {
                    double eyeDist = BotEquipmentHelper.getEyeToHitboxDistance(player, target);
                    if (eyeDist <= minEyeDist && player.distanceTo(target) <= 3.6 && player.hasLineOfSight(target)) {
                        minEyeDist = eyeDist;
                        immediateMouth = target;
                    }
                }
            }
            return immediateMouth;
        }

        // 1. إن كان هناك هدف مثبت وما زال في مدى الاشتباك والتركيز ومكشوفاً وغير محظور الوصول:
        if (lockedTargetUUID != null && targetLockTicks > 0) {
            LivingEntity current = findLivingEntityByUUID(level, player, lockedTargetUUID);
            if (current != null && current.isAlive() && !current.isRemoved() && !current.isDeadOrDying() && current.getHealth() > 0 && !isFriendOrCompanion(current, player) && !isBlockedByFollowBan(current)) {
                boolean blocked = unreachableTargets.containsKey(current.getUUID())
                    && BotEquipmentHelper.getEyeToHitboxDistance(player, current) > 2.95;
                if (!blocked && player.distanceTo(current) <= 16.0 && player.hasLineOfSight(current)) {
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
            if (isBlockedByFollowBan(target)) {
                it.remove();
                continue;
            }
            double distToBotSq = player.distanceToSqr(target);
            double distToCompSq = companion != null ? companion.distanceToSqr(target) : Double.MAX_VALUE;

            if (distToBotSq > 400.0 && distToCompSq > 400.0) {
                it.remove();
                continue;
            }

            boolean blocked = unreachableTargets.containsKey(uuid)
                && BotEquipmentHelper.getEyeToHitboxDistance(player, target) > 2.95;
            if (blocked) continue;

            // التأكد من وجود خط رؤية واضح من البوت حتى لا يشتبك مع أعداء خلف جدران مصمتة أو في كهوف
            if (!player.hasLineOfSight(target)) continue;

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

        // 3. اختيار أقرب عدو حي ومكشوف للبوت ضمن محيط 16.0 بلوكة (يشمل الرماة والمعتدين المسجلين)
        LivingEntity nextTarget = null;
        double bestDistSq = 16.0 * 16.0;
        for (UUID uuid : hostileTargets.keySet()) {
            LivingEntity target = findLivingEntityByUUID(level, player, uuid);
            if (target != null && target.isAlive() && !target.isRemoved() && !target.isDeadOrDying() && target.getHealth() > 0 && !isFriendOrCompanion(target, player) && !isBlockedByFollowBan(target)) {
                boolean blocked = unreachableTargets.containsKey(uuid)
                    && BotEquipmentHelper.getEyeToHitboxDistance(player, target) > 2.95;
                if (!blocked && player.hasLineOfSight(target)) {
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

    private static void decrementExpiringUuids(Map<UUID, Integer> map) {
        Iterator<Map.Entry<UUID, Integer>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            int remaining = entry.getValue() - 1;
            if (remaining <= 0) {
                it.remove();
            } else {
                entry.setValue(remaining);
            }
        }
    }

    public void purgeFollowBannedTargets() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc != null ? mc.level : null;
        LocalPlayer player = mc != null ? mc.player : null;
        if (level != null && player != null) {
            hostileTargets.keySet().removeIf(uuid -> {
                LivingEntity e = findLivingEntityByUUID(level, player, uuid);
                return e != null && BotSettingsManager.getInstance().isFollowMobBanned(e);
            });
            companionProvokedTargets.keySet().removeIf(uuid -> {
                LivingEntity e = findLivingEntityByUUID(level, player, uuid);
                return e != null && BotSettingsManager.getInstance().isFollowMobBanned(e);
            });
            if (lockedTargetUUID != null) {
                LivingEntity locked = findLivingEntityByUUID(level, player, lockedTargetUUID);
                if (locked != null && BotSettingsManager.getInstance().isFollowMobBanned(locked)) {
                    lockedTargetUUID = null;
                    targetLockTicks = 0;
                }
            }
        } else {
            hostileTargets.clear();
            companionProvokedTargets.clear();
            lockedTargetUUID = null;
            targetLockTicks = 0;
        }
    }

    private boolean isBlockedByFollowBan(LivingEntity entity) {
        if (entity == null) return false;
        if (!BotSettingsManager.getInstance().isFollowMobBanned(entity)) {
            return false;
        }
        return !companionProvokedTargets.containsKey(entity.getUUID());
    }

    private void detectCompanionDirectAttacks(Minecraft client, LocalPlayer player, ClientLevel level, Player companion) {
        if (companion == null || level == null) return;
        Vec3 compEye = companion.getEyePosition();
        Vec3 compLook = companion.getViewVector(1.0f);

        // 1. رصد الهجوم القريب المباشر من القائد فقط (مع التحقق من خط النظر وعدم حجب ببلوك أو تلويح تعدين)
        AABB meleeBox = companion.getBoundingBox().inflate(5.0);
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, meleeBox)) {
            if (candidate == player || candidate == companion || !candidate.isAlive() || candidate.isRemoved()) continue;
            if (isFriendOrCompanion(candidate, player)) continue;

            boolean freshHit = candidate.hurtTime > 0 && candidate.hurtTime >= Math.max(1, candidate.hurtDuration - 2);
            boolean attributed = (candidate.getLastHurtByMob() == companion || candidate.getLastHurtByPlayer() == companion)
                && candidate.hurtTime > 0;
            boolean inferredMeleeHit = false;
            if (!attributed && companionAttackGraceTicks > 0 && freshHit
                    && BotEquipmentHelper.getEyeToHitboxDistance(companion, candidate) <= 3.6) {
                Vec3 rayEnd = compEye.add(compLook.scale(4.0));
                Optional<Vec3> boxHit = candidate.getBoundingBox().inflate(0.3).clip(compEye, rayEnd);
                if (boxHit.isPresent()) {
                    HitResult blockHit = level.clip(new ClipContext(
                        compEye, boxHit.get(), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, companion
                    ));
                    inferredMeleeHit = blockHit.getType() == HitResult.Type.MISS;
                }
            }
            if (attributed || inferredMeleeHit) {
                companionProvokedTargets.put(candidate.getUUID(), 600);
                registerHostileTarget(client, player, candidate);
            }
        }

        // 2. رصد إصابة القائد للموب بسهم أو مقذوف متحرك فعلياً (استبعاد الأسهم المغروسة بالأرض)
        AABB projBox = companion.getBoundingBox().inflate(24.0);
        for (Projectile proj : level.getEntitiesOfClass(Projectile.class, projBox)) {
            if (!proj.isAlive() || proj.isRemoved() || proj.onGround() || proj.getOwner() != companion) continue;
            if (proj.getDeltaMovement().lengthSqr() < 0.01) continue;
            AABB impactBox = proj.getBoundingBox().inflate(0.75);
            for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, impactBox)) {
                if (candidate == player || candidate == companion || !candidate.isAlive() || candidate.isRemoved()) continue;
                if (isFriendOrCompanion(candidate, player)) continue;
                boolean freshHit = candidate.hurtTime > 0 && candidate.hurtTime >= Math.max(1, candidate.hurtDuration - 2);
                if (freshHit && impactBox.intersects(candidate.getBoundingBox())) {
                    companionProvokedTargets.put(candidate.getUUID(), 600);
                    registerHostileTarget(client, player, candidate);
                }
            }
        }
    }

    /**
     * تحديث سجل الأعداء والتهديدات المحيطة
     */
    private void updateHostilityRegistry(Minecraft client, LocalPlayer player, ClientLevel level, Player companion) {
        // إنقاص مدة صلاحية الأعداء المسجلين والموبات المستثارة من القائد
        decrementExpiringUuids(hostileTargets);
        decrementExpiringUuids(companionProvokedTargets);

        boolean compDamaged = false;
        if (companion != null) {
            float compHealth = companion.getHealth();
            compDamaged = companion.hurtTime > 0 || (lastCompanionHealth > 0 && compHealth < lastCompanionHealth - 0.01f);
            boolean compSwingFresh = companion.swinging && companion.swingTime <= 2 && !companion.isUsingItem();
            boolean compAttacking = companion.swinging || companion.swingTime > 0 || companion.attackAnim > 0;
            if (compSwingFresh) {
                companionAttackGraceTicks = 4;
            }

            detectCompanionDirectAttacks(client, player, level, companion);

            if (compDamaged || compAttacking) {
                scanAndEnlistEnemies(client, player, level, companion, companion.position());
            }
            LivingEntity compHurtBy = companion.getLastHurtByMob();
            if (compDamaged && compHurtBy != null && !isFriendOrCompanion(compHurtBy, player)
                && (isHostileMob(compHurtBy, player, companion) || compHurtBy instanceof Player)) {
                registerHostileTarget(client, player, compHurtBy);
            }
            lastCompanionHealth = compHealth;
        }

        float botHealth = player.getHealth();
        boolean botDamaged = player.hurtTime > 0 || (lastPlayerHealth > 0 && botHealth < lastPlayerHealth - 0.01f);
        if (botDamaged) {
            scanAndEnlistEnemies(client, player, level, companion, player.position());
            LivingEntity botHurtBy = player.getLastHurtByMob();
            if (botHurtBy != null && !isFriendOrCompanion(botHurtBy, player)
                && (isHostileMob(botHurtBy, player, companion) || botHurtBy instanceof Player)) {
                registerHostileTarget(client, player, botHurtBy);
            }
        }
        lastPlayerHealth = botHealth;

        // مقذوفات قادمة (تشمل مدى 16 بلوكة ونحو البوت أو القائد)
        Projectile incoming = findIncomingProjectile(level, player, companion);
        if (incoming != null) {
            arrowDefenseCooldown = 12;
            if (incoming.getOwner() instanceof LivingEntity shooter && !isFriendOrCompanion(shooter, player)
                && (isHostileMob(shooter, player, companion) || shooter instanceof Player)) {
                registerHostileTarget(client, player, shooter);
            }
        }

        // رصد اللاعبين غير الأصدقاء فقط عند العدوان الفعلي (ضرب مباشر أو تصويب بقوس/نشاب)
        scanAggressingPlayers(client, player, level, companion, botDamaged, compDamaged);

        // مسح مستمر للمحيط كل 10 تكات (نصف ثانية) لرصد أي وحش عدائي يقترب دون تأخير
        if (enemyScanCooldown <= 0) {
            scanAndEnlistEnemies(client, player, level, companion, player.position());
            if (companion != null) {
                scanAndEnlistEnemies(client, player, level, companion, companion.position());
            }
            enemyScanCooldown = 10;
        }
    }

    private void scanAggressingPlayers(Minecraft client, LocalPlayer player, ClientLevel level, Player companion, boolean botDamaged, boolean compDamaged) {
        AABB box = player.getBoundingBox().inflate(16.0);
        List<Player> nearbyPlayers = level.getEntitiesOfClass(Player.class, box,
            p -> p != player && p != companion && p.isAlive() && !p.isRemoved() && !isFriendOrCompanion(p, player));

        for (Player other : nearbyPlayers) {
            if (other.isSpectator() || other.isCreative()) continue;
            Vec3 otherEye = other.getEyePosition();
            Vec3 otherLook = other.getViewVector(1.0f);

            double distToBot = other.distanceTo(player);
            Vec3 toBot = player.getEyePosition().subtract(otherEye);
            double dotToBot = toBot.lengthSqr() > 0.001 ? otherLook.dot(toBot.normalize()) : 0.0;

            double distToComp = companion != null ? other.distanceTo(companion) : Double.MAX_VALUE;
            double dotToComp = 0.0;
            if (companion != null) {
                Vec3 toComp = companion.getEyePosition().subtract(otherEye);
                dotToComp = toComp.lengthSqr() > 0.001 ? otherLook.dot(toComp.normalize()) : 0.0;
            }

            boolean swingingAtUs = (other.swinging || other.attackAnim > 0) && (
                (distToBot <= 4.3 && dotToBot >= 0.55)
                || (companion != null && distToComp <= 4.3 && dotToComp >= 0.55)
                || (botDamaged && distToBot <= 5.0 && dotToBot >= 0.25)
                || (compDamaged && companion != null && distToComp <= 5.0 && dotToComp >= 0.25)
            );

            ItemStack activeStack = other.isUsingItem() ? other.getUseItem() : ItemStack.EMPTY;
            boolean aimingRangedWeapon = !activeStack.isEmpty()
                && (activeStack.is(Items.BOW) || activeStack.is(Items.CROSSBOW))
                && ((dotToBot >= 0.88 && distToBot <= 16.0 && other.hasLineOfSight(player))
                    || (companion != null && dotToComp >= 0.88 && distToComp <= 16.0 && other.hasLineOfSight(companion)));

            if (swingingAtUs || aimingRangedWeapon) {
                registerHostileTarget(client, player, other);
            }
        }
    }

    private void scanAndEnlistEnemies(Minecraft client, LocalPlayer player, ClientLevel level, Player companion, Vec3 center) {
        AABB box = new AABB(center.x - 16.0, center.y - 5.0, center.z - 16.0, center.x + 16.0, center.y + 5.0, center.z + 16.0);
        List<LivingEntity> list = level.getEntitiesOfClass(LivingEntity.class, box,
            e -> e != player && e != companion && e.isAlive() && !e.isRemoved() && !isFriendOrCompanion(e, player)
                && isHostileMob(e, player, companion) && !isBlockedByFollowBan(e));
        for (LivingEntity hostile : list) {
            registerHostileTarget(client, player, hostile);
        }
    }

    public void registerHostileTarget(Minecraft client, LocalPlayer player, LivingEntity entity) {
        if (entity == null || entity == player || entity.getId() == player.getId() || isFriendOrCompanion(entity, player)) return;
        if (!entity.isAlive() || entity.isRemoved()) return;
        if (isBlockedByFollowBan(entity)) return;
        UUID uuid = entity.getUUID();
        hostileTargets.put(uuid, 600); // 30 ثانية
    }

    private List<LivingEntity> getNearbyHostiles(ClientLevel level, LocalPlayer player, Player companion, double radius) {
        AABB box = player.getBoundingBox().inflate(radius);
        return level.getEntitiesOfClass(LivingEntity.class, box,
            e -> e != player && e.isAlive() && !e.isRemoved() && !isFriendOrCompanion(e, player)
                && isHostileMob(e, player, companion) && !isBlockedByFollowBan(e));
    }

    private Projectile findIncomingProjectile(ClientLevel level, LocalPlayer player, Player companion) {
        AABB box = player.getBoundingBox().inflate(16.0);
        List<Projectile> list = level.getEntitiesOfClass(Projectile.class, box, p -> p.isAlive() && !p.isRemoved() && !p.onGround());
        for (Projectile proj : list) {
            Vec3 motion = proj.getDeltaMovement();
            if (motion.lengthSqr() < 0.04) continue;
            Vec3 normMotion = motion.normalize();

            Vec3 toBot = player.getEyePosition().subtract(proj.position());
            if (toBot.lengthSqr() <= 256.0 && normMotion.dot(toBot.normalize()) > 0.65) {
                return proj;
            }
            if (companion != null) {
                Vec3 toComp = companion.getEyePosition().subtract(proj.position());
                if (toComp.lengthSqr() <= 256.0 && normMotion.dot(toComp.normalize()) > 0.65) {
                    return proj;
                }
            }
        }
        return null;
    }

    private void checkEnemyDefeated(LivingEntity enemy) {
        if (!enemy.isAlive() || enemy.isRemoved() || enemy.isDeadOrDying() || enemy.getHealth() <= 0) {
            hostileTargets.remove(enemy.getUUID());
            unreachableTargets.remove(enemy.getUUID());
            companionProvokedTargets.remove(enemy.getUUID());
            if (lockedTargetUUID != null && lockedTargetUUID.equals(enemy.getUUID())) {
                lockedTargetUUID = null;
                targetLockTicks = 0;
            }
            weaponChoiceLockTicks = 0;
        }
    }

    public void resetCombat(Minecraft client) {
        hostileTargets.clear();
        unreachableTargets.clear();
        companionProvokedTargets.clear();
        companionAttackGraceTicks = 0;
        rangedHelper.reset(client);
        combatNavigator.reset(client);
        lockedTargetUUID = null;
        targetLockTicks = 0;
        armorCheckCooldown = 0;
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
        return BotEquipmentHelper.ensureBestSwordInMainHand(player, gameMode, false, 0);
    }

    public boolean ensureBestAxeInMainHand(LocalPlayer player, MultiPlayerGameMode gameMode) {
        return BotEquipmentHelper.ensureBestAxeInMainHand(player, gameMode, false, 0);
    }

    public void ensureShieldInOffhand(LocalPlayer player, MultiPlayerGameMode gameMode) {
        BotEquipmentHelper.ensureInOffhand(player, gameMode, stack -> stack.is(Items.SHIELD));
    }

    public void checkShieldDisabledAndSwap(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        if (shieldSwapCooldown > 0) return;
        ItemStack offhand = player.getOffhandItem();
        boolean shieldDisabled = offhand.is(Items.SHIELD) && player.getCooldowns().isOnCooldown(offhand);
        boolean lowDurability = BotEquipmentHelper.isOffhandShieldLowDurability(player);
        if (!shieldDisabled && !lowDurability) return;

        shieldSwapCooldown = 20;
        BotEquipmentHelper.swapBackupShieldToOffhand(player, gameMode, true, true);
    }

    private void raiseShield(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        if (!BotEquipmentHelper.isShieldReady(player)) {
            releaseShield(client, player, gameMode);
            return;
        }
        client.options.keyUse.setDown(true);
        if (!player.isUsingItem() && gameMode != null) {
            gameMode.useItem(player, InteractionHand.OFF_HAND);
        }
    }

    private void releaseShield(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        if (client != null && client.options != null) {
            client.options.keyUse.setDown(false);
        }
        if (player != null && player.isUsingItem() && player.getUsedItemHand() == InteractionHand.OFF_HAND && gameMode != null) {
            gameMode.releaseUsingItem(player);
        }
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
        if (entity instanceof TamableAnimal tamed && tamed.isTame()) return true;
        return false;
    }

    public static boolean isNeutralMobActivelyHostile(LivingEntity entity, LocalPlayer player, Player companion) {
        if (entity instanceof Mob mob) {
            if (mob.isAggressive()) return true;
            LivingEntity target = mob.getTarget();
            if (target != null && (target == player || (companion != null && target == companion))) {
                return true;
            }
        }
        if (entity instanceof EnderMan enderman && enderman.isCreepy()) {
            return true;
        }
        if (player != null && player.getLastHurtByMob() == entity && player.hurtTime > 0) {
            return true;
        }
        return companion != null && companion.getLastHurtByMob() == entity && companion.hurtTime > 0;
    }

    private boolean isHostileMob(LivingEntity entity, LocalPlayer player, Player companion) {
        if (entity instanceof NeutralMob) {
            return isNeutralMobActivelyHostile(entity, player, companion);
        }
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
        BotEquipmentHelper.smoothLookAt(player, targetX, targetY, targetZ, maxTurnDegrees);
    }

    public static float wrapDegrees(float degrees) {
        return BotEquipmentHelper.wrapDegrees(degrees);
    }
}
