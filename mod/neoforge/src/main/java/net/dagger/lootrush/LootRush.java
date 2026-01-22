package net.dagger.lootrush;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

@Mod(LootRush.MODID)
public class LootRush {
    public static final String MODID = "lootrush";
    public static final Logger LOGGER = LogUtils.getLogger();

    private LootRushGameManager gameManager;
    private MinecraftServer server;

    public LootRush(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        NeoForge.EVENT_BUS.register(this);
    }

    private void initGameManager() {
        if (gameManager != null) {
            return;
        }
        this.gameManager = new LootRushGameManager(
                new java.util.ArrayList<>(Config.BANNED_ITEMS.get()),
                Config.SWAP_INTERVAL_SECONDS.get(),
                Config.SCATTER_MIN_COORD.get(),
                Config.SCATTER_MAX_COORD.get()
        );
        NeoForge.EVENT_BUS.register(gameManager);
        if (server != null) {
            gameManager.setServer(server);
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("LootRush server starting...");
        this.server = event.getServer();
        initGameManager();
        gameManager.setServer(server);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        initGameManager();
        gameManager.registerCommands(event.getDispatcher());
    }
}
