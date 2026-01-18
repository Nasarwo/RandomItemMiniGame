package net.dagger.randomitemminigame;

import net.dagger.randomitemminigame.game.GameState;
import net.dagger.randomitemminigame.game.Role;
import net.dagger.randomitemminigame.service.CommandService;
import net.dagger.randomitemminigame.service.GameInfoService;
import net.dagger.randomitemminigame.service.ItemService;
import net.dagger.randomitemminigame.service.LanguageService;
import net.dagger.randomitemminigame.service.LivesService;
import net.dagger.randomitemminigame.service.Messages;
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
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
public class LootRushGameManager implements Listener, CommandExecutor, TabCompleter {
	private static final int COUNTDOWN_SECONDS = 10;

	private final JavaPlugin plugin;
	private final LanguageService languageService;
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
	private final GameInfoService gameInfoService;

	private GameState state = GameState.IDLE;
	private Material targetItem;
	private BukkitRunnable countdownTask;
	private BukkitRunnable monitorTask;

	public LootRushGameManager(JavaPlugin plugin, List<String> bannedItems, int swapIntervalSeconds, int minScatterCoord, int maxScatterCoord) {
		this.plugin = plugin;
		this.languageService = new LanguageService();
		this.roleService = new RoleService(languageService);
		this.itemService = new ItemService(bannedItems);
		this.livesService = new LivesService();
		this.scoreboardService = new ScoreboardService(languageService);
		this.timerService = new TimerService(plugin, scoreboardService);
		this.winService = new WinService(roleService);
		this.worldService = new WorldService();
		this.gameInfoService = new GameInfoService(languageService);
		this.teleportService = new TeleportService(plugin, languageService, this::broadcastToParticipants, minScatterCoord, maxScatterCoord);
		this.swapService = new SwapService(
				plugin,
				languageService,
				() -> state == GameState.ACTIVE && targetItem != null,
				this::getActiveParticipants,
				this::broadcastToParticipants,
				swapIntervalSeconds);
		this.commandService = new CommandService(
				languageService,
				this::handleStart,
				this::handleStop,
				this::handleStatus,
				this::handleCancel,
				this::handleSkip,
				args -> handleRole(args.sender, args.args),
				args -> handleLang(args.sender, args.args));
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
		LanguageService.Language lang = getLanguage(sender);
		if (state != GameState.IDLE) {
			sender.sendMessage(Messages.get(lang, Messages.MessageKey.GAME_ALREADY_RUNNING));
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
			sender.sendMessage(Messages.get(lang, Messages.MessageKey.NO_PLAYERS));
			return;
		}

		targetItem = itemService.pickRandomItem();
		state = GameState.COUNTDOWN;
		roleService.prepareSpectators(spectators);
		worldService.setWorldStateForLoading();
		worldService.setSafetyBorder();

		// Teleport to spawn and clear inventory for safety during loading
		for (Player player : online) {
			player.teleport(player.getWorld().getSpawnLocation());
			player.getInventory().clear();
			player.getInventory().setArmorContents(new ItemStack[] { null, null, null, null });
			player.getInventory().setItemInOffHand(null);
		}

		broadcast(Messages.MessageKey.RANDOM_ITEM_HEADER);
		for (Player player : Bukkit.getOnlinePlayers()) {
			LanguageService.Language playerLang = languageService.getLanguage(player);
			player.sendMessage(Component.text()
					.append(Messages.get(playerLang, Messages.MessageKey.NEED_TO_OBTAIN))
					.append(formatMaterial(targetItem).color(NamedTextColor.AQUA))
					.build());
		}
		LanguageService.Language defaultLang = languageService.getDefaultLanguage();
		Bukkit.getConsoleSender().sendMessage(Component.text()
				.append(Messages.get(defaultLang, Messages.MessageKey.NEED_TO_OBTAIN))
				.append(formatMaterial(targetItem).color(NamedTextColor.AQUA))
				.build());
		playSoundForAll(Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.0f);

		List<Player> participantsSnapshot = new ArrayList<>(participants);
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

			broadcast(Messages.MessageKey.PLAYERS_TELEPORTED, COUNTDOWN_SECONDS);
			winService.removeTargetItemFromPlayers(participantsSnapshot, targetItem);
			worldService.setWorldStateActive();
			worldService.resetBorder();
			gameInfoService.showTargetItem(targetItem);
			startCountdown();
		}));
	}

	private void handleStop(CommandSender sender) {
		LanguageService.Language lang = getLanguage(sender);
		if (state == GameState.IDLE) {
			sender.sendMessage(Messages.get(lang, Messages.MessageKey.GAME_ALREADY_STOPPED));
			return;
		}

		cancelCountdown();
		cancelMonitor();
		timerService.cancel();
		timerService.updateState(GameState.IDLE);
		swapService.stop();
		livesService.clear();
		scoreboardService.clear();
		clearAllPlayerInventories();
		clearAllPlayerRespawns();
		state = GameState.IDLE;
		targetItem = null;
		gameInfoService.hide();
		worldService.setWorldStateAfterGame();
		worldService.resetBorder();
		broadcast(Messages.MessageKey.GAME_STOPPED);
	}

	private void handleCancel(CommandSender sender) {
		LanguageService.Language lang = getLanguage(sender);
		if (state != GameState.COUNTDOWN) {
			sender.sendMessage(Messages.get(lang, Messages.MessageKey.NO_COUNTDOWN));
			return;
		}

		cancelCountdown();
		timerService.cancel();
		timerService.updateState(GameState.IDLE);
		swapService.stop();
		teleportService.cancel();
		livesService.clear();
		scoreboardService.clear();
		clearAllPlayerRespawns();
		state = GameState.IDLE;
		targetItem = null;
		gameInfoService.hide();
		worldService.setWorldStateAfterGame();
		worldService.resetBorder();
		broadcast(Messages.MessageKey.GAME_CANCELLED);
	}

	private void handleSkip(CommandSender sender) {
		LanguageService.Language lang = getLanguage(sender);
		if (state != GameState.ACTIVE) {
			sender.sendMessage(Messages.get(lang, Messages.MessageKey.GAME_NOT_ACTIVE));
			return;
		}

		if (targetItem == null) {
			sender.sendMessage(Messages.get(lang, Messages.MessageKey.NO_CURRENT_ITEM));
			return;
		}

		Material oldItem = targetItem;
		targetItem = itemService.pickRandomItem();
		gameInfoService.updateTargetItem(targetItem);

		List<Player> participants = winService.getAlivePlayers();
		winService.removeTargetItemFromPlayers(participants, targetItem);

		for (Player player : Bukkit.getOnlinePlayers()) {
			LanguageService.Language playerLang = languageService.getLanguage(player);
			player.sendMessage(Component.text()
					.append(Messages.get(playerLang, Messages.MessageKey.ITEM_SKIPPED))
					.append(Messages.get(playerLang, Messages.MessageKey.NEW_ITEM))
					.append(formatMaterial(targetItem).color(NamedTextColor.AQUA))
					.build());
		}
		LanguageService.Language defaultLang = languageService.getDefaultLanguage();
		Bukkit.getConsoleSender().sendMessage(Component.text()
				.append(Messages.get(defaultLang, Messages.MessageKey.ITEM_SKIPPED))
				.append(Messages.get(defaultLang, Messages.MessageKey.NEW_ITEM))
				.append(formatMaterial(targetItem).color(NamedTextColor.AQUA))
				.build());

		sender.sendMessage(Component.text()
				.append(Messages.get(lang, Messages.MessageKey.ITEM_CHANGED))
				.append(formatMaterial(oldItem).color(NamedTextColor.GRAY))
				.append(Messages.get(lang, Messages.MessageKey.ITEM_CHANGED_TO))
				.append(formatMaterial(targetItem).color(NamedTextColor.AQUA))
				.build());
	}

	private void handleStatus(CommandSender sender) {
		LanguageService.Language lang = getLanguage(sender);
		if (state == GameState.IDLE) {
			sender.sendMessage(Messages.get(lang, Messages.MessageKey.GAME_NOT_STARTED));
		} else if (state == GameState.COUNTDOWN) {
			sender.sendMessage(Component.text()
					.append(Messages.get(lang, Messages.MessageKey.COUNTDOWN_IN_PROGRESS))
					.append(formatMaterial(targetItem).color(NamedTextColor.AQUA))
					.build());
		} else {
			sender.sendMessage(Component.text()
					.append(Messages.get(lang, Messages.MessageKey.GAME_ACTIVE))
					.append(formatMaterial(targetItem).color(NamedTextColor.AQUA))
					.build());
		}
	}

	private void handleRole(CommandSender sender, String[] args) {
		LanguageService.Language lang = getLanguage(sender);
		if (!sender.hasPermission("randomitemminigame.admin")) {
			sender.sendMessage(Messages.get(lang, Messages.MessageKey.NO_PERMISSION_ROLE));
			return;
		}

		if (!(sender instanceof Player) && args.length < 3) {
			sender.sendMessage(Messages.get(lang, Messages.MessageKey.ROLE_USAGE));
			return;
		}

		if (args.length < 2) {
			sender.sendMessage(Messages.get(lang, Messages.MessageKey.ROLE_USAGE));
			return;
		}

		String roleName = args[1].toLowerCase(Locale.ROOT);
		Role newRole;
		if ("player".equals(roleName)) {
			newRole = Role.PLAYER;
		} else if ("spectator".equals(roleName)) {
			newRole = Role.SPECTATOR;
		} else {
			sender.sendMessage(Messages.get(lang, Messages.MessageKey.UNKNOWN_ROLE));
			return;
		}

		List<Player> targets = new ArrayList<>();
		if (args.length >= 3) {
			String selector = args[2];
			if (selector.startsWith("@")) {
				try {
					List<Entity> entities = Bukkit.selectEntities(sender, selector);
					for (Entity entity : entities) {
						if (entity instanceof Player player) {
							targets.add(player);
						}
					}
					if (targets.isEmpty()) {
						sender.sendMessage(Messages.get(lang, Messages.MessageKey.SELECTOR_NO_PLAYERS, selector));
						return;
					}
				} catch (IllegalArgumentException e) {
					sender.sendMessage(Messages.get(lang, Messages.MessageKey.INVALID_SELECTOR, e.getMessage()));
					return;
				}
			} else {
				Player target = Bukkit.getPlayer(selector);
				if (target == null) {
					sender.sendMessage(Messages.get(lang, Messages.MessageKey.PLAYER_NOT_FOUND, selector));
					return;
				}
				targets.add(target);
			}
		} else if (sender instanceof Player self) {
			targets.add(self);
		} else {
			sender.sendMessage(Messages.get(lang, Messages.MessageKey.NEED_PLAYER_OR_SELECTOR));
			return;
		}

		for (Player target : targets) {
			roleService.setRole(target, newRole);
			if (!sender.equals(target)) {
				LanguageService.Language targetLang = languageService.getLanguage(target);
				target.sendMessage(Messages.get(targetLang, Messages.MessageKey.ROLE_SET_BY_ADMIN, roleName));
			}
		}

		if (targets.size() == 1) {
			sender.sendMessage(Messages.get(lang, Messages.MessageKey.ROLE_SET_SINGLE, roleName, targets.get(0).getName()));
		} else {
			sender.sendMessage(Messages.get(lang, Messages.MessageKey.ROLE_SET_MULTIPLE, roleName, targets.size()));
		}
	}

	private void handleLang(CommandSender sender, String[] args) {
		LanguageService.Language lang = getLanguage(sender);
		if (args.length < 2) {
			sender.sendMessage(Messages.get(lang, Messages.MessageKey.LANG_USAGE));
			return;
		}

		String langCode = args[1].toLowerCase(Locale.ROOT);
		LanguageService.Language newLang = LanguageService.Language.fromCode(langCode);

		if (!langCode.equals("ru") && !langCode.equals("en") && !langCode.equals("uk") && !langCode.equals("ua")) {
			sender.sendMessage(Messages.get(lang, Messages.MessageKey.UNKNOWN_LANGUAGE));
			return;
		}

		if (sender instanceof Player player) {
			LanguageService.Language oldLang = languageService.getLanguage(player);
			languageService.setLanguage(player, newLang);
			teleportService.onPlayerLanguageChanged(player, oldLang, newLang);
			gameInfoService.updateLanguage(player, oldLang, newLang);
			String langName;
			if (newLang == LanguageService.Language.EN) {
				langName = "English";
			} else if (newLang == LanguageService.Language.UK) {
				langName = "Українська";
			} else {
				langName = "Русский";
			}
			sender.sendMessage(Messages.get(newLang, Messages.MessageKey.LANGUAGE_SET, langName));
		} else {
			sender.sendMessage(Messages.get(lang, Messages.MessageKey.CURRENT_LANGUAGE,
					languageService.getDefaultLanguage().getCode()));
		}
	}

	private LanguageService.Language getLanguage(CommandSender sender) {
		if (sender instanceof Player player) {
			return languageService.getLanguage(player);
		}
		return languageService.getDefaultLanguage();
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		gameInfoService.addPlayer(event.getPlayer());
		if (state == GameState.ACTIVE || state == GameState.COUNTDOWN) {
			scoreboardService.addViewer(event.getPlayer(), livesService.getAllLives());
		}
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		gameInfoService.removePlayer(event.getPlayer());
		scoreboardService.removePlayer(event.getPlayer());
	}

	@EventHandler
	public void onEntityDamage(EntityDamageEvent event) {
		if (event.getEntity() instanceof Player player) {
			if (player.isInvulnerable() || (state == GameState.COUNTDOWN && roleService.getRole(player) == Role.PLAYER)) {
				event.setCancelled(true);
			}
		}
	}

	@EventHandler
	public void onPlayerMove(PlayerMoveEvent event) {
		if (state != GameState.COUNTDOWN) {
			return;
		}

		Player player = event.getPlayer();
		if (roleService.getRole(player) != Role.PLAYER) {
			return;
		}

		// Only restrict movement after teleportation (when countdownTask is running),
		// not during loading (when players are in spawn box)
		if (countdownTask == null) {
			return;
		}

		if (event.hasChangedBlock()) {
			Location from = event.getFrom();
			Location to = event.getTo();

			// Allow looking around, but prevent movement
			if (to.getX() != from.getX() || to.getY() != from.getY() || to.getZ() != from.getZ()) {
				event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(), to.getYaw(), to.getPitch()));
			}
		}
	}

	@EventHandler
	public void onBlockBreak(BlockBreakEvent event) {
		if (state == GameState.COUNTDOWN && roleService.getRole(event.getPlayer()) == Role.PLAYER) {
			event.setCancelled(true);
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

		int lives = livesService.decreaseLives(player);
		LanguageService.Language lang = languageService.getLanguage(player);

		scoreboardService.updateAllPlayersLives(livesService.getAllLives());

		if (!livesService.hasLives(player)) {
			roleService.setRole(player, Role.SPECTATOR);
			player.sendMessage(Messages.get(lang, Messages.MessageKey.NO_LIVES_LEFT));
			broadcastToParticipants(Messages.MessageKey.PLAYER_OUT, player.getName());
		} else {
			player.sendMessage(Messages.get(lang, Messages.MessageKey.LIVES_REMAINING, lives, LivesService.getMaxLives()));
		}
	}


	private void startCountdown() {
		cancelCountdown();
		countdownTask = new BukkitRunnable() {
			private int secondsLeft = COUNTDOWN_SECONDS;

			@Override
			public void run() {
				if (secondsLeft <= 0) {
					for (Player player : Bukkit.getOnlinePlayers()) {
						LanguageService.Language playerLang = languageService.getLanguage(player);
						player.sendMessage(Component.text()
								.append(Messages.get(playerLang, Messages.MessageKey.START_GOOD_LUCK))
								.append(formatMaterial(targetItem).color(NamedTextColor.AQUA))
								.build());
					}
					LanguageService.Language defaultLang = languageService.getDefaultLanguage();
					Bukkit.getConsoleSender().sendMessage(Component.text()
							.append(Messages.get(defaultLang, Messages.MessageKey.START_GOOD_LUCK))
							.append(formatMaterial(targetItem).color(NamedTextColor.AQUA))
							.build());
					state = GameState.ACTIVE;
					long gameStartTime = System.currentTimeMillis();
					timerService.updateState(state);
					timerService.start(gameStartTime, state);
					startMonitorTask();
					swapService.start(gameStartTime);
					cancel();
					countdownTask = null;
					return;
				}

				broadcast(Messages.MessageKey.START_IN_SECONDS, secondsLeft);
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
		gameInfoService.hide();
		for (Player player : Bukkit.getOnlinePlayers()) {
			LanguageService.Language playerLang = languageService.getLanguage(player);
			player.sendMessage(Component.text()
					.append(Messages.get(playerLang, Messages.MessageKey.PLAYER_WON))
					.append(Component.text(winner.getName(), NamedTextColor.GREEN))
					.append(Messages.get(playerLang, Messages.MessageKey.OBTAINED_FIRST))
					.append(formatMaterial(targetItem).color(NamedTextColor.AQUA))
					.append(Messages.get(playerLang, Messages.MessageKey.AND_WON))
					.build());
		}
		LanguageService.Language defaultLang = languageService.getDefaultLanguage();
		Bukkit.getConsoleSender().sendMessage(Component.text()
				.append(Messages.get(defaultLang, Messages.MessageKey.PLAYER_WON))
				.append(Component.text(winner.getName(), NamedTextColor.GREEN))
				.append(Messages.get(defaultLang, Messages.MessageKey.OBTAINED_FIRST))
				.append(formatMaterial(targetItem).color(NamedTextColor.AQUA))
				.append(Messages.get(defaultLang, Messages.MessageKey.AND_WON))
				.build());
		resetPlayersAfterGame();
		worldService.setWorldStateAfterGame();
		worldService.resetBorder();
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

	private void broadcast(Messages.MessageKey key, Object... args) {
		for (Player player : Bukkit.getOnlinePlayers()) {
			LanguageService.Language lang = languageService.getLanguage(player);
			player.sendMessage(Messages.get(lang, key, args));
		}
		LanguageService.Language defaultLang = languageService.getDefaultLanguage();
		Bukkit.getConsoleSender().sendMessage(Messages.get(defaultLang, key, args));
	}

	private void broadcastToParticipants(Component component) {
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (roleService.getRole(player) == Role.PLAYER) {
				player.sendMessage(component);
			}
		}
	}

	private void broadcastToParticipants(Messages.MessageKey key, Object... args) {
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (roleService.getRole(player) == Role.PLAYER) {
				LanguageService.Language lang = languageService.getLanguage(player);
				player.sendMessage(Messages.get(lang, key, args));
			}
		}
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

			player.setRespawnLocation(null, false);

			Location spawn = player.getWorld().getSpawnLocation();
			if (defaultWorld != null) {
				spawn = defaultWorld.getSpawnLocation();
			}
			player.teleport(spawn);
			LanguageService.Language lang = languageService.getLanguage(player);
			player.sendMessage(Messages.get(lang, Messages.MessageKey.RETURNING_TO_SPAWN));
		}
	}

	private void clearAllPlayerRespawns() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			player.setRespawnLocation(null, false);
		}
	}

	private void clearAllPlayerInventories() {
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
			for (Player player : Bukkit.getOnlinePlayers()) {
				LanguageService.Language playerLang = languageService.getLanguage(player);
				player.sendMessage(Messages.get(playerLang, Messages.MessageKey.LAST_PLAYER_STANDING)
						.append(Component.text(winner.getName(), NamedTextColor.GREEN))
						.append(Messages.get(playerLang, Messages.MessageKey.WINS_ROUND)));
			}
			LanguageService.Language defaultLang = languageService.getDefaultLanguage();
			Bukkit.getConsoleSender().sendMessage(Messages.get(defaultLang, Messages.MessageKey.LAST_PLAYER_STANDING)
					.append(Component.text(winner.getName(), NamedTextColor.GREEN))
					.append(Messages.get(defaultLang, Messages.MessageKey.WINS_ROUND)));
			endGameWithWinner(winner);
		}
	}


	private List<Player> getActiveParticipants() {
		return winService.getAlivePlayers();
	}

}
