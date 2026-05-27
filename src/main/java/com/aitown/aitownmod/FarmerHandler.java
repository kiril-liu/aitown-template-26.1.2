package com.aitown.aitownmod;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.List;

/**
 * 农田工 v1。
 *
 * 核心设计：
 * 1. 农民围绕 SmartVillagerData 的 MemoryWork 工作点行动。
 * 2. 如果没有工作点，第一次 tick 会把当前站位设为农田中心。
 * 3. 农田半径固定，且最多维护 FARM_MAX_PLOTS 块农田，避免无限扩张。
 * 4. 优先维护已有农田：捡掉落物、补种、收割、存小麦。
 * 5. 缺种子时先去仓库拿；仓库也没有时，再除草找种子。
 * 6. 小麦全部存仓库；种子默认留在身上，用于继续补种。
 */
@EventBusSubscriber(modid = aitown.MODID)
public class FarmerHandler {
    private static final String KEY_STATE = "FarmerState";

    private static final String STATE_PATROL_FIELD = "patrol_field";
    private static final String STATE_FETCH_SEEDS = "fetch_seeds";
    private static final String STATE_CLEAR_GRASS = "clear_grass";
    private static final String STATE_TILL_SOIL = "till_soil";
    private static final String STATE_PLANT = "plant";
    private static final String STATE_HARVEST = "harvest";
    private static final String STATE_PICKUP_DROPS = "pickup_drops";
    private static final String STATE_DEPOSIT_WHEAT = "deposit_wheat";
    private static final String STATE_WAIT = "wait";

    private static final String KEY_TARGET_X = "FarmerTargetX";
    private static final String KEY_TARGET_Y = "FarmerTargetY";
    private static final String KEY_TARGET_Z = "FarmerTargetZ";
    private static final String KEY_WHEAT_SINCE_DIARY = "FarmerWheatSinceDiary";
    private static final String KEY_NO_SEED_WAREHOUSE_COOLDOWN = "FarmerNoSeedWarehouseCooldown";

    private static final int FARM_RADIUS = 4;
    private static final int FARM_MAX_PLOTS = 16;
    private static final int TARGET_SEEDS = 8;
    private static final int WHEAT_DIARY_STEP = 30;
    private static final int NO_SEED_WAREHOUSE_COOLDOWN_TICKS = 600;

