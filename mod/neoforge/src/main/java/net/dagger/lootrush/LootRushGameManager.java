package net.dagger.lootrush;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.dagger.lootrush.game.GameState;
import net.dagger.lootrush.game.Role;
import net.dagger.lootrush.service.*;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

public class LootRushGameManager {
    private static final int COUNTDOWN_SECONDS = 10;

    private final LanguageService languageService;
    private final RoleService roleService;
    private final TeleportService teleportService;
    private final SwapService swapService;
    private final ItemService itemService;
    private final LivesService livesService;
    private final ScoreboardService scoreboardService;
    private final TimerService timerService;
    private final WinService winService;
    private final GameInfoService gameInfoService;

    private MinecraftServer server;
    private GameState state = GameState.IDLE;
    private Item targetItem;

    private boolean isCountingDown = false;
    private int countdownSecondsLeft = 0;
    private long lastCountdownTick = 0;
    private final Map<UUID, BlockPos> countdownPositions = new HashMap<>();

    public LootRushGameManager(List<String> bannedItems, int swapIntervalSeconds, int scatterMinCoord, int scatterMaxCoord) {
        this.languageService = new LanguageService();
        this.roleService = new RoleService(languageService);
        this.itemService = new ItemService(bannedItems);
        this.livesService = new LivesService();
        this.scoreboardService = new ScoreboardService(languageService);
        this.timerService = new TimerService(scoreboardService);
        this.winService = new WinService(roleService);
        this.gameInfoService = new GameInfoService(languageService);

        this.teleportService = new TeleportService(languageService, scatterMinCoord, scatterMaxCoord);

        this.swapService = new SwapService(
                languageService,
                () -> state == GameState.ACTIVE && targetItem != null,
                this::getActiveParticipants,
                this::broadcastToParticipants,
                swapIntervalSeconds);
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    private WorldService getWorldService() {
        if (this.server == null) return null;
        return new WorldService(server);
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerLootRushCommand(dispatcher, "lootrush");
        registerLootRushCommand(dispatcher, "lr");
    }

    private void registerLootRushCommand(CommandDispatcher<CommandSourceStack> dispatcher, String root) {
        dispatcher.register(Commands.literal(root)
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
                .executes(this::handleUsage)
                .then(Commands.literal("start").executes(this::handleStart))
                .then(Commands.literal("stop").executes(this::handleStop))
                .then(Commands.literal("status").executes(this::handleStatus))
                .then(Commands.literal("cancel").executes(this::handleCancel))
                .then(Commands.literal("skip").executes(this::handleSkip))
                .then(Commands.literal("lang")
                        .executes(this::handleLangUsage)
                        .then(Commands.argument("language", StringArgumentType.word())
                                .executes(this::handleLang)))
                .then(Commands.literal("role")
                        .then(Commands.argument("role", StringArgumentType.word())
                                .executes(this::handleRoleSelf)
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(this::handleRole))))
        );
    }

    private int handleUsage(CommandContext<CommandSourceStack> context) {
        LanguageService.Language lang = getLanguage(context.getSource());
        context.getSource().sendFailure(Messages.get(lang, Messages.MessageKey.USAGE));
        return 0;
    }

