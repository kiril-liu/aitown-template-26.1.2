package com.aitown.aitownmod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class VillagerInventoryMenu extends AbstractContainerMenu {
    private final Container villagerInventory;
    private final Villager villager;

    // 服务端构造器
    public VillagerInventoryMenu(int containerId, Inventory playerInventory, Container villagerInventory, Villager villager) {
        super(ModMenus.VILLAGER_INVENTORY_MENU.get(), containerId);
        this.villagerInventory = villagerInventory;
        this.villager = villager;
        villagerInventory.startOpen(playerInventory.player);

        // 村民背包 8 格 (支持双向存取！)
        for (int i = 0; i < villagerInventory.getContainerSize(); i++) {
            int x = 62 + (i % 3) * 18; // 排成 3 列
            int y = 17 + (i / 3) * 18;
            this.addSlot(new Slot(villagerInventory, i, x, y));
        }

        // 玩家背包
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }
    }

    // 客户端数据包反序列化
    public VillagerInventoryMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        this(containerId, playerInventory, getVillager(playerInventory, buf.readInt()));
    }

    private VillagerInventoryMenu(int containerId, Inventory playerInventory, Villager villager) {
        this(containerId, playerInventory, villager.getInventory(), villager);
    }

    private static Villager getVillager(Inventory playerInventory, int entityId) {
        Entity entity = playerInventory.player.level().getEntity(entityId);
        if (entity instanceof Villager v) return v;
        throw new IllegalStateException("Entity is not a villager");
    }

    @Override
    public boolean stillValid(Player player) {
        return villager.isAlive() && player.distanceTo(villager) < 8.0F;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY; // 为求稳定，暂不实现 Shift 快捷移动
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        villagerInventory.stopOpen(player);
    }
}