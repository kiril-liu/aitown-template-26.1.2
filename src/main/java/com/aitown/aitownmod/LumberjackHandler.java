package com.aitown.aitownmod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.List;

@EventBusSubscriber(modid = aitown.MODID)
public class LumberjackHandler {
    private static final String KEY_STATE = "LumberjackState";
    private static final String STATE_SEEK_TREE = "seek_tree";
    private static final String STATE_MOVE_TO_TREE = "move_to_tree";
    private static final String STATE_CHOP_TREE = "chop_tree";
    private static final String STATE_CLEAN_DROPS = "clean_drops";
    private static final String STATE_PLANT_SAPLING = "plant_sapling";
    private static final String STATE_FETCH_SAPLING = "fetch_sapling";
    private static final String STATE_DEPOSIT_ITEMS = "deposit_items";
    private static final String STATE_WAIT = "wait";

    private static final String KEY_TREE_X = "LumberjackTreeX";
    private static final String KEY_TREE_Y = "LumberjackTreeY";
    private static final String KEY_TREE_Z = "LumberjackTreeZ";

    private static final String KEY_WORK_X = "LumberjackWorkX";
    private static final String KEY_WORK_Y = "LumberjackWorkY";
    private static final String KEY_WORK_Z = "LumberjackWorkZ";

    private static final int TREE_SEARCH_RADIUS = 24;
    private static final int MAX_LOGS_PER_STEP = 1;
    private static final int MAX_LEAVES_PER_STEP = 1;
    private static final int DEPOSIT_THRESHOLD = 24;

