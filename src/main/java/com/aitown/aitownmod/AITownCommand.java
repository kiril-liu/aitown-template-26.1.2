package com.aitown.aitownmod;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.UUID;

/**
 * 小镇系统命令。
 *
 * 当前用途：
 * 1. 聊天栏 clickable text 执行 /aitown_villager <uuid>，查看某个智能村民详情。
 * 2. 详情里的 clickable text 执行 /aitown_set_work_here <uuid>，把玩家当前位置记录为该村民的工作点。
 * 3. 职业选择 clickable text 执行 /aitown_set_role <uuid> <role>，注册村民并直接设置职业。
 *
 * 注意：这些命令主要给 clickable text 使用，不作为正式玩法里的公开调试入口。
 */
@EventBusSubscriber(modid = aitown.MODID)
public class AITownCommand {
    private static final String PLAYER_TOWN_X = "SelectedTownX";
    private static final String PLAYER_TOWN_Y = "SelectedTownY";
    private static final String PLAYER_TOWN_Z = "SelectedTownZ";

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("aitown_villager")
                        .then(Commands.argument("uuid", StringArgumentType.word())
                                .executes(context -> showVillagerDetail(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "uuid")
                                )))
        );

        event.getDispatcher().register(
                Commands.literal("aitown_set_work_here")
                        .then(Commands.argument("uuid", StringArgumentType.word())
                                .executes(context -> setWorkHere(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "uuid")
                                )))
        );

        event.getDispatcher().register(
                Commands.literal("aitown_set_role")
                        .then(Commands.argument("uuid", StringArgumentType.word())
                                .then(Commands.argument("role", StringArgumentType.word())
                                        .executes(context -> setRole(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "uuid"),
                                                StringArgumentType.getString(context, "role")
                                        ))))
        );

        event.getDispatcher().register(
                Commands.literal("aitown_warehouse")
                        .executes(context -> showWarehousePanel(context.getSource()))
        );

        event.getDispatcher().register(
                Commands.literal("aitown_houses")
                        .executes(context -> showHousePanel(context.getSource()))
        );

        event.getDispatcher().register(
                Commands.literal("aitown_trades")
                        .executes(context -> showTradePanel(context.getSource()))
        );
    }

    private static int setRole(CommandSourceStack source, String uuidText, String role) {
        try {
            if (!(source.getEntity() instanceof Player player)) {
                source.sendFailure(Component.literal("这个操作只能由玩家点击执行。"));
                return 0;
            }

            if (!isAllowedRole(role)) {
                source.sendFailure(Component.literal("未知职业：" + role));
                return 0;
            }

            if (!hasSelectedTown(player)) {
                source.sendFailure(Component.literal("还没有设置小镇中心。请先用小镇系统芯片 Shift + 右键点击仓库中心方块。"));
                return 0;
            }

            UUID uuid = UUID.fromString(uuidText);
            Entity entity = source.getLevel().getEntity(uuid);

            if (!(entity instanceof Villager villager)) {
                source.sendFailure(Component.literal("找不到这个智能村民，可能它不在当前加载区域。"));
                return 0;
            }

            BlockPos townCenter = getSelectedTown(player);
            SmartVillagerData.ensureIdentity(villager);
            SmartVillagerData.setTownAndWarehouse(villager, townCenter);
            SmartVillagerData.setRole(villager, role);
            SmartVillagerData.registerSmartVillagerToPlayerTown(player, villager);
            SmartVillagerData.addDiary(villager, "我被登记为 " + SmartVillagerData.roleDisplayName(role) + "，正式加入了小镇。所绑定的小镇中心是 "
                    + townCenter.getX() + ", " + townCenter.getY() + ", " + townCenter.getZ() + "。");

            source.sendSuccess(() -> Component.literal(
                    "已将 "
                            + SmartVillagerData.getCitizenName(villager)
                            + " 设置为："
                            + SmartVillagerData.roleDisplayName(role)
                            + "，并注册到当前小镇。"
            ), false);

            SmartVillagerData.sendStatusToPlayer(villager, player);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("设置职业失败，村民 UUID 可能无效：" + uuidText));
            return 0;
        }
    }

    private static boolean isAllowedRole(String role) {
        return SmartVillagerData.ROLE_BUILDER.equals(role)
                || SmartVillagerData.ROLE_LUMBERJACK.equals(role)
                || SmartVillagerData.ROLE_MINER.equals(role)
                || SmartVillagerData.ROLE_HANDWORKER.equals(role)
                || SmartVillagerData.ROLE_FARMER.equals(role)
                || SmartVillagerData.ROLE_SHEPHERD.equals(role)
                || SmartVillagerData.ROLE_RESIDENT.equals(role);
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

    private static int setWorkHere(CommandSourceStack source, String uuidText) {
        try {
            if (!(source.getEntity() instanceof Player player)) {
                source.sendFailure(Component.literal("这个操作只能由玩家点击执行。"));
                return 0;
            }

            UUID uuid = UUID.fromString(uuidText);
            Entity entity = source.getLevel().getEntity(uuid);

            if (!(entity instanceof Villager villager)) {
                source.sendFailure(Component.literal("找不到这个智能村民，可能它不在当前加载区域。"));
                return 0;
            }

            if (SmartVillagerData.ROLE_NONE.equals(SmartVillagerData.getRole(villager))) {
                source.sendFailure(Component.literal("这个村民还不是智能村民。"));
                return 0;
            }

            BlockPos pos = player.blockPosition();
            SmartVillagerData.setMemoryWork(villager, pos);
            SmartVillagerData.addDiary(villager, "我的工作点被重新设置到了 "
                    + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "。");

            source.sendSuccess(() -> Component.literal(
                    "已将 "
                            + SmartVillagerData.getCitizenName(villager)
                            + " 的工作点设置为："
                            + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
            ), false);

            SmartVillagerData.sendStatusToPlayer(villager, player);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("村民 UUID 无效：" + uuidText));
            return 0;
        }
    }

    private static int showWarehousePanel(CommandSourceStack source) {
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("这个操作只能由玩家点击执行。"));
            return 0;
        }

        if (!hasSelectedTown(player)) {
            source.sendFailure(Component.literal("还没有设置小镇中心。"));
            return 0;
        }

        TownSystemChipItem.showWarehouseStatusPanel(source.getLevel(), player);
        return 1;
    }

    private static int showHousePanel(CommandSourceStack source) {
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("这个操作只能由玩家点击执行。"));
            return 0;
        }

        if (!hasSelectedTown(player)) {
            source.sendFailure(Component.literal("还没有设置小镇中心。"));
            return 0;
        }

        TownSystem.showHouseInfo(source.getLevel(), player, getSelectedTown(player));
        return 1;
    }

    private static int showTradePanel(CommandSourceStack source) {
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("这个操作只能由玩家点击执行。"));
            return 0;
        }

        if (!hasSelectedTown(player)) {
            source.sendFailure(Component.literal("还没有设置小镇中心。"));
            return 0;
        }

        TownSystem.showTradeInfo(source.getLevel(), player, getSelectedTown(player));
        return 1;
    }

    private static int showVillagerDetail(CommandSourceStack source, String uuidText) {
        try {
            UUID uuid = UUID.fromString(uuidText);
            Entity entity = source.getLevel().getEntity(uuid);

            if (!(entity instanceof Villager villager)) {
                source.sendFailure(Component.literal("找不到这个智能村民，可能它不在当前加载区域。"));
                return 0;
            }

            if (SmartVillagerData.ROLE_NONE.equals(SmartVillagerData.getRole(villager))) {
                source.sendFailure(Component.literal("这个村民还不是智能村民。"));
                return 0;
            }

            if (source.getEntity() instanceof Player player) {
                SmartVillagerData.sendStatusToPlayer(villager, player);
            } else {
                source.sendSuccess(() -> Component.literal(
                        SmartVillagerData.getCitizenName(villager)
                                + " ["
                                + SmartVillagerData.roleDisplayName(SmartVillagerData.getRole(villager))
                                + "] "
                                + SmartVillagerData.getStatus(villager)
                                + " / Hunger "
                                + SmartVillagerData.getHunger(villager)
                ), false);
            }

            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("村民 UUID 无效：" + uuidText));
            return 0;
        }
    }
}