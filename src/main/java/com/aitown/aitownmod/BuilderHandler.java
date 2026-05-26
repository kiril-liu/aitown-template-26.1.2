package com.aitown.aitownmod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.List;

@EventBusSubscriber(modid = aitown.MODID)
public class BuilderHandler {
    private static final String KEY_STATE = "BuilderState";

    private static final String STATE_FIND_SITE = "find_site";
    private static final String STATE_FETCH_MATERIALS = "fetch_materials";
    private static final String STATE_MOVE_TO_DOOR = "move_to_door";
    private static final String STATE_BUILD = "build";

    private static final String KEY_BUILD_X = "BuildCenterX";
    private static final String KEY_BUILD_Y = "BuildCenterY";
    private static final String KEY_BUILD_Z = "BuildCenterZ";

    private static final String KEY_DOOR_X = "BuilderDoorX";
    private static final String KEY_DOOR_Y = "BuilderDoorY";
    private static final String KEY_DOOR_Z = "BuilderDoorZ";

    private static final String KEY_BLUEPRINT = "BlueprintName";

    private static final int SITE_SEARCH_RADIUS = 40;
    private static final int SITE_MARGIN = 2;
    private static final int TARGET_OAK_LOGS = 48;
    private static final int MAX_PLACE_PER_TICK = 4;
    private static final int TARGET_COBBLESTONE = 64;

