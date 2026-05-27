package com.aitown.aitownmod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 小镇系统芯片。
 *
 * 第一版不做 GUI，先用聊天栏完成小镇系统最小闭环：
 *
 * 1. Shift + 右键方块：
 *    设置当前玩家选择的小镇中心 / 仓库中心。
 *
 * 2. 普通右键智能村民：
 *    查看该村民状态。
 *
 * 3. Shift + 右键智能村民：
 *    将该村民注入小镇系统，并在 建筑师 / 伐木工 / 矿工 / 手工业者 之间循环切换。
 *
 * 4. 右键空气：
 *    展开当前玩家通过小镇系统芯片注册过的智能村民列表，显示他们的状态、任务和位置。
 *
 * 5. Shift + 右键空气：
 *    查看当前仓库库存和最近仓库存取记录。
 */
public class TownSystemChipItem extends Item {
    private static final String PLAYER_TOWN_X = "SelectedTownX";
    private static final String PLAYER_TOWN_Y = "SelectedTownY";
    private static final String PLAYER_TOWN_Z = "SelectedTownZ";

    public TownSystemChipItem(Properties properties) {
        super(properties);
    }

    /**
     * 右键空气时触发。
     *
     * 注意：
     * - useOn(...) 处理右键方块。
     * - interactLivingEntity(...) 处理右键实体。
     * - use(...) 处理右键空气。
     *
     * 普通右键空气显示村民状态。
     * Shift + 右键空气显示仓库库存和仓库流水。
     */
    @Override
    public InteractionResult use(
            Level level,
            Player player,
            InteractionHand hand
    ) {
        // 客户端只返回成功，不在客户端读取或修改小镇数据。
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            showWarehouseStatusPanel(serverLevel, player);
        } else {
            showRegisteredTownStatusPanel(serverLevel, player);
        }

