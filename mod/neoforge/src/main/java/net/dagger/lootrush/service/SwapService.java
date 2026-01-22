package net.dagger.lootrush.service;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class SwapService {
    private final int swapIntervalTicks;
    private static final int SWAP_COUNTDOWN_SECONDS = 10;

    private final LanguageService languageService;
    private final Supplier<Boolean> canSwapSupplier;
    private final Supplier<List<ServerPlayer>> participantsSupplier;
    private final Consumer<Component> participantBroadcast;
    private final Random random = new Random();

    private boolean running = false;
    private long nextSwapTime = 0;
    private int countdown = -1;
    private long gameStartTime = 0;
    private final Map<UUID, Integer> invulnerabilityTicks = new HashMap<>();

    public SwapService(
            LanguageService languageService,
            Supplier<Boolean> canSwapSupplier,
            Supplier<List<ServerPlayer>> participantsSupplier,
            Consumer<Component> participantBroadcast,
            int swapIntervalSeconds) {
        this.languageService = languageService;
        this.canSwapSupplier = canSwapSupplier;
        this.participantsSupplier = participantsSupplier;
        this.participantBroadcast = participantBroadcast;

        this.swapIntervalTicks = swapIntervalSeconds * 20;
    }

    public void start(long gameStartTime) {
        this.running = true;
        this.gameStartTime = gameStartTime;
        this.nextSwapTime = gameStartTime + (swapIntervalTicks * 50L);
        this.countdown = -1;
    }

    public void stop() {
        this.running = false;
    }

    public void tick() {
        tickInvulnerability();
        if (!running || !canSwapSupplier.get()) {
            return;
        }

        List<ServerPlayer> participants = participantsSupplier.get();
        if (participants.size() < 2) {
            long currentTime = System.currentTimeMillis();
            long intervalMs = swapIntervalTicks * 50L;
            long elapsed = currentTime - gameStartTime;
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

        int displaySeconds = secondsUntilSwap + 1;

        if (displaySeconds == 60) {
            if (countdown != 60) {
                LanguageService.Language defaultLang = languageService.getDefaultLanguage();
                participantBroadcast.accept(Messages.get(defaultLang, Messages.MessageKey.SWAP_IN_MINUTE));
                countdown = 60;
            }
        } else if (displaySeconds == 30) {
            if (countdown != 30) {
                LanguageService.Language defaultLang = languageService.getDefaultLanguage();
                participantBroadcast.accept(Messages.get(defaultLang, Messages.MessageKey.SWAP_IN_30_SECONDS));
                countdown = 30;
            }
        }
    }

    private void performSwap(List<ServerPlayer> participants) {
        LanguageService.Language defaultLang = languageService.getDefaultLanguage();
        participantBroadcast.accept(Messages.get(defaultLang, Messages.MessageKey.PLAYERS_SWAPPING));

        Map<ServerPlayer, PlayerSwapState> playerStates = new HashMap<>();

        for (ServerPlayer player : participants) {
            ServerLevel level = (ServerLevel) player.level();
            ServerPlayer.RespawnConfig respawnConfig = player.getRespawnConfig();
            LevelData.RespawnData respawnData = respawnConfig != null
                    ? respawnConfig.respawnData()
                    : LevelData.RespawnData.of(player.level().dimension(), player.blockPosition(), player.getYRot(), player.getXRot());
            playerStates.put(player, new PlayerSwapState(player.position(), level, player.getYRot(), player.getXRot(), respawnData));
        }

        Collections.shuffle(participants, random);

        for (int i = 0; i < participants.size(); i++) {
            ServerPlayer currentPlayer = participants.get(i);
            ServerPlayer targetPlayer = participants.get((i + 1) % participants.size());

            PlayerSwapState targetState = playerStates.get(targetPlayer);
            if (targetState == null) {
                continue;
            }

            // Teleport
            currentPlayer.teleportTo(
                    targetState.level(),
                    targetState.position().x,
                    targetState.position().y,
                    targetState.position().z,
                    Set.of(),
                    targetState.yRot(),
                    targetState.xRot(),
                    false
            );

            currentPlayer.setRespawnPosition(new ServerPlayer.RespawnConfig(targetState.respawnData(), true), false);

            currentPlayer.setInvulnerable(true);
            invulnerabilityTicks.put(currentPlayer.getUUID(), 500);

            currentPlayer.connection.send(new ClientboundSoundPacket(
                    BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.ENDERMAN_TELEPORT), SoundSource.PLAYERS,
                    currentPlayer.getX(), currentPlayer.getY(), currentPlayer.getZ(),
                    1.0f, 1.0f, currentPlayer.getRandom().nextLong()));
        }
    }

    private void tickInvulnerability() {
        if (invulnerabilityTicks.isEmpty()) {
            return;
        }
        Map<UUID, ServerPlayer> playersById = new HashMap<>();
        for (ServerPlayer player : participantsSupplier.get()) {
            playersById.put(player.getUUID(), player);
        }
        Iterator<Map.Entry<UUID, Integer>> iterator = invulnerabilityTicks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            int ticksLeft = entry.getValue() - 1;
            if (ticksLeft <= 0) {
                ServerPlayer player = playersById.get(entry.getKey());
                if (player != null) {
                    player.setInvulnerable(false);
                }
                iterator.remove();
                continue;
            }
            entry.setValue(ticksLeft);
        }
    }

    private record PlayerSwapState(Vec3 position, ServerLevel level, float yRot, float xRot,
                                   LevelData.RespawnData respawnData) {
    }
}
