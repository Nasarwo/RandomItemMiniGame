package net.dagger.randomitemminigame.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class TeleportService {
	private final int minScatterCoord;
	private final int maxScatterCoord;
	private static final int MIN_PLAYER_DISTANCE = 10000;
	private static final int SAFE_LOCATION_ATTEMPTS = 256;
	private final JavaPlugin plugin;
	private final LanguageService languageService;
	private final Consumer<Component> participantBroadcast;
	private final Random random = new Random();
	private final Map<LanguageService.Language, BossBar> currentBossBars = new HashMap<>();
	private volatile boolean cancelled = false;
	private final List<CompletableFuture<?>> activeOperations = new CopyOnWriteArrayList<>();

	private static final Set<Material> UNSAFE_FLOOR_BLOCKS = EnumSet.of(
			Material.LAVA,
			Material.WATER,
			Material.KELP,
			Material.KELP_PLANT,
			Material.SEAGRASS,
			Material.TALL_SEAGRASS,
			Material.BUBBLE_COLUMN,
			Material.CACTUS,
			Material.MAGMA_BLOCK,
			Material.CAMPFIRE,
			Material.SOUL_CAMPFIRE,
			Material.FIRE,
			Material.SOUL_FIRE,
			Material.POWDER_SNOW,
			Material.SWEET_BERRY_BUSH,
			Material.COBWEB,
			Material.WITHER_ROSE
	);

	public TeleportService(JavaPlugin plugin, LanguageService languageService, Consumer<Component> participantBroadcast, int minScatterCoord, int maxScatterCoord) {
		this.plugin = plugin;
		this.languageService = languageService;
		this.participantBroadcast = participantBroadcast;
		this.minScatterCoord = minScatterCoord;
		this.maxScatterCoord = maxScatterCoord;
	}

	public CompletableFuture<Void> scatterPlayers(List<Player> players) {
		cancelled = true;
		clearBossBar();
		for (CompletableFuture<?> operation : activeOperations) {
			if (operation != null && !operation.isDone()) {
				operation.cancel(false);
			}
		}
		activeOperations.clear();
		cancelled = false;
		Map<LanguageService.Language, BossBar> scatterBars = createScatterBossBar(players, players.size());
		currentBossBars.putAll(scatterBars);
		CompletableFuture<Void> mainFuture = teleportPlayers(players, scatterBars);
		trackOperation(mainFuture);
		return mainFuture.whenComplete((ignored, throwable) -> {
			hideScatterBossBar(scatterBars, throwable == null && !cancelled);
			if (currentBossBars.equals(scatterBars)) {
				currentBossBars.clear();
			} else {
				for (BossBar bar : scatterBars.values()) {
					if (currentBossBars.containsValue(bar)) {
						currentBossBars.values().remove(bar);
					}
				}
			}
			activeOperations.remove(mainFuture);
		});
	}

	private CompletableFuture<Void> teleportPlayers(List<Player> players, Map<LanguageService.Language, BossBar> scatterBars) {
		if (players.isEmpty() || cancelled) {
			return CompletableFuture.completedFuture(null);
		}

		CompletableFuture<List<PlayerScatterTarget>> targetsFuture = prepareScatterTargets(players, new ArrayList<>(), 0, scatterBars);
		trackOperation(targetsFuture);
		return targetsFuture.thenCompose(targets -> {
			if (cancelled) {
				return CompletableFuture.completedFuture(null);
			}

			List<PlayerScatterTarget> targetsList = new ArrayList<>(targets);

			Bukkit.getScheduler().runTask(plugin, () -> {
				if (cancelled) {
					return;
				}
				for (PlayerScatterTarget target : targetsList) {
					Player player = Bukkit.getPlayer(target.playerId());
					if (player != null && player.isOnline()) {
						LanguageService.Language lang = languageService.getLanguage(player);
						player.sendMessage(Messages.get(lang, Messages.MessageKey.LOADING_CHUNKS));
					}
				}
			});

			CompletableFuture<Void> chunksFuture = preloadAllChunksParallel(targetsList, targetsList);
			trackOperation(chunksFuture);
			return chunksFuture.thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
				if (cancelled) {
					return;
				}

				for (PlayerScatterTarget target : targetsList) {
					Player player = Bukkit.getPlayer(target.playerId());
					if (player == null || !player.isOnline()) {
						continue;
					}

					plugin.getLogger().info(String.format("[LootRush] Teleporting %s to %.1f %.1f %.1f",
							player.getName(), target.location().getX(), target.location().getY(), target.location().getZ()));
					player.teleport(target.location());
					player.getInventory().clear();
					player.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[] { null, null, null, null });
					player.getInventory().setItemInOffHand(null);
					player.setRespawnLocation(target.location(), true);

					refreshChunksForPlayer(player, target.location());

					Bukkit.getScheduler().runTaskLater(plugin, () -> {
						if (player.isOnline() && !cancelled) {
							player.setInvulnerable(true);
							player.setNoDamageTicks(Integer.MAX_VALUE);
							Bukkit.getScheduler().runTaskLater(plugin, () -> {
								if (player.isOnline() && !cancelled) {
									player.setInvulnerable(false);
									player.setNoDamageTicks(20);
								}
							}, 500L);
						}
					}, 1L);

					LanguageService.Language lang = languageService.getLanguage(player);
					player.sendMessage(Messages.get(lang, Messages.MessageKey.TELEPORTED_TO, formatLocation(target.location())));
				}
			})).whenComplete((ignored, throwable) -> {
				activeOperations.remove(chunksFuture);
			});
		}).whenComplete((ignored, throwable) -> {
			activeOperations.remove(targetsFuture);
		});
	}

	private CompletableFuture<List<PlayerScatterTarget>> prepareScatterTargets(List<Player> players, List<PlayerScatterTarget> accumulator, int index,
			Map<LanguageService.Language, BossBar> scatterBars) {
		if (cancelled || index >= players.size()) {
			return CompletableFuture.completedFuture(new ArrayList<>(accumulator));
		}

		Player player = players.get(index);
		for (Player participant : players) {
			LanguageService.Language participantLang = languageService.getLanguage(participant);
			participant.sendMessage(Messages.get(participantLang, Messages.MessageKey.SEARCHING_LOCATION, player.getName()));
		}
		updateScatterBossBar(scatterBars, accumulator.size(), players.size(), player.getName(), true);
		List<Location> existingLocations = accumulator.stream()
				.map(PlayerScatterTarget::location)
				.toList();

		CompletableFuture<Location> locationFuture = findRandomLocationAsync(player.getWorld(), existingLocations, player.getName(), players);
		trackOperation(locationFuture);
		return locationFuture.thenCompose(location -> {
			if (cancelled) {
				activeOperations.remove(locationFuture);
				return CompletableFuture.completedFuture(new ArrayList<>(accumulator));
			}

			for (Player participant : players) {
				LanguageService.Language participantLang = languageService.getLanguage(participant);
				participant.sendMessage(Messages.get(participantLang, Messages.MessageKey.LOCATION_FOUND, player.getName(), formatLocation(location)));
			}
			accumulator.add(new PlayerScatterTarget(player.getUniqueId(), location));
			updateScatterBossBar(scatterBars, accumulator.size(), players.size(), player.getName(), false);
			CompletableFuture<List<PlayerScatterTarget>> nextFuture = prepareScatterTargets(players, accumulator, index + 1, scatterBars);
			nextFuture.whenComplete((ignored, throwable) -> {
				activeOperations.remove(locationFuture);
			});
			return nextFuture;
		});
	}

	private CompletableFuture<Location> findRandomLocationAsync(World world, List<Location> existingLocations, String playerName, List<Player> participants) {
		CompletableFuture<Location> future = new CompletableFuture<>();
		trackOperation(future);
		findRandomLocationAttempt(world, existingLocations, future, 0, playerName, participants);
		return future;
	}

	private void findRandomLocationAttempt(World world, List<Location> existingLocations, CompletableFuture<Location> future, int attempt,
			String playerName, List<Player> participants) {
		if (cancelled || future.isDone()) {
			if (cancelled && !future.isDone()) {
				Bukkit.getScheduler().runTask(plugin, () -> future.cancel(false));
			}
			return;
		}

		if (attempt >= SAFE_LOCATION_ATTEMPTS) {
			plugin.getLogger().warning("Не удалось найти безопасную точку для телепорта после " + SAFE_LOCATION_ATTEMPTS
					+ " попыток. Используем резервный вариант.");
			Bukkit.getScheduler().runTask(plugin, () -> {
				if (!cancelled && !future.isDone()) {
					future.complete(fallbackRandomLocation(world, existingLocations));
				}
			});
			return;
		}

		int x = randomCoordinate();
		int z = randomCoordinate();

		if (!isFarEnough(x, z, world, existingLocations)) {
			if (!cancelled && participants != null) {
				for (Player participant : participants) {
					if (participant != null && participant.isOnline()) {
						LanguageService.Language participantLang = languageService.getLanguage(participant);
						participant.sendMessage(Messages.get(participantLang, Messages.MessageKey.ATTEMPT_TOO_CLOSE, attempt + 1, playerName, x, z));
					}
				}
			}
			findRandomLocationAttempt(world, existingLocations, future, attempt + 1, playerName, participants);
			return;
		}

		int chunkX = x >> 4;
		int chunkZ = z >> 4;

		preloadSurroundingChunks(world, chunkX, chunkZ).thenCompose(ignore ->
				world.getChunkAtAsyncUrgently(chunkX, chunkZ)
		).thenAccept(chunk -> {
			if (cancelled || future.isDone()) {
				return;
			}

			Bukkit.getScheduler().runTask(plugin, () -> {
				if (cancelled || future.isDone()) {
					return;
				}

				int floorY = world.getHighestBlockYAt(x, z);
				if (floorY <= world.getMinHeight()) {
					LanguageService.Language defaultLang = languageService.getDefaultLanguage();
					String message = Messages.getString(defaultLang, Messages.MessageKey.ATTEMPT_Y_TOO_LOW, attempt + 1, playerName, x, z);
					plugin.getLogger().info("[LootRush] " + message);
					if (participants != null) {
						for (Player participant : participants) {
							if (participant != null && participant.isOnline()) {
								LanguageService.Language participantLang = languageService.getLanguage(participant);
								participant.sendMessage(Messages.get(participantLang, Messages.MessageKey.ATTEMPT_Y_TOO_LOW, attempt + 1, playerName, x, z));
							}
						}
					}
					findRandomLocationAttempt(world, existingLocations, future, attempt + 1, playerName, participants);
					return;
				}

				Block floor = world.getBlockAt(x, floorY, z);
				int feetY = floorY + 1;
				Block feet = world.getBlockAt(x, feetY, z);
				Block head = world.getBlockAt(x, feetY + 1, z);

				if (isSafeFloor(floor) && isPassable(feet) && isPassable(head)) {
					LanguageService.Language defaultLang = languageService.getDefaultLanguage();
					String message = Messages.getString(defaultLang, Messages.MessageKey.ATTEMPT_LOCATION_FOUND, attempt + 1, playerName, x, feetY, z);
					plugin.getLogger().info("[LootRush] " + message + " on block " + floor.getType());
					if (!cancelled && participants != null) {
						for (Player participant : participants) {
							if (participant != null && participant.isOnline()) {
								LanguageService.Language participantLang = languageService.getLanguage(participant);
								participant.sendMessage(Messages.get(participantLang, Messages.MessageKey.ATTEMPT_LOCATION_FOUND, attempt + 1, playerName, x, feetY, z));
							}
						}
					}
					Location finalLocation = new Location(world, x + 0.5, feetY, z + 0.5);
					preloadSurroundingChunks(world, chunkX, chunkZ).whenComplete((ignored, ex) -> {
						if (cancelled) {
							if (!future.isDone()) {
								Bukkit.getScheduler().runTask(plugin, () -> future.cancel(false));
							}
							return;
						}
						if (ex != null) {
							plugin.getLogger().warning("Не удалось прогрузить соседние чанки для точки телепорта: " + ex.getMessage());
						}
						refreshChunksAround(world, chunkX, chunkZ);
						if (!future.isDone()) {
							future.complete(finalLocation);
						}
					});
				} else {
					LanguageService.Language defaultLang = languageService.getDefaultLanguage();
					String message = Messages.getString(defaultLang, Messages.MessageKey.ATTEMPT_UNSAFE_BLOCKS, attempt + 1, playerName, x, feetY, z, floor.getType(), feet.getType(), head.getType());
					plugin.getLogger().info("[LootRush] " + message);
					if (!cancelled && participants != null) {
						for (Player participant : participants) {
							if (participant != null && participant.isOnline()) {
								LanguageService.Language participantLang = languageService.getLanguage(participant);
								participant.sendMessage(Messages.get(participantLang, Messages.MessageKey.ATTEMPT_UNSAFE_BLOCKS, attempt + 1, playerName, x, feetY, z, floor.getType(), feet.getType(), head.getType()));
							}
						}
					}
					findRandomLocationAttempt(world, existingLocations, future, attempt + 1, playerName, participants);
				}
			});
		}).exceptionally(ex -> {
			if (!cancelled) {
				plugin.getLogger().warning("Ошибка загрузки чанка для телепорта: " + ex.getMessage());
				if (participants != null) {
					for (Player participant : participants) {
						if (participant != null && participant.isOnline()) {
							LanguageService.Language participantLang = languageService.getLanguage(participant);
							participant.sendMessage(Messages.get(participantLang, Messages.MessageKey.CHUNK_LOAD_ERROR, ex.getMessage()));
						}
					}
				}
			}
			if (!cancelled && !future.isDone()) {
				findRandomLocationAttempt(world, existingLocations, future, attempt + 1, playerName, participants);
			}
			return null;
		});
	}

	private CompletableFuture<Void> preloadAllChunksParallel(List<PlayerScatterTarget> targets, List<PlayerScatterTarget> allTargets) {
		if (cancelled || targets.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}

		Set<String> nearChunkKeys = new HashSet<>();
		Set<String> farChunkKeys = new HashSet<>();
		Map<String, World> chunkWorlds = new HashMap<>();

		for (PlayerScatterTarget target : targets) {
			Location loc = target.location();
			int chunkX = loc.getBlockX() >> 4;
			int chunkZ = loc.getBlockZ() >> 4;
			World world = loc.getWorld();

			for (int dx = -6; dx <= 6; dx++) {
				for (int dz = -6; dz <= 6; dz++) {
					int cx = chunkX + dx;
					int cz = chunkZ + dz;
					String key = world.getName() + ":" + cx + ":" + cz;
					nearChunkKeys.add(key);
					chunkWorlds.put(key, world);
				}
			}

			for (int dx = -12; dx <= 12; dx++) {
				for (int dz = -12; dz <= 12; dz++) {
					if (Math.abs(dx) <= 6 && Math.abs(dz) <= 6) {
						continue;
					}
					int cx = chunkX + dx;
					int cz = chunkZ + dz;
					String key = world.getName() + ":" + cx + ":" + cz;
					farChunkKeys.add(key);
					chunkWorlds.put(key, world);
				}
			}
		}

		int totalNearChunks = nearChunkKeys.size();
		int totalFarChunks = farChunkKeys.size();
		int totalChunks = totalNearChunks + totalFarChunks;

		Bukkit.getScheduler().runTask(plugin, () -> {
			if (cancelled) {
				return;
			}
			for (PlayerScatterTarget target : allTargets) {
				Player player = Bukkit.getPlayer(target.playerId());
				if (player != null && player.isOnline()) {
					LanguageService.Language playerLang = languageService.getLanguage(player);
					player.sendMessage(Component.text()
							.append(Messages.get(playerLang, Messages.MessageKey.LOADING_NEAR_CHUNKS))
							.append(Component.text(totalNearChunks, NamedTextColor.AQUA))
							.append(Messages.get(playerLang, Messages.MessageKey.CHUNKS_TEXT))
							.build());
				}
			}
		});

		List<CompletableFuture<Chunk>> nearChunkFutures = new ArrayList<>();
		int[] nearChunkCounter = {0};
		for (String key : nearChunkKeys) {
			String[] parts = key.split(":");
			World world = chunkWorlds.get(key);
			int chunkX = Integer.parseInt(parts[1]);
			int chunkZ = Integer.parseInt(parts[2]);

			CompletableFuture<Chunk> chunkFuture = world.getChunkAtAsyncUrgently(chunkX, chunkZ)
				.thenApply(chunk -> {
					if (!cancelled) {
						Bukkit.getScheduler().runTask(plugin, () -> {
							if (!cancelled) {
								nearChunkCounter[0]++;
								for (PlayerScatterTarget target : allTargets) {
									Player player = Bukkit.getPlayer(target.playerId());
									if (player != null && player.isOnline()) {
										LanguageService.Language playerLang = languageService.getLanguage(player);
										player.sendMessage(Messages.get(playerLang, Messages.MessageKey.CHUNK_LOADED_WITH_COORDS, chunkX, chunkZ, nearChunkCounter[0], totalNearChunks));
									}
								}
							}
						});
					}
					return chunk;
				});
			trackOperation(chunkFuture);
			nearChunkFutures.add(chunkFuture);
		}

		return CompletableFuture.allOf(nearChunkFutures.toArray(CompletableFuture[]::new))
			.thenRun(() -> {
				Bukkit.getScheduler().runTask(plugin, () -> {
					if (cancelled) {
						return;
					}

					for (String key : nearChunkKeys) {
						String[] parts = key.split(":");
						World world = chunkWorlds.get(key);
						int chunkX = Integer.parseInt(parts[1]);
						int chunkZ = Integer.parseInt(parts[2]);
						world.refreshChunk(chunkX, chunkZ);
					}

					for (PlayerScatterTarget target : allTargets) {
						Player player = Bukkit.getPlayer(target.playerId());
						if (player != null && player.isOnline()) {
							LanguageService.Language playerLang = languageService.getLanguage(player);
							player.sendMessage(Component.text()
									.append(Messages.get(playerLang, Messages.MessageKey.NEAR_CHUNKS_LOADED))
									.append(Messages.get(playerLang, Messages.MessageKey.LOADING_FAR_CHUNKS))
									.append(Component.text(totalFarChunks, NamedTextColor.AQUA))
									.append(Messages.get(playerLang, Messages.MessageKey.CHUNKS_TEXT))
									.build());
						}
					}
				});
			})
			.thenCompose(v -> {
				if (cancelled) {
					return CompletableFuture.completedFuture(null);
				}

				List<CompletableFuture<Chunk>> farChunkFutures = new ArrayList<>();
				int[] farChunkCounter = {0};
				for (String key : farChunkKeys) {
					String[] parts = key.split(":");
					World world = chunkWorlds.get(key);
			int chunkX = Integer.parseInt(parts[1]);
			int chunkZ = Integer.parseInt(parts[2]);

			CompletableFuture<Chunk> chunkFuture = world.getChunkAtAsyncUrgently(chunkX, chunkZ)
				.thenApply(chunk -> {
					if (!cancelled) {
						Bukkit.getScheduler().runTask(plugin, () -> {
									if (!cancelled) {
										farChunkCounter[0]++;
										for (PlayerScatterTarget target : allTargets) {
											Player player = Bukkit.getPlayer(target.playerId());
											if (player != null && player.isOnline()) {
												LanguageService.Language playerLang = languageService.getLanguage(player);
												player.sendMessage(Messages.get(playerLang, Messages.MessageKey.CHUNK_LOADED_WITH_COORDS, chunkX, chunkZ, farChunkCounter[0], totalFarChunks));
											}
										}
									}
								});
							}
							return chunk;
						});
					trackOperation(chunkFuture);
					farChunkFutures.add(chunkFuture);
				}

				return CompletableFuture.allOf(farChunkFutures.toArray(CompletableFuture[]::new))
					.thenRun(() -> {
						Bukkit.getScheduler().runTask(plugin, () -> {
							if (cancelled) {
								return;
							}

							for (String key : farChunkKeys) {
								String[] parts = key.split(":");
								World world = chunkWorlds.get(key);
								int chunkX = Integer.parseInt(parts[1]);
								int chunkZ = Integer.parseInt(parts[2]);
							world.refreshChunk(chunkX, chunkZ);
						}

						for (PlayerScatterTarget target : allTargets) {
								Player player = Bukkit.getPlayer(target.playerId());
								if (player != null && player.isOnline()) {
									LanguageService.Language playerLang = languageService.getLanguage(player);
									player.sendMessage(Component.text()
											.append(Messages.get(playerLang, Messages.MessageKey.ALL_CHUNKS_LOADED))
											.append(Messages.get(playerLang, Messages.MessageKey.CHUNKS_COUNT, totalChunks))
											.build());
								}
							}
						});
					});
			});
	}

	private void refreshChunksForPlayer(Player player, Location location) {
		World world = location.getWorld();
		int chunkX = location.getBlockX() >> 4;
		int chunkZ = location.getBlockZ() >> 4;

		for (int dx = -5; dx <= 5; dx++) {
			for (int dz = -5; dz <= 5; dz++) {
				int cx = chunkX + dx;
				int cz = chunkZ + dz;
				world.refreshChunk(cx, cz);
			}
		}

		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (player.isOnline() && player.getWorld().equals(world)) {
				for (int dx = -8; dx <= 8; dx++) {
					for (int dz = -8; dz <= 8; dz++) {
						if (Math.abs(dx) <= 5 && Math.abs(dz) <= 5) {
							continue;
						}
						int cx = chunkX + dx;
						int cz = chunkZ + dz;
						world.refreshChunk(cx, cz);
					}
				}
			}
		}, 5L);

		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (player.isOnline() && player.getWorld().equals(world)) {
				for (int dx = -12; dx <= 12; dx++) {
					for (int dz = -12; dz <= 12; dz++) {
						if (Math.abs(dx) <= 8 && Math.abs(dz) <= 8) {
							continue;
						}
						int cx = chunkX + dx;
						int cz = chunkZ + dz;
						world.refreshChunk(cx, cz);
					}
				}
			}
		}, 15L);
	}

	private CompletableFuture<Void> preloadSurroundingChunks(World world, int chunkX, int chunkZ) {
		List<CompletableFuture<Chunk>> futures = new ArrayList<>();
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				int cx = chunkX + dx;
				int cz = chunkZ + dz;
				futures.add(world.getChunkAtAsyncUrgently(cx, cz));
			}
		}
		return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
	}

	private void refreshChunksAround(World world, int chunkX, int chunkZ) {
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				world.refreshChunk(chunkX + dx, chunkZ + dz);
			}
		}
	}

	private Location fallbackRandomLocation(World world, List<Location> existingLocations) {
		Location spawn = world.getSpawnLocation().clone();
		int offset = 5000 + random.nextInt(5000);
		spawn.add(offset, 0, offset);
		return spawn;
	}

	private boolean isSafeFloor(Block block) {
		Material type = block.getType();
		return type.isSolid() && !UNSAFE_FLOOR_BLOCKS.contains(type);
	}

	private boolean isPassable(Block block) {
		return block.isPassable() || block.isEmpty();
	}

	private int randomCoordinate() {
		int range = maxScatterCoord - minScatterCoord;
		if (range <= 0) range = 1;
		int base = minScatterCoord + random.nextInt(range + 1);
		return random.nextBoolean() ? base : -base;
	}

	private boolean isFarEnough(int x, int z, World world, List<Location> existingLocations) {
		long minDistanceSq = (long) MIN_PLAYER_DISTANCE * (long) MIN_PLAYER_DISTANCE;
		for (Location existing : existingLocations) {
			if (!existing.getWorld().equals(world)) {
				continue;
			}
			double dx = existing.getX() - x;
			double dz = existing.getZ() - z;
			if ((dx * dx) + (dz * dz) < minDistanceSq) {
				return false;
			}
		}
		return true;
	}

	private Map<LanguageService.Language, BossBar> createScatterBossBar(Collection<? extends Player> viewers, int totalPlayers) {
		Map<LanguageService.Language, BossBar> bars = new HashMap<>();

		for (LanguageService.Language lang : LanguageService.Language.values()) {
			String title = Messages.getString(lang, Messages.MessageKey.SCATTER_BOSS_BAR, 0, totalPlayers);
			BossBar bossBar = Bukkit.createBossBar(title, BarColor.BLUE, BarStyle.SOLID);
			bossBar.setProgress(totalPlayers == 0 ? 1.0 : 0.0);
			bossBar.setVisible(true);
			bars.put(lang, bossBar);
		}

		for (Player viewer : viewers) {
			LanguageService.Language lang = languageService.getLanguage(viewer);
			BossBar bar = bars.get(lang);
			if (bar != null) {
				bar.addPlayer(viewer);
			}
		}
		return bars;
	}

	private void updateScatterBossBar(Map<LanguageService.Language, BossBar> bossBars, int ready, int total, String playerName, boolean searching) {
		if (bossBars == null || total <= 0) {
			return;
		}

		double progress = Math.min(1.0, Math.max(0.0, (double) ready / total));

		for (Map.Entry<LanguageService.Language, BossBar> entry : bossBars.entrySet()) {
			BossBar bossBar = entry.getValue();
			bossBar.setProgress(progress);
			String status = Messages.getString(entry.getKey(), Messages.MessageKey.SCATTER_BOSS_BAR, ready, total);
			bossBar.setTitle(status);
		}
	}

	private void hideScatterBossBar(Map<LanguageService.Language, BossBar> bossBars, boolean success) {
		if (bossBars == null) {
			return;
		}

		// Remove boss bar immediately if successful to avoid conflict with GameInfoService
		if (success) {
			for (BossBar bossBar : bossBars.values()) {
				bossBar.removeAll();
				bossBar.setVisible(false);
			}
			return;
		}

		for (Map.Entry<LanguageService.Language, BossBar> entry : bossBars.entrySet()) {
			BossBar bossBar = entry.getValue();
			bossBar.setProgress(bossBar.getProgress());
			String title = Messages.getString(entry.getKey(), Messages.MessageKey.TELEPORTATION_STOPPED);
			bossBar.setTitle(title);
		}

		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			for (BossBar bossBar : bossBars.values()) {
				bossBar.removeAll();
				bossBar.setVisible(false);
			}
		}, 40L);
	}

	public void clearBossBar() {
		for (BossBar bossBar : currentBossBars.values()) {
			bossBar.removeAll();
			bossBar.setVisible(false);
		}
		currentBossBars.clear();
	}

	public void onPlayerLanguageChanged(Player player, LanguageService.Language oldLang, LanguageService.Language newLang) {
		if (player == null || oldLang == null || newLang == null || oldLang == newLang || currentBossBars.isEmpty()) {
			return;
		}

		BossBar oldBar = currentBossBars.get(oldLang);
		BossBar newBar = currentBossBars.get(newLang);
		if (oldBar == null && newBar == null) {
			return;
		}

		Bukkit.getScheduler().runTask(plugin, () -> {
			if (oldBar != null) {
				oldBar.removePlayer(player);
			}
			if (newBar != null) {
				newBar.addPlayer(player);
			}
		});
	}

	private void trackOperation(CompletableFuture<?> future) {
		if (future != null && !future.isDone()) {
			activeOperations.add(future);
		}
	}

	public void cancel() {
		cancelled = true;
		clearBossBar();
		for (CompletableFuture<?> operation : activeOperations) {
			if (operation != null && !operation.isDone()) {
				operation.cancel(false);
			}
		}
		activeOperations.clear();
	}

	private String formatLocation(Location location) {
		return "(" + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + ")";
	}

	private record PlayerScatterTarget(UUID playerId, Location location) {
	}
}
