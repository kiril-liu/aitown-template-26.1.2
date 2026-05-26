package com.aitown.aitownmod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.List;

/**
 * 矿工 / 采石工 v1。
 *
 * 当前目标：
 * 1. 只采集小镇中心高度或以上的石头，不向地下开采。
 * 2. 只采集看起来像自然岩体的石头。
 * 3. 如果目标石头附近有人造建筑方块，就不采，避免拆建筑师刚建好的房子。
 * 4. 破坏石头后用掉落物公共接口捡取圆石等产物。
 * 5. 采集到 10 个左右后送回小镇仓库。
 *
 * 当前不做：
 * - 不下矿洞。
 * - 不处理岩浆、水、深坑。
 * - 不处理工具耐久。
 * - 不处理铁矿、煤矿等矿石。
 */
@EventBusSubscriber(modid = aitown.MODID)
public class MinerHandler {
    private static final String KEY_STATE = "MinerState";

    private static final String STATE_SEEK_STONE = "seek_stone";
    private static final String STATE_MOVE_TO_STONE = "move_to_stone";
    private static final String STATE_MINE_STONE = "mine_stone";
    private static final String STATE_PICKUP_DROPS = "pickup_drops";
    private static final String STATE_DEPOSIT_ITEMS = "deposit_items";
    private static final String STATE_WAIT = "wait";

    private static final String KEY_STONE_X = "MinerStoneX";
    private static final String KEY_STONE_Y = "MinerStoneY";
    private static final String KEY_STONE_Z = "MinerStoneZ";

    private static final String KEY_WORK_X = "MinerWorkX";
    private static final String KEY_WORK_Y = "MinerWorkY";
    private static final String KEY_WORK_Z = "MinerWorkZ";

    // 搜索半径先不要太大，方便观察和调试。
    private static final int STONE_SEARCH_RADIUS = 28;

    // 采集到 10 个就回仓库，方便你观察矿工状态切换。
    private static final int DEPOSIT_THRESHOLD = 10;

    // 目标石头周围 3x3x3 内至少有这么多个自然石头，才认为它属于自然岩体。
    private static final int MIN_NATURAL_STONE_CLUSTER = 8;

    // 目标石头附近这个半径内如果出现人造方块，就不采。
    private static final int ARTIFICIAL_BLOCK_CHECK_RADIUS = 3;

