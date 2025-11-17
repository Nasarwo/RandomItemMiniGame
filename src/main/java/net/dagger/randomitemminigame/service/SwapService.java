package net.dagger.randomitemminigame.service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class SwapService {
	private static final int SWAP_INTERVAL_TICKS = 5 * 60 * 20; // 5 минут между телепортами
	private static final int FIRST_SWAP_TICKS = (4 * 60 + 50) * 20; // 4:50 для первого телепорта
	private static final int SWAP_COUNTDOWN_SECONDS = 10;

	private final JavaPlugin plugin;
	private final Supplier<Boolean> canSwapSupplier;
	private final Supplier<List<Player>> participantsSupplier;
	private final Consumer<Component> participantBroadcast;
	private final Random random = new Random();
	private BukkitRunnable task;

	public SwapService(JavaPlugin plugin,
			Supplier<Boolean> canSwapSupplier,
			Supplier<List<Player>> participantsSupplier,
			Consumer<Component> participantBroadcast) {
		this.plugin = plugin;
		this.canSwapSupplier = canSwapSupplier;
		this.participantsSupplier = participantsSupplier;
		this.participantBroadcast = participantBroadcast;
	}

	public void start() {
		stop();
		task = new BukkitRunnable() {
			private int ticksUntilSwap = FIRST_SWAP_TICKS; // Первый телепорт через 4:50
			private int countdown = -1;

			@Override
			public void run() {
				if (!canSwapSupplier.get()) {
					return;
				}

				List<Player> participants = participantsSupplier.get();
				if (participants.size() < 2) {
					ticksUntilSwap = FIRST_SWAP_TICKS; // Сбрасываем на первый интервал
					countdown = -1;
					return;
				}

				if (countdown >= 0) {
					if (countdown == SWAP_COUNTDOWN_SECONDS) {
						participantBroadcast.accept(Component.text("Случайная смена мест через " + countdown + " секунд!", NamedTextColor.LIGHT_PURPLE));
					} else if (countdown > 0) {
						participantBroadcast.accept(Component.text("Смена мест через " + countdown + " секунд.", NamedTextColor.LIGHT_PURPLE));
					} else {
						performSwap(participants);
						countdown = -1;
						ticksUntilSwap = SWAP_INTERVAL_TICKS;
						return;
					}
					countdown--;
					return;
				}

				ticksUntilSwap -= 20;

				// Показываем предупреждения о предстоящем телепорте
				int secondsUntilSwap = ticksUntilSwap / 20;
				if (secondsUntilSwap == 60) {
					participantBroadcast.accept(Component.text("Случайная смена мест через 1 минуту!", NamedTextColor.YELLOW));
				} else if (secondsUntilSwap == 30) {
					participantBroadcast.accept(Component.text("Случайная смена мест через 30 секунд!", NamedTextColor.YELLOW));
				}

				if (ticksUntilSwap <= 0) {
					countdown = SWAP_COUNTDOWN_SECONDS;
					participantBroadcast.accept(Component.text("Случайная смена мест начнётся через " + SWAP_COUNTDOWN_SECONDS + " секунд!", NamedTextColor.LIGHT_PURPLE));
				}
			}
		};
		task.runTaskTimer(plugin, 0L, 20L);
	}

	public void stop() {
		if (task != null) {
			task.cancel();
			task = null;
		}
	}

	private void performSwap(List<Player> participants) {
		participantBroadcast.accept(Component.text("Игроки меняются местами!", NamedTextColor.LIGHT_PURPLE));

		// Сохраняем текущие локации и спавны для каждого игрока
		// Важно: сохраняем ДО перемешивания, чтобы корректно обменивать позиции
		Map<Player, Location> playerLocations = new HashMap<>();
		Map<Player, Location> playerRespawnLocations = new HashMap<>();
		for (Player player : participants) {
			// Сохраняем текущую позицию игрока (для телепортации другого игрока сюда)
			playerLocations.put(player, player.getLocation().clone());
			// Сохраняем текущий спавн игрока (для передачи другому игроку)
			// Если спавна нет, используем текущую позицию (должно быть установлено при старте)
			Location respawnLoc = player.getRespawnLocation();
			if (respawnLoc == null) {
				respawnLoc = player.getLocation().clone();
			}
			playerRespawnLocations.put(player, respawnLoc);
		}

		// Перемешиваем список игроков для случайного обмена
		Collections.shuffle(participants, random);

		// Делаем циклический сдвиг: каждый игрок идёт на позицию следующего в перемешанном списке
		// Это гарантирует, что ВСЕ игроки перемещаются, даже если их нечётное количество
		// При этом спавны тоже меняются: каждый игрок получает спавн того, на чьё место он переместился
		for (int i = 0; i < participants.size(); i++) {
			Player currentPlayer = participants.get(i);
			// Следующий игрок в перемешанном списке (последний идёт на позицию первого)
			Player targetPlayer = participants.get((i + 1) % participants.size());

			Location targetLocation = playerLocations.get(targetPlayer);
			Location targetRespawnLocation = playerRespawnLocations.get(targetPlayer);

			currentPlayer.teleport(targetLocation);
			// Очищаем инвентарь игрока после телепортации
			currentPlayer.getInventory().clear();
			currentPlayer.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[] { null, null, null, null });
			currentPlayer.getInventory().setItemInOffHand(null);
			// Устанавливаем спавн того игрока, на чьё место был перемещён текущий игрок
			// Это важно: после смерти игрок появится на спавне того, на чьё место он переместился,
			// а не на своём старом спавне, что предотвратит поиск других игроков
			currentPlayer.setRespawnLocation(targetRespawnLocation, true);
			// Устанавливаем полную неуязвимость на 10 секунд (200 тиков) после телепортации
			// Используем задержку, чтобы телепортация не сбросила неуязвимость
			// Это предотвращает убийство игроков прыжком с высоты, в лаву, утоплением и т.д.
			Bukkit.getScheduler().runTaskLater(plugin, () -> {
				if (currentPlayer.isOnline()) {
					// Устанавливаем полную неуязвимость
					currentPlayer.setInvulnerable(true);
					// Также устанавливаем максимальное значение noDamageTicks для дополнительной защиты
					currentPlayer.setNoDamageTicks(Integer.MAX_VALUE);
					// Через 10 секунд (200 тиков) отключаем неуязвимость
					Bukkit.getScheduler().runTaskLater(plugin, () -> {
						if (currentPlayer.isOnline()) {
							currentPlayer.setInvulnerable(false);
							// Устанавливаем стандартное значение noDamageTicks
							currentPlayer.setNoDamageTicks(20);
						}
					}, 200L);
				}
			}, 1L);
			currentPlayer.playSound(targetLocation, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.MASTER, 1.0f, 1.0f);
		}
	}
}
