package com.aitown.aitownmod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 手工业者 v5。
 *
 * 核心原则：
 * 1. 平时待在金块工坊旁边，不在仓库旁边等。
 * 2. 到达工坊并等待一段时间后，才检查仓库成品库存。
 * 3. 仓库缺什么商品，就生产什么商品。
 * 4. 合成前先确认输出物品能放进背包，避免消耗原料后成品消失。
 * 5. 当前负责生产：火把、橡木木板、橡木楼梯、橡木栅栏、橡木栅栏门、橡木门、白色床。
 */
@EventBusSubscriber(modid = aitown.MODID)
public class HandworkerHandler {
    private static final String KEY_STATE = "HandworkerState";

    private static final String STATE_SEEK_WORKSHOP = "seek_workshop";
    private static final String STATE_WAIT_AT_WORKSHOP = "wait_at_workshop";
    private static final String STATE_FETCH_MATERIALS = "fetch_materials";
    private static final String STATE_MOVE_TO_WORKSHOP = "move_to_workshop";
    private static final String STATE_CRAFT_ITEMS = "craft_items";
    private static final String STATE_DEPOSIT_PRODUCTS = "deposit_products";

    private static final String KEY_WORKSHOP_X = "HandworkerWorkshopX";
    private static final String KEY_WORKSHOP_Y = "HandworkerWorkshopY";
    private static final String KEY_WORKSHOP_Z = "HandworkerWorkshopZ";
    private static final String KEY_PRODUCTS_SINCE_DIARY = "HandworkerProductsSinceDiary";

    private static final int WORKSHOP_SEARCH_RADIUS = 32;

    // 目标值设为 1，表示：仓库里完全没有这个商品时才生产。
    private static final int TARGET_TORCH_STOCK = 1;
    private static final int TARGET_PLANK_STOCK = 1;
    private static final int TARGET_STAIR_STOCK = 1;
    private static final int TARGET_FENCE_STOCK = 1;
    private static final int TARGET_FENCE_GATE_STOCK = 1;
    private static final int TARGET_DOOR_STOCK = 1;
    private static final int TARGET_BED_STOCK = 1;

    // 200 tick 约等于 10 秒，方便观察生产闭环。
    private static final int WORKSHOP_CHECK_INTERVAL_TICKS = 200;

    // 每次只拿一小批原料，避免把仓库基础材料一次性加工空。
    private static final int TARGET_STICKS = 16;
    private static final int TARGET_COAL = 8;
    private static final int TARGET_OAK_LOGS = 8;
    private static final int TARGET_OAK_PLANKS = 32;
    private static final int TARGET_WOOL = 3;

    private static final int MAX_TORCH_RECIPES_PER_WORK = 4;
    private static final int MAX_PLANK_RECIPES_PER_WORK = 8;
    private static final int MAX_STAIR_RECIPES_PER_WORK = 2;
    private static final int MAX_FENCE_RECIPES_PER_WORK = 4;
    private static final int MAX_FENCE_GATE_RECIPES_PER_WORK = 2;
    private static final int MAX_DOOR_RECIPES_PER_WORK = 1;
    private static final int MAX_BED_RECIPES_PER_WORK = 1;

    private static final String[] WOOL_ITEMS = new String[]{
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
    };

