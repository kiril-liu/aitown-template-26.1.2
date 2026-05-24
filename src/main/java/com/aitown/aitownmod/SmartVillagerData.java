package com.aitown.aitownmod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;

/**
 * v0.2.1 智能村民身份与节流核心。
 *
 * 这个类只负责：
 * 1. 这个智能村民是谁；
 * 2. 当前是什么职业；
 * 3. 当前是什么状态；
 * 4. 多久思考一次。
 */
public class SmartVillagerData {
    public static final String KEY_NAME = "CitizenName";
    public static final String KEY_ID = "CitizenId";
    public static final String KEY_ROLE = "SmartRole";
    public static final String KEY_STATUS = "WorkerStatus";

    // ==========================================
// AI Town 统一距离参数
// 注意：这些值都是“距离平方”，不是普通距离。
// 2.0D 约等于 1.4 格；4.0D 等于 2 格；64.0D 等于 8 格。
// ==========================================

    // 精确站位：用于逃离点、临时安全点等，需要比较精确地走到某个格子附近。
    public static final double REACH_EXACT_POS = 2.0D;

    // 捡掉落物：需要比较靠近掉落物。
    public static final double REACH_PICKUP = 4.0D;

    // 回家 / 靠近大本营 / 靠近仓库：不需要站得特别精确。
    public static final double REACH_HOME = 16.0D;

    // 建筑施工：目标是“够得到目标方块”，不是站到目标方块上。
    public static final double REACH_BUILD = 64.0D;

    // 伐木：目标是靠近树干并砍伐。
    public static final double REACH_CHOP = 36.0D;

    // 清理树叶：树叶经常在上方，所以距离比伐木稍微宽一点。
    public static final double REACH_LEAF = 49.0D;

    // 应急建造：当寻路/站位失败时使用，允许稍远距离兜底放置。
    public static final double REACH_EMERGENCY_BUILD = 100.0D;

    // 移动速度参数
    public static final double SPEED_WORK = 0.6D;
    public static final double SPEED_NORMAL = 0.55D;
    public static final double SPEED_SLOW = 0.5D;

    private static final String[] NAMES = new String[] {
            "阿木", "石头", "松果", "米粒", "橡子", "青砖", "河灯", "小麦",
            "灰灰", "阿桥", "木芽", "圆石", "小栗", "白桦", "土豆", "南瓜",
            "晨星", "暮雨", "云杉", "红砖", "苔苔", "竹叶", "小溪", "炭炭"
    };

    /**
     * 第一次成为智能村民时，给它一个永久名字和短 ID。
     */
    public static void ensureIdentity(Villager villager) {
        if (!villager.getPersistentData().contains(KEY_NAME)) {
            String name = NAMES[villager.getRandom().nextInt(NAMES.length)];
            villager.getPersistentData().putString(KEY_NAME, name);
        }

        if (!villager.getPersistentData().contains(KEY_ID)) {
            String uuid = villager.getUUID().toString().replace("-", "");
            String shortId = "AIT-" + uuid.substring(0, 6).toUpperCase();
            villager.getPersistentData().putString(KEY_ID, shortId);
        }
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
        return villager.getPersistentData().getString(KEY_ROLE).orElse("未分配");
    }

    public static String getStatus(Villager villager) {
        return villager.getPersistentData().getString(KEY_STATUS).orElse("未知");
    }

    /**
     * 更新头顶名字。
     * 例：§e[施工中] 阿木
     */
    public static void setDisplayStatus(Villager villager, String color, String status, String role) {
        ensureIdentity(villager);

        villager.getPersistentData().putString(KEY_STATUS, status);
        villager.getPersistentData().putString(KEY_ROLE, role);

        villager.setCustomName(Component.literal(color + "[" + status + "] " + getCitizenName(villager)));
        villager.setCustomNameVisible(true);
    }

    /**
     * 让每个村民错峰思考，避免所有村民同一 tick 同时扫描世界。
     *
     * intervalTicks:
     * 10 = 约 0.5 秒
     * 20 = 约 1 秒
     * 40 = 约 2 秒
     * 100 = 约 5 秒
     */
    public static boolean shouldThink(Villager villager, int intervalTicks) {
        return intervalTicks <= 1 || ((villager.tickCount + villager.getId()) % intervalTicks == 0);
    }

