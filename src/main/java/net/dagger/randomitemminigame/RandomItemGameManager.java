package net.dagger.randomitemminigame;

import net.dagger.randomitemminigame.game.GameState;
import net.dagger.randomitemminigame.game.Role;
import net.dagger.randomitemminigame.service.CommandService;
import net.dagger.randomitemminigame.service.ItemService;
import net.dagger.randomitemminigame.service.LivesService;
import net.dagger.randomitemminigame.service.RoleService;
import net.dagger.randomitemminigame.service.ScoreboardService;
import net.dagger.randomitemminigame.service.SwapService;
import net.dagger.randomitemminigame.service.TeleportService;
import net.dagger.randomitemminigame.service.TimerService;
import net.dagger.randomitemminigame.service.WinService;
import net.dagger.randomitemminigame.service.WorldService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;


/**
 * Controls the lifecycle of the random item minigame.
 */
public class RandomItemGameManager implements Listener, CommandExecutor, TabCompleter {
	private static final int COUNTDOWN_SECONDS = 5;

	private final JavaPlugin plugin;
	private final RoleService roleService;
	private final TeleportService teleportService;
	private final SwapService swapService;
	private final ItemService itemService;
	private final LivesService livesService;
	private final ScoreboardService scoreboardService;
	private final TimerService timerService;
	private final WinService winService;
	private final WorldService worldService;
	private final CommandService commandService;

	private GameState state = GameState.IDLE;
	private Material targetItem;
	private BukkitRunnable countdownTask;
	private BukkitRunnable monitorTask;

	public RandomItemGameManager(JavaPlugin plugin) {
		this.plugin = plugin;
		this.roleService = new RoleService();
		this.itemService = new ItemService();
		this.livesService = new LivesService();
		this.scoreboardService = new ScoreboardService();
		this.timerService = new TimerService(plugin, scoreboardService);
		this.winService = new WinService(roleService);
		this.worldService = new WorldService();
		this.teleportService = new TeleportService(plugin, this::broadcastToParticipants);
		this.swapService = new SwapService(
				plugin,
				() -> state == GameState.ACTIVE && targetItem != null,
				this::getActiveParticipants,
				this::broadcastToParticipants);
		this.commandService = new CommandService(
				this::handleStart,
				this::handleStop,
				this::handleStatus,
				this::handleCancel,
				this::handleSkip,
				args -> handleRole(args.sender, args.args));
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		return commandService.execute(sender, command, label, args);
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		return commandService.onTabComplete(sender, command, alias, args);
	}

