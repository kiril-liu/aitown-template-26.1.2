package com.aitown.aitownmod;


import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

@EventBusSubscriber(modid = aitown.MODID)
public class LumberjackHandler {

    @SubscribeEvent
    public static void onVillagerTick(EntityTickEvent.Pre event) {
        if (!event.getEntity().level().isClientSide() && event.getEntity() instanceof Villager villager) {

            if (villager.getPersistentData().getBoolean("IsLumberjack").orElse(false)) {
                if (villager.getPersistentData().getBoolean("IsIdle").orElse(false)) return;

                // 1. 【吸尘器】自动捡起原木、树苗、苹果、木棍
                AABB searchBox = villager.getBoundingBox().inflate(2.0D, 1.0D, 2.0D);
                for (ItemEntity itemEntity : villager.level().getEntitiesOfClass(ItemEntity.class, searchBox)) {
                    ItemStack stack = itemEntity.getItem();
                    String itemName = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
                    if (itemName.contains("_log") || itemName.contains("sapling") || itemName.equals("apple") || itemName.equals("stick")) {
                        int beforeCount = stack.getCount();
                        ItemStack remaining = villager.getInventory().addItem(stack);
                        if (remaining.getCount() < beforeCount) {
                            itemEntity.setItem(remaining);
                            villager.level().playSound(null, villager.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.2F, 1.5F);
                        }
                    }
                }

                if (villager.tickCount % 10 == 0) {
                    net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) villager.level();
                    SimpleContainer inventory = villager.getInventory();

                    // 算算包里有多少原木，以及还有没有空位
                    int logCount = 0;
                    int emptySlots = 0;
                    for (int i = 0; i < inventory.getContainerSize(); i++) {
                        ItemStack slotItem = inventory.getItem(i);
                        if (slotItem.isEmpty()) {
                            emptySlots++;
                        } else {
                            String name = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slotItem.getItem()).getPath();
                            if (name.contains("_log")) logCount += slotItem.getCount();
                        }
                    }

                    int hx = villager.getPersistentData().getInt("HomeX").orElse(villager.getBlockX());
                    int hy = villager.getPersistentData().getInt("HomeY").orElse(villager.getBlockY());
                    int hz = villager.getPersistentData().getInt("HomeZ").orElse(villager.getBlockZ());
                    BlockPos homePos = new BlockPos(hx, hy, hz);

                    // ==========================================
                    // 状态 A：原木够了，或者背包完全没空位了，强制回家存箱子！
                    // ==========================================
                    if (logCount >= 16 || emptySlots == 0) {
                        if (villager.distanceToSqr(homePos.getX(), homePos.getY(), homePos.getZ()) > 9.0D) {
                            villager.getNavigation().moveTo(homePos.getX(), homePos.getY(), homePos.getZ(), 0.6D);
                        } else {
                            boolean deposited = false;
                            boolean foundChest = false; // 标记是否找到了箱子
                            BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

                            searchChest:
                            for (int dx = -5; dx <= 5; dx++) {
                                for (int dy = -2; dy <= 2; dy++) {
                                    for (int dz = -5; dz <= 5; dz++) {
                                        mPos.set(homePos.getX() + dx, homePos.getY() + dy, homePos.getZ() + dz);
                                        if (level.getBlockEntity(mPos) instanceof net.minecraft.world.Container chest) {
                                            foundChest = true; // 只要摸到一个箱子就算
                                            // 【修复】存入所有东西，不仅仅是原木，把苹果树苗也存进去！
                                            for (int i = 0; i < inventory.getContainerSize(); i++) {
                                                ItemStack slotItem = inventory.getItem(i);
                                                if (!slotItem.isEmpty()) {
                                                    for (int c = 0; c < chest.getContainerSize(); c++) {
                                                        ItemStack chestSlot = chest.getItem(c);
                                                        boolean isSameType = chestSlot.getItem() == slotItem.getItem();
                                                        if (chestSlot.isEmpty() || (isSameType && chestSlot.getCount() + slotItem.getCount() <= chestSlot.getMaxStackSize())) {
                                                            if (chestSlot.isEmpty()) chest.setItem(c, slotItem.copy());
                                                            else chestSlot.grow(slotItem.getCount());

                                                            inventory.setItem(i, ItemStack.EMPTY);
                                                            chest.setChanged();
                                                            villager.level().playSound(null, villager.blockPosition(), SoundEvents.WOOD_PLACE, SoundSource.NEUTRAL, 1.0F, 1.0F);
                                                            deposited = true;
                                                            break searchChest;
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 精准报错系统
                            if (!deposited && villager.tickCount % 60 == 0) {
                                villager.setCustomName(Component.literal("§7[闲置] 智能伐木工"));
                                villager.getPersistentData().putBoolean("IsIdle", true);
                                Player nearestPlayer = villager.level().getNearestPlayer(villager, 10.0D);
                                if (nearestPlayer != null) {
                                    if (!foundChest) {
                                        nearestPlayer.sendSystemMessage(Component.literal("§c[智能伐木工] 报告老板！大本营周围找不到箱子！我没法存木头！"));
                                    } else {
                                        nearestPlayer.sendSystemMessage(Component.literal("§c[智能伐木工] 报告老板！大本营的箱子已经完全塞满了！"));
                                    }
                                    villager.level().playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1.0F, 1.0F);
                                }
                            }
                        }
                        return;
                    }

                    // ==========================================
                    // 状态 B、C、D (捡漏、种树、找树砍)
                    // ==========================================
                    ItemEntity targetDrop = null;
                    AABB visionBox = villager.getBoundingBox().inflate(15.0D, 8.0D, 15.0D);
                    for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, visionBox)) {
                        String itemName = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).getPath();
                        if (itemName.contains("_log") || itemName.contains("sapling") || itemName.equals("apple")) {
                            targetDrop = item;
                            break;
                        }
                    }

                    if (targetDrop != null) {
                        if (villager.distanceToSqr(targetDrop) > 4.0D) {
                            villager.getNavigation().moveTo(targetDrop.getX(), targetDrop.getY(), targetDrop.getZ(), 0.6D);
                        }
                        return;
                    }

                    ItemStack saplingStack = ItemStack.EMPTY;
                    for (int i = 0; i < inventory.getContainerSize(); i++) {
                        if (!inventory.getItem(i).isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(inventory.getItem(i).getItem()).getPath().contains("sapling")) {
                            saplingStack = inventory.getItem(i);
                            break;
                        }
                    }
                    if (!saplingStack.isEmpty()) {
                        BlockPos.MutableBlockPos plantPos = new BlockPos.MutableBlockPos();
                        for (int dx = -3; dx <= 3; dx++) {
                            for (int dz = -3; dz <= 3; dz++) {
                                plantPos.set(villager.getBlockX() + dx, villager.getBlockY(), villager.getBlockZ() + dz);
                                BlockState ground = level.getBlockState(plantPos.below());
                                BlockState space = level.getBlockState(plantPos);
                                if ((ground.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) || ground.is(net.minecraft.world.level.block.Blocks.DIRT)) && space.isAir()) {
                                    if (villager.distanceToSqr(plantPos.getX(), plantPos.getY(), plantPos.getZ()) > 4.0D) {
                                        villager.getNavigation().moveTo(plantPos.getX(), plantPos.getY(), plantPos.getZ(), 0.6D);
                                    } else {
                                        net.minecraft.world.item.BlockItem saplingItem = (net.minecraft.world.item.BlockItem) saplingStack.getItem();
                                        level.setBlockAndUpdate(plantPos, saplingItem.getBlock().defaultBlockState());
                                        saplingStack.shrink(1);
                                        villager.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                                        villager.level().playSound(null, plantPos, SoundEvents.GRASS_PLACE, SoundSource.NEUTRAL, 1.0F, 1.0F);
                                    }
                                    return;
                                }
                            }
                        }
                    }

                    BlockPos targetTree = null;
                    BlockPos targetLeaf = null;
                    BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

                    scanTree:
                    for (int dx = -25; dx <= 25; dx++) {
                        for (int dy = -2; dy <= 15; dy++) {
                            for (int dz = -25; dz <= 25; dz++) {
                                mPos.set(villager.getBlockX() + dx, villager.getBlockY() + dy, villager.getBlockZ() + dz);
                                BlockState state = level.getBlockState(mPos);

                                if (state.is(net.minecraft.tags.BlockTags.LOGS)) {
                                    boolean hasLeaves = false;
                                    for (int up = 1; up <= 12; up++) {
                                        BlockState aboveState = level.getBlockState(mPos.offset(0, up, 0));
                                        if (aboveState.is(net.minecraft.tags.BlockTags.LEAVES)) {
                                            hasLeaves = true;
                                            break;
                                        }
                                        if (!aboveState.isAir() && !aboveState.is(net.minecraft.tags.BlockTags.LOGS)) {
                                            break;
                                        }
                                    }
                                    if (hasLeaves) {
                                        targetTree = mPos.immutable();
                                        break scanTree;
                                    }
                                }
                                else if (targetTree == null && state.is(net.minecraft.tags.BlockTags.LEAVES)) {
                                    targetLeaf = mPos.immutable();
                                }
                            }
                        }
                    }

                    if (targetTree != null) {
                        if (villager.distanceToSqr(targetTree.getX(), targetTree.getY(), targetTree.getZ()) > 144.0D) {
                            villager.getNavigation().moveTo(targetTree.getX(), targetTree.getY(), targetTree.getZ(), 0.6D);
                        } else {
                            level.destroyBlock(targetTree, true);
                            villager.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                        }
                    } else if (targetLeaf != null) {
                        if (villager.distanceToSqr(targetLeaf.getX(), targetLeaf.getY(), targetLeaf.getZ()) > 144.0D) {
                            villager.getNavigation().moveTo(targetLeaf.getX(), targetLeaf.getY(), targetLeaf.getZ(), 0.6D);
                        } else {
                            level.destroyBlock(targetLeaf, true);
                            villager.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                        }
                    } else {
                        if (villager.distanceToSqr(homePos.getX(), homePos.getY(), homePos.getZ()) > 25.0D) {
                            villager.getNavigation().moveTo(homePos.getX(), homePos.getY(), homePos.getZ(), 0.5D);
                        } else {
                            villager.setCustomName(Component.literal("§7[闲置] 智能伐木工"));
                            villager.getPersistentData().putBoolean("IsIdle", true);
                            Player nearestPlayer = villager.level().getNearestPlayer(villager, 10.0D);
                            if (nearestPlayer != null) {
                                nearestPlayer.sendSystemMessage(Component.literal("§e[智能伐木工] 附近已经没有树了，我下班休息啦！"));
                            }
                        }
                    }
                }
            }
        }
    }
}