    @SubscribeEvent
    public static void onVillagerTick(EntityTickEvent.Pre event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }

        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }

        if (!SmartVillagerData.isRole(villager, SmartVillagerData.ROLE_FARMER)) {
            return;
        }

        if (!(villager.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        SmartVillagerData.ensureIdentity(villager);
        SmartVillagerData.suppressVanillaMovement(villager);
        ensureFieldCenter(villager);

        SmartVillagerData.tickHunger(villager);
        if (SmartVillagerData.tryHandleHunger(level, villager)) {
            return;
        }

        // 路过掉落物时顺手捡，避免小麦和种子消失。
        if (SmartVillagerData.shouldThink(villager, 10)) {
            pickupFarmDrops(level, villager);
        }

        if (!SmartVillagerData.shouldThink(villager, 5)) {
            return;
        }

        String state = getState(villager);

        if (STATE_PATROL_FIELD.equals(state)) {
            tickPatrolField(level, villager);
            return;
        }

        if (STATE_FETCH_SEEDS.equals(state)) {
            tickFetchSeeds(level, villager);
            return;
        }

        if (STATE_CLEAR_GRASS.equals(state)) {
            tickClearGrass(level, villager);
            return;
        }

        if (STATE_TILL_SOIL.equals(state)) {
            tickTillSoil(level, villager);
            return;
        }

        if (STATE_PLANT.equals(state)) {
            tickPlant(level, villager);
            return;
        }

        if (STATE_HARVEST.equals(state)) {
            tickHarvest(level, villager);
            return;
        }

        if (STATE_PICKUP_DROPS.equals(state)) {
            tickPickupDrops(level, villager);
            return;
        }

        if (STATE_DEPOSIT_WHEAT.equals(state)) {
            tickDepositWheat(level, villager);
            return;
        }

        if (STATE_WAIT.equals(state)) {
            tickWait(level, villager);
            return;
        }

        setState(villager, STATE_PATROL_FIELD);
    }

    /**
     * 农民的任务选择器。
     *
     * 这个状态不是单纯巡逻，而是按优先级选择下一个任务。
     */
    private static void tickPatrolField(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        BlockPos fieldCenter = SmartVillagerData.getMemoryWorkOrCurrent(villager);
        SmartVillagerData.setStatus(villager, "巡视农田", "围绕工作点维护小麦田");

        if (hasFarmDropsNearby(level, villager)) {
            setState(villager, STATE_PICKUP_DROPS);
            return;
        }

        // 优先收割成熟小麦。
        // 成熟小麦会掉落小麦和种子，通常能自然解决“缺种子”的问题。
        BlockPos matureWheat = findMatureWheat(level, fieldCenter);
        if (matureWheat != null) {
            saveTarget(villager, matureWheat);
            setState(villager, STATE_HARVEST);
            return;
        }

        BlockPos emptyFarmland = findEmptyFarmland(level, fieldCenter);
        if (emptyFarmland != null) {
            saveTarget(villager, emptyFarmland);
            if (hasSeeds(villager)) {
                setState(villager, STATE_PLANT);
            } else if (isSeedWarehouseOnCooldown(villager)) {
                // 仓库刚刚确认过没有种子，就不要立刻来回跑仓库。
                // 先留在农田附近除草找种子。
                setState(villager, STATE_CLEAR_GRASS);
            } else {
                setState(villager, STATE_FETCH_SEEDS);
            }
            return;
        }

        if (hasWheat(villager)) {
            setState(villager, STATE_DEPOSIT_WHEAT);
            return;
        }

        int plotCount = countFarmPlots(level, fieldCenter);
        if (plotCount < FARM_MAX_PLOTS) {
            BlockPos soil = findTillableSoil(level, fieldCenter);
            if (soil != null) {
                if (hasSeeds(villager)) {
                    saveTarget(villager, soil);
                    setState(villager, STATE_TILL_SOIL);
                } else if (isSeedWarehouseOnCooldown(villager)) {
                    setState(villager, STATE_CLEAR_GRASS);
                } else {
                    setState(villager, STATE_FETCH_SEEDS);
                }
                return;
            }
        }

        setState(villager, STATE_WAIT);
    }

    private static void tickFetchSeeds(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        if (hasSeeds(villager)) {
            setState(villager, STATE_PATROL_FIELD);
            return;
        }

        BlockPos warehouse = SmartVillagerData.getWarehouseCenter(villager);

        // 如果身上已经有小麦，去仓库拿种子前顺手把小麦存掉。
        SmartVillagerData.setStatus(villager, "仓库取种", "缺少小麦种子，先去仓库补给");
        SmartVillagerData.setTargetPlace(villager, warehouse, "farmer_fetch_seeds");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_STORAGE,
                SmartVillagerData.SPEED_NORMAL
        );

        if (!arrived) {
            return;
        }

        if (hasWheat(villager)) {
            depositWheatOnly(level, villager, warehouse);
        }

        int moved = SmartVillagerData.takeItemsFromWarehouse(
                level,
                villager,
                warehouse,
                List.of(new SmartVillagerData.ItemRequest("minecraft:wheat_seeds", TARGET_SEEDS)),
                SmartVillagerData.WAREHOUSE_RADIUS
        );

        if (moved > 0 || hasSeeds(villager)) {
            clearSeedWarehouseCooldown(villager);
            setState(villager, STATE_PATROL_FIELD);
        } else {
            // 仓库也没有种子，进入一段冷却时间。
            // 冷却期间农民不会反复跑仓库，而是在农田附近除草找种子。
            markSeedWarehouseEmpty(villager);
            setState(villager, STATE_CLEAR_GRASS);
        }
    }

    private static void tickClearGrass(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        if (hasSeeds(villager)) {
            setState(villager, STATE_PATROL_FIELD);
            return;
        }

        BlockPos fieldCenter = SmartVillagerData.getMemoryWorkOrCurrent(villager);
        BlockPos grass = findGrassToBreak(level, fieldCenter);

        if (grass == null) {
            SmartVillagerData.setStatus(villager, "等待种子", "仓库和附近草丛都没有种子来源");
            setState(villager, STATE_WAIT);
            return;
        }

        SmartVillagerData.setStatus(villager, "除草找种", "仓库没有种子，破坏草丛寻找种子");
        SmartVillagerData.setTargetPlace(villager, grass, "farmer_clear_grass");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_WORK,
                SmartVillagerData.SPEED_NORMAL
        );

        if (!arrived) {
            return;
        }

        level.destroyBlock(grass, true);
        villager.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, grass, SoundEvents.GRASS_BREAK, SoundSource.NEUTRAL, 0.7F, 1.1F);
        setState(villager, STATE_PICKUP_DROPS);
    }

    private static void tickTillSoil(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        BlockPos soil = readTarget(villager);
        BlockPos fieldCenter = SmartVillagerData.getMemoryWorkOrCurrent(villager);

        if (soil == null || !isInsideField(fieldCenter, soil)) {
            setState(villager, STATE_PATROL_FIELD);
            return;
        }

        if (countFarmPlots(level, fieldCenter) >= FARM_MAX_PLOTS) {
            setState(villager, STATE_PATROL_FIELD);
            return;
        }

        if (!isTillableSoil(level, soil)) {
            setState(villager, STATE_PATROL_FIELD);
            return;
        }

        SmartVillagerData.setStatus(villager, "开垦农田", "把土方块整理成耕地");
        SmartVillagerData.setTargetPlace(villager, soil, "farmer_till_soil");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_WORK,
                SmartVillagerData.SPEED_NORMAL
        );

        if (!arrived) {
            return;
        }

        level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
        villager.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, soil, SoundEvents.HOE_TILL, SoundSource.NEUTRAL, 0.8F, 1.0F);

        saveTarget(villager, soil);
        if (hasSeeds(villager)) {
            setState(villager, STATE_PLANT);
        } else {
            setState(villager, STATE_FETCH_SEEDS);
        }
    }

    private static void tickPlant(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        BlockPos farmland = readTarget(villager);
        BlockPos fieldCenter = SmartVillagerData.getMemoryWorkOrCurrent(villager);

        if (farmland == null || !isInsideField(fieldCenter, farmland)) {
            setState(villager, STATE_PATROL_FIELD);
            return;
        }

        if (!isEmptyFarmland(level, farmland)) {
            setState(villager, STATE_PATROL_FIELD);
            return;
        }

        if (!hasSeeds(villager)) {
            setState(villager, STATE_FETCH_SEEDS);
            return;
        }

        SmartVillagerData.setStatus(villager, "播种小麦", "把小麦种子种回空耕地");
        SmartVillagerData.setTargetPlace(villager, farmland, "farmer_plant_wheat");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_WORK,
                SmartVillagerData.SPEED_NORMAL
        );

        if (!arrived) {
            return;
        }

        if (SmartVillagerData.consumeOne(villager.getInventory(), "minecraft:wheat_seeds")) {
            level.setBlockAndUpdate(farmland.above(), Blocks.WHEAT.defaultBlockState());
            villager.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, farmland.above(), SoundEvents.CROP_PLANTED, SoundSource.NEUTRAL, 0.7F, 1.0F);
        }

        clearTarget(villager);
        setState(villager, STATE_PATROL_FIELD);
    }

    private static void tickHarvest(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        BlockPos wheat = readTarget(villager);
        BlockPos fieldCenter = SmartVillagerData.getMemoryWorkOrCurrent(villager);

        if (wheat == null || !isInsideField(fieldCenter, wheat)) {
            setState(villager, STATE_PATROL_FIELD);
            return;
        }

        if (!isMatureWheat(level, wheat)) {
            setState(villager, STATE_PATROL_FIELD);
            return;
        }

        SmartVillagerData.setStatus(villager, "收割小麦", "收割成熟小麦，之后会补种");
        SmartVillagerData.setTargetPlace(villager, wheat, "farmer_harvest_wheat");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_WORK,
                SmartVillagerData.SPEED_NORMAL
        );

        if (!arrived) {
            return;
        }

        level.destroyBlock(wheat, true);
        villager.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, wheat, SoundEvents.CROP_BREAK, SoundSource.NEUTRAL, 0.8F, 1.0F);
        saveTarget(villager, wheat.below());
        setState(villager, STATE_PICKUP_DROPS);
    }

    private static void tickPickupDrops(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        SmartVillagerData.setStatus(villager, "捡拾作物", "收集小麦和小麦种子");

        boolean cleaned = SmartVillagerData.moveToAndPickupNearestItem(
                level,
                villager,
                7.0D,
                2.0D,
                FarmerHandler::isFarmProduct,
                "farmer_pickup_drops"
        );

        if (!cleaned) {
            return;
        }

        // 收割后优先补种刚刚空出来的耕地。
        BlockPos farmland = readTarget(villager);
        if (farmland != null && isEmptyFarmland(level, farmland)) {
            if (hasSeeds(villager)) {
                setState(villager, STATE_PLANT);
            } else {
                setState(villager, STATE_FETCH_SEEDS);
            }
            return;
        }

        if (hasWheat(villager)) {
            setState(villager, STATE_DEPOSIT_WHEAT);
        } else {
            setState(villager, STATE_PATROL_FIELD);
        }
    }

    private static void tickDepositWheat(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        if (!hasWheat(villager)) {
            setState(villager, STATE_PATROL_FIELD);
            return;
        }

        BlockPos warehouse = SmartVillagerData.getWarehouseCenter(villager);

        SmartVillagerData.setStatus(villager, "存入小麦", "把收获的小麦送回仓库，种子继续留作补种");
        SmartVillagerData.setTargetPlace(villager, warehouse, "farmer_deposit_wheat");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_STORAGE,
                SmartVillagerData.SPEED_NORMAL
        );

        if (!arrived) {
            return;
        }

        int wheatBefore = SmartVillagerData.countItems(villager.getInventory(), "minecraft:wheat");
        int moved = depositWheatOnly(level, villager, warehouse);

        if (moved > 0) {
            addWheatProgress(villager, Math.min(wheatBefore, moved));
        }

        setState(villager, STATE_PATROL_FIELD);
    }

    private static void tickWait(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        BlockPos fieldCenter = SmartVillagerData.getMemoryWorkOrCurrent(villager);

        SmartVillagerData.setStatus(villager, "等待成熟", "在农田旁巡视，等待小麦成熟");
        SmartVillagerData.setTargetPlace(villager, fieldCenter, "farmer_wait_field");
        SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_WORK,
                SmartVillagerData.SPEED_SLOW
        );

        if (!SmartVillagerData.shouldThink(villager, 40)) {
            return;
        }

        setState(villager, STATE_PATROL_FIELD);
    }

    private static void ensureFieldCenter(Villager villager) {
        if (!SmartVillagerData.hasMemoryWork(villager)) {
            BlockPos pos = villager.blockPosition();
            SmartVillagerData.setMemoryWork(villager, pos);
            SmartVillagerData.addDiary(villager, "我把这里记作自己的农田中心。位置是 "
                    + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "。");
        }
    }

    private static int depositWheatOnly(
            net.minecraft.server.level.ServerLevel level,
            Villager villager,
            BlockPos warehouse
    ) {
        return SmartVillagerData.depositItemsToWarehouse(
                level,
                villager,
                warehouse,
                List.of(new SmartVillagerData.ItemRequest("minecraft:wheat", 999)),
                SmartVillagerData.WAREHOUSE_RADIUS
        );
    }

    private static void pickupFarmDrops(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        SmartVillagerData.pickupNearbyItems(
                level,
                villager,
                villager.blockPosition(),
                2.5D,
                1.5D,
                FarmerHandler::isFarmProduct
        );
    }

    private static boolean hasFarmDropsNearby(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        return SmartVillagerData.findNearestDroppedItem(
                level,
                villager,
                5.0D,
                FarmerHandler::isFarmProduct
        ) != null;
    }

    private static boolean isFarmProduct(ItemStack stack) {
        String itemName = SmartVillagerData.itemId(stack);
        return itemName.equals("minecraft:wheat") || itemName.equals("minecraft:wheat_seeds");
    }

    private static boolean hasSeeds(Villager villager) {
        return SmartVillagerData.hasItem(villager.getInventory(), "minecraft:wheat_seeds");
    }

    private static boolean hasWheat(Villager villager) {
        return SmartVillagerData.hasItem(villager.getInventory(), "minecraft:wheat");
    }

    private static boolean isSeedWarehouseOnCooldown(Villager villager) {
        int untilTick = villager.getPersistentData()
                .getInt(KEY_NO_SEED_WAREHOUSE_COOLDOWN)
                .orElse(0);
        return villager.tickCount < untilTick;
    }

    private static void markSeedWarehouseEmpty(Villager villager) {
        villager.getPersistentData().putInt(
                KEY_NO_SEED_WAREHOUSE_COOLDOWN,
                villager.tickCount + NO_SEED_WAREHOUSE_COOLDOWN_TICKS
        );
    }

    private static void clearSeedWarehouseCooldown(Villager villager) {
        villager.getPersistentData().remove(KEY_NO_SEED_WAREHOUSE_COOLDOWN);
    }

    private static BlockPos findMatureWheat(
            net.minecraft.server.level.ServerLevel level,
            BlockPos center
    ) {
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int dx = -FARM_RADIUS; dx <= FARM_RADIUS; dx++) {
            for (int dz = -FARM_RADIUS; dz <= FARM_RADIUS; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    mPos.set(center.getX() + dx, center.getY() + dy + 1, center.getZ() + dz);
                    BlockPos candidate = mPos.immutable();

                    if (!isMatureWheat(level, candidate)) {
                        continue;
                    }

                    double distance = center.distSqr(candidate);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
        }

        return best;
    }

    private static boolean isMatureWheat(
            net.minecraft.server.level.ServerLevel level,
            BlockPos wheatPos
    ) {
        BlockState state = level.getBlockState(wheatPos);
        return state.is(Blocks.WHEAT)
                && state.hasProperty(CropBlock.AGE)
                && state.getValue(CropBlock.AGE) >= CropBlock.MAX_AGE;
    }

    private static BlockPos findEmptyFarmland(
            net.minecraft.server.level.ServerLevel level,
            BlockPos center
    ) {
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        for (int dx = -FARM_RADIUS; dx <= FARM_RADIUS; dx++) {
            for (int dz = -FARM_RADIUS; dz <= FARM_RADIUS; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    mPos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockPos candidate = mPos.immutable();

                    if (isEmptyFarmland(level, candidate)) {
                        return candidate;
                    }
                }
            }
        }

        return null;
    }

    private static boolean isEmptyFarmland(
            net.minecraft.server.level.ServerLevel level,
            BlockPos farmland
    ) {
        return level.getBlockState(farmland).is(Blocks.FARMLAND)
                && level.getBlockState(farmland.above()).isAir();
    }

    private static BlockPos findTillableSoil(
            net.minecraft.server.level.ServerLevel level,
            BlockPos center
    ) {
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        for (int dx = -FARM_RADIUS; dx <= FARM_RADIUS; dx++) {
            for (int dz = -FARM_RADIUS; dz <= FARM_RADIUS; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    mPos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockPos candidate = mPos.immutable();

                    if (isTillableSoil(level, candidate)) {
                        return candidate;
                    }
                }
            }
        }

        return null;
    }

    private static boolean isTillableSoil(
            net.minecraft.server.level.ServerLevel level,
            BlockPos pos
    ) {
        BlockState state = level.getBlockState(pos);
        return (state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK))
                && level.getBlockState(pos.above()).isAir();
    }

    private static BlockPos findGrassToBreak(
            net.minecraft.server.level.ServerLevel level,
            BlockPos center
    ) {
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        for (int dx = -FARM_RADIUS; dx <= FARM_RADIUS; dx++) {
            for (int dz = -FARM_RADIUS; dz <= FARM_RADIUS; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    mPos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockPos candidate = mPos.immutable();

                    if (isBreakableGrass(level.getBlockState(candidate))) {
                        return candidate;
                    }
                }
            }
        }

        return null;
    }

    private static boolean isBreakableGrass(BlockState state) {
        return state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN);
    }

    private static int countFarmPlots(
            net.minecraft.server.level.ServerLevel level,
            BlockPos center
    ) {
        int count = 0;
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        for (int dx = -FARM_RADIUS; dx <= FARM_RADIUS; dx++) {
            for (int dz = -FARM_RADIUS; dz <= FARM_RADIUS; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    mPos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = level.getBlockState(mPos);

                    if (state.is(Blocks.FARMLAND) || state.is(Blocks.WHEAT)) {
                        count++;
                    }
                }
            }
        }

        return count;
    }

    private static boolean isInsideField(BlockPos center, BlockPos pos) {
        return Math.abs(pos.getX() - center.getX()) <= FARM_RADIUS
                && Math.abs(pos.getZ() - center.getZ()) <= FARM_RADIUS
                && Math.abs(pos.getY() - center.getY()) <= 2;
    }

    private static void addWheatProgress(Villager villager, int amount) {
        if (amount <= 0) {
            return;
        }

        int value = villager.getPersistentData().getInt(KEY_WHEAT_SINCE_DIARY).orElse(0) + amount;

        if (value >= WHEAT_DIARY_STEP) {
            SmartVillagerData.addDiary(villager, "我已经累计收获并上交了 30 个小麦。");
            value = 0;
        }

        villager.getPersistentData().putInt(KEY_WHEAT_SINCE_DIARY, value);
    }

    private static void saveTarget(Villager villager, BlockPos pos) {
        villager.getPersistentData().putInt(KEY_TARGET_X, pos.getX());
        villager.getPersistentData().putInt(KEY_TARGET_Y, pos.getY());
        villager.getPersistentData().putInt(KEY_TARGET_Z, pos.getZ());
    }

    private static BlockPos readTarget(Villager villager) {
        if (!villager.getPersistentData().contains(KEY_TARGET_X)) {
            return null;
        }

        return new BlockPos(
                villager.getPersistentData().getInt(KEY_TARGET_X).orElse(0),
                villager.getPersistentData().getInt(KEY_TARGET_Y).orElse(0),
                villager.getPersistentData().getInt(KEY_TARGET_Z).orElse(0)
        );
    }

    private static void clearTarget(Villager villager) {
        villager.getPersistentData().remove(KEY_TARGET_X);
        villager.getPersistentData().remove(KEY_TARGET_Y);
        villager.getPersistentData().remove(KEY_TARGET_Z);
    }

    private static String getState(Villager villager) {
        return villager.getPersistentData().getString(KEY_STATE).orElse(STATE_PATROL_FIELD);
    }

    private static void setState(Villager villager, String state) {
        villager.getPersistentData().putString(KEY_STATE, state);
    }
}