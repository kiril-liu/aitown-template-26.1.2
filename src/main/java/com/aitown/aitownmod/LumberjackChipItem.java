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

public class LumberjackChipItem extends Item {
    public LumberjackChipItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player playerIn, LivingEntity target, InteractionHand hand) {
        if (target instanceof Villager villager) {
            if (playerIn.level().isClientSide()) return InteractionResult.SUCCESS;

            if (villager.getPersistentData().contains("IsLumberjack")) {
                if (playerIn.isShiftKeyDown()) {
                    //villager.setCustomName(Component.literal("§a[伐木中] 智能伐木工"));
                    SmartVillagerData.setDisplayStatus(villager, "§a", "伐木中", "伐木工");
                    villager.getPersistentData().putBoolean("IsIdle", false); // 唤醒
                    villager.getPersistentData().putInt("HomeX", villager.getBlockX());
                    villager.getPersistentData().putInt("HomeY", villager.getBlockY());
                    villager.getPersistentData().putInt("HomeZ", villager.getBlockZ());
                    //playerIn.sendSystemMessage(Component.literal("§a[系统] 已重新设定大本营坐标，继续伐木！"));
                    playerIn.sendSystemMessage(Component.literal(
                            "§a[系统] " + SmartVillagerData.getCitizenName(villager)
                                    + " 已重新设定大本营坐标，继续伐木！"
                    ));
                    if (!playerIn.isCreative()) stack.shrink(1);
                    return InteractionResult.SUCCESS;
                }
            }

            // 【职业切换】如果是普通村民，或者从建筑工转行过来
            //villager.setCustomName(Component.literal("§a[伐木中] 智能伐木工"));
            //villager.setCustomNameVisible(true);
            SmartVillagerData.setDisplayStatus(villager, "§a", "伐木中", "伐木工");

            villager.getPersistentData().remove("IsBuilding"); // 洗掉建筑工记忆
            villager.getPersistentData().putBoolean("IsLumberjack", true);
            villager.getPersistentData().putBoolean("IsIdle", false);
            villager.getPersistentData().putInt("HomeX", villager.getBlockX());
            villager.getPersistentData().putInt("HomeY", villager.getBlockY());
            villager.getPersistentData().putInt("HomeZ", villager.getBlockZ());

            //playerIn.sendSystemMessage(Component.literal("§a成功注入/转职为伐木芯片！大本营已设定！"));
            playerIn.sendSystemMessage(Component.literal(
                    "§a成功注入/转职！" + SmartVillagerData.getCitizenName(villager)
                            + " 成为伐木工，大本营已设定！"
            ));
            if (!playerIn.isCreative()) stack.shrink(1);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }
}