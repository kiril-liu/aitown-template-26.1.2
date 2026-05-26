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
                SmartVillagerData.ensureIdentity(villager);

                if (!villager.getPersistentData().getBoolean("IsIdle").orElse(false)) {
                    SmartVillagerData.suppressVanillaMovement(villager);
                }
                if (villager.getPersistentData().getBoolean("IsIdle").orElse(false)) return;


                if (SmartVillagerData.shouldThink(villager, 10)) {
                    SmartVillagerData.pickupNearbyItems(
                            villager,
                            2.0D,
                            1.0D,
                            stack -> {
                                String itemName = net.minecraft.core.registries.BuiltInRegistries.ITEM
                                        .getKey(stack.getItem())
                                        .getPath();

                                return itemName.contains("_log")
                                        || itemName.contains("sapling")
                                        || itemName.equals("apple")
                                        || itemName.equals("stick");
                            }
                    );
                }

                if (SmartVillagerData.shouldThink(villager, 40)) {
                    net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) villager.level();
                    SimpleContainer inventory = villager.getInventory();

                    BlockPos homePos = getHomePos(villager);

                    // ==========================================
                    // 状态 A：原木够了，或者背包完全没空位了，强制回家存箱子！
                    // ==========================================
                    boolean handledDeposit = SmartVillagerData.handleDepositIfNeeded(
                            level,
                            villager,
                            "伐木工",
                            homePos,
                            16,
                            stack -> {
                                String itemName = net.minecraft.core.registries.BuiltInRegistries.ITEM
                                        .getKey(stack.getItem())
                                        .getPath();

                                return itemName.contains("_log")
                                        || itemName.contains("sapling")
                                        || itemName.equals("apple")
                                        || itemName.equals("stick");
                            }
                    );

                    if (handledDeposit) {
                        return;
                    }

                    BlockPos treeBase = findNearestRealTree(level, villager, 25);

                    if (treeBase != null) {
                        BlockPos treeAnchor = getTreeAnchor(treeBase);

                        SmartVillagerData.setDisplayStatus(villager, "§a", "伐木中", "伐木工");
                        SmartVillagerData.setTargetPlace(villager, treeAnchor, "chop_tree_anchor");

                        boolean arrived = SmartVillagerData.moveToTargetPlace(
                                villager,
                                SmartVillagerData.PLACE_CUT_TREE,
                                SmartVillagerData.SPEED_NORMAL
                        );

                        if (!arrived) {
                            return;
                        }

                        villager.getNavigation().stop();
                        SmartVillagerData.lookAtTargetPlace(villager);

                        chopWholeTreeAt(level, villager, treeBase);

                        return;
                    }

                    // 没树，尝试种树
                    if (tryPlantSapling(level, villager, inventory)) {
                        return;
                    }

                    // 没树也没树苗：在当前工作地点 idle
                    BlockPos idlePos = SmartVillagerData.hasTargetPlace(villager)
                            ? SmartVillagerData.getTargetPlace(villager)
                            : villager.blockPosition();

                    SmartVillagerData.setIdleAt(villager, "伐木工", idlePos);
                }
            }
        }
    }

    private static boolean isRealTreeAt(
            net.minecraft.server.level.ServerLevel level,
            BlockPos base
    ) {
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        int logCount = 0;
        boolean foundLeaves = false;

        // 从当前原木往上找连续原木
        for (int up = 0; up <= 12; up++) {
            mPos.set(base.getX(), base.getY() + up, base.getZ());

            BlockState state = level.getBlockState(mPos);

            if (state.is(net.minecraft.tags.BlockTags.LOGS)) {
                logCount++;
                continue;
            }

            // 原木断了以后，在附近找树叶
            for (int dx = -3; dx <= 3; dx++) {
                for (int dy = -2; dy <= 3; dy++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        BlockPos leafPos = mPos.offset(dx, dy, dz);
                        BlockState leafState = level.getBlockState(leafPos);

                        if (leafState.is(net.minecraft.tags.BlockTags.LEAVES)) {
                            foundLeaves = true;
                            break;
                        }
                    }

                    if (foundLeaves) break;
                }

                if (foundLeaves) break;
            }

            break;
        }

        return logCount >= 2 && foundLeaves;
    }

    private static boolean chopWholeTreeAt(
            net.minecraft.server.level.ServerLevel level,
            Villager villager,
            BlockPos base
    ) {
        boolean didWork = false;

        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        // 1. 先砍原木
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy <= 12; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    mPos.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);

                    BlockState state = level.getBlockState(mPos);

                    if (state.is(net.minecraft.tags.BlockTags.LOGS)) {
                        double dist = villager.distanceToSqr(
                                mPos.getX() + 0.5D,
                                mPos.getY() + 0.5D,
                                mPos.getZ() + 0.5D
                        );

                        if (dist <= SmartVillagerData.DISTANCE_CHOP) {
                            level.destroyBlock(mPos, true);
                            villager.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                            didWork = true;
                        }
                    }
                }
            }
        }

        // 2. 再清树叶
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = 0; dy <= 14; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    mPos.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);

                    BlockState state = level.getBlockState(mPos);

                    if (state.is(net.minecraft.tags.BlockTags.LEAVES)) {
                        double dist = villager.distanceToSqr(
                                mPos.getX() + 0.5D,
                                mPos.getY() + 0.5D,
                                mPos.getZ() + 0.5D
                        );

                        if (dist <= SmartVillagerData.DISTANCE_LEAF) {
                            level.destroyBlock(mPos, true);
                            didWork = true;
                        }
                    }
                }
            }
        }

        if (didWork) {
            villager.level().playSound(
                    null,
                    villager.blockPosition(),
                    SoundEvents.WOOD_BREAK,
                    SoundSource.NEUTRAL,
                    0.8F,
                    1.0F
            );
        }

        return didWork;
    }

    private static BlockPos findNearestRealTree(
            net.minecraft.server.level.ServerLevel level,
            Villager villager,
            int radius
    ) {
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        BlockPos bestTree = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 8; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    mPos.set(
                            villager.getBlockX() + dx,
                            villager.getBlockY() + dy,
                            villager.getBlockZ() + dz
                    );

                    BlockState state = level.getBlockState(mPos);

                    if (!state.is(net.minecraft.tags.BlockTags.LOGS)) {
                        continue;
                    }

                    if (!isRealTreeAt(level, mPos)) {
                        continue;
                    }

                    double dist = villager.distanceToSqr(
                            mPos.getX() + 0.5D,
                            mPos.getY(),
                            mPos.getZ() + 0.5D
                    );

                    if (dist < bestDist) {
                        bestDist = dist;
                        bestTree = mPos.immutable();
                    }
                }
            }
        }

        return bestTree;
    }
    private static BlockPos getTreeAnchor(BlockPos treeBase) {
        return treeBase.offset(2, 0, 2);
    }
    private static boolean hasNearbySaplingOrLog(
            net.minecraft.server.level.ServerLevel level,
            BlockPos pos,
            int radius
    ) {
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -1; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    mPos.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);

                    BlockState state = level.getBlockState(mPos);

                    String blockName = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(state.getBlock())
                            .getPath();

                    if (state.is(net.minecraft.tags.BlockTags.LOGS)
                            || blockName.contains("sapling")) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
    private static BlockPos getHomePos(Villager villager) {
        int x = villager.getPersistentData().getInt("HomeX").orElse(villager.getBlockX());
        int y = villager.getPersistentData().getInt("HomeY").orElse(villager.getBlockY());
        int z = villager.getPersistentData().getInt("HomeZ").orElse(villager.getBlockZ());

        return new BlockPos(x, y, z);
    }
    private static boolean tryPlantSapling(
            net.minecraft.server.level.ServerLevel level,
            Villager villager,
            SimpleContainer inventory
    ) {
        ItemStack saplingStack = ItemStack.EMPTY;
        int saplingSlot = -1;

        // 1. 先从伐木工背包里找树苗
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);

            if (stack.isEmpty()) {
                continue;
            }

            String itemName = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem())
                    .getPath();

            if (itemName.contains("sapling")
                    && stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                saplingStack = stack;
                saplingSlot = i;
                break;
            }
        }

        // 背包里没有树苗，就不处理种树
        if (saplingStack.isEmpty() || saplingSlot < 0) {
            return false;
        }

        // 2. 选择搜索中心：
        // 如果有 targetPlace，就围绕当前工作地点找；
        // 否则围绕村民当前位置找。
        BlockPos center = SmartVillagerData.hasTargetPlace(villager)
                ? SmartVillagerData.getTargetPlace(villager)
                : villager.blockPosition();

        BlockPos.MutableBlockPos plantPos = new BlockPos.MutableBlockPos();

        // 3. 搜索附近适合种树的位置
        // 半径可以稍微大一点，不要只在脚边找。
        int searchRadius = 8;

        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    plantPos.set(
                            center.getX() + dx,
                            center.getY() + dy,
                            center.getZ() + dz
                    );

                    BlockState ground = level.getBlockState(plantPos.below());
                    BlockState space = level.getBlockState(plantPos);

                    // 4. 地面必须适合种树
                    boolean validGround =
                            ground.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                                    || ground.is(net.minecraft.world.level.block.Blocks.DIRT)
                                    || ground.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)
                                    || ground.is(net.minecraft.world.level.block.Blocks.PODZOL);

                    if (!validGround) {
                        continue;
                    }

                    // 5. 树苗所在位置必须是空气
                    if (!space.isAir()) {
                        continue;
                    }

                    // 6. 不要在自己太近的位置种树，避免树长大后把伐木工卡住
                    double distToVillager = villager.distanceToSqr(
                            plantPos.getX() + 0.5D,
                            plantPos.getY(),
                            plantPos.getZ() + 0.5D
                    );

                    if (distToVillager < 16.0D) { // 4 格以内不种
                        continue;
                    }

                    // 7. 附近已经有树苗或原木，就不要再种
                    // 这样可以保证树之间大概隔开 5 格左右。
                    if (hasNearbySaplingOrLog(level, plantPos, 5)) {
                        continue;
                    }

                    // 8. 找到合适位置后，先把它设为目标点
                    SmartVillagerData.setDisplayStatus(villager, "§2", "种树", "伐木工");
                    SmartVillagerData.setTargetPlace(villager, plantPos.immutable(), "plant_sapling");

                    boolean arrived = SmartVillagerData.moveToTargetPlace(
                            villager,
                            SmartVillagerData.PLACE_PLANT_TREE,
                            SmartVillagerData.SPEED_NORMAL
                    );

                    // 还没走到种树位置，就先继续移动
                    if (!arrived) {
                        return true;
                    }

                    // 9. 已经靠近，开始种树
                    net.minecraft.world.item.BlockItem saplingItem =
                            (net.minecraft.world.item.BlockItem) saplingStack.getItem();

                    level.setBlockAndUpdate(
                            plantPos,
                            saplingItem.getBlock().defaultBlockState()
                    );

                    saplingStack.shrink(1);
                    inventory.setChanged();

                    villager.swing(net.minecraft.world.InteractionHand.MAIN_HAND);

                    villager.level().playSound(
                            null,
                            plantPos,
                            SoundEvents.GRASS_PLACE,
                            SoundSource.NEUTRAL,
                            1.0F,
                            1.0F
                    );

                    SmartVillagerData.clearTargetPlace(villager);

                    return true;
                }
            }
        }

        // 有树苗，但是附近没有找到合适位置
        return false;
    }


}