package com.aitown.aitownmod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
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

    public static final String ROLE_HANDWORKER = "handworker";
    public static final String ROLE_FARMER = "farmer";
    public static final String ROLE_SHEPHERD = "shepherd";
    public static final String KEY_PLAYER_TOWN_VILLAGERS = "TownSmartVillagerIds";
    public static final String KEY_NAME = "CitizenName";
    public static final String KEY_ID = "CitizenId";
    public static final String KEY_ROLE = "SmartRole";
    public static final String KEY_STATUS = "WorkerStatus";
    public static final String KEY_TASK = "CurrentTask";

    public static final String KEY_COINS = "AITownCoins";
    public static final String KEY_TOTAL_EARNED = "AITownTotalEarned";
    public static final String KEY_TOTAL_SPENT = "AITownTotalSpent";
    public static final String KEY_WAREHOUSE_LOG = "WarehouseLog";

    public static final String KEY_HUNGER = "AITownHunger";
    public static final String KEY_LAST_HUNGER_TICK = "AITownLastHungerTick";
    public static final String KEY_DIARY = "AITownDiary";

    public static final int HUNGER_MAX = 100;
    public static final int HUNGER_INTERRUPT = 80;
    public static final int HUNGER_EAT_RECOVER = 30;
    public static final int HUNGER_TICK_INTERVAL = 600;
    public static final int DIARY_MAX_LINES = 20;

    public static final String KEY_TOWN_X = "TownCenterX";
    public static final String KEY_TOWN_Y = "TownCenterY";
    public static final String KEY_TOWN_Z = "TownCenterZ";

    public static final String KEY_WAREHOUSE_X = "WarehouseX";
    public static final String KEY_WAREHOUSE_Y = "WarehouseY";
    public static final String KEY_WAREHOUSE_Z = "WarehouseZ";

    public static final String KEY_MEMORY_HOME_X = "MemoryHomeX";
    public static final String KEY_MEMORY_HOME_Y = "MemoryHomeY";
    public static final String KEY_MEMORY_HOME_Z = "MemoryHomeZ";

    public static final String KEY_MEMORY_WORK_X = "MemoryWorkX";
    public static final String KEY_MEMORY_WORK_Y = "MemoryWorkY";
    public static final String KEY_MEMORY_WORK_Z = "MemoryWorkZ";

    public static final String KEY_MEMORY_CANTEEN_X = "MemoryCanteenX";
    public static final String KEY_MEMORY_CANTEEN_Y = "MemoryCanteenY";
    public static final String KEY_MEMORY_CANTEEN_Z = "MemoryCanteenZ";

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

    public static int getCoins(Villager villager) {
        return villager.getPersistentData().getInt(KEY_COINS).orElse(0);
    }

    public static int getTotalEarned(Villager villager) {
        return villager.getPersistentData().getInt(KEY_TOTAL_EARNED).orElse(0);
    }

    public static int getTotalSpent(Villager villager) {
        return villager.getPersistentData().getInt(KEY_TOTAL_SPENT).orElse(0);
    }

    public static void addCoins(Villager villager, int amount) {
        if (amount <= 0) {
            return;
        }

        int coins = getCoins(villager);
        int totalEarned = getTotalEarned(villager);

        villager.getPersistentData().putInt(KEY_COINS, coins + amount);
        villager.getPersistentData().putInt(KEY_TOTAL_EARNED, totalEarned + amount);
    }

    public static boolean spendCoins(Villager villager, int amount) {
        if (amount <= 0) {
            return true;
        }

        int coins = getCoins(villager);

        if (coins < amount) {
            return false;
        }

        int totalSpent = getTotalSpent(villager);

        villager.getPersistentData().putInt(KEY_COINS, coins - amount);
        villager.getPersistentData().putInt(KEY_TOTAL_SPENT, totalSpent + amount);

        return true;
    }

    public static String getRole(Villager villager) {
        return villager.getPersistentData().getString(KEY_ROLE).orElse(ROLE_NONE);
    }

    public static boolean isRole(Villager villager, String role) {
        return getRole(villager).equals(role);
    }

    public static String roleDisplayName(String role) {
        if (ROLE_BUILDER.equals(role)) {
            return "建筑工";
        }

        if (ROLE_LUMBERJACK.equals(role)) {
            return "伐木工";
        }
        if (ROLE_MINER.equals(role)) {
            return "采石工";
        }

        if (ROLE_HANDWORKER.equals(role)) {
            return "工匠师";
        }

        if (ROLE_FARMER.equals(role)) {
            return "农田工";
        }

        if (ROLE_SHEPHERD.equals(role)) {
            return "牧羊工";
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
        villager.getPersistentData().putBoolean("IsHandworker", ROLE_HANDWORKER.equals(role));
        villager.getPersistentData().putBoolean("IsFarmer", ROLE_FARMER.equals(role));
        villager.getPersistentData().putBoolean("IsShepherd", ROLE_SHEPHERD.equals(role));

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

    public static int getHunger(Villager villager) {
        return clamp(villager.getPersistentData().getInt(KEY_HUNGER).orElse(0), 0, HUNGER_MAX);
    }

    public static void setHunger(Villager villager, int value) {
        villager.getPersistentData().putInt(KEY_HUNGER, clamp(value, 0, HUNGER_MAX));
    }

    public static void addHunger(Villager villager, int amount) {
        if (amount == 0) {
            return;
        }

        setHunger(villager, getHunger(villager) + amount);
    }

    public static void reduceHunger(Villager villager, int amount) {
        if (amount <= 0) {
            return;
        }

        setHunger(villager, getHunger(villager) - amount);
    }

    public static void tickHunger(Villager villager) {
        if (!shouldThink(villager, HUNGER_TICK_INTERVAL)) {
            return;
        }

        int oldHunger = getHunger(villager);
        addHunger(villager, 1);

        if (oldHunger < HUNGER_INTERRUPT && getHunger(villager) >= HUNGER_INTERRUPT) {
            addDiary(villager, "我开始觉得很饿，需要找点东西吃。");
        }
    }

    /**
     * 饥饿中断逻辑。
     *
     * 返回 true 表示当前职业状态机应该暂停。
     * 当前版本只从仓库取 1 个苹果，吃掉后降低 Hunger，不会搬空仓库。
     */
    public static boolean tryHandleHunger(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        if (getHunger(villager) < HUNGER_INTERRUPT) {
            return false;
        }

        if (consumeOne(villager.getInventory(), "minecraft:apple")) {
            reduceHunger(villager, HUNGER_EAT_RECOVER);
            setStatus(villager, "吃东西", "吃了一个苹果，饥饿值下降到 " + getHunger(villager));
            addDiary(villager, "我吃了一个苹果，感觉没那么饿了。");
            return true;
        }

        BlockPos warehouse = getWarehouseCenter(villager);
        setStatus(villager, "寻找食物", "饥饿值 " + getHunger(villager) + "，去仓库找苹果");
        setTargetPlace(villager, warehouse, "hunger_find_food");

        boolean arrived = moveToTargetPlace(
                villager,
                PLACE_STORAGE,
                SPEED_NORMAL
        );

        if (!arrived) {
            return true;
        }

        int moved = takeItemsFromWarehouse(
                level,
                villager,
                warehouse,
                List.of(new ItemRequest("minecraft:apple", 1)),
                WAREHOUSE_RADIUS
        );

        if (moved <= 0) {
            setStatus(villager, "等待食物", "仓库没有苹果，暂时停止工作");
            return true;
        }

        if (consumeOne(villager.getInventory(), "minecraft:apple")) {
            reduceHunger(villager, HUNGER_EAT_RECOVER);
            setStatus(villager, "吃东西", "从仓库拿到苹果并吃掉，饥饿值下降到 " + getHunger(villager));
            addDiary(villager, "我从仓库拿到一个苹果并吃掉了。");
        }

        return true;
    }

    public static void addDiary(Villager villager, String line) {
        if (line == null || line.isBlank()) {
            return;
        }

        ensureIdentity(villager);

        // 当前 NeoForge / Minecraft 版本里 Level 不再直接暴露 getDayTime()。
        // 这里先用村民自身 tickCount 作为轻量时间戳，避免为了日记系统绑定世界时间 API。
        int time = villager.tickCount;

        String entry = "Tick " + time + " " + line;
        String oldDiary = villager.getPersistentData().getString(KEY_DIARY).orElse("");
        ArrayList<String> lines = new ArrayList<>();

        if (!oldDiary.isBlank()) {
            lines.addAll(Arrays.asList(oldDiary.split("\\n")));
        }

        lines.add(0, entry);

        while (lines.size() > DIARY_MAX_LINES) {
            lines.remove(lines.size() - 1);
        }

        villager.getPersistentData().putString(KEY_DIARY, String.join("\n", lines));
    }

    public static String getDiary(Villager villager) {
        return villager.getPersistentData().getString(KEY_DIARY).orElse("暂无日记");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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

    public static void setMemoryHome(Villager villager, BlockPos pos) {
        setMemoryPos(villager, KEY_MEMORY_HOME_X, KEY_MEMORY_HOME_Y, KEY_MEMORY_HOME_Z, pos);
    }

    public static void setMemoryWork(Villager villager, BlockPos pos) {
        setMemoryPos(villager, KEY_MEMORY_WORK_X, KEY_MEMORY_WORK_Y, KEY_MEMORY_WORK_Z, pos);
    }

    public static void setMemoryCanteen(Villager villager, BlockPos pos) {
        setMemoryPos(villager, KEY_MEMORY_CANTEEN_X, KEY_MEMORY_CANTEEN_Y, KEY_MEMORY_CANTEEN_Z, pos);
    }

    public static boolean hasMemoryWork(Villager villager) {
        return hasMemoryPos(villager, KEY_MEMORY_WORK_X, KEY_MEMORY_WORK_Y, KEY_MEMORY_WORK_Z);
    }

    public static BlockPos getMemoryWorkOrCurrent(Villager villager) {
        if (!hasMemoryWork(villager)) {
            return villager.blockPosition();
        }

        return getMemoryPos(villager, KEY_MEMORY_WORK_X, KEY_MEMORY_WORK_Y, KEY_MEMORY_WORK_Z);
    }

    private static void setMemoryPos(
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

    private static boolean hasMemoryPos(
            Villager villager,
            String keyX,
            String keyY,
            String keyZ
    ) {
        return villager.getPersistentData().contains(keyX)
                && villager.getPersistentData().contains(keyY)
                && villager.getPersistentData().contains(keyZ);
    }

    private static BlockPos getMemoryPos(
            Villager villager,
            String keyX,
            String keyY,
            String keyZ
    ) {
        return new BlockPos(
                villager.getPersistentData().getInt(keyX).orElse(villager.getBlockX()),
                villager.getPersistentData().getInt(keyY).orElse(villager.getBlockY()),
                villager.getPersistentData().getInt(keyZ).orElse(villager.getBlockZ())
        );
    }

    private static String memoryText(
            Villager villager,
            String keyX,
            String keyY,
            String keyZ
    ) {
        if (!hasMemoryPos(villager, keyX, keyY, keyZ)) {
            return "未设置";
        }

        BlockPos pos = getMemoryPos(villager, keyX, keyY, keyZ);
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    public static String getTargetText(Villager villager) {
        if (!hasTargetPlace(villager)) {
            return "未设置";
        }

        BlockPos target = getTargetPlace(villager);
        String action = villager.getPersistentData().getString(KEY_TARGET_ACTION).orElse("无动作");

        return target.getX() + ", " + target.getY() + ", " + target.getZ() + " / " + action;
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

    public static String displayItemName(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return "未知物品";
        }

        return switch (itemName) {
            case "minecraft:oak_log" -> "橡木原木";
            case "minecraft:oak_wood" -> "橡木";
            case "minecraft:oak_planks" -> "橡木板";
            case "minecraft:oak_fence" -> "橡木栅栏";
            case "minecraft:oak_fence_gate" -> "橡木栅栏门";
            case "minecraft:oak_stairs" -> "橡木楼梯";
            case "minecraft:oak_door" -> "橡木门";
            case "minecraft:white_bed" -> "白色床";
            case "minecraft:torch" -> "火把";
            case "minecraft:stick" -> "木棍";
            case "minecraft:coal" -> "煤炭";
            case "minecraft:cobblestone" -> "圆石";
            case "minecraft:stone" -> "石头";
            case "minecraft:oak_sapling" -> "橡树树苗";
            case "minecraft:wheat" -> "小麦";
            case "minecraft:wheat_seeds" -> "小麦种子";
            case "minecraft:apple" -> "苹果";
            case "minecraft:white_wool" -> "白色羊毛";
            case "minecraft:orange_wool" -> "橙色羊毛";
            case "minecraft:magenta_wool" -> "品红色羊毛";
            case "minecraft:light_blue_wool" -> "淡蓝色羊毛";
            case "minecraft:yellow_wool" -> "黄色羊毛";
            case "minecraft:lime_wool" -> "黄绿色羊毛";
            case "minecraft:pink_wool" -> "粉色羊毛";
            case "minecraft:gray_wool" -> "灰色羊毛";
            case "minecraft:light_gray_wool" -> "淡灰色羊毛";
            case "minecraft:cyan_wool" -> "青色羊毛";
            case "minecraft:purple_wool" -> "紫色羊毛";
            case "minecraft:blue_wool" -> "蓝色羊毛";
            case "minecraft:brown_wool" -> "棕色羊毛";
            case "minecraft:green_wool" -> "绿色羊毛";
            case "minecraft:red_wool" -> "红色羊毛";
            case "minecraft:black_wool" -> "黑色羊毛";
            default -> itemName;
        };
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

                        net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(mPos);

                        if (!(blockEntity instanceof Container chest) || !isWarehouseStorage(blockEntity)) {
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

                            recordWarehouseTransaction(
                                    level,
                                    villager,
                                    warehouseCenter,
                                    "取出",
                                    request.itemName(),
                                    moved,
                                    radius
                            );

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
        int earnedCoins = 0;

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
                earnedCoins += TradeValueRegistry.calculateValue(request.itemName(), moved);

                recordWarehouseTransaction(
                        level,
                        villager,
                        warehouseCenter,
                        "存入",
                        request.itemName(),
                        moved,
                        radius
                );
            }
        }

        if (earnedCoins > 0) {
            addCoins(villager, earnedCoins);
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

    private static boolean isWarehouseStorage(Object blockEntity) {
        return blockEntity instanceof net.minecraft.world.level.block.entity.ChestBlockEntity
                || blockEntity instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity;
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

                    net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(mPos);

                    if (!(blockEntity instanceof Container chest) || !isWarehouseStorage(blockEntity)) {
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

    public static int countWarehouseItem(
            net.minecraft.server.level.ServerLevel level,
            BlockPos warehouseCenter,
            String itemName,
            int radius
    ) {
        int count = 0;
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    mPos.set(
                            warehouseCenter.getX() + dx,
                            warehouseCenter.getY() + dy,
                            warehouseCenter.getZ() + dz
                    );

                    net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(mPos);

                    if (!(blockEntity instanceof Container chest) || !isWarehouseStorage(blockEntity)) {
                        continue;
                    }

                    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                        ItemStack stack = chest.getItem(slot);

                        if (!stack.isEmpty() && itemMatches(stack, itemName)) {
                            count += stack.getCount();
                        }
                    }
                }
            }
        }

        return count;
    }

    public static String getWarehouseTrackedSummary(
            net.minecraft.server.level.ServerLevel level,
            BlockPos warehouseCenter
    ) {
        String[] trackedItems = new String[]{
                "minecraft:oak_log",
                "minecraft:oak_planks",
                "minecraft:oak_fence",
                "minecraft:oak_fence_gate",
                "minecraft:oak_door",
                "minecraft:white_bed",
                "minecraft:torch",
                "minecraft:stick",
                "minecraft:coal",
                "minecraft:cobblestone",
                "minecraft:stone",
                "minecraft:oak_sapling",
                "minecraft:wheat",
                "minecraft:wheat_seeds",
                "minecraft:apple",
                "minecraft:white_wool",
                "minecraft:black_wool",
                "minecraft:gray_wool",
                "minecraft:brown_wool"
        };

        StringBuilder builder = new StringBuilder();

        for (String itemName : trackedItems) {
            int count = countWarehouseItem(level, warehouseCenter, itemName, WAREHOUSE_RADIUS);

            if (count <= 0) {
                continue;
            }

            if (builder.length() > 0) {
                builder.append("，");
            }

            builder.append(displayItemName(itemName)).append(" x").append(count);
        }

        if (builder.length() == 0) {
            return "暂无已追踪物品";
        }

        return builder.toString();
    }

    public static String getWarehouseFullSummary(
            net.minecraft.server.level.ServerLevel level,
            BlockPos warehouseCenter,
            int maxEntries
    ) {
        LinkedHashMap<String, Integer> itemCounts = new LinkedHashMap<>();
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        for (int dx = -WAREHOUSE_RADIUS; dx <= WAREHOUSE_RADIUS; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -WAREHOUSE_RADIUS; dz <= WAREHOUSE_RADIUS; dz++) {
                    mPos.set(
                            warehouseCenter.getX() + dx,
                            warehouseCenter.getY() + dy,
                            warehouseCenter.getZ() + dz
                    );

                    net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(mPos);

                    if (!(blockEntity instanceof Container chest) || !isWarehouseStorage(blockEntity)) {
                        continue;
                    }

                    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                        ItemStack stack = chest.getItem(slot);

                        if (stack.isEmpty()) {
                            continue;
                        }

                        String itemName = itemId(stack);
                        itemCounts.put(itemName, itemCounts.getOrDefault(itemName, 0) + stack.getCount());
                    }
                }
            }
        }

        if (itemCounts.isEmpty()) {
            return "仓库为空或没有找到箱子";
        }

        StringBuilder builder = new StringBuilder();
        int shown = 0;

        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            if (shown > 0) {
                builder.append("，");
            }

            builder.append(displayItemName(entry.getKey())).append(" x").append(entry.getValue());
            shown++;

            if (shown >= maxEntries) {
                int remainingTypes = itemCounts.size() - shown;

                if (remainingTypes > 0) {
                    builder.append("，等 ").append(itemCounts.size()).append(" 类");
                }

                break;
            }
        }

        return builder.toString();
    }

    public static String getWarehouseLog(Villager villager) {
        return villager.getPersistentData().getString(KEY_WAREHOUSE_LOG).orElse("暂无记录");
    }

    private static void recordWarehouseTransaction(
            net.minecraft.server.level.ServerLevel level,
            Villager villager,
            BlockPos warehouseCenter,
            String action,
            String itemName,
            int moved,
            int radius
    ) {
        if (moved <= 0) {
            return;
        }

        ensureIdentity(villager);

        int remaining = countWarehouseItem(level, warehouseCenter, itemName, radius);

        String line = "T" + villager.tickCount
                + " " + getCitizenName(villager)
                + " " + action
                + " " + displayItemName(itemName)
                + " x" + moved
                + "，仓库剩余 x" + remaining;

        String oldLog = villager.getPersistentData().getString(KEY_WAREHOUSE_LOG).orElse("");
        ArrayList<String> lines = new ArrayList<>();

        if (!oldLog.isBlank()) {
            lines.addAll(Arrays.asList(oldLog.split("\\n")));
        }

        lines.add(0, line);

        while (lines.size() > 20) {
            lines.remove(lines.size() - 1);
        }

        villager.getPersistentData().putString(KEY_WAREHOUSE_LOG, String.join("\n", lines));
    }

    public static void sendStatusToPlayer(Villager villager, net.minecraft.world.entity.player.Player player) {
        BlockPos town = getTownCenter(villager);
        BlockPos warehouse = getWarehouseCenter(villager);

        player.sendSystemMessage(Component.literal("§6===== 智能村民状态 ====="));
        player.sendSystemMessage(Component.literal("§e名字：§f" + getCitizenName(villager) + " §7(" + getCitizenId(villager) + ")"));
        player.sendSystemMessage(Component.literal("§e职业：§f" + roleDisplayName(getRole(villager))));
        player.sendSystemMessage(Component.literal("§e状态：§f" + getStatus(villager)));
        player.sendSystemMessage(Component.literal("§e任务：§f" + getTask(villager)));
        player.sendSystemMessage(Component.literal("§e饥饿：§f" + getHunger(villager) + " / " + HUNGER_MAX));
        player.sendSystemMessage(Component.literal("§e金币：§f" + getCoins(villager) + " §7｜累计收入：" + getTotalEarned(villager) + " ｜累计支出：" + getTotalSpent(villager)));
        player.sendSystemMessage(Component.literal("§e小镇中心：§f" + town.getX() + ", " + town.getY() + ", " + town.getZ()));
        player.sendSystemMessage(Component.literal("§e仓库中心：§f" + warehouse.getX() + ", " + warehouse.getY() + ", " + warehouse.getZ()));
        player.sendSystemMessage(Component.literal("§e当前位置：§f" + villager.getBlockX() + ", " + villager.getBlockY() + ", " + villager.getBlockZ()));
        player.sendSystemMessage(Component.literal("§e当前目标：§f" + getTargetText(villager)));
        player.sendSystemMessage(Component.literal("§e记忆坐标："));
        player.sendSystemMessage(Component.literal("§7- 家：§f" + memoryText(villager, KEY_MEMORY_HOME_X, KEY_MEMORY_HOME_Y, KEY_MEMORY_HOME_Z)));
        player.sendSystemMessage(Component.literal("§7- 工作点：§f" + memoryText(villager, KEY_MEMORY_WORK_X, KEY_MEMORY_WORK_Y, KEY_MEMORY_WORK_Z)));
        player.sendSystemMessage(Component.literal("§7- 食堂：§f" + memoryText(villager, KEY_MEMORY_CANTEEN_X, KEY_MEMORY_CANTEEN_Y, KEY_MEMORY_CANTEEN_Z)));
        player.sendSystemMessage(Component.literal("§7- 仓库：§f" + warehouse.getX() + ", " + warehouse.getY() + ", " + warehouse.getZ()));

        String uuid = villager.getUUID().toString();
        Component setWorkHere = Component.literal("§a[把我的当前位置设为他的工作点]")
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.RunCommand(
                                "/aitown_set_work_here " + uuid
                        ))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal("点击后，把你当前站的位置记录为 " + getCitizenName(villager) + " 的工作点")
                        ))
                );
        player.sendSystemMessage(setWorkHere);

        player.sendSystemMessage(Component.literal("§e近日记："));

        String diary = getDiary(villager);
        if ("暂无日记".equals(diary)) {
            player.sendSystemMessage(Component.literal("§7- 暂无日记"));
        } else {
            String[] lines = diary.split("\\n");
            int shown = 0;

            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }

                player.sendSystemMessage(Component.literal("§7- " + line));
                shown++;

                if (shown >= 6) {
                    break;
                }
            }
        }
    }
}