    private int handleStart(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        LanguageService.Language lang = getLanguage(source);

        if (state != GameState.IDLE) {
            source.sendFailure(Messages.get(lang, Messages.MessageKey.GAME_ALREADY_RUNNING));
            return 0;
        }

        List<ServerPlayer> online = source.getServer().getPlayerList().getPlayers();
        List<ServerPlayer> participants = online.stream()
                .filter(player -> roleService.getRole(player) == Role.PLAYER)
                .collect(Collectors.toList());
        List<ServerPlayer> spectators = online.stream()
                .filter(player -> roleService.getRole(player) == Role.SPECTATOR)
                .collect(Collectors.toList());

        if (participants.isEmpty()) {
            source.sendFailure(Messages.get(lang, Messages.MessageKey.NO_PLAYERS));
            return 0;
        }

        targetItem = itemService.pickRandomItem();
        state = GameState.COUNTDOWN;
        roleService.prepareSpectators(spectators);
        getWorldService().setWorldStateForLoading();
        getWorldService().setSafetyBorder();

        for (ServerPlayer player : online) {
            BlockPos spawnPos = player.level().getLevelData().getRespawnData().pos();
            player.teleportTo(spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());
            clearInventory(player);
        }

        broadcast(Messages.MessageKey.RANDOM_ITEM_HEADER);
        for (ServerPlayer player : online) {
            LanguageService.Language playerLang = languageService.getLanguage(player);
            MutableComponent msg = Component.empty()
                    .append(Messages.get(playerLang, Messages.MessageKey.NEED_TO_OBTAIN))
                    .append(formatItem(targetItem).withStyle(ChatFormatting.AQUA));
            player.sendSystemMessage(msg);
        }
        if (server != null) {
            LanguageService.Language defaultLang = languageService.getDefaultLanguage();
            server.sendSystemMessage(Component.empty()
                    .append(Messages.get(defaultLang, Messages.MessageKey.NEED_TO_OBTAIN))
                    .append(formatItem(targetItem).withStyle(ChatFormatting.AQUA)));
        }

        playSoundForAll(SoundEvents.ENDER_DRAGON_GROWL, 1.0f, 1.0f);

        List<ServerPlayer> participantsSnapshot = new ArrayList<>(participants);
        livesService.initializeLives(participantsSnapshot);
        scoreboardService.createScoreboard(source.getServer().getScoreboard(), livesService.getAllLives(), online);

        teleportService.scatterPlayers(participantsSnapshot).whenComplete((ignored, throwable) -> {
             source.getServer().execute(() -> {
                 if (throwable != null) {
                     LootRush.LOGGER.error("Не удалось телепортировать игроков", throwable);
                     stopGame(false);
                     source.sendFailure(Component.literal("Не удалось телепортировать игроков: " + throwable.getMessage()));
                     return;
                 }
                 if (state != GameState.COUNTDOWN) return;

                 broadcast(Messages.MessageKey.PLAYERS_TELEPORTED, COUNTDOWN_SECONDS);
                 winService.removeTargetItemFromPlayers(participantsSnapshot, targetItem);
                 getWorldService().setWorldStateActive();
                 getWorldService().resetBorder();
                 gameInfoService.showTargetItem(targetItem, online);
                 startCountdown();
             });
        });

        return 1;
    }

    private int handleStop(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        LanguageService.Language lang = getLanguage(source);

        if (state == GameState.IDLE) {
            source.sendFailure(Messages.get(lang, Messages.MessageKey.GAME_ALREADY_STOPPED));
            return 0;
        }

        stopGame(true);
        broadcast(Messages.MessageKey.GAME_STOPPED);
        return 1;
    }

    private int handleCancel(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        LanguageService.Language lang = getLanguage(source);

        if (state != GameState.COUNTDOWN) {
            source.sendFailure(Messages.get(lang, Messages.MessageKey.NO_COUNTDOWN));
            return 0;
        }

        stopGame(false);
        broadcast(Messages.MessageKey.GAME_CANCELLED);
        return 1;
    }

