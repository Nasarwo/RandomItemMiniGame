package net.dagger.lootrush.service;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class ItemService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final List<Item> itemPool;
    private final Random random = new Random();

    public ItemService(List<String> bannedItems) {
        Set<Item> bannedMaterials = new HashSet<>();
        List<Pattern> bannedPatterns = new ArrayList<>();

        for (String item : bannedItems) {
            if (item.startsWith("REGEX:")) {
                try {
                    bannedPatterns.add(Pattern.compile(item.substring(6)));
                } catch (Exception e) {
                    LOGGER.warn("Invalid regex pattern in banned-items: " + item);
                }
            } else {
                try {
                    // Fallback to string parsing if ResourceLocation is problematic
                    // But BuiltInRegistries.ITEM.get() needs ResourceLocation.
                    // Let's try net.minecraft.resources.ResourceLocation again but maybe I can't import it?
                    // I will try to use var and fully qualified name.
                    // If this fails, I'll try to use a different registry method if available.
                    // BuiltInRegistries.ITEM.get(ResourceLocation) is the standard way.

                    Identifier id = Identifier.parse(item.toLowerCase(Locale.ROOT));
                    Item material = BuiltInRegistries.ITEM.get(id)
                            .map(Holder.Reference::value)
                            .orElse(Items.AIR);
                    if (material != Items.AIR) {
                        bannedMaterials.add(material);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Invalid item name in banned-items: " + item);
                }
            }
        }

        this.itemPool = StreamSupport.stream(BuiltInRegistries.ITEM.spliterator(), false)
                .filter(item -> isSurvivalObtainable(item, bannedMaterials, bannedPatterns))
                .collect(Collectors.toList());

        LOGGER.info("Item pool size: " + itemPool.size());
    }

    public Item pickRandomItem() {
        if (itemPool.isEmpty()) {
            return Items.DIRT;
        }
        return itemPool.get(random.nextInt(itemPool.size()));
    }

    public boolean isItemInPool(Item item) {
        return itemPool.contains(item);
    }

    public int getPoolSize() {
        return itemPool.size();
    }

    private boolean isSurvivalObtainable(Item item, Set<Item> bannedMaterials, List<Pattern> bannedPatterns) {
        if (item == Items.AIR) {
            return false;
        }

        if (bannedMaterials.contains(item)) {
            return false;
        }

        String name = BuiltInRegistries.ITEM.getKey(item).toString();
        for (Pattern pattern : bannedPatterns) {
            if (pattern.matcher(name).matches()) {
                return false;
            }
        }

        return true;
    }
}
