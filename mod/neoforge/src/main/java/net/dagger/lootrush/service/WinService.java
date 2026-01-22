package net.dagger.lootrush.service;

import net.dagger.lootrush.game.Role;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class
WinService {
    private final RoleService roleService;

    public WinService(RoleService roleService) {
        this.roleService = roleService;
    }

    public boolean hasTargetItem(ServerPlayer player, Item targetItem) {
        if (targetItem == null) {
            return false;
        }
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && stack.is(targetItem)) {
                return true;
            }
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND
        }) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty() && stack.is(targetItem)) {
                return true;
            }
        }
        return false;
    }

    public void removeTargetItemFromPlayers(Collection<? extends ServerPlayer> players, Item targetItem) {
        for (ServerPlayer player : players) {
            player.getInventory().clearOrCountMatchingItems(stack -> stack.is(targetItem), -1, player.inventoryMenu.getCraftSlots());
            player.containerMenu.broadcastChanges();
        }
    }

    public List<ServerPlayer> getAlivePlayers(Collection<ServerPlayer> allPlayers) {
        return allPlayers.stream()
                .filter(p -> roleService.getRole(p) == Role.PLAYER && p.isAlive())
                .collect(Collectors.toList());
    }
}