    @SubscribeEvent
    public static void onVillagerTick(EntityTickEvent.Pre event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }

        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }

        if (!SmartVillagerData.isRole(villager, SmartVillagerData.ROLE_HANDWORKER)) {
            return;
        }

        if (!(villager.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        SmartVillagerData.ensureIdentity(villager);
        SmartVillagerData.suppressVanillaMovement(villager);
        SmartVillagerData.tickHunger(villager);

        if (SmartVillagerData.tryHandleHunger(level, villager)) {
            return;
        }

        if (!SmartVillagerData.shouldThink(villager, 10)) {
            return;
        }

        String state = getState(villager);

        if (STATE_SEEK_WORKSHOP.equals(state)) {
            tickSeekWorkshop(level, villager);
            return;
        }

        if (STATE_WAIT_AT_WORKSHOP.equals(state)) {
            tickWaitAtWorkshop(level, villager);
            return;
        }

        if (STATE_FETCH_MATERIALS.equals(state)) {
            tickFetchMaterials(level, villager);
            return;
        }

        if (STATE_MOVE_TO_WORKSHOP.equals(state)) {
            tickMoveToWorkshop(level, villager);
            return;
        }

        if (STATE_CRAFT_ITEMS.equals(state)) {
            tickCraftItems(level, villager);
            return;
        }

        if (STATE_DEPOSIT_PRODUCTS.equals(state)) {
            tickDepositProducts(level, villager);
            return;
        }

        setState(villager, STATE_SEEK_WORKSHOP);
    }

    private static void tickSeekWorkshop(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        SmartVillagerData.setStatus(villager, "寻找工坊", "寻找附近的金块工作点");

        BlockPos workshop = findNearestGoldBlock(level, villager);

        if (workshop == null) {
            SmartVillagerData.setStatus(villager, "缺少工坊", "附近没有金块工作点");
            return;
        }

        savePos(villager, workshop);
        setState(villager, STATE_WAIT_AT_WORKSHOP);
    }

    private static void tickWaitAtWorkshop(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        BlockPos workshop = readWorkshop(villager);

        if (workshop == null || !level.getBlockState(workshop).is(Blocks.GOLD_BLOCK)) {
            setState(villager, STATE_SEEK_WORKSHOP);
            return;
        }

        BlockPos standPos = findStandPosNear(level, workshop);

        if (standPos == null) {
            SmartVillagerData.setStatus(villager, "工坊堵塞", "金块旁边没有可站位置");
            return;
        }

        SmartVillagerData.setTargetPlace(villager, standPos, "handworker_wait_at_workshop");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_WORK,
                SmartVillagerData.SPEED_SLOW
        );

        if (!arrived) {
            SmartVillagerData.setStatus(villager, "返回工坊", "先回到金块旁边，再等待检查仓库");
            return;
        }

        int torchStock = warehouseItemCount(level, villager, "minecraft:torch");
        int plankStock = warehouseItemCount(level, villager, "minecraft:oak_planks");
        int stairStock = warehouseItemCount(level, villager, "minecraft:oak_stairs");
        int fenceStock = warehouseItemCount(level, villager, "minecraft:oak_fence");
        int gateStock = warehouseItemCount(level, villager, "minecraft:oak_fence_gate");
        int doorStock = warehouseItemCount(level, villager, "minecraft:oak_door");
        int bedStock = warehouseItemCount(level, villager, "minecraft:white_bed");

        boolean needsProduction = torchStock < TARGET_TORCH_STOCK
                || plankStock < TARGET_PLANK_STOCK
                || stairStock < TARGET_STAIR_STOCK
                || fenceStock < TARGET_FENCE_STOCK
                || gateStock < TARGET_FENCE_GATE_STOCK
                || doorStock < TARGET_DOOR_STOCK
                || bedStock < TARGET_BED_STOCK;

        if (!needsProduction) {
            SmartVillagerData.setStatus(
                    villager,
                    "工坊待命",
                    "仓库还有商品 torch=" + torchStock
                            + " planks=" + plankStock
                            + " stairs=" + stairStock
                            + " fence=" + fenceStock
                            + " gate=" + gateStock
                            + " door=" + doorStock
                            + " bed=" + bedStock
                            + "，暂不生产"
            );
            return;
        }

        SmartVillagerData.setStatus(
                villager,
                "准备生产",
                "发现缺货 torch=" + torchStock
                        + " planks=" + plankStock
                        + " stairs=" + stairStock
                        + " fence=" + fenceStock
                        + " gate=" + gateStock
                        + " door=" + doorStock
                        + " bed=" + bedStock
        );

        if (!SmartVillagerData.shouldThink(villager, WORKSHOP_CHECK_INTERVAL_TICKS)) {
            return;
        }

        setState(villager, STATE_FETCH_MATERIALS);
    }

    private static void tickFetchMaterials(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        if (!needsProduction(level, villager)) {
            setState(villager, STATE_WAIT_AT_WORKSHOP);
            return;
        }

        if (canCraftNeededProduct(level, villager)) {
            setState(villager, STATE_MOVE_TO_WORKSHOP);
            return;
        }

        BlockPos warehouse = SmartVillagerData.getWarehouseCenter(villager);

        boolean needTorches = warehouseNeeds(level, villager, "minecraft:torch", TARGET_TORCH_STOCK);
        boolean needPlanks = warehouseNeeds(level, villager, "minecraft:oak_planks", TARGET_PLANK_STOCK);
        boolean needStairs = warehouseNeeds(level, villager, "minecraft:oak_stairs", TARGET_STAIR_STOCK);
        boolean needFences = warehouseNeeds(level, villager, "minecraft:oak_fence", TARGET_FENCE_STOCK);
        boolean needFenceGates = warehouseNeeds(level, villager, "minecraft:oak_fence_gate", TARGET_FENCE_GATE_STOCK);
        boolean needDoors = warehouseNeeds(level, villager, "minecraft:oak_door", TARGET_DOOR_STOCK);
        boolean needBeds = warehouseNeeds(level, villager, "minecraft:white_bed", TARGET_BED_STOCK);

        SmartVillagerData.setStatus(
                villager,
                "仓库取材",
                "缺货 torch=" + needTorches
                        + " planks=" + needPlanks
                        + " stairs=" + needStairs
                        + " fence=" + needFences
                        + " gate=" + needFenceGates
                        + " door=" + needDoors
                        + " bed=" + needBeds
        );

        SmartVillagerData.setTargetPlace(villager, warehouse, "handworker_fetch_materials");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_STORAGE,
                SmartVillagerData.SPEED_NORMAL
        );

        if (!arrived) {
            return;
        }

        SmartVillagerData.takeItemsFromWarehouse(
                level,
                villager,
                warehouse,
                buildMaterialRequests(needTorches, needPlanks, needStairs, needFences, needFenceGates, needDoors, needBeds),
                SmartVillagerData.WAREHOUSE_RADIUS
        );

        if (canCraftNeededProduct(level, villager)) {
            setState(villager, STATE_MOVE_TO_WORKSHOP);
        } else {
            SmartVillagerData.setStatus(
                    villager,
                    "仓库等原料",
                    "缺货商品需要生产，但原料不足；等待仓库补货"
            );
            setState(villager, STATE_FETCH_MATERIALS);
        }
    }

    private static void tickMoveToWorkshop(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        BlockPos workshop = readWorkshop(villager);

        if (workshop == null || !level.getBlockState(workshop).is(Blocks.GOLD_BLOCK)) {
            setState(villager, STATE_SEEK_WORKSHOP);
            return;
        }

        BlockPos standPos = findStandPosNear(level, workshop);

        if (standPos == null) {
            SmartVillagerData.setStatus(villager, "工坊堵塞", "金块旁边没有可站位置");
            return;
        }

        SmartVillagerData.setStatus(villager, "走向工坊", "带着原料回金块旁边加工");
        SmartVillagerData.setTargetPlace(villager, standPos, "handworker_workshop");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_WORK,
                SmartVillagerData.SPEED_NORMAL
        );

        if (arrived) {
            setState(villager, STATE_CRAFT_ITEMS);
        }
    }

    private static void tickCraftItems(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        SmartVillagerData.setStatus(villager, "手工合成", "制作建筑和生活用品");

        SimpleContainer inventory = villager.getInventory();
        boolean needTorches = warehouseNeeds(level, villager, "minecraft:torch", TARGET_TORCH_STOCK);
        boolean needPlanks = warehouseNeeds(level, villager, "minecraft:oak_planks", TARGET_PLANK_STOCK);
        boolean needStairs = warehouseNeeds(level, villager, "minecraft:oak_stairs", TARGET_STAIR_STOCK);
        boolean needFences = warehouseNeeds(level, villager, "minecraft:oak_fence", TARGET_FENCE_STOCK);
        boolean needFenceGates = warehouseNeeds(level, villager, "minecraft:oak_fence_gate", TARGET_FENCE_GATE_STOCK);
        boolean needDoors = warehouseNeeds(level, villager, "minecraft:oak_door", TARGET_DOOR_STOCK);
        boolean needBeds = warehouseNeeds(level, villager, "minecraft:white_bed", TARGET_BED_STOCK);

        int crafted = 0;

        // 如果要做木制加工件，但手上没有足够木板，允许先把原木加工成木板。
        // 这是中间材料，不要求仓库里也缺木板。
        int helperPlankRecipes = 0;
        while ((needStairs || needFences || needFenceGates || needDoors || needBeds)
                && SmartVillagerData.countItems(inventory, "minecraft:oak_planks") < requiredHelperPlanks(needStairs, needFences, needFenceGates, needDoors, needBeds)
                && helperPlankRecipes < MAX_PLANK_RECIPES_PER_WORK
                && SmartVillagerData.hasItem(inventory, "minecraft:oak_log")) {
            if (!canFullyAdd(inventory, new ItemStack(Items.OAK_PLANKS, 4))) {
                break;
            }

            SmartVillagerData.consumeOne(inventory, "minecraft:oak_log");
            addCraftedItem(inventory, new ItemStack(Items.OAK_PLANKS, 4));
            helperPlankRecipes++;
            crafted++;
        }

        int torchRecipes = 0;
        while (needTorches
                && torchRecipes < MAX_TORCH_RECIPES_PER_WORK
                && SmartVillagerData.hasItem(inventory, "minecraft:stick")
                && SmartVillagerData.hasItem(inventory, "minecraft:coal")) {
            if (!canFullyAdd(inventory, new ItemStack(Items.TORCH, 4))) {
                break;
            }

            SmartVillagerData.consumeOne(inventory, "minecraft:stick");
            SmartVillagerData.consumeOne(inventory, "minecraft:coal");
            addCraftedItem(inventory, new ItemStack(Items.TORCH, 4));
            torchRecipes++;
            crafted++;
        }

        int plankRecipes = 0;
        while (needPlanks
                && plankRecipes < MAX_PLANK_RECIPES_PER_WORK
                && SmartVillagerData.hasItem(inventory, "minecraft:oak_log")) {
            if (!canFullyAdd(inventory, new ItemStack(Items.OAK_PLANKS, 4))) {
                break;
            }

            SmartVillagerData.consumeOne(inventory, "minecraft:oak_log");
            addCraftedItem(inventory, new ItemStack(Items.OAK_PLANKS, 4));
            plankRecipes++;
            crafted++;
        }

        int stairRecipes = 0;
        while (needStairs
                && stairRecipes < MAX_STAIR_RECIPES_PER_WORK
                && SmartVillagerData.countItems(inventory, "minecraft:oak_planks") >= 6) {
            if (!canFullyAdd(inventory, new ItemStack(Items.OAK_STAIRS, 4))) {
                break;
            }

            consumeMany(inventory, "minecraft:oak_planks", 6);
            addCraftedItem(inventory, new ItemStack(Items.OAK_STAIRS, 4));
            stairRecipes++;
            crafted++;
        }

        int fenceRecipes = 0;
        while (needFences
                && fenceRecipes < MAX_FENCE_RECIPES_PER_WORK
                && SmartVillagerData.countItems(inventory, "minecraft:oak_planks") >= 2) {
            if (!canFullyAdd(inventory, new ItemStack(Items.OAK_FENCE, 3))) {
                break;
            }

            consumeMany(inventory, "minecraft:oak_planks", 2);
            addCraftedItem(inventory, new ItemStack(Items.OAK_FENCE, 3));
            fenceRecipes++;
            crafted++;
        }

        int gateRecipes = 0;
        while (needFenceGates
                && gateRecipes < MAX_FENCE_GATE_RECIPES_PER_WORK
                && SmartVillagerData.countItems(inventory, "minecraft:oak_planks") >= 2
                && SmartVillagerData.countItems(inventory, "minecraft:stick") >= 2) {
            if (!canFullyAdd(inventory, new ItemStack(Items.OAK_FENCE_GATE, 1))) {
                break;
            }

            consumeMany(inventory, "minecraft:oak_planks", 2);
            consumeMany(inventory, "minecraft:stick", 2);
            addCraftedItem(inventory, new ItemStack(Items.OAK_FENCE_GATE, 1));
            gateRecipes++;
            crafted++;
        }

        int doorRecipes = 0;
        while (needDoors
                && doorRecipes < MAX_DOOR_RECIPES_PER_WORK
                && SmartVillagerData.countItems(inventory, "minecraft:oak_planks") >= 6) {
            if (!canFullyAdd(inventory, new ItemStack(Items.OAK_DOOR, 3))) {
                break;
            }

            consumeMany(inventory, "minecraft:oak_planks", 6);
            addCraftedItem(inventory, new ItemStack(Items.OAK_DOOR, 3));
            doorRecipes++;
            crafted++;
        }

        int bedRecipes = 0;
        while (needBeds
                && bedRecipes < MAX_BED_RECIPES_PER_WORK
                && SmartVillagerData.countItems(inventory, "minecraft:oak_planks") >= 3
                && countAnyWool(inventory) >= 3) {
            if (!canFullyAdd(inventory, new ItemStack(Items.WHITE_BED, 1))) {
                break;
            }

            consumeMany(inventory, "minecraft:oak_planks", 3);
            consumeAnyWool(inventory, 3);
            addCraftedItem(inventory, new ItemStack(Items.WHITE_BED, 1));
            bedRecipes++;
            crafted++;
        }

        inventory.setChanged();

        if (crafted > 0) {
            addProductProgress(villager, countHandworkerProducts(inventory));
            villager.swing(InteractionHand.MAIN_HAND);
            level.playSound(
                    null,
                    villager.blockPosition(),
                    SoundEvents.WOOD_PLACE,
                    SoundSource.NEUTRAL,
                    0.5F,
                    1.3F
            );

            setState(villager, STATE_DEPOSIT_PRODUCTS);
        } else {
            SmartVillagerData.setStatus(villager, "合成失败", "没有足够原料或背包没有空间，回工坊等待");
            setState(villager, STATE_WAIT_AT_WORKSHOP);
        }
    }

    private static void tickDepositProducts(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        BlockPos warehouse = SmartVillagerData.getWarehouseCenter(villager);

        SmartVillagerData.setStatus(villager, "存入成品", "把手工业成品送回仓库");
        SmartVillagerData.setTargetPlace(villager, warehouse, "handworker_deposit_products");

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
                        new SmartVillagerData.ItemRequest("minecraft:torch", 999),
                        new SmartVillagerData.ItemRequest("minecraft:oak_planks", 999),
                        new SmartVillagerData.ItemRequest("minecraft:oak_stairs", 999),
                        new SmartVillagerData.ItemRequest("minecraft:oak_fence", 999),
                        new SmartVillagerData.ItemRequest("minecraft:oak_fence_gate", 999),
                        new SmartVillagerData.ItemRequest("minecraft:oak_door", 999),
                        new SmartVillagerData.ItemRequest("minecraft:white_bed", 999)
                ),
                SmartVillagerData.WAREHOUSE_RADIUS
        );

        setState(villager, STATE_WAIT_AT_WORKSHOP);
    }

    private static boolean needsProduction(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        return warehouseNeeds(level, villager, "minecraft:torch", TARGET_TORCH_STOCK)
                || warehouseNeeds(level, villager, "minecraft:oak_planks", TARGET_PLANK_STOCK)
                || warehouseNeeds(level, villager, "minecraft:oak_stairs", TARGET_STAIR_STOCK)
                || warehouseNeeds(level, villager, "minecraft:oak_fence", TARGET_FENCE_STOCK)
                || warehouseNeeds(level, villager, "minecraft:oak_fence_gate", TARGET_FENCE_GATE_STOCK)
                || warehouseNeeds(level, villager, "minecraft:oak_door", TARGET_DOOR_STOCK)
                || warehouseNeeds(level, villager, "minecraft:white_bed", TARGET_BED_STOCK);
    }

    private static boolean canCraftNeededProduct(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        SimpleContainer inventory = villager.getInventory();

        boolean needTorches = warehouseNeeds(level, villager, "minecraft:torch", TARGET_TORCH_STOCK);
        boolean needPlanks = warehouseNeeds(level, villager, "minecraft:oak_planks", TARGET_PLANK_STOCK);
        boolean needStairs = warehouseNeeds(level, villager, "minecraft:oak_stairs", TARGET_STAIR_STOCK);
        boolean needFences = warehouseNeeds(level, villager, "minecraft:oak_fence", TARGET_FENCE_STOCK);
        boolean needFenceGates = warehouseNeeds(level, villager, "minecraft:oak_fence_gate", TARGET_FENCE_GATE_STOCK);
        boolean needDoors = warehouseNeeds(level, villager, "minecraft:oak_door", TARGET_DOOR_STOCK);
        boolean needBeds = warehouseNeeds(level, villager, "minecraft:white_bed", TARGET_BED_STOCK);

        if (needTorches
                && SmartVillagerData.hasItem(inventory, "minecraft:stick")
                && SmartVillagerData.hasItem(inventory, "minecraft:coal")) {
            return true;
        }

        if (needPlanks && SmartVillagerData.hasItem(inventory, "minecraft:oak_log")) {
            return true;
        }

        if (needStairs) {
            if (SmartVillagerData.countItems(inventory, "minecraft:oak_planks") >= 6) {
                return true;
            }

            if (SmartVillagerData.hasItem(inventory, "minecraft:oak_log")) {
                return true;
            }
        }

        if (needFences) {
            if (SmartVillagerData.countItems(inventory, "minecraft:oak_planks") >= 2) {
                return true;
            }

            if (SmartVillagerData.hasItem(inventory, "minecraft:oak_log")) {
                return true;
            }
        }

        if (needFenceGates) {
            if (SmartVillagerData.countItems(inventory, "minecraft:oak_planks") >= 2
                    && SmartVillagerData.countItems(inventory, "minecraft:stick") >= 2) {
                return true;
            }

            if (SmartVillagerData.hasItem(inventory, "minecraft:oak_log")
                    && SmartVillagerData.countItems(inventory, "minecraft:stick") >= 2) {
                return true;
            }
        }

        if (needDoors) {
            if (SmartVillagerData.countItems(inventory, "minecraft:oak_planks") >= 6) {
                return true;
            }

            if (SmartVillagerData.hasItem(inventory, "minecraft:oak_log")) {
                return true;
            }
        }

        if (needBeds) {
            if (SmartVillagerData.countItems(inventory, "minecraft:oak_planks") >= 3
                    && countAnyWool(inventory) >= 3) {
                return true;
            }

            return SmartVillagerData.hasItem(inventory, "minecraft:oak_log")
                    && countAnyWool(inventory) >= 3;
        }

        return false;
    }

    private static boolean warehouseNeeds(
            net.minecraft.server.level.ServerLevel level,
            Villager villager,
            String itemName,
            int targetStock
    ) {
        return warehouseItemCount(level, villager, itemName) < targetStock;
    }

    private static int warehouseItemCount(
            net.minecraft.server.level.ServerLevel level,
            Villager villager,
            String itemName
    ) {
        BlockPos warehouse = SmartVillagerData.getWarehouseCenter(villager);
        return SmartVillagerData.countWarehouseItem(
                level,
                warehouse,
                itemName,
                SmartVillagerData.WAREHOUSE_RADIUS
        );
    }

    private static List<SmartVillagerData.ItemRequest> buildMaterialRequests(
            boolean needTorches,
            boolean needPlanks,
            boolean needStairs,
            boolean needFences,
            boolean needFenceGates,
            boolean needDoors,
            boolean needBeds
    ) {
        ArrayList<SmartVillagerData.ItemRequest> requests = new ArrayList<>();

        if (needTorches) {
            requests.add(new SmartVillagerData.ItemRequest("minecraft:stick", TARGET_STICKS));
            requests.add(new SmartVillagerData.ItemRequest("minecraft:coal", TARGET_COAL));
        }

        if (needFenceGates) {
            requests.add(new SmartVillagerData.ItemRequest("minecraft:stick", TARGET_STICKS));
        }

        if (needPlanks || needStairs || needFences || needFenceGates || needDoors || needBeds) {
            requests.add(new SmartVillagerData.ItemRequest("minecraft:oak_planks", TARGET_OAK_PLANKS));
            requests.add(new SmartVillagerData.ItemRequest("minecraft:oak_log", TARGET_OAK_LOGS));
        }

        if (needBeds) {
            for (String woolItem : WOOL_ITEMS) {
                requests.add(new SmartVillagerData.ItemRequest(woolItem, TARGET_WOOL));
            }
        }

        return requests;
    }

    private static void addProductProgress(Villager villager, int amount) {
        if (amount <= 0) {
            return;
        }

        int value = villager.getPersistentData().getInt(KEY_PRODUCTS_SINCE_DIARY).orElse(0) + amount;

        if (value >= 100) {
            SmartVillagerData.addDiary(villager, "我已经累计完成了 100 个手工制品。");
            value = 0;
        }

        villager.getPersistentData().putInt(KEY_PRODUCTS_SINCE_DIARY, value);
    }

    private static int countHandworkerProducts(SimpleContainer inventory) {
        return SmartVillagerData.countItems(inventory, "minecraft:torch")
                + SmartVillagerData.countItems(inventory, "minecraft:oak_planks")
                + SmartVillagerData.countItems(inventory, "minecraft:oak_stairs")
                + SmartVillagerData.countItems(inventory, "minecraft:oak_fence")
                + SmartVillagerData.countItems(inventory, "minecraft:oak_fence_gate")
                + SmartVillagerData.countItems(inventory, "minecraft:oak_door")
                + SmartVillagerData.countItems(inventory, "minecraft:white_bed");
    }

    private static int requiredHelperPlanks(
            boolean needStairs,
            boolean needFences,
            boolean needFenceGates,
            boolean needDoors,
            boolean needBeds
    ) {
        if (needStairs || needDoors) {
            return 6;
        }

        if (needFences || needFenceGates) {
            return 4;
        }

        if (needBeds) {
            return 3;
        }

        return 0;
    }

    private static int countAnyWool(SimpleContainer inventory) {
        int total = 0;

        for (String woolItem : WOOL_ITEMS) {
            total += SmartVillagerData.countItems(inventory, woolItem);
        }

        return total;
    }

    private static void consumeAnyWool(SimpleContainer inventory, int count) {
        int remaining = count;

        for (String woolItem : WOOL_ITEMS) {
            while (remaining > 0 && SmartVillagerData.hasItem(inventory, woolItem)) {
                SmartVillagerData.consumeOne(inventory, woolItem);
                remaining--;
            }

            if (remaining <= 0) {
                return;
            }
        }
    }

    private static boolean canFullyAdd(SimpleContainer inventory, ItemStack output) {
        int remaining = output.getCount();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);

            if (stack.isEmpty()) {
                remaining -= Math.min(remaining, output.getMaxStackSize());
            } else if (stack.getItem() == output.getItem()) {
                int maxSize = Math.min(stack.getMaxStackSize(), inventory.getMaxStackSize());
                int canAdd = maxSize - stack.getCount();

                if (canAdd > 0) {
                    remaining -= Math.min(remaining, canAdd);
                }
            }

            if (remaining <= 0) {
                return true;
            }
        }

        return false;
    }

    private static boolean addCraftedItem(SimpleContainer inventory, ItemStack output) {
        ItemStack remaining = inventory.addItem(output.copy());
        inventory.setChanged();
        return remaining.isEmpty();
    }

    private static void consumeMany(SimpleContainer inventory, String itemName, int count) {
        for (int i = 0; i < count; i++) {
            if (!SmartVillagerData.consumeOne(inventory, itemName)) {
                return;
            }
        }
    }

    private static BlockPos findNearestGoldBlock(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int dx = -WORKSHOP_SEARCH_RADIUS; dx <= WORKSHOP_SEARCH_RADIUS; dx++) {
            for (int dy = -4; dy <= 6; dy++) {
                for (int dz = -WORKSHOP_SEARCH_RADIUS; dz <= WORKSHOP_SEARCH_RADIUS; dz++) {
                    mPos.set(
                            villager.getBlockX() + dx,
                            villager.getBlockY() + dy,
                            villager.getBlockZ() + dz
                    );

                    if (!level.getBlockState(mPos).is(Blocks.GOLD_BLOCK)) {
                        continue;
                    }

                    double distance = villager.distanceToSqr(
                            mPos.getX() + 0.5D,
                            mPos.getY(),
                            mPos.getZ() + 0.5D
                    );

                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = mPos.immutable();
                    }
                }
            }
        }

        return best;
    }

    private static BlockPos findStandPosNear(
            net.minecraft.server.level.ServerLevel level,
            BlockPos workshop
    ) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = workshop.relative(direction);

            if (isStandable(level, candidate)) {
                return candidate;
            }
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

    private static void savePos(Villager villager, BlockPos pos) {
        villager.getPersistentData().putInt(KEY_WORKSHOP_X, pos.getX());
        villager.getPersistentData().putInt(KEY_WORKSHOP_Y, pos.getY());
        villager.getPersistentData().putInt(KEY_WORKSHOP_Z, pos.getZ());
    }

    private static BlockPos readWorkshop(Villager villager) {
        if (!villager.getPersistentData().contains(KEY_WORKSHOP_X)) {
            return null;
        }

        return new BlockPos(
                villager.getPersistentData().getInt(KEY_WORKSHOP_X).orElse(0),
                villager.getPersistentData().getInt(KEY_WORKSHOP_Y).orElse(0),
                villager.getPersistentData().getInt(KEY_WORKSHOP_Z).orElse(0)
        );
    }

    private static String getState(Villager villager) {
        return villager.getPersistentData().getString(KEY_STATE).orElse(STATE_SEEK_WORKSHOP);
    }

    private static void setState(Villager villager, String state) {
        villager.getPersistentData().putString(KEY_STATE, state);
    }
}