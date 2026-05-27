package com.aitown.aitownmod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * 小镇交易价值表 + 工人金币系统。
 *
 * 当前是最小闭环版本：
 * 1. 用固定价格表给常见资源定价。
 * 2. 工人把资源存入仓库后，可以根据物品数量获得金币。
 * 3. 金币直接写入村民 PersistentData，保证随村民一起保存。
 *
 * 后续可以扩展：
 * - 动态价格：仓库缺什么，什么涨价。
 * - 职业限制：伐木工只结算木材，矿工只结算石材。
 * - 每日生活开销：食物、住房、工具维护。
 * - 村民之间交易：食物、工具、住房服务。
 */
public class TradeValueRegistry {
    private static final String KEY_COINS = "AITownCoins";
    private static final String KEY_TOTAL_EARNED = "AITownTotalEarned";
    private static final String KEY_TOTAL_SPENT = "AITownTotalSpent";

    /**
     * 固定物品价值表。
     *
     * 单位：金币 / 个。
     * 当前先用简单整数，方便调试和观察。
     */
    private static final Map<String, Integer> ITEM_VALUES = new HashMap<>();

    static {
        // =========================================================
        // 伐木工产物
        // =========================================================
        register("minecraft:oak_log", 4);
        register("minecraft:oak_wood", 4);
        register("minecraft:spruce_log", 4);
        register("minecraft:birch_log", 4);
        register("minecraft:jungle_log", 4);
        register("minecraft:acacia_log", 4);
        register("minecraft:dark_oak_log", 4);
        register("minecraft:mangrove_log", 4);
        register("minecraft:cherry_log", 4);

        register("minecraft:oak_sapling", 1);
        register("minecraft:spruce_sapling", 1);
        register("minecraft:birch_sapling", 1);
        register("minecraft:apple", 3);
        register("minecraft:stick", 1);

        // =========================================================
        // 手工业者产物
        // =========================================================
        register("minecraft:oak_planks", 1);
        register("minecraft:torch", 3);

        // =========================================================
        // 矿工 / 采石工产物
        // =========================================================
        register("minecraft:cobblestone", 2);
        register("minecraft:stone", 3);
        register("minecraft:andesite", 2);
        register("minecraft:diorite", 2);
        register("minecraft:granite", 2);
        register("minecraft:tuff", 2);
        register("minecraft:calcite", 3);
        register("minecraft:cobbled_deepslate", 3);
        register("minecraft:deepslate", 4);

        // =========================================================
        // 后续矿物，先预留价格
        // =========================================================
        register("minecraft:coal", 5);
        register("minecraft:raw_iron", 12);
        register("minecraft:raw_copper", 8);
        register("minecraft:raw_gold", 16);
        register("minecraft:iron_ingot", 20);
        register("minecraft:copper_ingot", 12);
        register("minecraft:gold_ingot", 25);

        // =========================================================
        // 后续农民和食物系统，先预留价格
        // =========================================================
        register("minecraft:wheat", 2);
        register("minecraft:wheat_seeds", 1);
        register("minecraft:carrot", 2);
        register("minecraft:potato", 2);
        register("minecraft:beetroot", 2);
        register("minecraft:bread", 6);
        register("minecraft:baked_potato", 5);
    }

    private static void register(String itemName, int value) {
        ITEM_VALUES.put(itemName, value);
    }

    /**
     * 返回某个物品的单价。
     *
     * 未登记的物品价值为 0，不参与金币结算。
     */
    public static int getItemValue(String itemName) {
        return ITEM_VALUES.getOrDefault(itemName, 0);
    }

    public static int getItemValue(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }

        String itemName = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return getItemValue(itemName);
    }

    /**
     * 计算一组物品的总价值。
     */
    public static int calculateStackValue(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }

        return getItemValue(stack) * stack.getCount();
    }

    /**
     * 根据物品 ID 和数量计算总价值。
     *
     * 工人存入仓库时可以直接调用这个方法。
     */
    public static int calculateValue(String itemName, int count) {
        if (count <= 0) {
            return 0;
        }

        return getItemValue(itemName) * count;
    }

    // =========================================================
    // 金币系统
    // =========================================================

    public static int getCoins(Villager villager) {
        return villager.getPersistentData().getInt(KEY_COINS).orElse(0);
    }

    public static int getTotalEarned(Villager villager) {
        return villager.getPersistentData().getInt(KEY_TOTAL_EARNED).orElse(0);
    }

    public static int getTotalSpent(Villager villager) {
        return villager.getPersistentData().getInt(KEY_TOTAL_SPENT).orElse(0);
    }

    /**
     * 给工人发金币。
     *
     * 当前用于：
     * - 伐木工把木材存入仓库后获得报酬。
     * - 矿工把石材存入仓库后获得报酬。
     *
     * 后续可以扩展成 EconomyService，统一处理工资、税、生活开销。
     */
    public static void addCoins(Villager villager, int amount) {
        if (amount <= 0) {
            return;
        }

        int coins = getCoins(villager);
        int totalEarned = getTotalEarned(villager);

        villager.getPersistentData().putInt(KEY_COINS, coins + amount);
        villager.getPersistentData().putInt(KEY_TOTAL_EARNED, totalEarned + amount);
    }

    /**
     * 尝试花费金币。
     *
     * 返回 true：支付成功。
     * 返回 false：金币不足。
     */
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

    /**
     * 工人存货后的结算入口。
     *
     * 参数：
     * - villager：获得金币的工人。
     * - itemName：存入仓库的物品 ID，例如 minecraft:oak_log。
     * - count：实际成功存入仓库的数量。
     *
     * 返回值：本次获得的金币数量。
     */
    public static int rewardForDepositedItems(
            Villager villager,
            String itemName,
            int count
    ) {
        int reward = calculateValue(itemName, count);

        if (reward > 0) {
            addCoins(villager, reward);
        }

        return reward;
    }

    /**
     * 给玩家显示某个工人的金币信息。
     *
     * 这个方法用于调试和状态查看，不参与核心逻辑。
     */
    public static void sendCoinsStatusToPlayer(Villager villager, Player player) {
        player.sendSystemMessage(Component.literal(
                "§6[金币] §e"
                        + SmartVillagerData.getCitizenName(villager)
                        + " §f当前金币：§e"
                        + getCoins(villager)
                        + " §7｜累计收入："
                        + getTotalEarned(villager)
                        + " §7｜累计支出："
                        + getTotalSpent(villager)
        ));
    }
}