    @SubscribeEvent
    public static void onVillagerTick(EntityTickEvent.Pre event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }

        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }

        if (!SmartVillagerData.isRole(villager, SmartVillagerData.ROLE_LUMBERJACK)) {
            return;
        }

        if (!(villager.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        SmartVillagerData.ensureIdentity(villager);
        SmartVillagerData.suppressVanillaMovement(villager);

        if (SmartVillagerData.shouldThink(villager, 10)) {
            pickupDrops(level, villager, villager.blockPosition(), 2.5D, 1.5D);
        }

        if (!SmartVillagerData.shouldThink(villager, 5)) {
            return;
        }

        String state = getState(villager);

        if (STATE_SEEK_TREE.equals(state)) {
            tickSeekTree(level, villager);
            return;
        }

        if (STATE_MOVE_TO_TREE.equals(state)) {
            tickMoveToTree(level, villager);
            return;
        }

        if (STATE_CHOP_TREE.equals(state)) {
            tickChopTree(level, villager);
            return;
        }

        if (STATE_CLEAN_DROPS.equals(state)) {
            tickCleanDrops(level, villager);
            return;
        }

        if (STATE_PLANT_SAPLING.equals(state)) {
            tickPlantSapling(level, villager);
            return;
        }

        if (STATE_FETCH_SAPLING.equals(state)) {
            tickFetchSapling(level, villager);
            return;
        }

        if (STATE_DEPOSIT_ITEMS.equals(state)) {
            tickDepositItems(level, villager);
            return;
        }

        if (STATE_WAIT.equals(state)) {
            tickWait(level, villager);
            return;
        }

        setState(villager, STATE_SEEK_TREE);
    }

    private static void tickSeekTree(net.minecraft.server.level.ServerLevel level, Villager villager) {
        SmartVillagerData.setStatus(villager, "寻找树木", "寻找附近成熟树");

        if (shouldDeposit(villager)) {
            setState(villager, STATE_DEPOSIT_ITEMS);
            return;
        }

        BlockPos treeBase = findNearestRealTree(level, villager, TREE_SEARCH_RADIUS);

        if (treeBase == null) {
            setState(villager, STATE_WAIT);
            return;
        }

        BlockPos workPos = treeBase.offset(2, 0, 2);

        savePos(villager, KEY_TREE_X, KEY_TREE_Y, KEY_TREE_Z, treeBase);
        savePos(villager, KEY_WORK_X, KEY_WORK_Y, KEY_WORK_Z, workPos);

        setState(villager, STATE_MOVE_TO_TREE);
    }

    private static void tickMoveToTree(net.minecraft.server.level.ServerLevel level, Villager villager) {
        BlockPos treeBase = readPos(villager, KEY_TREE_X, KEY_TREE_Y, KEY_TREE_Z);
        BlockPos workPos = readPos(villager, KEY_WORK_X, KEY_WORK_Y, KEY_WORK_Z);

        if (treeBase == null || workPos == null) {
            setState(villager, STATE_SEEK_TREE);
            return;
        }

        if (!isRealTreeAt(level, treeBase)) {
            setState(villager, STATE_SEEK_TREE);
            return;
        }

        SmartVillagerData.setStatus(villager, "走向树木", "前往伐木位置");
        SmartVillagerData.setTargetPlace(villager, workPos, "lumberjack_tree_work_pos");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_WORK,
                SmartVillagerData.SPEED_NORMAL
        );

        if (arrived) {
            setState(villager, STATE_CHOP_TREE);
        }
    }

    private static void tickChopTree(net.minecraft.server.level.ServerLevel level, Villager villager) {
        BlockPos treeBase = readPos(villager, KEY_TREE_X, KEY_TREE_Y, KEY_TREE_Z);

        if (treeBase == null) {
            setState(villager, STATE_SEEK_TREE);
            return;
        }

        SmartVillagerData.setStatus(villager, "伐木中", "每次砍伐一块树木方块");

        int broken = chopTreeStep(level, villager, treeBase);

        if (broken > 0) {
            villager.swing(InteractionHand.MAIN_HAND);

            level.playSound(
                    null,
                    villager.blockPosition(),
                    SoundEvents.WOOD_BREAK,
                    SoundSource.NEUTRAL,
                    0.8F,
                    1.0F
            );

            return;
        }

        setState(villager, STATE_CLEAN_DROPS);
    }

    private static void tickCleanDrops(net.minecraft.server.level.ServerLevel level, Villager villager) {
        BlockPos treeBase = readPos(villager, KEY_TREE_X, KEY_TREE_Y, KEY_TREE_Z);

        if (treeBase == null) {
            setState(villager, STATE_PLANT_SAPLING);
            return;
        }

        SmartVillagerData.setStatus(villager, "清理掉落物", "收集原木、苹果、木棍、树苗");

        pickupDrops(level, villager, treeBase, 7.0D, 4.0D);

        setState(villager, STATE_PLANT_SAPLING);
    }

    private static void tickPlantSapling(net.minecraft.server.level.ServerLevel level, Villager villager) {
        BlockPos treeBase = readPos(villager, KEY_TREE_X, KEY_TREE_Y, KEY_TREE_Z);

        if (treeBase == null) {
            setState(villager, STATE_DEPOSIT_ITEMS);
            return;
        }

        SmartVillagerData.setStatus(villager, "补种树苗", "在砍树位置补种");

        if (!hasSapling(villager.getInventory())) {
            setState(villager, STATE_FETCH_SAPLING);
            return;
        }

        BlockPos planted = plantSaplingNear(level, villager, treeBase);

        if (planted != null) {
            savePos(villager, KEY_WORK_X, KEY_WORK_Y, KEY_WORK_Z, planted.offset(2, 0, 0));
        }

        setState(villager, STATE_DEPOSIT_ITEMS);
    }

    private static void tickFetchSapling(net.minecraft.server.level.ServerLevel level, Villager villager) {
        BlockPos warehouse = SmartVillagerData.getWarehouseCenter(villager);

        SmartVillagerData.setStatus(villager, "仓库取树苗", "缺树苗，去仓库拿");

        SmartVillagerData.setTargetPlace(villager, warehouse, "lumberjack_fetch_sapling");

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
                        new SmartVillagerData.ItemRequest("minecraft:oak_sapling", 8),
                        new SmartVillagerData.ItemRequest("minecraft:spruce_sapling", 8),
                        new SmartVillagerData.ItemRequest("minecraft:birch_sapling", 8)
                ),
                SmartVillagerData.WAREHOUSE_RADIUS
        );

        if (moved > 0) {
            setState(villager, STATE_PLANT_SAPLING);
        } else {
            SmartVillagerData.setStatus(villager, "等待树苗", "仓库没有树苗");
        }
    }

    private static void tickDepositItems(net.minecraft.server.level.ServerLevel level, Villager villager) {
        if (!hasDepositItems(villager)) {
            setState(villager, STATE_WAIT);
            return;
        }

        BlockPos warehouse = SmartVillagerData.getWarehouseCenter(villager);

        SmartVillagerData.setStatus(villager, "存入仓库", "把伐木产物放入仓库");

        SmartVillagerData.setTargetPlace(villager, warehouse, "lumberjack_deposit");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_STORAGE,
                SmartVillagerData.SPEED_NORMAL
        );

        if (!arrived) {
            return;
        }

        SmartVillagerData.depositItemsToWarehouse(
                level,
                villager,
                warehouse,
                List.of(
                        new SmartVillagerData.ItemRequest("minecraft:oak_log", 999),
                        new SmartVillagerData.ItemRequest("minecraft:oak_wood", 999),
                        new SmartVillagerData.ItemRequest("minecraft:apple", 999),
                        new SmartVillagerData.ItemRequest("minecraft:stick", 999),
                        new SmartVillagerData.ItemRequest("minecraft:oak_sapling", 999),
                        new SmartVillagerData.ItemRequest("minecraft:spruce_sapling", 999),
                        new SmartVillagerData.ItemRequest("minecraft:birch_sapling", 999)
                ),
                SmartVillagerData.WAREHOUSE_RADIUS
        );

        setState(villager, STATE_WAIT);
    }

    private static void tickWait(net.minecraft.server.level.ServerLevel level, Villager villager) {
        SmartVillagerData.setStatus(villager, "等待树长大", "等待树苗成长或寻找新树");

        BlockPos workPos = readPos(villager, KEY_WORK_X, KEY_WORK_Y, KEY_WORK_Z);

        if (workPos != null) {
            SmartVillagerData.setTargetPlace(villager, workPos, "lumberjack_wait");
            SmartVillagerData.moveToTargetPlace(
                    villager,
                    SmartVillagerData.PLACE_WORK,
                    SmartVillagerData.SPEED_SLOW
            );
        }

        if (!SmartVillagerData.shouldThink(villager, 40)) {
            return;
        }

        BlockPos tree = findNearestRealTree(level, villager, TREE_SEARCH_RADIUS);

        if (tree != null) {
            savePos(villager, KEY_TREE_X, KEY_TREE_Y, KEY_TREE_Z, tree);
            savePos(villager, KEY_WORK_X, KEY_WORK_Y, KEY_WORK_Z, tree.offset(2, 0, 2));
            setState(villager, STATE_MOVE_TO_TREE);
        }
    }

    private static int chopTreeStep(net.minecraft.server.level.ServerLevel level, Villager villager, BlockPos treeBase) {
        // 第一版改成更真实的节奏：每次状态机只破坏一个方块。
        // 优先砍原木；如果已经没有原木，再清理一块树叶。
        int brokenLogs = breakBlocks(level, villager, treeBase, true, MAX_LOGS_PER_STEP);

        if (brokenLogs > 0) {
            return brokenLogs;
        }

        return breakBlocks(level, villager, treeBase, false, MAX_LEAVES_PER_STEP);
    }

    private static int breakBlocks(
            net.minecraft.server.level.ServerLevel level,
            Villager villager,
            BlockPos treeBase,
            boolean logsOnly,
            int limit
    ) {
        int broken = 0;
        int radius = logsOnly ? 2 : 5;
        int height = logsOnly ? 12 : 14;

        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        for (int dy = 0; dy <= height; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    mPos.set(treeBase.getX() + dx, treeBase.getY() + dy, treeBase.getZ() + dz);

                    BlockState state = level.getBlockState(mPos);

                    boolean shouldBreak = logsOnly
                            ? state.is(BlockTags.LOGS)
                            : state.is(BlockTags.LEAVES);

                    if (!shouldBreak) {
                        continue;
                    }

                    level.destroyBlock(mPos.immutable(), true);
                    broken++;

                    if (broken >= limit) {
                        return broken;
                    }
                }
            }
        }

        return broken;
    }

    private static BlockPos findNearestRealTree(
            net.minecraft.server.level.ServerLevel level,
            Villager villager,
            int radius
    ) {
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        BlockPos bestTree = null;
        double bestDistance = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 8; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    mPos.set(villager.getBlockX() + dx, villager.getBlockY() + dy, villager.getBlockZ() + dz);

                    if (!level.getBlockState(mPos).is(BlockTags.LOGS)) {
                        continue;
                    }

                    BlockPos base = findLowestLog(level, mPos.immutable());

                    if (!isRealTreeAt(level, base)) {
                        continue;
                    }

                    double distance = villager.distanceToSqr(base.getX() + 0.5D, base.getY(), base.getZ() + 0.5D);

                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestTree = base;
                    }
                }
            }
        }

        return bestTree;
    }

    private static BlockPos findLowestLog(net.minecraft.server.level.ServerLevel level, BlockPos start) {
        BlockPos current = start;

        for (int i = 0; i < 12; i++) {
            BlockPos below = current.below();

            if (!level.getBlockState(below).is(BlockTags.LOGS)) {
                break;
            }

            current = below;
        }

        return current;
    }

    private static boolean isRealTreeAt(net.minecraft.server.level.ServerLevel level, BlockPos base) {
        int logCount = 0;
        boolean foundLeaves = false;

        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        for (int up = 0; up <= 12; up++) {
            mPos.set(base.getX(), base.getY() + up, base.getZ());

            BlockState state = level.getBlockState(mPos);

            if (state.is(BlockTags.LOGS)) {
                logCount++;
                continue;
            }

            for (int dx = -4; dx <= 4; dx++) {
                for (int dy = -2; dy <= 4; dy++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        if (level.getBlockState(mPos.offset(dx, dy, dz)).is(BlockTags.LEAVES)) {
                            foundLeaves = true;
                            break;
                        }
                    }

                    if (foundLeaves) {
                        break;
                    }
                }

                if (foundLeaves) {
                    break;
                }
            }

            break;
        }

        return logCount >= 2 && foundLeaves;
    }

    private static boolean pickupDrops(
            net.minecraft.server.level.ServerLevel level,
            Villager villager,
            BlockPos center,
            double horizontalRadius,
            double verticalRadius
    ) {
        boolean picked = false;

        AABB box = new AABB(center).inflate(horizontalRadius, verticalRadius, horizontalRadius);
        SimpleContainer inventory = villager.getInventory();

        for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, box)) {
            ItemStack stack = itemEntity.getItem();

            if (!isLumberjackProduct(stack)) {
                continue;
            }

            ItemStack moving = stack.copy();
            ItemStack remaining = inventory.addItem(moving);
            int moved = stack.getCount() - remaining.getCount();

            if (moved <= 0) {
                continue;
            }

            stack.shrink(moved);

            if (stack.isEmpty()) {
                itemEntity.discard();
            } else {
                itemEntity.setItem(stack);
            }

            inventory.setChanged();
            picked = true;
        }

        return picked;
    }

    private static BlockPos plantSaplingNear(
            net.minecraft.server.level.ServerLevel level,
            Villager villager,
            BlockPos preferredPos
    ) {
        SimpleContainer inventory = villager.getInventory();

        int slot = findSaplingSlot(inventory);

        if (slot < 0) {
            return null;
        }

        ItemStack stack = inventory.getItem(slot);

        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }

        BlockState saplingState = blockItem.getBlock().defaultBlockState();

        if (level.getBlockState(preferredPos).isAir() && saplingState.canSurvive(level, preferredPos)) {
            level.setBlockAndUpdate(preferredPos, saplingState);
            stack.shrink(1);
            inventory.setChanged();
            villager.swing(InteractionHand.MAIN_HAND);
            return preferredPos;
        }

        return null;
    }

    private static int findSaplingSlot(SimpleContainer inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);

            if (!stack.isEmpty() && isSapling(stack)) {
                return i;
            }
        }

        return -1;
    }

    private static boolean hasSapling(SimpleContainer inventory) {
        return findSaplingSlot(inventory) >= 0;
    }

    private static boolean shouldDeposit(Villager villager) {
        return countLumberjackProducts(villager.getInventory()) >= DEPOSIT_THRESHOLD;
    }

    private static boolean hasDepositItems(Villager villager) {
        return countLumberjackProducts(villager.getInventory()) > 0;
    }

    private static int countLumberjackProducts(SimpleContainer inventory) {
        int count = 0;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);

            if (!stack.isEmpty() && isLumberjackProduct(stack)) {
                count += stack.getCount();
            }
        }

        return count;
    }

    private static boolean isSapling(ItemStack stack) {
        return SmartVillagerData.itemId(stack).contains("sapling");
    }

    private static boolean isLumberjackProduct(ItemStack stack) {
        String itemName = SmartVillagerData.itemId(stack);

        return itemName.contains("_log")
                || itemName.contains("_wood")
                || itemName.contains("sapling")
                || itemName.equals("minecraft:apple")
                || itemName.equals("minecraft:stick");
    }

    private static String getState(Villager villager) {
        return villager.getPersistentData().getString(KEY_STATE).orElse(STATE_SEEK_TREE);
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
}