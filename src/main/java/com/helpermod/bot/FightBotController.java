package com.helpermod.bot;

import com.helpermod.bot.food.BotFoodManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.HoeItem;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.helpermod.bot.combat.BotCombatManager;
import com.helpermod.bot.path.BotNavigator;
import com.helpermod.bot.path.FarmPathfinder;
import com.helpermod.bot.friend.BotFriendManager;
import java.util.HashMap;
import java.util.Map;

/**
 * وحدة التحكم في وضع الدفاع التلقائي بالدرع (Defend Mode).
 * تراقب الموبات المعادية واللاعبين المحيطين والمقذوفات في نطاق 5*5،
 * وترفع الدرع في اليد اليسرى بذكاء وحصانة كاملة لمنع أي ضربات من اختراق البوت.
 */
@SuppressWarnings("unused")
public class FightBotController {

    private static final FightBotController INSTANCE = new FightBotController();

    public enum DefenseMode {
        OFF("معطل"),
        CAUTIOUS("نمط الحذر"),
        COMBAT("نمط القتال");

        private final String displayName;

        DefenseMode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private DefenseMode defenseMode = DefenseMode.CAUTIOUS;
    private boolean isDefending = false;
    private int shieldEquipCooldown = 0;
    private int noShieldAlertCooldown = 0;
    private int shieldSwapCooldown = 0;
    private int hungryAlertCooldown = 0;

    // سرعة الالتفاف والصد عند الدفاع: مضاعفة بالكامل (108.0f درجة/نبضة لمواجهة أسرع الحركات والتصدي الفوري)
    public static final float DEFENSE_ROTATION_SPEED = 108.0f;

    // حالة التراجع الذكي وصنع مسافة 5 بلوكات وتفادي الجدران
    private boolean isRetreating = false;
    private int threatLockTicks = 0;
    private int lastThreatEntityId = -1;

    // حالة الجري الطارئ عند اقتراب الخصم لمقدار بلوكة واحدة (الهروب لـ 5 بلوكات في أطول مسار)
    private boolean isEmergencyRunning = false;
    private Vec3 emergencyRunStartPos = null;
    private Vec3 emergencyRunDir = null;
    private int emergencyRunTicks = 0;

    // تتبع حالة تناول الطعام (في وضع الدفاع فقط، خارج الزراعة)
    private boolean isEatingDefense = false;
    private int eatingDefenseTicks = 0;
    private int lastFoodLevelDefense = -1;
    private static final int DEFENSE_FOOD_SLOT = 8; // الزر 9 في شريط الأدوات

    // تمييز الهدف والتهديد الحالي
    private Vec3 currentThreatPos = null;

    // تكتيك قتال الكريبر (Hit & Run 10 Blocks with Sprint-Jump)
    private enum CreeperTacticState {
        NONE,
        APPROACH_AND_STRIKE,
        FLEE_SPRINT_JUMP,
        ASSESS
    }
    private CreeperTacticState creeperCombatState = CreeperTacticState.NONE;
    private Vec3 creeperCombatStartPos = null;
    private Vec3 creeperCombatEscapeDir = null;
    private int creeperCombatFleeTicks = 0;
    private int creeperCombatAssessTicks = 0;
    private int fightJumpCooldown = 0;

    // ملاحة A* الآمنة للقتال وضبط الضربة الحرجة وتجنب الأهداف المسدودة
    private final BotNavigator fightNavigator = new BotNavigator();
    private final Map<Integer, Integer> unreachableThreatCooldowns = new HashMap<>();
    private boolean isJumpingForCrit = false;
    private int critAirTicks = 0;
    private boolean lastThreatWasHostileAggression = false;

    private FightBotController() {}

    public static FightBotController getInstance() {
        return INSTANCE;
    }

    public DefenseMode getDefenseMode() {
        return defenseMode;
    }

    public boolean isDefenseMode() {
        return defenseMode != DefenseMode.OFF;
    }

    public boolean isDefending() {
        return isDefenseMode() && isDefending;
    }

    public boolean isBusyWithDefense() {
        return isDefenseMode() && (isDefending || isEmergencyRunning || isRetreating);
    }

    public boolean isRetreating() {
        return isRetreating;
    }

    /**
     * تطبيق نمط الدفاع المحفوظ عند تشغيل العميل دون إعادة كتابة ملف الإعدادات.
     */
    public void applyPersistedDefenseMode(DefenseMode mode) {
        this.defenseMode = mode != null ? mode : DefenseMode.CAUTIOUS;
    }

    public void setDefenseMode(Minecraft client, DefenseMode mode) {
        this.defenseMode = mode;
        BotSettingsManager.getInstance().setDefenseMode(mode);
        if (mode == DefenseMode.OFF) {
            disableDefenseCompletely(client);
            sendChatMessage(client, "§c[Bot] تم إيقاف وضع الدفاع بالكامل في المطلق (/bot defend off).");
        } else if (mode == DefenseMode.CAUTIOUS) {
            sendChatMessage(client, "§e[Bot] تم تفعيل وضع الدفاع: §6نمط الحذر (Cautious)§e! يراقب المحيط ويصد بالدرع ويتراجع عند الخطر.");
            if (client.player != null && client.gameMode != null && !FarmBotController.getInstance().isFarming()) {
                ensureShieldInOffhand(client, client.player, client.gameMode);
            }
        } else if (mode == DefenseMode.COMBAT) {
            sendChatMessage(client, "§c[Bot] تم تفعيل وضع الدفاع: §4نمط القتال (Combat)§c! يحمي نفسه ويهاجم ويقتل الوحوش المحيطة (ويهرب من الكريبر).");
            if (client.player != null && client.gameMode != null && !FarmBotController.getInstance().isFarming()) {
                ensureShieldInOffhand(client, client.player, client.gameMode);
            }
        }
    }

    /**
     * إلغاء وضع الدفاع في المطلق:
     * يوقف وضع الدفاع تماماً، يحرر الدرع، يلغي التراجع والهروب والأكل الدفاعي،
     * ويصفر جميع التهديدات ومؤقتات التركيز وتحكم الأزرار.
     */
    public void disableDefenseCompletely(Minecraft client) {
        this.defenseMode = DefenseMode.OFF;
        stopDefending(client);
        hungryAlertCooldown = 0;
        if (client != null) {
            stopEatingDefense(client, client.player, client.gameMode);
            releaseRetreatMovement(client);
            releaseEmergencyRun(client);
            if (client.options != null) {
                client.options.keyUse.setDown(false);
            }
            if (client.player != null && client.gameMode != null && client.player.isUsingItem()) {
                client.gameMode.releaseUsingItem(client.player);
                client.player.stopUsingItem();
            }
        }
        isDefending = false;
        isRetreating = false;
        isEmergencyRunning = false;
        emergencyRunStartPos = null;
        emergencyRunDir = null;
        emergencyRunTicks = 0;
        threatLockTicks = 0;
        lastThreatEntityId = -1;
        currentThreatPos = null;
        noShieldAlertCooldown = 0;
        shieldSwapCooldown = 0;
        creeperCombatState = CreeperTacticState.NONE;
        creeperCombatStartPos = null;
        creeperCombatEscapeDir = null;
        creeperCombatFleeTicks = 0;
        creeperCombatAssessTicks = 0;
        fightNavigator.reset(client);
        unreachableThreatCooldowns.clear();
        isJumpingForCrit = false;
        critAirTicks = 0;
        lastThreatWasHostileAggression = false;
    }

    public void setDefenseMode(Minecraft client, boolean enabled) {
        setDefenseMode(client, enabled ? DefenseMode.CAUTIOUS : DefenseMode.OFF);
    }

    public void toggleDefenseMode(Minecraft client) {
        if (defenseMode == DefenseMode.OFF) {
            setDefenseMode(client, DefenseMode.CAUTIOUS);
        } else if (defenseMode == DefenseMode.CAUTIOUS) {
            setDefenseMode(client, DefenseMode.COMBAT);
        } else {
            setDefenseMode(client, DefenseMode.OFF);
        }
    }

    public Vec3 getCurrentThreatPos() {
        return currentThreatPos;
    }

    public void startDefending(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode, ThreatInfo threat) {
        if (threat != null) {
            currentThreatPos = threat.pos;
        }
        ensureShieldInOffhand(client, player, gameMode);
        if (threat != null) {
            lookAt(player, threat.pos.x, threat.pos.y, threat.pos.z);
        } else if (currentThreatPos != null) {
            lookAt(player, currentThreatPos.x, currentThreatPos.y, currentThreatPos.z);
        }

        if (BotEquipmentHelper.isShieldReady(player)) {
            client.options.keyUse.setDown(true);
            if (!player.isUsingItem() || !player.getUseItem().is(Items.SHIELD)) {
                gameMode.useItem(player, InteractionHand.OFF_HAND);
                player.startUsingItem(InteractionHand.OFF_HAND);
            }
            isDefending = true;
        } else {
            client.options.keyUse.setDown(false);
            isDefending = false;
        }
    }

