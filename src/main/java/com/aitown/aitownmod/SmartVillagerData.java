package com.aitown.aitownmod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.player.Player;

import java.util.*;

public class SmartVillagerData {
    public static final String ROLE_NONE = "none";
    public static final String ROLE_BUILDER = "builder";
    public static final String ROLE_LUMBERJACK = "lumberjack";
    public static final String ROLE_MINER = "miner";
    public static final String KEY_PLAYER_TOWN_VILLAGERS = "TownSmartVillagerIds";

    public static final String KEY_NAME = "CitizenName";
    public static final String KEY_ID = "CitizenId";
    public static final String KEY_ROLE = "SmartRole";
    public static final String KEY_STATUS = "WorkerStatus";
    public static final String KEY_TASK = "CurrentTask";

    public static final String KEY_TOWN_X = "TownCenterX";
    public static final String KEY_TOWN_Y = "TownCenterY";
    public static final String KEY_TOWN_Z = "TownCenterZ";

    public static final String KEY_WAREHOUSE_X = "WarehouseX";
    public static final String KEY_WAREHOUSE_Y = "WarehouseY";
    public static final String KEY_WAREHOUSE_Z = "WarehouseZ";

    private static final String KEY_TARGET_X = "TargetPlaceX";
    private static final String KEY_TARGET_Y = "TargetPlaceY";
    private static final String KEY_TARGET_Z = "TargetPlaceZ";
    private static final String KEY_TARGET_ACTION = "TargetAction";

    public static final double PLACE_CLOSE = 4.0D;
    public static final double PLACE_WORK = 9.0D;
    public static final double PLACE_STORAGE = 9.0D;

    public static final double SPEED_WORK = 0.6D;
    public static final double SPEED_NORMAL = 0.55D;
    public static final double SPEED_SLOW = 0.45D;

    public static final int WAREHOUSE_RADIUS = 8;

    private static final String[] NAMES = new String[]{
            "阿木", "石头", "松果", "米粒", "橡子", "青砖", "河灯", "小麦",
            "灰灰", "阿桥", "木芽", "圆石", "小栗", "白桦", "土豆", "南瓜",
            "晨星", "暮雨", "云杉", "红砖", "苔苔", "竹叶", "小溪", "炭炭"
    };

    /**
     * 仓库存取请求。
     *
     * itemName 使用完整物品 ID：
     * minecraft:oak_log
     * minecraft:oak_sapling
     * minecraft:apple
     */
    public record ItemRequest(String itemName, int count) {
    }

    public static void ensureIdentity(Villager villager) {
        if (!villager.getPersistentData().contains(KEY_NAME)) {
            String name = NAMES[villager.getRandom().nextInt(NAMES.length)];
            villager.getPersistentData().putString(KEY_NAME, name);
        }

        if (!villager.getPersistentData().contains(KEY_ID)) {
            String uuid = villager.getUUID().toString().replace("-", "");
            villager.getPersistentData().putString(KEY_ID, "AIT-" + uuid.substring(0, 6).toUpperCase());
        }
    }

    public static void registerSmartVillagerToPlayerTown(Player player, Villager villager) {
        String villagerId = villager.getUUID().toString();

        ArrayList<String> ids = getRegisteredSmartVillagerIdStrings(player);

        if (!ids.contains(villagerId)) {
            ids.add(villagerId);
            saveRegisteredSmartVillagerIdStrings(player, ids);
        }
    }

    public static ArrayList<UUID> getRegisteredSmartVillagerIds(Player player) {
        ArrayList<UUID> result = new ArrayList<>();

        for (String idText : getRegisteredSmartVillagerIdStrings(player)) {
            try {
                result.add(UUID.fromString(idText));
            } catch (Exception ignored) {
                // 遇到损坏 UUID 直接跳过，避免一个坏数据导致整个面板打不开。
            }
        }

        return result;
    }
    private static ArrayList<String> getRegisteredSmartVillagerIdStrings(Player player) {
        ArrayList<String> ids = new ArrayList<>();

        String raw = player.getPersistentData()
                .getString(KEY_PLAYER_TOWN_VILLAGERS)
                .orElse("");

        if (raw.isBlank()) {
            return ids;
        }

        String[] parts = raw.split(";");

        for (String part : parts) {
            String trimmed = part.trim();

            if (!trimmed.isEmpty() && !ids.contains(trimmed)) {
                ids.add(trimmed);
            }
        }

        return ids;
    }

