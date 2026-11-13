package com.helpermod.bot.friend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.helpermod.HelperMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

public class BotFriendManager {

    private static final BotFriendManager INSTANCE = new BotFriendManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Set<String> friends = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    private BotFriendManager() {
        load();
    }

    public static BotFriendManager getInstance() {
        return INSTANCE;
    }

    public synchronized boolean addFriend(String name) {
        if (name == null || name.isBlank()) return false;
        boolean added = friends.add(name.trim());
        if (added) {
            save();
        }
        return added;
    }

    public synchronized boolean removeFriend(String name) {
        if (name == null || name.isBlank()) return false;
        boolean removed = friends.remove(name.trim());
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized boolean isFriend(String name) {
        if (name == null || name.isBlank()) return false;
        return friends.contains(name.trim());
    }

    public synchronized boolean isFriend(net.minecraft.world.entity.player.Player player) {
        if (player == null) return false;
        String name1 = player.getScoreboardName();
        String name2 = player.getName().getString();
        String name3 = player.getGameProfile() != null ? player.getGameProfile().name() : null;
        return isFriend(name1) || isFriend(name2) || isFriend(name3);
    }

    public synchronized Collection<String> getFriends() {
        return Collections.unmodifiableSet(new TreeSet<>(friends));
    }

    private Path getConfigFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("helpermod_friends.json");
    }

    public synchronized void save() {
        try {
            Path file = getConfigFile();
            JsonObject root = new JsonObject();
            JsonArray array = new JsonArray();
            for (String friend : friends) {
                array.add(friend);
            }
            root.add("friends", array);

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
            HelperMod.LOGGER.error("Failed to save helpermod_friends.json", e);
        }
    }

    public synchronized void load() {
        Path file = getConfigFile();
        if (!Files.exists(file)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root != null && root.has("friends")) {
                friends.clear();
                JsonArray array = root.getAsJsonArray("friends");
                for (JsonElement el : array) {
                    if (el.isJsonPrimitive()) {
                        friends.add(el.getAsString().trim());
                    }
                }
            }
        } catch (Exception e) {
            HelperMod.LOGGER.error("Failed to load helpermod_friends.json", e);
        }
    }
}
