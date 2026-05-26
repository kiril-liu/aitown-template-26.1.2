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

/**
 * 小镇系统芯片。
 *
 * 第一版不做 GUI，先用最小闭环：
 *
 * 1. Shift + 右键方块：
 *    设置当前玩家选择的小镇中心 / 仓库中心。
 *
 * 2. 普通右键智能村民：
 *    查看该村民状态。
 *
 * 3. Shift + 右键智能村民：
 *    将该村民注入小镇系统，并在 建筑师 / 伐木工 之间循环切换。
 */
public class TownSystemChipItem extends Item {
    private static final String PLAYER_TOWN_X = "SelectedTownX";
    private static final String PLAYER_TOWN_Y = "SelectedTownY";
    private static final String PLAYER_TOWN_Z = "SelectedTownZ";

    public TownSystemChipItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();

        if (player == null) {
            return InteractionResult.PASS;
        }

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
        // 建筑师 -> 伐木工 -> 矿工 -> 建筑师
        if (SmartVillagerData.ROLE_BUILDER.equals(currentRole)) {
            nextRole = SmartVillagerData.ROLE_LUMBERJACK;
        } else if (SmartVillagerData.ROLE_LUMBERJACK.equals(currentRole)) {
            nextRole = SmartVillagerData.ROLE_MINER;
        } else {
            nextRole = SmartVillagerData.ROLE_BUILDER;
        }

        SmartVillagerData.setRole(villager, nextRole);

        // 注册到当前玩家的小镇智能村民列表。
        // 之后右键空气显示小镇状态时，就不需要扫描附近村民了。
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

        int usedSlots = SmartVillagerData.usedSlots(villager);
        int totalItems = SmartVillagerData.totalItems(villager);

        String inventory = SmartVillagerData.getInventorySummary(villager, 4);

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
                        + " §7｜背包：§f"
                        + usedSlots + " 格 / "
                        + totalItems + " 个"
        ));

        player.sendSystemMessage(Component.literal(
                "  §7物品：§f" + inventory
        ));
    }
}