    public void stopDefending(Minecraft client) {
        if (client != null && client.options != null) {
            client.options.keyUse.setDown(false);
        }
        if (client != null && client.gameMode != null && client.player != null) {
            if (client.player.isUsingItem()) {
                client.gameMode.releaseUsingItem(client.player);
            }
            client.player.stopUsingItem();
        }
        releaseRetreatMovement(client);
        releaseEmergencyRun(client);
        fightNavigator.reset(client);
        isDefending = false;
        isRetreating = false;
        isEmergencyRunning = false;
        emergencyRunStartPos = null;
        emergencyRunDir = null;
        emergencyRunTicks = 0;
        threatLockTicks = 0;
        lastThreatEntityId = -1;
        lastThreatWasHostileAggression = false;
        currentThreatPos = null;
        creeperCombatState = CreeperTacticState.NONE;
        creeperCombatStartPos = null;
        creeperCombatEscapeDir = null;
        creeperCombatFleeTicks = 0;
        creeperCombatAssessTicks = 0;
        isJumpingForCrit = false;
        critAirTicks = 0;

        // إذا كان البوت يمارس الزراعة، إعادة الفأس الزراعي فوراً إلى اليد اليسرى (تبديل الدرع بالفأس)
        if (client != null && client.player != null && client.gameMode != null && FarmBotController.getInstance().isFarming()) {
            ensureHoeInOffhand(client, client.player, client.gameMode);
        }
    }

