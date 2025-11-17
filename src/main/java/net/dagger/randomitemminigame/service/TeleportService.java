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
	private static final int MIN_SCATTER_COORD = 10000;
	private static final int MAX_SCATTER_COORD = 100000;
	private static final int MIN_PLAYER_DISTANCE = 10000;
	private static final int SAFE_LOCATION_ATTEMPTS = 256;
	private final JavaPlugin plugin;
	private final Consumer<Component> participantBroadcast;
	private final Random random = new Random();
	private BossBar currentBossBar = null;
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

	public TeleportService(JavaPlugin plugin, Consumer<Component> participantBroadcast) {
		this.plugin = plugin;
		this.participantBroadcast = participantBroadcast;
	}

	public CompletableFuture<Void> scatterPlayers(List<Player> players) {
		// Отменяем все предыдущие операции перед новым запуском
		cancelled = true;
		clearBossBar();
		// Отменяем все активные операции
		for (CompletableFuture<?> operation : activeOperations) {
			if (operation != null && !operation.isDone()) {
				operation.cancel(false);
			}
		}
		activeOperations.clear();
		// Сбрасываем флаг отмены при новом запуске
		cancelled = false;
		BossBar scatterBar = createScatterBossBar(players, players.size());
		currentBossBar = scatterBar;
		CompletableFuture<Void> mainFuture = teleportPlayers(players, scatterBar);
		trackOperation(mainFuture);
		return mainFuture.whenComplete((ignored, throwable) -> {
			hideScatterBossBar(scatterBar, throwable == null && !cancelled);
			if (currentBossBar == scatterBar) {
				currentBossBar = null;
			}
			activeOperations.remove(mainFuture);
		});
	}

	private CompletableFuture<Void> teleportPlayers(List<Player> players, BossBar scatterBar) {
		if (players.isEmpty() || cancelled) {
			return CompletableFuture.completedFuture(null);
		}

		CompletableFuture<List<PlayerScatterTarget>> targetsFuture = prepareScatterTargets(players, new ArrayList<>(), 0, scatterBar);
		trackOperation(targetsFuture);
		return targetsFuture.thenCompose(targets -> {
			if (cancelled) {
				return CompletableFuture.completedFuture(null);
			}

			List<PlayerScatterTarget> targetsList = new ArrayList<>(targets);

			// Уведомляем игроков о начале загрузки чанков
			Bukkit.getScheduler().runTask(plugin, () -> {
				if (cancelled) {
					return;
				}
				for (PlayerScatterTarget target : targetsList) {
					Player player = Bukkit.getPlayer(target.playerId());
					if (player != null && player.isOnline()) {
						player.sendMessage(Component.text("Загружаем чанки для телепортации...", NamedTextColor.YELLOW));
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

					plugin.getLogger().info(String.format("[RandomItem] Teleporting %s to %.1f %.1f %.1f",
							player.getName(), target.location().getX(), target.location().getY(), target.location().getZ()));
					player.teleport(target.location());
					// Очищаем инвентарь игрока после телепортации
					player.getInventory().clear();
					player.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[] { null, null, null, null });
					player.getInventory().setItemInOffHand(null);
					// Устанавливаем точку спавна игрока на его стартовую позицию в раунде
					player.setRespawnLocation(target.location(), true);

					// Принудительно обновляем чанки вокруг игрока, чтобы они стали видимыми
					refreshChunksForPlayer(player, target.location());

					// Устанавливаем полную неуязвимость на 10 секунд (200 тиков) после телепортации
					// Используем задержку, чтобы телепортация не сбросила неуязвимость
					// Это предотвращает убийство игроков прыжком с высоты, в лаву, утоплением и т.д.
					Bukkit.getScheduler().runTaskLater(plugin, () -> {
						if (player.isOnline() && !cancelled) {
							// Устанавливаем полную неуязвимость
							player.setInvulnerable(true);
							// Также устанавливаем максимальное значение noDamageTicks для дополнительной защиты
							player.setNoDamageTicks(Integer.MAX_VALUE);
							// Через 10 секунд (200 тиков) отключаем неуязвимость
							Bukkit.getScheduler().runTaskLater(plugin, () -> {
								if (player.isOnline() && !cancelled) {
									player.setInvulnerable(false);
									// Устанавливаем стандартное значение noDamageTicks
									player.setNoDamageTicks(20);
								}
							}, 200L);
						}
					}, 1L);

					player.sendMessage(Component.text("Вы телепортированы в " + formatLocation(target.location()), NamedTextColor.GRAY));
				}
			})).whenComplete((ignored, throwable) -> {
				activeOperations.remove(chunksFuture);
			});
		}).whenComplete((ignored, throwable) -> {
			activeOperations.remove(targetsFuture);
		});
	}

	private CompletableFuture<List<PlayerScatterTarget>> prepareScatterTargets(List<Player> players, List<PlayerScatterTarget> accumulator, int index,
			BossBar scatterBar) {
		if (cancelled || index >= players.size()) {
			return CompletableFuture.completedFuture(new ArrayList<>(accumulator));
		}

		Player player = players.get(index);
		participantBroadcast.accept(Component.text("Ищем место для " + player.getName() + "...", NamedTextColor.YELLOW));
		updateScatterBossBar(scatterBar, accumulator.size(), players.size(), player.getName(), true);
		List<Location> existingLocations = accumulator.stream()
				.map(PlayerScatterTarget::location)
				.toList();

		CompletableFuture<Location> locationFuture = findRandomLocationAsync(player.getWorld(), existingLocations, player.getName());
		trackOperation(locationFuture);
		return locationFuture.thenCompose(location -> {
			if (cancelled) {
				activeOperations.remove(locationFuture);
				return CompletableFuture.completedFuture(new ArrayList<>(accumulator));
			}

			participantBroadcast.accept(Component.text("Нашли место для " + player.getName() + ": " + formatLocation(location), NamedTextColor.GREEN));
			accumulator.add(new PlayerScatterTarget(player.getUniqueId(), location));
			updateScatterBossBar(scatterBar, accumulator.size(), players.size(), player.getName(), false);
			CompletableFuture<List<PlayerScatterTarget>> nextFuture = prepareScatterTargets(players, accumulator, index + 1, scatterBar);
			nextFuture.whenComplete((ignored, throwable) -> {
				activeOperations.remove(locationFuture);
			});
			return nextFuture;
		});
	}

	private CompletableFuture<Location> findRandomLocationAsync(World world, List<Location> existingLocations, String playerName) {
		CompletableFuture<Location> future = new CompletableFuture<>();
		trackOperation(future);
		findRandomLocationAttempt(world, existingLocations, future, 0, playerName);
		return future;
	}

	private void findRandomLocationAttempt(World world, List<Location> existingLocations, CompletableFuture<Location> future, int attempt,
			String playerName) {
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
			if (!cancelled) {
				participantBroadcast.accept(Component.text("Попытка #" + (attempt + 1) + " для " + playerName + ": ("
						+ x + ", ???, " + z + ") слишком близко к другим игрокам", NamedTextColor.GRAY));
			}
			findRandomLocationAttempt(world, existingLocations, future, attempt + 1, playerName);
			return;
		}

		int chunkX = x >> 4;
		int chunkZ = z >> 4;

		// Предзагружаем соседние чанки параллельно для ускорения проверки безопасности
		preloadSurroundingChunks(world, chunkX, chunkZ);

		world.getChunkAtAsyncUrgently(chunkX, chunkZ).thenAccept(chunk -> {
			if (cancelled || future.isDone()) {
				return;
			}

			Bukkit.getScheduler().runTask(plugin, () -> {
				if (cancelled || future.isDone()) {
					return;
				}

				int floorY = world.getHighestBlockYAt(x, z);
				if (floorY <= world.getMinHeight()) {
					String message = String.format("Попытка #%d для %s: (%d, %d) отклонена — Y ниже минимума", attempt + 1, playerName, x, z);
					plugin.getLogger().info("[RandomItem] " + message);
					participantBroadcast.accept(Component.text(message, NamedTextColor.GRAY));
					findRandomLocationAttempt(world, existingLocations, future, attempt + 1, playerName);
					return;
				}

				Block floor = world.getBlockAt(x, floorY, z);
				int feetY = floorY + 1;
				Block feet = world.getBlockAt(x, feetY, z);
				Block head = world.getBlockAt(x, feetY + 1, z);

				if (isSafeFloor(floor) && isPassable(feet) && isPassable(head)) {
					String message = String.format("Попытка #%d для %s: найдена точка (%d, %d, %d)",
							attempt + 1, playerName, x, feetY, z);
					plugin.getLogger().info("[RandomItem] " + message + " на блоке " + floor.getType());
					if (!cancelled) {
						participantBroadcast.accept(Component.text(message, NamedTextColor.GREEN));
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
					String message = String.format("Попытка #%d для %s: (%d, %d, %d) отклонена — блоки небезопасны (floor=%s, feet=%s, head=%s)",
							attempt + 1, playerName, x, feetY, z, floor.getType(), feet.getType(), head.getType());
					plugin.getLogger().info("[RandomItem] " + message);
					if (!cancelled) {
						participantBroadcast.accept(Component.text(message, NamedTextColor.GRAY));
					}
					findRandomLocationAttempt(world, existingLocations, future, attempt + 1, playerName);
				}
			});
		}).exceptionally(ex -> {
			if (!cancelled) {
				plugin.getLogger().warning("Ошибка загрузки чанка для телепорта: " + ex.getMessage());
				participantBroadcast.accept(Component.text("Ошибка при прогрузке чанка: " + ex.getMessage(), NamedTextColor.RED));
			}
			if (!cancelled && !future.isDone()) {
				findRandomLocationAttempt(world, existingLocations, future, attempt + 1, playerName);
			}
			return null;
		});
	}

	private CompletableFuture<Void> preloadAllChunksParallel(List<PlayerScatterTarget> targets, List<PlayerScatterTarget> allTargets) {
		if (cancelled || targets.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}

		// Разделяем чанки на ближние (приоритетные) и дальние для двухэтапной загрузки
		Set<String> nearChunkKeys = new HashSet<>();
		Set<String> farChunkKeys = new HashSet<>();
		Map<String, World> chunkWorlds = new HashMap<>();

		for (PlayerScatterTarget target : targets) {
			Location loc = target.location();
			int chunkX = loc.getBlockX() >> 4;
			int chunkZ = loc.getBlockZ() >> 4;
			World world = loc.getWorld();

			// Ближние чанки (радиус 6x6) - загружаем в первую очередь для быстрой визуализации
			for (int dx = -6; dx <= 6; dx++) {
				for (int dz = -6; dz <= 6; dz++) {
					int cx = chunkX + dx;
					int cz = chunkZ + dz;
					String key = world.getName() + ":" + cx + ":" + cz;
					nearChunkKeys.add(key);
					chunkWorlds.put(key, world);
				}
			}

			// Дальние чанки (радиус 12x12, исключая ближние) - загружаем во вторую очередь
			for (int dx = -12; dx <= 12; dx++) {
				for (int dz = -12; dz <= 12; dz++) {
					// Пропускаем ближние чанки
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

		// Уведомляем о начале загрузки ближних чанков
		Bukkit.getScheduler().runTask(plugin, () -> {
			if (cancelled) {
				return;
			}
			for (PlayerScatterTarget target : allTargets) {
				Player player = Bukkit.getPlayer(target.playerId());
				if (player != null && player.isOnline()) {
					player.sendMessage(Component.text()
							.append(Component.text("Загружаем ближние чанки: ", NamedTextColor.YELLOW))
							.append(Component.text(totalNearChunks, NamedTextColor.AQUA))
							.append(Component.text(" чанков...", NamedTextColor.YELLOW))
							.build());
				}
			}
		});

		// Сначала загружаем ближние чанки (приоритетные)
		List<CompletableFuture<Chunk>> nearChunkFutures = new ArrayList<>();
		int[] nearChunkCounter = {0};
		for (String key : nearChunkKeys) {
			String[] parts = key.split(":");
			World world = chunkWorlds.get(key);
			int chunkX = Integer.parseInt(parts[1]);
			int chunkZ = Integer.parseInt(parts[2]);

			// Создаём CompletableFuture с логированием каждого чанка
			CompletableFuture<Chunk> chunkFuture = world.getChunkAtAsyncUrgently(chunkX, chunkZ)
				.thenApply(chunk -> {
					if (!cancelled) {
						// Отправляем сообщение о загрузке каждого чанка
						Bukkit.getScheduler().runTask(plugin, () -> {
							if (!cancelled) {
								nearChunkCounter[0]++;
								for (PlayerScatterTarget target : allTargets) {
									Player player = Bukkit.getPlayer(target.playerId());
									if (player != null && player.isOnline()) {
										player.sendMessage(Component.text()
												.append(Component.text("Загружен чанк ", NamedTextColor.GRAY))
												.append(Component.text("[" + chunkX + ", " + chunkZ + "]", NamedTextColor.AQUA))
												.append(Component.text(" (" + nearChunkCounter[0] + "/" + totalNearChunks + ")", NamedTextColor.YELLOW))
												.build());
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

		// Загружаем ближние чанки и сразу обновляем их
		return CompletableFuture.allOf(nearChunkFutures.toArray(CompletableFuture[]::new))
			.thenRun(() -> {
				// Обновляем ближние чанки на главном потоке
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

					// Уведомляем о завершении загрузки ближних чанков
					for (PlayerScatterTarget target : allTargets) {
						Player player = Bukkit.getPlayer(target.playerId());
						if (player != null && player.isOnline()) {
							player.sendMessage(Component.text()
									.append(Component.text("Ближние чанки загружены! ", NamedTextColor.GREEN))
									.append(Component.text("Загружаем дальние чанки: ", NamedTextColor.YELLOW))
									.append(Component.text(totalFarChunks, NamedTextColor.AQUA))
									.append(Component.text(" чанков...", NamedTextColor.YELLOW))
									.build());
						}
					}
				});
			})
			.thenCompose(v -> {
				if (cancelled) {
					return CompletableFuture.completedFuture(null);
				}

				// Затем загружаем дальние чанки параллельно
				List<CompletableFuture<Chunk>> farChunkFutures = new ArrayList<>();
				int[] farChunkCounter = {0};
				for (String key : farChunkKeys) {
					String[] parts = key.split(":");
					World world = chunkWorlds.get(key);
					int chunkX = Integer.parseInt(parts[1]);
					int chunkZ = Integer.parseInt(parts[2]);

					// Создаём CompletableFuture с логированием каждого чанка
					CompletableFuture<Chunk> chunkFuture = world.getChunkAtAsyncUrgently(chunkX, chunkZ)
						.thenApply(chunk -> {
							if (!cancelled) {
								// Отправляем сообщение о загрузке каждого чанка
								Bukkit.getScheduler().runTask(plugin, () -> {
									if (!cancelled) {
										farChunkCounter[0]++;
										for (PlayerScatterTarget target : allTargets) {
											Player player = Bukkit.getPlayer(target.playerId());
											if (player != null && player.isOnline()) {
												player.sendMessage(Component.text()
														.append(Component.text("Загружен чанк ", NamedTextColor.GRAY))
														.append(Component.text("[" + chunkX + ", " + chunkZ + "]", NamedTextColor.AQUA))
														.append(Component.text(" (" + farChunkCounter[0] + "/" + totalFarChunks + ")", NamedTextColor.YELLOW))
														.build());
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
						// Обновляем дальние чанки на главном потоке
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

							// Уведомляем о завершении загрузки всех чанков
							for (PlayerScatterTarget target : allTargets) {
								Player player = Bukkit.getPlayer(target.playerId());
								if (player != null && player.isOnline()) {
									player.sendMessage(Component.text()
											.append(Component.text("Все чанки загружены! ", NamedTextColor.GREEN))
											.append(Component.text("(" + totalChunks + " чанков)", NamedTextColor.GRAY))
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

		// Обновляем ближние чанки в радиусе 5x5 вокруг игрока для немедленной визуализации
		for (int dx = -5; dx <= 5; dx++) {
			for (int dz = -5; dz <= 5; dz++) {
				int cx = chunkX + dx;
				int cz = chunkZ + dz;
				world.refreshChunk(cx, cz);
			}
		}

		// Обновляем средние чанки в радиусе 8x8 через небольшую задержку
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (player.isOnline() && player.getWorld().equals(world)) {
				for (int dx = -8; dx <= 8; dx++) {
					for (int dz = -8; dz <= 8; dz++) {
						// Пропускаем уже обновлённые ближние чанки
						if (Math.abs(dx) <= 5 && Math.abs(dz) <= 5) {
							continue;
						}
						int cx = chunkX + dx;
						int cz = chunkZ + dz;
						world.refreshChunk(cx, cz);
					}
				}
			}
		}, 5L); // 5 тиков = 0.25 секунды

		// Обновляем дальние чанки в радиусе 12x12 для полного покрытия
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (player.isOnline() && player.getWorld().equals(world)) {
				for (int dx = -12; dx <= 12; dx++) {
					for (int dz = -12; dz <= 12; dz++) {
						// Пропускаем уже обновлённые чанки
						if (Math.abs(dx) <= 8 && Math.abs(dz) <= 8) {
							continue;
						}
						int cx = chunkX + dx;
						int cz = chunkZ + dz;
						world.refreshChunk(cx, cz);
					}
				}
			}
		}, 15L); // 15 тиков = 0.75 секунды
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
		int range = MAX_SCATTER_COORD - MIN_SCATTER_COORD;
		int base = MIN_SCATTER_COORD + random.nextInt(range + 1);
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

	private BossBar createScatterBossBar(Collection<? extends Player> viewers, int totalPlayers) {
		BossBar bossBar = Bukkit.createBossBar("Поиск безопасных локаций... 0/" + totalPlayers, BarColor.BLUE, BarStyle.SOLID);
		bossBar.setProgress(totalPlayers == 0 ? 1.0 : 0.0);
		for (Player viewer : viewers) {
			bossBar.addPlayer(viewer);
		}
		bossBar.setVisible(true);
		return bossBar;
	}

	private void updateScatterBossBar(BossBar bossBar, int ready, int total, String playerName, boolean searching) {
		if (bossBar == null || total <= 0) {
			return;
		}

		double progress = Math.min(1.0, Math.max(0.0, (double) ready / total));
		bossBar.setProgress(progress);

		String status = (searching ? "Ищем место для " : "Готово для ") + playerName + " • " + ready + "/" + total;
		bossBar.setTitle(status);
	}

	private void hideScatterBossBar(BossBar bossBar, boolean success) {
		if (bossBar == null) {
			return;
		}

		bossBar.setProgress(success ? 1.0 : bossBar.getProgress());
		bossBar.setTitle(success ? "Телепортация завершена" : "Телепортация остановлена");

		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			bossBar.removeAll();
			bossBar.setVisible(false);
		}, 40L);
	}

	/**
	 * Принудительно очищает текущий bossbar, если он существует.
	 * Используется при отмене начала игры.
	 */
	public void clearBossBar() {
		if (currentBossBar != null) {
			currentBossBar.removeAll();
			currentBossBar.setVisible(false);
			currentBossBar = null;
		}
	}

	/**
	 * Отслеживает CompletableFuture операцию для возможности её отмены.
	 */
	private void trackOperation(CompletableFuture<?> future) {
		if (future != null && !future.isDone()) {
			activeOperations.add(future);
		}
	}

	/**
	 * Отменяет все текущие операции телепортации и загрузки чанков.
	 * Используется при отмене начала игры.
	 */
	public void cancel() {
		cancelled = true;
		clearBossBar();
		// Отменяем все активные операции
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