    private int handleSkip(CommandContext<CommandSourceStack> context) {
        LanguageService.Language lang = getLanguage(context.getSource());
        if (state != GameState.ACTIVE && state != GameState.COUNTDOWN) {
            context.getSource().sendFailure(Messages.get(lang, Messages.MessageKey.GAME_NOT_ACTIVE));
            return 0;
        }
        if (targetItem == null) {
            context.getSource().sendFailure(Messages.get(lang, Messages.MessageKey.NO_CURRENT_ITEM));
            return 0;
        }

        targetItem = itemService.pickRandomItem();
        gameInfoService.updateTargetItem(targetItem);
        if (server != null) {
            winService.removeTargetItemFromPlayers(server.getPlayerList().getPlayers(), targetItem);
        }

        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                LanguageService.Language playerLang = languageService.getLanguage(player);
                player.sendSystemMessage(Component.empty()
                        .append(Messages.get(playerLang, Messages.MessageKey.ITEM_SKIPPED))
                        .append(Messages.get(playerLang, Messages.MessageKey.NEW_ITEM))
                        .append(formatItem(targetItem).withStyle(ChatFormatting.AQUA)));
            }
            LanguageService.Language defaultLang = languageService.getDefaultLanguage();
            server.sendSystemMessage(Component.empty()
                    .append(Messages.get(defaultLang, Messages.MessageKey.ITEM_SKIPPED))
                    .append(Messages.get(defaultLang, Messages.MessageKey.NEW_ITEM))
                    .append(formatItem(targetItem).withStyle(ChatFormatting.AQUA)));
        }
        return 1;
    }

    private int handleStatus(CommandContext<CommandSourceStack> context) {
        LanguageService.Language lang = getLanguage(context.getSource());
        if (state == GameState.IDLE) {
            context.getSource().sendSuccess(() -> Messages.get(lang, Messages.MessageKey.GAME_NOT_STARTED), false);
        } else if (state == GameState.COUNTDOWN) {
            context.getSource().sendSuccess(() -> Component.empty()
                    .append(Messages.get(lang, Messages.MessageKey.COUNTDOWN_IN_PROGRESS))
                    .append(formatItem(targetItem).withStyle(ChatFormatting.AQUA)), false);
        } else {
            context.getSource().sendSuccess(() -> Component.empty()
                    .append(Messages.get(lang, Messages.MessageKey.GAME_ACTIVE))
                    .append(formatItem(targetItem).withStyle(ChatFormatting.AQUA)), false);
        }
        return 1;
    }

    private int handleLang(CommandContext<CommandSourceStack> context) {
        String langCode = StringArgumentType.getString(context, "language");
        LanguageService.Language newLang = LanguageService.Language.fromCode(langCode);

        if (!Objects.equals(langCode, "ru") && !Objects.equals(langCode, "en") && !Objects.equals(langCode, "uk") && !Objects.equals(langCode, "ua")) {
            LanguageService.Language lang = getLanguage(context.getSource());
            context.getSource().sendFailure(Messages.get(lang, Messages.MessageKey.UNKNOWN_LANGUAGE));
            return 0;
        }

        if (context.getSource().getEntity() instanceof ServerPlayer player) {
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
            context.getSource().sendSuccess(() -> Messages.get(newLang, Messages.MessageKey.LANGUAGE_SET, langName), false);
        } else {
            LanguageService.Language lang = getLanguage(context.getSource());
            context.getSource().sendSuccess(() -> Messages.get(lang, Messages.MessageKey.CURRENT_LANGUAGE,
                    languageService.getDefaultLanguage().getCode()), false);
        }
        return 1;
    }

    private int handleLangUsage(CommandContext<CommandSourceStack> context) {
        LanguageService.Language lang = getLanguage(context.getSource());
        context.getSource().sendFailure(Messages.get(lang, Messages.MessageKey.LANG_USAGE));
        return 0;
    }

    private int handleRoleSelf(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
            LanguageService.Language lang = getLanguage(context.getSource());
            context.getSource().sendFailure(Messages.get(lang, Messages.MessageKey.NEED_PLAYER_OR_SELECTOR));
            return 0;
        }
        String roleName = StringArgumentType.getString(context, "role");
        return handleRoleInternal(context, roleName, List.of(player));
    }

    private int handleRole(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String roleName = StringArgumentType.getString(context, "role");
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
        return handleRoleInternal(context, roleName, new ArrayList<>(targets));
    }

    private int handleRoleInternal(CommandContext<CommandSourceStack> context, String roleName, List<ServerPlayer> targets) {
        LanguageService.Language lang = getLanguage(context.getSource());
        if (!context.getSource().permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))) {
            context.getSource().sendFailure(Messages.get(lang, Messages.MessageKey.NO_PERMISSION_ROLE));
            return 0;
        }
        if (targets.isEmpty()) {
            context.getSource().sendFailure(Messages.get(lang, Messages.MessageKey.NEED_PLAYER_OR_SELECTOR));
            return 0;
        }

        Role newRole;
        if ("player".equalsIgnoreCase(roleName)) {
            newRole = Role.PLAYER;
        } else if ("spectator".equalsIgnoreCase(roleName)) {
            newRole = Role.SPECTATOR;
        } else {
            context.getSource().sendFailure(Messages.get(lang, Messages.MessageKey.UNKNOWN_ROLE));
            return 0;
        }

        for (ServerPlayer target : targets) {
            roleService.setRole(target, newRole);
            if (context.getSource().getEntity() == null || context.getSource().getEntity() != target) {
                LanguageService.Language targetLang = languageService.getLanguage(target);
                target.sendSystemMessage(Messages.get(targetLang, Messages.MessageKey.ROLE_SET_BY_ADMIN, roleName));
            }
        }

        if (targets.size() == 1) {
            context.getSource().sendSuccess(() -> Messages.get(lang, Messages.MessageKey.ROLE_SET_SINGLE, roleName, targets.get(0).getName()), false);
        } else {
            context.getSource().sendSuccess(() -> Messages.get(lang, Messages.MessageKey.ROLE_SET_MULTIPLE, roleName, targets.size()), false);
        }
        return targets.size();
    }

    private void stopGame(boolean clearInventories) {
        isCountingDown = false;
        countdownPositions.clear();
        timerService.updateState(GameState.IDLE);
        timerService.cancel();
        swapService.stop();
        teleportService.cancel();
        livesService.clear();
        if (server != null) {
            scoreboardService.clear(server.getScoreboard(), server.getPlayerList().getPlayers());
            getWorldService().setWorldStateAfterGame();
            getWorldService().resetBorder();

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (clearInventories) {
                    clearInventory(player);
                }
                player.setRespawnPosition(null, false);
            }
        }
        state = GameState.IDLE;
        targetItem = null;
        gameInfoService.hide();
    }

    private void startCountdown() {
        isCountingDown = true;
        countdownSecondsLeft = COUNTDOWN_SECONDS;
        lastCountdownTick = System.currentTimeMillis();
        countdownPositions.clear();
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (roleService.getRole(player) == Role.PLAYER) {
                    countdownPositions.put(player.getUUID(), player.blockPosition());
                }
            }
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (server == null) return;

        long now = System.currentTimeMillis();
        teleportService.tickInvulnerability(server);

        if (isCountingDown) {
            if (now - lastCountdownTick >= 1000) {
                lastCountdownTick = now;
                countdownSecondsLeft--;
                if (countdownSecondsLeft <= 0) {
                    isCountingDown = false;
                    startGame();
                } else {
                    broadcast(Messages.MessageKey.START_IN_SECONDS, countdownSecondsLeft);
                }
            }
        }

        if (state == GameState.ACTIVE) {
            timerService.tick(server.getScoreboard(), server.getPlayerList().getPlayers());
            swapService.tick();

            if (server.getTickCount() % 20 == 0) {
                checkWinConditions();
            }
        }
    }

    private void startGame() {
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                LanguageService.Language playerLang = languageService.getLanguage(player);
                player.sendSystemMessage(Component.empty()
                        .append(Messages.get(playerLang, Messages.MessageKey.START_GOOD_LUCK))
                        .append(formatItem(targetItem).withStyle(ChatFormatting.AQUA)));
            }
            LanguageService.Language defaultLang = languageService.getDefaultLanguage();
            server.sendSystemMessage(Component.empty()
                    .append(Messages.get(defaultLang, Messages.MessageKey.START_GOOD_LUCK))
                    .append(formatItem(targetItem).withStyle(ChatFormatting.AQUA)));
        }
        state = GameState.ACTIVE;
        long gameStartTime = System.currentTimeMillis();
        timerService.start(gameStartTime, state);
        swapService.start(gameStartTime);
    }

    private void checkWinConditions() {
        if (targetItem == null) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (winService.hasTargetItem(player, targetItem)) {
                endGameWithWinner(player);
                return;
            }
        }

        List<ServerPlayer> alive = winService.getAlivePlayers(server.getPlayerList().getPlayers());
        if (alive.size() == 1) {
            ServerPlayer winner = alive.get(0);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                LanguageService.Language playerLang = languageService.getLanguage(player);
                player.sendSystemMessage(Component.empty()
                        .append(Messages.get(playerLang, Messages.MessageKey.LAST_PLAYER_STANDING))
                        .append(Component.literal(winner.getName().getString()).withStyle(ChatFormatting.GREEN))
                        .append(Messages.get(playerLang, Messages.MessageKey.WINS_ROUND)));
            }
            LanguageService.Language defaultLang = languageService.getDefaultLanguage();
            server.sendSystemMessage(Component.empty()
                    .append(Messages.get(defaultLang, Messages.MessageKey.LAST_PLAYER_STANDING))
                    .append(Component.literal(winner.getName().getString()).withStyle(ChatFormatting.GREEN))
                    .append(Messages.get(defaultLang, Messages.MessageKey.WINS_ROUND)));
            endGameWithWinner(winner);
        }
    }

    private void endGameWithWinner(ServerPlayer winner) {
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                LanguageService.Language playerLang = languageService.getLanguage(player);
                player.sendSystemMessage(Component.empty()
                        .append(Messages.get(playerLang, Messages.MessageKey.PLAYER_WON))
                        .append(Component.literal(winner.getName().getString()).withStyle(ChatFormatting.GREEN))
                        .append(Messages.get(playerLang, Messages.MessageKey.OBTAINED_FIRST))
                        .append(formatItem(targetItem).withStyle(ChatFormatting.AQUA))
                        .append(Messages.get(playerLang, Messages.MessageKey.AND_WON)));
            }
            LanguageService.Language defaultLang = languageService.getDefaultLanguage();
            server.sendSystemMessage(Component.empty()
                    .append(Messages.get(defaultLang, Messages.MessageKey.PLAYER_WON))
                    .append(Component.literal(winner.getName().getString()).withStyle(ChatFormatting.GREEN))
                    .append(Messages.get(defaultLang, Messages.MessageKey.OBTAINED_FIRST))
                    .append(formatItem(targetItem).withStyle(ChatFormatting.AQUA))
                    .append(Messages.get(defaultLang, Messages.MessageKey.AND_WON)));
        }
        resetPlayersAfterGame();
        stopGame(false);
        playSoundForAll(SoundEvents.WITHER_DEATH, 1.0f, 1.0f);
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            gameInfoService.addPlayer(player);
            if (state == GameState.ACTIVE || state == GameState.COUNTDOWN) {
                scoreboardService.addViewer(server.getScoreboard(), player, livesService.getAllLives(), server.getPlayerList().getPlayers());
            }
        }
    }

    @SubscribeEvent
    public void onPlayerQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            gameInfoService.removePlayer(player);
            if (server != null) {
                scoreboardService.removePlayer(server.getScoreboard(), player);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        if (state != GameState.ACTIVE) return;
        if (event.getEntity() instanceof ServerPlayer player) {
            if (roleService.getRole(player) != Role.PLAYER) return;

            int lives = livesService.decreaseLives(player);
            scoreboardService.updateAllPlayersLives(server.getScoreboard(), livesService.getAllLives(), server.getPlayerList().getPlayers());

            if (!livesService.hasLives(player)) {
                roleService.setRole(player, Role.SPECTATOR);
                LanguageService.Language lang = languageService.getLanguage(player);
                player.sendSystemMessage(Messages.get(lang, Messages.MessageKey.NO_LIVES_LEFT));
                broadcastToParticipants(Messages.get(lang, Messages.MessageKey.PLAYER_OUT, player.getName()));
            } else {
                LanguageService.Language lang = languageService.getLanguage(player);
                player.sendSystemMessage(Messages.get(lang, Messages.MessageKey.LIVES_REMAINING, lives, LivesService.getMaxLives()));
            }
        }
    }

    @SubscribeEvent
    public void onItemPickup(ItemEntityPickupEvent.Post event) {
        if (state != GameState.ACTIVE) return;
        if (event.getPlayer() instanceof ServerPlayer player) {
            ItemStack stack = event.getItemEntity().getItem();
            if (targetItem != null && stack.is(targetItem)) {
                server.execute(() -> {
                     if (winService.hasTargetItem(player, targetItem)) {
                         endGameWithWinner(player);
                     }
                });
            }
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (state == GameState.COUNTDOWN && event.getPlayer() instanceof ServerPlayer player && roleService.getRole(player) == Role.PLAYER) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onDamage(LivingDamageEvent.Pre event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (player.isInvulnerable() || (state == GameState.COUNTDOWN && roleService.getRole(player) == Role.PLAYER)) {
                event.setNewDamage(0.0f);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (state != GameState.COUNTDOWN || roleService.getRole(player) != Role.PLAYER || !isCountingDown) {
            return;
        }
        BlockPos stored = countdownPositions.get(player.getUUID());
        if (stored == null) {
            countdownPositions.put(player.getUUID(), player.blockPosition());
            return;
        }
        BlockPos current = player.blockPosition();
        if (!current.equals(stored)) {
            player.teleportTo((ServerLevel) player.level(), stored.getX() + 0.5, stored.getY() + 0.0, stored.getZ() + 0.5, java.util.Set.of(), player.getYRot(), player.getXRot(), false);
        }
    }

    private LanguageService.Language getLanguage(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return languageService.getLanguage(player);
        }
        return languageService.getDefaultLanguage();
    }

    private void broadcast(Messages.MessageKey key, Object... args) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            LanguageService.Language lang = languageService.getLanguage(player);
            player.sendSystemMessage(Messages.get(lang, key, args));
        }
        LanguageService.Language defaultLang = languageService.getDefaultLanguage();
        server.sendSystemMessage(Messages.get(defaultLang, key, args));
    }

    private void broadcastToParticipants(Component component) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (roleService.getRole(player) == Role.PLAYER) {
                player.sendSystemMessage(component);
            }
        }
    }

    private List<ServerPlayer> getActiveParticipants() {
        if (server == null) return new ArrayList<>();
        return winService.getAlivePlayers(server.getPlayerList().getPlayers());
    }

    private MutableComponent formatItem(Item item) {
        if (item == null) return Component.literal("?");
        return Component.translatable(item.getDescriptionId());
    }

    private void playSoundForAll(net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(new ClientboundSoundPacket(
                    BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), SoundSource.MASTER,
                    player.getX(), player.getY(), player.getZ(),
                    volume, pitch, player.getRandom().nextLong()));
        }
    }

    private void clearInventory(ServerPlayer player) {
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
    }

    private void resetPlayersAfterGame() {
        if (server == null) return;
        BlockPos defaultSpawn = server.overworld().getLevelData().getRespawnData().pos();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (roleService.getRole(player) != Role.PLAYER) {
                roleService.setRole(player, Role.PLAYER);
            }
            clearInventory(player);
            player.setRespawnPosition(null, false);
            player.teleportTo(defaultSpawn.getX(), defaultSpawn.getY(), defaultSpawn.getZ());
            LanguageService.Language lang = languageService.getLanguage(player);
            player.sendSystemMessage(Messages.get(lang, Messages.MessageKey.RETURNING_TO_SPAWN));
        }
    }
}