    public void onTick(Minecraft client) {
        if (defenseMode == DefenseMode.OFF) return;

        // إذا كان البوت في وضع الزراعة، فإن FarmBotController يتولى بالكامل إدارة الدفاع والهروب والدرع
        if (FarmBotController.getInstance().isFarming()) {
            return;
        }

        // إذا كان البوت في وضع الاتباع، يتولى BotFollowController بالكامل إدارة القتال والمرافقة
        if (BotFollowController.getInstance().isFollowing()) {
            return;
        }

        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        MultiPlayerGameMode gameMode = client.gameMode;

        if (player == null || level == null || gameMode == null || player.isDeadOrDying()) {
            if (isDefending) stopDefending(client);
            stopEatingDefense(client, player, gameMode);
            return;
        }

        if (noShieldAlertCooldown > 0) noShieldAlertCooldown--;
        if (shieldSwapCooldown > 0) shieldSwapCooldown--;
        if (hungryAlertCooldown > 0) hungryAlertCooldown--;
        if (fightJumpCooldown > 0) fightJumpCooldown--;
        fightNavigator.tickCooldowns();
        unreachableThreatCooldowns.entrySet().removeIf(entry -> {
            int rem = entry.getValue() - 1;
            if (rem <= 0) return true;
            entry.setValue(rem);
            return false;
        });

        boolean inWater = BotNavigator.isEntityInWater(level, player);
        if (!player.onGround() && !inWater) {
            client.options.keyJump.setDown(false);
        }

        // حراسة التنفس الطارئة في الماء (الطفو أو الهروب من السقف قبل الغرق مع السماح بالغوص الحر)
        if (fightNavigator.handleUrgentWaterBreathing(client, player, level, gameMode)) {
            isJumpingForCrit = false;
            critAirTicks = 0;
            isDefending = false;
            return;
        }

        // فحص البيئة المحيطة لكشف أي تهديد (موب معادٍ، لاعب معتدٍ فعلياً، أو مقذوف)
        ThreatInfo threat = detectThreat(player, level);

        // فحص هل تعطل الدرع أو اقترب من الانكسار ومحاولة الاستبدال الفوري
        checkShieldDisabledAndSwap(client, player, gameMode);

        if (threat != null) {
            currentThreatPos = threat.pos;
            double distToThreat = player.position().distanceTo(threat.pos);

            // 1. فحص الكريبر: تكتيك الضرب والهروب 10 بلوكات بالقفز السريع الآمن في نمط القتال
            boolean isCreeper = threat.entity instanceof Creeper
                || (threat.entity instanceof Mob mob && mob.getType().getDescriptionId().contains("creeper"));

            if (isCreeper && defenseMode == DefenseMode.COMBAT && threat.entity instanceof Creeper creeper) {
                if (!creeper.isAlive() || creeper.isRemoved() || creeper.isDeadOrDying() || creeper.getHealth() <= 0) {
                    creeperCombatState = CreeperTacticState.NONE;
                    lastThreatEntityId = -1;
                    lastThreatWasHostileAggression = false;
                    threatLockTicks = 0;
                    stopDefending(client);
                    return;
                }

                if (creeperCombatState == CreeperTacticState.NONE) {
                    creeperCombatState = CreeperTacticState.APPROACH_AND_STRIKE;
                }

                double distToCreeper = player.distanceTo(creeper);
                double eyeDistToCreeper = BotEquipmentHelper.getEyeToHitboxDistance(player, creeper);
                boolean inCreeperReach = eyeDistToCreeper <= 2.95 && distToCreeper <= 3.6;

                // أ) الاقتراب بمسار A* الآمن والقفز والضرب الحرج عند الهبوط
                if (creeperCombatState == CreeperTacticState.APPROACH_AND_STRIKE) {
                    if (!BotCombatManager.getInstance().ensureBestAxeInMainHand(player, gameMode)) {
                        ensureBestAxeInMainHand(player, gameMode);
                    }
                    client.options.keyUse.setDown(false);
                    if (player.isUsingItem() && player.getUseItem().is(Items.SHIELD)) {
                        gameMode.releaseUsingItem(player);
                        player.stopUsingItem();
                    }

                    if (inCreeperReach) {
                        fightNavigator.clearPathOnly();
                        lookAt(player, creeper.getX(), creeper.getEyeY() - 0.15, creeper.getZ());
                        float charge = player.getAttackStrengthScale(0.0f);

                        if (charge < 0.85f && !isJumpingForCrit) {
                            client.options.keyUp.setDown(false);
                            client.options.keyDown.setDown(false);
                            BotNavigator.releaseJumpOrMaintainWaterBuoyancy(client, player, level, creeper);
                            return;
                        }

                        boolean strikeNow = false;
                        if (BotNavigator.isEntityInWater(level, player) || player.onClimbable() || !BotNavigator.isCeilingClear(level, player.blockPosition())) {
                            strikeNow = true;
                            isJumpingForCrit = false;
                            critAirTicks = 0;
                        } else if (player.onGround() && !isJumpingForCrit && fightJumpCooldown <= 0) {
                            isJumpingForCrit = true;
                            critAirTicks = 0;
                            fightJumpCooldown = 12;
                            client.options.keyJump.setDown(true);
                            client.options.keyUp.setDown(distToCreeper > 1.7);
                        } else if (isJumpingForCrit) {
                            client.options.keyJump.setDown(false);
                            critAirTicks++;
                            if (player.getDeltaMovement().y < -0.01 || critAirTicks > 7) {
                                strikeNow = true;
                            }
                        } else {
                            strikeNow = true;
                        }

                        if (strikeNow) {
                            lookAt(player, creeper.getX(), creeper.getEyeY() - 0.15, creeper.getZ());
                            gameMode.attack(player, creeper);
                            player.swing(InteractionHand.MAIN_HAND);
                            BotNavigator.releaseJumpOrMaintainWaterBuoyancy(client, player, level, creeper);
                            client.options.keyUp.setDown(false);
                            isJumpingForCrit = false;
                            critAirTicks = 0;

                            if (!creeper.isAlive() || creeper.isRemoved() || creeper.isDeadOrDying() || creeper.getHealth() <= 0) {
                                creeperCombatState = CreeperTacticState.NONE;
                                lastThreatEntityId = -1;
                                lastThreatWasHostileAggression = false;
                                threatLockTicks = 0;
                                stopDefending(client);
                                return;
                            }

                            // فور الضربة: الهروب السريع لمسافة 10 بلوكات في الجهة المعاكسة مع تفادي الجدران والمخاطر
                            creeperCombatState = CreeperTacticState.FLEE_SPRINT_JUMP;
                            creeperCombatStartPos = player.position();
                            creeperCombatEscapeDir = BotFollowController.calculateCreeperEscapeDirection(level, player, creeper.position());
                            creeperCombatFleeTicks = 0;
                        }
                    } else {
                        isJumpingForCrit = false;
                        critAirTicks = 0;
                        BotNavigator.NavResult navRes = fightNavigator.navigateTo(
                            client, player, level, gameMode, creeper.position(), true, 2.3, false, DEFENSE_ROTATION_SPEED, true, 2500
                        );
                        if (navRes == BotNavigator.NavResult.BLOCKED_UNREACHABLE) {
                            unreachableThreatCooldowns.put(creeper.getId(), 40);
                            creeperCombatState = CreeperTacticState.NONE;
                            lastThreatEntityId = -1;
                            lastThreatWasHostileAggression = false;
                            threatLockTicks = 0;
                            stopDefending(client);
                        }
                    }
                    return;
                }

                // ب) الركض والقفز بأقصى سرعة لمسافة 10 بلوكات مع حراسة ضد الحفر واللافا
                if (creeperCombatState == CreeperTacticState.FLEE_SPRINT_JUMP) {
                    if (creeperCombatStartPos == null || creeperCombatEscapeDir == null
                        || BotNavigator.isHazardOrDropAhead(level, player, creeperCombatEscapeDir.x, creeperCombatEscapeDir.z)) {
                        if (creeperCombatStartPos == null) {
                            creeperCombatStartPos = player.position();
                        }
                        creeperCombatEscapeDir = BotFollowController.calculateCreeperEscapeDirection(level, player, creeper.position());
                    }

                    double fledDist = player.position().distanceTo(creeperCombatStartPos);
                    creeperCombatFleeTicks++;

                    client.options.keyUse.setDown(false);
                    if (player.isUsingItem()) {
                        gameMode.releaseUsingItem(player);
                        player.stopUsingItem();
                    }

                    float runYaw = (float) (Math.atan2(creeperCombatEscapeDir.z, creeperCombatEscapeDir.x) * 180.0 / Math.PI) - 90.0f;
                    player.setYRot(runYaw);
                    player.yHeadRot = runYaw;
                    player.yBodyRot = runYaw;
                    player.setXRot(0.0f);

                    boolean stillHazard = BotNavigator.isHazardOrDropAhead(level, player, creeperCombatEscapeDir.x, creeperCombatEscapeDir.z);
                    client.options.keyUp.setDown(!stillHazard);
                    client.options.keyDown.setDown(false);
                    client.options.keyLeft.setDown(false);
                    client.options.keyRight.setDown(false);
                    client.options.keySprint.setDown(!stillHazard);
                    player.setSprinting(!stillHazard);

                    if (!stillHazard && player.onGround() && fightJumpCooldown <= 0
                        && BotNavigator.isSafeToJumpAhead(level, player, creeperCombatEscapeDir.x, creeperCombatEscapeDir.z)
                        && BotNavigator.isCeilingClear(level, player.blockPosition())) {
                        client.options.keyJump.setDown(true);
                        fightJumpCooldown = 8;
                    } else {
                        BotNavigator.releaseJumpOrMaintainWaterBuoyancy(client, player, level, null);
                    }

                    if (fledDist >= 10.0 || distToCreeper >= 12.0 || creeperCombatFleeTicks > 45) {
                        client.options.keyUp.setDown(false);
                        BotNavigator.releaseJumpOrMaintainWaterBuoyancy(client, player, level, creeper);
                        client.options.keySprint.setDown(false);
                        player.setSprinting(false);
                        creeperCombatState = CreeperTacticState.ASSESS;
                        creeperCombatAssessTicks = 0;
                    }
                    return;
                }

                // ج) التقييم والنظر نحو الكريبر
                if (creeperCombatState == CreeperTacticState.ASSESS) {
                    client.options.keyUp.setDown(false);
                    BotNavigator.releaseJumpOrMaintainWaterBuoyancy(client, player, level, creeper);
                    client.options.keySprint.setDown(false);
                    player.setSprinting(false);

                    lookAt(player, creeper.getX(), creeper.getEyeY() - 0.15, creeper.getZ());

                    if (!creeper.isAlive() || creeper.isRemoved() || creeper.isDeadOrDying() || creeper.getHealth() <= 0) {
                        creeperCombatState = CreeperTacticState.NONE;
                        lastThreatEntityId = -1;
                        lastThreatWasHostileAggression = false;
                        threatLockTicks = 0;
                        stopDefending(client);
                        return;
                    }

                    creeperCombatAssessTicks++;
                    float charge = player.getAttackStrengthScale(0.0f);
                    if (charge >= 0.90f || creeperCombatAssessTicks >= 10) {
                        creeperCombatState = CreeperTacticState.APPROACH_AND_STRIKE;
                        creeperCombatAssessTicks = 0;
                    }
                    return;
                }
            } else if (isCreeper) {
                // الكريبر في نمط الحذر: الهروب الفوري في أطول مسار مفتوح حتى الابتعاد تماماً
                if (!isEmergencyRunning || emergencyRunTicks > 30) {
                    isEmergencyRunning = true;
                    emergencyRunStartPos = player.position();
                    emergencyRunTicks = 0;
                    emergencyRunDir = findLongestPathAway(player, level, threat.pos);
                    isRetreating = false;
                    releaseRetreatMovement(client);
                }
                emergencyRunTicks++;
                applyEmergencyRun(client, player, emergencyRunDir);
                if (BotEquipmentHelper.isShieldReady(player)) {
                    client.options.keyUse.setDown(true);
                } else {
                    client.options.keyUse.setDown(false);
                }
                return;
            }

            // 2. نمط القتال (Combat Mode): حماية النفس وقتل الوحش أو المعتدي الفعلي
            if (defenseMode == DefenseMode.COMBAT && threat.hostileAggression() && threat.entity instanceof LivingEntity enemy && !(threat.entity instanceof Projectile)) {
                BotCombatManager.WeaponMode weaponMode = BotCombatManager.getInstance().determineWeaponChoice(player, level, enemy);
                if (weaponMode == BotCombatManager.WeaponMode.SWORD) {
                    if (!ensureBestSwordInMainHand(player, gameMode)) {
                        ensureBestAxeInMainHand(player, gameMode);
                    }
                } else {
                    if (!BotCombatManager.getInstance().ensureBestAxeInMainHand(player, gameMode)) {
                        ensureBestAxeInMainHand(player, gameMode);
                    }
                }
                ensureShieldInOffhand(client, player, gameMode);

                double eyeDistToEnemy = BotEquipmentHelper.getEyeToHitboxDistance(player, enemy);
                boolean inMeleeReach = eyeDistToEnemy <= 2.95 && distToThreat <= 3.6;

                if (inMeleeReach) {
                    fightNavigator.clearPathOnly();
                    lookAt(player, enemy.getX(), enemy.getEyeY() - 0.15, enemy.getZ());
                    float charge = player.getAttackStrengthScale(0.0f);
                    boolean isFullyCharged = (weaponMode == BotCombatManager.WeaponMode.SWORD) ? (charge >= 0.85f) : (charge >= 0.90f);

                    if (isFullyCharged || isJumpingForCrit) {
                        client.options.keyUse.setDown(false); // خفض الدرع للهجوم
                        if (player.isUsingItem() && player.getUseItem().is(Items.SHIELD)) {
                            gameMode.releaseUsingItem(player);
                            player.stopUsingItem();
                        }

                        if (weaponMode == BotCombatManager.WeaponMode.SWORD) {
                            // السيف: هجوم مباشر بدون قفز
                            BotNavigator.releaseJumpOrMaintainWaterBuoyancy(client, player, level, enemy);
                            isJumpingForCrit = false;
                            critAirTicks = 0;

                            lookAt(player, enemy.getX(), enemy.getEyeY() - 0.15, enemy.getZ());
                            gameMode.attack(player, enemy);
                            player.swing(InteractionHand.MAIN_HAND);
                            boolean walkCloser = distToThreat > 1.8 && !BotNavigator.isHazardOrDropAhead(level, player, enemy.getX() - player.getX(), enemy.getZ() - player.getZ());
                            client.options.keyUp.setDown(walkCloser);
                            client.options.keyDown.setDown(false);

                            if (!enemy.isAlive() || enemy.isRemoved() || enemy.isDeadOrDying() || enemy.getHealth() <= 0) {
                                lastThreatEntityId = -1;
                                lastThreatWasHostileAggression = false;
                                threatLockTicks = 0;
                                stopDefending(client);
                            }
                        } else {
                            // الفأس: قفزة Crit حقيقية وانتظار السقوط قبل الضرب
                            boolean strikeNow = false;
                            if (BotNavigator.isEntityInWater(level, player) || player.onClimbable() || !BotNavigator.isCeilingClear(level, player.blockPosition())) {
                                strikeNow = true;
                                isJumpingForCrit = false;
                                critAirTicks = 0;
                            } else if (player.onGround() && !isJumpingForCrit && fightJumpCooldown <= 0) {
                                isJumpingForCrit = true;
                                critAirTicks = 0;
                                fightJumpCooldown = 12;
                                client.options.keyJump.setDown(true);
                                client.options.keyUp.setDown(distToThreat > 1.7);
                            } else if (isJumpingForCrit) {
                                client.options.keyJump.setDown(false);
                                critAirTicks++;
                                if (player.getDeltaMovement().y < -0.01 || critAirTicks > 7) {
                                    strikeNow = true;
                                }
                            } else {
                                strikeNow = true;
                            }

                            if (strikeNow) {
                                lookAt(player, enemy.getX(), enemy.getEyeY() - 0.15, enemy.getZ());
                                gameMode.attack(player, enemy);
                                player.swing(InteractionHand.MAIN_HAND);
                                BotNavigator.releaseJumpOrMaintainWaterBuoyancy(client, player, level, enemy);
                                client.options.keyUp.setDown(false);
                                isJumpingForCrit = false;
                                critAirTicks = 0;

                                if (!enemy.isAlive() || enemy.isRemoved() || enemy.isDeadOrDying() || enemy.getHealth() <= 0) {
                                    lastThreatEntityId = -1;
                                    lastThreatWasHostileAggression = false;
                                    threatLockTicks = 0;
                                    stopDefending(client);
                                }
                            }
                        }
                    } else {
                        BotNavigator.releaseJumpOrMaintainWaterBuoyancy(client, player, level, enemy);
                        isJumpingForCrit = false;
                        critAirTicks = 0;
                        boolean shouldGuard = BotEquipmentHelper.isShieldReady(player)
                            && (player.getHealth() < 8.0f || distToThreat <= 2.4);

                        if (shouldGuard) {
                            client.options.keyUse.setDown(true);
                            if (!player.isUsingItem() || !player.getUseItem().is(Items.SHIELD)) {
                                gameMode.useItem(player, InteractionHand.OFF_HAND);
                                player.startUsingItem(InteractionHand.OFF_HAND);
                            }
                            client.options.keyUp.setDown(false);
                            client.options.keySprint.setDown(false);
                            player.setSprinting(false);
                            boolean safeBackpedal = player.getHealth() < 8.0f
                                && !BotNavigator.isHazardOrDropAhead(level, player, player.getX() - enemy.getX(), player.getZ() - enemy.getZ());
                            client.options.keyDown.setDown(safeBackpedal);
                        } else {
                            client.options.keyUse.setDown(false);
                            if (player.isUsingItem() && player.getUseItem().is(Items.SHIELD)) {
                                gameMode.releaseUsingItem(player);
                                player.stopUsingItem();
                            }
                            client.options.keyDown.setDown(false);
                            boolean safeAdvance = distToThreat > 2.2
                                && !BotNavigator.isHazardOrDropAhead(level, player, enemy.getX() - player.getX(), enemy.getZ() - player.getZ());
                            client.options.keyUp.setDown(safeAdvance);
                        }
                    }
                } else {
                    // خارج المدى: اقتراب ذكي عبر A* (BotNavigator)
                    isJumpingForCrit = false;
                    critAirTicks = 0;
                    client.options.keyUse.setDown(false);
                    if (player.isUsingItem() && player.getUseItem().is(Items.SHIELD)) {
                        gameMode.releaseUsingItem(player);
                        player.stopUsingItem();
                    }

                    BotNavigator.NavResult navRes = fightNavigator.navigateTo(
                        client, player, level, gameMode, enemy.position(), true, 2.3, false, DEFENSE_ROTATION_SPEED, true, 2500
                    );
                    if (navRes == BotNavigator.NavResult.BLOCKED_UNREACHABLE) {
                        unreachableThreatCooldowns.put(enemy.getId(), 40);
                        lastThreatEntityId = -1;
                        lastThreatWasHostileAggression = false;
                        threatLockTicks = 0;
                        stopDefending(client);
                    }
                }
                return;
            }

            // 3. نمط الحذر (Cautious Mode) أو المقذوفات: التصدي بالدرع الجاهز والتراجع الذكي
            isJumpingForCrit = false;
            critAirTicks = 0;
            fightNavigator.clearPathOnly();

            if (distToThreat <= 1.2 && !isEmergencyRunning && threat.hostileAggression()) {
                isEmergencyRunning = true;
                emergencyRunStartPos = player.position();
                emergencyRunTicks = 0;
                emergencyRunDir = findLongestPathAway(player, level, threat.pos);
                isRetreating = false;
                releaseRetreatMovement(client);
            }

            // متابعة الجري الطارئ لمسافة 5 بلوكات ثم إعادة النظر للأولوية بعدها فوراً دون تعقيد
            if (isEmergencyRunning) {
                double distanceFled = emergencyRunStartPos != null ? player.position().distanceTo(emergencyRunStartPos) : 0;
                if (distanceFled >= 5.0 || distToThreat >= 5.0 || emergencyRunTicks > 60) {
                    isEmergencyRunning = false;
                    emergencyRunStartPos = null;
                    emergencyRunDir = null;
                    releaseEmergencyRun(client);
                } else {
                    emergencyRunTicks++;
                    applyEmergencyRun(client, player, emergencyRunDir);
                    return;
                }
            }

            // توجيه الوجه والدرع نحو الخصم فوراً وبسرعة التفاف مضاعفة
            lookAt(player, threat.pos.x, threat.pos.y, threat.pos.z);

            // إدارة الأكل أثناء التهديد: لا يعالج نفسه فوراً أثناء القتال، بل فقط إذا ضمن أن الخطر بعيد (المسافة >= 7.0)
            int food = player.getFoodData().getFoodLevel();
            boolean shouldEat = ((food < 10 || player.getHealth() <= 10.0f) && distToThreat >= 7.0)
                    || (isEatingDefense && food < 14 && distToThreat >= 6.0);
            if (shouldEat) {
                handleEatingDefense(client, player, gameMode, true);
            } else {
                if (isEatingDefense) {
                    stopEatingDefense(client, player, gameMode);
                }
                // رفع الدرع للدفاع فوراً فقط عندما يكون الدرع جاهزاً غير معطل
                if (BotEquipmentHelper.isShieldReady(player)) {
                    client.options.keyUse.setDown(true);
                    if (!player.isUsingItem() || !player.getUseItem().is(Items.SHIELD)) {
                        gameMode.useItem(player, InteractionHand.OFF_HAND);
                        player.startUsingItem(InteractionHand.OFF_HAND);
                    }
                    isDefending = true;
                } else if (player.getOffhandItem().is(Items.SHIELD)) {
                    // الدرع في فترة تبريد الفأس: نحرر مفتاح الاستخدام حتى لا تتباطأ حركة التراجع
                    client.options.keyUse.setDown(false);
                    if (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.OFF_HAND) {
                        gameMode.releaseUsingItem(player);
                        player.stopUsingItem();
                    }
                    isDefending = false;
                } else {
                    client.options.keyUse.setDown(false);
                    isDefending = false;
                    ensureShieldInOffhand(client, player, gameMode);
                    if (noShieldAlertCooldown <= 0) {
                        sendChatMessage(client, "§e[Bot] تنبيه: يوجد خطر يهاجمك ولكن لا يوجد درع (Shield) في يدك اليسرى!");
                        noShieldAlertCooldown = 100;
                    }
                }
            }

            // إدارة مسافة التراجع الذكي وتفادي الاصطدام بالجدران:
            // إذا اقترب الخصم لأقل من بلوكتين (< 2.0)، يبدأ البوت بالتراجع حتى يصنع مسافة 5 بلوكات
            if (distToThreat < 2.0) {
                isRetreating = true;
            } else if (distToThreat >= 5.0) {
                isRetreating = false;
            }

            if (isRetreating) {
                Vec3 retreatDir = calculateSmartRetreatDirection(player, level, threat.pos);
                applyRetreatMovement(client, player, retreatDir.x, retreatDir.z);
            } else {
                releaseRetreatMovement(client);
                BotNavigator.maintainWaterBuoyancy(client, player, level, threat.entity());
            }
        } else {
            // لا تهديد: إيقاف الجري والتراجع وخفض الدرع وتناول الطعام بحرية
            isJumpingForCrit = false;
            critAirTicks = 0;
            fightNavigator.clearPathOnly();
            if (isEmergencyRunning) {
                isEmergencyRunning = false;
                emergencyRunStartPos = null;
                emergencyRunDir = null;
                releaseEmergencyRun(client);
            }
            if (isRetreating) {
                isRetreating = false;
                releaseRetreatMovement(client);
            }
            if (isDefending) {
                stopDefending(client);
            }

            // التأكد من الدرع في اليد اليسرى
            if (shieldEquipCooldown <= 0) {
                ensureShieldInOffhand(client, player, gameMode);
                shieldEquipCooldown = 15;
            } else {
                shieldEquipCooldown--;
            }

            // تناول الطعام بشكل طبيعي عند غياب الخطر
            handleEatingDefense(client, player, gameMode, false);

            // الحفاظ على الطفو المائي عند الوقوف أو الأكل في المياه العميقة
            BotNavigator.maintainWaterBuoyancy(client, player, level, null);
        }
    }

