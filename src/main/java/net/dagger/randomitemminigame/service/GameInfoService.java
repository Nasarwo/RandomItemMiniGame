package net.dagger.randomitemminigame.service;

import java.util.HashMap;
import java.util.Map;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class GameInfoService {
	private final LanguageService languageService;
	private final Map<LanguageService.Language, BossBar> bossBars = new HashMap<>();
	private Material currentItem;

	public GameInfoService(LanguageService languageService) {
		this.languageService = languageService;
	}

	public void showTargetItem(Material item) {
		hide();
		this.currentItem = item;

		for (LanguageService.Language lang : LanguageService.Language.values()) {
			Component title = Component.translatable(item.translationKey()).color(NamedTextColor.WHITE);

			BossBar bossBar = BossBar.bossBar(title, 0.0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
			bossBars.put(lang, bossBar);
		}

		for (Player player : Bukkit.getOnlinePlayers()) {
			addPlayer(player);
		}
	}

	public void hide() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			removePlayer(player);
		}
		bossBars.clear();
		currentItem = null;
	}

	public void addPlayer(Player player) {
		if (currentItem == null || bossBars.isEmpty()) {
			return;
		}
		LanguageService.Language lang = languageService.getLanguage(player);
		BossBar bar = bossBars.get(lang);
		if (bar != null) {
			player.showBossBar(bar);
		}
	}

	public void removePlayer(Player player) {
		if (bossBars.isEmpty()) {
			return;
		}
		for (BossBar bar : bossBars.values()) {
			player.hideBossBar(bar);
		}
	}

	public void updateLanguage(Player player, LanguageService.Language oldLang, LanguageService.Language newLang) {
		if (currentItem == null || bossBars.isEmpty()) {
			return;
		}

		BossBar oldBar = bossBars.get(oldLang);
		if (oldBar != null) {
			player.hideBossBar(oldBar);
		}

		BossBar newBar = bossBars.get(newLang);
		if (newBar != null) {
			player.showBossBar(newBar);
		}
	}

	public void updateTargetItem(Material newItem) {
		if (this.currentItem == null) {
			return;
		}
		this.currentItem = newItem;

		for (Map.Entry<LanguageService.Language, BossBar> entry : bossBars.entrySet()) {
			BossBar bar = entry.getValue();

			Component title = Component.translatable(newItem.translationKey()).color(NamedTextColor.WHITE);

			bar.name(title);
			bar.progress(0.0f);
		}
	}
}
