package net.dagger.lootrush.service;

import net.dagger.lootrush.LootRush;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TeleportService {
    private final int minScatterCoord;
    private final int maxScatterCoord;
    private static final int MIN_PLAYER_DISTANCE = 10000;
    private static final int SAFE_LOCATION_ATTEMPTS = 256;
    private static final int MAX_PARALLEL_CHUNK_LOADS = 8;
    private static final Set<Block> UNSAFE_FLOOR_BLOCKS = Set.of(
            Blocks.LAVA,
            Blocks.WATER,
            Blocks.KELP,
            Blocks.KELP_PLANT,
            Blocks.SEAGRASS,
            Blocks.TALL_SEAGRASS,
            Blocks.BUBBLE_COLUMN,
            Blocks.CACTUS,
            Blocks.MAGMA_BLOCK,
            Blocks.CAMPFIRE,
            Blocks.SOUL_CAMPFIRE,
            Blocks.FIRE,
            Blocks.SOUL_FIRE,
            Blocks.POWDER_SNOW,
            Blocks.SWEET_BERRY_BUSH,
            Blocks.COBWEB,
            Blocks.WITHER_ROSE
    );

    private final LanguageService languageService;
    private final Random random = new Random();
    private final Map<LanguageService.Language, ServerBossEvent> currentBossBars = new HashMap<>();
    private volatile boolean cancelled = false;
    private final List<CompletableFuture<?>> activeOperations = new CopyOnWriteArrayList<>();
    private final Map<UUID, Integer> invulnerabilityTicks = new HashMap<>();

    public TeleportService(LanguageService languageService, int minScatterCoord, int maxScatterCoord) {
        this.languageService = languageService;
        this.minScatterCoord = minScatterCoord;
        this.maxScatterCoord = maxScatterCoord;
    }

    public CompletableFuture<Void> scatterPlayers(List<ServerPlayer> players) {
        cancelled = true;
        clearBossBar();
        for (CompletableFuture<?> operation : activeOperations) {
            if (operation != null && !operation.isDone()) {
                operation.cancel(false);
            }
        }
        activeOperations.clear();
        cancelled = false;

        Map<LanguageService.Language, ServerBossEvent> scatterBars = createScatterBossBar(players, players.size());
        currentBossBars.putAll(scatterBars);
        CompletableFuture<Void> mainFuture = teleportPlayers(players, scatterBars);
        trackOperation(mainFuture);
        return mainFuture.whenComplete((ignored, throwable) -> {
            hideScatterBossBar(scatterBars, throwable == null && !cancelled, players.isEmpty() ? null : players.get(0).level().getServer());
            if (currentBossBars.equals(scatterBars)) {
                currentBossBars.clear();
            } else {
                for (ServerBossEvent bar : scatterBars.values()) {
                    currentBossBars.values().remove(bar);
                }
            }
            activeOperations.remove(mainFuture);
        });
    }

    private CompletableFuture<Void> teleportPlayers(List<ServerPlayer> players, Map<LanguageService.Language, ServerBossEvent> scatterBars) {
        if (players.isEmpty() || cancelled) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<List<PlayerScatterTarget>> targetsFuture = prepareScatterTargets(players, new ArrayList<>(), 0, scatterBars);
        trackOperation(targetsFuture);
        return targetsFuture.thenCompose(targets -> {
            if (cancelled || targets.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }

            MinecraftServer server = players.get(0).level().getServer();
            server.execute(() -> {
                for (PlayerScatterTarget target : targets) {
                    ServerPlayer player = players.stream()
                            .filter(p -> p.getUUID().equals(target.playerId()))
                            .findFirst()
                            .orElse(null);
                    if (player == null || player.isRemoved()) {
                        continue;
                    }
                    LanguageService.Language lang = languageService.getLanguage(player);
                    player.sendSystemMessage(Messages.get(lang, Messages.MessageKey.LOADING_CHUNKS));
                }
            });

            CompletableFuture<Void> chunksFuture = preloadAllChunksParallel(targets, targets);
            trackOperation(chunksFuture);
            return chunksFuture.thenRun(() -> {
                if (cancelled) {
                    return;
                }
                server.execute(() -> {
                    if (cancelled) {
                        return;
                    }
                    for (PlayerScatterTarget target : targets) {
                        ServerPlayer player = players.stream()
                                .filter(p -> p.getUUID().equals(target.playerId()))
                                .findFirst()
                                .orElse(null);
                        if (player == null || player.isRemoved()) {
                            continue;
                        }
                        teleportPlayer(player, target.location());
                    }
                });
            }).whenComplete((ignored, throwable) -> activeOperations.remove(chunksFuture));
        }).whenComplete((ignored, throwable) -> activeOperations.remove(targetsFuture));
    }

    private CompletableFuture<List<PlayerScatterTarget>> prepareScatterTargets(
            List<ServerPlayer> players,
            List<PlayerScatterTarget> accumulator,
            int index,
            Map<LanguageService.Language, ServerBossEvent> scatterBars) {
        if (cancelled || index >= players.size()) {
            return CompletableFuture.completedFuture(new ArrayList<>(accumulator));
        }

        ServerPlayer player = players.get(index);
        for (ServerPlayer participant : players) {
            LanguageService.Language participantLang = languageService.getLanguage(participant);
            participant.sendSystemMessage(Messages.get(participantLang, Messages.MessageKey.SEARCHING_LOCATION, player.getName().getString()));
        }
        updateScatterBossBar(scatterBars, accumulator.size(), players.size(), player.getName().getString(), true);

        List<ScatterLocation> existingLocations = accumulator.stream()
                .map(target -> new ScatterLocation(target.level(), target.location()))
                .toList();

        CompletableFuture<BlockPos> locationFuture = findRandomLocationAsync((ServerLevel) player.level(), existingLocations, player.getName().getString(), players);
        trackOperation(locationFuture);
        return locationFuture.thenCompose(location -> {
            if (cancelled) {
                activeOperations.remove(locationFuture);
                return CompletableFuture.completedFuture(new ArrayList<>(accumulator));
            }

            for (ServerPlayer participant : players) {
                LanguageService.Language participantLang = languageService.getLanguage(participant);
                participant.sendSystemMessage(Messages.get(participantLang, Messages.MessageKey.LOCATION_FOUND,
                        player.getName().getString(), formatLocation(location)));
            }
            accumulator.add(new PlayerScatterTarget(player.getUUID(), (ServerLevel) player.level(), location));
            updateScatterBossBar(scatterBars, accumulator.size(), players.size(), player.getName().getString(), false);
            CompletableFuture<List<PlayerScatterTarget>> nextFuture = prepareScatterTargets(players, accumulator, index + 1, scatterBars);
            nextFuture.whenComplete((ignored, throwable) -> activeOperations.remove(locationFuture));
            return nextFuture;
        });
    }

    private CompletableFuture<BlockPos> findRandomLocationAsync(ServerLevel level, List<ScatterLocation> existingLocations, String playerName, List<ServerPlayer> participants) {
        CompletableFuture<BlockPos> future = new CompletableFuture<>();
        trackOperation(future);
        findRandomLocationAttempt(level, existingLocations, future, 0, playerName, participants);
        return future;
    }

    private void findRandomLocationAttempt(ServerLevel level, List<ScatterLocation> existingLocations, CompletableFuture<BlockPos> future, int attempt,
                                           String playerName, List<ServerPlayer> participants) {
        if (cancelled || future.isDone()) {
            if (cancelled && !future.isDone()) {
                level.getServer().execute(() -> future.cancel(false));
            }
            return;
        }

        if (attempt >= SAFE_LOCATION_ATTEMPTS) {
            LootRush.LOGGER.warn("Не удалось найти безопасную точку для телепорта после {} попыток.", SAFE_LOCATION_ATTEMPTS);
            level.getServer().execute(() -> {
                if (!cancelled && !future.isDone()) {
                    future.complete(fallbackRandomLocation(level));
                }
            });
            return;
        }

        int x = randomCoordinate();
        int z = randomCoordinate();

        if (!isFarEnough(level, x, z, existingLocations)) {
            for (ServerPlayer participant : participants) {
                LanguageService.Language lang = languageService.getLanguage(participant);
                participant.sendSystemMessage(Messages.get(lang, Messages.MessageKey.ATTEMPT_TOO_CLOSE, attempt + 1, playerName, x, z));
            }
            findRandomLocationAttempt(level, existingLocations, future, attempt + 1, playerName, participants);
            return;
        }

        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        CompletableFuture<ChunkAccess> chunkFuture = preloadSurroundingChunks(level, chunkX, chunkZ)
                .thenCompose(ignored -> loadChunk(level, chunkX, chunkZ));
        trackOperation(chunkFuture);
        chunkFuture.thenAccept(chunk -> {
            if (cancelled || future.isDone()) {
                return;
            }
            level.getServer().execute(() -> {
                if (cancelled || future.isDone()) {
                    return;
                }
                int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int floorY = topY - 1;
                if (floorY < level.getMinY()) {
                    for (ServerPlayer participant : participants) {
                        LanguageService.Language lang = languageService.getLanguage(participant);
                        participant.sendSystemMessage(Messages.get(lang, Messages.MessageKey.ATTEMPT_Y_TOO_LOW, attempt + 1, playerName, x, z));
                    }
                    findRandomLocationAttempt(level, existingLocations, future, attempt + 1, playerName, participants);
                    return;
                }

                BlockPos floorPos = new BlockPos(x, floorY, z);
                BlockPos feetPos = floorPos.above();
                BlockPos headPos = floorPos.above(2);

                BlockState floor = level.getBlockState(floorPos);
                BlockState feet = level.getBlockState(feetPos);
                BlockState head = level.getBlockState(headPos);

                if (isSafeFloor(floor) && isPassable(level, feetPos) && isPassable(level, headPos)) {
                    for (ServerPlayer participant : participants) {
                        LanguageService.Language lang = languageService.getLanguage(participant);
                        participant.sendSystemMessage(Messages.get(lang, Messages.MessageKey.ATTEMPT_LOCATION_FOUND, attempt + 1, playerName, x, feetPos.getY(), z));
                    }
                    future.complete(feetPos);
                } else {
                    for (ServerPlayer participant : participants) {
                        LanguageService.Language lang = languageService.getLanguage(participant);
                        participant.sendSystemMessage(Messages.get(lang, Messages.MessageKey.ATTEMPT_UNSAFE_BLOCKS, attempt + 1, playerName, x, feetPos.getY(), z,
                                floor.getBlock().getName().getString(), feet.getBlock().getName().getString(), head.getBlock().getName().getString()));
                    }
                    findRandomLocationAttempt(level, existingLocations, future, attempt + 1, playerName, participants);
                }
            });
        }).exceptionally(ex -> {
            if (!cancelled) {
                for (ServerPlayer participant : participants) {
                    LanguageService.Language lang = languageService.getLanguage(participant);
                    participant.sendSystemMessage(Messages.get(lang, Messages.MessageKey.CHUNK_LOAD_ERROR, ex.getMessage()));
                }
                findRandomLocationAttempt(level, existingLocations, future, attempt + 1, playerName, participants);
            }
            return null;
        }).whenComplete((ignored, throwable) -> activeOperations.remove(chunkFuture));
    }

    private CompletableFuture<ChunkAccess> loadChunk(ServerLevel level, int chunkX, int chunkZ) {
        CompletableFuture<ChunkAccess> resultFuture = new CompletableFuture<>();
        MinecraftServer server = level.getServer();
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);

        server.execute(() -> {
            if (cancelled) {
                resultFuture.completeExceptionally(new IllegalStateException("Chunk load cancelled"));
                return;
            }
            CompletableFuture<?> ticketFuture = level.getChunkSource().addTicketAndLoadWithRadius(TicketType.FORCED, pos, 1);
            ticketFuture.whenComplete((ignored, ticketError) -> {
                if (ticketError != null) {
                    resultFuture.completeExceptionally(ticketError);
                    return;
                }
                CompletableFuture<ChunkAccess> chunkFuture = level.getChunkSource()
                        .getChunkFuture(chunkX, chunkZ, ChunkStatus.FULL, true)
                        .thenApply(result -> {
                            ChunkAccess chunk = result.orElse(null);
                            if (chunk == null) {
                                String error = result.getError() == null ? "unknown" : result.getError();
                                throw new IllegalStateException("Chunk load failed: " + error);
                            }
                            return chunk;
                        });

                chunkFuture.whenComplete((chunk, throwable) -> {
                    level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, pos, 1);
                    if (throwable != null) {
                        resultFuture.completeExceptionally(throwable);
                        return;
                    }
                    resultFuture.complete(chunk);
                });
            });
        });

        return resultFuture;
    }

    private CompletableFuture<Void> preloadSurroundingChunks(ServerLevel level, int chunkX, int chunkZ) {
        List<CompletableFuture<ChunkAccess>> futures = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                futures.add(loadChunk(level, chunkX + dx, chunkZ + dz));
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> preloadAllChunksParallel(List<PlayerScatterTarget> targets, List<PlayerScatterTarget> allTargets) {
        if (cancelled || targets.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        Set<ChunkKey> nearChunkKeys = new HashSet<>();
        Set<ChunkKey> farChunkKeys = new HashSet<>();

        for (PlayerScatterTarget target : targets) {
            BlockPos loc = target.location();
            int chunkX = loc.getX() >> 4;
            int chunkZ = loc.getZ() >> 4;
            ServerLevel level = target.level();

            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    nearChunkKeys.add(new ChunkKey(level, chunkX + dx, chunkZ + dz));
                }
            }

            for (int dx = -12; dx <= 12; dx++) {
                for (int dz = -12; dz <= 12; dz++) {
                    if (Math.abs(dx) <= 6 && Math.abs(dz) <= 6) {
                        continue;
                    }
                    farChunkKeys.add(new ChunkKey(level, chunkX + dx, chunkZ + dz));
                }
            }
        }

        int totalNearChunks = nearChunkKeys.size();
        int totalFarChunks = farChunkKeys.size();
        int totalChunks = totalNearChunks + totalFarChunks;
        AtomicInteger totalLoaded = new AtomicInteger(0);

        MinecraftServer server = targets.get(0).level().getServer();
        List<ServerPlayer> players = resolvePlayers(server, allTargets);
        server.execute(() -> {
            if (cancelled) {
                return;
            }
            updateLoadingBossBar(0, totalChunks);
            for (ServerPlayer player : players) {
                LanguageService.Language lang = languageService.getLanguage(player);
                player.sendSystemMessage(Component.empty()
                        .append(Messages.get(lang, Messages.MessageKey.LOADING_NEAR_CHUNKS))
                        .append(Component.literal(String.valueOf(totalNearChunks)).withStyle(net.minecraft.ChatFormatting.WHITE))
                        .append(Messages.get(lang, Messages.MessageKey.CHUNKS_TEXT)));
            }
        });

        return preloadChunkSet(nearChunkKeys, totalNearChunks, totalLoaded, totalChunks, server, players)
                .thenRun(() -> server.execute(() -> {
                    if (cancelled) {
                        return;
                    }
                    for (ServerPlayer player : players) {
                        LanguageService.Language lang = languageService.getLanguage(player);
                        player.sendSystemMessage(Component.empty()
                                .append(Messages.get(lang, Messages.MessageKey.NEAR_CHUNKS_LOADED))
                                .append(Messages.get(lang, Messages.MessageKey.LOADING_FAR_CHUNKS))
                                .append(Component.literal(String.valueOf(totalFarChunks)).withStyle(net.minecraft.ChatFormatting.WHITE))
                                .append(Messages.get(lang, Messages.MessageKey.CHUNKS_TEXT)));
                    }
                }))
                .thenCompose(ignored -> {
                    if (cancelled) {
                        return CompletableFuture.completedFuture(null);
                    }

                    return preloadChunkSet(farChunkKeys, totalFarChunks, totalLoaded, totalChunks, server, players)
                            .thenRun(() -> server.execute(() -> {
                                if (cancelled) {
                                    return;
                                }
                                updateLoadingBossBar(totalChunks, totalChunks);
                                for (ServerPlayer player : players) {
                                    LanguageService.Language lang = languageService.getLanguage(player);
                                    player.sendSystemMessage(Component.empty()
                                            .append(Messages.get(lang, Messages.MessageKey.ALL_CHUNKS_LOADED))
                                            .append(Messages.get(lang, Messages.MessageKey.CHUNKS_COUNT, totalChunks)));
                                }
                            }));
                });
    }

    private CompletableFuture<Void> preloadChunkSet(Set<ChunkKey> chunkKeys, int totalChunks, AtomicInteger totalLoaded,
                                                    int totalAllChunks, MinecraftServer server, List<ServerPlayer> players) {
        if (cancelled || chunkKeys.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> doneFuture = new CompletableFuture<>();
        AtomicInteger loadedCounter = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicBoolean pumpScheduled = new AtomicBoolean();
        java.util.ArrayDeque<ChunkKey> queue = new java.util.ArrayDeque<>(chunkKeys);

        Runnable pump = new Runnable() {
            @Override
            public void run() {
                if (doneFuture.isDone()) {
                    return;
                }
                if (cancelled) {
                    doneFuture.complete(null);
                    return;
                }
                while (inFlight.get() < MAX_PARALLEL_CHUNK_LOADS) {
                    ChunkKey key = queue.poll();
                    if (key == null) {
                        if (inFlight.get() == 0) {
                            doneFuture.complete(null);
                        }
                        return;
                    }
                    inFlight.incrementAndGet();
                    CompletableFuture<Void> chunkFuture = loadChunk(key.level(), key.x(), key.z())
                            .thenAccept(chunk -> {
                                if (cancelled || doneFuture.isDone()) {
                                    return;
                                }
                                int count = loadedCounter.incrementAndGet();
                                int loaded = totalLoaded.incrementAndGet();
                                server.execute(() -> {
                                    if (cancelled || doneFuture.isDone()) {
                                        return;
                                    }
                                    updateLoadingBossBar(loaded, totalAllChunks);
                                    for (ServerPlayer player : players) {
                                        LanguageService.Language lang = languageService.getLanguage(player);
                                        player.sendSystemMessage(Messages.get(lang, Messages.MessageKey.CHUNK_LOADED_WITH_COORDS,
                                                key.x(), key.z(), count, totalChunks));
                                    }
                                });
                            })
                            .exceptionally(ex -> {
                                if (!cancelled && !doneFuture.isDone()) {
                                    server.execute(() -> {
                                        for (ServerPlayer player : players) {
                                            LanguageService.Language lang = languageService.getLanguage(player);
                                            player.sendSystemMessage(Messages.get(lang, Messages.MessageKey.CHUNK_LOAD_ERROR, ex.getMessage()));
                                        }
                                    });
                                }
                                return null;
                            })
                            .whenComplete((ignored, throwable) -> {
                                inFlight.decrementAndGet();
                                schedulePump();
                            });
                    trackOperation(chunkFuture);
                }
            }

            private void schedulePump() {
                if (doneFuture.isDone() || cancelled) {
                    if (!doneFuture.isDone()) {
                        doneFuture.complete(null);
                    }
                    return;
                }
                if (!pumpScheduled.compareAndSet(false, true)) {
                    return;
                }
                CompletableFuture.runAsync(() -> {
                    try {
                        run();
                    } finally {
                        pumpScheduled.set(false);
                        if (!doneFuture.isDone() && (!queue.isEmpty() || inFlight.get() > 0)) {
                            schedulePump();
                        }
                    }
                });
            }
        };

        pump.run();
        return doneFuture;
    }

    private List<ServerPlayer> resolvePlayers(MinecraftServer server, List<PlayerScatterTarget> targets) {
        List<ServerPlayer> players = new ArrayList<>();
        for (PlayerScatterTarget target : targets) {
            ServerPlayer player = server.getPlayerList().getPlayer(target.playerId());
            if (player != null && !players.contains(player)) {
                players.add(player);
            }
        }
        return players;
    }

    private void teleportPlayer(ServerPlayer player, BlockPos pos) {
        player.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        player.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(player.level().dimension(), pos, player.getYRot(), player.getXRot()), true), false);

        player.setInvulnerable(true);
        invulnerabilityTicks.put(player.getUUID(), 500);

        LanguageService.Language lang = languageService.getLanguage(player);
        player.sendSystemMessage(Messages.get(lang, Messages.MessageKey.TELEPORTED_TO, formatLocation(pos)));
    }

    private boolean isSafeFloor(BlockState state) {
        return state.isSolidRender() && !UNSAFE_FLOOR_BLOCKS.contains(state.getBlock());
    }

    private boolean isPassable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.getCollisionShape(level, pos).isEmpty();
    }

    private BlockPos fallbackRandomLocation(ServerLevel level) {
        BlockPos spawn = level.getLevelData().getRespawnData().pos();
        int offset = 5000 + random.nextInt(5000);
        return spawn.offset(offset, 0, offset);
    }

    private int randomCoordinate() {
        int range = maxScatterCoord - minScatterCoord;
        if (range <= 0) range = 1;
        int base = minScatterCoord + random.nextInt(range + 1);
        return random.nextBoolean() ? base : -base;
    }

    private boolean isFarEnough(ServerLevel level, int x, int z, List<ScatterLocation> existingLocations) {
        long minDistanceSq = (long) MIN_PLAYER_DISTANCE * (long) MIN_PLAYER_DISTANCE;
        for (ScatterLocation existing : existingLocations) {
            if (existing.level() != level) {
                continue;
            }
            double dx = existing.pos().getX() - x;
            double dz = existing.pos().getZ() - z;
            if ((dx * dx) + (dz * dz) < minDistanceSq) {
                return false;
            }
        }
        return true;
    }

    private Map<LanguageService.Language, ServerBossEvent> createScatterBossBar(Collection<? extends ServerPlayer> viewers, int totalPlayers) {
        Map<LanguageService.Language, ServerBossEvent> bars = new HashMap<>();

        for (LanguageService.Language lang : LanguageService.Language.values()) {
            Component title = Messages.get(lang, Messages.MessageKey.SCATTER_BOSS_BAR, 0, totalPlayers)
                    .copy()
                    .withStyle(ChatFormatting.WHITE);
            ServerBossEvent bossBar = new ServerBossEvent(title, BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.PROGRESS);
            bossBar.setProgress(totalPlayers == 0 ? 1.0f : 0.0f);
            bossBar.setVisible(true);
            bars.put(lang, bossBar);
        }

        for (ServerPlayer viewer : viewers) {
            LanguageService.Language lang = languageService.getLanguage(viewer);
            ServerBossEvent bar = bars.get(lang);
            if (bar != null) {
                bar.addPlayer(viewer);
            }
        }
        return bars;
    }

    private void updateScatterBossBar(Map<LanguageService.Language, ServerBossEvent> bossBars, int ready, int total, String playerName, boolean searching) {
        if (bossBars == null || total <= 0) {
            return;
        }

        float progress = Math.min(1.0f, Math.max(0.0f, (float) ready / total));
        for (Map.Entry<LanguageService.Language, ServerBossEvent> entry : bossBars.entrySet()) {
            ServerBossEvent bossBar = entry.getValue();
            bossBar.setProgress(progress);
            bossBar.setName(Messages.get(entry.getKey(), Messages.MessageKey.SCATTER_BOSS_BAR, ready, total)
                    .copy()
                    .withStyle(ChatFormatting.WHITE));
        }
    }

    private void updateLoadingBossBar(int loadedChunks, int totalChunks) {
        if (currentBossBars.isEmpty() || totalChunks <= 0) {
            return;
        }
        float progress = Math.min(1.0f, Math.max(0.0f, (float) loadedChunks / totalChunks));
        for (Map.Entry<LanguageService.Language, ServerBossEvent> entry : currentBossBars.entrySet()) {
            ServerBossEvent bossBar = entry.getValue();
            bossBar.setName(Messages.get(entry.getKey(), Messages.MessageKey.LOADING_CHUNKS)
                    .copy()
                    .withStyle(ChatFormatting.WHITE));
            bossBar.setProgress(progress);
        }
    }

    private void hideScatterBossBar(Map<LanguageService.Language, ServerBossEvent> bossBars, boolean success, MinecraftServer server) {
        if (bossBars == null) {
            return;
        }

        if (success) {
            for (ServerBossEvent bossBar : bossBars.values()) {
                bossBar.removeAllPlayers();
                bossBar.setVisible(false);
            }
            return;
        }

        for (Map.Entry<LanguageService.Language, ServerBossEvent> entry : bossBars.entrySet()) {
            ServerBossEvent bossBar = entry.getValue();
            bossBar.setProgress(bossBar.getProgress());
            bossBar.setName(Messages.get(entry.getKey(), Messages.MessageKey.TELEPORTATION_STOPPED)
                    .copy()
                    .withStyle(ChatFormatting.WHITE));
        }

        if (server != null) {
            java.util.Timer timer = new java.util.Timer();
            timer.schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    server.execute(() -> {
                        for (ServerBossEvent bossBar : bossBars.values()) {
                            bossBar.removeAllPlayers();
                            bossBar.setVisible(false);
                        }
                    });
                }
            }, 2000L);
        }
    }

    public void clearBossBar() {
        for (ServerBossEvent bar : currentBossBars.values()) {
            bar.removeAllPlayers();
            bar.setVisible(false);
        }
        currentBossBars.clear();
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

    public void tickInvulnerability(MinecraftServer server) {
        if (invulnerabilityTicks.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Integer>> iterator = invulnerabilityTicks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            int ticksLeft = entry.getValue() - 1;
            if (ticksLeft <= 0) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    player.setInvulnerable(false);
                }
                iterator.remove();
                continue;
            }
            entry.setValue(ticksLeft);
        }
    }

    public void onPlayerLanguageChanged(ServerPlayer player, LanguageService.Language oldLang, LanguageService.Language newLang) {
        if (player == null || oldLang == null || newLang == null || oldLang == newLang || currentBossBars.isEmpty()) {
            return;
        }

        ServerBossEvent oldBar = currentBossBars.get(oldLang);
        ServerBossEvent newBar = currentBossBars.get(newLang);
        if (oldBar != null) {
            oldBar.removePlayer(player);
        }
        if (newBar != null) {
            newBar.addPlayer(player);
        }
    }

    private void trackOperation(CompletableFuture<?> future) {
        if (future != null && !future.isDone()) {
            activeOperations.add(future);
        }
    }

    private String formatLocation(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private record PlayerScatterTarget(UUID playerId, ServerLevel level, BlockPos location) {
    }

    private record ScatterLocation(ServerLevel level, BlockPos pos) {
    }

    private record ChunkKey(ServerLevel level, int x, int z) {
    }
}