    public static void setTargetPlace(Villager villager, BlockPos pos, String action) {
        villager.getPersistentData().putInt("TargetPlaceX", pos.getX());
        villager.getPersistentData().putInt("TargetPlaceY", pos.getY());
        villager.getPersistentData().putInt("TargetPlaceZ", pos.getZ());
        villager.getPersistentData().putString("TargetAction", action);
    }

    public static boolean hasTargetPlace(Villager villager) {
        return villager.getPersistentData().contains("TargetPlaceX");
    }

    public static BlockPos getTargetPlace(Villager villager) {
        if (!hasTargetPlace(villager)) {
            return villager.blockPosition();
        }

        int x = villager.getPersistentData().getInt("TargetPlaceX").orElse(villager.getBlockX());
        int y = villager.getPersistentData().getInt("TargetPlaceY").orElse(villager.getBlockY());
        int z = villager.getPersistentData().getInt("TargetPlaceZ").orElse(villager.getBlockZ());

        return new BlockPos(x, y, z);
    }

    public static String getTargetAction(Villager villager) {
        return villager.getPersistentData().getString("TargetAction").orElse("none");
    }

    public static void clearTargetPlace(Villager villager) {
        villager.getPersistentData().remove("TargetPlaceX");
        villager.getPersistentData().remove("TargetPlaceY");
        villager.getPersistentData().remove("TargetPlaceZ");
        villager.getPersistentData().remove("TargetAction");

        clearTargetFail(villager);
    }

    /**
     * 统一移动到 target_place。
     *
     * 返回 true：已经足够接近目标，可以执行动作。
     * 返回 false：还没到，已经调用 Minecraft 寻路去接近目标。
     */
    public static boolean moveToTargetPlace(
            Villager villager,
            double reachSqr,
            double speed
    ) {
        if (!hasTargetPlace(villager)) {
            return false;
        }

        // 关键：先压制原版村民自由移动
        suppressVanillaMovement(villager);

        BlockPos target = getTargetPlace(villager);
        lookAtTargetPlace(villager);
        double distance = villager.distanceToSqr(
                target.getX() + 0.5D,
                target.getY() + 0.5D,
                target.getZ() + 0.5D
        );

        if (distance <= reachSqr) {
            // 到达目标附近后停止导航，避免继续乱走
            villager.getNavigation().stop();
            return true;
        }

        villager.getNavigation().moveTo(
                target.getX() + 0.5D,
                target.getY(),
                target.getZ() + 0.5D,
                speed
        );

        addTargetFail(villager, target);

        return false;
    }

    public static void addTargetFail(Villager villager, BlockPos target) {
        int lastX = villager.getPersistentData().getInt("TargetFailX").orElse(999999);
        int lastY = villager.getPersistentData().getInt("TargetFailY").orElse(999999);
        int lastZ = villager.getPersistentData().getInt("TargetFailZ").orElse(999999);

        boolean sameTarget =
                lastX == target.getX()
                        && lastY == target.getY()
                        && lastZ == target.getZ();

        if (!sameTarget) {
            villager.getPersistentData().putInt("TargetFailX", target.getX());
            villager.getPersistentData().putInt("TargetFailY", target.getY());
            villager.getPersistentData().putInt("TargetFailZ", target.getZ());
            villager.getPersistentData().putInt("TargetFailCount", 1);
        } else {
            int count = villager.getPersistentData().getInt("TargetFailCount").orElse(0);
            villager.getPersistentData().putInt("TargetFailCount", count + 1);
        }
    }

    public static int getTargetFailCount(Villager villager) {
        return villager.getPersistentData().getInt("TargetFailCount").orElse(0);
    }

    public static boolean shouldFallbackTargetAction(Villager villager, int failLimit) {
        return getTargetFailCount(villager) >= failLimit;
    }

