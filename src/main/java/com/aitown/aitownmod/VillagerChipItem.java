package com.aitown.aitownmod;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class VillagerChipItem extends Item {

    public VillagerChipItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player playerIn, LivingEntity target, InteractionHand hand) {
        if (target instanceof Villager villager) {

            // 客户端直接放行，阻止原版交互
            if (playerIn.level().isClientSide()) {
                return InteractionResult.SUCCESS;
            }

            // 准备盲盒图纸库
            String[] blueprints = {
                    "minecraft:village/plains/houses/plains_small_house_1",
                    "minecraft:village/plains/houses/plains_small_house_2",
                    "minecraft:village/plains/houses/plains_small_house_3",
                    "minecraft:village/plains/houses/plains_small_house_4"
            };
            String chosenBlueprint = blueprints[villager.getRandom().nextInt(blueprints.length)];

            // 【核心逻辑】如果他已经是我们的建筑工了
            if (villager.getPersistentData().contains("IsBuilding")) {

                // ==========================================
                // 【情况 A】蹲下(Shift) + 右键 = 强制消耗芯片，派发新图纸！
                // ==========================================
                if (playerIn.isShiftKeyDown()) {
                    villager.setCustomName(Component.literal("§e[施工中] 智能建筑工"));

                    villager.getPersistentData().putBoolean("IsBuilding", true);
                    villager.getPersistentData().putString("BlueprintName", chosenBlueprint);
                    villager.getPersistentData().putInt("BuildCenterX", villager.getBlockX());
                    villager.getPersistentData().putInt("BuildCenterY", villager.getBlockY());
                    villager.getPersistentData().putInt("BuildCenterZ", villager.getBlockZ());

                    playerIn.sendSystemMessage(Component.literal("§a[系统] 强行注入新芯片！抽中图纸(" + chosenBlueprint + ")！马上开工！"));

                    if (!playerIn.isCreative()) {
                        stack.shrink(1); // 扣除芯片
                    }
                    return InteractionResult.SUCCESS;
                }
                // ==========================================
                // 【情况 B】直接右键 = 仅查看背包（安全操作，不消耗芯片）
                // ==========================================
                else {
                    SimpleContainer inventory = villager.getInventory();
                    playerIn.sendSystemMessage(Component.literal("§b=== 智能建筑工的背包 ==="));
                    boolean isEmpty = true;
                    for (int i = 0; i < inventory.getContainerSize(); i++) {
                        ItemStack itemInSlot = inventory.getItem(i);
                        if (!itemInSlot.isEmpty()) {
                            playerIn.sendSystemMessage(Component.literal(" - §f" + itemInSlot.getHoverName().getString() + " x" + itemInSlot.getCount()));
                            isEmpty = false;
                        }
                    }
                    if (isEmpty) playerIn.sendSystemMessage(Component.literal(" - (空空如也)"));
                    return InteractionResult.SUCCESS;
                }
            }

            // ==========================================
            // 【情况 C】第一次遇到普通村民，直接激活！
            // ==========================================
            villager.setCustomName(Component.literal("§e[施工中] 智能建筑工"));
            villager.setCustomNameVisible(true);

            villager.getPersistentData().putBoolean("IsBuilding", true);
            villager.getPersistentData().putString("BlueprintName", chosenBlueprint);
            villager.getPersistentData().putInt("BuildCenterX", villager.getBlockX());
            villager.getPersistentData().putInt("BuildCenterY", villager.getBlockY());
            villager.getPersistentData().putInt("BuildCenterZ", villager.getBlockZ());

            playerIn.sendSystemMessage(Component.literal("§a成功激活！首张图纸(" + chosenBlueprint + ")，开始施工！"));

            if (!playerIn.isCreative()) {
                stack.shrink(1);
            }
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }
}