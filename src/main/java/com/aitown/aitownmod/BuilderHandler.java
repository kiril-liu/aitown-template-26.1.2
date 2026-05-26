package com.aitown.aitownmod;

import net.minecraft.core.BlockPos;

import net.minecraft.network.chat.Component;

import net.minecraft.world.SimpleContainer;

import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

@EventBusSubscriber(modid = aitown.MODID)
public class BuilderHandler {

    @SubscribeEvent
    public static void onVillagerTick(EntityTickEvent.Pre event) {
        if (!event.getEntity().level().isClientSide() && event.getEntity() instanceof Villager villager) {

            //if (villager.hasCustomName() && villager.getCustomName().getString().contains("智能建筑工")) {
            if (villager.getPersistentData().contains("IsBuilding")) {
                SmartVillagerData.ensureIdentity(villager);

                // 有建筑任务时，压制原版村民自由移动
                if (villager.getPersistentData().getBoolean("IsBuilding").orElse(false)) {
                    SmartVillagerData.suppressVanillaMovement(villager);

                    // 如果当前已经有 target_place，每 5 tick 维护一次寻路方向
                    if (SmartVillagerData.hasTargetPlace(villager)
                            && SmartVillagerData.shouldThink(villager, 5)) {
                        SmartVillagerData.moveToTargetPlace(
                                villager,
                                SmartVillagerData.PLACE_BUILD_HOUSE,
                                SmartVillagerData.SPEED_WORK
                        );
                    }
                }

                // 1. 建筑工补充工作背包
                // 建筑工不再捡地上的方块，而是从附近箱子拿橡木原木。
                // ==========================================
                if (SmartVillagerData.shouldThink(villager, 20)) {
                    restockBuilderBackpack(villager);
                }
                // ==========================================
                // 2. 核心建筑 AI
                // ==========================================
                //if (villager.tickCount % 10 == 0 && villager.getPersistentData().getBoolean("IsBuilding").orElse(false)) {
                if (SmartVillagerData.shouldThink(villager, 20)
                        && villager.getPersistentData().getBoolean("IsBuilding").orElse(false)) {
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
                            //net.minecraft.world.level.block.state.BlockState requiredState = blockInfo.state();

                            net.minecraft.world.level.block.state.BlockState requiredState = blockInfo.state();

                            requiredState = normalizeToWoodenHouse(requiredState);

                            if (requiredState == null) {
                                continue;
                            }

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

                                    if (tryCraftFromOakLog(serverLevel, villager, center, requiredItem)) {
                                        debugBuilder(villager, "使用橡木原木合成："
                                                + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(requiredItem).getPath());
                                        return;
                                    }
                                }
                                if (hasMaterial) {
                                    SmartVillagerData.setDisplayStatus(villager, "§e", "施工中", "建筑工");

                                    double distance = villager.distanceToSqr(
                                            targetPos.getX() + 0.5D,
                                            targetPos.getY() + 0.5D,
                                            targetPos.getZ() + 0.5D
                                    );

                                    sourceContainer.removeItem(sourceSlot, 1);
                                    sourceContainer.setChanged();

                                    boolean placed = villager.level().setBlockAndUpdate(targetPos, requiredState);

                                    if (placed) {
                                        villager.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                                        villager.level().playSound(
                                                null,
                                                targetPos,
                                                net.minecraft.sounds.SoundEvents.WOOD_PLACE,
                                                net.minecraft.sounds.SoundSource.NEUTRAL,
                                                1.0F,
                                                1.0F
                                        );
                                    }

                                    return;
                                } else {
                                    missingMaterials = true;
                                    missingBlockName = requiredState.getBlock().getName().getString();
                                    debugBuilder(villager, "缺少材料：" + missingBlockName + "，目标方块=" + posText(targetPos));
                                    // 【核心修复】发现缺材料立刻停工！不再扫描其它方块，防止卡死服务器导致点击无反应！
                                    break;
                                }
                            }
                        }

                        // 8. 判定是否盖完
                        if (isFinished) {
                            villager.getPersistentData().putBoolean("IsBuilding", false);
                            debugBuilder(villager, "找不到图纸：" + blueprintName);
                            //villager.setCustomName(Component.literal("§7[闲置] 智能建筑工"));
                            SmartVillagerData.setDisplayStatus(villager, "§7", "闲置", "建筑工");
                            Player nearestPlayer = villager.level().getNearestPlayer(villager, 10.0D);
                            if (nearestPlayer != null) {
                                nearestPlayer.sendSystemMessage(Component.literal("§a[智能建筑工] 老板，图纸上的房子盖好啦！结工钱！"));
                                villager.level().playSound(null, villager.blockPosition(), net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
                            }
                        } else if (missingMaterials && villager.tickCount % 60 == 0) {
                            Player nearestPlayer = villager.level().getNearestPlayer(villager, 10.0D);
                            if (nearestPlayer != null) {
                                SmartVillagerData.setDisplayStatus(villager, "§c", "缺材料", "建筑工");
                                nearestPlayer.sendSystemMessage(Component.literal("§c[智能建筑工] 没材料了！我目前急需：§e" + missingBlockName));
                                villager.level().playSound(null, villager.blockPosition(), net.minecraft.sounds.SoundEvents.VILLAGER_NO, net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
                            }
                        }
                    } else {
                        villager.getPersistentData().putBoolean("IsBuilding", false);
                        debugBuilder(villager, "找不到图纸：" + blueprintName);
                        Player nearestPlayer = villager.level().getNearestPlayer(villager, 10.0D);
                        if (nearestPlayer != null) {
                            nearestPlayer.sendSystemMessage(Component.literal("§c[智能建筑工] 找不到图纸啊！"));
                        }
                    }
                }
            }
        }
    }

    private static void debugBuilder(Villager villager, String message) {
        // 每 4 秒最多打印一次，避免刷屏
        if (!SmartVillagerData.shouldThink(villager, 80)) {
            return;
        }

        Player nearestPlayer = villager.level().getNearestPlayer(villager, 16.0D);

        if (nearestPlayer != null) {
            nearestPlayer.sendSystemMessage(Component.literal(
                    "§8[BuilderDebug][" + SmartVillagerData.getCitizenName(villager) + "] §7" + message
            ));
        }
    }

    private static String posText(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static net.minecraft.world.level.block.state.BlockState normalizeToWoodenHouse(
            net.minecraft.world.level.block.state.BlockState originalState
    ) {
        if (originalState.isAir()
                || originalState.is(net.minecraft.world.level.block.Blocks.STRUCTURE_VOID)
                || originalState.is(net.minecraft.world.level.block.Blocks.STRUCTURE_BLOCK)) {
            return null;
        }

        String blockName = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(originalState.getBlock())
                .getPath();

        // ================================
        // 门是双格方块，当前阶段先转成单格栅栏门，避免下半门反复破坏/重建。
        // 门的上半部分直接跳过。
        // ================================
        if (originalState.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
            net.minecraft.world.level.block.state.properties.DoubleBlockHalf half =
                    originalState.getValue(net.minecraft.world.level.block.DoorBlock.HALF);

            if (half == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) {
                return null;
            }
            return copyCommonProperties(
                    originalState,
                    net.minecraft.world.level.block.Blocks.OAK_FENCE_GATE.defaultBlockState()
            );
        }

        // ================================
        // 1. 跳过当前木屋阶段不需要的东西
        // ================================
        if (blockName.contains("torch")
                || blockName.contains("lantern")
                || blockName.contains("candle")
                || blockName.contains("flower_pot")
                || blockName.contains("bell")
                || blockName.contains("bed")
                || blockName.contains("chest")
                || blockName.contains("barrel")) {
            return null;
        }

        // ================================
        // 2. 玻璃类 -> 木窗格
        // 原版没有 oak_window，所以先用 oak_fence 当木窗。
        // ================================
        if (blockName.contains("glass")
                || blockName.contains("pane")
                || blockName.contains("iron_bars")) {
            return net.minecraft.world.level.block.Blocks.OAK_FENCE.defaultBlockState();
        }

        // ================================
        // 3. 楼梯、半砖、门、栅栏、活板门统一橡木化
        // ================================
        if (blockName.endsWith("_stairs")) {
            return copyCommonProperties(
                    originalState,
                    net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState()
            );
        }

        if (blockName.endsWith("_slab")) {
            return copyCommonProperties(
                    originalState,
                    net.minecraft.world.level.block.Blocks.OAK_SLAB.defaultBlockState()
            );
        }


        if (blockName.endsWith("_trapdoor")) {
            return net.minecraft.world.level.block.Blocks.OAK_TRAPDOOR.defaultBlockState();
        }

        if (blockName.endsWith("_fence")
                || blockName.endsWith("_wall")) {
            return net.minecraft.world.level.block.Blocks.OAK_FENCE.defaultBlockState();
        }

        if (blockName.endsWith("_fence_gate")) {
            return net.minecraft.world.level.block.Blocks.OAK_FENCE_GATE.defaultBlockState();
        }

        // ================================
        // 4. 原木类统一成橡木原木
        // ================================
        if (blockName.endsWith("_log")
                || blockName.endsWith("_wood")) {
            return net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState();
        }

        if (blockName.contains("stripped")) {
            return net.minecraft.world.level.block.Blocks.STRIPPED_OAK_LOG.defaultBlockState();
        }

        // ================================
        // 5. 石头 / 砖 / 混凝土 / 泥土类 -> 木板或去皮橡木
        // ================================
        if (blockName.contains("stone")
                || blockName.contains("cobblestone")
                || blockName.contains("brick")
                || blockName.contains("deepslate")
                || blockName.contains("andesite")
                || blockName.contains("diorite")
                || blockName.contains("granite")
                || blockName.contains("sandstone")
                || blockName.contains("tuff")
                || blockName.contains("calcite")
                || blockName.contains("terracotta")
                || blockName.contains("concrete")
                || blockName.contains("mud")
                || blockName.contains("clay")) {
            return net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState();
        }

        // ================================
        // 6. 普通木板类统一成橡木木板
        // ================================
        if (blockName.endsWith("_planks")) {
            return net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState();
        }

        // ================================
        // 7. 叶子、装饰、地毯等暂时跳过或木板化
        // ================================
        if (blockName.contains("leaves")
                || blockName.contains("carpet")
                || blockName.contains("wool")) {
            return null;
        }

        // ================================
        // 8. 如果本来就是橡木相关，就保留
        // ================================
        if (blockName.startsWith("oak_")) {
            return originalState;
        }

        // ================================
        // 9. 其他未知方块，当前木屋阶段先统一转木板
        // ================================
        return net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState();
    }

    private static int getOakLogCraftYield(net.minecraft.world.item.Item requiredItem) {
        String itemName = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(requiredItem)
                .getPath();

        if (itemName.equals("oak_log")) {
            return 1;
        }

        if (itemName.equals("stripped_oak_log")) {
            return 1;
        }

        if (itemName.equals("oak_planks")) {
            return 4;
        }

        if (itemName.equals("oak_stairs")) {
            return 4;
        }

        if (itemName.equals("oak_slab")) {
            return 8;
        }

        if (itemName.equals("oak_door")) {
            return 3;
        }

        if (itemName.equals("oak_fence")) {
            return 3;
        }

        if (itemName.equals("oak_fence_gate")) {
            return 1;
        }

        if (itemName.equals("oak_trapdoor")) {
            return 2;
        }

        if (itemName.equals("oak_button")) {
            return 4;
        }

        if (itemName.equals("oak_pressure_plate")) {
            return 2;
        }

        if (itemName.equals("oak_sign")) {
            return 2;
        }

        return 0;
    }

    private static boolean consumeOneOakLog(
            net.minecraft.server.level.ServerLevel serverLevel,
            Villager villager,
            net.minecraft.core.BlockPos center
    ) {
        net.minecraft.world.item.Item oakLogItem = net.minecraft.world.item.Items.OAK_LOG;

        // 1. 先找村民自己背包
        net.minecraft.world.SimpleContainer inventory = villager.getInventory();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(i);

            if (!stack.isEmpty() && stack.getItem() == oakLogItem) {
                inventory.removeItem(i, 1);
                inventory.setChanged();
                return true;
            }
        }

        // 2. 再找建筑中心附近箱子
        int radius = 10;
        net.minecraft.core.BlockPos.MutableBlockPos mutablePos =
                new net.minecraft.core.BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    mutablePos.set(
                            center.getX() + dx,
                            center.getY() + dy,
                            center.getZ() + dz
                    );

                    net.minecraft.world.level.block.entity.BlockEntity be =
                            serverLevel.getBlockEntity(mutablePos);

                    if (be instanceof net.minecraft.world.Container chest) {
                        for (int i = 0; i < chest.getContainerSize(); i++) {
                            net.minecraft.world.item.ItemStack stack = chest.getItem(i);

                            if (!stack.isEmpty() && stack.getItem() == oakLogItem) {
                                chest.removeItem(i, 1);
                                chest.setChanged();
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    private static boolean tryCraftFromOakLog(
            net.minecraft.server.level.ServerLevel serverLevel,
            Villager villager,
            net.minecraft.core.BlockPos center,
            net.minecraft.world.item.Item requiredItem
    ) {
        int yieldCount = getOakLogCraftYield(requiredItem);

        if (yieldCount <= 0) {
            return false;
        }

        boolean consumed = consumeOneOakLog(serverLevel, villager, center);

        if (!consumed) {
            return false;
        }

        net.minecraft.world.SimpleContainer inventory = villager.getInventory();

        inventory.addItem(new net.minecraft.world.item.ItemStack(requiredItem, yieldCount));
        inventory.setChanged();

        villager.level().playSound(
                null,
                villager.blockPosition(),
                net.minecraft.sounds.SoundEvents.VILLAGER_WORK_CARTOGRAPHER,
                net.minecraft.sounds.SoundSource.NEUTRAL,
                0.8F,
                1.2F
        );

        return true;
    }

    private static net.minecraft.world.level.block.state.BlockState copyCommonProperties(
            net.minecraft.world.level.block.state.BlockState from,
            net.minecraft.world.level.block.state.BlockState to
    ) {
        // 楼梯、门、栅栏门、活板门常用：朝向
        if (from.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                && to.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            to = to.setValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,
                    from.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
            );
        }

        // 楼梯：上半 / 下半
        if (from.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)
                && to.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)) {
            to = to.setValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF,
                    from.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)
            );
        }

        // 楼梯：直线 / 内角 / 外角
        if (from.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.STAIRS_SHAPE)
                && to.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.STAIRS_SHAPE)) {
            to = to.setValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.STAIRS_SHAPE,
                    from.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.STAIRS_SHAPE)
            );
        }

        // 半砖：上半砖 / 下半砖 / 双层
        if (from.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE)
                && to.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE)) {
            to = to.setValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE,
                    from.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE)
            );
        }

        // 原木：轴向
        if (from.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)
                && to.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)) {
            to = to.setValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS,
                    from.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)
            );
        }

        // 栅栏门 / 活板门：开关状态
        if (from.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
                && to.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)) {
            to = to.setValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN,
                    from.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
            );
        }

        return to;
    }


    private static void restockBuilderBackpack(Villager villager) {
        if (!(villager.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }

        net.minecraft.world.SimpleContainer inventory = villager.getInventory();
        net.minecraft.world.item.Item oakLogItem = net.minecraft.world.item.Items.OAK_LOG;

        int currentOakLogs = countItem(inventory, oakLogItem);
        int emptySlots = countEmptySlots(inventory);

        // 目标：建筑工身上尽量保持 32 个橡木原木。
        // 不要塞满，给合成出来的楼梯、半砖、栅栏留空间。
        int targetOakLogs = 32;

        if (currentOakLogs >= targetOakLogs) {
            return;
        }

        if (emptySlots <= 0) {
            SmartVillagerData.setDisplayStatus(villager, "§c", "背包满", "建筑工");
            return;
        }

        int need = targetOakLogs - currentOakLogs;

        net.minecraft.core.BlockPos center = getBuilderSupplyCenter(villager);

        boolean movedAny = false;

        int radius = 12;
        net.minecraft.core.BlockPos.MutableBlockPos mutablePos =
                new net.minecraft.core.BlockPos.MutableBlockPos();

        searchChest:
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    mutablePos.set(
                            center.getX() + dx,
                            center.getY() + dy,
                            center.getZ() + dz
                    );

                    net.minecraft.world.level.block.entity.BlockEntity be =
                            serverLevel.getBlockEntity(mutablePos);

                    if (be instanceof net.minecraft.world.Container chest) {
                        for (int i = 0; i < chest.getContainerSize(); i++) {
                            net.minecraft.world.item.ItemStack chestStack = chest.getItem(i);

                            if (chestStack.isEmpty()) {
                                continue;
                            }

                            if (chestStack.getItem() != oakLogItem) {
                                continue;
                            }

                            int toMove = Math.min(need, chestStack.getCount());

                            net.minecraft.world.item.ItemStack moving =
                                    new net.minecraft.world.item.ItemStack(oakLogItem, toMove);

                            net.minecraft.world.item.ItemStack remaining =
                                    inventory.addItem(moving);

                            int moved = toMove - remaining.getCount();

                            if (moved > 0) {
                                chestStack.shrink(moved);
                                chest.setChanged();
                                inventory.setChanged();

                                need -= moved;
                                movedAny = true;

                                SmartVillagerData.setDisplayStatus(villager, "§b", "补充材料", "建筑工");

                                villager.level().playSound(
                                        null,
                                        villager.blockPosition(),
                                        net.minecraft.sounds.SoundEvents.ITEM_PICKUP,
                                        net.minecraft.sounds.SoundSource.NEUTRAL,
                                        0.3F,
                                        1.4F
                                );
                            }

                            if (need <= 0) {
                                break searchChest;
                            }
                        }
                    }
                }
            }
        }

        if (!movedAny && currentOakLogs <= 0) {
            SmartVillagerData.setDisplayStatus(villager, "§6", "等待木材", "建筑工");

            // 不要频繁刷屏，偶尔提示即可
            if (SmartVillagerData.shouldThink(villager, 120)) {
                net.minecraft.world.entity.player.Player nearestPlayer =
                        villager.level().getNearestPlayer(villager, 12.0D);

                if (nearestPlayer != null) {
                    nearestPlayer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§e[" + SmartVillagerData.getCitizenName(villager)
                                    + "] 正在等待仓库补充橡木原木。"
                    ));
                }
            }
        }
    }

    private static int countItem(
            net.minecraft.world.SimpleContainer inventory,
            net.minecraft.world.item.Item item
    ) {
        int count = 0;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(i);

            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }

        return count;
    }
    private static int countEmptySlots(net.minecraft.world.SimpleContainer inventory) {
        int count = 0;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                count++;
            }
        }

        return count;
    }
    private static net.minecraft.core.BlockPos getBuilderSupplyCenter(Villager villager) {
        if (villager.getPersistentData().contains("BuildCenterX")) {
            int x = villager.getPersistentData().getInt("BuildCenterX").orElse(villager.getBlockX());
            int y = villager.getPersistentData().getInt("BuildCenterY").orElse(villager.getBlockY());
            int z = villager.getPersistentData().getInt("BuildCenterZ").orElse(villager.getBlockZ());

            return new net.minecraft.core.BlockPos(x, y, z);
        }

        return villager.blockPosition();
    }
}