	private void handleStart(CommandSender sender) {
		if (state != GameState.IDLE) {
			sender.sendMessage(colored("Мини-игра уже запущена или идёт отсчёт.", NamedTextColor.RED));
			return;
		}

		List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
		List<Player> participants = online.stream()
				.filter(player -> roleService.getRole(player) == Role.PLAYER)
				.collect(Collectors.toList());
		List<Player> spectators = online.stream()
				.filter(player -> roleService.getRole(player) == Role.SPECTATOR)
				.collect(Collectors.toList());

		if (participants.isEmpty()) {
			sender.sendMessage(colored("Нет игроков, участвующих в мини-игре. Используйте /randomitem role player.", NamedTextColor.RED));
			return;
		}

		targetItem = itemService.pickRandomItem();
		state = GameState.COUNTDOWN;
		roleService.prepareSpectators(spectators);
		worldService.setWorldStateForCountdown();

		broadcast(colored("=== Случайный предмет ===", NamedTextColor.GOLD));
		broadcast(Component.text()
				.append(colored("Нужно первым добыть: ", NamedTextColor.YELLOW))
				.append(formatMaterial(targetItem).color(NamedTextColor.AQUA))
				.build());
		broadcast(colored("Игроки телепортированы. Отсчёт " + COUNTDOWN_SECONDS + " сек...", NamedTextColor.GRAY));
		playSoundForAll(Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.0f);

		List<Player> participantsSnapshot = new ArrayList<>(participants);
		// Инициализируем жизни и scoreboard для всех участников
		livesService.initializeLives(participantsSnapshot);
		scoreboardService.createScoreboard(livesService.getAllLives());

		teleportService.scatterPlayers(participantsSnapshot).whenComplete((ignored, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
			if (throwable != null) {
				plugin.getLogger().severe("Не удалось телепортировать игроков: " + throwable.getMessage());
				handleStop(sender);
				return;
			}

			if (state != GameState.COUNTDOWN) {
				return;
			}

			winService.removeTargetItemFromPlayers(participantsSnapshot, targetItem);
			startCountdown();
		}));
	}

	private void handleStop(CommandSender sender) {
		if (state == GameState.IDLE) {
			sender.sendMessage(colored("Мини-игра и так остановлена.", NamedTextColor.RED));
			return;
		}

		cancelCountdown();
		cancelMonitor();
		timerService.cancel();
		timerService.updateState(GameState.IDLE);
		swapService.stop();
		livesService.clear();
		scoreboardService.clear();
		// Очищаем инвентари всех игроков при остановке игры
		clearAllPlayerInventories();
		// Очищаем спавны всех игроков при остановке игры
		clearAllPlayerRespawns();
		state = GameState.IDLE;
		targetItem = null;
		broadcast(colored("Мини-игра остановлена администратором.", NamedTextColor.RED));
	}

	private void handleCancel(CommandSender sender) {
		if (state != GameState.COUNTDOWN) {
			sender.sendMessage(colored("Нет активного отсчёта или загрузки для прерывания. Используйте /randomitem stop для остановки игры.", NamedTextColor.RED));
			return;
		}

		// Прерываем отсчёт и загрузку чанков
		cancelCountdown();
		timerService.cancel();
		timerService.updateState(GameState.IDLE);
		swapService.stop();
		// Отменяем все операции телепортации и загрузки чанков
		teleportService.cancel();
		livesService.clear();
		scoreboardService.clear();
		clearAllPlayerRespawns();
		state = GameState.IDLE;
		targetItem = null;
		broadcast(colored("Начало игры прервано администратором.", NamedTextColor.RED));
	}

	private void handleSkip(CommandSender sender) {
		if (state != GameState.ACTIVE) {
			sender.sendMessage(colored("Игра не активна. Пропуск предмета возможен только во время активной игры.", NamedTextColor.RED));
			return;
		}

		if (targetItem == null) {
			sender.sendMessage(colored("Нет текущего предмета для пропуска.", NamedTextColor.RED));
			return;
		}

		Material oldItem = targetItem;
		targetItem = itemService.pickRandomItem();

		// Удаляем старый предмет из инвентарей всех игроков
		List<Player> participants = winService.getAlivePlayers();
		winService.removeTargetItemFromPlayers(participants, targetItem);

		// Уведомляем всех о новом предмете
		broadcast(Component.text()
				.append(colored("Предмет пропущен администратором. ", NamedTextColor.YELLOW))
				.append(colored("Новый предмет: ", NamedTextColor.GOLD))
				.append(formatMaterial(targetItem).color(NamedTextColor.AQUA))
				.build());

		sender.sendMessage(Component.text()
				.append(colored("Предмет изменён с ", NamedTextColor.GREEN))
				.append(formatMaterial(oldItem).color(NamedTextColor.GRAY))
				.append(colored(" на ", NamedTextColor.GREEN))
				.append(formatMaterial(targetItem).color(NamedTextColor.AQUA))
				.build());
	}

	private void handleStatus(CommandSender sender) {
		if (state == GameState.IDLE) {
			sender.sendMessage(colored("Мини-игра не запущена.", NamedTextColor.GREEN));
		} else if (state == GameState.COUNTDOWN) {
			sender.sendMessage(Component.text()
					.append(colored("Идёт отсчёт. Цель: ", NamedTextColor.YELLOW))
					.append(formatMaterial(targetItem).color(NamedTextColor.AQUA))
					.build());
		} else {
			sender.sendMessage(Component.text()
					.append(colored("Игра активна! Цель: ", NamedTextColor.AQUA))
					.append(formatMaterial(targetItem).color(NamedTextColor.AQUA))
					.build());
		}
	}

	private void handleRole(CommandSender sender, String[] args) {
		if (!sender.hasPermission("randomitemminigame.admin")) {
			sender.sendMessage(colored("Недостаточно прав для изменения ролей.", NamedTextColor.RED));
			return;
		}

		if (!(sender instanceof Player) && args.length < 3) {
			sender.sendMessage(colored("Использование: /randomitem role <player|spectator> [ник|селектор]", NamedTextColor.YELLOW));
			return;
		}

		if (args.length < 2) {
			sender.sendMessage(colored("Использование: /randomitem role <player|spectator> [ник|селектор]", NamedTextColor.YELLOW));
			return;
		}

		String roleName = args[1].toLowerCase(Locale.ROOT);
		Role newRole;
		if ("player".equals(roleName)) {
			newRole = Role.PLAYER;
		} else if ("spectator".equals(roleName)) {
			newRole = Role.SPECTATOR;
		} else {
			sender.sendMessage(colored("Неизвестная роль. Доступно: player, spectator.", NamedTextColor.RED));
			return;
		}

		List<Player> targets = new ArrayList<>();
		if (args.length >= 3) {
			String selector = args[2];
			// Проверяем, является ли это селектором (начинается с @)
			if (selector.startsWith("@")) {
				try {
					List<Entity> entities = Bukkit.selectEntities(sender, selector);
					for (Entity entity : entities) {
						if (entity instanceof Player player) {
							targets.add(player);
						}
					}
					if (targets.isEmpty()) {
						sender.sendMessage(colored("Селектор " + selector + " не нашёл игроков.", NamedTextColor.RED));
						return;
					}
				} catch (IllegalArgumentException e) {
					sender.sendMessage(colored("Неверный селектор: " + e.getMessage(), NamedTextColor.RED));
					return;
				}
			} else {
				// Обычное имя игрока
				Player target = Bukkit.getPlayer(selector);
				if (target == null) {
					sender.sendMessage(colored("Игрок " + selector + " не найден.", NamedTextColor.RED));
					return;
				}
				targets.add(target);
			}
		} else if (sender instanceof Player self) {
			targets.add(self);
		} else {
			sender.sendMessage(colored("Нужно указать ник игрока или селектор.", NamedTextColor.YELLOW));
			return;
		}

		// Устанавливаем роль для всех найденных игроков
		for (Player target : targets) {
			roleService.setRole(target, newRole);
			if (!sender.equals(target)) {
				target.sendMessage(colored("Администратор установил вам роль " + roleName, NamedTextColor.AQUA));
			}
		}

		if (targets.size() == 1) {
			sender.sendMessage(colored("Установлена роль " + roleName + " для " + targets.get(0).getName(), NamedTextColor.GREEN));
		} else {
			sender.sendMessage(colored("Установлена роль " + roleName + " для " + targets.size() + " игроков", NamedTextColor.GREEN));
		}
	}

	@EventHandler
	public void onEntityDamage(EntityDamageEvent event) {
		// Отменяем весь урон для неуязвимых игроков (после телепортации)
		if (event.getEntity() instanceof Player player) {
			if (player.isInvulnerable()) {
				event.setCancelled(true);
			}
		}
	}

	@EventHandler
	public void onItemPickup(EntityPickupItemEvent event) {
		if (state != GameState.ACTIVE) {
			return;
		}

		Entity entity = event.getEntity();
		if (!(entity instanceof Player player)) {
			return;
		}

		org.bukkit.inventory.ItemStack stack = event.getItem().getItemStack();
		if (targetItem != null && stack.getType() == targetItem) {
			handlePotentialWin(player);
		}
	}

	@EventHandler
	public void onPlayerDeath(PlayerDeathEvent event) {
		if (state != GameState.ACTIVE) {
			return;
		}

		Player player = event.getEntity();
		if (roleService.getRole(player) != Role.PLAYER) {
			return;
		}

		// Уменьшаем жизни игрока
		int lives = livesService.decreaseLives(player);

		// Обновляем scoreboard для всех игроков
		scoreboardService.updateAllPlayersLives(livesService.getAllLives());

		if (!livesService.hasLives(player)) {
			// Игрок исчерпал все жизни - переводим в наблюдатели
			roleService.setRole(player, Role.SPECTATOR);
			scoreboardService.removePlayer(player);
			player.sendMessage(colored("Вы исчерпали все жизни и теперь наблюдаете за раундом.", NamedTextColor.RED));
			broadcastToParticipants(colored(player.getName() + " исчерпал все жизни и выбыл из игры.", NamedTextColor.GRAY));
		} else {
			// У игрока ещё есть жизни - сообщаем сколько осталось
			player.sendMessage(colored("У вас осталось жизней: " + lives + " из " + LivesService.getMaxLives(), NamedTextColor.YELLOW));
		}
	}


	private void startCountdown() {
		cancelCountdown();
		countdownTask = new BukkitRunnable() {
			private int secondsLeft = COUNTDOWN_SECONDS;

			@Override
			public void run() {
				if (secondsLeft <= 0) {
					broadcast(Component.text()
							.append(colored("Старт! Удачи в поисках ", NamedTextColor.GREEN))
							.append(formatMaterial(targetItem).color(NamedTextColor.AQUA))
							.build());
					state = GameState.ACTIVE;
					long gameStartTime = System.currentTimeMillis();
					timerService.updateState(state);
					timerService.start(gameStartTime, state);
					startMonitorTask();
					swapService.start();
					cancel();
					countdownTask = null;
					return;
				}

				broadcast(colored("Старт через " + secondsLeft + "...", NamedTextColor.YELLOW));
				secondsLeft--;
			}
		};
		countdownTask.runTaskTimer(plugin, 0L, 20L);
	}

	private void startMonitorTask() {
		cancelMonitor();
		monitorTask = new BukkitRunnable() {
			@Override
			public void run() {
				if (state != GameState.ACTIVE || targetItem == null) {
					return;
				}

				for (Player player : Bukkit.getOnlinePlayers()) {
					if (handlePotentialWin(player)) {
						break;
					}
				}

				checkLastPlayerStanding();
			}
		};
		monitorTask.runTaskTimer(plugin, 0L, 20L);
	}
	private void cancelCountdown() {
		if (countdownTask != null) {
			countdownTask.cancel();
			countdownTask = null;
		}
	}

	private void cancelMonitor() {
		if (monitorTask != null) {
			monitorTask.cancel();
			monitorTask = null;
		}
	}

	private void endGameWithWinner(Player winner) {
		if (state == GameState.IDLE) {
			return;
		}

		state = GameState.IDLE;
		cancelCountdown();
		cancelMonitor();
		timerService.cancel();
		timerService.updateState(GameState.IDLE);
		swapService.stop();
		livesService.clear();
		scoreboardService.clear();
		broadcast(Component.text()
				.append(colored("Игрок ", NamedTextColor.GOLD))
				.append(colored(winner.getName(), NamedTextColor.GREEN))
				.append(colored(" первым добыл ", NamedTextColor.GOLD))
				.append(formatMaterial(targetItem).color(NamedTextColor.AQUA))
				.append(colored(" и победил!", NamedTextColor.GOLD))
				.build());
		resetPlayersAfterGame();
		worldService.setWorldStateAfterGame();
		playSoundForAll(Sound.ENTITY_WITHER_DEATH, SoundCategory.MASTER, 1.5f, 0.8f);
		targetItem = null;
	}

	private Component formatMaterial(Material material) {
		if (material == null) {
			return Component.text("?", NamedTextColor.WHITE);
		}
		return Component.translatable(material.translationKey());
	}

	private void playSoundForAll(Sound sound, float volume, float pitch) {
		playSoundForAll(sound, SoundCategory.MASTER, volume, pitch);
	}

	private void playSoundForAll(Sound sound, SoundCategory category, float volume, float pitch) {
		for (Player player : Bukkit.getOnlinePlayers()) {
			player.playSound(player.getLocation(), sound, category, volume, pitch);
		}
	}

	private void broadcast(Component component) {
		for (Player player : Bukkit.getOnlinePlayers()) {
			player.sendMessage(component);
		}
		Bukkit.getConsoleSender().sendMessage(component);
	}

	private void broadcastToParticipants(Component component) {
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (roleService.getRole(player) == Role.PLAYER) {
				player.sendMessage(component);
			}
		}
	}

	private Component colored(String content, NamedTextColor color) {
		return Component.text(content, color);
	}


	private void resetPlayersAfterGame() {
		World defaultWorld = plugin.getServer().getWorlds().isEmpty() ? null : plugin.getServer().getWorlds().get(0);

		for (Player player : Bukkit.getOnlinePlayers()) {
			if (roleService.getRole(player) != Role.PLAYER) {
				roleService.setRole(player, Role.PLAYER);
			}
			player.getInventory().clear();
			player.getInventory().setArmorContents(new ItemStack[] { null, null, null, null });
			player.getInventory().setItemInOffHand(null);

			// Очищаем спавн игрока после окончания игры
			player.setRespawnLocation(null, false);

			Location spawn = player.getWorld().getSpawnLocation();
			if (defaultWorld != null) {
				spawn = defaultWorld.getSpawnLocation();
			}
			player.teleport(spawn);
			player.sendMessage(colored("Возвращаем на спавн и очищаем инвентарь после раунда.", NamedTextColor.GRAY));
		}
	}

	private void clearAllPlayerRespawns() {
		// Очищаем спавны всех игроков
		for (Player player : Bukkit.getOnlinePlayers()) {
			player.setRespawnLocation(null, false);
		}
	}

	private void clearAllPlayerInventories() {
		// Очищаем инвентари всех игроков
		for (Player player : Bukkit.getOnlinePlayers()) {
			player.getInventory().clear();
			player.getInventory().setArmorContents(new ItemStack[] { null, null, null, null });
			player.getInventory().setItemInOffHand(null);
		}
	}


	private boolean handlePotentialWin(Player player) {
		if (state != GameState.ACTIVE || targetItem == null) {
			return false;
		}

		if (roleService.getRole(player) != Role.PLAYER) {
			return false;
		}

		if (!winService.hasTargetItem(player, targetItem)) {
			return false;
		}

		endGameWithWinner(player);
		return true;
	}

	private void checkLastPlayerStanding() {
		if (state != GameState.ACTIVE || targetItem == null) {
			return;
		}

		List<Player> alivePlayers = winService.getAlivePlayers();

		if (alivePlayers.size() == 1) {
			Player winner = alivePlayers.get(0);
			broadcast(Component.text("Игрок остался один в живых: ", NamedTextColor.GOLD)
					.append(Component.text(winner.getName(), NamedTextColor.GREEN))
					.append(Component.text(" и выигрывает раунд!", NamedTextColor.GOLD)));
			endGameWithWinner(winner);
		}
	}


	private List<Player> getActiveParticipants() {
		return winService.getAlivePlayers();
	}

}