    @SubscribeEvent
    public static void onVillagerTick(EntityTickEvent.Pre event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }

        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }

        if (!SmartVillagerData.isRole(villager, SmartVillagerData.ROLE_BUILDER)) {
            return;
        }

        if (!(villager.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        SmartVillagerData.ensureIdentity(villager);
        SmartVillagerData.suppressVanillaMovement(villager);

        if (!SmartVillagerData.shouldThink(villager, 10)) {
            return;
        }

        String state = getState(villager);

        if (STATE_FIND_SITE.equals(state)) {
            tickFindSite(level, villager);
            return;
        }

        if (STATE_FETCH_MATERIALS.equals(state)) {
            tickFetchMaterials(level, villager);
            return;
        }

        if (STATE_MOVE_TO_DOOR.equals(state)) {
            tickMoveToDoor(level, villager);
            return;
        }

        if (STATE_BUILD.equals(state)) {
            tickBuild(level, villager);
            return;
        }

        setState(villager, STATE_FIND_SITE);
    }

    private static void tickFindSite(net.minecraft.server.level.ServerLevel level, Villager villager) {
        SmartVillagerData.setStatus(villager, "寻找空地", "根据蓝图尺寸寻找可建造区域");

        List<StructureTemplate.StructureBlockInfo> blocks = loadTemplateBlocks(level, villager);

        if (blocks.isEmpty()) {
            SmartVillagerData.setStatus(villager, "无蓝图", "找不到房屋蓝图");
            return;
        }

        BuildBounds bounds = calculateBounds(blocks);

        BlockPos origin = findBuildableSite(level, villager, blocks, bounds);

        if (origin == null) {
            SmartVillagerData.setStatus(villager, "没有空地", "附近没有足够大的空地");
            return;
        }

        BlockPos doorStand = findDoorStandPos(level, origin, blocks);

        if (doorStand == null) {
            SmartVillagerData.setStatus(villager, "无门口站位", "找不到门口施工点");
            return;
        }

        savePos(villager, KEY_BUILD_X, KEY_BUILD_Y, KEY_BUILD_Z, origin);
        savePos(villager, KEY_DOOR_X, KEY_DOOR_Y, KEY_DOOR_Z, doorStand);

        setState(villager, STATE_FETCH_MATERIALS);
    }

    private static void tickFetchMaterials(net.minecraft.server.level.ServerLevel level, Villager villager) {
        SimpleContainer inventory = villager.getInventory();

        int oakLogs = SmartVillagerData.countItems(inventory, "minecraft:oak_log");
        int cobblestone = SmartVillagerData.countItems(inventory, "minecraft:cobblestone");
        int stone = SmartVillagerData.countItems(inventory, "minecraft:stone");

        // 只要身上有一批基础材料，就可以先回门口继续施工。
        // 建造时如果发现缺具体材料，会再次回来仓库取。
        if (oakLogs >= TARGET_OAK_LOGS / 2
                || cobblestone >= TARGET_COBBLESTONE / 2
                || stone >= TARGET_COBBLESTONE / 2) {
            setState(villager, STATE_MOVE_TO_DOOR);
            return;
        }

        BlockPos warehouse = SmartVillagerData.getWarehouseCenter(villager);

        SmartVillagerData.setStatus(villager, "仓库取材", "去小镇仓库拿木材和石材");

        SmartVillagerData.setTargetPlace(villager, warehouse, "builder_fetch_materials");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_STORAGE,
                SmartVillagerData.SPEED_NORMAL
        );

        if (!arrived) {
            return;
        }

        int moved = SmartVillagerData.takeItemsFromWarehouse(
                level,
                villager,
                warehouse,
                List.of(
                        // 木结构基础材料。
                        new SmartVillagerData.ItemRequest("minecraft:oak_log", TARGET_OAK_LOGS),

                        // 石结构基础材料。
                        // 当前矿工主要产 cobblestone，所以建筑师优先拿圆石。
                        new SmartVillagerData.ItemRequest("minecraft:cobblestone", TARGET_COBBLESTONE),

                        // 如果仓库里以后有 stone，也可以拿。
                        new SmartVillagerData.ItemRequest("minecraft:stone", TARGET_COBBLESTONE)
                ),
                SmartVillagerData.WAREHOUSE_RADIUS
        );

        if (moved > 0) {
            setState(villager, STATE_MOVE_TO_DOOR);
        } else {
            SmartVillagerData.setStatus(villager, "仓库等料", "仓库暂时没有木材或石材");
        }
    }

    private static void tickMoveToDoor(net.minecraft.server.level.ServerLevel level, Villager villager) {
        BlockPos doorStand = readPos(villager, KEY_DOOR_X, KEY_DOOR_Y, KEY_DOOR_Z);

        if (doorStand == null) {
            setState(villager, STATE_FIND_SITE);
            return;
        }

        SmartVillagerData.setStatus(villager, "走向门口", "站到门口施工点");

        SmartVillagerData.setTargetPlace(villager, doorStand, "builder_door_stand");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_WORK,
                SmartVillagerData.SPEED_NORMAL
        );

        if (arrived) {
            setState(villager, STATE_BUILD);
        }
    }

    private static void tickBuild(net.minecraft.server.level.ServerLevel level, Villager villager) {
        BlockPos origin = readPos(villager, KEY_BUILD_X, KEY_BUILD_Y, KEY_BUILD_Z);
        BlockPos doorStand = readPos(villager, KEY_DOOR_X, KEY_DOOR_Y, KEY_DOOR_Z);

        if (origin == null || doorStand == null) {
            setState(villager, STATE_FIND_SITE);
            return;
        }

        double distance = villager.distanceToSqr(
                doorStand.getX() + 0.5D,
                doorStand.getY(),
                doorStand.getZ() + 0.5D
        );

        if (distance > SmartVillagerData.PLACE_WORK) {
            setState(villager, STATE_MOVE_TO_DOOR);
            return;
        }

        List<StructureTemplate.StructureBlockInfo> blocks = loadTemplateBlocks(level, villager);

        if (blocks.isEmpty()) {
            setState(villager, STATE_FIND_SITE);
            return;
        }

        SmartVillagerData.setStatus(villager, "施工中", "按蓝图建造橡木房屋");

        int placed = 0;
        boolean finished = true;

        for (StructureTemplate.StructureBlockInfo blockInfo : blocks) {
            BlockState required = normalizeToWoodenHouse(blockInfo.state());

            if (required == null || required.isAir() || required.is(Blocks.STRUCTURE_VOID)) {
                continue;
            }

            BlockPos targetPos = origin.offset(blockInfo.pos());
            BlockState current = level.getBlockState(targetPos);

            if (current.is(required.getBlock())) {
                continue;
            }

            finished = false;

            if (!SmartVillagerData.canPlaceBlockSafely(level, villager, targetPos)) {
                // 目标点有人或不是空气时不放，等待后续 tick 再尝试。
                continue;
            }

            Item requiredItem = required.getBlock().asItem();

            if (requiredItem == Items.AIR) {
                continue;
            }

            if (!consumeOneItem(villager.getInventory(), requiredItem)) {
                // 先尝试用石材加工。
                // 例如：圆石 -> 圆石楼梯 / 圆石台阶 / 圆石墙。
                if (tryCraftFromCobblestone(villager, requiredItem)) {
                    return;
                }

                // 再尝试用橡木原木加工。
                // 例如：橡木原木 -> 木板 / 楼梯 / 台阶 / 栅栏门 / 活板门。
                if (tryCraftFromOakLog(villager, requiredItem)) {
                    return;
                }

                // 两种基础材料都没有，回仓库取。
                setState(villager, STATE_FETCH_MATERIALS);
                return;
            }

            if (level.setBlockAndUpdate(targetPos, required)) {
                placed++;

                villager.swing(InteractionHand.MAIN_HAND);

                level.playSound(
                        null,
                        targetPos,
                        SoundEvents.WOOD_PLACE,
                        SoundSource.NEUTRAL,
                        0.8F,
                        1.0F
                );
            }

            if (placed >= MAX_PLACE_PER_TICK) {
                return;
            }
        }

        if (finished) {
            SmartVillagerData.setStatus(villager, "房屋完成", "寻找下一块建造空地");

            clearPos(villager, KEY_BUILD_X, KEY_BUILD_Y, KEY_BUILD_Z);
            clearPos(villager, KEY_DOOR_X, KEY_DOOR_Y, KEY_DOOR_Z);
            SmartVillagerData.clearTargetPlace(villager);

            setState(villager, STATE_FIND_SITE);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<StructureTemplate.StructureBlockInfo> loadTemplateBlocks(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        String blueprintName = villager.getPersistentData()
                .getString(KEY_BLUEPRINT)
                .orElse("minecraft:village/plains/houses/plains_small_house_1");

        Identifier structureId = Identifier.parse(blueprintName);

        java.util.Optional<StructureTemplate> optionalTemplate =
                level.getServer().getStructureManager().get(structureId);

        if (optionalTemplate.isEmpty()) {
            return List.of();
        }

        try {
            java.lang.reflect.Field palettesField = StructureTemplate.class.getDeclaredField("palettes");
            palettesField.setAccessible(true);

            List<StructureTemplate.Palette> palettes =
                    (List<StructureTemplate.Palette>) palettesField.get(optionalTemplate.get());

            if (!palettes.isEmpty()) {
                return palettes.get(0).blocks();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return List.of();
    }

    private record BuildBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
    }

    private static BuildBounds calculateBounds(List<StructureTemplate.StructureBlockInfo> blocks) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (StructureTemplate.StructureBlockInfo info : blocks) {
            BlockState converted = normalizeToWoodenHouse(info.state());

            if (converted == null || converted.isAir() || converted.is(Blocks.STRUCTURE_VOID)) {
                continue;
            }

            BlockPos p = info.pos();

            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }

        return new BuildBounds(minX, maxX, minY, maxY, minZ, maxZ);
    }

    private static BlockPos findBuildableSite(
            net.minecraft.server.level.ServerLevel level,
            Villager villager,
            List<StructureTemplate.StructureBlockInfo> blocks,
            BuildBounds bounds
    ) {
        BlockPos.MutableBlockPos candidate = new BlockPos.MutableBlockPos();

        for (int r = 8; r <= SITE_SEARCH_RADIUS; r += 4) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }

                    candidate.set(
                            villager.getBlockX() + dx,
                            villager.getBlockY(),
                            villager.getBlockZ() + dz
                    );

                    if (isBuildAreaClear(level, candidate, bounds)) {
                        return candidate.immutable();
                    }
                }
            }
        }

        return null;
    }

    private static boolean isBuildAreaClear(
            net.minecraft.server.level.ServerLevel level,
            BlockPos origin,
            BuildBounds bounds
    ) {
        for (int x = bounds.minX - SITE_MARGIN; x <= bounds.maxX + SITE_MARGIN; x++) {
            for (int y = bounds.minY; y <= bounds.maxY + 2; y++) {
                for (int z = bounds.minZ - SITE_MARGIN; z <= bounds.maxZ + SITE_MARGIN; z++) {
                    if (!level.getBlockState(origin.offset(x, y, z)).isAir()) {
                        return false;
                    }
                }
            }
        }

        return !level.getBlockState(origin.below()).isAir();
    }

    private static BlockPos findDoorStandPos(
            net.minecraft.server.level.ServerLevel level,
            BlockPos origin,
            List<StructureTemplate.StructureBlockInfo> blocks
    ) {
        for (StructureTemplate.StructureBlockInfo info : blocks) {
            BlockState state = info.state();

            if (!(state.getBlock() instanceof DoorBlock)) {
                continue;
            }

            if (state.hasProperty(DoorBlock.HALF)
                    && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
                continue;
            }

            Direction facing = Direction.SOUTH;

            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            }

            BlockPos door = origin.offset(info.pos());

            // 门外 2 格作为施工站位，避免建筑师站在门洞或墙体位置。
            return door.relative(facing.getOpposite(), 2);
        }

        return origin.offset(0, 0, -3);
    }

    private static BlockState normalizeToWoodenHouse(BlockState originalState) {
        if (originalState.isAir()
                || originalState.is(Blocks.STRUCTURE_VOID)
                || originalState.is(Blocks.STRUCTURE_BLOCK)) {
            return null;
        }

        String blockName = BuiltInRegistries.BLOCK.getKey(originalState.getBlock()).getPath();

        // 门是双格方块，当前阶段仍然转成单格橡木栅栏门。
        // 上半部分跳过，避免重复建造。
        if (originalState.getBlock() instanceof DoorBlock) {
            if (originalState.hasProperty(DoorBlock.HALF)
                    && originalState.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
                return null;
            }

            return copyCommonProperties(originalState, Blocks.OAK_FENCE_GATE.defaultBlockState());
        }

        // 暂时跳过复杂装饰和容器。
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

        // 玻璃当前还没有熔炉工供应，先保留为橡木栅栏当木窗。
        if (blockName.contains("glass")
                || blockName.contains("pane")
                || blockName.contains("iron_bars")) {
            return Blocks.OAK_FENCE.defaultBlockState();
        }

        // =========================================================
        // 石材类：不再转橡木，改成圆石体系。
        // =========================================================

        if (isStoneLikeBlockName(blockName)) {
            if (blockName.endsWith("_stairs")) {
                return copyCommonProperties(
                        originalState,
                        Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                );
            }

            if (blockName.endsWith("_slab")) {
                return copyCommonProperties(
                        originalState,
                        Blocks.COBBLESTONE_SLAB.defaultBlockState()
                );
            }

            if (blockName.endsWith("_wall")) {
                return Blocks.COBBLESTONE_WALL.defaultBlockState();
            }

            return Blocks.COBBLESTONE.defaultBlockState();
        }

        // =========================================================
        // 木材类：仍然统一成橡木体系。
        // =========================================================

        if (blockName.endsWith("_stairs")) {
            return copyCommonProperties(originalState, Blocks.OAK_STAIRS.defaultBlockState());
        }

        if (blockName.endsWith("_slab")) {
            return copyCommonProperties(originalState, Blocks.OAK_SLAB.defaultBlockState());
        }

        if (blockName.endsWith("_trapdoor")) {
            return copyCommonProperties(originalState, Blocks.OAK_TRAPDOOR.defaultBlockState());
        }

        if (blockName.endsWith("_fence")) {
            return Blocks.OAK_FENCE.defaultBlockState();
        }

        if (blockName.endsWith("_fence_gate")) {
            return copyCommonProperties(originalState, Blocks.OAK_FENCE_GATE.defaultBlockState());
        }

        if (blockName.endsWith("_log") || blockName.endsWith("_wood")) {
            return copyCommonProperties(originalState, Blocks.OAK_LOG.defaultBlockState());
        }

        if (blockName.contains("stripped")) {
            return copyCommonProperties(originalState, Blocks.STRIPPED_OAK_LOG.defaultBlockState());
        }

        if (blockName.endsWith("_planks")) {
            return Blocks.OAK_PLANKS.defaultBlockState();
        }

        // 当前阶段跳过叶子、地毯、羊毛。
        if (blockName.contains("leaves")
                || blockName.contains("carpet")
                || blockName.contains("wool")) {
            return null;
        }

        if (blockName.startsWith("oak_")) {
            return originalState;
        }

        // 未知方块默认转成橡木木板，保证不会卡死建造流程。
        return Blocks.OAK_PLANKS.defaultBlockState();
    }

    private static boolean isStoneLikeBlockName(String blockName) {
        return blockName.contains("stone")
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
                || blockName.contains("clay");
    }

    private static BlockState copyCommonProperties(BlockState from, BlockState to) {
        if (from.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                && to.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            to = to.setValue(BlockStateProperties.HORIZONTAL_FACING, from.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }

        if (from.hasProperty(BlockStateProperties.HALF)
                && to.hasProperty(BlockStateProperties.HALF)) {
            to = to.setValue(BlockStateProperties.HALF, from.getValue(BlockStateProperties.HALF));
        }

        if (from.hasProperty(BlockStateProperties.STAIRS_SHAPE)
                && to.hasProperty(BlockStateProperties.STAIRS_SHAPE)) {
            to = to.setValue(BlockStateProperties.STAIRS_SHAPE, from.getValue(BlockStateProperties.STAIRS_SHAPE));
        }

        if (from.hasProperty(BlockStateProperties.SLAB_TYPE)
                && to.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            to = to.setValue(BlockStateProperties.SLAB_TYPE, from.getValue(BlockStateProperties.SLAB_TYPE));
        }

        if (from.hasProperty(BlockStateProperties.AXIS)
                && to.hasProperty(BlockStateProperties.AXIS)) {
            to = to.setValue(BlockStateProperties.AXIS, from.getValue(BlockStateProperties.AXIS));
        }

        if (from.hasProperty(BlockStateProperties.OPEN)
                && to.hasProperty(BlockStateProperties.OPEN)) {
            to = to.setValue(BlockStateProperties.OPEN, from.getValue(BlockStateProperties.OPEN));
        }

        return to;
    }

    private static boolean tryCraftFromOakLog(Villager villager, Item requiredItem) {
        int yield = getOakLogCraftYield(requiredItem);

        if (yield <= 0) {
            return false;
        }

        if (!SmartVillagerData.consumeOne(villager.getInventory(), "minecraft:oak_log")) {
            return false;
        }

        villager.getInventory().addItem(new ItemStack(requiredItem, yield));
        villager.getInventory().setChanged();

        SmartVillagerData.setStatus(villager, "加工木材", "用橡木原木合成建筑材料");

        return true;
    }

    private static boolean tryCraftFromCobblestone(Villager villager, Item requiredItem) {
        int yield = getCobblestoneCraftYield(requiredItem);

        if (yield <= 0) {
            return false;
        }

        // 先消耗圆石。
        if (!SmartVillagerData.consumeOne(villager.getInventory(), "minecraft:cobblestone")) {
            return false;
        }

        villager.getInventory().addItem(new ItemStack(requiredItem, yield));
        villager.getInventory().setChanged();

        SmartVillagerData.setStatus(villager, "加工石材", "用圆石加工建筑材料");

        return true;
    }

    private static int getCobblestoneCraftYield(Item requiredItem) {
        String itemName = BuiltInRegistries.ITEM.getKey(requiredItem).getPath();

        // 圆石本体。
        if (itemName.equals("cobblestone")) {
            return 1;
        }

        // 当前阶段的简化加工：
        // 真实配方不是 1:1，但这里是智能建筑师的“脑内加工”。
        // 后续如果要真实经济，可以改成按配方消耗多个圆石。
        if (itemName.equals("cobblestone_stairs")) {
            return 1;
        }

        if (itemName.equals("cobblestone_slab")) {
            return 2;
        }

        if (itemName.equals("cobblestone_wall")) {
            return 1;
        }

        // 如果你后面想让建筑师用圆石加工石砖，也可以在这里扩展。
        if (itemName.equals("stone_bricks")) {
            return 1;
        }

        if (itemName.equals("stone_brick_stairs")) {
            return 1;
        }

        if (itemName.equals("stone_brick_slab")) {
            return 2;
        }

        return 0;
    }

    private static int getOakLogCraftYield(Item requiredItem) {
        String itemName = BuiltInRegistries.ITEM.getKey(requiredItem).getPath();

        if (itemName.equals("oak_log")) return 1;
        if (itemName.equals("stripped_oak_log")) return 1;
        if (itemName.equals("oak_planks")) return 4;
        if (itemName.equals("oak_stairs")) return 4;
        if (itemName.equals("oak_slab")) return 8;
        if (itemName.equals("oak_fence")) return 3;
        if (itemName.equals("oak_fence_gate")) return 1;
        if (itemName.equals("oak_trapdoor")) return 2;
        if (itemName.equals("oak_button")) return 4;
        if (itemName.equals("oak_pressure_plate")) return 2;
        if (itemName.equals("oak_sign")) return 2;

        return 0;
    }

    private static boolean consumeOneItem(SimpleContainer inventory, Item item) {
        String itemName = BuiltInRegistries.ITEM.getKey(item).toString();
        return SmartVillagerData.consumeOne(inventory, itemName);
    }

    private static String getState(Villager villager) {
        return villager.getPersistentData().getString(KEY_STATE).orElse(STATE_FIND_SITE);
    }

    private static void setState(Villager villager, String state) {
        villager.getPersistentData().putString(KEY_STATE, state);
    }

    private static void savePos(Villager villager, String keyX, String keyY, String keyZ, BlockPos pos) {
        villager.getPersistentData().putInt(keyX, pos.getX());
        villager.getPersistentData().putInt(keyY, pos.getY());
        villager.getPersistentData().putInt(keyZ, pos.getZ());
    }

    private static BlockPos readPos(Villager villager, String keyX, String keyY, String keyZ) {
        if (!villager.getPersistentData().contains(keyX)) {
            return null;
        }

        return new BlockPos(
                villager.getPersistentData().getInt(keyX).orElse(0),
                villager.getPersistentData().getInt(keyY).orElse(0),
                villager.getPersistentData().getInt(keyZ).orElse(0)
        );
    }

    private static void clearPos(Villager villager, String keyX, String keyY, String keyZ) {
        villager.getPersistentData().remove(keyX);
        villager.getPersistentData().remove(keyY);
        villager.getPersistentData().remove(keyZ);
    }
}