    /**
     * التأكد من وجود الدرع في اليد اليسرى (Offhand) بدلاً من الفأس أو غيره.
     * إذا كان في شريط الأدوات أو الحقيبة، يتم نقله تلقائياً إلى اليد اليسرى.
     */
    public boolean ensureShieldInOffhand(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        return BotEquipmentHelper.ensureInOffhand(player, gameMode, stack -> stack.is(Items.SHIELD));
    }

    public static int getAxeTier(ItemStack stack) {
        return BotEquipmentHelper.getAxeOrSwordTier(stack);
    }

    public boolean ensureBestAxeInMainHand(LocalPlayer player, MultiPlayerGameMode gameMode) {
        return BotEquipmentHelper.ensureBestInMainHand(player, gameMode, BotEquipmentHelper::getAxeOrSwordTier, 1, 0);
    }

    public boolean ensureBestSwordInMainHand(LocalPlayer player, MultiPlayerGameMode gameMode) {
        return BotEquipmentHelper.ensureBestInMainHand(player, gameMode, BotEquipmentHelper::getSwordTier, 1, 0);
    }

    /**
     * التأكد من وجود الفأس الزراعي (Hoe) في اليد اليسرى (Offhand) بدلاً من الدرع أو غيره.
     * إذا لم يكن في اليد اليسرى، يتم البحث عنه في شريط الأدوات أو الحقيبة وتبديله فوراً مع ما في اليد اليسرى.
     */
    public boolean ensureHoeInOffhand(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        return BotEquipmentHelper.ensureInOffhand(player, gameMode, stack -> stack.getItem() instanceof HoeItem);
    }

