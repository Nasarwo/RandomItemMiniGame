package net.dagger.lootrush.service;

import net.dagger.lootrush.game.GameState;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;

public class TimerService {
    private final ScoreboardService scoreboardService;
    private long gameStartTime;
    private GameState currentState;
    public TimerService(ScoreboardService scoreboardService) {
        this.scoreboardService = scoreboardService;
    }

    public void start(long startTime, GameState state) {
        this.gameStartTime = startTime;
        this.currentState = state;
    }

    public void tick(ServerScoreboard scoreboard, Collection<ServerPlayer> viewers) {
        if (currentState != GameState.ACTIVE || scoreboard == null) {
            return;
        }

        long elapsed = System.currentTimeMillis() - gameStartTime;
        long seconds = elapsed / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;

        String timeString = String.format("%02d:%02d", minutes, seconds);
        scoreboardService.updateTimer(scoreboard, timeString, viewers);
    }

    public void updateState(GameState state) {
        this.currentState = state;
    }

    public void cancel() {
        currentState = GameState.IDLE;
    }
}
