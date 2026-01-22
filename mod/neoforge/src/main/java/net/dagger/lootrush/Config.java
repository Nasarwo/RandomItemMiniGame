package net.dagger.lootrush;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Arrays;
import java.util.List;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<List<? extends String>> BANNED_ITEMS = BUILDER
            .comment("List of items to ban from the random selection. Can use regex with 'REGEX:' prefix.")
            .defineList("bannedItems", Arrays.asList(
                    "minecraft:bedrock",
                    "minecraft:barrier",
                    "minecraft:structure_void",
                    "minecraft:command_block",
                    "minecraft:chain_command_block",
                    "minecraft:repeating_command_block",
                    "minecraft:jigsaw",
                    "minecraft:structure_block",
                    "minecraft:end_portal_frame",
                    "minecraft:end_portal",
                    "minecraft:nether_portal",
                    "minecraft:light",
                    "minecraft:debug_stick",
                    "minecraft:knowledge_book",
                    "minecraft:bundle"
            ), (java.util.function.Supplier<String>) null, obj -> obj instanceof String);

    public static final ModConfigSpec.IntValue SWAP_INTERVAL_SECONDS = BUILDER
            .comment("Interval in seconds between player swaps")
            .defineInRange("swapIntervalSeconds", 300, 10, 3600);

    public static final ModConfigSpec.IntValue SCATTER_MIN_COORD = BUILDER
            .comment("Minimum coordinate for scatter teleport")
            .defineInRange("scatterMinCoord", 10000, 0, 1000000);

    public static final ModConfigSpec.IntValue SCATTER_MAX_COORD = BUILDER
            .comment("Maximum coordinate for scatter teleport")
            .defineInRange("scatterMaxCoord", 100000, 1000, 1000000);

    static final ModConfigSpec SPEC = BUILDER.build();
}