    public boolean hasShieldInInventory(LocalPlayer player) {
        return BotEquipmentHelper.hasMatchingItem(player, stack -> stack.is(Items.SHIELD), true);
    }

    public boolean hasHoeInInventory(LocalPlayer player) {
        return BotEquipmentHelper.hasMatchingItem(player, stack -> stack.getItem() instanceof HoeItem, true);
    }

    /**
     * كشف التهديدات المحيطة بدقة عالية:
     * - اللاعبين الآخرين: فقط عند العدوان الفعلي (هجوم مباشر باليد/السلاح أو تصويب بقوس/نشاب باتجاه البوت)
     * - الوحوش المعادية المكشوفة والمقذوفات القادمة نحو البوت في مدى 16 بلوكة
     */
    public ThreatInfo detectThreat(LocalPlayer player, ClientLevel level) {
        Vec3 playerEyePos = player.getEyePosition();
        double playerX = player.getX();
        double playerY = player.getY();
        double playerZ = player.getZ();

        // 1. إذا تعرض اللاعب لأي ضربة مؤخراً، نحدد مصدر الضرر ونواجهه فوراً (مع استثناء الأصدقاء)
        if (player.hurtTime > 0 && player.getLastHurtByMob() != null) {
            LivingEntity attacker = player.getLastHurtByMob();
            if (attacker.isAlive() && !attacker.isRemoved()) {
                boolean isAttackerFriend = false;
                if (attacker instanceof Player p) {
                    isAttackerFriend = BotFriendManager.getInstance().isFriend(p)
                                    || BotFollowController.getInstance().isFollowingPlayer(p);
                }
                if (!isAttackerFriend) {
                    boolean blocked = unreachableThreatCooldowns.containsKey(attacker.getId())
                        && BotEquipmentHelper.getEyeToHitboxDistance(player, attacker) > 2.95;
                    double dx = Math.abs(attacker.getX() - playerX);
                    double dz = Math.abs(attacker.getZ() - playerZ);
                    if (!blocked && dx <= 10.0 && dz <= 10.0) {
                        Vec3 aimPos = new Vec3(attacker.getX(), attacker.getY() + attacker.getEyeHeight() * 0.75, attacker.getZ());
                        lastThreatEntityId = attacker.getId();
                        lastThreatWasHostileAggression = true;
                        threatLockTicks = 40;
                        return new ThreatInfo(attacker, aimPos, 500.0, true);
                    }
                }
            }
        }

        // فحص الكائنات المحيطة ضمن نطاق 10 بلوكات (أو 6-8 للوحوش القريبة)
        AABB scanBox = new AABB(
            playerX - 10.0, playerY - 4.0, playerZ - 10.0,
            playerX + 10.0, playerY + 4.0, playerZ + 10.0
        );

        ThreatInfo highestThreat = null;
        double highestPriority = -1;

        for (Entity entity : level.getEntities(player, scanBox)) {
            if (!entity.isAlive() || entity.isRemoved()) continue;

            if (unreachableThreatCooldowns.containsKey(entity.getId())) {
                if (!(entity instanceof LivingEntity living && BotEquipmentHelper.getEyeToHitboxDistance(player, living) <= 2.95)) {
                    continue;
                }
            }

            double dx = Math.abs(entity.getX() - playerX);
            double dz = Math.abs(entity.getZ() - playerZ);
            double dy = Math.abs(entity.getY() - playerY);
            if (dy > 4.0) continue;

            double distSq = dx * dx + dz * dz;

            // 2. فحص اللاعبين الآخرين: فقط عند العدوان الفعلي (لا هجوم على المارة المسالمين أو حاملي الأدوات العادية)
            if (entity instanceof Player otherPlayer) {
                if (otherPlayer == player || otherPlayer.isSpectator() || otherPlayer.isCreative()) {
                    continue;
                }
                if (BotFriendManager.getInstance().isFriend(otherPlayer)
                    || BotFollowController.getInstance().isFollowingPlayer(otherPlayer)) {
                    continue;
                }

                Vec3 toBot = playerEyePos.subtract(otherPlayer.getEyePosition());
                Vec3 otherLook = otherPlayer.getViewVector(1.0F).normalize();
                double lookDot = toBot.lengthSqr() > 0.001 ? otherLook.dot(toBot.normalize()) : 0.0;

                boolean swingingAtBot = (otherPlayer.swinging || otherPlayer.attackAnim > 0)
                    && distSq <= 20.25 // ضمن 4.5 بلوكات
                    && lookDot >= 0.45;

                ItemStack activeUse = otherPlayer.isUsingItem() ? otherPlayer.getUseItem() : ItemStack.EMPTY;
                boolean aimingRangedWeapon = !activeUse.isEmpty()
                    && (activeUse.is(Items.BOW) || activeUse.is(Items.CROSSBOW))
                    && lookDot >= 0.82
                    && distSq <= 100.0
                    && player.hasLineOfSight(otherPlayer);

                boolean activeHostileLock = (otherPlayer.getId() == lastThreatEntityId
                    && threatLockTicks > 0
                    && lastThreatWasHostileAggression
                    && distSq <= 64.0
                    && player.hasLineOfSight(otherPlayer));

                if (swingingAtBot || aimingRangedWeapon || activeHostileLock) {
                    double priority = 200.0 + (swingingAtBot ? 120.0 : 0.0) + (aimingRangedWeapon ? 90.0 : 0.0) + (lookDot * 40.0) - distSq;
                    if (priority > highestPriority) {
                        highestPriority = priority;
                        Vec3 aimPos = new Vec3(otherPlayer.getX(), otherPlayer.getY() + otherPlayer.getEyeHeight() * 0.75, otherPlayer.getZ());
                        highestThreat = new ThreatInfo(otherPlayer, aimPos, priority, true);
                    }
                }
                continue;
            }

            // 3. فحص الوحوش والموبات المعادية (مع اشتراط خط الرؤية للمسافات > 3 بلوكات لمنع التعلق خلف الجدران)
            if (entity instanceof Mob mob) {
                if (dx > 8.0 || dz > 8.0) continue;
                if (!isHostileOrAggressive(mob, player)) {
                    continue;
                }
                if (distSq > 9.0 && !player.hasLineOfSight(mob)) {
                    continue;
                }

                Vec3 toBot = playerEyePos.subtract(mob.getEyePosition());
                Vec3 mobLook = mob.getViewVector(1.0F).normalize();
                double lookDot = toBot.lengthSqr() > 0.001 ? mobLook.dot(toBot.normalize()) : 0.0;

                boolean mobTargeting = mob.getTarget() == player;
                boolean mobLooking = lookDot >= 0.25;
                boolean mobAttacking = mob.swinging || mob.attackAnim > 0;
                boolean closeHostile = distSq <= 12.25;

                if (mobTargeting || mobLooking || mobAttacking || closeHostile) {
                    double priority = 150.0 + (mobTargeting ? 100.0 : 0.0) + (lookDot * 40.0) - distSq;
                    if (priority > highestPriority) {
                        highestPriority = priority;
                        Vec3 aimPos = new Vec3(mob.getX(), mob.getY() + mob.getEyeHeight() * 0.75, mob.getZ());
                        highestThreat = new ThreatInfo(mob, aimPos, priority, true);
                    }
                }
            }
        }

        // 4. فحص المقذوفات المقتربة بدقة (أسهم، رماح) في نطاق 16 بلوكة
        AABB projBox = new AABB(playerX - 16.0, playerY - 4.0, playerZ - 16.0, playerX + 16.0, playerY + 4.0, playerZ + 16.0);
        for (Projectile proj : level.getEntitiesOfClass(Projectile.class, projBox)) {
            if (!proj.isAlive() || proj.isRemoved() || proj.onGround() || proj.getOwner() == player) continue;
            if (proj.getOwner() instanceof Player ownerPlayer) {
                if (BotFriendManager.getInstance().isFriend(ownerPlayer)
                    || BotFollowController.getInstance().isFollowingPlayer(ownerPlayer)) {
                    continue;
                }
            }
            Vec3 vel = proj.getDeltaMovement();
            if (vel.lengthSqr() < 0.04) continue;
            Vec3 toBot = playerEyePos.subtract(proj.position());
            if (toBot.lengthSqr() <= 256.0 && vel.normalize().dot(toBot.normalize()) > 0.65) {
                return new ThreatInfo(proj, proj.position(), 600.0, true);
            }
        }

        if (highestThreat != null) {
            lastThreatEntityId = highestThreat.entity().getId();
            lastThreatWasHostileAggression = highestThreat.hostileAggression();
            threatLockTicks = 40; // استمرار قفل التركيز لمدة ثانيتين (40 نبضة)
        } else if (threatLockTicks > 0) {
            threatLockTicks--;
            if (threatLockTicks <= 0) {
                lastThreatWasHostileAggression = false;
            }
        }

        return highestThreat;
    }

