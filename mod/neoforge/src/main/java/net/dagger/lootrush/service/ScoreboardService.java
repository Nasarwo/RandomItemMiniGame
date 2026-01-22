package net.dagger.lootrush.service;

import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ScoreboardService {
    private final LanguageService languageService;
    private static final String OBJECTIVE_PREFIX = "lootrush_";
    private final Map<UUID, Objective> playerObjectives = new HashMap<>();
    private final Map<UUID, String> lastTimerKeys = new HashMap<>();

    public ScoreboardService(LanguageService languageService) {
        this.languageService = languageService;
    }

    public void createScoreboard(ServerScoreboard scoreboard, Map<UUID, Integer> playerLives, Collection<ServerPlayer> viewers) {
        clear(scoreboard, viewers);
        for (ServerPlayer viewer : viewers) {
            createPlayerScoreboard(scoreboard, viewer, playerLives, viewers);
        }
    }

    public void addViewer(ServerScoreboard scoreboard, ServerPlayer viewer, Map<UUID, Integer> playerLives, Collection<ServerPlayer> allPlayers) {
        if (playerObjectives.containsKey(viewer.getUUID())) {
            return;
        }
        createPlayerScoreboard(scoreboard, viewer, playerLives, allPlayers);
    }

    private void createPlayerScoreboard(ServerScoreboard scoreboard, ServerPlayer viewer, Map<UUID, Integer> playerLives, Collection<ServerPlayer> allPlayers) {
        LanguageService.Language playerLang = languageService.getLanguage(viewer);
        String objectiveName = OBJECTIVE_PREFIX + viewer.getUUID().toString().substring(0, 12);
        Objective objective = scoreboard.addObjective(
                objectiveName,
                ObjectiveCriteria.DUMMY,
                Messages.get(playerLang, Messages.MessageKey.LIVES),
                ObjectiveCriteria.RenderType.INTEGER,
                true,
                null
        );
        playerObjectives.put(viewer.getUUID(), objective);
        viewer.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));

        updateViewerLives(scoreboard, objective, playerLives, allPlayers);
    }

    private void updateViewerLives(ServerScoreboard scoreboard, Objective objective, Map<UUID, Integer> playerLives, Collection<ServerPlayer> allPlayers) {
        for (ServerPlayer player : allPlayers) {
            Integer lives = playerLives.get(player.getUUID());
            if (lives != null) {
                scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(player.getScoreboardName()), objective).set(lives);
            }
        }
    }

    public void updateAllPlayersLives(ServerScoreboard scoreboard, Map<UUID, Integer> playerLives, Collection<ServerPlayer> allPlayers) {
        for (Objective objective : playerObjectives.values()) {
            updateViewerLives(scoreboard, objective, playerLives, allPlayers);
        }
    }

    public void updatePlayerLives(ServerScoreboard scoreboard, ServerPlayer player, int lives) {
        for (Objective objective : playerObjectives.values()) {
            scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(player.getScoreboardName()), objective).set(lives);
        }
    }

    public void updateTimer(ServerScoreboard scoreboard, String timeString, Collection<ServerPlayer> viewers) {
        for (ServerPlayer viewer : viewers) {
            Objective objective = playerObjectives.get(viewer.getUUID());
            if (objective == null) {
                continue;
            }
            String lastKey = lastTimerKeys.get(viewer.getUUID());
            if (lastKey != null) {
                scoreboard.resetSinglePlayerScore(ScoreHolder.forNameOnly(lastKey), objective);
            }

            LanguageService.Language lang = languageService.getLanguage(viewer);
            String timerKey = Messages.getString(lang, Messages.MessageKey.TIME)
                    + net.minecraft.ChatFormatting.YELLOW + timeString;
            lastTimerKeys.put(viewer.getUUID(), timerKey);
            scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(timerKey), objective).set(999);
        }
    }

    public void removePlayer(ServerScoreboard scoreboard, ServerPlayer player) {
        Objective objective = playerObjectives.remove(player.getUUID());
        lastTimerKeys.remove(player.getUUID());
        if (objective != null) {
            scoreboard.removeObjective(objective);
        }
        player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, null));
        for (Objective obj : playerObjectives.values()) {
            scoreboard.resetSinglePlayerScore(ScoreHolder.forNameOnly(player.getScoreboardName()), obj);
        }
    }

    public void clear(ServerScoreboard scoreboard, Collection<ServerPlayer> viewers) {
        for (ServerPlayer player : viewers) {
            player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, null));
        }
        for (Objective objective : playerObjectives.values()) {
            scoreboard.removeObjective(objective);
        }
        playerObjectives.clear();
        lastTimerKeys.clear();
    }
}