    private static void saveRegisteredSmartVillagerIdStrings(Player player, ArrayList<String> ids) {
        player.getPersistentData().putString(
                KEY_PLAYER_TOWN_VILLAGERS,
                String.join(";", ids)
        );
    }

    public static void cleanupRegisteredSmartVillagers(
            net.minecraft.server.level.ServerLevel level,
            Player player
    ) {
        ArrayList<String> oldIds = getRegisteredSmartVillagerIdStrings(player);
        ArrayList<String> newIds = new ArrayList<>();

        for (String idText : oldIds) {
            try {
                UUID uuid = UUID.fromString(idText);

                if (level.getEntity(uuid) instanceof Villager villager
                        && !villager.isRemoved()
                        && !ROLE_NONE.equals(getRole(villager))) {
                    newIds.add(idText);
                }
            } catch (Exception ignored) {
                // 损坏 UUID 跳过。
            }
        }

        saveRegisteredSmartVillagerIdStrings(player, newIds);
    }

    public static String getInventorySummary(Villager villager, int maxEntries) {
        SimpleContainer inventory = villager.getInventory();

        LinkedHashMap<String, Integer> itemCounts = new LinkedHashMap<>();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);

            if (stack.isEmpty()) {
                continue;
            }

            String itemName = itemId(stack);
            itemCounts.put(itemName, itemCounts.getOrDefault(itemName, 0) + stack.getCount());
        }

        if (itemCounts.isEmpty()) {
            return "空";
        }

        StringBuilder builder = new StringBuilder();
        int shown = 0;

        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            if (shown > 0) {
                builder.append("，");
            }

            builder.append(entry.getKey())
                    .append(" x")
                    .append(entry.getValue());

            shown++;

            if (shown >= maxEntries) {
                int remainingTypes = itemCounts.size() - shown;

                if (remainingTypes > 0) {
                    builder.append("，等 ")
                            .append(itemCounts.size())
                            .append(" 类");
                }

                break;
            }
        }

        return builder.toString();
    }

    public static String getCitizenName(Villager villager) {
        ensureIdentity(villager);
        return villager.getPersistentData().getString(KEY_NAME).orElse("无名村民");
    }

    public static String getCitizenId(Villager villager) {
        ensureIdentity(villager);
        return villager.getPersistentData().getString(KEY_ID).orElse("AIT-??????");
    }

    public static String getRole(Villager villager) {
        return villager.getPersistentData().getString(KEY_ROLE).orElse(ROLE_NONE);
    }

    public static boolean isRole(Villager villager, String role) {
        return getRole(villager).equals(role);
    }

    public static String roleDisplayName(String role) {
        if (ROLE_BUILDER.equals(role)) {
            return "建筑师";
        }

        if (ROLE_LUMBERJACK.equals(role)) {
            return "伐木工";
        }
        if (ROLE_MINER.equals(role)) {
            return "矿工";
        }

        return "未分配";
    }

    public static void setRole(Villager villager, String role) {
        ensureIdentity(villager);

        villager.getPersistentData().putString(KEY_ROLE, role);

        // 兼容旧代码或旧存档里可能还在用的布尔字段。
        villager.getPersistentData().putBoolean("IsBuilding", ROLE_BUILDER.equals(role));
        villager.getPersistentData().putBoolean("IsLumberjack", ROLE_LUMBERJACK.equals(role));
        villager.getPersistentData().putBoolean("IsMiner", ROLE_MINER.equals(role));

        setStatus(villager, "待命", "等待小镇任务");
    }

    public static void setStatus(Villager villager, String status, String task) {
        ensureIdentity(villager);

        String role = getRole(villager);
        String roleName = roleDisplayName(role);

        villager.getPersistentData().putString(KEY_STATUS, status);
        villager.getPersistentData().putString(KEY_TASK, task);

        villager.setCustomName(Component.literal("§e[" + status + "] " + getCitizenName(villager)));
        villager.setCustomNameVisible(true);
    }

    public static String getStatus(Villager villager) {
        return villager.getPersistentData().getString(KEY_STATUS).orElse("未知");
    }

    public static String getTask(Villager villager) {
        return villager.getPersistentData().getString(KEY_TASK).orElse("无任务");
    }

    public static boolean shouldThink(Villager villager, int intervalTicks) {
        return intervalTicks <= 1 || ((villager.tickCount + villager.getId()) % intervalTicks == 0);
    }

    public static void setTownAndWarehouse(Villager villager, BlockPos pos) {
        villager.getPersistentData().putInt(KEY_TOWN_X, pos.getX());
        villager.getPersistentData().putInt(KEY_TOWN_Y, pos.getY());
        villager.getPersistentData().putInt(KEY_TOWN_Z, pos.getZ());

        villager.getPersistentData().putInt(KEY_WAREHOUSE_X, pos.getX());
        villager.getPersistentData().putInt(KEY_WAREHOUSE_Y, pos.getY());
        villager.getPersistentData().putInt(KEY_WAREHOUSE_Z, pos.getZ());
    }

    public static BlockPos getTownCenter(Villager villager) {
        if (!villager.getPersistentData().contains(KEY_TOWN_X)) {
            return villager.blockPosition();
        }

        return new BlockPos(
                villager.getPersistentData().getInt(KEY_TOWN_X).orElse(villager.getBlockX()),
                villager.getPersistentData().getInt(KEY_TOWN_Y).orElse(villager.getBlockY()),
                villager.getPersistentData().getInt(KEY_TOWN_Z).orElse(villager.getBlockZ())
        );
    }

    public static BlockPos getWarehouseCenter(Villager villager) {
        if (!villager.getPersistentData().contains(KEY_WAREHOUSE_X)) {
            return getTownCenter(villager);
        }

        return new BlockPos(
                villager.getPersistentData().getInt(KEY_WAREHOUSE_X).orElse(villager.getBlockX()),
                villager.getPersistentData().getInt(KEY_WAREHOUSE_Y).orElse(villager.getBlockY()),
                villager.getPersistentData().getInt(KEY_WAREHOUSE_Z).orElse(villager.getBlockZ())
        );
    }

    public static void setTargetPlace(Villager villager, BlockPos pos, String action) {
        villager.getPersistentData().putInt(KEY_TARGET_X, pos.getX());
        villager.getPersistentData().putInt(KEY_TARGET_Y, pos.getY());
        villager.getPersistentData().putInt(KEY_TARGET_Z, pos.getZ());
        villager.getPersistentData().putString(KEY_TARGET_ACTION, action);
    }

    public static boolean hasTargetPlace(Villager villager) {
        return villager.getPersistentData().contains(KEY_TARGET_X);
    }

    public static BlockPos getTargetPlace(Villager villager) {
        if (!hasTargetPlace(villager)) {
            return villager.blockPosition();
        }

        return new BlockPos(
                villager.getPersistentData().getInt(KEY_TARGET_X).orElse(villager.getBlockX()),
                villager.getPersistentData().getInt(KEY_TARGET_Y).orElse(villager.getBlockY()),
                villager.getPersistentData().getInt(KEY_TARGET_Z).orElse(villager.getBlockZ())
        );
    }

    public static void clearTargetPlace(Villager villager) {
        villager.getPersistentData().remove(KEY_TARGET_X);
        villager.getPersistentData().remove(KEY_TARGET_Y);
        villager.getPersistentData().remove(KEY_TARGET_Z);
        villager.getPersistentData().remove(KEY_TARGET_ACTION);
    }

    public static void suppressVanillaMovement(Villager villager) {
        try {
            villager.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
            villager.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.INTERACTION_TARGET);
        } catch (Exception ignored) {
            // 部分状态下 Brain memory 可能不可用，忽略即可。
        }
    }

    public static boolean moveToTargetPlace(Villager villager, double reachSqr, double speed) {
        if (!hasTargetPlace(villager)) {
            return false;
        }

        suppressVanillaMovement(villager);

        BlockPos target = getTargetPlace(villager);

        villager.getLookControl().setLookAt(
                target.getX() + 0.5D,
                target.getY() + 0.5D,
                target.getZ() + 0.5D,
                30.0F,
                30.0F
        );

        double distance = villager.distanceToSqr(
                target.getX() + 0.5D,
                target.getY(),
                target.getZ() + 0.5D
        );

        if (distance <= reachSqr) {
            villager.getNavigation().stop();
            return true;
        }

        villager.getNavigation().moveTo(
                target.getX() + 0.5D,
                target.getY(),
                target.getZ() + 0.5D,
                speed
        );

        return false;
    }

    public static boolean isCurrentBodyClear(Villager villager) {
        BlockPos feet = villager.blockPosition();
        BlockPos head = feet.above();

        return villager.level().getBlockState(feet).isAir()
                && villager.level().getBlockState(head).isAir();
    }

    /**
     * 建筑师放方块前统一调用。
     *
     * 判断：
     * 1. 目标位置必须是空气；
     * 2. 目标位置不能是建筑师脚或头的位置；
     * 3. 目标位置不能有 LivingEntity，避免把玩家、村民、动物卡住。
     */
    public static boolean canPlaceBlockSafely(
            net.minecraft.server.level.ServerLevel level,
            Villager builder,
            BlockPos pos
    ) {
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }

        BlockPos builderFeet = builder.blockPosition();
        BlockPos builderHead = builderFeet.above();

        if (pos.equals(builderFeet) || pos.equals(builderHead)) {
            return false;
        }

        AABB blockBox = new AABB(pos);

        List<LivingEntity> entities = level.getEntitiesOfClass(
                LivingEntity.class,
                blockBox,
                entity -> entity.isAlive()
        );

        return entities.isEmpty();
    }

    public static String itemId(ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }

        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static boolean itemMatches(ItemStack stack, String itemName) {
        return itemId(stack).equals(itemName);
    }

    public static int countItems(SimpleContainer inventory, String itemName) {
        int count = 0;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);

            if (!stack.isEmpty() && itemMatches(stack, itemName)) {
                count += stack.getCount();
            }
        }

        return count;
    }
    public static int usedSlots(Villager villager) {
        SimpleContainer inventory = villager.getInventory();
        int used = 0;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!inventory.getItem(i).isEmpty()) {
                used++;
            }
        }

        return used;
    }

    public static int totalItems(Villager villager) {
        SimpleContainer inventory = villager.getInventory();
        int total = 0;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);

            if (!stack.isEmpty()) {
                total += stack.getCount();
            }
        }

        return total;
    }

    public static String getHomeText(Villager villager) {
        BlockPos warehouse = getWarehouseCenter(villager);
        BlockPos town = getTownCenter(villager);

        // 当前新架构里，小镇中心就是仓库中心。
        // 旧代码里 InventoryInteractHandler 还叫“工作点/大本营”，这里直接返回仓库中心，保持兼容。
        return "小镇中心 "
                + town.getX() + ", " + town.getY() + ", " + town.getZ()
                + " / 仓库 "
                + warehouse.getX() + ", " + warehouse.getY() + ", " + warehouse.getZ();
    }

    public static boolean hasItem(SimpleContainer inventory, String itemName) {
        return countItems(inventory, itemName) > 0;
    }

    public static boolean consumeOne(SimpleContainer inventory, String itemName) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);

            if (!stack.isEmpty() && itemMatches(stack, itemName)) {
                stack.shrink(1);
                inventory.setChanged();
                return true;
            }
        }

        return false;
    }

    public static int pickupNearbyItems(
            net.minecraft.server.level.ServerLevel level,
            Villager villager,
            BlockPos center,
            double horizontalRadius,
            double verticalRadius,
            java.util.function.Predicate<ItemStack> matcher
    ) {
        int pickedCount = 0;

        AABB searchBox = new AABB(center).inflate(
                horizontalRadius,
                verticalRadius,
                horizontalRadius
        );

        SimpleContainer inventory = villager.getInventory();

        for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, searchBox)) {
            ItemStack groundStack = itemEntity.getItem();

            if (groundStack.isEmpty()) {
                continue;
            }

            if (!matcher.test(groundStack)) {
                continue;
            }

            ItemStack moving = groundStack.copy();
            ItemStack remaining = inventory.addItem(moving);

            int moved = groundStack.getCount() - remaining.getCount();

            if (moved <= 0) {
                continue;
            }

            groundStack.shrink(moved);

            if (groundStack.isEmpty()) {
                itemEntity.discard();
            } else {
                itemEntity.setItem(groundStack);
            }

            inventory.setChanged();
            pickedCount += moved;
        }

        if (pickedCount > 0) {
            level.playSound(
                    null,
                    villager.blockPosition(),
                    SoundEvents.ITEM_PICKUP,
                    SoundSource.NEUTRAL,
                    0.3F,
                    1.4F
            );
        }

        return pickedCount;
    }

    public static ItemEntity findNearestDroppedItem(
            net.minecraft.server.level.ServerLevel level,
            Villager villager,
            double radius,
            java.util.function.Predicate<ItemStack> matcher
    ) {
        AABB searchBox = villager.getBoundingBox().inflate(
                radius,
                3.0D,
                radius
        );

        ItemEntity bestItem = null;
        double bestDistance = Double.MAX_VALUE;

        for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, searchBox)) {
            ItemStack stack = itemEntity.getItem();

            if (stack.isEmpty()) {
                continue;
            }

            if (!matcher.test(stack)) {
                continue;
            }

            double distance = villager.distanceToSqr(itemEntity);

            if (distance < bestDistance) {
                bestDistance = distance;
                bestItem = itemEntity;
            }
        }

        return bestItem;
    }

    public static boolean moveToAndPickupNearestItem(
            net.minecraft.server.level.ServerLevel level,
            Villager villager,
            double searchRadius,
            double pickupRadius,
            java.util.function.Predicate<ItemStack> matcher,
            String targetAction
    ) {
        ItemEntity targetItem = findNearestDroppedItem(
                level,
                villager,
                searchRadius,
                matcher
        );

        // 附近没有需要捡的掉落物，说明清理完成。
        if (targetItem == null) {
            return true;
        }

        BlockPos itemPos = targetItem.blockPosition();

        setTargetPlace(villager, itemPos, targetAction);

        double distance = villager.distanceToSqr(targetItem);

        if (distance > pickupRadius * pickupRadius) {
            moveToTargetPlace(
                    villager,
                    pickupRadius * pickupRadius,
                    SPEED_NORMAL
            );

            return false;
        }

        pickupNearbyItems(
                level,
                villager,
                itemPos,
                pickupRadius,
                1.5D,
                matcher
        );

        return true;
    }

    /**
     * 从小镇仓库周围箱子里取物品。
     *
     * requests 表示需要取的物品和数量。
     * 方法只负责“怎么取”，不负责决定“为什么取”。
     */
    public static int takeItemsFromWarehouse(
            net.minecraft.server.level.ServerLevel level,
            Villager villager,
            BlockPos warehouseCenter,
            List<ItemRequest> requests,
            int radius
    ) {
        SimpleContainer inventory = villager.getInventory();
        int movedTotal = 0;

        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        for (ItemRequest request : requests) {
            int alreadyHas = countItems(inventory, request.itemName());
            int need = Math.max(0, request.count() - alreadyHas);

            if (need <= 0) {
                continue;
            }

            searchWarehouse:
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -3; dy <= 3; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        mPos.set(
                                warehouseCenter.getX() + dx,
                                warehouseCenter.getY() + dy,
                                warehouseCenter.getZ() + dz
                        );

                        if (!(level.getBlockEntity(mPos) instanceof Container chest)) {
                            continue;
                        }

                        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                            ItemStack chestStack = chest.getItem(slot);

                            if (chestStack.isEmpty() || !itemMatches(chestStack, request.itemName())) {
                                continue;
                            }

                            int toMove = Math.min(need, chestStack.getCount());
                            ItemStack moving = chestStack.copy();
                            moving.setCount(toMove);

                            ItemStack remaining = inventory.addItem(moving);
                            int moved = toMove - remaining.getCount();

                            if (moved <= 0) {
                                continue;
                            }

                            chestStack.shrink(moved);
                            chest.setChanged();
                            inventory.setChanged();

                            need -= moved;
                            movedTotal += moved;

                            if (need <= 0) {
                                break searchWarehouse;
                            }
                        }
                    }
                }
            }
        }

        if (movedTotal > 0) {
            level.playSound(
                    null,
                    villager.blockPosition(),
                    SoundEvents.ITEM_PICKUP,
                    SoundSource.NEUTRAL,
                    0.4F,
                    1.2F
            );
        }

        return movedTotal;
    }

    /**
     * 向小镇仓库周围箱子存物品。
     *
     * requests 表示允许存入的物品和最大数量。
     * 方法只负责“怎么存”，不负责工种规则。
     */
    public static int depositItemsToWarehouse(
            net.minecraft.server.level.ServerLevel level,
            Villager villager,
            BlockPos warehouseCenter,
            List<ItemRequest> requests,
            int radius
    ) {
        SimpleContainer inventory = villager.getInventory();
        int movedTotal = 0;

        for (ItemRequest request : requests) {
            int remainingToDeposit = request.count();

            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);

                if (stack.isEmpty() || !itemMatches(stack, request.itemName())) {
                    continue;
                }

                if (remainingToDeposit <= 0) {
                    break;
                }

                int moveCount = Math.min(stack.getCount(), remainingToDeposit);

                ItemStack moving = stack.copy();
                moving.setCount(moveCount);

                ItemStack notInserted = insertStackIntoWarehouse(level, warehouseCenter, moving, radius);
                int moved = moveCount - notInserted.getCount();

                if (moved <= 0) {
                    continue;
                }

                stack.shrink(moved);
                inventory.setChanged();

                remainingToDeposit -= moved;
                movedTotal += moved;
            }
        }

        if (movedTotal > 0) {
            level.playSound(
                    null,
                    villager.blockPosition(),
                    SoundEvents.ITEM_PICKUP,
                    SoundSource.NEUTRAL,
                    0.4F,
                    1.0F
            );
        }

        return movedTotal;
    }

    private static ItemStack insertStackIntoWarehouse(
            net.minecraft.server.level.ServerLevel level,
            BlockPos warehouseCenter,
            ItemStack stack,
            int radius
    ) {
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    mPos.set(
                            warehouseCenter.getX() + dx,
                            warehouseCenter.getY() + dy,
                            warehouseCenter.getZ() + dz
                    );

                    if (!(level.getBlockEntity(mPos) instanceof Container chest)) {
                        continue;
                    }

                    stack = insertIntoContainer(chest, stack);

                    if (stack.isEmpty()) {
                        return ItemStack.EMPTY;
                    }
                }
            }
        }

        return stack;
    }

    private static ItemStack insertIntoContainer(Container container, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slotStack = container.getItem(i);

            if (slotStack.isEmpty()) {
                continue;
            }

            if (slotStack.getItem() != stack.getItem()) {
                continue;
            }

            int maxSize = Math.min(slotStack.getMaxStackSize(), container.getMaxStackSize());
            int canMove = maxSize - slotStack.getCount();

            if (canMove <= 0) {
                continue;
            }

            int moved = Math.min(canMove, stack.getCount());
            slotStack.grow(moved);
            stack.shrink(moved);
            container.setChanged();

            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }

        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slotStack = container.getItem(i);

            if (!slotStack.isEmpty()) {
                continue;
            }

            int moved = Math.min(stack.getCount(), Math.min(stack.getMaxStackSize(), container.getMaxStackSize()));

            ItemStack placed = stack.copy();
            placed.setCount(moved);

            container.setItem(i, placed);
            stack.shrink(moved);
            container.setChanged();

            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }

        return stack;
    }

    public static void sendStatusToPlayer(Villager villager, net.minecraft.world.entity.player.Player player) {
        BlockPos town = getTownCenter(villager);
        BlockPos warehouse = getWarehouseCenter(villager);

        player.sendSystemMessage(Component.literal("§6===== 智能村民状态 ====="));
        player.sendSystemMessage(Component.literal("§e名字：§f" + getCitizenName(villager) + " §7(" + getCitizenId(villager) + ")"));
        player.sendSystemMessage(Component.literal("§e职业：§f" + roleDisplayName(getRole(villager))));
        player.sendSystemMessage(Component.literal("§e状态：§f" + getStatus(villager)));
        player.sendSystemMessage(Component.literal("§e任务：§f" + getTask(villager)));
        player.sendSystemMessage(Component.literal("§e小镇中心：§f" + town.getX() + ", " + town.getY() + ", " + town.getZ()));
        player.sendSystemMessage(Component.literal("§e仓库中心：§f" + warehouse.getX() + ", " + warehouse.getY() + ", " + warehouse.getZ()));
    }
}