    public static void clearTargetFail(Villager villager) {
        villager.getPersistentData().remove("TargetFailX");
        villager.getPersistentData().remove("TargetFailY");
        villager.getPersistentData().remove("TargetFailZ");
        villager.getPersistentData().remove("TargetFailCount");
    }

    public static int usedSlots(Villager villager) {
        SimpleContainer inv = villager.getInventory();
        int used = 0;

        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!inv.getItem(i).isEmpty()) {
                used++;
            }
        }

        return used;
    }

    public static int totalItems(Villager villager) {
        SimpleContainer inv = villager.getInventory();
        int total = 0;

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                total += stack.getCount();
            }
        }

        return total;
    }

    public static String getHomeText(Villager villager) {
        if (villager.getPersistentData().contains("HomeX")) {
            int x = villager.getPersistentData().getInt("HomeX").orElse(0);
            int y = villager.getPersistentData().getInt("HomeY").orElse(0);
            int z = villager.getPersistentData().getInt("HomeZ").orElse(0);
            return x + ", " + y + ", " + z;
        }

        if (villager.getPersistentData().contains("BuildCenterX")) {
            int x = villager.getPersistentData().getInt("BuildCenterX").orElse(0);
            int y = villager.getPersistentData().getInt("BuildCenterY").orElse(0);
            int z = villager.getPersistentData().getInt("BuildCenterZ").orElse(0);
            return x + ", " + y + ", " + z;
        }

        return "未设定";
    }

    public static void suppressVanillaMovement(Villager villager) {
        try {
            villager.getBrain().eraseMemory(
                    net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET
            );

            villager.getBrain().eraseMemory(
                    net.minecraft.world.entity.ai.memory.MemoryModuleType.INTERACTION_TARGET
            );
        } catch (Exception ignored) {
            // 有些版本/状态下 Brain memory 可能不可用，忽略即可
        }
    }

    public static void keepMovingToTargetPlace(
            Villager villager,
            double reachSqr,
            double speed
    ) {
        if (!hasTargetPlace(villager)) {
            return;
        }

        suppressVanillaMovement(villager);

        net.minecraft.core.BlockPos target = getTargetPlace(villager);

        lookAtTargetPlace(villager);

        double distance = villager.distanceToSqr(
                target.getX() + 0.5D,
                target.getY() + 0.5D,
                target.getZ() + 0.5D
        );

        // 关键：已经进入工作距离，就停下，不要继续往目标方块中心挤
        if (distance <= reachSqr) {
            villager.getNavigation().stop();
            return;
        }

        villager.getNavigation().moveTo(
                target.getX() + 0.5D,
                target.getY(),
                target.getZ() + 0.5D,
                speed
        );

    }
    public static void lookAtTargetPlace(Villager villager) {
        if (!hasTargetPlace(villager)) {
            return;
        }

        BlockPos target = getTargetPlace(villager);

        villager.getLookControl().setLookAt(
                target.getX() + 0.5D,
                target.getY() + 0.5D,
                target.getZ() + 0.5D,
                30.0F,
                30.0F
        );
    }

    public static net.minecraft.core.BlockPos findNearbySafeStandPos(
            Villager villager,
            net.minecraft.core.BlockPos center,
            int radius
    ) {
        net.minecraft.core.BlockPos.MutableBlockPos mPos =
                new net.minecraft.core.BlockPos.MutableBlockPos();

        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }

                    for (int dy = -2; dy <= 3; dy++) {
                        mPos.set(
                                center.getX() + dx,
                                center.getY() + dy,
                                center.getZ() + dz
                        );

                        if (isSafeStandPos(villager, mPos)) {
                            return mPos.immutable();
                        }
                    }
                }
            }
        }

        return null;
    }

    public static boolean isSafeStandPos(
            Villager villager,
            net.minecraft.core.BlockPos pos
    ) {
        // 脚下不能是空气
        if (villager.level().getBlockState(pos.below()).isAir()) {
            return false;
        }

        // 脚的位置必须为空
        if (!villager.level().getBlockState(pos).isAir()) {
            return false;
        }

        // 头的位置必须为空
        if (!villager.level().getBlockState(pos.above()).isAir()) {
            return false;
        }

        return true;
    }

}