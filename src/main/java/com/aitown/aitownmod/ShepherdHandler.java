package com.aitown.aitownmod;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.List;
import java.util.UUID;

/**
 * 牧羊工 v1。
 *
 * 第一版目标：
 * 1. 以 MemoryWork 为牧场中心。
 * 2. 从仓库拿 oak_fence / oak_fence_gate，自己搭建 7x7 简易牧场。
 * 3. 扫描附近羊，记录目标羊 UUID，持续把羊导航到牧场内部。
 * 4. 巡视牧场，剪可剪毛的羊。
 * 5. 捡羊毛并存入仓库。
 *
 * 注意：
 * - 这里不是复用原版小麦吸引机制，而是把“引导羊”视为牧羊工的职业能力。
 * - 第一版暂时不做繁殖，不做羊归属 NBT，不做复杂开关门寻路。
 */
@EventBusSubscriber(modid = aitown.MODID)
public class ShepherdHandler {
    private static final String KEY_STATE = "ShepherdState";
    private static final String KEY_TARGET_SHEEP_UUID = "ShepherdTargetSheepUuid";
    private static final String KEY_GUIDE_TICKS = "ShepherdGuideTicks";
    private static final String KEY_WOOL_SINCE_DIARY = "ShepherdWoolSinceDiary";

    private static final String STATE_CHECK_PASTURE = "check_pasture";
    private static final String STATE_FETCH_FENCE = "fetch_fence";
    private static final String STATE_BUILD_PASTURE = "build_pasture";
    private static final String STATE_FIND_SHEEP = "find_sheep";
    private static final String STATE_APPROACH_SHEEP = "approach_sheep";
    private static final String STATE_GUIDE_SHEEP = "guide_sheep";
    private static final String STATE_PATROL_PASTURE = "patrol_pasture";
    private static final String STATE_SHEAR_SHEEP = "shear_sheep";
    private static final String STATE_PICKUP_WOOL = "pickup_wool";
    private static final String STATE_DEPOSIT_WOOL = "deposit_wool";
    private static final String STATE_WAIT = "wait";

    private static final int PASTURE_RADIUS = 3;
    private static final int NEEDED_FENCES = 23;
    private static final int NEEDED_GATES = 1;
    private static final int SHEEP_SEARCH_RADIUS = 32;
    private static final int SHEEP_GUIDE_TIMEOUT = 600;
    private static final double APPROACH_SHEEP_REACH = 16.0D;
    private static final double SHEEP_INSIDE_RADIUS = 2.0D;
    private static final int WOOL_DIARY_STEP = 16;

    @SubscribeEvent
    public static void onVillagerTick(EntityTickEvent.Pre event) {
        net.minecraft.server.level.ServerLevel level =
                VillagerJobHelper.beginJobTick(event, SmartVillagerData.ROLE_SHEPHERD);
        if (level == null) {
            return;
        }
        Villager villager = (Villager) event.getEntity();
        ensurePastureCenter(villager);

        if (TownSystem.tickLifeNeeds(level, villager)) {
            return;
        }

        if (SmartVillagerData.shouldThink(villager, 10)) {
            pickupWoolNearby(level, villager);
        }

        if (!SmartVillagerData.shouldThink(villager, 5)) {
            return;
        }

        String state = getState(villager);

        if (STATE_CHECK_PASTURE.equals(state)) {
            tickCheckPasture(level, villager);
            return;
        }

        if (STATE_FETCH_FENCE.equals(state)) {
            tickFetchFence(level, villager);
            return;
        }

        if (STATE_BUILD_PASTURE.equals(state)) {
            tickBuildPasture(level, villager);
            return;
        }

        if (STATE_FIND_SHEEP.equals(state)) {
            tickFindSheep(level, villager);
            return;
        }

        if (STATE_APPROACH_SHEEP.equals(state)) {
            tickApproachSheep(level, villager);
            return;
        }

        if (STATE_GUIDE_SHEEP.equals(state)) {
            tickGuideSheep(level, villager);
            return;
        }

        if (STATE_PATROL_PASTURE.equals(state)) {
            tickPatrolPasture(level, villager);
            return;
        }

        if (STATE_SHEAR_SHEEP.equals(state)) {
            tickShearSheep(level, villager);
            return;
        }

        if (STATE_PICKUP_WOOL.equals(state)) {
            tickPickupWool(level, villager);
            return;
        }

        if (STATE_DEPOSIT_WOOL.equals(state)) {
            tickDepositWool(level, villager);
            return;
        }

        if (STATE_WAIT.equals(state)) {
            tickWait(level, villager);
            return;
        }

        setState(villager, STATE_CHECK_PASTURE);
    }