        return InteractionResult.SUCCESS;
    }

    /**
     * Shift + 右键方块：设置小镇中心 / 仓库中心。
     *
     * 当前版本把小镇中心和仓库中心视为同一个位置。
     * 后续如果做独立仓库方块，可以再把这两个坐标拆开。
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();

        if (player == null) {
            return InteractionResult.PASS;
        }

        // 只在服务端写玩家 NBT，避免客户端重复写入。
        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (!player.isShiftKeyDown()) {
            player.sendSystemMessage(Component.literal(
                    "§e[小镇系统芯片] Shift + 右键方块：设置小镇中心 / 仓库中心。"
            ));
            return InteractionResult.SUCCESS;
        }

        BlockPos clickedPos = context.getClickedPos();

        player.getPersistentData().putInt(PLAYER_TOWN_X, clickedPos.getX());
        player.getPersistentData().putInt(PLAYER_TOWN_Y, clickedPos.getY());
        player.getPersistentData().putInt(PLAYER_TOWN_Z, clickedPos.getZ());

        player.sendSystemMessage(Component.literal(
                "§a[小镇系统芯片] 已设置小镇中心 / 仓库中心：§e"
                        + clickedPos.getX() + ", "
                        + clickedPos.getY() + ", "
                        + clickedPos.getZ()
        ));

        return InteractionResult.SUCCESS;
    }

    /**
     * 右键村民：查看或切换职业。
     *
     * 普通右键：查看单个智能村民状态。
     * Shift + 右键：把村民注册到小镇系统，并循环切换职业。
     */
    @Override
    public InteractionResult interactLivingEntity(
            ItemStack stack,
            Player player,
            LivingEntity target,
            InteractionHand hand
    ) {
        if (!(target instanceof Villager villager)) {
            return InteractionResult.PASS;
        }

        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        SmartVillagerData.ensureIdentity(villager);

        if (!player.isShiftKeyDown()) {
            SmartVillagerData.sendStatusToPlayer(villager, player);
            return InteractionResult.SUCCESS;
        }

        if (!hasSelectedTown(player)) {
            player.sendSystemMessage(Component.literal(
                    "§c[小镇系统芯片] 还没有设置小镇中心。请先 Shift + 右键点击仓库中心方块。"
            ));
            return InteractionResult.SUCCESS;
        }

        BlockPos townCenter = getSelectedTown(player);
        SmartVillagerData.setTownAndWarehouse(villager, townCenter);

        String currentRole = SmartVillagerData.getRole(villager);
        String nextRole;

        // 第一版不用 GUI，直接循环切换：
        // 建筑师 -> 伐木工 -> 矿工 -> 手工业者 -> 建筑师
        if (SmartVillagerData.ROLE_BUILDER.equals(currentRole)) {
            nextRole = SmartVillagerData.ROLE_LUMBERJACK;
        } else if (SmartVillagerData.ROLE_LUMBERJACK.equals(currentRole)) {
            nextRole = SmartVillagerData.ROLE_MINER;
        } else if (SmartVillagerData.ROLE_MINER.equals(currentRole)) {
            nextRole = SmartVillagerData.ROLE_HANDWORKER;
        } else {
            nextRole = SmartVillagerData.ROLE_BUILDER;
        }

        SmartVillagerData.setRole(villager, nextRole);

        // 注册到当前玩家的小镇智能村民列表。
        // 之后右键空气显示小镇状态时，就不需要扫描附近村民。
        SmartVillagerData.registerSmartVillagerToPlayerTown(player, villager);

        player.sendSystemMessage(Component.literal(
                "§a[小镇系统芯片] 已将 "
                        + SmartVillagerData.getCitizenName(villager)
                        + " 设置为：§e"
                        + SmartVillagerData.roleDisplayName(nextRole)
                        + "§7，并注册到小镇智能村民列表。"
        ));

        return InteractionResult.SUCCESS;
    }

    private static boolean hasSelectedTown(Player player) {
        return player.getPersistentData().contains(PLAYER_TOWN_X)
                && player.getPersistentData().contains(PLAYER_TOWN_Y)
                && player.getPersistentData().contains(PLAYER_TOWN_Z);
    }

    private static BlockPos getSelectedTown(Player player) {
        return new BlockPos(
                player.getPersistentData().getInt(PLAYER_TOWN_X).orElse(player.getBlockX()),
                player.getPersistentData().getInt(PLAYER_TOWN_Y).orElse(player.getBlockY()),
                player.getPersistentData().getInt(PLAYER_TOWN_Z).orElse(player.getBlockZ())
        );
    }

    /**
     * 展示当前玩家通过小镇系统芯片注册过的所有智能村民。
     *
     * 当前不扫描世界范围内的村民，而是直接读取玩家 NBT 中保存的 UUID 列表。
     * 这样可以保证面板只显示“这个小镇系统芯片管理过的智能村民”。
     */
    private static void showRegisteredTownStatusPanel(
            ServerLevel level,
            Player player
    ) {
        // 每次打开面板时，顺手清理已经死亡、卸载或失效的村民 ID。
        SmartVillagerData.cleanupRegisteredSmartVillagers(level, player);

        java.util.ArrayList<java.util.UUID> villagerIds =
                SmartVillagerData.getRegisteredSmartVillagerIds(player);

        player.sendSystemMessage(Component.literal("§6========== 小镇智能村民状态 =========="));

        if (villagerIds.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "§7当前还没有通过小镇系统芯片注册的智能村民。"
            ));
            player.sendSystemMessage(Component.literal("§6===================================="));
            return;
        }

        BlockPos selectedTown = getSelectedTown(player);

        player.sendSystemMessage(Component.literal(
                "§a已注册智能村民：§f" + villagerIds.size() + " 个"
        ));

        for (java.util.UUID uuid : villagerIds) {
            if (!(level.getEntity(uuid) instanceof Villager villager)) {
                player.sendSystemMessage(Component.literal(
                        "§8- 离线 / 未加载村民：§7" + uuid
                ));
                continue;
            }

            sendRegisteredVillagerSummary(player, villager);
        }

        player.sendSystemMessage(Component.literal("§6===================================="));
    }

    /**
     * 输出单个智能村民的摘要。
     *
     * 内容尽量短：名字、职业、状态、任务和位置。
     * 这里不再显示背包详情，避免信息太多。
     */
    private static void sendRegisteredVillagerSummary(
            Player player,
            Villager villager
    ) {
        SmartVillagerData.ensureIdentity(villager);

        String name = SmartVillagerData.getCitizenName(villager);
        String role = SmartVillagerData.roleDisplayName(SmartVillagerData.getRole(villager));
        String status = SmartVillagerData.getStatus(villager);
        String task = SmartVillagerData.getTask(villager);

        BlockPos pos = villager.blockPosition();

        player.sendSystemMessage(Component.literal(
                "§e- " + name
                        + " §7[" + role + "] "
                        + "§f" + status
                        + " §7｜§f" + task
        ));

        player.sendSystemMessage(Component.literal(
                "  §7位置：§f"
                        + pos.getX() + ", "
                        + pos.getY() + ", "
                        + pos.getZ()
        ));
    }

    /**
     * Shift + 右键空气：显示仓库库存和最近仓库流水。
     */
    private static void showWarehouseStatusPanel(
            ServerLevel level,
            Player player
    ) {
        if (!hasSelectedTown(player)) {
            player.sendSystemMessage(Component.literal(
                    "§c[小镇系统芯片] 还没有设置小镇中心。请先 Shift + 右键点击仓库中心方块。"
            ));
            return;
        }

        SmartVillagerData.cleanupRegisteredSmartVillagers(level, player);

        BlockPos selectedTown = getSelectedTown(player);
        java.util.ArrayList<java.util.UUID> villagerIds =
                SmartVillagerData.getRegisteredSmartVillagerIds(player);

        player.sendSystemMessage(Component.literal("§6========== 小镇仓库状态 =========="));
        player.sendSystemMessage(Component.literal(
                "§e仓库中心：§f"
                        + selectedTown.getX() + ", "
                        + selectedTown.getY() + ", "
                        + selectedTown.getZ()
        ));

        player.sendSystemMessage(Component.literal(
                "§b追踪库存：§f"
                        + SmartVillagerData.getWarehouseTrackedSummary(level, selectedTown)
        ));

        player.sendSystemMessage(Component.literal(
                "§b全部库存：§f"
                        + SmartVillagerData.getWarehouseFullSummary(level, selectedTown, 24)
        ));

        player.sendSystemMessage(Component.literal("§e最近仓库流水："));

        int workersWithLog = 0;

        for (java.util.UUID uuid : villagerIds) {
            if (!(level.getEntity(uuid) instanceof Villager villager)) {
                continue;
            }

            String warehouseLog = SmartVillagerData.getWarehouseLog(villager);

            if ("暂无记录".equals(warehouseLog)) {
                continue;
            }

            workersWithLog++;

            player.sendSystemMessage(Component.literal(
                    "§6- "
                            + SmartVillagerData.roleDisplayName(SmartVillagerData.getRole(villager))
                            + "/"
                            + SmartVillagerData.getCitizenName(villager)
                            + " 的记录："
            ));

            String[] lines = warehouseLog.split("\\n");
            int shownForWorker = 0;

            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }

                player.sendSystemMessage(Component.literal("  §7- " + line));
                shownForWorker++;

                if (shownForWorker >= 4) {
                    break;
                }
            }
        }

        if (workersWithLog == 0) {
            player.sendSystemMessage(Component.literal("§7暂无仓库存取记录。"));
        }

        player.sendSystemMessage(Component.literal("§8说明：当前流水记录暂时保存在每个执行操作的智能村民身上。"));
        player.sendSystemMessage(Component.literal("§8如果某个职业没有记录，通常说明它还没有通过统一仓库接口成功存取，或该村民未被芯片注册 / 当前未加载。"));
        player.sendSystemMessage(Component.literal("§6================================"));
    }
}