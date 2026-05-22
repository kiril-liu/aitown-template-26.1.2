package com.aitown.aitownmod;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = aitown.MODID)
public class VillagerInventoryInteractHandler {

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Villager villager)) {
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return;
        }

        // 只处理我们的智能村民，普通村民仍然保留原版交易逻辑
        boolean isSmartWorker =
                villager.getPersistentData().contains("IsBuilding")
                        || villager.getPersistentData().contains("IsLumberjack");

        if (!isSmartWorker) {
            return;
        }

        // Shift + 右键留给芯片执行“重新激活 / 重新设定任务”
        // 普通右键才打开背包 UI
        if (serverPlayer.isShiftKeyDown()) {
            return;
        }

        SimpleContainer villagerInv = villager.getInventory();

        // 原版箱子 UI 是 9 格一行，但村民背包是 8 格。
        // 所以这里做一个 9 格显示容器：前 8 格映射村民真实背包，第 9 格暂时作为空格。
        SimpleContainer displayContainer = new SimpleContainer(9) {
            private void syncToVillager() {
                for (int i = 0; i < 8; i++) {
                    villagerInv.setItem(i, this.getItem(i));
                }
                villagerInv.setChanged();
            }

            @Override
            public void setChanged() {
                super.setChanged();
                syncToVillager();
            }

            public void stopOpen(net.minecraft.world.entity.player.Player player) {
                syncToVillager();

                ItemStack extra = this.getItem(8);
                if (!extra.isEmpty()) {
                    player.getInventory().placeItemBackInInventory(extra.copy());
                    this.setItem(8, ItemStack.EMPTY);
                }
            }
        };

        // 把村民真实背包复制到显示容器
        for (int i = 0; i < 8; i++) {
            displayContainer.setItem(i, villagerInv.getItem(i));
        }

        serverPlayer.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (containerId, playerInventory, player) ->
                        new ChestMenu(
                                MenuType.GENERIC_9x1,
                                containerId,
                                playerInventory,
                                displayContainer,
                                1
                        ),
                Component.literal("智能村民背包")
        ));

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}