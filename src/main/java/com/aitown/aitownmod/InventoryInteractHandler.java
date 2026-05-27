package com.aitown.aitownmod;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = aitown.MODID)
public class InventoryInteractHandler {

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Villager villager)) {
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return;
        }

        // 只处理我们的智能村民，普通村民仍然保留原版交易逻辑。
        // 之前这里只判断 IsBuilding / IsLumberjack，导致矿工不会进入这个状态查看逻辑。
        // 现在统一用 SmartRole 判断，只要不是 none，就认为是智能村民。
        boolean isSmartWorker = !SmartVillagerData.ROLE_NONE.equals(SmartVillagerData.getRole(villager));

        if (!isSmartWorker) {
            return;
        }

        // Shift + 右键留给小镇系统芯片执行“设置职业 / 注册村民”。
        // 普通右键才显示状态并打开背包 UI。
        if (serverPlayer.isShiftKeyDown()) {
            return;
        }

        serverPlayer.sendSystemMessage(Component.literal("§6===== 智能村民状态 ====="));
        serverPlayer.sendSystemMessage(Component.literal(
                "§e姓名：§f" + SmartVillagerData.getCitizenName(villager)
                        + " §8(" + SmartVillagerData.getCitizenId(villager) + ")"
        ));
        serverPlayer.sendSystemMessage(Component.literal(
                "§e职业：§f" + SmartVillagerData.roleDisplayName(SmartVillagerData.getRole(villager))
        ));
        serverPlayer.sendSystemMessage(Component.literal(
                "§e状态：§f" + SmartVillagerData.getStatus(villager)
        ));
        serverPlayer.sendSystemMessage(Component.literal(
                "§e任务：§f" + SmartVillagerData.getTask(villager)
        ));
        serverPlayer.sendSystemMessage(Component.literal(
                "§e金币：§f" + SmartVillagerData.getCoins(villager)
                        + " §7｜累计收入：" + SmartVillagerData.getTotalEarned(villager)
                        + " ｜累计支出：" + SmartVillagerData.getTotalSpent(villager)
        ));
        serverPlayer.sendSystemMessage(Component.literal(
                "§e背包：§f" + SmartVillagerData.usedSlots(villager)
                        + "/" + villager.getInventory().getContainerSize()
                        + " 格，合计 " + SmartVillagerData.totalItems(villager) + " 个物品"
        ));
        serverPlayer.sendSystemMessage(Component.literal(
                "§e工作点/大本营：§f" + SmartVillagerData.getHomeText(villager)
        ));

        SimpleContainer villagerInv = villager.getInventory();

        // 原版箱子 UI 是 9 格一行，但村民背包是 8 格。
        // 所以这里做一个 9 格显示容器：前 8 格映射村民真实背包，第 9 格暂时作为空格。
        // 注意：初始化显示容器时不能触发同步，否则会把还没复制进去的空槽同步回村民背包，导致物品被清空。
        class VillagerDisplayContainer extends SimpleContainer {
            private boolean loading = true;

            public VillagerDisplayContainer() {
                super(9);
            }

            public void finishLoading() {
                loading = false;
            }

            private void syncToVillager() {
                if (loading) {
                    return;
                }

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
        }

        VillagerDisplayContainer displayContainer = new VillagerDisplayContainer();

        // 把村民真实背包复制到显示容器。
        for (int i = 0; i < 8; i++) {
            displayContainer.setItem(i, villagerInv.getItem(i).copy());
        }

        displayContainer.finishLoading();

        serverPlayer.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (containerId, playerInventory, player) ->
                        new ChestMenu(
                                MenuType.GENERIC_9x1,
                                containerId,
                                playerInventory,
                                displayContainer,
                                1
                        ),
                Component.literal(SmartVillagerData.getCitizenName(villager) + "的背包")
        ));

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}