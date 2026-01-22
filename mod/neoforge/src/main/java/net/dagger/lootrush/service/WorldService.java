package net.dagger.lootrush.service;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.gamerules.GameRules;

public class WorldService {
    private final MinecraftServer server;

    public WorldService(MinecraftServer server) {
        this.server = server;
    }

    public void setWorldStateForLoading() {
        if (server == null) return;
        server.setDifficulty(Difficulty.PEACEFUL, true);
        for (ServerLevel level : server.getAllLevels()) {
            level.setDayTime(0);
            level.getGameRules().set(GameRules.ADVANCE_TIME, true, server);
            level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
        }
    }

    public void setSafetyBorder() {
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            BlockPos spawnPos = level.getLevelData().getRespawnData().pos();
            level.getWorldBorder().setCenter(spawnPos.getX(), spawnPos.getZ());
            level.getWorldBorder().setSize(96);
        }
    }

    public void resetBorder() {
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            BlockPos spawnPos = level.getLevelData().getRespawnData().pos();
            level.getWorldBorder().setCenter(spawnPos.getX(), spawnPos.getZ());
            level.getWorldBorder().setSize(60000000); // Default max size
        }
    }

    public void setWorldStateActive() {
        if (server == null) return;
        server.setDifficulty(Difficulty.NORMAL, true);
    }

    public void setWorldStateAfterGame() {
        if (server == null) return;
        server.setDifficulty(Difficulty.PEACEFUL, true);
        for (ServerLevel level : server.getAllLevels()) {
            level.setDayTime(0);
            level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
            level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
        }
        resetAdvancements();
    }

    private void resetAdvancements() {
        if (server == null) {
            return;
        }
        var advancements = server.getAdvancements().getAllAdvancements();
        for (var player : server.getPlayerList().getPlayers()) {
            for (var advancement : advancements) {
                var progress = player.getAdvancements().getOrStartProgress(advancement);
                for (String criterion : progress.getCompletedCriteria()) {
                    progress.revokeProgress(criterion);
                }
            }
        }
    }
}
