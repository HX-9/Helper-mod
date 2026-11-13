package com.helpermod.bot;
import com.helpermod.bot.combat.BotCombatManager;
import com.helpermod.bot.food.BotFoodManager;
import com.helpermod.bot.path.FarmPathfinder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * وحدة التحكم الذكية بوضع الاتباع (Smart Companion Follow Controller):
 * 1. الركض السريع (Sprint) من بعيد (> 10 بلوكات) حتى الوصول لخلف الصديق لليمين على بعد 3 بلوكات.
 * 2. عند الوصول: التوقف التام ومطابقة زاوية نظر الصديق (Yaw & Pitch).
 * 3. الثبات التام في المكان طالما أن الصديق لم يتحرك بقدميه (حتى لو دار برأسه والتفت يميناً ويساراً 360 درجة).
 * 4. عند تحرك الصديق ومشيه: مرافقة مرنة والبقاء قريباً منه بمسافة ~3 بلوكات من أي جهة دون دوران دائري أو تشنج.
 * 5. خوارزميات ذكية لمنع العلوق (Anti-Stuck): مناورة جانبية، قفز ذكي، فتح الأبواب تلقائياً، وإعادة حساب A* فورية.
 * 6. التوافق التام مع نظام الدفاع بالدرع (Shield Defense) دون تعارض.
 */
public class BotFollowController {

    private static final BotFollowController INSTANCE = new BotFollowController();

    public static BotFollowController getInstance() {
        return INSTANCE;
    }

    private enum FollowState {
        SPRINT_FROM_DISTANCE,   // الركض لمسافة طويلة (> 10 بلوكات) للوصول لخلف اليمين
        STATIONARY_MATCHING,    // الاستقرار في المكان ومطابقة زاوية نظر الصديق
        FLEXIBLE_ESCORT         // المرافقة المرنة عند حركة الصديق للمحافظة على مسافة ~3 بلوكات
    }

    private boolean following = false;
    private String targetPlayerName = null;
    private UUID targetPlayerUUID = null;

    private FollowState currentState = FollowState.SPRINT_FROM_DISTANCE;

    // موضع تثبيت الصديق عند الاستقرار لمراقبة هل تحركت أقدامه أم التفت فقط
    private Vec3 companionAnchorPos = null;

    // مسار A* للوصول الآمن وتفادي العوائق
    private List<BlockPos> currentSafePath = new ArrayList<>();
    private BlockPos lastGoalPos = null;
    private int pathRecalcCooldown = 0;

    // كاشف ومناور العلوق الذكي (Smart Anti-Stuck System)
    private Vec3 lastStuckCheckPos = null;
    private int stuckTicks = 0;
    private int strafeTicks = 0;
    private boolean strafeRight = true;

    // عدادات الاستقرار والتفاعل
    private int companionStationaryTicks = 0;
    private int doorInteractCooldown = 0;

    // نظام الحارس الشخصي ورصد الأعداء (Tactical Bodyguard & Aggro System)
    private final Map<UUID, Integer> hostileTargets = new HashMap<>();
    private UUID currentTargetUUID = null;
    private boolean isJumpingForCrit = false;
    private int jumpCritTicks = 0;
    private int tetherAlertCooldown = 0;
    @SuppressWarnings("unused")
    private int arrowDefenseCooldown = 0;
    @SuppressWarnings("unused")
    private int eatingTicks = 0;
    @SuppressWarnings("unused")
    private boolean isEatingInCombat = false;
    // الأولوية رقم 1: نظام الأكل والعلاج الذاتي التلقائي أثناء الاتباع
    private boolean isEatingWhileFollowing = false;
    private int followEatingTicks = 0;
    private int lastFollowFoodLevel = -1;
    private int shieldSwapCooldown = 0;
    private int hungryAlertCooldown = 0;
    private int moveJumpCooldown = 0;
    private int followEatSuppressionTicks = 0;

    // تكتيك قتال الكريبر (Hit & Run 10 Blocks with Sprint-Jump)
    private enum CreeperTacticState {
        NONE,
        APPROACH_AND_STRIKE,
        FLEE_SPRINT_JUMP,
        ASSESS
    }
    private CreeperTacticState creeperState = CreeperTacticState.NONE;
    private Vec3 creeperFleeStartPos = null;
    private Vec3 creeperFleeDir = null;
    private int creeperFleeTicks = 0;
    private int creeperAssessTicks = 0;

    private BotFollowController() {}

    public boolean isFollowing() {
        return following;
    }

    public String getTargetPlayerName() {
        return targetPlayerName;
    }

    public UUID getTargetPlayerUUID() {
        return targetPlayerUUID;
    }

    /**
     * التحقق مما إذا كان اللاعب المحدد هو المرافق المتبع حالياً
     */
    public boolean isFollowingPlayer(Player player) {
        if (!following || player == null) return false;
        if (targetPlayerUUID != null && targetPlayerUUID.equals(player.getUUID())) {
            return true;
        }
        if (targetPlayerName != null) {
            String name1 = player.getScoreboardName();
            String name2 = player.getName().getString();
            return targetPlayerName.equalsIgnoreCase(name1) || targetPlayerName.equalsIgnoreCase(name2);
        }
        return false;
    }

    public boolean isFollowingPlayer(String name) {
        if (!following || name == null || targetPlayerName == null) return false;
        return targetPlayerName.equalsIgnoreCase(name);
    }

    /**
     * بدء وضع الاتباع
     */
    public void startFollowing(Minecraft client, String playerName) {
        this.targetPlayerName = playerName;
        this.targetPlayerUUID = null;
        this.following = true;
        this.currentState = FollowState.SPRINT_FROM_DISTANCE;
        this.companionAnchorPos = null;
        this.currentSafePath.clear();
        this.lastGoalPos = null;
        this.stuckTicks = 0;
        this.strafeTicks = 0;
        this.lastStuckCheckPos = null;
        this.companionStationaryTicks = 0;
        this.hostileTargets.clear();
        this.currentTargetUUID = null;
        this.isJumpingForCrit = false;
        this.jumpCritTicks = 0;
        this.tetherAlertCooldown = 0;
        this.arrowDefenseCooldown = 0;
        this.eatingTicks = 0;
        this.isEatingInCombat = false;
        this.isEatingWhileFollowing = false;
        this.followEatingTicks = 0;
        this.lastFollowFoodLevel = -1;
        this.shieldSwapCooldown = 0;
        this.hungryAlertCooldown = 0;
        this.moveJumpCooldown = 0;
        this.followEatSuppressionTicks = 0;
        this.creeperState = CreeperTacticState.NONE;
        this.creeperFleeStartPos = null;
        this.creeperFleeDir = null;
        this.creeperFleeTicks = 0;
        this.creeperAssessTicks = 0;

        // تصفير أي قتال أو دفاع معلق في الكنترولر القتالي
        BotCombatManager.getInstance().resetCombat(client);
        FightBotController.getInstance().stopDefending(client);

        // إيقاف بوت المزرعة لمنع أي تضارب حركي
        if (FarmBotController.getInstance().isFarming()) {
            FarmBotController.getInstance().stop(client);
        }

        if (client != null && client.level != null) {
            Player p = findPlayerByName(client.level, playerName);
            if (p != null) {
                this.targetPlayerUUID = p.getUUID();
            }
        }

        if (client != null && client.player != null) {
            client.player.sendSystemMessage(Component.literal("§a[Bot] تم تفعيل وضع الاتباع الذكي والحارس الشخصي للاعب: §e" + playerName + "§a!"));
        }
    }

