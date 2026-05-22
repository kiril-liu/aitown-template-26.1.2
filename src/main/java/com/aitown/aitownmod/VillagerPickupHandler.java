package com.aitown.aitownmod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

@EventBusSubscriber(modid = aitown.MODID)
public class VillagerPickupHandler {

    @SubscribeEvent
    public static void onVillagerTick(EntityTickEvent.Pre event) {
        if (!event.getEntity().level().isClientSide() && event.getEntity() instanceof Villager villager) {

            if (villager.hasCustomName() && villager.getCustomName().getString().contains("智能建筑工")) {

                // ==========================================
                // 1. 捡东西的逻辑
                // ==========================================
                AABB searchBox = villager.getBoundingBox().inflate(2.0D, 1.0D, 2.0D);
                for (ItemEntity itemEntity : villager.level().getEntitiesOfClass(ItemEntity.class, searchBox)) {
                    ItemStack stack = itemEntity.getItem();
                    if (stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                        SimpleContainer inventory = villager.getInventory();
                        int beforeCount = stack.getCount();
                        ItemStack remaining = inventory.addItem(stack);
                        if (remaining.getCount() < beforeCount) {
                            itemEntity.setItem(remaining);
                            villager.level().playSound(null, villager.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.2F, 1.5F);
                        } else {
                            if (villager.tickCount % 60 == 0) {
                                Player nearestPlayer = villager.level().getNearestPlayer(villager, 10.0D);
                                if (nearestPlayer != null) {
                                    nearestPlayer.sendSystemMessage(Component.literal("§c[智能建筑工] 我的 8 格背包已经塞满啦！"));
                                    villager.level().playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1.0F, 1.0F);
                                }
                            }
                        }
                    }
                }

                // ==========================================
                // 2. 核心建筑 AI
                // ==========================================
                if (villager.tickCount % 10 == 0 && villager.getPersistentData().getBoolean("IsBuilding").orElse(false)) {
                    int cx = villager.getPersistentData().getInt("BuildCenterX").orElse(0);
                    int cy = villager.getPersistentData().getInt("BuildCenterY").orElse(0);
                    int cz = villager.getPersistentData().getInt("BuildCenterZ").orElse(0);
                    net.minecraft.core.BlockPos center = new net.minecraft.core.BlockPos(cx, cy, cz);

                    net.minecraft.server.level.ServerLevel serverLevel = (net.minecraft.server.level.ServerLevel) villager.level();
                    net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager structureManager = serverLevel.getServer().getStructureManager();

                    String blueprintName = villager.getPersistentData().getString("BlueprintName").orElse("minecraft:village/plains/houses/plains_small_house_1");
                    net.minecraft.resources.Identifier structureId = net.minecraft.resources.Identifier.parse(blueprintName);

                    java.util.Optional<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate> optionalTemplate = structureManager.get(structureId);

                    if (optionalTemplate.isPresent()) {
                        net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate template = optionalTemplate.get();
                        boolean isFinished = true;
                        boolean missingMaterials = false;
                        String missingBlockName = "";

                        java.util.List<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo> blocks = new java.util.ArrayList<>();
                        try {
                            java.lang.reflect.Field palettesField = net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.class.getDeclaredField("palettes");
                            palettesField.setAccessible(true);
                            java.util.List<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.Palette> palettesList =
                                    (java.util.List<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.Palette>) palettesField.get(template);
                            if (!palettesList.isEmpty()) {
                                blocks = palettesList.get(0).blocks();
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                        for (net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo blockInfo : blocks) {
                            net.minecraft.world.level.block.state.BlockState requiredState = blockInfo.state();

                            if (requiredState.isAir() || requiredState.is(net.minecraft.world.level.block.Blocks.STRUCTURE_VOID)) {
                                continue;
                            }

                            net.minecraft.core.BlockPos targetPos = center.offset(blockInfo.pos());
                            net.minecraft.world.level.block.state.BlockState currentState = serverLevel.getBlockState(targetPos);

                            if (!currentState.is(requiredState.getBlock())) {
                                isFinished = false;

                                net.minecraft.world.item.Item requiredItem = requiredState.getBlock().asItem();
                                boolean hasMaterial = false;
                                net.minecraft.world.Container sourceContainer = null;
                                int sourceSlot = -1;

                                // A. 找村民自己的背包
                                SimpleContainer inventory = villager.getInventory();
                                for (int i = 0; i < inventory.getContainerSize(); i++) {
                                    if (!inventory.getItem(i).isEmpty() && inventory.getItem(i).getItem() == requiredItem) {
                                        hasMaterial = true;
                                        sourceContainer = inventory;
                                        sourceSlot = i;
                                        break;
                                    }
                                }

                                // B. 开启“蓝牙”扫描周围 10 格的所有容器（箱子/木桶等）
                                if (!hasMaterial) {
                                    int radius = 10;
                                    net.minecraft.core.BlockPos.MutableBlockPos mutablePos = new net.minecraft.core.BlockPos.MutableBlockPos();
                                    searchChests:
                                    for (int dx = -radius; dx <= radius; dx++) {
                                        for (int dy = -4; dy <= 4; dy++) {
                                            for (int dz = -radius; dz <= radius; dz++) {
                                                mutablePos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                                                net.minecraft.world.level.block.entity.BlockEntity be = serverLevel.getBlockEntity(mutablePos);
                                                if (be instanceof net.minecraft.world.Container chest) {
                                                    for (int i = 0; i < chest.getContainerSize(); i++) {
                                                        if (!chest.getItem(i).isEmpty() && chest.getItem(i).getItem() == requiredItem) {
                                                            hasMaterial = true;
                                                            sourceContainer = chest;
                                                            sourceSlot = i;
                                                            break searchChests;
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // ==========================================
                                // C. 【新增】随身工作台：没找到材料？尝试在大脑里合成！
                                // ==========================================
                                if (!hasMaterial) {
                                    String reqName = BuiltInRegistries.ITEM.getKey(requiredItem).getPath();
                                    String ingredientName = null;
                                    int yieldCount = 1; // 默认合成产出 1 个

                                    // 【合成法则表】你可以随时在这里添加新的转换规则！
                                    if (reqName.endsWith("_planks")) {
                                        ingredientName = reqName.replace("_planks", "_log"); // 木板 -> 找原木
                                        yieldCount = 4;
                                    } else if (reqName.endsWith("_stairs")) {
                                        ingredientName = reqName.replace("_stairs", "_planks"); // 楼梯 -> 找木板
                                    } else if (reqName.endsWith("_slab")) {
                                        ingredientName = reqName.replace("_slab", "_planks"); // 半砖 -> 找木板
                                        yieldCount = 2;
                                    } else if (reqName.endsWith("_door")) {
                                        ingredientName = reqName.replace("_door", "_planks"); // 门 -> 找木板
                                    } else if (reqName.endsWith("_fence")) {
                                        ingredientName = reqName.replace("_fence", "_planks"); // 栅栏 -> 找木板
                                    } else if (reqName.equals("cobblestone_stairs") || reqName.equals("cobblestone_slab") || reqName.equals("cobblestone_wall")) {
                                        ingredientName = "cobblestone"; // 各种圆石建材 -> 找圆石
                                        yieldCount = reqName.endsWith("_slab") ? 2 : 1;
                                    } else if (reqName.equals("stone_bricks")) {
                                        ingredientName = "stone"; // 石砖 -> 找石头
                                    } else if (reqName.equals("glass_pane")) {
                                        ingredientName = "glass"; // 玻璃板 -> 找玻璃
                                        yieldCount = 2;
                                    }

                                    // 如果触发了合成法则，就去寻找“原料”
                                    if (ingredientName != null) {
                                        //net.minecraft.world.item.Item ingredientItem = BuiltInRegistries.ITEM.get(net.minecraft.resources.Identifier.parse("minecraft:" + ingredientName)).get().value();
                                        // 【核心修复】安全拆盲盒！先拿到盒子，不直接撕开
                                        java.util.Optional<net.minecraft.core.Holder.Reference<net.minecraft.world.item.Item>> optItem =
                                                BuiltInRegistries.ITEM.get(net.minecraft.resources.Identifier.parse("minecraft:" + ingredientName));

                                        // 晃一晃盒子，如果盒子是空的（比如想要找“石头木板”这种不存在的东西），就直接结束本次思考，向玩家报错！
                                        if (optItem.isEmpty()) {
                                            missingMaterials = true;
                                            missingBlockName = requiredState.getBlock().getName().getString() + " (无法自动合成，请直接提供成品)";
                                            break;
                                        }

                                        // 如果盒子里有东西，安全取出！
                                        net.minecraft.world.item.Item ingredientItem = optItem.get().value();

                                        boolean hasIngredient = false;
                                        net.minecraft.world.Container ingContainer = null;
                                        int ingSlot = -1;

                                        // 先找自己背包有没有原料
                                        for (int i = 0; i < inventory.getContainerSize(); i++) {
                                            if (!inventory.getItem(i).isEmpty() && inventory.getItem(i).getItem() == ingredientItem) {
                                                hasIngredient = true;
                                                ingContainer = inventory;
                                                ingSlot = i;
                                                break;
                                            }
                                        }

                                        // 如果自己包里没有，扫描周围蓝牙箱子里的原料
                                        if (!hasIngredient) {
                                            int radius = 10;
                                            net.minecraft.core.BlockPos.MutableBlockPos mutablePos = new net.minecraft.core.BlockPos.MutableBlockPos();
                                            searchIng:
                                            for (int dx = -radius; dx <= radius; dx++) {
                                                for (int dy = -4; dy <= 4; dy++) {
                                                    for (int dz = -radius; dz <= radius; dz++) {
                                                        mutablePos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                                                        net.minecraft.world.level.block.entity.BlockEntity be = serverLevel.getBlockEntity(mutablePos);
                                                        if (be instanceof net.minecraft.world.Container chest) {
                                                            for (int i = 0; i < chest.getContainerSize(); i++) {
                                                                if (!chest.getItem(i).isEmpty() && chest.getItem(i).getItem() == ingredientItem) {
                                                                    hasIngredient = true;
                                                                    ingContainer = chest;
                                                                    ingSlot = i;
                                                                    break searchIng;
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // 【开始脑内合成！】如果找到了原料
                                        if (hasIngredient) {
                                            // 扣除 1 个原料
                                            ingContainer.removeItem(ingSlot, 1);
                                            ingContainer.setChanged();

                                            // 凭空把合成出来的成品（如 4个木板）塞进村民的背包里！
                                            inventory.addItem(new ItemStack(requiredItem, yieldCount));

                                            // 播放一个类似“做手工”的音效
                                            villager.level().playSound(null, villager.blockPosition(), net.minecraft.sounds.SoundEvents.VILLAGER_WORK_MASON, net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);

                                            // 【极其关键】这 0.5 秒用来做手工了，告诉大脑：等下一个 0.5 秒再去放置！
                                            return;
                                        }
                                    }
                                }

                                if (hasMaterial) {
                                    if (villager.distanceToSqr(targetPos.getX(), targetPos.getY(), targetPos.getZ()) > 64.0D) {
                                        villager.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 0.6D);
                                    } else {
                                        sourceContainer.removeItem(sourceSlot, 1);
                                        sourceContainer.setChanged();
                                        villager.level().setBlockAndUpdate(targetPos, requiredState);
                                        villager.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                                        villager.level().playSound(null, targetPos, net.minecraft.sounds.SoundEvents.WOOD_PLACE, net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
                                    }
                                    return;
                                } else {
                                    missingMaterials = true;
                                    missingBlockName = requiredState.getBlock().getName().getString();
                                    // 【核心修复】发现缺材料立刻停工！不再扫描其它方块，防止卡死服务器导致点击无反应！
                                    break;
                                }
                            }
                        }

                        // 8. 判定是否盖完
                        if (isFinished) {
                            villager.getPersistentData().putBoolean("IsBuilding", false);
                            villager.setCustomName(Component.literal("§7[闲置] 智能建筑工"));
                            Player nearestPlayer = villager.level().getNearestPlayer(villager, 10.0D);
                            if (nearestPlayer != null) {
                                nearestPlayer.sendSystemMessage(Component.literal("§a[智能建筑工] 老板，图纸上的房子盖好啦！结工钱！"));
                                villager.level().playSound(null, villager.blockPosition(), net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
                            }
                        } else if (missingMaterials && villager.tickCount % 60 == 0) {
                            Player nearestPlayer = villager.level().getNearestPlayer(villager, 10.0D);
                            if (nearestPlayer != null) {
                                nearestPlayer.sendSystemMessage(Component.literal("§c[智能建筑工] 没材料了！我目前急需：§e" + missingBlockName));
                                villager.level().playSound(null, villager.blockPosition(), net.minecraft.sounds.SoundEvents.VILLAGER_NO, net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
                            }
                        }
                    } else {
                        villager.getPersistentData().putBoolean("IsBuilding", false);
                        Player nearestPlayer = villager.level().getNearestPlayer(villager, 10.0D);
                        if (nearestPlayer != null) {
                            nearestPlayer.sendSystemMessage(Component.literal("§c[智能建筑工] 找不到图纸啊！"));
                        }
                    }
                }
            }
        }
    }
}