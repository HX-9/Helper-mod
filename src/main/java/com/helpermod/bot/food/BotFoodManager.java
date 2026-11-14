package com.helpermod.bot.food;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.helpermod.HelperMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class BotFoodManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // قائمة الأطعمة الافتراضية الصحية الخالية تماماً من لحم الزومبي أو الأطعمة السامة
    private static final List<String> DEFAULT_FOODS = List.of(
            "cooked_beef",
            "cooked_porkchop",
            "cooked_mutton",
            "cooked_chicken",
            "cooked_salmon",
            "cooked_cod",
            "baked_potato",
            "bread",
            "golden_carrot",
            "golden_apple",
            "enchanted_golden_apple",
            "apple",
            "carrot"
    );

    // قاموس الأطعمة المعروفة وأسمائها المعربة لسهولة الاقتراح والعرض
    public static final Map<String, String> FOOD_NAMES = new LinkedHashMap<>();
    static {
        FOOD_NAMES.put("cooked_beef", "ستيك / لحم بقر مطبوخ");
        FOOD_NAMES.put("cooked_porkchop", "لحم خنزير مطبوخ");
        FOOD_NAMES.put("cooked_mutton", "لحم غنم مطبوخ");
        FOOD_NAMES.put("cooked_chicken", "دجاج مطبوخ");
        FOOD_NAMES.put("cooked_salmon", "سلمون مطبوخ");
        FOOD_NAMES.put("cooked_cod", "سمك قد مطبوخ");
        FOOD_NAMES.put("baked_potato", "بطاطس مشوية");
        FOOD_NAMES.put("bread", "خبز");
        FOOD_NAMES.put("golden_carrot", "جزر ذهبي");
        FOOD_NAMES.put("golden_apple", "تفاح ذهبي");
        FOOD_NAMES.put("enchanted_golden_apple", "تفاح ذهبي مطور (Notch Apple)");
        FOOD_NAMES.put("apple", "تفاح");
        FOOD_NAMES.put("carrot", "جزر");
        FOOD_NAMES.put("cooked_rabbit", "أرنب مطبوخ");
        FOOD_NAMES.put("pumpkin_pie", "فطيرة قرع");
        FOOD_NAMES.put("cookie", "كعك");
        FOOD_NAMES.put("melon_slice", "شريحة بطيخ");
        FOOD_NAMES.put("sweet_berries", "توت حلو");
        FOOD_NAMES.put("glow_berries", "توت مشع");
        FOOD_NAMES.put("mushroom_stew", "حساء فطر");
        FOOD_NAMES.put("rabbit_stew", "حساء أرنب");
        FOOD_NAMES.put("beetroot_soup", "حساء شمندر");
        FOOD_NAMES.put("honey_bottle", "قارورة عسل");
        FOOD_NAMES.put("dried_kelp", "عشب بحري مجفف");
    }

    private static final BotFoodManager INSTANCE = new BotFoodManager();

    private final Set<String> allowedFoods = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    private BotFoodManager() {
        load();
    }

    public static BotFoodManager getInstance() {
        return INSTANCE;
    }

    public enum AddFoodResult {
        ADDED,
        ALREADY_EXISTS,
        INVALID
    }

    public synchronized AddFoodResult addFoodChecked(String rawName) {
        if (rawName == null || rawName.isBlank()) return AddFoodResult.INVALID;
        String normalized = normalizeFoodName(rawName);
        if (!isValidFoodName(normalized)) {
            return AddFoodResult.INVALID;
        }
        boolean added = allowedFoods.add(normalized);
        if (added) {
            save();
            return AddFoodResult.ADDED;
        }
        return AddFoodResult.ALREADY_EXISTS;
    }

    public synchronized boolean addFood(String rawName) {
        return addFoodChecked(rawName) == AddFoodResult.ADDED;
    }

    /**
     * التحقق من أن الاسم يطابق طعاماً فعلياً مسجلاً في اللعبة.
     */
    public static boolean isValidFoodName(String rawName) {
        if (rawName == null || rawName.isBlank()) return false;
        String normalized = normalizeFoodName(rawName);
        if (normalized.isBlank() || normalized.contains(" ")) return false;
        if (FOOD_NAMES.containsKey(normalized)) return true;

        Identifier id = normalized.contains(":")
            ? Identifier.tryParse(normalized)
            : Identifier.tryParse("minecraft:" + normalized);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return false;
        }
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return item != null && item != Items.AIR && item.components().has(DataComponents.FOOD);
    }

    /**
     * التحقق من وجود أي طعام مسموح وصالح للأكل حالياً في حقيبة اللاعب.
     */
    public synchronized boolean hasEdibleAllowedFood(LocalPlayer player) {
        if (player == null) return false;
        for (int i = 0; i < 36; i++) {
            if (canEatNow(player, player.getInventory().getItem(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * العثور على أفضل طعام مسموح وصالح للأكل في الهوتبار أو نقله من الحقيبة إلى خانة محددة.
     */
    public synchronized int ensureBestFoodInHotbar(
            LocalPlayer player,
            MultiPlayerGameMode gameMode,
            int fallbackHotbarSlot,
            int avoidHotbarSlot) {
        if (player == null) return -1;

        int bestHotbarSlot = -1;
        int bestHotbarScore = -1;
        for (int i = 0; i < 9; i++) {
            if (i == avoidHotbarSlot) continue;
            ItemStack stack = player.getInventory().getItem(i);
            if (canEatNow(player, stack)) {
                int score = getFoodScore(stack);
                if (score > bestHotbarScore) {
                    bestHotbarScore = score;
                    bestHotbarSlot = i;
                }
            }
        }
        if (bestHotbarSlot != -1) {
            return bestHotbarSlot;
        }

        if (gameMode != null) {
            int invBestSlot = -1;
            int invBestScore = -1;
            for (int i = 9; i < 36; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (canEatNow(player, stack)) {
                    int score = getFoodScore(stack);
                    if (score > invBestScore) {
                        invBestScore = score;
                        invBestSlot = i;
                    }
                }
            }
            if (invBestSlot != -1) {
                int containerId = player.inventoryMenu.containerId;
                gameMode.handleContainerInput(containerId, invBestSlot, fallbackHotbarSlot, ContainerInput.SWAP, player);
                return fallbackHotbarSlot;
            }
        }

        if (avoidHotbarSlot >= 0 && avoidHotbarSlot < 9) {
            ItemStack avoided = player.getInventory().getItem(avoidHotbarSlot);
            if (canEatNow(player, avoided)) {
                return avoidHotbarSlot;
            }
        }

        return -1;
    }

    public synchronized boolean removeFood(String rawName) {
        if (rawName == null || rawName.isBlank()) return false;
        String normalized = normalizeFoodName(rawName);
        boolean removed = allowedFoods.remove(normalized);
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized void clearFoods() {
        allowedFoods.clear();
        save();
    }

    public synchronized boolean isAllowed(String rawName) {
        if (rawName == null || rawName.isBlank()) return false;
        String normalized = normalizeFoodName(rawName);
        return allowedFoods.contains(normalized);
    }

    public synchronized boolean isAllowed(Item item) {
        if (item == null) return false;
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        return allowedFoods.contains(path);
    }

    public synchronized boolean isAllowed(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return isAllowed(stack.getItem());
    }

    /**
     * التحقق مما إذا كان الطعام صالحاً وممكناً للأكل في هذه اللحظة بناءً على حالة اللاعب:
     * - الأصل في ماينكرافت هو شريط الطعام (الجوع) وليس الصحة.
     * - إذا كان شريط الجوع ناقصاً (< 20): يمكن أكل أي طعام مسموح به.
     * - إذا كان شريط الجوع ممتلئاً (20): لا يمكن أكل الطعام العادي نهائياً،
     *   والاستثناء الوحيد هو التفاح الذهبي لعلاج الصحة الناقصة.
     */
    public synchronized boolean canEatNow(LocalPlayer player, ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) return false;
        if (!isAllowed(stack)) return false;

        boolean isGolden = stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE);
        player.getFoodData().getFoodLevel();
        boolean needsFood = player.getFoodData().needsFood(); // foodLevel < 20

        if (needsFood) {
            return true;
        }

        // إذا كان شريط الطعام ممتلئاً (20):
        // فقط التفاح الذهبي يمكن تناوله لعلاج الصحة
        if (isGolden && player.getHealth() < 20.0f) {
            return true;
        }

        return false;
    }

    /**
     * ترتيب أفضلية الطعام المسموح به
     */
    public int getFoodScore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        if (!isAllowed(stack)) return -1;

        if (stack.is(Items.ENCHANTED_GOLDEN_APPLE)) return 100;
        if (stack.is(Items.GOLDEN_APPLE)) return 90;
        if (stack.is(Items.GOLDEN_CARROT)) return 85;
        if (stack.is(Items.COOKED_BEEF) || stack.is(Items.COOKED_PORKCHOP)) return 80;
        if (stack.is(Items.COOKED_MUTTON) || stack.is(Items.COOKED_SALMON)) return 75;
        if (stack.is(Items.COOKED_CHICKEN)) return 70;
        if (stack.is(Items.BAKED_POTATO) || stack.is(Items.BREAD) || stack.is(Items.COOKED_COD)) return 65;
        if (stack.is(Items.CARROT) || stack.is(Items.APPLE)) return 50;

        return 40;
    }

    public synchronized Collection<String> getAllowedFoods() {
        return Collections.unmodifiableSet(new TreeSet<>(allowedFoods));
    }

    public String getDisplayName(String foodKey) {
        String normalized = normalizeFoodName(foodKey);
        String name = FOOD_NAMES.get(normalized);
        if (name != null) {
            return normalized + " (" + name + ")";
        }
        return normalized;
    }

    public static String normalizeFoodName(String raw) {
        String clean = raw.trim().toLowerCase();
        if (clean.startsWith("minecraft:")) {
            clean = clean.substring("minecraft:".length());
        }
        // مرادفات عربية شائعة
        return switch (clean) {
            case "ستيك", "لحم_بقر", "لحم_بقر_مطبوخ" -> "cooked_beef";
            case "لحم_خنزير", "لحم_خنزير_مطبوخ" -> "cooked_porkchop";
            case "لحم_غنم", "لحم_غنم_مطبوخ" -> "cooked_mutton";
            case "دجاج", "دجاج_مطبوخ" -> "cooked_chicken";
            case "سلمون", "سلمون_مطبوخ" -> "cooked_salmon";
            case "سمك", "سمك_قد", "سمك_مطبوخ" -> "cooked_cod";
            case "بطاطس", "بطاطا", "بطاطس_مشوية", "بطاطا_مشوية" -> "baked_potato";
            case "خبز" -> "bread";
            case "جزر_ذهبي" -> "golden_carrot";
            case "تفاح_ذهبي" -> "golden_apple";
            case "تفاح_ذهبي_مطور", "نوتش" -> "enchanted_golden_apple";
            case "تفاح" -> "apple";
            case "جزر" -> "carrot";
            case "توت", "توت_حلو" -> "sweet_berries";
            case "عسل" -> "honey_bottle";
            default -> clean;
        };
    }

    private Path getConfigFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("helpermod_allowed_foods.json");
    }

    public synchronized void save() {
        try {
            Path file = getConfigFile();
            JsonObject root = new JsonObject();
            JsonArray array = new JsonArray();
            for (String food : allowedFoods) {
                array.add(food);
            }
            root.add("allowed_foods", array);

            Files.createDirectories(file.getParent());
            Path tempFile = file.resolveSibling(file.getFileName().toString() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tempFile)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(tempFile, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException e) {
                Files.move(tempFile, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            HelperMod.LOGGER.error("Failed to save helpermod_allowed_foods.json", e);
        }
    }

    public synchronized void load() {
        Path file = getConfigFile();
        if (!Files.exists(file)) {
            // تهيئة القائمة بالخيارات الافتراضية الصحية
            allowedFoods.clear();
            if (DEFAULT_FOODS != null) {
                allowedFoods.addAll(DEFAULT_FOODS);
            }
            save();
            return;
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root != null && root.has("allowed_foods")) {
                allowedFoods.clear();
                JsonArray array = root.getAsJsonArray("allowed_foods");
                for (JsonElement el : array) {
                    if (el != null && !el.getAsString().isBlank()) {
                        allowedFoods.add(normalizeFoodName(el.getAsString()));
                    }
                }
            }
        } catch (Exception e) {
            HelperMod.LOGGER.error("Failed to load helpermod_allowed_foods.json", e);
            if (allowedFoods.isEmpty() && DEFAULT_FOODS != null) {
                allowedFoods.addAll(DEFAULT_FOODS);
            }
        }
    }
}