    /**
     * إيقاف وضع الاتباع
     */
    public void stopFollowing(Minecraft client) {
        this.following = false;
        this.targetPlayerName = null;
        this.targetPlayerUUID = null;
        this.currentState = FollowState.SPRINT_FROM_DISTANCE;
        this.companionAnchorPos = null;
        this.currentSafePath.clear();
        this.lastGoalPos = null;
        this.stuckTicks = 0;
        this.strafeTicks = 0;
        this.hostileTargets.clear();
        this.currentTargetUUID = null;
        this.isJumpingForCrit = false;
        this.jumpCritTicks = 0;
        this.tetherAlertCooldown = 0;
        this.arrowDefenseCooldown = 0;
        this.eatingTicks = 0;
        this.isEatingInCombat = false;
        this.isEatingWhileFollowing = false;
        this.followEatingTicks = 0;
        this.lastFollowFoodLevel = -1;
        this.shieldSwapCooldown = 0;
        this.hungryAlertCooldown = 0;
        this.moveJumpCooldown = 0;
        this.followEatSuppressionTicks = 0;
        this.creeperState = CreeperTacticState.NONE;
        this.creeperFleeStartPos = null;
        this.creeperFleeDir = null;
        this.creeperFleeTicks = 0;
        this.creeperAssessTicks = 0;

        BotCombatManager.getInstance().resetCombat(client);
        releaseMovement(client);
        if (client != null) {
            if (client.player != null && client.gameMode != null && client.player.isUsingItem()) {
                client.gameMode.releaseUsingItem(client.player);
                client.player.stopUsingItem();
            }
            if (client.options != null) {
                client.options.keyUse.setDown(false);
            }
        }

        if (client != null && client.player != null) {
            client.player.sendSystemMessage(Component.literal("§c[Bot] تم إيقاف وضع الاتباع."));
        }
    }

    /**
     * التحديث الدوري مع كل Tick
     */
    public void onTick(Minecraft client) {
        if (!following || client == null) return;

        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        MultiPlayerGameMode gameMode = client.gameMode;

        if (player == null || level == null || player.isDeadOrDying()) {
            releaseMovement(client);
            return;
        }

        if (doorInteractCooldown > 0) doorInteractCooldown--;
        if (pathRecalcCooldown > 0) pathRecalcCooldown--;

        Player companion = findTargetPlayer(level);
        if (companion == null) {
            releaseMovement(client);
            return;
        }
        this.targetPlayerUUID = companion.getUUID();

        if (tetherAlertCooldown > 0) tetherAlertCooldown--;
        if (hungryAlertCooldown > 0) hungryAlertCooldown--;
        if (moveJumpCooldown > 0) moveJumpCooldown--;
        if (followEatSuppressionTicks > 0) followEatSuppressionTicks--;

        // تحرير القفز التلقائي فوراً بمجرد الارتفاع عن الأرض في الهواء لمنع تكرار القفز العشوائي
        boolean inWater = player.isInWater() || level.getBlockState(player.blockPosition()).is(Blocks.WATER);
        if (!player.onGround() && !inWater && !isJumpingForCrit) {
            client.options.keyJump.setDown(false);
        }

        // حساب المسافات بدقة لتنظيم مصفوفة الأولويات الثلاث
        double dx = player.getX() - companion.getX();
        double dz = player.getZ() - companion.getZ();
        double distToCompanion = Math.sqrt(dx * dx + dz * dz);

        // ==============================================================
        // الأولوية 1: القتال والدفاع التكتيكي الحاسم (Combat & Defense)
        // ==============================================================
        // تفويض كامل لمنطق القتال المتخصص BotCombatManager لفصل القتال عن الملاحة
        boolean engagedInCombat = BotCombatManager.getInstance().tickCombat(client, player, level, gameMode, companion, distToCompanion);
        if (engagedInCombat) {
            // أثناء القتال النشط، نوقف الأكل فوراً ونترك السيطرة الحركية والقتالية التامة لمنسق المعارك
            stopFollowEating(client, player, gameMode);
            return;
        }

        // ==============================================================
        // الأولوية 2: اللحاق بالقائد والتمركز الصحيح (Follow Catch-up)
        // ==============================================================
        // إذا ابتعد القائد (> 4.5 بلوكات): نوقف الأكل فوراً ونعطي الأولوية الكاملة للركض واللحاق به!
        if (distToCompanion > 4.5) {
            if (isEatingWhileFollowing) {
                stopFollowEating(client, player, gameMode);
                followEatSuppressionTicks = 35; // مهلة منع العودة السريعة للأكل لمنع التذبذب والبطء
            }
            // خفض الدرع وإيقاف استخدام أي عنصر فوراً لتمكين الركض السريع (Sprint) بكامل السرعة
            if (client.options.keyUse.isDown()) {
                client.options.keyUse.setDown(false);
            }
            if (player.isUsingItem()) {
                gameMode.releaseUsingItem(player);
                player.stopUsingItem();
            }
        }

        // ==============================================================
        // الأولوية 3: تناول الطعام والعلاج التلقائي (Safe Eating & Healing)
        // ==============================================================
        // يأكل فقط إذا كان في نطاق آمن ومريح ومستقر بجوار القائد (<= 4.0 بلوكات)، ولا توجد معارك، وانتهت مهلة المنع
        if (!engagedInCombat && distToCompanion <= 4.0 && followEatSuppressionTicks <= 0) {
            handleFollowEating(client, player, gameMode);
        }

        boolean busyDefending = false;

        // فحص هل الصديق تحركت أقدامه فعلياً من مكانه أم أنه واقف ويلتفت فقط
        boolean companionStepped = companionAnchorPos == null
            || companion.position().distanceTo(companionAnchorPos) > 1.2;

        if (!companionStepped) {
            companionStationaryTicks++;
        } else {
            companionStationaryTicks = 0;
        }

        // أ) إذا ابتعد القائد مسافة (> 5.5 بلوكات): الركض السريع الفوري نحو القائد دون تأخير!
        if (distToCompanion > 5.5) {
            currentState = FollowState.SPRINT_FROM_DISTANCE;
            companionAnchorPos = null;
        }

        switch (currentState) {
            case SPRINT_FROM_DISTANCE: {
                // إذا اقترب البوت من القائد (<= 3.2 بلوكة)، ننهي الركض البعيد وننتقل فوراً للمرافقة المرنة
                if (distToCompanion <= 3.2) {
                    currentState = FollowState.FLEXIBLE_ESCORT;
                    releaseMovement(client);
                    break;
                }

                // الركض السريع المباشر نحو موضع القائد الحالي
                Vec3 targetPos = companion.position();
                smartNavigateTo(client, player, level, gameMode, targetPos, true, 2.5, busyDefending);
                break;
            }

            case STATIONARY_MATCHING: {
                // حالة الاستقرار: إذا تحرك القائد أو ابتعدت المسافة عن 3.4 بلوكات، نخرج فوراً ونلحق به!
                if (companionStepped || distToCompanion > 3.4) {
                    currentState = FollowState.FLEXIBLE_ESCORT;
                    companionAnchorPos = null;
                    break;
                }

                // البوت يظل ثابتاً في مكانه ولا يتحرك إن كان القائد لم يتحرك
                releaseMovement(client);

                // ثم ينظر لنفس المكان الذي ينظر إليه صديقه
                if (!busyDefending) {
                    smoothLookTowards(player, companion.getYRot(), companion.getXRot(), 18.0f);
                }
                break;
            }

            case FLEXIBLE_ESCORT: {
                // إذا توقف القائد لأكثر من ثانيتين ومسافته قريبة ومستقرة، نستقر ونطابق النظر
                if (!companionStepped && companionStationaryTicks >= 40 && distToCompanion <= 3.6) {
                    currentState = FollowState.STATIONARY_MATCHING;
                    companionAnchorPos = companion.position();
                    releaseMovement(client);
                    if (!busyDefending) {
                        smoothLookTowards(player, companion.getYRot(), companion.getXRot(), 20.0f);
                    }
                    return;
                }

                // الحفاظ على مسافة طبيعية ومريحة (~2.8 بلوكة) من القائد
                if (distToCompanion < 2.2) {
                    // قريب جداً: التوقف حتى لا يصطدم به أو يدفعه
                    releaseMovement(client);
                    if (!busyDefending) {
                        smoothLookTowards(player, companion.getYRot(), companion.getXRot(), 15.0f);
                    }
                } else if (distToCompanion > 3.2) {
                    // القائد ابتعد: الاقتراب منه فوراً
                    Vec3 escortTarget = calculateFlexibleEscortPos(player, companion, level);
                    boolean sprint = distToCompanion > 4.5;
                    smartNavigateTo(client, player, level, gameMode, escortTarget, sprint, 0.5, busyDefending);
                } else {
                    // المسافة ممتازة
                    releaseMovement(client);
                    if (!busyDefending) {
                        smoothLookTowards(player, companion.getYRot(), companion.getXRot(), 15.0f);
                    }
                }
                break;
            }
        }
    }