    private boolean isHostileOrAggressive(Mob mob, LocalPlayer player) {
        if (mob instanceof Monster) return true;
        if (mob.getTarget() == player) return true;
        if (mob.isAggressive()) return true;
        if (mob instanceof Creeper) return true;
        return false;
    }

    /**
     * توجيه نظر ورأس وجسد اللاعب والدرع نحو موقع الخطر لصد الهجوم بسرعة فائقة:
     * سرعة الالتفاف أسرع بـ 3 مرات (54.0 درجة لكل نبضة) لضمان عدم تمكن الخصم من الالتفاف خلف البوت.
     */
    public void lookAt(LocalPlayer player, double targetX, double targetY, double targetZ) {
        double dx = targetX - player.getX();
        double dy = targetY - player.getEyeY();
        double dz = targetZ - player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float targetPitch = (float) (-(Math.atan2(dy, dist) * 180.0 / Math.PI));

        float currentYaw = player.getYRot();
        float diffYaw = wrapDegrees(targetYaw - currentYaw);
        float stepYaw = Math.max(-DEFENSE_ROTATION_SPEED, Math.min(DEFENSE_ROTATION_SPEED, diffYaw));

        float currentPitch = player.getXRot();
        float diffPitch = targetPitch - currentPitch;
        float stepPitch = Math.max(-DEFENSE_ROTATION_SPEED, Math.min(DEFENSE_ROTATION_SPEED, diffPitch));

        float newYaw = currentYaw + stepYaw;
        float newPitch = currentPitch + stepPitch;

        player.setYRot(newYaw);
        player.setXRot(newPitch);
        player.yHeadRot = newYaw;
        player.yBodyRot = newYaw;
    }

    /**
     * التحقق مما إذا كان الدرع تعطل (ضربه فأس) ومحاولة استبداله فوراً بدرع آخر من الحقيبة.
     * ملاحظة: في الفانيلا دروع الكولداون تشترك نفس مدة التعطل لكل الدروع، لكن الاستبدال يضمن أفضل جاهزية
     * متى انتهت فترة التعطل (5 ثوانٍ).
     */
    private void checkShieldDisabledAndSwap(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        if (shieldSwapCooldown > 0) return;
        ItemStack offhand = player.getOffhandItem();
        boolean disabled = offhand.is(Items.SHIELD) && player.getCooldowns().isOnCooldown(offhand);
        boolean lowDurability = BotEquipmentHelper.isOffhandShieldLowDurability(player);
        if (!disabled && !lowDurability) return;

        shieldSwapCooldown = 100; // منع التكرار والسبام لمدة 5 ثوانٍ كاملة
        if (BotEquipmentHelper.swapBackupShieldToOffhand(player, gameMode, false, lowDurability)) {
            sendChatMessage(client, lowDurability
                ? "§e[Bot - Defend] درع اليد اليسرى على وشك الانكسار! تم استبداله بدرع احتياطي."
                : "§e[Bot - Defend] الدرع تعطل بضربة فأس! تم استبداله بدرع احتياطي فوراً.");
        }
    }

    /**
     * تناول الطعام (بطاطا مطبوخة) في وضع الدفاع (خارج الزراعة).
     * بدون تهديد: أكل طبيعي إذا كان الجوع 14 أو أقل، والاستمرار في تناول الحبات حتى الشبع التام (20).
     * مع تهديد نشط: أكل فقط إن كان الجوع حرجياً (أقل من 6).
     */
    /**
     * تناول الطعام المسموح به في وضع الدفاع:
     * - الأصل الأساسي هو شريط الجوع وليس الصحة.
     * - إذا كان شريط الجوع ممتلئاً (20): لا يحاول أكل الطعام العادي، وفقط التفاح الذهبي لعلاج الصحة.
     * - إن كان جائعاً ولم يجد أي طعام مسموح به، لا يأكل ويرسل في الشات "أنا جائع" كل دقيقة.
     */
    private void handleEatingDefense(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode, boolean threatActive) {
        int food = player.getFoodData().getFoodLevel();
        float health = player.getHealth();
        boolean needsFood = player.getFoodData().needsFood();

        // الأصل هو شريط الجوع:
        if (!needsFood) {
            boolean hasGolden = hasAllowedGoldenAppleDF(player);
            if (health >= 20.0f || !hasGolden) {
                stopEatingDefense(client, player, gameMode);
                return;
            }
        }

        if (threatActive) {
            if (food >= 14 || !needsFood) {
                stopEatingDefense(client, player, gameMode);
                return;
            }
        }

        // ضمان وجود أفضل طعام مسموح في شريط الأدوات
        int hotbarSlot = ensureFoodInHotbarDF(player, gameMode);
        if (hotbarSlot == -1) {
            stopEatingDefense(client, player, gameMode);

            if (needsFood && hungryAlertCooldown <= 0) {
                hungryAlertCooldown = 1200; // دقيقة واحدة
                if (player.connection != null) {
                    player.connection.sendChat("أنا جائع!");
                }
                sendChatMessage(client, "§c[Bot] أنا جائع ولا أملك أي طعام مسموح به في الحقيبة!");
            }
            return;
        }

        // تحديد الخانة المحتوية على الطعام
        if (player.getInventory().getSelectedSlot() != hotbarSlot) {
            player.getInventory().setSelectedSlot(hotbarSlot);
            if (player.connection != null) {
                player.connection.send(new ServerboundSetCarriedItemPacket(hotbarSlot));
            }
        }

        // إذا تم ابتلاع حبة بنجاح: تصفير عداد الحبة الحالية
        if (isEatingDefense && lastFoodLevelDefense != -1 && food > lastFoodLevelDefense) {
            eatingDefenseTicks = 0;
        }
        lastFoodLevelDefense = food;

        isEatingDefense = true;
        eatingDefenseTicks++;

        // تحرير استخدام الدرع مؤقتاً لتفعيل أكل الطعام باليد الرئيسية
        if (player.isUsingItem() && player.getUseItem().is(Items.SHIELD)) {
            gameMode.releaseUsingItem(player);
            player.stopUsingItem();
        }

        client.options.keyUse.setDown(true);
        if (!player.isUsingItem()) {
            client.options.keyUse.setDown(false);
            gameMode.useItem(player, InteractionHand.MAIN_HAND);
            player.startUsingItem(InteractionHand.MAIN_HAND);
            client.options.keyUse.setDown(true);
        }

        // مهلة أمان للحبة الواحدة
        if (eatingDefenseTicks > 45) {
            stopEatingDefense(client, player, gameMode);
        }
    }

