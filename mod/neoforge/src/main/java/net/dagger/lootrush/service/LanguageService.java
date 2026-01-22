package net.dagger.lootrush.service;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class LanguageService {
    public enum Language {
        RU("ru"),
        EN("en"),
        UK("uk");

        private final String code;

        Language(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }

        public static Language fromCode(String code) {
            if (code == null) {
                return RU;
            }
            String lowerCode = code.toLowerCase(Locale.ROOT);
            if ("en".equals(lowerCode) || "english".equals(lowerCode)) {
                return EN;
            }
            if ("uk".equals(lowerCode) || "ukrainian".equals(lowerCode) || "ua".equals(lowerCode)) {
                return UK;
            }
            return RU;
        }
    }

    private final Map<UUID, Language> playerLanguages = new HashMap<>();
    private Language defaultLanguage = Language.RU;

    public Language getLanguage(ServerPlayer player) {
        return playerLanguages.getOrDefault(player.getUUID(), defaultLanguage);
    }

    public Language getLanguage(UUID playerId) {
        return playerLanguages.getOrDefault(playerId, defaultLanguage);
    }

    public void setLanguage(ServerPlayer player, Language language) {
        playerLanguages.put(player.getUUID(), language);
    }

    public void setLanguage(UUID playerId, Language language) {
        playerLanguages.put(playerId, language);
    }

    public void removePlayer(ServerPlayer player) {
        playerLanguages.remove(player.getUUID());
    }

    public void clear() {
        playerLanguages.clear();
    }

    public Language getDefaultLanguage() {
        return defaultLanguage;
    }

    public void setDefaultLanguage(Language language) {
        this.defaultLanguage = language;
    }
}