    /**
     * الملاحة الذكية وفك العلوق التلقائي (Smart Navigation & Anti-Stuck)
     */
    private void smartNavigateTo(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode, Vec3 destination, boolean allowSprint, double stopDist, boolean busyDefending) {
        // فحص الوصول للهدف النهائي: نتوقف فقط إذا وصلنا للمسافة المطلوبة من الهدف النهائي الفعلي وليس من نقاط المسار
        double finalDx = destination.x - player.getX();
        double finalDz = destination.z - player.getZ();
        double distToFinalSq = finalDx * finalDx + finalDz * finalDz;
        if (distToFinalSq <= stopDist * stopDist) {
            releaseMovement(client);
            currentSafePath.clear();
            lastGoalPos = null;
            return;
        }

        BlockPos goalPos = BlockPos.containing(destination.x, destination.y, destination.z);

        // 1. كاشف العلوق الذكي (Stuck Detection)
        if (lastStuckCheckPos == null) {
            lastStuckCheckPos = player.position();
        } else {
            double movedDist = player.position().distanceTo(lastStuckCheckPos);
            if (movedDist < 0.08) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
                strafeTicks = 0;
                lastStuckCheckPos = player.position();
            }
        }

        // 2. معالجة وفك العلوق بمناورات ذكية
        if (stuckTicks >= 12) {
            // فحص وفتح أي أبواب أو بوابات سياج مغلقة أمامه
            checkAndOpenDoors(client, player, level, gameMode);

            // قفزة ذكية لتخطي العائق إن كان هناك عائق حقيقي وسالك فوقه
            boolean hasObs = isObstacleDirectlyAhead(player, level, destination.x, destination.z)
                          || hasStepDirectlyAhead(player, level, destination.x, destination.z);
            if (player.onGround() && hasObs && isCeilingClear(level, player.blockPosition()) && moveJumpCooldown <= 0) {
                client.options.keyJump.setDown(true);
                moveJumpCooldown = 12;
            } else {
                client.options.keyJump.setDown(false);
            }

            // مناورة جانبية (Strafing) لتخطي الزوايا
            strafeTicks++;
            if (strafeRight) {
                client.options.keyRight.setDown(true);
                client.options.keyLeft.setDown(false);
            } else {
                client.options.keyLeft.setDown(true);
                client.options.keyRight.setDown(false);
            }

            if (strafeTicks > 8) {
                strafeRight = !strafeRight;
                strafeTicks = 0;
            }

            if (stuckTicks >= 24) {
                // علوق مستمر: إعادة حساب مسار A* بديل فوراً
                currentSafePath.clear();
                lastGoalPos = null;
                pathRecalcCooldown = 0;
                stuckTicks = 0;
                client.options.keyJump.setDown(false);
            }
        } else {
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
        }

        // 3. تخطيط المسار الآمن بواسطة A*
        boolean needNewPath = currentSafePath.isEmpty() || (pathRecalcCooldown <= 0 && (lastGoalPos == null || goalPos.distSqr(lastGoalPos) > 9.0));
        if (needNewPath && pathRecalcCooldown <= 0) {
            lastGoalPos = goalPos;
            pathRecalcCooldown = 10;
            List<BlockPos> path = FarmPathfinder.findPath(level, player.blockPosition(), goalPos, 3000);
            if (path != null && !path.isEmpty()) {
                currentSafePath = new ArrayList<>(path);
            }
        }

        // 4. الحركة نحو العقدة التالية أو الهدف المباشر
        Vec3 nextTarget = destination;
        if (!currentSafePath.isEmpty()) {
            // تنعيم المسار: إذا كان البوت بالفعل عند أو أقرب للعقدة التالية (node 1)، نتخطى العقدة 0 لمنع الالتفاف للخلف
            if (currentSafePath.size() >= 2) {
                BlockPos node1 = currentSafePath.get(1);
                double d1x = player.getX() - (node1.getX() + 0.5);
                double d1z = player.getZ() - (node1.getZ() + 0.5);
                if (d1x * d1x + d1z * d1z < (allowSprint ? 2.5 : 1.5) && Math.abs(player.getY() - node1.getY()) < 1.5) {
                    currentSafePath.remove(0);
                }
            }

            BlockPos nextNode = currentSafePath.get(0);
            double nx = nextNode.getX() + 0.5;
            double ny = nextNode.getY();
            double nz = nextNode.getZ() + 0.5;

            double ndx = player.getX() - nx;
            double ndz = player.getZ() - nz;
            double reachThreshold = allowSprint ? 1.44 : 0.8;
            if (ndx * ndx + ndz * ndz < reachThreshold && Math.abs(player.getY() - ny) < 1.5) {
                currentSafePath.remove(0);
                if (!currentSafePath.isEmpty()) {
                    nextNode = currentSafePath.get(0);
                    nx = nextNode.getX() + 0.5;
                    ny = nextNode.getY();
                    nz = nextNode.getZ() + 0.5;
                    nextTarget = new Vec3(nx, ny, nz);
                } else {
                    nextTarget = destination;
                }
            } else {
                nextTarget = new Vec3(nx, ny, nz);
            }
        }

