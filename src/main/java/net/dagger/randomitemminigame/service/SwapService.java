package net.dagger.randomitemminigame.service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class SwapService {
	private final int swapIntervalTicks;
	private final int firstSwapDelayTicks;
	private static final int SWAP_COUNTDOWN_SECONDS = 10;

	private final JavaPlugin plugin;
	private final LanguageService languageService;
	private final Supplier<Boolean> canSwapSupplier;
	private final Supplier<List<Player>> participantsSupplier;
	private final Consumer<Component> participantBroadcast;
	private final Random random = new Random();
	private BukkitRunnable task;

	public SwapService(JavaPlugin plugin,
			LanguageService languageService,
			Supplier<Boolean> canSwapSupplier,
			Supplier<List<Player>> participantsSupplier,
			Consumer<Component> participantBroadcast,
			int swapIntervalSeconds) {
		this.plugin = plugin;
		this.languageService = languageService;
		this.canSwapSupplier = canSwapSupplier;
		this.participantsSupplier = participantsSupplier;
		this.participantBroadcast = participantBroadcast;

		this.swapIntervalTicks = swapIntervalSeconds * 20;
		this.firstSwapDelayTicks = (swapIntervalSeconds - SWAP_COUNTDOWN_SECONDS) * 20;
	}

	public void start(long gameStartTime) {
		stop();
		task = new BukkitRunnable() {
			private int countdown = -1;
			private long nextSwapTime = gameStartTime + (swapIntervalTicks * 50); // Convert ticks to ms (1 tick = 50ms)

			@Override
			public void run() {
				if (!canSwapSupplier.get()) {
					return;
				}

				List<Player> participants = participantsSupplier.get();
				if (participants.size() < 2) {
					// Recalculate next swap time to keep it aligned if player count drops
					long currentTime = System.currentTimeMillis();
					long elapsed = currentTime - gameStartTime;
					long intervalMs = swapIntervalTicks * 50L;
					nextSwapTime = gameStartTime + ((elapsed / intervalMs) + 1) * intervalMs;
					countdown = -1;
					return;
				}

				long currentTime = System.currentTimeMillis();
				long msUntilSwap = nextSwapTime - currentTime;
				int secondsUntilSwap = (int) (msUntilSwap / 1000);

				if (msUntilSwap <= 0) {
					performSwap(participants);
					countdown = -1;
					nextSwapTime += swapIntervalTicks * 50L;
					return;
				}

				if (secondsUntilSwap <= SWAP_COUNTDOWN_SECONDS && secondsUntilSwap > 0) {
					// Avoid spamming if secondsUntilSwap stays the same for multiple ticks
					if (countdown != secondsUntilSwap) {
						countdown = secondsUntilSwap;
						LanguageService.Language defaultLang = languageService.getDefaultLanguage();
						if (countdown == SWAP_COUNTDOWN_SECONDS) {
							participantBroadcast.accept(Messages.get(defaultLang, Messages.MessageKey.SWAP_IN_SECONDS, countdown));
						} else {
							participantBroadcast.accept(Messages.get(defaultLang, Messages.MessageKey.SWAP_IN_SECONDS_SHORT, countdown));
						}
					}
				}

				// +1 to align with user expectation (e.g. at 60.9s show 60s message)
				int displaySeconds = secondsUntilSwap + 1;

				if (displaySeconds == 60) {
					// Ensure we only broadcast once per second
					if (countdown != 60) {
						LanguageService.Language defaultLang = languageService.getDefaultLanguage();
						participantBroadcast.accept(Messages.get(defaultLang, Messages.MessageKey.SWAP_IN_MINUTE));
						countdown = 60; // Use countdown var to track we showed this
					}
				} else if (displaySeconds == 30) {
					if (countdown != 30) {
						LanguageService.Language defaultLang = languageService.getDefaultLanguage();
						participantBroadcast.accept(Messages.get(defaultLang, Messages.MessageKey.SWAP_IN_30_SECONDS));
						countdown = 30;
					}
				}
			}
		};
		task.runTaskTimer(plugin, 0L, 5L); // Check more frequently (every 5 ticks / 0.25s) for precision
	}

	public void stop() {
		if (task != null) {
			task.cancel();
			task = null;
		}
	}

	private void performSwap(List<Player> participants) {
		LanguageService.Language defaultLang = languageService.getDefaultLanguage();
		participantBroadcast.accept(Messages.get(defaultLang, Messages.MessageKey.PLAYERS_SWAPPING));

		Map<Player, Location> playerLocations = new HashMap<>();
		Map<Player, Location> playerRespawnLocations = new HashMap<>();
		for (Player player : participants) {
			playerLocations.put(player, player.getLocation().clone());
			Location respawnLoc = player.getRespawnLocation();
			if (respawnLoc == null) {
				respawnLoc = player.getLocation().clone();
			}
			playerRespawnLocations.put(player, respawnLoc);
		}

		Collections.shuffle(participants, random);

		for (int i = 0; i < participants.size(); i++) {
			Player currentPlayer = participants.get(i);
			Player targetPlayer = participants.get((i + 1) % participants.size());

			Location targetLocation = playerLocations.get(targetPlayer);
			Location targetRespawnLocation = playerRespawnLocations.get(targetPlayer);

			currentPlayer.teleport(targetLocation);
			currentPlayer.setRespawnLocation(targetRespawnLocation, true);
			Bukkit.getScheduler().runTaskLater(plugin, () -> {
				if (currentPlayer.isOnline()) {
					currentPlayer.setInvulnerable(true);
					currentPlayer.setNoDamageTicks(Integer.MAX_VALUE);
					Bukkit.getScheduler().runTaskLater(plugin, () -> {
						if (currentPlayer.isOnline()) {
							currentPlayer.setInvulnerable(false);
							currentPlayer.setNoDamageTicks(20);
						}
					}, 500L);
				}
			}, 1L);
			currentPlayer.playSound(targetLocation, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.MASTER, 1.0f, 1.0f);
		}
	}
}
