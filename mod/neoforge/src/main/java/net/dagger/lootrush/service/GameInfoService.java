package net.dagger.lootrush.service;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.Map;

public class GameInfoService {
    private final LanguageService languageService;
    private final Map<LanguageService.Language, ServerBossEvent> bossBars = new HashMap<>();
    private Item currentItem;

    public GameInfoService(LanguageService languageService) {
        this.languageService = languageService;
    }

    public void showTargetItem(Item item, Iterable<ServerPlayer> players) {
        hide();
        this.currentItem = item;

        for (LanguageService.Language lang : LanguageService.Language.values()) {
            Component title = Component.translatable(item.getDescriptionId()).withStyle(ChatFormatting.WHITE);

            ServerBossEvent bossBar = new ServerBossEvent(title, BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
            bossBar.setProgress(0.0f);
            bossBars.put(lang, bossBar);
        }

        for (ServerPlayer player : players) {
            addPlayer(player);
        }
    }

    public void hide() {
        for (ServerBossEvent bar : bossBars.values()) {
            bar.removeAllPlayers();
        }
        bossBars.clear();
        currentItem = null;
    }

    public void addPlayer(ServerPlayer player) {
        if (currentItem == null || bossBars.isEmpty()) {
            return;
        }
        LanguageService.Language lang = languageService.getLanguage(player);
        ServerBossEvent bar = bossBars.get(lang);
        if (bar != null) {
            bar.addPlayer(player);
        }
    }

    public void removePlayer(ServerPlayer player) {
        if (bossBars.isEmpty()) {
            return;
        }
        for (ServerBossEvent bar : bossBars.values()) {
            bar.removePlayer(player);
        }
    }

    public void updateLanguage(ServerPlayer player, LanguageService.Language oldLang, LanguageService.Language newLang) {
        if (currentItem == null || bossBars.isEmpty()) {
            return;
        }

        ServerBossEvent oldBar = bossBars.get(oldLang);
        if (oldBar != null) {
            oldBar.removePlayer(player);
        }

        ServerBossEvent newBar = bossBars.get(newLang);
        if (newBar != null) {
            newBar.addPlayer(player);
        }
    }

    public void updateTargetItem(Item newItem) {
        if (this.currentItem == null) {
            return;
        }
        this.currentItem = newItem;

        for (Map.Entry<LanguageService.Language, ServerBossEvent> entry : bossBars.entrySet()) {
            ServerBossEvent bar = entry.getValue();

            Component title = Component.translatable(newItem.getDescriptionId()).withStyle(ChatFormatting.WHITE);

            bar.setName(title);
            bar.setProgress(0.0f);
        }
    }
}
