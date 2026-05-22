package com.aitown.aitownmod;

import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

public class VillagerInventoryMenuProvider implements MenuProvider {
    private final Villager villager;

    public VillagerInventoryMenuProvider(Villager villager) {
        this.villager = villager;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("智能工人的背包");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new VillagerInventoryMenu(containerId, playerInventory, villager.getInventory(), villager);
    }
}