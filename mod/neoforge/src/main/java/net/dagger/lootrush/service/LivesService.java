package net.dagger.lootrush.service;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LivesService {
    private static final int MAX_LIVES = 5;
    private final Map<UUID, Integer> playerLives = new HashMap<>();

    public void initializeLives(List<ServerPlayer> participants) {
        playerLives.clear();
        for (ServerPlayer player : participants) {
            playerLives.put(player.getUUID(), MAX_LIVES);
        }
    }

    public int getLives(ServerPlayer player) {
        return playerLives.getOrDefault(player.getUUID(), MAX_LIVES);
    }

    public int decreaseLives(ServerPlayer player) {
        int lives = getLives(player);
        lives--;
        playerLives.put(player.getUUID(), lives);
        return lives;
    }

    public boolean hasLives(ServerPlayer player) {
        return getLives(player) > 0;
    }

    public void removePlayer(ServerPlayer player) {
        playerLives.remove(player.getUUID());
    }

    public void clear() {
        playerLives.clear();
    }

    public Map<UUID, Integer> getAllLives() {
        return new HashMap<>(playerLives);
    }

    public static int getMaxLives() {
        return MAX_LIVES;
    }
}
