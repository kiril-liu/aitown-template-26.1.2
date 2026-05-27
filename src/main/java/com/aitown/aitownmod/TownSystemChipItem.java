package com.aitown.aitownmod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
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
 * 3. Shift + 右键村民：
 *    打开职业选择菜单。点击聊天栏里的职业后，才会正式注册并设置职业。
 *
 * 4. 右键空气：
 *    展开当前玩家通过小镇系统芯片注册过的智能村民列表。
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
     * 右键村民：查看状态或打开职业选择菜单。
     *
     * 普通右键：查看单个智能村民状态。
     * Shift + 右键：显示职业选择 clickable text。
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

        sendRoleSelectionMenu(player, villager);
        return InteractionResult.SUCCESS;
    }

    private static void sendRoleSelectionMenu(Player player, Villager villager) {
        String name = SmartVillagerData.getCitizenName(villager);
        String uuid = villager.getUUID().toString();

        player.sendSystemMessage(Component.literal("§6========== 选择智能村民职业 =========="));
        player.sendSystemMessage(Component.literal("§e村民：§f" + name));
        player.sendSystemMessage(Component.literal("§7点击下面的职业后，会把它注册到当前小镇，并设置为该职业。"));

        player.sendSystemMessage(Component.empty()
                .append(roleButton(uuid, SmartVillagerData.ROLE_BUILDER, "建筑工"))
                .append(Component.literal("  "))
                .append(roleButton(uuid, SmartVillagerData.ROLE_LUMBERJACK, "伐木工"))
                .append(Component.literal("  "))
                .append(roleButton(uuid, SmartVillagerData.ROLE_MINER, "采石工"))
        );

        player.sendSystemMessage(Component.empty()
                .append(roleButton(uuid, SmartVillagerData.ROLE_HANDWORKER, "工匠师"))
                .append(Component.literal("  "))
                .append(roleButton(uuid, SmartVillagerData.ROLE_FARMER, "农田工"))
                .append(Component.literal("  "))
                .append(roleButton(uuid, SmartVillagerData.ROLE_SHEPHERD, "牧羊工"))
        );

        player.sendSystemMessage(Component.literal("§6===================================="));
    }

    private static Component roleButton(String uuid, String role, String label) {
        return Component.literal("§a[" + label + "]")
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.RunCommand(
                                "/aitown_set_role " + uuid + " " + role
                        ))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal("点击后设置为 " + label)
                        ))
                );
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
     * 内容尽量短：名字、职业、饥饿值和详情入口。
     * 当前状态、当前任务、等待材料、位置等信息全部放进点击后的详情里。
     */
    private static void sendRegisteredVillagerSummary(
            Player player,
            Villager villager
    ) {
        SmartVillagerData.ensureIdentity(villager);

        String name = SmartVillagerData.getCitizenName(villager);
        String role = SmartVillagerData.roleDisplayName(SmartVillagerData.getRole(villager));
        String uuid = villager.getUUID().toString();

        Component clickableLine = Component.literal(
                "§e- " + name
                        + " §7[" + role + "]"
                        + " §7｜饥饿：§f" + SmartVillagerData.getHunger(villager)
                        + " §a[详情]"
        ).withStyle(style -> style
                .withClickEvent(new ClickEvent.RunCommand(
                        "/aitown_villager " + uuid
                ))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.literal("点击查看 " + name + " 的详细状态和日记")
                ))
        );

        player.sendSystemMessage(clickableLine);
    }

    private static int extractLogTick(String line) {
        if (line == null || !line.startsWith("T")) {
            return 0;
        }

        int spaceIndex = line.indexOf(' ');
        if (spaceIndex <= 1) {
            return 0;
        }

        try {
            return Integer.parseInt(line.substring(1, spaceIndex));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String stripLogTick(String line) {
        if (line == null || !line.startsWith("T")) {
            return line;
        }

        int spaceIndex = line.indexOf(' ');
        if (spaceIndex <= 0 || spaceIndex >= line.length() - 1) {
            return line;
        }

        return line.substring(spaceIndex + 1);
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

        player.sendSystemMessage(Component.literal("§e最近 20 条仓库流水："));

        java.util.ArrayList<String> recentLogs = new java.util.ArrayList<>();

        for (java.util.UUID uuid : villagerIds) {
            if (!(level.getEntity(uuid) instanceof Villager villager)) {
                continue;
            }

            String warehouseLog = SmartVillagerData.getWarehouseLog(villager);

            if ("暂无记录".equals(warehouseLog)) {
                continue;
            }

            String[] lines = warehouseLog.split("\\n");

            for (String line : lines) {
                if (!line.isBlank()) {
                    recentLogs.add(line);
                }
            }
        }

        recentLogs.sort((a, b) -> Integer.compare(extractLogTick(b), extractLogTick(a)));

        if (recentLogs.isEmpty()) {
            player.sendSystemMessage(Component.literal("§7暂无仓库存取记录。"));
        } else {
            int shown = 0;

            for (String line : recentLogs) {
                player.sendSystemMessage(Component.literal("§7- " + stripLogTick(line)));
                shown++;

                if (shown >= 20) {
                    break;
                }
            }
        }

        player.sendSystemMessage(Component.literal("§8说明：当前流水会合并所有已注册且已加载的智能村民记录，并按最近时间排序显示。"));
        player.sendSystemMessage(Component.literal("§6================================"));
    }
}