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
            if (playerIn.level().isClientSide()) return InteractionResult.SUCCESS;

            String[] blueprints = {
                    "minecraft:village/plains/houses/plains_small_house_1",
                    "minecraft:village/plains/houses/plains_small_house_2",
                    "minecraft:village/plains/houses/plains_small_house_3",
                    "minecraft:village/plains/houses/plains_small_house_4"
            };
            String chosenBlueprint = blueprints[villager.getRandom().nextInt(blueprints.length)];

            if (villager.getPersistentData().contains("IsBuilding")) {
                if (playerIn.isShiftKeyDown()) {
                    villager.setCustomName(Component.literal("§e[施工中] 智能建筑工"));
                    villager.getPersistentData().putBoolean("IsBuilding", true);
                    villager.getPersistentData().putString("BlueprintName", chosenBlueprint);
                    villager.getPersistentData().putInt("BuildCenterX", villager.getBlockX());
                    villager.getPersistentData().putInt("BuildCenterY", villager.getBlockY());
                    villager.getPersistentData().putInt("BuildCenterZ", villager.getBlockZ());
                    playerIn.sendSystemMessage(Component.literal("§a[系统] 强行注入新芯片！抽中图纸(" + chosenBlueprint + ")！马上开工！"));
                    if (!playerIn.isCreative()) stack.shrink(1);
                    return InteractionResult.SUCCESS;
                } else {
                    // 【情况 B】直接右键 = 打开可视化 GUI 背包！
//                        if (playerIn instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
//                            serverPlayer.openMenu(
//                                    new VillagerInventoryMenuProvider(villager),
//                                    buf -> buf.writeInt(villager.getId())
//                            );
//                        }
                        return InteractionResult.SUCCESS;
                }
            }

            // 【职业切换】如果是普通村民，或者从伐木工转行过来
            villager.setCustomName(Component.literal("§e[施工中] 智能建筑工"));
            villager.setCustomNameVisible(true);

            villager.getPersistentData().remove("IsLumberjack"); // 洗掉伐木工记忆
            villager.getPersistentData().putBoolean("IsBuilding", true);
            villager.getPersistentData().putString("BlueprintName", chosenBlueprint);
            villager.getPersistentData().putInt("BuildCenterX", villager.getBlockX());
            villager.getPersistentData().putInt("BuildCenterY", villager.getBlockY());
            villager.getPersistentData().putInt("BuildCenterZ", villager.getBlockZ());

            playerIn.sendSystemMessage(Component.literal("§a成功激活/转职！首张图纸(" + chosenBlueprint + ")，开始施工！"));
            if (!playerIn.isCreative()) stack.shrink(1);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }
}