    private void stopEatingDefense(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        if (!isEatingDefense) return;
        isEatingDefense = false;
        eatingDefenseTicks = 0;
        lastFoodLevelDefense = -1;
        // تحرير العنصر وإيقاف الأكل
        if (player != null && player.isUsingItem()) {
            ItemStack usingItem = player.getUseItem();
            if (!usingItem.is(Items.SHIELD)) {
                if (gameMode != null) gameMode.releaseUsingItem(player);
                player.stopUsingItem();
            }
        }
        // العودة لخانة آمنة حتى يتمكن الدرع من التفعيل فوراً
        if (player != null && player.getInventory().getSelectedSlot() == DEFENSE_FOOD_SLOT) {
            player.getInventory().setSelectedSlot(0);
            if (player.connection != null) {
                player.connection.send(new ServerboundSetCarriedItemPacket(0));
            }
        }
        if (client.options != null && !isDefending) {
            client.options.keyUse.setDown(false);
        }
    }

    private boolean hasAllowedGoldenAppleDF(LocalPlayer player) {
        BotFoodManager foodMgr = BotFoodManager.getInstance();
        for (int i = 0; i < 36; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty() && (s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE))) {
                if (foodMgr.isAllowed(s)) return true;
            }
        }
        return false;
    }

    private int ensureFoodInHotbarDF(LocalPlayer player, MultiPlayerGameMode gameMode) {
        BotFoodManager foodMgr = BotFoodManager.getInstance();
        int bestSlot = -1;
        int bestScore = -1;

        // 1. فحص شريط الأدوات أولاً
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (foodMgr.canEatNow(player, stack)) {
                int score = foodMgr.getFoodScore(stack);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = i;
                }
            }
        }
        if (bestSlot != -1) return bestSlot;

        // 2. نقل أفضل طعام من الحقيبة إلى خانة DEFENSE_FOOD_SLOT (الخانة 8)
        int containerId = player.inventoryMenu.containerId;
        int invBestSlot = -1;
        bestScore = -1;
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (foodMgr.canEatNow(player, stack)) {
                int score = foodMgr.getFoodScore(stack);
                if (score > bestScore) {
                    bestScore = score;
                    invBestSlot = i;
                }
            }
        }

        if (invBestSlot != -1) {
            gameMode.handleContainerInput(containerId, invBestSlot, DEFENSE_FOOD_SLOT, ContainerInput.SWAP, player);
            return DEFENSE_FOOD_SLOT;
        }

        return -1;
    }

    private void sendChatMessage(Minecraft client, String message) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal(message));
        }
    }

    /**
     * حساب مسار التراجع الذكي برسم محيط الغرفة وفحص المسافات للجدران والعوائق:
     * - إذا كان الجدار خلف البوت على بعد 3 بلوكات أو أقل:
     *   يفحص جهات اليسار واليمين والخلف، ويتراجع بالانحراف نحو الجهة التي جدارها أبعد.
     *   (مثال: خلفه 3 بلوكات، يساره 20، يمينه 30 -> يتراجع بظهره نحو اليمين الأكثر اتساعاً).
     * - إذا كان خلفه متسع أكثر من 3 بلوكات: يتراجع للخلف مباشرة بعيداً عن الخصم.
     */
    public Vec3 calculateSmartRetreatDirection(LocalPlayer player, ClientLevel level, Vec3 threatPos) {
        double awayX = player.getX() - threatPos.x;
        double awayZ = player.getZ() - threatPos.z;
        double awayLen = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (awayLen > 0.001) {
            awayX /= awayLen;
            awayZ /= awayLen;
        } else {
            Vec3 look = player.getLookAngle();
            awayX = -look.x;
            awayZ = -look.z;
            double len = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (len > 0.001) {
                awayX /= len;
                awayZ /= len;
            } else {
                awayX = 0;
                awayZ = 1;
            }
        }

        // قياس المسافة للجدار خلف البوت مباشرة
        double distBack = measureDistanceToObstacle(level, player, awayX, awayZ, 35.0);

        // إذا كان هناك متسع كافٍ في الخلف (أكثر من 3 بلوكات): التراجع للخلف مباشرة
        if (distBack > 3.0) {
            return new Vec3(awayX, 0, awayZ);
        }

        // إذا كان الجدار على بعد 3 بلوكات أو أقل: فحص محيط الغرفة (اليمين واليسار والزوايا المائلة)
        // متجه اليمين النسبي للخصم
        double rightX = awayZ;
        double rightZ = -awayX;
        // متجه اليسار النسبي للخصم
        double leftX = -awayZ;
        double leftZ = awayX;

        // قياس المسافة للجدران يميناً ويساراً وللزوايا الخلفية المائلة
        double distRight = measureDistanceToObstacle(level, player, rightX, rightZ, 35.0);
        double distLeft = measureDistanceToObstacle(level, player, leftX, leftZ, 35.0);

        // الزوايا المائلة للخلف (خلف-يمين وخلف-يسار)
        double brX = (awayX + rightX) * 0.7071;
        double brZ = (awayZ + rightZ) * 0.7071;
        double blX = (awayX + leftX) * 0.7071;
        double blZ = (awayZ + leftZ) * 0.7071;

        double distBR = measureDistanceToObstacle(level, player, brX, brZ, 35.0);
        double distBL = measureDistanceToObstacle(level, player, blX, blZ, 35.0);

        double totalRightOpenness = Math.max(distRight, distBR * 1.2);
        double totalLeftOpenness = Math.max(distLeft, distBL * 1.2);

        // الانحياز للجهة التي جدارها أبعد، مع الحفاظ على التراجع للخلف بقدر الإمكان
        if (totalRightOpenness >= totalLeftOpenness) {
            // الجهة اليمنى جدارها أبعد: التراجع للخلف ولكن لليمين أكثر (ليواجه بظهره جدار اليمين)
            double moveX = awayX * 0.35 + rightX * 0.95;
            double moveZ = awayZ * 0.35 + rightZ * 0.95;
            double moveLen = Math.sqrt(moveX * moveX + moveZ * moveZ);
            return new Vec3(moveX / moveLen, 0, moveZ / moveLen);
        } else {
            // الجهة اليسرى جدارها أبعد: التراجع للخلف ولكن لليسار أكثر (ليواجه بظهره جدار اليسار)
            double moveX = awayX * 0.35 + leftX * 0.95;
            double moveZ = awayZ * 0.35 + leftZ * 0.95;
            double moveLen = Math.sqrt(moveX * moveX + moveZ * moveZ);
            return new Vec3(moveX / moveLen, 0, moveZ / moveLen);
        }
    }

    /**
     * قياس المسافة بالأمتار حتى أول جدار حقيقي أو سياج أو خطر/هاوية في اتجاه معين،
     * مع السماح بصعود ونزول التضاريس المتدرجة (درجة واحدة).
     */
    public static double measureDistanceToObstacle(ClientLevel level, LocalPlayer player, double dirX, double dirZ, double maxDist) {
        if (level == null || player == null) return maxDist;
        double px = player.getX();
        double curY = Math.floor(player.getY() + 0.15);
        double pz = player.getZ();

        double step = 0.5;
        for (double d = 0.5; d <= maxDist; d += step) {
            double cx = px + dirX * d;
            double cz = pz + dirZ * d;

            BlockPos feet = BlockPos.containing(cx, curY, cz);
            BlockPos head = feet.above();
            BlockState feetState = level.getBlockState(feet);
            BlockState headState = level.getBlockState(head);

            if (FarmPathfinder.isHazard(feetState) || FarmPathfinder.isHazard(headState)
                || FarmPathfinder.isWaterColumnHazardous(level, feet)
                || FarmPathfinder.isFenceOrWall(feetState) || FarmPathfinder.isFenceOrWall(headState)) {
                return Math.max(0.0, d - 0.5);
            }

            if (!FarmPathfinder.isFeetPassable(level, feet) || !FarmPathfinder.isPassable(level, head)) {
                // فحص إمكانية صعود درجة واحدة بأمان (كتلة صلبة قابلة للوقوف + فراغ فوقها للرأس)
                BlockPos stepUpFeet = feet.above();
                BlockPos stepUpHead = stepUpFeet.above();
                BlockPos prevHeadAbove = BlockPos.containing(px + dirX * (d - step), curY, pz + dirZ * (d - step)).above(2);
                if (FarmPathfinder.isFeetPassable(level, stepUpFeet)
                    && FarmPathfinder.isPassable(level, stepUpHead)
                    && FarmPathfinder.isPassable(level, prevHeadAbove)
                    && !FarmPathfinder.isHazard(level.getBlockState(feet))
                    && !FarmPathfinder.isWaterColumnHazardous(level, feet)
                    && !FarmPathfinder.isFenceOrWall(level.getBlockState(stepUpFeet))) {
                    curY += 1.0;
                    continue;
                }
                return Math.max(0.0, d - 0.5);
            }

            // فحص الأرضية تحت القدمين ومنع السقوط من الحفر الخطرة أو الأعمدة المائية القاتلة (Magma) مع السماح بالسباحة
            BlockPos below = feet.below();
            BlockState belowState = level.getBlockState(below);
            if (FarmPathfinder.isHazard(belowState) || FarmPathfinder.isWaterColumnHazardous(level, below)) {
                return Math.max(0.0, d - 0.5);
            }
            boolean waterHere = FarmPathfinder.isWater(level, feet) || FarmPathfinder.isWater(level, below);
            if (!waterHere && FarmPathfinder.isPassable(level, below)) {
                // قد تكون حفرة بدرجة واحدة: نتحقق من وجود أرضية صلبة وآمنة عند below(2)
                BlockPos below2 = feet.below(2);
                BlockState below2State = level.getBlockState(below2);
                if (FarmPathfinder.isPassable(level, below2) || FarmPathfinder.isHazard(below2State)) {
                    return Math.max(0.0, d - 0.5);
                }
                curY -= 1.0;
            }
        }
        return maxDist;
    }

    /**
     * توجيه أزرار حركة اللاعب للتراجع والمناورة الجانبية ليبقى الوجه والدرع قبال الخصم دائماً
     */
    public void applyRetreatMovement(Minecraft client, LocalPlayer player, double moveDirX, double moveDirZ) {
        if (client == null || client.options == null || player == null) return;
        float moveYaw = (float) (Math.atan2(moveDirZ, moveDirX) * 180.0 / Math.PI) - 90.0f;
        float diff = wrapDegrees(moveYaw - player.getYRot());

        client.options.keyUp.setDown(diff > -67.5f && diff < 67.5f);
        client.options.keyDown.setDown(diff > 112.5f || diff < -112.5f);
        client.options.keyLeft.setDown(diff > -157.5f && diff < -22.5f);
        client.options.keyRight.setDown(diff > 22.5f && diff < 157.5f);

        if (client.level != null && player.horizontalCollision && player.onGround() && fightJumpCooldown <= 0
            && BotNavigator.isCeilingClear(client.level, player.blockPosition())
            && BotNavigator.isSafeToJumpAhead(client.level, player, moveDirX, moveDirZ)) {
            client.options.keyJump.setDown(true);
            fightJumpCooldown = 10;
        } else {
            BotNavigator.releaseJumpOrMaintainWaterBuoyancy(client, player, client.level, null);
        }
    }

    public void releaseRetreatMovement(Minecraft client) {
        if (client != null && client.options != null) {
            client.options.keyUp.setDown(false);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keyJump.setDown(false);
        }
    }

    public static float wrapDegrees(float degrees) {
        return BotEquipmentHelper.wrapDegrees(degrees);
    }

    /**
     * إيجاد أطول مسار سالك بعيداً عن الخصم للجري فيه (دون كثر قواعد):
     * يفحص الزوايا في القوس المواجه للابتعاد عن الخصم ويختار الاتجاه الأكثر اتساعاً وخالياً من العوائق.
     */
    public Vec3 findLongestPathAway(LocalPlayer player, ClientLevel level, Vec3 threatPos) {
        double awayX = player.getX() - threatPos.x;
        double awayZ = player.getZ() - threatPos.z;
        double awayLen = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (awayLen > 0.001) {
            awayX /= awayLen;
            awayZ /= awayLen;
        } else {
            Vec3 look = player.getLookAngle();
            awayX = -look.x;
            awayZ = -look.z;
            double len = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (len > 0.001) {
                awayX /= len;
                awayZ /= len;
            } else {
                awayX = 0;
                awayZ = 1;
            }
        }

        double baseAngle = Math.atan2(awayZ, awayX);
        double maxDist = -1;
        Vec3 bestDir = new Vec3(awayX, 0, awayZ);

        // فحص 13 زاوية في قوس 180 درجة بعيداً عن الخصم (من -90 إلى +90 درجة)
        for (int i = -6; i <= 6; i++) {
            double angle = baseAngle + (i * (Math.PI / 12.0)); // كل 15 درجة
            double dirX = Math.cos(angle);
            double dirZ = Math.sin(angle);
            double dist = measureDistanceToObstacle(level, player, dirX, dirZ, 25.0);
            if (dist > maxDist) {
                maxDist = dist;
                bestDir = new Vec3(dirX, 0, dirZ);
            }
        }

        // إذا كانت كل الاتجاهات مغلقة تماماً (< 2 بلوكات)، فحص بقية الزوايا في محيط 360 درجة
        if (maxDist < 2.0) {
            for (int i = 7; i <= 18; i++) {
                double angle = baseAngle + (i * (Math.PI / 12.0));
                double dirX = Math.cos(angle);
                double dirZ = Math.sin(angle);
                double dist = measureDistanceToObstacle(level, player, dirX, dirZ, 25.0);
                if (dist > maxDist) {
                    maxDist = dist;
                    bestDir = new Vec3(dirX, 0, dirZ);
                }
            }
        }

        return bestDir;
    }

    /**
     * تطبيق الجري السريع (Sprint) في اتجاه الهروب المحدد
     */
    public void applyEmergencyRun(Minecraft client, LocalPlayer player, Vec3 dir) {
        if (client == null || client.options == null || player == null || dir == null) return;

        // إيقاف استخدام الدرع أو الأكل لأن استخدام العنصر يبطئ اللاعب ويمنع الجري السريع
        client.options.keyUse.setDown(false);
        if (player.isUsingItem()) {
            if (client.gameMode != null) client.gameMode.releaseUsingItem(player);
            player.stopUsingItem();
        }

        // توجيه وجه ورأس وجسد اللاعب نحو مسار الجري
        float runYaw = (float) (Math.atan2(dir.z, dir.x) * 180.0 / Math.PI) - 90.0f;
        player.setYRot(runYaw);
        player.yHeadRot = runYaw;
        player.yBodyRot = runYaw;
        player.setXRot(0.0f);

        boolean hazardAhead = client.level != null && BotNavigator.isHazardOrDropAhead(client.level, player, dir.x, dir.z);

        // الضغط على مفتاح التقدم للأمام وتفعيل الجري السريع فقط إن لم يكن هناك خطر مباشر
        client.options.keyUp.setDown(!hazardAhead);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keySprint.setDown(!hazardAhead);
        player.setSprinting(!hazardAhead);

        // القفز التلقائي عند مواجهة عقبة آمنة للقفز فقط
        if (!hazardAhead && client.level != null && player.horizontalCollision && player.onGround() && fightJumpCooldown <= 0
            && BotNavigator.isCeilingClear(client.level, player.blockPosition())
            && BotNavigator.isSafeToJumpAhead(client.level, player, dir.x, dir.z)) {
            client.options.keyJump.setDown(true);
            fightJumpCooldown = 10;
        } else {
            BotNavigator.releaseJumpOrMaintainWaterBuoyancy(client, player, client.level, null);
        }
    }

    public void releaseEmergencyRun(Minecraft client) {
        if (client != null && client.options != null) {
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            client.options.keyJump.setDown(false);
        }
        if (client != null && client.player != null) {
            client.player.setSprinting(false);
        }
    }

    public boolean isEmergencyRunning() {
        return isEmergencyRunning;
    }

    public record ThreatInfo(Entity entity, Vec3 pos, double priority, boolean hostileAggression) {
        public ThreatInfo(Entity entity, Vec3 pos, double priority) {
            this(entity, pos, priority, true);
        }
    }
}