        executeMove(client, player, level, gameMode, nextTarget.x, nextTarget.y, nextTarget.z, allowSprint, busyDefending);
    }

    /**
     * تنفيذ الحركة وضبط الأزرار والنظر
     */
    private void executeMove(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode, double tx, double ty, double tz, boolean sprint, boolean busyDefending) {
        double dx = tx - player.getX();
        double dz = tz - player.getZ();
        double distSq = dx * dx + dz * dz;

        if (distSq < 0.04) {
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            player.setSprinting(false);
            return;
        }

        // إذا كان البوت في حالة دفاع نشطة بالدرع، نترك التحكم الحركي الكامل لـ FightBotController
        if (busyDefending) {
            return;
        }

        lookAt(player, tx, player.getEyeY(), tz);

        // تجنب الدوس على التوت الحلو أثناء المشي المباشر
        double moveDirX = tx - player.getX();
        double moveDirZ = tz - player.getZ();
        double moveLen = Math.sqrt(moveDirX * moveDirX + moveDirZ * moveDirZ);
        if (moveLen > 0.1) {
            double normX = moveDirX / moveLen;
            double normZ = moveDirZ / moveLen;
            BlockPos aheadFeet = BlockPos.containing(player.getX() + normX * 0.75, player.getY() + 0.1, player.getZ() + normZ * 0.75);
            BlockPos aheadFloor = aheadFeet.below();
            if (level.getBlockState(aheadFeet).is(Blocks.SWEET_BERRY_BUSH) || level.getBlockState(aheadFloor).is(Blocks.SWEET_BERRY_BUSH)) {
                // شجيرة توت أمام البوت مباشرة: إيقاف التقدم المباشر وإعادة تخطيط مسار A* آمن فوراً للالتفاف حولها
                client.options.keyUp.setDown(false);
                client.options.keySprint.setDown(false);
                player.setSprinting(false);
                pathRecalcCooldown = 0;
                currentSafePath.clear();
                lastGoalPos = null;
                return;
            }
        }

        client.options.keyUp.setDown(true);
        boolean canSprint = sprint && !isEatingWhileFollowing && player.getFoodData().getFoodLevel() > 6;
        client.options.keySprint.setDown(canSprint);
        player.setSprinting(canSprint);

        // قفز احترافي بنظام النبضة الواحدة (Single Pulse & Cooldown) لمنع كثرة القفز والتعليق:
        if (moveJumpCooldown > 0) moveJumpCooldown--;

        // التعامل مع الماء
        boolean inWater = player.isInWater() || level.getBlockState(player.blockPosition()).is(Blocks.WATER);
        if (inWater) {
            client.options.keyJump.setDown(true);
            client.options.keySprint.setDown(true);
            return;
        }

        boolean isNodeUp = !currentSafePath.isEmpty() && (ty - player.getY() >= 0.45);
        boolean ceilingOk = isCeilingClear(level, player.blockPosition());
        boolean obstacleAhead = isObstacleDirectlyAhead(player, level, tx, tz) || hasStepDirectlyAhead(player, level, tx, tz);

        // يقفز فقط إذا كانت هناك عقدة مسار صاعدة، أو عائق أمامي صلب وسالك فوقه، وكان على الأرض، ولم يقفز لتوه
        boolean needsJump = (isNodeUp || obstacleAhead) && player.onGround() && ceilingOk && moveJumpCooldown <= 0;

        if (needsJump) {
            client.options.keyJump.setDown(true);
            moveJumpCooldown = 10; // مهلة كافية لصعود البلوكة والهبوط دون تكرار القفز العشوائي
            checkAndOpenDoors(client, player, level, gameMode);
        } else {
            // تحرير زر القفز فوراً بمجرد الارتفاع عن الأرض أو عدم وجود عائق لمنع تكرار القفز العبثي
            client.options.keyJump.setDown(false);
        }
    }

    /**
     * حساب موضع المرافقة المرنة (~3 بلوكات من الصديق من أقرب جهة سالكة دون دوران)
     */
    private Vec3 calculateFlexibleEscortPos(LocalPlayer player, Player companion, ClientLevel level) {
        double dx = player.getX() - companion.getX();
        double dz = player.getZ() - companion.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);

        Vec3 dir;
        if (len > 0.1) {
            dir = new Vec3(dx / len, 0, dz / len);
        } else {
            // كاحتياط خلف اللاعب
            float yaw = companion.getYRot();
            double rad = Math.toRadians(yaw);
            dir = new Vec3(Math.sin(rad), 0, -Math.cos(rad));
        }

        // النقطة المستهدفة على بعد 2.8 بلوكة
        double targetX = companion.getX() + dir.x * 2.8;
        double targetZ = companion.getZ() + dir.z * 2.8;
        double targetY = companion.getY();

        BlockPos base = BlockPos.containing(targetX, targetY, targetZ);
        for (int dy : new int[]{0, 1, -1, 2, -2}) {
            BlockPos test = base.above(dy);
            if (isSafeStandPosition(level, test)) {
                return new Vec3(test.getX() + 0.5, test.getY(), test.getZ() + 0.5);
            }
        }

        return new Vec3(targetX, targetY, targetZ);
    }

    /**
     * حساب موضع خلف الصديق في اليمين على بعد 3 بلوكات بالضبط (yaw + 135°)
     */
    public static Vec3 calculateBehindRightPost(Player companion, ClientLevel level) {
        float yaw = companion.getYRot();
        double angleRad = Math.toRadians(yaw + 135.0f);

        double offsetX = -Math.sin(angleRad) * 3.0;
        double offsetZ = Math.cos(angleRad) * 3.0;

        double targetX = companion.getX() + offsetX;
        double targetZ = companion.getZ() + offsetZ;
        double baseY = companion.getY();

        BlockPos basePos = BlockPos.containing(targetX, baseY, targetZ);
        int[] yOffsets = {0, 1, -1, 2, -2};
        for (int dy : yOffsets) {
            BlockPos cand = basePos.above(dy);
            if (isSafeStandPosition(level, cand)) {
                return new Vec3(cand.getX() + 0.5, cand.getY(), cand.getZ() + 0.5);
            }
        }

        // فحص النقاط المجاورة القريبة إن كان هناك جدار
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (ox == 0 && oz == 0) continue;
                for (int dy : yOffsets) {
                    BlockPos cand = basePos.offset(ox, dy, oz);
                    if (isSafeStandPosition(level, cand)) {
                        return new Vec3(cand.getX() + 0.5, cand.getY(), cand.getZ() + 0.5);
                    }
                }
            }
        }

        return new Vec3(targetX, baseY, targetZ);
    }

    /**
     * فحص هل سقف الغرفة سالك للقفز دون ارتطام الرأس
     */
    public static boolean isCeilingClear(ClientLevel level, BlockPos pos) {
        return FarmPathfinder.isPassable(level, pos.above(2));
    }

    /**
     * فحص هل يوجد عائق صلب حقيقي بارتفاع بلوكة واحدة أمام قدمي البوت في اتجاه هدفه وسالك فوقه للصعود
     */
    @SuppressWarnings("deprecation")
    public static boolean isObstacleDirectlyAhead(LocalPlayer player, ClientLevel level, double tx, double tz) {
        if (!player.horizontalCollision) return false;
        double dx = tx - player.getX();
        double dz = tz - player.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.05) return false;

        double dirX = dx / len;
        double dirZ = dz / len;

        BlockPos aheadPos = BlockPos.containing(player.getX() + dirX * 0.75, player.getY() + 0.2, player.getZ() + dirZ * 0.75);
        BlockState aheadState = level.getBlockState(aheadPos);
        if (aheadState.isAir() || !aheadState.blocksMotion() || FarmPathfinder.isFenceOrWall(aheadState)) {
            return false;
        }

        BlockPos aboveAhead = aheadPos.above();
        BlockPos aboveAboveAhead = aheadPos.above(2);
        return FarmPathfinder.isPassable(level, aboveAhead) && FarmPathfinder.isPassable(level, aboveAboveAhead);
    }

    /**
     * فحص هل توجد درجة أو حافة بارتفاع بلوكة أمام البوت مباشرة وسالكة فوقها للصعود السلس دون انتظار التصادم
     */
    @SuppressWarnings("deprecation")
    public static boolean hasStepDirectlyAhead(LocalPlayer player, ClientLevel level, double tx, double tz) {
        double dx = tx - player.getX();
        double dz = tz - player.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        if (distXZ < 0.1) return false;
        double normX = dx / distXZ;
        double normZ = dz / distXZ;

        BlockPos currentFoot = player.blockPosition();
        BlockPos aheadFoot = BlockPos.containing(player.getX() + normX * 0.75, player.getY() + 0.1, player.getZ() + normZ * 0.75);
        if (aheadFoot.equals(currentFoot)) {
            aheadFoot = BlockPos.containing(player.getX() + normX * 1.1, player.getY() + 0.1, player.getZ() + normZ * 1.1);
        }
        if (aheadFoot.equals(currentFoot)) return false;

        BlockState footState = level.getBlockState(aheadFoot);
        if (!footState.isAir() && footState.blocksMotion() && !FarmPathfinder.isFenceOrWall(footState)) {
            BlockPos aboveFoot = aheadFoot.above();
            BlockPos aboveAboveFoot = aheadFoot.above(2);
            return FarmPathfinder.isPassable(level, aboveFoot) && FarmPathfinder.isPassable(level, aboveAboveFoot);
        }
        return false;
    }

    /**
     * التحقق من سلامة موقع الوقوف
     */
    private static boolean isSafeStandPosition(ClientLevel level, BlockPos pos) {
        if (!FarmPathfinder.isPassable(level, pos) || !FarmPathfinder.isPassable(level, pos.above())) {
            return false;
        }
        BlockPos floorPos = pos.below();
        BlockState floor = level.getBlockState(floorPos);
        if (floor.isAir() || floor.is(Blocks.LAVA) || floor.is(Blocks.FIRE) || floor.is(Blocks.SWEET_BERRY_BUSH) || level.getBlockState(pos).is(Blocks.SWEET_BERRY_BUSH)) {
            return false;
        }
        if (FarmPathfinder.isFenceOrWall(floor)) {
            return false;
        }
        return !floor.getCollisionShape(level, floorPos).isEmpty();
    }

    /**
     * فحص وفتح أي أبواب خشبية أو بوابات سياج مغلقة
     */
    private void checkAndOpenDoors(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode) {
        if (gameMode == null || doorInteractCooldown > 0) return;
        BlockPos feet = player.blockPosition();

        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                for (int oy = 0; oy <= 1; oy++) {
                    BlockPos p = feet.offset(ox, oy, oz);
                    BlockState st = level.getBlockState(p);
                    boolean isDoor = FarmPathfinder.isOpenableDoor(st) && !st.getValue(DoorBlock.OPEN);
                    boolean isGate = FarmPathfinder.isOpenableGate(st) && !st.getValue(FenceGateBlock.OPEN);

                    if (isDoor || isGate) {
                        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(p), Direction.UP, p, false);
                        gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                        doorInteractCooldown = 12;
                        return;
                    }
                }
            }
        }
    }

    private void lookAt(LocalPlayer player, double targetX, double targetY, double targetZ) {
        double dx = targetX - player.getX();
        double dy = targetY - player.getEyeY();
        double dz = targetZ - player.getZ();

        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float targetPitch = (float) (-(Math.atan2(dy, distXZ) * 180.0 / Math.PI));

        player.setYRot(targetYaw);
        player.setXRot(targetPitch);
        player.yHeadRot = targetYaw;
        player.yBodyRot = targetYaw;
    }

    private void smoothLookTowards(LocalPlayer player, float targetYaw, float targetPitch, float maxStep) {
        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();

        float diffYaw = wrapDegrees(targetYaw - currentYaw);
        float stepYaw = Math.max(-maxStep, Math.min(maxStep, diffYaw));
        float newYaw = currentYaw + stepYaw;
        player.setYRot(newYaw);
        player.yHeadRot = newYaw;
        player.yBodyRot = newYaw;

        float diffPitch = targetPitch - currentPitch;
        float stepPitch = Math.max(-maxStep, Math.min(maxStep, diffPitch));
        player.setXRot(currentPitch + stepPitch);
    }

    private static float wrapDegrees(float degrees) {
        float f = degrees % 360.0f;
        if (f >= 180.0f) f -= 360.0f;
        if (f < -180.0f) f += 360.0f;
        return f;
    }

    private void releaseMovement(Minecraft client) {
        if (client == null || client.options == null) return;
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keySprint.setDown(false);
        if (client.player != null) {
            client.player.setSprinting(false);
        }
    }

    private Player findTargetPlayer(ClientLevel level) {
        if (targetPlayerUUID != null) {
            Player p = level.getPlayerByUUID(targetPlayerUUID);
            if (p != null) return p;
        }
        if (targetPlayerName != null) {
            for (Player p : level.players()) {
                if (p.getScoreboardName().equalsIgnoreCase(targetPlayerName) || p.getName().getString().equalsIgnoreCase(targetPlayerName)) {
                    return p;
                }
            }
        }
        return null;
    }

    private Player findPlayerByName(ClientLevel level, String name) {
        for (Player p : level.players()) {
            if (p.getScoreboardName().equalsIgnoreCase(name) || p.getName().getString().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    private void smoothLookAt(LocalPlayer player, double targetX, double targetY, double targetZ, float maxStep) {
        double dx = targetX - player.getX();
        double dy = targetY - player.getEyeY();
        double dz = targetZ - player.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) Math.toDegrees(-Math.atan2(dx, dz));
        float targetPitch = (float) Math.toDegrees(-Math.atan2(dy, distXZ));

        smoothLookTowards(player, targetYaw, targetPitch, maxStep);
    }

    private static int getAxeTier(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        if (!(stack.getItem() instanceof AxeItem)) return -1;
        if (stack.is(Items.NETHERITE_AXE)) return 6;
        if (stack.is(Items.DIAMOND_AXE)) return 5;
        if (stack.is(Items.IRON_AXE)) return 4;
        if (stack.is(Items.STONE_AXE)) return 3;
        if (stack.is(Items.GOLDEN_AXE)) return 2;
        if (stack.is(Items.WOODEN_AXE)) return 1;
        return 1;
    }

    private boolean ensureBestAxeInMainHand(LocalPlayer player, MultiPlayerGameMode gameMode) {
        ItemStack current = player.getMainHandItem();
        int bestSlot = -1;
        int bestTier = getAxeTier(current);

        // فحص شريط الأدوات (Hotbar 0-8)
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

        if (bestTier > 0) {
            return true;
        }

        // فحص الحقيبة (9-35) إن لم يكن هناك أي فأس في شريط الأدوات
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

    private void ensureShieldInOffhand(LocalPlayer player, MultiPlayerGameMode gameMode) {
        if (player.getOffhandItem().is(Items.SHIELD)) return;

        int containerId = player.inventoryMenu.containerId;
        // البحث في شريط الأدوات
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.SHIELD)) {
                gameMode.handleContainerInput(containerId, 36 + i, 40, ContainerInput.SWAP, player);
                return;
            }
        }
        // البحث في الحقيبة
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.SHIELD)) {
                gameMode.handleContainerInput(containerId, i, 40, ContainerInput.SWAP, player);
                return;
            }
        }
    }

    private boolean hasAllowedGoldenApple(LocalPlayer player) {
        BotFoodManager foodMgr = BotFoodManager.getInstance();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE))) {
                if (foodMgr.isAllowed(stack)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean ensureFoodInHand(LocalPlayer player, MultiPlayerGameMode gameMode) {
        BotFoodManager foodMgr = BotFoodManager.getInstance();

        // 1. فحص اليد الرئيسية: هل تحتوي بالفعل على طعام مسموح وصالح للأكل الآن؟
        if (foodMgr.canEatNow(player, player.getMainHandItem())) {
            return true;
        }

        int bestSlot = -1;
        int bestScore = -1;
        // 2. البحث في شريط الأدوات (Hotbar 0-8)
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

        if (bestSlot != -1) {
            player.getInventory().setSelectedSlot(bestSlot);
            if (player.connection != null) {
                player.connection.send(new ServerboundSetCarriedItemPacket(bestSlot));
            }
            return true;
        }

        // 3. البحث في الحقيبة الرئيسية (9-35) ونقله للـ Hotbar
        int invSlot = -1;
        bestScore = -1;
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (foodMgr.canEatNow(player, stack)) {
                int score = foodMgr.getFoodScore(stack);
                if (score > bestScore) {
                    bestScore = score;
                    invSlot = i;
                }
            }
        }

        if (invSlot != -1) {
            int targetHotbar = 0;
            int containerId = player.inventoryMenu.containerId;
            gameMode.handleContainerInput(containerId, invSlot, targetHotbar, ContainerInput.SWAP, player);
            player.getInventory().setSelectedSlot(targetHotbar);
            if (player.connection != null) {
                player.connection.send(new ServerboundSetCarriedItemPacket(targetHotbar));
            }
            return true;
        }

        return false;
    }

    /**
     * الأولوية رقم 1 للبوت: الأكل والعلاج الذاتي التلقائي أثناء الاتباع:
     * - الأصل الأساسي هو شريط الطعام (الجوع) وليس الصحة.
     * - إذا كان شريط الجوع ممتلئاً (20): لا يحاول أكل الطعام العادي نهائياً،
     *   ولا يأكل إلا إذا كان يملك تفاحاً ذهبياً مسموحاً وكانت الصحة ناقصة.
     * - إن كان جائعاً ولم يجد أي طعام مسموح به، لا يأكل ويرسل في الشات "أنا جائع" كل دقيقة.
     */
    private boolean handleFollowEating(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        int food = player.getFoodData().getFoodLevel();
        float health = player.getHealth();
        boolean needsFood = player.getFoodData().needsFood(); // food < 20
        boolean isFullFood = !needsFood; // food >= 20

        // فحص هل البوت يحتاج لتناول طعام أصلاً:
        if (isFullFood) {
            if (health >= 20.0f) {
                stopFollowEating(client, player, gameMode);
                return false;
            }
            // الصحة ناقصة وشريط الجوع ممتلئ تماماً:
            // هل يملك تفاحاً ذهبياً مسموحاً به في الحقيبة؟
            boolean hasGoldenApple = hasAllowedGoldenApple(player);
            if (!hasGoldenApple) {
                // شبعان تماماً ولا يملك تفاحاً ذهبياً: لا يحاول الأكل، بل يعتمد على التجدد الطبيعي السريع
                stopFollowEating(client, player, gameMode);
                return false;
            }
        }

        // محاولة سحب أفضل طعام مسموح لليد الرئيسية
        boolean hasFood = ensureFoodInHand(player, gameMode);
        if (!hasFood) {
            stopFollowEating(client, player, gameMode);

            // إن كان جائعاً ولم يجد أي طعام مسموح به: لا يأكل ويرسل "أنا جائع" كل دقيقة
            if (needsFood) {
                if (hungryAlertCooldown <= 0) {
                    hungryAlertCooldown = 1200; // دقيقة واحدة = 1200 نبضة
                    if (player.connection != null) {
                        player.connection.sendChat("أنا جائع!");
                    }
                    if (client.player != null) {
                        client.player.sendSystemMessage(Component.literal("§c[Bot] أنا جائع ولا أملك أي طعام مسموح به في الحقيبة!"));
                    }
                }
            }
            return false;
        }

        // تحسين نمط الاستهلاك: تصفير عداد الحبة عند ابتلاعها وارتفاع الجوع
        if (isEatingWhileFollowing && lastFollowFoodLevel != -1 && food > lastFollowFoodLevel) {
            followEatingTicks = 0;
        }
        lastFollowFoodLevel = food;

        isEatingWhileFollowing = true;
        followEatingTicks++;

        // تحرير الدرع إذا كان يستخدمه للبدء في الأكل
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
        if (followEatingTicks > 45) {
            followEatingTicks = 0;
            if (!player.getFoodData().needsFood() && player.getHealth() >= 20.0f) {
                stopFollowEating(client, player, gameMode);
                return false;
            }
        }

        return true;
    }

    private void stopFollowEating(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        if (!isEatingWhileFollowing) return;
        isEatingWhileFollowing = false;
        followEatingTicks = 0;
        lastFollowFoodLevel = -1;

        if (player != null && player.isUsingItem()) {
            ItemStack usingItem = player.getUseItem();
            if (!usingItem.is(Items.SHIELD)) {
                if (gameMode != null) gameMode.releaseUsingItem(player);
                player.stopUsingItem();
            }
        }

        if (client != null && client.options != null) {
            client.options.keyUse.setDown(false);
        }

        // إعادة تجهيز الفأس باليد الرئيسية والدرع باليد اليسرى فور الانتهاء من الأكل
        if (player != null && gameMode != null) {
            ensureBestAxeInMainHand(player, gameMode);
            ensureShieldInOffhand(player, gameMode);
        }
    }

    /**
     * التحقق مما إذا كان الدرع تعطل (ضربه فأس) ومحاولة استبداله فوراً بدرع آخر من الحقيبة.
     */
    @SuppressWarnings("unused")
    private void checkShieldDisabledAndSwap(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode) {
        if (shieldSwapCooldown > 0) return;
        if (!player.getCooldowns().isOnCooldown(new ItemStack(Items.SHIELD))) return;

        shieldSwapCooldown = 100; // منع السبام لمدة 5 ثوانٍ
        int containerId = player.inventoryMenu.containerId;

        // البحث في الحقيبة الرئيسية
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.SHIELD)) {
                gameMode.handleContainerInput(containerId, i, 40, ContainerInput.SWAP, player);
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.literal("§e[Bot - Shield] تعطل الدرع بضربة فأس! تم استبداله بدرع احتياطي فوراً."));
                }
                return;
            }
        }
        // البحث في شريط الأدوات
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.SHIELD)) {
                gameMode.handleContainerInput(containerId, 36 + i, 40, ContainerInput.SWAP, player);
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.literal("§e[Bot - Shield] تعطل الدرع بضربة فأس! تم استبداله بدرع احتياطي فوراً."));
                }
                return;
            }
        }
    }

    @SuppressWarnings("unused")
    private Projectile findIncomingProjectile(ClientLevel level, LocalPlayer player, Player companion) {
        AABB searchBox = player.getBoundingBox().inflate(14.0);
        List<Projectile> projectiles = level.getEntitiesOfClass(Projectile.class, searchBox, p -> p.isAlive() && !p.isRemoved());

        for (Projectile proj : projectiles) {
            Vec3 vel = proj.getDeltaMovement();
            if (vel.lengthSqr() < 0.05) continue;

            Vec3 toBot = player.getEyePosition().subtract(proj.position());
            double distToBot = toBot.length();
            if (distToBot <= 12.0) {
                double dot = vel.normalize().dot(toBot.normalize());
                if (dot > 0.55) {
                    return proj;
                }
            }

            if (companion != null) {
                Vec3 toComp = companion.getEyePosition().subtract(proj.position());
                double distToComp = toComp.length();
                if (distToComp <= 10.0) {
                    double dot = vel.normalize().dot(toComp.normalize());
                    if (dot > 0.60) {
                        return proj;
                    }
                }
            }
        }
        return null;
    }

    public static Vec3 calculateCreeperEscapeDirection(ClientLevel level, LocalPlayer player, Vec3 creeperPos) {
        double awayX = player.getX() - creeperPos.x;
        double awayZ = player.getZ() - creeperPos.z;
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

        // قياس المسافة بالأمتار في الاتجاه المعاكس المباشر
        double distDirect = FightBotController.measureDistanceToObstacle(level, player, awayX, awayZ, 15.0);
        if (distDirect >= 10.0) {
            return new Vec3(awayX, 0, awayZ);
        }

        // إذا كان هناك جدار أو عائق (< 10 بلوكات)، فحص الزوايا لاختيار أطول مسار سالك وخالٍ من العوائق
        double baseAngle = Math.atan2(awayZ, awayX);
        double bestDist = distDirect;
        Vec3 bestDir = new Vec3(awayX, 0, awayZ);

        for (int i = 1; i <= 8; i++) {
            double offset = i * (Math.PI / 12.0); // 15 درجة لكل خطوة
            // فحص جهة اليمين
            double angleR = baseAngle + offset;
            double dirXR = Math.cos(angleR);
            double dirZR = Math.sin(angleR);
            double distR = FightBotController.measureDistanceToObstacle(level, player, dirXR, dirZR, 15.0);
            if (distR > bestDist) {
                bestDist = distR;
                bestDir = new Vec3(dirXR, 0, dirZR);
                if (bestDist >= 10.0) break;
            }
            // فحص جهة اليسار
            double angleL = baseAngle - offset;
            double dirXL = Math.cos(angleL);
            double dirZL = Math.sin(angleL);
            double distL = FightBotController.measureDistanceToObstacle(level, player, dirXL, dirZL, 15.0);
            if (distL > bestDist) {
                bestDist = distL;
                bestDir = new Vec3(dirXL, 0, dirZL);
                if (bestDist >= 10.0) break;
            }
        }

        return bestDir;
    }

    @SuppressWarnings("unused")
    private boolean handleCreeperCombat(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode, Player companion, net.minecraft.world.entity.monster.Creeper creeper) {
        // إذا مات الكريبر أو اختفى أو انفجر:
        if (creeper == null || !creeper.isAlive() || creeper.isRemoved() || creeper.isDeadOrDying() || creeper.getHealth() <= 0) {
            if (creeper != null) {
                hostileTargets.remove(creeper.getUUID());
                if (currentTargetUUID != null && currentTargetUUID.equals(creeper.getUUID())) {
                    currentTargetUUID = null;
                }
            }
            creeperState = CreeperTacticState.NONE;
            creeperFleeStartPos = null;
            creeperFleeDir = null;
            creeperFleeTicks = 0;
            creeperAssessTicks = 0;
            releaseMovement(client);
            if (player.isUsingItem() && player.getUseItem().is(Items.SHIELD)) {
                gameMode.releaseUsingItem(player);
                player.stopUsingItem();
            }
            client.options.keyUse.setDown(false);
            return false;
        }

        // فحص مسافة الـ 20 بلوكة عن القائد:
        double distToCompanion = player.distanceTo(companion);
        if (distToCompanion > 20.0) {
            creeperState = CreeperTacticState.NONE;
            releaseMovement(client);
            if (tetherAlertCooldown <= 0) {
                client.player.sendSystemMessage(Component.literal("§e[Bot - Bodyguard] تجاوزت المسافة 20 بلوكة عن القائد! التوقف عن مطاردة الكريبر والعودة للقائد..."));
                tetherAlertCooldown = 100;
            }
            smartNavigateTo(client, player, level, gameMode, companion.position(), true, 2.5, false);
            return true;
        }

        // إذا لم تبدأ الحالة بعد:
        if (creeperState == CreeperTacticState.NONE) {
            creeperState = CreeperTacticState.APPROACH_AND_STRIKE;
        }

        double distToCreeper = player.distanceTo(creeper);

        // ==========================================
        // الحالة 1: الاقتراب والقفز والضرب بالفأس
        // ==========================================
        if (creeperState == CreeperTacticState.APPROACH_AND_STRIKE) {
            ensureBestAxeInMainHand(player, gameMode);
            // خفض الدرع حتى لا يعيق سرعة البوت وضربته
            if (client.options.keyUse.isDown()) {
                client.options.keyUse.setDown(false);
            }
            if (player.isUsingItem() && player.getUseItem().is(Items.SHIELD)) {
                gameMode.releaseUsingItem(player);
                player.stopUsingItem();
            }

            smoothLookAt(player, creeper.getX(), creeper.getEyeY() - 0.15, creeper.getZ(), 60.0f);

            @SuppressWarnings("unused")
            float charge = player.getAttackStrengthScale(0.0f);
            boolean isSwelling = creeper.getSwellDir() > 0;

            if (distToCreeper <= 3.2) {
                if (player.onGround() && !isJumpingForCrit) {
                    // القفز للأعلى لتنفيذ ضربة حرجة حاسمة
                    isJumpingForCrit = true;
                    jumpCritTicks = 0;
                    client.options.keyJump.setDown(true);
                    client.options.keyUp.setDown(distToCreeper > 1.8);
                } else {
                    jumpCritTicks++;
                    boolean isFalling = player.getDeltaMovement().y < -0.01;
                    boolean jumpTimeout = jumpCritTicks > 12;

                    // الهجوم في لحظة الهبوط أو إذا كان الكريبر ينتفخ بسرعة
                    if (isFalling || jumpTimeout || isSwelling) {
                        smoothLookAt(player, creeper.getX(), creeper.getEyeY() - 0.15, creeper.getZ(), 90.0f);
                        gameMode.attack(player, creeper);
                        player.swing(InteractionHand.MAIN_HAND);

                        // التحقق هل مات الكريبر من الضربة:
                        if (!creeper.isAlive() || creeper.isRemoved() || creeper.isDeadOrDying() || creeper.getHealth() <= 0) {
                            hostileTargets.remove(creeper.getUUID());
                            if (currentTargetUUID != null && currentTargetUUID.equals(creeper.getUUID())) {
                                currentTargetUUID = null;
                            }
                            creeperState = CreeperTacticState.NONE;
                            releaseMovement(client);
                            return false;
                        }

                        // فوراً في نفس اللحظة الانتقال لحالة الهروب الأقصى لمسافة 10 بلوكات!
                        creeperState = CreeperTacticState.FLEE_SPRINT_JUMP;
                        creeperFleeStartPos = player.position();
                        creeperFleeDir = calculateCreeperEscapeDirection(level, player, creeper.position());
                        creeperFleeTicks = 0;
                        isJumpingForCrit = false;
                        jumpCritTicks = 0;
                    }
                }
            } else {
                // الكريبر أبعد من 3.2 بلوكات: الركض السريع نحوه بالفأس
                client.options.keyDown.setDown(false);
                client.options.keyUp.setDown(true);
                client.options.keySprint.setDown(true);
                player.setSprinting(true);
                if (moveJumpCooldown <= 0 && isObstacleDirectlyAhead(player, level, creeper.getX(), creeper.getZ()) && player.onGround() && isCeilingClear(level, player.blockPosition())) {
                    client.options.keyJump.setDown(true);
                    moveJumpCooldown = 10;
                } else {
                    client.options.keyJump.setDown(false);
                }
                isJumpingForCrit = false;
                jumpCritTicks = 0;
            }
            return true;
        }

        // ==========================================
        // الحالة 2: الركض والقفز بأقصى سرعة لمسافة 10 بلوكات في الجهة المعاكسة
        // ==========================================
        if (creeperState == CreeperTacticState.FLEE_SPRINT_JUMP) {
            if (creeperFleeStartPos == null || creeperFleeDir == null) {
                creeperFleeStartPos = player.position();
                creeperFleeDir = calculateCreeperEscapeDirection(level, player, creeper.position());
            }

            double fledDist = player.position().distanceTo(creeperFleeStartPos);
            creeperFleeTicks++;

            // خفض الدرع والأكل تماماً لتحقيق أقصى سرعة ممكنة
            if (client.options.keyUse.isDown()) {
                client.options.keyUse.setDown(false);
            }
            if (player.isUsingItem()) {
                gameMode.releaseUsingItem(player);
                player.stopUsingItem();
            }

            // توجيه وجه ورأس وجسد اللاعب نحو مسار الهروب المعاكس تماماً
            float runYaw = (float) (Math.atan2(creeperFleeDir.z, creeperFleeDir.x) * 180.0 / Math.PI) - 90.0f;
            player.setYRot(runYaw);
            player.yHeadRot = runYaw;
            player.yBodyRot = runYaw;
            player.setXRot(0.0f);

            // تفعيل الركض والقفز بنبضات محسوبة للهروب السريع دون التعليق في الجدران
            client.options.keyUp.setDown(true);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keySprint.setDown(true);
            player.setSprinting(true);

            if (player.onGround() && moveJumpCooldown <= 0 && isCeilingClear(level, player.blockPosition())) {
                client.options.keyJump.setDown(true);
                moveJumpCooldown = 8;
            } else {
                client.options.keyJump.setDown(false);
            }

            // فحص قطع مسافة الـ 10 بلوكات:
            if (fledDist >= 10.0 || distToCreeper >= 12.0 || creeperFleeTicks > 45) {
                // إيقاف الهروب والانتقال للتقييم والنظر
                client.options.keyUp.setDown(false);
                client.options.keyJump.setDown(false);
                client.options.keySprint.setDown(false);
                player.setSprinting(false);
                creeperState = CreeperTacticState.ASSESS;
                creeperAssessTicks = 0;
            }
            return true;
        }

        // ==========================================
        // الحالة 3: التقييم والنظر نحو الكريبر
        // ==========================================
        if (creeperState == CreeperTacticState.ASSESS) {
            client.options.keyUp.setDown(false);
            client.options.keyJump.setDown(false);
            client.options.keySprint.setDown(false);
            player.setSprinting(false);

            smoothLookAt(player, creeper.getX(), creeper.getEyeY() - 0.15, creeper.getZ(), 85.0f);

            // فحص هل الكريبر ما زال حياً؟
            if (!creeper.isAlive() || creeper.isRemoved() || creeper.isDeadOrDying() || creeper.getHealth() <= 0) {
                hostileTargets.remove(creeper.getUUID());
                if (currentTargetUUID != null && currentTargetUUID.equals(creeper.getUUID())) {
                    currentTargetUUID = null;
                }
                creeperState = CreeperTacticState.NONE;
                releaseMovement(client);
                return false;
            }

            // الكريبر لا زال حياً: الانتظار بضع تكات لاكتمال شحن الفأس ثم العودة وتكرار الهجوم!
            creeperAssessTicks++;
            float charge = player.getAttackStrengthScale(0.0f);
            if (charge >= 0.90f || creeperAssessTicks >= 10) {
                creeperState = CreeperTacticState.APPROACH_AND_STRIKE;
                creeperAssessTicks = 0;
            }
            return true;
        }

        return false;
    }
}