    private static void tickCheckPasture(net.minecraft.server.level.ServerLevel level, Villager villager) {
        BlockPos center = SmartVillagerData.getMemoryWorkOrCurrent(villager);
        SmartVillagerData.setStatus(villager, "检查牧场", "检查 7x7 围栏牧场是否完整");

        if (isPastureComplete(level, center)) {
            setState(villager, STATE_FIND_SHEEP);
        } else {
            setState(villager, STATE_FETCH_FENCE);
        }
    }

    private static void tickFetchFence(net.minecraft.server.level.ServerLevel level, Villager villager) {
        int fences = SmartVillagerData.countItems(villager.getInventory(), "minecraft:oak_fence");
        int gates = SmartVillagerData.countItems(villager.getInventory(), "minecraft:oak_fence_gate");

        if (fences >= NEEDED_FENCES && gates >= NEEDED_GATES) {
            setState(villager, STATE_BUILD_PASTURE);
            return;
        }

        BlockPos warehouse = SmartVillagerData.getWarehouseCenter(villager);
        SmartVillagerData.setStatus(villager, "领取围栏", "去仓库拿牧场需要的栅栏和栅栏门");
        SmartVillagerData.setTargetPlace(villager, warehouse, "shepherd_fetch_fence");

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
                        new SmartVillagerData.ItemRequest("minecraft:oak_fence", NEEDED_FENCES),
                        new SmartVillagerData.ItemRequest("minecraft:oak_fence_gate", NEEDED_GATES)
                ),
                SmartVillagerData.WAREHOUSE_RADIUS
        );

        fences = SmartVillagerData.countItems(villager.getInventory(), "minecraft:oak_fence");
        gates = SmartVillagerData.countItems(villager.getInventory(), "minecraft:oak_fence_gate");

        if (fences >= NEEDED_FENCES && gates >= NEEDED_GATES) {
            setState(villager, STATE_BUILD_PASTURE);
        } else {
            SmartVillagerData.setStatus(villager, "等待围栏", "仓库缺少栅栏或栅栏门，暂时无法搭建牧场");
        }
    }

    private static void tickBuildPasture(net.minecraft.server.level.ServerLevel level, Villager villager) {
        BlockPos center = SmartVillagerData.getMemoryWorkOrCurrent(villager);
        BlockPos next = findNextPastureBlockToPlace(level, center);

        if (next == null) {
            SmartVillagerData.addDiary(villager, "我完成了一个 7x7 的羊圈牧场。现在可以开始管理羊群了。");
            setState(villager, STATE_FIND_SHEEP);
            return;
        }

        SmartVillagerData.setStatus(villager, "搭建牧场", "用栅栏和栅栏门围出 7x7 羊圈");
        SmartVillagerData.setTargetPlace(villager, next, "shepherd_build_pasture");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_WORK,
                SmartVillagerData.SPEED_NORMAL
        );

        if (!arrived) {
            return;
        }

        if (!level.getBlockState(next).isAir()) {
            return;
        }

        if (isGatePos(center, next)) {
            if (!SmartVillagerData.consumeOne(villager.getInventory(), "minecraft:oak_fence_gate")) {
                setState(villager, STATE_FETCH_FENCE);
                return;
            }

            BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState()
                    .setValue(FenceGateBlock.OPEN, false);
            level.setBlockAndUpdate(next, gate);
        } else {
            if (!SmartVillagerData.consumeOne(villager.getInventory(), "minecraft:oak_fence")) {
                setState(villager, STATE_FETCH_FENCE);
                return;
            }

            level.setBlockAndUpdate(next, Blocks.OAK_FENCE.defaultBlockState());
        }

        villager.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, next, SoundEvents.WOOD_PLACE, SoundSource.NEUTRAL, 0.7F, 1.0F);
    }

    private static void tickFindSheep(net.minecraft.server.level.ServerLevel level, Villager villager) {
        BlockPos center = SmartVillagerData.getMemoryWorkOrCurrent(villager);

        if (!isPastureComplete(level, center)) {
            setState(villager, STATE_CHECK_PASTURE);
            return;
        }

        Sheep shearable = findShearableSheepInside(level, center);
        if (shearable != null) {
            saveTargetSheep(villager, shearable);
            setState(villager, STATE_SHEAR_SHEEP);
            return;
        }

        Sheep outside = findNearestOutsideSheep(level, villager, center);
        if (outside != null) {
            saveTargetSheep(villager, outside);
            villager.getPersistentData().putInt(KEY_GUIDE_TICKS, 0);
            setState(villager, STATE_APPROACH_SHEEP);
            return;
        }

        setState(villager, STATE_PATROL_PASTURE);
    }

    private static void tickApproachSheep(net.minecraft.server.level.ServerLevel level, Villager villager) {
        Sheep sheep = getTargetSheep(level, villager);
        BlockPos center = SmartVillagerData.getMemoryWorkOrCurrent(villager);

        if (sheep == null || !sheep.isAlive()) {
            clearTargetSheep(villager);
            setState(villager, STATE_FIND_SHEEP);
            return;
        }

        if (isInsidePasture(center, sheep.blockPosition())) {
            clearTargetSheep(villager);
            setState(villager, STATE_FIND_SHEEP);
            return;
        }

        SmartVillagerData.setStatus(villager, "接近羊群", "靠近目标羊，准备把它引回牧场");
        SmartVillagerData.setTargetPlace(villager, sheep.blockPosition(), "shepherd_approach_sheep");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                APPROACH_SHEEP_REACH,
                SmartVillagerData.SPEED_NORMAL
        );

        if (arrived) {
            setState(villager, STATE_GUIDE_SHEEP);
        }
    }

    private static void tickGuideSheep(net.minecraft.server.level.ServerLevel level, Villager villager) {
        Sheep sheep = getTargetSheep(level, villager);
        BlockPos center = SmartVillagerData.getMemoryWorkOrCurrent(villager);

        if (sheep == null || !sheep.isAlive()) {
            clearTargetSheep(villager);
            setState(villager, STATE_FIND_SHEEP);
            return;
        }

        if (isInsidePasture(center, sheep.blockPosition())) {
            clearTargetSheep(villager);
            SmartVillagerData.addDiary(villager, "我成功把一只羊赶进了牧场。");
            setState(villager, STATE_FIND_SHEEP);
            return;
        }

        int guideTicks = villager.getPersistentData().getInt(KEY_GUIDE_TICKS).orElse(0) + 5;
        villager.getPersistentData().putInt(KEY_GUIDE_TICKS, guideTicks);

        if (guideTicks > SHEEP_GUIDE_TIMEOUT) {
            clearTargetSheep(villager);
            SmartVillagerData.addDiary(villager, "有一只羊一直没有走进牧场，我暂时放弃了这次引导。");
            setState(villager, STATE_FIND_SHEEP);
            return;
        }

        SmartVillagerData.setStatus(villager, "引导羊群", "持续引导目标羊进入牧场");

        if (SmartVillagerData.shouldThink(villager, 10)) {
            sheep.getNavigation().moveTo(
                    center.getX() + 0.5D,
                    center.getY(),
                    center.getZ() + 0.5D,
                    1.0D
            );
        }

        BlockPos gate = gatePos(center);
        SmartVillagerData.setTargetPlace(villager, gate, "shepherd_guide_sheep_home");
        SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_WORK,
                SmartVillagerData.SPEED_NORMAL
        );
    }

    private static void tickPatrolPasture(net.minecraft.server.level.ServerLevel level, Villager villager) {
        BlockPos center = SmartVillagerData.getMemoryWorkOrCurrent(villager);
        SmartVillagerData.setStatus(villager, "巡视牧场", "看守牧场，检查是否有可以剪毛的羊");
        SmartVillagerData.setTargetPlace(villager, center, "shepherd_patrol_pasture");
        SmartVillagerData.moveToTargetPlace(villager, SmartVillagerData.PLACE_WORK, SmartVillagerData.SPEED_SLOW);

        if (hasWool(villager)) {
            setState(villager, STATE_DEPOSIT_WOOL);
            return;
        }

        Sheep shearable = findShearableSheepInside(level, center);
        if (shearable != null) {
            saveTargetSheep(villager, shearable);
            setState(villager, STATE_SHEAR_SHEEP);
            return;
        }

        TownSystem.tryIdleTalk(level, villager, "我刚才巡视了牧场，正在等羊重新长出羊毛。");

        if (SmartVillagerData.shouldThink(villager, 80)) {
            setState(villager, STATE_FIND_SHEEP);
        }
    }

    private static void tickShearSheep(net.minecraft.server.level.ServerLevel level, Villager villager) {
        Sheep sheep = getTargetSheep(level, villager);
        BlockPos center = SmartVillagerData.getMemoryWorkOrCurrent(villager);

        if (sheep == null || !sheep.isAlive() || !isInsidePasture(center, sheep.blockPosition())) {
            clearTargetSheep(villager);
            setState(villager, STATE_FIND_SHEEP);
            return;
        }

        if (!sheep.readyForShearing()) {
            clearTargetSheep(villager);
            setState(villager, STATE_PATROL_PASTURE);
            return;
        }

        SmartVillagerData.setStatus(villager, "剪羊毛", "给牧场里的羊剪毛");
        SmartVillagerData.setTargetPlace(villager, sheep.blockPosition(), "shepherd_shear_sheep");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_WORK,
                SmartVillagerData.SPEED_NORMAL
        );

        if (!arrived) {
            return;
        }

        sheep.shear(level, SoundSource.NEUTRAL, new ItemStack(Items.SHEARS));
        villager.swing(InteractionHand.MAIN_HAND);
        clearTargetSheep(villager);
        setState(villager, STATE_PICKUP_WOOL);
    }

    private static void tickPickupWool(net.minecraft.server.level.ServerLevel level, Villager villager) {
        SmartVillagerData.setStatus(villager, "捡羊毛", "收集剪下来的羊毛");

        boolean cleaned = SmartVillagerData.moveToAndPickupNearestItem(
                level,
                villager,
                8.0D,
                2.0D,
                ShepherdHandler::isWoolProduct,
                "shepherd_pickup_wool"
        );

        if (!cleaned) {
            return;
        }

        if (hasWool(villager)) {
            setState(villager, STATE_DEPOSIT_WOOL);
        } else {
            setState(villager, STATE_FIND_SHEEP);
        }
    }

    private static void tickDepositWool(net.minecraft.server.level.ServerLevel level, Villager villager) {
        if (!hasWool(villager)) {
            setState(villager, STATE_FIND_SHEEP);
            return;
        }

        BlockPos warehouse = SmartVillagerData.getWarehouseCenter(villager);
        SmartVillagerData.setStatus(villager, "存入羊毛", "把剪下来的羊毛送回仓库");
        SmartVillagerData.setTargetPlace(villager, warehouse, "shepherd_deposit_wool");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_STORAGE,
                SmartVillagerData.SPEED_NORMAL
        );

        if (!arrived) {
            return;
        }

        int before = countAllWool(villager);
        int moved = SmartVillagerData.depositItemsToWarehouse(
                level,
                villager,
                warehouse,
                woolRequests(),
                SmartVillagerData.WAREHOUSE_RADIUS
        );

        if (moved > 0) {
            addWoolProgress(villager, Math.min(before, moved));
        }

        setState(villager, STATE_FIND_SHEEP);
    }

    private static void tickWait(net.minecraft.server.level.ServerLevel level, Villager villager) {
        BlockPos center = SmartVillagerData.getMemoryWorkOrCurrent(villager);
        SmartVillagerData.setStatus(villager, "等待羊毛", "牧场暂时没有可以剪毛的羊");
        SmartVillagerData.setTargetPlace(villager, center, "shepherd_wait");
        SmartVillagerData.moveToTargetPlace(villager, SmartVillagerData.PLACE_WORK, SmartVillagerData.SPEED_SLOW);

        TownSystem.tryIdleTalk(level, villager, "我刚才剪了羊毛，也照看了一下牧场里的羊。");

        if (SmartVillagerData.shouldThink(villager, 80)) {
            setState(villager, STATE_FIND_SHEEP);
        }
    }

    private static void ensurePastureCenter(Villager villager) {
        if (!SmartVillagerData.hasMemoryWork(villager)) {
            BlockPos pos = villager.blockPosition();
            SmartVillagerData.setMemoryWork(villager, pos);
            SmartVillagerData.addDiary(villager, "我把这里记作自己的牧场中心。位置是 "
                    + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "。");
        }
    }

    private static boolean isPastureComplete(net.minecraft.server.level.ServerLevel level, BlockPos center) {
        for (int dx = -PASTURE_RADIUS; dx <= PASTURE_RADIUS; dx++) {
            for (int dz = -PASTURE_RADIUS; dz <= PASTURE_RADIUS; dz++) {
                if (Math.abs(dx) != PASTURE_RADIUS && Math.abs(dz) != PASTURE_RADIUS) {
                    continue;
                }

                BlockPos pos = center.offset(dx, 0, dz);
                BlockState state = level.getBlockState(pos);

                if (isGatePos(center, pos)) {
                    if (!state.is(Blocks.OAK_FENCE_GATE)) {
                        return false;
                    }
                } else if (!state.is(Blocks.OAK_FENCE)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static BlockPos findNextPastureBlockToPlace(net.minecraft.server.level.ServerLevel level, BlockPos center) {
        for (int dx = -PASTURE_RADIUS; dx <= PASTURE_RADIUS; dx++) {
            for (int dz = -PASTURE_RADIUS; dz <= PASTURE_RADIUS; dz++) {
                if (Math.abs(dx) != PASTURE_RADIUS && Math.abs(dz) != PASTURE_RADIUS) {
                    continue;
                }

                BlockPos pos = center.offset(dx, 0, dz);
                BlockState state = level.getBlockState(pos);

                if (isGatePos(center, pos)) {
                    if (!state.is(Blocks.OAK_FENCE_GATE) && state.isAir()) {
                        return pos;
                    }
                } else if (!state.is(Blocks.OAK_FENCE) && state.isAir()) {
                    return pos;
                }
            }
        }

        return null;
    }

    private static BlockPos gatePos(BlockPos center) {
        return center.offset(0, 0, -PASTURE_RADIUS);
    }

    private static boolean isGatePos(BlockPos center, BlockPos pos) {
        return pos.equals(gatePos(center));
    }

    private static boolean isInsidePasture(BlockPos center, BlockPos pos) {
        return Math.abs(pos.getX() - center.getX()) <= SHEEP_INSIDE_RADIUS
                && Math.abs(pos.getZ() - center.getZ()) <= SHEEP_INSIDE_RADIUS
                && Math.abs(pos.getY() - center.getY()) <= 2;
    }

    private static Sheep findNearestOutsideSheep(
            net.minecraft.server.level.ServerLevel level,
            Villager villager,
            BlockPos center
    ) {
        List<Sheep> sheepList = level.getEntitiesOfClass(
                Sheep.class,
                new AABB(villager.blockPosition()).inflate(SHEEP_SEARCH_RADIUS, 6.0D, SHEEP_SEARCH_RADIUS),
                sheep -> sheep.isAlive() && !sheep.isBaby() && !isInsidePasture(center, sheep.blockPosition())
        );

        Sheep best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Sheep sheep : sheepList) {
            double distance = villager.distanceToSqr(sheep);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = sheep;
            }
        }

        return best;
    }

    private static Sheep findShearableSheepInside(net.minecraft.server.level.ServerLevel level, BlockPos center) {
        List<Sheep> sheepList = level.getEntitiesOfClass(
                Sheep.class,
                new AABB(center).inflate(PASTURE_RADIUS, 3.0D, PASTURE_RADIUS),
                sheep -> sheep.isAlive() && isInsidePasture(center, sheep.blockPosition()) && sheep.readyForShearing()
        );

        if (sheepList.isEmpty()) {
            return null;
        }

        return sheepList.get(0);
    }

    private static void saveTargetSheep(Villager villager, Sheep sheep) {
        villager.getPersistentData().putString(KEY_TARGET_SHEEP_UUID, sheep.getUUID().toString());
    }

    private static Sheep getTargetSheep(net.minecraft.server.level.ServerLevel level, Villager villager) {
        String uuidText = villager.getPersistentData().getString(KEY_TARGET_SHEEP_UUID).orElse("");

        if (uuidText.isBlank()) {
            return null;
        }

        try {
            UUID uuid = UUID.fromString(uuidText);
            Entity entity = level.getEntity(uuid);

            if (entity instanceof Sheep sheep) {
                return sheep;
            }
        } catch (Exception ignored) {
            // UUID 损坏时当作目标失效。
        }

        return null;
    }

    private static void clearTargetSheep(Villager villager) {
        villager.getPersistentData().remove(KEY_TARGET_SHEEP_UUID);
        villager.getPersistentData().putInt(KEY_GUIDE_TICKS, 0);
    }

    private static void pickupWoolNearby(net.minecraft.server.level.ServerLevel level, Villager villager) {
        SmartVillagerData.pickupNearbyItems(
                level,
                villager,
                villager.blockPosition(),
                2.5D,
                1.5D,
                ShepherdHandler::isWoolProduct
        );
    }

    private static boolean isWoolProduct(ItemStack stack) {
        String itemName = SmartVillagerData.itemId(stack);
        return itemName.endsWith("_wool");
    }

    private static boolean hasWool(Villager villager) {
        return countAllWool(villager) > 0;
    }

    private static int countAllWool(Villager villager) {
        int count = 0;
        for (String itemName : woolItemNames()) {
            count += SmartVillagerData.countItems(villager.getInventory(), itemName);
        }
        return count;
    }

    private static List<SmartVillagerData.ItemRequest> woolRequests() {
        return woolItemNames().stream()
                .map(item -> new SmartVillagerData.ItemRequest(item, 999))
                .toList();
    }

    private static List<String> woolItemNames() {
        return List.of(
                "minecraft:white_wool",
                "minecraft:orange_wool",
                "minecraft:magenta_wool",
                "minecraft:light_blue_wool",
                "minecraft:yellow_wool",
                "minecraft:lime_wool",
                "minecraft:pink_wool",
                "minecraft:gray_wool",
                "minecraft:light_gray_wool",
                "minecraft:cyan_wool",
                "minecraft:purple_wool",
                "minecraft:blue_wool",
                "minecraft:brown_wool",
                "minecraft:green_wool",
                "minecraft:red_wool",
                "minecraft:black_wool"
        );
    }

    private static void addWoolProgress(Villager villager, int amount) {
        VillagerJobHelper.addDiaryProgress(villager, KEY_WOOL_SINCE_DIARY, amount, WOOL_DIARY_STEP,
                "我已经累计剪下并上交了 16 个羊毛。");
    }

    // 状态样板统一委托给 VillagerJobHelper。
    private static String getState(Villager villager) {
        return VillagerJobHelper.getState(villager, KEY_STATE, STATE_CHECK_PASTURE);
    }

    private static void setState(Villager villager, String state) {
        VillagerJobHelper.setState(villager, KEY_STATE, state);
    }
}