    @SubscribeEvent
    public static void onVillagerTick(EntityTickEvent.Pre event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }

        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }

        if (!SmartVillagerData.isRole(villager, SmartVillagerData.ROLE_MINER)) {
            return;
        }

        if (!(villager.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        SmartVillagerData.ensureIdentity(villager);
        SmartVillagerData.suppressVanillaMovement(villager);

        // 轻量拾取：矿工路过自己的掉落物时顺手捡。
        if (SmartVillagerData.shouldThink(villager, 10)) {
            SmartVillagerData.pickupNearbyItems(
                    level,
                    villager,
                    villager.blockPosition(),
                    2.5D,
                    1.5D,
                    MinerHandler::isMinerProduct
            );
        }

        // 主状态机节流，避免每 tick 大范围扫描。
        if (!SmartVillagerData.shouldThink(villager, 5)) {
            return;
        }

        String state = getState(villager);

        if (STATE_SEEK_STONE.equals(state)) {
            tickSeekStone(level, villager);
            return;
        }

        if (STATE_MOVE_TO_STONE.equals(state)) {
            tickMoveToStone(level, villager);
            return;
        }

        if (STATE_MINE_STONE.equals(state)) {
            tickMineStone(level, villager);
            return;
        }

        if (STATE_PICKUP_DROPS.equals(state)) {
            tickPickupDrops(level, villager);
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

        setState(villager, STATE_SEEK_STONE);
    }

    private static void tickSeekStone(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        SmartVillagerData.setStatus(villager, "寻找石头", "寻找可采集的自然岩体");

        if (shouldDeposit(villager)) {
            setState(villager, STATE_DEPOSIT_ITEMS);
            return;
        }

        BlockPos targetStone = findNearestValidStone(level, villager);

        if (targetStone == null) {
            setState(villager, STATE_WAIT);
            return;
        }

        BlockPos workPos = findWorkPosForStone(level, targetStone);

        if (workPos == null) {
            setState(villager, STATE_SEEK_STONE);
            return;
        }

        savePos(villager, KEY_STONE_X, KEY_STONE_Y, KEY_STONE_Z, targetStone);
        savePos(villager, KEY_WORK_X, KEY_WORK_Y, KEY_WORK_Z, workPos);

        setState(villager, STATE_MOVE_TO_STONE);
    }

    private static void tickMoveToStone(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        BlockPos stonePos = readPos(villager, KEY_STONE_X, KEY_STONE_Y, KEY_STONE_Z);
        BlockPos workPos = readPos(villager, KEY_WORK_X, KEY_WORK_Y, KEY_WORK_Z);

        if (stonePos == null || workPos == null) {
            setState(villager, STATE_SEEK_STONE);
            return;
        }

        // 走过去的过程中，目标可能已经被玩家或别的矿工破坏，
        // 或者附近出现人造方块，所以每次移动前都重新验证。
        if (!isValidMineTarget(level, villager, stonePos)) {
            clearMiningTarget(villager);
            setState(villager, STATE_SEEK_STONE);
            return;
        }

        SmartVillagerData.setStatus(villager, "走向石头", "前往采石位置");

        SmartVillagerData.setTargetPlace(villager, workPos, "miner_stone_work_pos");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_WORK,
                SmartVillagerData.SPEED_NORMAL
        );

        if (arrived) {
            setState(villager, STATE_MINE_STONE);
        }
    }

    private static void tickMineStone(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        BlockPos stonePos = readPos(villager, KEY_STONE_X, KEY_STONE_Y, KEY_STONE_Z);

        if (stonePos == null) {
            setState(villager, STATE_SEEK_STONE);
            return;
        }

        // 开采前最后一次确认：
        // 1. 它还是石头；
        // 2. 它不在地下；
        // 3. 它附近不像建筑物；
        // 4. 它仍然像自然岩体。
        if (!isValidMineTarget(level, villager, stonePos)) {
            clearMiningTarget(villager);
            setState(villager, STATE_SEEK_STONE);
            return;
        }

        SmartVillagerData.setStatus(villager, "采集中", "破坏石头并生成掉落物");

        // true = 生成原版掉落物。
        // stone 通常会掉 cobblestone。
        boolean destroyed = level.destroyBlock(stonePos, true);

        if (destroyed) {
            villager.swing(InteractionHand.MAIN_HAND);

            level.playSound(
                    null,
                    stonePos,
                    SoundEvents.STONE_BREAK,
                    SoundSource.NEUTRAL,
                    0.8F,
                    1.0F
            );
        }

        setState(villager, STATE_PICKUP_DROPS);
    }

    private static void tickPickupDrops(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        SmartVillagerData.setStatus(villager, "捡取石材", "收集采石掉落物");

        boolean cleaned = SmartVillagerData.moveToAndPickupNearestItem(
                level,
                villager,
                8.0D,
                2.0D,
                MinerHandler::isMinerProduct,
                "miner_pickup_stone_drops"
        );

        if (!cleaned) {
            return;
        }

        clearMiningTarget(villager);

        if (shouldDeposit(villager)) {
            setState(villager, STATE_DEPOSIT_ITEMS);
        } else {
            setState(villager, STATE_SEEK_STONE);
        }
    }

    private static void tickDepositItems(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        BlockPos warehouse = SmartVillagerData.getWarehouseCenter(villager);

        SmartVillagerData.setStatus(villager, "存入仓库", "把石材放入小镇仓库");

        SmartVillagerData.setTargetPlace(villager, warehouse, "miner_deposit_items");

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
                        new SmartVillagerData.ItemRequest("minecraft:cobblestone", 999),
                        new SmartVillagerData.ItemRequest("minecraft:stone", 999),
                        new SmartVillagerData.ItemRequest("minecraft:andesite", 999),
                        new SmartVillagerData.ItemRequest("minecraft:diorite", 999),
                        new SmartVillagerData.ItemRequest("minecraft:granite", 999)
                ),
                SmartVillagerData.WAREHOUSE_RADIUS
        );

        setState(villager, STATE_SEEK_STONE);
    }

    private static void tickWait(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        SmartVillagerData.setStatus(villager, "等待采石", "附近没有安全的自然石头");

        if (!SmartVillagerData.shouldThink(villager, 40)) {
            return;
        }

        BlockPos targetStone = findNearestValidStone(level, villager);

        if (targetStone != null) {
            BlockPos workPos = findWorkPosForStone(level, targetStone);

            if (workPos != null) {
                savePos(villager, KEY_STONE_X, KEY_STONE_Y, KEY_STONE_Z, targetStone);
                savePos(villager, KEY_WORK_X, KEY_WORK_Y, KEY_WORK_Z, workPos);
                setState(villager, STATE_MOVE_TO_STONE);
            }
        }
    }

    private static BlockPos findNearestValidStone(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        BlockPos bestStone = null;
        double bestDistance = Double.MAX_VALUE;

        BlockPos townCenter = SmartVillagerData.getTownCenter(villager);
        int minMineY = townCenter.getY();

        for (int dx = -STONE_SEARCH_RADIUS; dx <= STONE_SEARCH_RADIUS; dx++) {
            for (int dy = -4; dy <= 12; dy++) {
                for (int dz = -STONE_SEARCH_RADIUS; dz <= STONE_SEARCH_RADIUS; dz++) {
                    mPos.set(
                            villager.getBlockX() + dx,
                            villager.getBlockY() + dy,
                            villager.getBlockZ() + dz
                    );

                    // 不采小镇中心高度以下的石头，避免向地下挖。
                    if (mPos.getY() < minMineY) {
                        continue;
                    }

                    BlockPos candidate = mPos.immutable();

                    if (!isValidMineTarget(level, villager, candidate)) {
                        continue;
                    }

                    double distance = villager.distanceToSqr(
                            candidate.getX() + 0.5D,
                            candidate.getY(),
                            candidate.getZ() + 0.5D
                    );

                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestStone = candidate;
                    }
                }
            }
        }

        return bestStone;
    }

    /**
     * 判断一个石头是否适合被矿工采集。
     *
     * 核心安全规则：
     * 1. 只采 stone / cobblestone / andesite / diorite / granite。
     * 2. 不采小镇中心高度以下的石头。
     * 3. 必须暴露在空气旁边，避免扫描地下石头。
     * 4. 附近必须像自然岩体。
     * 5. 附近不能有人造建筑方块，避免拆建筑师或玩家建筑。
     * 6. 不能采自己脚下或身体位置附近的关键方块。
     */
    private static boolean isValidMineTarget(
            net.minecraft.server.level.ServerLevel level,
            Villager villager,
            BlockPos pos
    ) {
        BlockState state = level.getBlockState(pos);

        if (!isMineableStone(state)) {
            return false;
        }

        BlockPos townCenter = SmartVillagerData.getTownCenter(villager);

        if (pos.getY() < townCenter.getY()) {
            return false;
        }

        BlockPos feet = villager.blockPosition();

        // 不允许挖自己脚下支撑方块，也不挖身体所在坐标附近的关键点。
        if (pos.equals(feet)
                || pos.equals(feet.above())
                || pos.equals(feet.below())) {
            return false;
        }

        if (!isStoneExposedToAir(level, pos)) {
            return false;
        }

        if (countNaturalStoneAround(level, pos, 1) < MIN_NATURAL_STONE_CLUSTER) {
            return false;
        }

        if (hasNearbyArtificialBlocks(level, pos, ARTIFICIAL_BLOCK_CHECK_RADIUS)) {
            return false;
        }

        return true;
    }

    private static boolean isMineableStone(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DIORITE)
                || state.is(Blocks.GRANITE);
    }

    private static boolean isNaturalStone(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DIORITE)
                || state.is(Blocks.GRANITE)
                || state.is(Blocks.TUFF);
    }

    /**
     * 判断石头是否暴露在空气旁边。
     *
     * 这样矿工不会去采完全埋在地下的石头。
     */
    private static boolean isStoneExposedToAir(
            net.minecraft.server.level.ServerLevel level,
            BlockPos pos
    ) {
        if (level.getBlockState(pos.above()).isAir()) {
            return true;
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }

        return false;
    }

    private static int countNaturalStoneAround(
            net.minecraft.server.level.ServerLevel level,
            BlockPos center,
            int radius
    ) {
        int count = 0;

        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    mPos.set(
                            center.getX() + dx,
                            center.getY() + dy,
                            center.getZ() + dz
                    );

                    if (isNaturalStone(level.getBlockState(mPos))) {
                        count++;
                    }
                }
            }
        }

        return count;
    }

    /**
     * 附近有人造方块就不采。
     *
     * 这个规则用来保护：
     * - 建筑师刚建的房子；
     * - 玩家建筑；
     * - 仓库周围结构；
     * - 木制道路、箱子、门、玻璃等。
     */
    private static boolean hasNearbyArtificialBlocks(
            net.minecraft.server.level.ServerLevel level,
            BlockPos center,
            int radius
    ) {
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    mPos.set(
                            center.getX() + dx,
                            center.getY() + dy,
                            center.getZ() + dz
                    );

                    if (isArtificialBlock(level.getBlockState(mPos))) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static boolean isArtificialBlock(BlockState state) {
        if (state.isAir()) {
            return false;
        }

        String blockName = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock())
                .getPath();

        return blockName.contains("planks")
                || blockName.contains("stairs")
                || blockName.contains("slab")
                || blockName.contains("fence")
                || blockName.contains("fence_gate")
                || blockName.contains("door")
                || blockName.contains("trapdoor")
                || blockName.contains("glass")
                || blockName.contains("pane")
                || blockName.contains("chest")
                || blockName.contains("barrel")
                || blockName.contains("bed")
                || blockName.contains("torch")
                || blockName.contains("lantern")
                || blockName.contains("crafting_table")
                || blockName.contains("furnace");
    }

    /**
     * 为目标石头寻找工作站位。
     *
     * 第一版只找目标石头水平四周的空气位置：
     * - 脚的位置是空气；
     * - 头的位置是空气；
     * - 脚下有方块。
     */
    private static BlockPos findWorkPosForStone(
            net.minecraft.server.level.ServerLevel level,
            BlockPos stonePos
    ) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = stonePos.relative(direction);

            if (isStandable(level, candidate)) {
                return candidate;
            }
        }

        // 如果石头上方是空气，也允许站在石头上方附近。
        BlockPos above = stonePos.above();

        if (isStandable(level, above)) {
            return above;
        }

        return null;
    }

    private static boolean isStandable(
            net.minecraft.server.level.ServerLevel level,
            BlockPos pos
    ) {
        return level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir()
                && !level.getBlockState(pos.below()).isAir();
    }

    private static boolean shouldDeposit(Villager villager) {
        return countMinerProducts(villager.getInventory()) >= DEPOSIT_THRESHOLD
                || countEmptySlots(villager.getInventory()) <= 1;
    }

    private static int countMinerProducts(SimpleContainer inventory) {
        int count = 0;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);

            if (!stack.isEmpty() && isMinerProduct(stack)) {
                count += stack.getCount();
            }
        }

        return count;
    }

    private static int countEmptySlots(SimpleContainer inventory) {
        int count = 0;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                count++;
            }
        }

        return count;
    }

    private static boolean isMinerProduct(ItemStack stack) {
        String itemName = SmartVillagerData.itemId(stack);

        return itemName.equals("minecraft:cobblestone")
                || itemName.equals("minecraft:stone")
                || itemName.equals("minecraft:andesite")
                || itemName.equals("minecraft:diorite")
                || itemName.equals("minecraft:granite");
    }

    private static String getState(Villager villager) {
        return villager.getPersistentData().getString(KEY_STATE).orElse(STATE_SEEK_STONE);
    }

    private static void setState(Villager villager, String state) {
        villager.getPersistentData().putString(KEY_STATE, state);
    }

    private static void savePos(
            Villager villager,
            String keyX,
            String keyY,
            String keyZ,
            BlockPos pos
    ) {
        villager.getPersistentData().putInt(keyX, pos.getX());
        villager.getPersistentData().putInt(keyY, pos.getY());
        villager.getPersistentData().putInt(keyZ, pos.getZ());
    }

    private static BlockPos readPos(
            Villager villager,
            String keyX,
            String keyY,
            String keyZ
    ) {
        if (!villager.getPersistentData().contains(keyX)
                || !villager.getPersistentData().contains(keyY)
                || !villager.getPersistentData().contains(keyZ)) {
            return null;
        }

        return new BlockPos(
                villager.getPersistentData().getInt(keyX).orElse(0),
                villager.getPersistentData().getInt(keyY).orElse(0),
                villager.getPersistentData().getInt(keyZ).orElse(0)
        );
    }

    private static void clearMiningTarget(Villager villager) {
        villager.getPersistentData().remove(KEY_STONE_X);
        villager.getPersistentData().remove(KEY_STONE_Y);
        villager.getPersistentData().remove(KEY_STONE_Z);

        villager.getPersistentData().remove(KEY_WORK_X);
        villager.getPersistentData().remove(KEY_WORK_Y);
        villager.getPersistentData().remove(KEY_WORK_Z);

        SmartVillagerData.clearTargetPlace(villager);
    }
}