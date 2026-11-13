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
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.helpermod.bot.path.FarmPathfinder;
import com.helpermod.bot.friend.BotFriendManager;

/**
 * وحدة التحكم في وضع الدفاع التلقائي بالدرع (Defend Mode).
 * تراقب الموبات المعادية واللاعبين المحيطين والمقذوفات في نطاق 5*5،
 * وترفع الدرع في اليد اليسرى بذكاء وحصانة كاملة لمنع أي ضربات من اختراق البوت.
 */
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

    public void setDefenseMode(Minecraft client, DefenseMode mode) {
        this.defenseMode = mode;
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

        if (player.getOffhandItem().is(Items.SHIELD)) {
            client.options.keyUse.setDown(true);
            if (!player.isUsingItem() || !player.getUseItem().is(Items.SHIELD)) {
                gameMode.useItem(player, InteractionHand.OFF_HAND);
                player.startUsingItem(InteractionHand.OFF_HAND);
            }
            isDefending = true;
        }
    }

    public void stopDefending(Minecraft client) {
        if (client.options != null) {
            client.options.keyUse.setDown(false);
        }
        if (client.gameMode != null && client.player != null) {
            if (client.player.isUsingItem()) {
                client.gameMode.releaseUsingItem(client.player);
            }
            client.player.stopUsingItem();
        }
        releaseRetreatMovement(client);
        releaseEmergencyRun(client);
        isDefending = false;
        isRetreating = false;
        isEmergencyRunning = false;
        emergencyRunStartPos = null;
        emergencyRunDir = null;
        emergencyRunTicks = 0;
        threatLockTicks = 0;
        lastThreatEntityId = -1;
        currentThreatPos = null;
        creeperCombatState = CreeperTacticState.NONE;
        creeperCombatStartPos = null;
        creeperCombatEscapeDir = null;
        creeperCombatFleeTicks = 0;
        creeperCombatAssessTicks = 0;

        // إذا كان البوت يمارس الزراعة، إعادة الفأس الزراعي فوراً إلى اليد اليسرى (تبديل الدرع بالفأس)
        if (client.player != null && client.gameMode != null && FarmBotController.getInstance().isFarming()) {
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

        boolean inWater = player.isInWater() || level.getBlockState(player.blockPosition()).is(Blocks.WATER);
        if (!player.onGround() && !inWater) {
            client.options.keyJump.setDown(false);
        }

        // فحص البيئة المحيطة لكشف أي تهديد (موب معادٍ، لاعب ينظر أو يقترب، أو مقذوف)
        ThreatInfo threat = detectThreat(player, level);

        // فحص هل تعطل الدرع (ضربه فأس) ومحاولة الاستبدال الفوري
        checkShieldDisabledAndSwap(client, player, gameMode);

        if (threat != null) {
            currentThreatPos = threat.pos;
            double distToThreat = player.position().distanceTo(threat.pos);

            // 1. فحص الكريبر: تكتيك الضرب والهروب 10 بلوكات بالقفز السريع في نمط القتال
            boolean isCreeper = threat.entity instanceof Creeper
                || (threat.entity instanceof Mob mob && mob.getType().getDescriptionId().contains("creeper"));

            if (isCreeper && defenseMode == DefenseMode.COMBAT && threat.entity instanceof Creeper creeper) {
                // فحص هل مات الكريبر أو انفجر
                if (!creeper.isAlive() || creeper.isRemoved() || creeper.isDeadOrDying() || creeper.getHealth() <= 0) {
                    creeperCombatState = CreeperTacticState.NONE;
                    lastThreatEntityId = -1;
                    threatLockTicks = 0;
                    stopDefending(client);
                    return;
                }

                if (creeperCombatState == CreeperTacticState.NONE) {
                    creeperCombatState = CreeperTacticState.APPROACH_AND_STRIKE;
                }

                double distToCreeper = player.distanceTo(creeper);

                // أ) الاقتراب والقفز والضرب بالفأس
                if (creeperCombatState == CreeperTacticState.APPROACH_AND_STRIKE) {
                    ensureBestAxeInMainHand(player, gameMode);
                    client.options.keyUse.setDown(false);
                    if (player.isUsingItem() && player.getUseItem().is(Items.SHIELD)) {
                        gameMode.releaseUsingItem(player);
                        player.stopUsingItem();
                    }

                    lookAt(player, creeper.getX(), creeper.getEyeY() - 0.15, creeper.getZ());
                    if (distToCreeper <= 3.2) {
                        if (player.onGround() && !isEmergencyRunning && fightJumpCooldown <= 0 && BotFollowController.isCeilingClear(level, player.blockPosition())) {
                            client.options.keyJump.setDown(true);
                            fightJumpCooldown = 12;
                            client.options.keyUp.setDown(distToCreeper > 1.8);
                        } else {
                            client.options.keyJump.setDown(false);
                            lookAt(player, creeper.getX(), creeper.getEyeY() - 0.15, creeper.getZ());
                            gameMode.attack(player, creeper);
                            player.swing(InteractionHand.MAIN_HAND);
                            client.options.keyJump.setDown(false);
                            client.options.keyUp.setDown(false);

                            if (!creeper.isAlive() || creeper.isRemoved() || creeper.isDeadOrDying() || creeper.getHealth() <= 0) {
                                creeperCombatState = CreeperTacticState.NONE;
                                lastThreatEntityId = -1;
                                threatLockTicks = 0;
                                stopDefending(client);
                                return;
                            }

                            // فور الضربة: الهروب السريع لمسافة 10 بلوكات في الجهة المعاكسة مع تفادي الجدران
                            creeperCombatState = CreeperTacticState.FLEE_SPRINT_JUMP;
                            creeperCombatStartPos = player.position();
                            creeperCombatEscapeDir = BotFollowController.calculateCreeperEscapeDirection(level, player, creeper.position());
                            creeperCombatFleeTicks = 0;
                        }
                    } else {
                        client.options.keyDown.setDown(false);
                        client.options.keyUp.setDown(true);
                        client.options.keySprint.setDown(true);
                        player.setSprinting(true);
                        if (fightJumpCooldown <= 0 && BotFollowController.isObstacleDirectlyAhead(player, level, creeper.getX(), creeper.getZ()) && player.onGround() && BotFollowController.isCeilingClear(level, player.blockPosition())) {
                            client.options.keyJump.setDown(true);
                            fightJumpCooldown = 10;
                        } else {
                            client.options.keyJump.setDown(false);
                        }
                    }
                    return;
                }

                // ب) الركض والقفز بأقصى سرعة لمسافة 10 بلوكات في الجهة المعاكسة
                if (creeperCombatState == CreeperTacticState.FLEE_SPRINT_JUMP) {
                    if (creeperCombatStartPos == null || creeperCombatEscapeDir == null) {
                        creeperCombatStartPos = player.position();
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

                    client.options.keyUp.setDown(true);
                    client.options.keyDown.setDown(false);
                    client.options.keyLeft.setDown(false);
                    client.options.keyRight.setDown(false);
                    client.options.keySprint.setDown(true);
                    player.setSprinting(true);

                    if (player.onGround() && fightJumpCooldown <= 0 && BotFollowController.isCeilingClear(level, player.blockPosition())) {
                        client.options.keyJump.setDown(true);
                        fightJumpCooldown = 8;
                    } else {
                        client.options.keyJump.setDown(false);
                    }

                    if (fledDist >= 10.0 || distToCreeper >= 12.0 || creeperCombatFleeTicks > 45) {
                        client.options.keyUp.setDown(false);
                        client.options.keyJump.setDown(false);
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
                    client.options.keyJump.setDown(false);
                    client.options.keySprint.setDown(false);
                    player.setSprinting(false);

                    lookAt(player, creeper.getX(), creeper.getEyeY() - 0.15, creeper.getZ());

                    if (!creeper.isAlive() || creeper.isRemoved() || creeper.isDeadOrDying() || creeper.getHealth() <= 0) {
                        creeperCombatState = CreeperTacticState.NONE;
                        lastThreatEntityId = -1;
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
                if (player.getOffhandItem().is(Items.SHIELD)) {
                    client.options.keyUse.setDown(true);
                }
                return;
            }

            // 2. نمط القتال (Combat Mode): حماية النفس وقتل الوحش
            if (defenseMode == DefenseMode.COMBAT && threat.entity instanceof LivingEntity enemy && !(threat.entity instanceof Projectile)) {
                ensureBestAxeInMainHand(player, gameMode);
                ensureShieldInOffhand(client, player, gameMode);

                lookAt(player, enemy.getX(), enemy.getEyeY() - 0.15, enemy.getZ());
                float charge = player.getAttackStrengthScale(0.0f);
                boolean isFullyCharged = charge >= 0.95f;

                if (isFullyCharged) {
                    client.options.keyUse.setDown(false); // خفض الدرع للهجوم
                    if (player.isUsingItem() && player.getUseItem().is(Items.SHIELD)) {
                        gameMode.releaseUsingItem(player);
                        player.stopUsingItem();
                    }

                    if (distToThreat <= 3.2) {
                        if (player.onGround() && fightJumpCooldown <= 0 && BotFollowController.isCeilingClear(level, player.blockPosition())) {
                            client.options.keyJump.setDown(true);
                            fightJumpCooldown = 12;
                            client.options.keyUp.setDown(distToThreat > 1.8);
                        } else {
                            client.options.keyJump.setDown(false);
                            lookAt(player, enemy.getX(), enemy.getEyeY() - 0.15, enemy.getZ());
                            gameMode.attack(player, enemy);
                            player.swing(InteractionHand.MAIN_HAND);
                            client.options.keyJump.setDown(false);
                            client.options.keyUp.setDown(false);

                            if (!enemy.isAlive() || enemy.isRemoved() || enemy.isDeadOrDying() || enemy.getHealth() <= 0) {
                                lastThreatEntityId = -1;
                                threatLockTicks = 0;
                                stopDefending(client);
                            }
                        }
                    } else {
                        client.options.keyDown.setDown(false);
                        client.options.keyUp.setDown(true);
                        client.options.keySprint.setDown(true);
                        player.setSprinting(true);
                        if (fightJumpCooldown <= 0 && BotFollowController.isObstacleDirectlyAhead(player, level, enemy.getX(), enemy.getZ()) && player.onGround() && BotFollowController.isCeilingClear(level, player.blockPosition())) {
                            client.options.keyJump.setDown(true);
                            fightJumpCooldown = 10;
                        } else {
                            client.options.keyJump.setDown(false);
                        }
                    }
                } else {
                    client.options.keyJump.setDown(false);
                    if (distToThreat <= 3.8) {
                        client.options.keyUse.setDown(true);
                        client.options.keyUp.setDown(false);
                        client.options.keySprint.setDown(false);
                        player.setSprinting(false);
                        client.options.keyDown.setDown(true);
                    } else {
                        client.options.keyUse.setDown(false);
                        if (player.isUsingItem() && player.getUseItem().is(Items.SHIELD)) {
                            gameMode.releaseUsingItem(player);
                            player.stopUsingItem();
                        }
                        client.options.keyDown.setDown(false);
                        client.options.keyUp.setDown(true);
                        client.options.keySprint.setDown(false);
                        player.setSprinting(false);
                        if (fightJumpCooldown <= 0 && BotFollowController.isObstacleDirectlyAhead(player, level, enemy.getX(), enemy.getZ()) && player.onGround() && BotFollowController.isCeilingClear(level, player.blockPosition())) {
                            client.options.keyJump.setDown(true);
                            fightJumpCooldown = 10;
                        } else {
                            client.options.keyJump.setDown(false);
                        }
                    }
                }
                return;
            }

            // 3. نمط الحذر (Cautious Mode) أو المقذوفات: التصدي بالدرع والتراجع الذكي
            if (distToThreat <= 1.2 && !isEmergencyRunning) {
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
                // رفع الدرع للدفاع فوراً
                if (player.getOffhandItem().is(Items.SHIELD)) {
                    client.options.keyUse.setDown(true);
                    if (!player.isUsingItem() || !player.getUseItem().is(Items.SHIELD)) {
                        gameMode.useItem(player, InteractionHand.OFF_HAND);
                        player.startUsingItem(InteractionHand.OFF_HAND);
                    }
                    isDefending = true;
                } else {
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
            }
        } else {
            // لا تهديد: إيقاف الجري والتراجع وخفض الدرع وتناول الطعام بحرية
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
        }
    }

    /**
     * التأكد من وجود الدرع في اليد اليسرى (Offhand) بدلاً من الفأس أو غيره.
     * إذا كان في شريط الأدوات أو الحقيبة، يتم نقله تلقائياً إلى اليد اليسرى.
     */
    public boolean ensureShieldInOffhand(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        if (player.getOffhandItem().is(Items.SHIELD)) {
            return true;
        }

        int containerId = player.inventoryMenu.containerId;

        // 1. البحث في شريط الأدوات (Hotbar: الخانات 0 إلى 8)
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.SHIELD)) {
                int containerSlot = 36 + i;
                gameMode.handleContainerInput(containerId, containerSlot, 40, ContainerInput.SWAP, player);
                return true;
            }
        }

        // 2. البحث في الحقيبة الرئيسية (الخانات 9 إلى 35)
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.SHIELD)) {
                gameMode.handleContainerInput(containerId, i, 40, ContainerInput.SWAP, player);
                return true;
            }
        }

        return false;
    }

    public static int getAxeTier(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        if (stack.getItem() instanceof AxeItem) {
            if (stack.is(Items.NETHERITE_AXE)) return 7;
            if (stack.is(Items.DIAMOND_AXE)) return 6;
            if (stack.is(Items.IRON_AXE)) return 5;
            if (stack.is(Items.STONE_AXE)) return 4;
            if (stack.is(Items.GOLDEN_AXE)) return 3;
            if (stack.is(Items.WOODEN_AXE)) return 2;
            return 2;
        }
        if (stack.is(ItemTags.SWORDS)) {
            if (stack.is(Items.NETHERITE_SWORD)) return 6;
            if (stack.is(Items.DIAMOND_SWORD)) return 5;
            if (stack.is(Items.IRON_SWORD)) return 4;
            if (stack.is(Items.STONE_SWORD)) return 3;
            if (stack.is(Items.GOLDEN_SWORD)) return 2;
            if (stack.is(Items.WOODEN_SWORD)) return 1;
            return 1;
        }
        return -1;
    }

    public boolean ensureBestAxeInMainHand(LocalPlayer player, MultiPlayerGameMode gameMode) {
        ItemStack current = player.getMainHandItem();
        int bestSlot = -1;
        int bestTier = getAxeTier(current);

        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            int tier = getAxeTier(stack);
            if (tier > bestTier) {
                bestTier = tier;
                bestSlot = i;
            }
        }

        if (bestSlot != -1) {
            player.getInventory().setSelectedSlot(bestSlot);
            if (player.connection != null) {
                player.connection.send(new ServerboundSetCarriedItemPacket(bestSlot));
            }
            return true;
        }

        if (bestTier > 0) return true;

        int invBestSlot = -1;
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            int tier = getAxeTier(stack);
            if (tier > bestTier) {
                bestTier = tier;
                invBestSlot = i;
            }
        }

        if (invBestSlot != -1) {
            int targetHotbar = 0;
            int containerId = player.inventoryMenu.containerId;
            gameMode.handleContainerInput(containerId, invBestSlot, targetHotbar, ContainerInput.SWAP, player);
            player.getInventory().setSelectedSlot(targetHotbar);
            if (player.connection != null) {
                player.connection.send(new ServerboundSetCarriedItemPacket(targetHotbar));
            }
            return true;
        }
        return false;
    }

    /**
     * التأكد من وجود الفأس الزراعي (Hoe) في اليد اليسرى (Offhand) بدلاً من الدرع أو غيره.
     * إذا لم يكن في اليد اليسرى، يتم البحث عنه في شريط الأدوات أو الحقيبة وتبديله فوراً مع ما في اليد اليسرى.
     */
    public boolean ensureHoeInOffhand(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        if (player.getOffhandItem().getItem() instanceof HoeItem) {
            return true;
        }

        int containerId = player.inventoryMenu.containerId;

        // 1. البحث في شريط الأدوات (Hotbar: الخانات 0 إلى 8)
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof HoeItem) {
                int containerSlot = 36 + i;
                gameMode.handleContainerInput(containerId, containerSlot, 40, ContainerInput.SWAP, player);
                return true;
            }
        }

        // 2. البحث في الحقيبة الرئيسية (الخانات 9 إلى 35)
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof HoeItem) {
                gameMode.handleContainerInput(containerId, i, 40, ContainerInput.SWAP, player);
                return true;
            }
        }

        return false;
    }

    public boolean hasShieldInInventory(LocalPlayer player) {
        if (player.getOffhandItem().is(Items.SHIELD)) return true;
        for (int i = 0; i < 36; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty() && s.is(Items.SHIELD)) return true;
        }
        return false;
    }

    public boolean hasHoeInInventory(LocalPlayer player) {
        if (player.getOffhandItem().getItem() instanceof HoeItem) return true;
        for (int i = 0; i < 36; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof HoeItem) return true;
        }
        return false;
    }

    /**
     * كشف التهديدات المحيطة في مساحة 5*5 أفقياً بدقة عالية وحصانة كاملة:
     * - اللاعبين الذين ينظرون باتجاه البوت أو يقتربون لمسافة الضرب أو يلوحون بالسلاح
     * - الوحوش والموبات المعادية التي تستهدف البوت أو تنظر نحوه أو تقترب منه
     * - المقذوفات القادمة باتجاه البوت (أسهم، رماح)
     */
    public ThreatInfo detectThreat(LocalPlayer player, ClientLevel level) {
        Vec3 playerEyePos = player.getEyePosition();
        double playerX = player.getX();
        double playerY = player.getY();
        double playerZ = player.getZ();

        // 1. إذا تعرض اللاعب لأي ضربة مؤخراً، نحدد مصدر الضرر ونواجهه بالدرع فوراً بحصانة تامة (مع استثناء الأصدقاء)
        if (player.hurtTime > 0 && player.getLastHurtByMob() != null) {
            LivingEntity attacker = player.getLastHurtByMob();
            if (attacker.isAlive() && !attacker.isRemoved()) {
                boolean isAttackerFriend = false;
                if (attacker instanceof Player p) {
                    isAttackerFriend = BotFriendManager.getInstance().isFriend(p)
                                    || BotFollowController.getInstance().isFollowingPlayer(p);
                }
                if (!isAttackerFriend) {
                    double dx = Math.abs(attacker.getX() - playerX);
                    double dz = Math.abs(attacker.getZ() - playerZ);
                    if (dx <= 7.0 && dz <= 7.0) {
                        Vec3 aimPos = new Vec3(attacker.getX(), attacker.getY() + attacker.getEyeHeight() * 0.75, attacker.getZ());
                        return new ThreatInfo(attacker, aimPos, 500.0);
                    }
                }
            }
        }

        // فحص الكائنات المحيطة ضمن مساحة 6 بلوكات في كل اتجاه (نطاق 12×12 بلوكة إجمالاً)
        AABB scanBox = new AABB(
            playerX - 6.0, playerY - 3.0, playerZ - 6.0,
            playerX + 6.0, playerY + 4.0, playerZ + 6.0
        );

        ThreatInfo highestThreat = null;
        double highestPriority = -1;

        for (Entity entity : level.getEntities(player, scanBox)) {
            if (!entity.isAlive() || entity.isRemoved()) continue;

            double dx = Math.abs(entity.getX() - playerX);
            double dz = Math.abs(entity.getZ() - playerZ);
            double dy = Math.abs(entity.getY() - playerY);

            // التحقق من الحدود الدقيقة لمسافة 6 بلوكات
            if (dx > 6.0 || dz > 6.0 || dy > 4.0) continue;

            double distSq = dx * dx + dz * dz;

            // 2. فحص اللاعبين الآخرين (مع استثناء الأصدقاء المسجلين واللاعب المتبع تماماً)
            if (entity instanceof Player otherPlayer) {
                if (otherPlayer == player || otherPlayer.isSpectator() || otherPlayer.isCreative()) {
                    continue;
                }

                // فحص هل اللاعب صديق أو اللاعب المتبع: إذا كان كذلك لا يتم الحذر ولا الدفاع ضده إطلاقاً
                if (BotFriendManager.getInstance().isFriend(otherPlayer)
                    || BotFollowController.getInstance().isFollowingPlayer(otherPlayer)) {
                    continue;
                }

                Vec3 toBot = playerEyePos.subtract(otherPlayer.getEyePosition()).normalize();
                Vec3 otherLook = otherPlayer.getViewVector(1.0F).normalize();
                double lookDot = otherLook.dot(toBot);

                // يعتبر اللاعب تهديداً إذا:
                // 1) يلوح بيده أو سلاحه للهجوم
                // 2) يحمل سلاحاً (سيف، فأس، رمح، قوس)
                // 3) اقترب جداً (أقل من 3.5 بلوكات) حتى لو أشاح بنظره ليلتف
                // 4) كان مهدداً وتم قفل الهدف عليه حديثاً (لمنع تشتت البوت أثناء دوران الخصم)
                // 5) ينظر باتجاه البوت
                ItemStack handItem = otherPlayer.getMainHandItem();
                boolean isArmed = !handItem.isEmpty() && (
                    handItem.is(ItemTags.SWORDS)
                    || handItem.is(ItemTags.AXES)
                    || handItem.is(Items.BOW)
                    || handItem.is(Items.CROSSBOW)
                    || handItem.is(Items.TRIDENT)
                );
                boolean isVeryClose = distSq <= 12.25; // ضمن مسافة 3.5 بلوكات
                boolean isLookingAtBot = lookDot >= 0.1;
                boolean isAttacking = otherPlayer.swinging || otherPlayer.attackAnim > 0;
                boolean isLockedThreat = (otherPlayer.getId() == lastThreatEntityId && threatLockTicks > 0);

                if (isLookingAtBot || isAttacking || isArmed || isVeryClose || isLockedThreat) {
                    double priority = 200.0 + (isAttacking ? 100.0 : 0.0) + (isArmed ? 50.0 : 0.0) + (isVeryClose ? 60.0 : 0.0) + (lookDot * 40.0) - distSq;
                    if (priority > highestPriority) {
                        highestPriority = priority;
                        Vec3 aimPos = new Vec3(otherPlayer.getX(), otherPlayer.getY() + otherPlayer.getEyeHeight() * 0.75, otherPlayer.getZ());
                        highestThreat = new ThreatInfo(otherPlayer, aimPos, priority);
                    }
                }
                continue;
            }

            // 3. فحص الوحوش والموبات المعادية
            if (entity instanceof Mob mob) {
                // استثناء الحيوانات المسالمة في المزرعة (أبقار، خراف، دجاج، قرويين...)
                if (!isHostileOrAggressive(mob, player)) {
                    continue;
                }

                Vec3 toBot = playerEyePos.subtract(mob.getEyePosition()).normalize();
                Vec3 mobLook = mob.getViewVector(1.0F).normalize();
                double lookDot = mobLook.dot(toBot);

                boolean mobTargeting = mob.getTarget() == player;
                boolean mobLooking = lookDot >= 0.25;
                boolean mobAttacking = mob.swinging || mob.attackAnim > 0;

                if (mobTargeting || mobLooking || mobAttacking) {
                    double priority = 150.0 + (mobTargeting ? 100.0 : 0.0) + (lookDot * 40.0) - distSq;
                    if (priority > highestPriority) {
                        highestPriority = priority;
                        Vec3 aimPos = new Vec3(mob.getX(), mob.getY() + mob.getEyeHeight() * 0.75, mob.getZ());
                        highestThreat = new ThreatInfo(mob, aimPos, priority);
                    }
                }
            }
        }

        // 4. فحص المقذوفات المقتربة من البوت (أسهم، رماح) في نطاق 8 بلوكات (مع استثناء مقذوفات الأصدقاء)
        AABB projBox = new AABB(playerX - 8.0, playerY - 3.0, playerZ - 8.0, playerX + 8.0, playerY + 4.0, playerZ + 8.0);
        for (Projectile proj : level.getEntitiesOfClass(Projectile.class, projBox)) {
            if (!proj.isAlive() || proj.isRemoved() || proj.getOwner() == player) continue;
            if (proj.getOwner() instanceof Player ownerPlayer) {
                if (BotFriendManager.getInstance().isFriend(ownerPlayer)) {
                    continue;
                }
            }
            Vec3 vel = proj.getDeltaMovement();
            Vec3 toBot = playerEyePos.subtract(proj.position());
            if (vel.dot(toBot) > 0.1) {
                return new ThreatInfo(proj, proj.position(), 600.0);
            }
        }

        if (highestThreat != null) {
            lastThreatEntityId = highestThreat.entity().getId();
            threatLockTicks = 40; // استمرار قفل التركيز لمدة ثانيتين (40 نبضة) لمنع تشتت البوت أثناء دوران الخصم
        } else if (threatLockTicks > 0) {
            threatLockTicks--;
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
        if (!player.getCooldowns().isOnCooldown(new ItemStack(Items.SHIELD))) return;

        shieldSwapCooldown = 100; // منع التكرار والسبام لمدة 5 ثوانٍ كاملة
        int containerId = player.inventoryMenu.containerId;

        // البحث في الحقيبة الرئيسية عن درع آخر
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.SHIELD)) {
                gameMode.handleContainerInput(containerId, i, 40, ContainerInput.SWAP, player);
                sendChatMessage(client, "§e[Bot - Defend] الدرع تعطل بضربة فأس! تم استبداله بدرع احتياطي فوراً.");
                return;
            }
        }
        // البحث في شريط الأدوات
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.SHIELD)) {
                gameMode.handleContainerInput(containerId, 36 + i, 40, ContainerInput.SWAP, player);
                sendChatMessage(client, "§e[Bot - Defend] الدرع تعطل بضربة فأس! تم استبداله بدرع احتياطي فوراً.");
                return;
            }
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
     * قياس المسافة بالأمتار حتى أول جدار أو عائق أو سياج في اتجاه معين (رسم محيط الغرفة)
     */
    public static double measureDistanceToObstacle(ClientLevel level, LocalPlayer player, double dirX, double dirZ, double maxDist) {
        if (level == null || player == null) return maxDist;
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        double step = 0.5;
        for (double d = 0.5; d <= maxDist; d += step) {
            double cx = px + dirX * d;
            double cz = pz + dirZ * d;

            BlockPos feet = BlockPos.containing(cx, py, cz);
            BlockPos head = feet.above();

            // فحص هل البلوكة غير سالكة أو جدار أو سياج
            if (!FarmPathfinder.isPassable(level, feet) || !FarmPathfinder.isPassable(level, head)
                || FarmPathfinder.isFenceOrWall(level.getBlockState(feet))
                || FarmPathfinder.isFenceOrWall(level.getBlockState(head))) {
                return Math.max(0.0, d - 0.5);
            }

            // فحص السقوط من حافة خطرة (هاوية أو لافا)
            BlockState below = level.getBlockState(feet.below());
            if (below.isAir() || below.is(Blocks.LAVA)) {
                BlockState below2 = level.getBlockState(feet.below(2));
                if (below2.isAir() || below2.is(Blocks.LAVA)) {
                    return Math.max(0.0, d - 0.5);
                }
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

        if (client.level != null && player.horizontalCollision && player.onGround() && fightJumpCooldown <= 0 && BotFollowController.isCeilingClear(client.level, player.blockPosition())) {
            client.options.keyJump.setDown(true);
            fightJumpCooldown = 10;
        } else {
            client.options.keyJump.setDown(false);
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
        float f = degrees % 360.0f;
        if (f >= 180.0f) f -= 360.0f;
        if (f < -180.0f) f += 360.0f;
        return f;
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

        // الضغط على مفتاح التقدم للأمام وتفعيل الجري السريع
        client.options.keyUp.setDown(true);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keySprint.setDown(true);
        player.setSprinting(true);

        // القفز التلقائي عند مواجهة أي عقبة أثناء الجري بنبضة منضبطة
        if (client.level != null && player.horizontalCollision && player.onGround() && fightJumpCooldown <= 0 && BotFollowController.isCeilingClear(client.level, player.blockPosition())) {
            client.options.keyJump.setDown(true);
            fightJumpCooldown = 10;
        } else {
            client.options.keyJump.setDown(false);
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

    public record ThreatInfo(Entity entity, Vec3 pos, double priority) {}
}
