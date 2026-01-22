package net.dagger.lootrush.service;

import net.dagger.lootrush.game.Role;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RoleService {
    private final LanguageService languageService;
    private final Map<UUID, Role> playerRoles = new HashMap<>();

    public RoleService(LanguageService languageService) {
        this.languageService = languageService;
    }

    public Role getRole(ServerPlayer player) {
        return playerRoles.getOrDefault(player.getUUID(), Role.PLAYER);
    }

    public void setRole(ServerPlayer player, Role role) {
        LanguageService.Language lang = languageService.getLanguage(player);
        if (role == Role.PLAYER) {
            playerRoles.remove(player.getUUID());
            player.setGameMode(GameType.SURVIVAL);
            player.sendSystemMessage(Messages.get(lang, Messages.MessageKey.NOW_PARTICIPATING));
        } else {
            playerRoles.put(player.getUUID(), Role.SPECTATOR);
            player.setGameMode(GameType.SPECTATOR);
            player.sendSystemMessage(Messages.get(lang, Messages.MessageKey.NOW_SPECTATOR));
        }
    }

    public void prepareSpectators(Collection<? extends ServerPlayer> spectators) {
        for (ServerPlayer spectator : spectators) {
            spectator.setGameMode(GameType.SPECTATOR);
            LanguageService.Language lang = languageService.getLanguage(spectator);
            spectator.sendSystemMessage(Messages.get(lang, Messages.MessageKey.SPECTATING_ROUND));
        }
    }
}
