package com.aitown.aitownmod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 小镇级系统服务。
 *
 * 当前版本先用静态内存表保存房屋、交易、对话记录。
 * 优点是接入简单，方便验证房屋归属和社交闭环。
 * 缺点是退出世界后不会持久化。
 * 后续稳定后，应迁移到 SavedData。
 */
public class TownSystem {
    public static final int HOUSE_PRICE = 100;

    private static final int HOUSE_SCAN_RADIUS = 12;
    private static final int MAX_HOUSES_PER_TOWN = 128;
    private static final int MAX_TRADE_LOGS = 40;
    private static final int MAX_SOCIAL_LOGS = 40;

    private static final int SOCIAL_SEARCH_RADIUS = 16;
    private static final int SOCIAL_COOLDOWN_TICKS = 600;
    private static final int SOCIAL_DURATION_TICKS = 160;
    // 开聊距离放宽到 9.0（约 3 格），避免寻路停在三格外永远迈不进两格、从不触发聊天。
    private static final double SOCIAL_START_DISTANCE_SQR = 9.0D;

    private static final Map<String, ArrayList<HouseRecord>> HOUSES_BY_TOWN = new HashMap<>();
    private static final Map<String, ArrayList<String>> TRADE_LOGS_BY_TOWN = new HashMap<>();
    private static final Map<String, ArrayList<String>> SOCIAL_LOGS_BY_TOWN = new HashMap<>();

    public record BedSlot(BlockPos bedPos, String ownerUuid) {
    }

    public record HouseRecord(
            String houseId,
            BlockPos center,
            BlockPos door,
            ArrayList<BedSlot> beds,
            int price
    ) {
        public int bedCount() {
            return beds.size();
        }

        public int freeBedCount() {
            int count = 0;

            for (BedSlot bed : beds) {
                if (bed.ownerUuid() == null || bed.ownerUuid().isBlank()) {
                    count++;
                }
            }

            return count;
        }
    }

    public static String townKey(Villager villager) {
        return townKey(villager.level().dimension().toString(), SmartVillagerData.getTownCenter(villager));
    }

    public static String townKey(ServerLevel level, BlockPos townCenter) {
        return townKey(level.dimension().toString(), townCenter);
    }

    private static String townKey(String dimension, BlockPos townCenter) {
        return dimension + "@" + townCenter.getX() + "," + townCenter.getY() + "," + townCenter.getZ();
    }

    public static void registerHouseFromBuild(
            ServerLevel level,
            Villager builder,
            BlockPos buildOrigin
    ) {
        BlockPos townCenter = SmartVillagerData.getTownCenter(builder);
        String key = townKey(level, townCenter);
        ArrayList<HouseRecord> houses = HOUSES_BY_TOWN.computeIfAbsent(key, ignored -> new ArrayList<>());

        if (houses.size() >= MAX_HOUSES_PER_TOWN) {
            SmartVillagerData.addDiary(builder, "小镇房屋登记已满，这栋房子暂时没有登记。");
            return;
        }

        if (isNearExistingHouse(houses, buildOrigin)) {
            return;
        }

        BlockPos door = findNearestDoor(level, buildOrigin, HOUSE_SCAN_RADIUS);
        ArrayList<BlockPos> beds = findBeds(level, buildOrigin, HOUSE_SCAN_RADIUS);

        if (door == null || beds.isEmpty()) {
            SmartVillagerData.addDiary(builder, "我完成了一栋建筑，但小镇系统没有找到门或床，所以暂时没有登记为住宅。");
            return;
        }

        // 当前只保留最多 3 个床位，方便观察一床、两床、三床房屋。
        while (beds.size() > 3) {
            beds.remove(beds.size() - 1);
        }

        ArrayList<BedSlot> bedSlots = new ArrayList<>();

        for (BlockPos bed : beds) {
            bedSlots.add(new BedSlot(bed, ""));
        }

        String houseId = "H-" + Math.abs((buildOrigin.asLong() + level.getGameTime()) % 1000000L);
        HouseRecord house = new HouseRecord(houseId, buildOrigin, door, bedSlots, HOUSE_PRICE);
        houses.add(house);

        addTradeLog(level, townCenter, "小镇系统 登记房屋 " + houseId + "，床位 x" + house.bedCount() + "，价格 " + HOUSE_PRICE);
        SmartVillagerData.addDiary(builder, "我完成的房子已经被小镇系统登记为 " + houseId + "，共有 " + house.bedCount() + " 个床位。");
    }

    public static boolean tryBuyHouseForResident(ServerLevel level, Villager resident) {
        if (SmartVillagerData.hasOwnedHome(resident)) {
            return true;
        }

        BlockPos townCenter = SmartVillagerData.getTownCenter(resident);
        String key = townKey(level, townCenter);
        ArrayList<HouseRecord> houses = HOUSES_BY_TOWN.computeIfAbsent(key, ignored -> new ArrayList<>());

        for (HouseRecord house : houses) {
            for (int b = 0; b < house.beds().size(); b++) {
                BedSlot bed = house.beds().get(b);

                if (bed.ownerUuid() != null && !bed.ownerUuid().isBlank()) {
                    continue;
                }

                if (!SmartVillagerData.spendCoins(resident, house.price())) {
                    SmartVillagerData.setStatus(resident, "想买房", "金币不足，需要 " + house.price() + " 金币");
                    return false;
                }

                String ownerUuid = resident.getUUID().toString();
                house.beds().set(b, new BedSlot(bed.bedPos(), ownerUuid));
                SmartVillagerData.setOwnedHome(resident, house.houseId(), bed.bedPos());
                SmartVillagerData.addDiary(resident, "我花了 " + house.price() + " 金币，买下了房子 " + house.houseId() + " 的一个床位。");
                addTradeLog(level, townCenter, SmartVillagerData.getCitizenName(resident) + " 购买房屋 " + house.houseId() + " 的床位，支付 " + house.price() + " 金币");
                return true;
            }
        }

        SmartVillagerData.setStatus(resident, "想买房", "小镇里还没有空床位");
        return false;
    }

    /**
     * 全角色共用的"生活需求"流水线，优先级 HOME > HUNGER > MOOD。
     *
     * 返回 true 表示本 tick 已被生活需求占用，调用方（各职业 Handler）应立即 return，
     * 不再执行本职业的工作状态机。
     *
     * 为避免新镇"人人都要先买房却没人干活"的死锁：镇里没有空床位、或村民暂时
     * 买不起时，买房与买苹果都不会阻塞——先去工作赚钱，等条件具备再消费。
     */
    public static boolean tickLifeNeeds(ServerLevel level, Villager villager) {
        // 1) HOME：没有自己的床位就尝试买房。
        if (!SmartVillagerData.hasOwnedHome(villager) && pursueHome(level, villager)) {
            return true;
        }

        // 2) HUNGER：饿了就花金币向小镇买苹果吃。
        SmartVillagerData.tickHunger(villager);
        if (SmartVillagerData.tryHandleHunger(level, villager)) {
            return true;
        }

        // 3) MOOD：正在聊天就把这次聊完；否则心情低落时主动找人聊天。
        if (SmartVillagerData.isTalking(villager)) {
            return tickConversation(level, villager, false);
        }
        SmartVillagerData.tickMood(villager);
        if (SmartVillagerData.getMood(villager) <= SmartVillagerData.MOOD_TALK_THRESHOLD) {
            return tryStartTalk(level, villager);
        }

        return false;
    }

    /**
     * 通用买房行为。返回 true 表示本 tick 用于买房（占用）。
     * 没有空床位或买不起时返回 false，让村民先去工作赚钱，避免死锁。
     */
    private static boolean pursueHome(ServerLevel level, Villager villager) {
        if (!townHasFreeBed(level, villager)) {
            return false;
        }

        if (SmartVillagerData.getCoins(villager) < HOUSE_PRICE) {
            SmartVillagerData.setStatus(villager, "攒钱买房", "想买房，金币还差一些（需要 " + HOUSE_PRICE + "）");
            return false;
        }

        BlockPos warehouse = SmartVillagerData.getWarehouseCenter(villager);
        SmartVillagerData.setStatus(villager, "前往购房", "先走到仓库旁，再买一个空床位");
        SmartVillagerData.setTargetPlace(villager, warehouse, "buy_house");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_STORAGE,
                SmartVillagerData.SPEED_SLOW
        );

        if (!arrived) {
            return true;
        }

        tryBuyHouseForResident(level, villager);
        return true;
    }

    /** 镇里是否还有空床位可买。 */
    public static boolean townHasFreeBed(ServerLevel level, Villager villager) {
        BlockPos townCenter = SmartVillagerData.getTownCenter(villager);
        ArrayList<HouseRecord> houses = HOUSES_BY_TOWN.get(townKey(level, townCenter));

        if (houses == null) {
            return false;
        }

        for (HouseRecord house : houses) {
            if (house.freeBedCount() > 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * 心情低落时主动找人聊天（任何角色都可发起，不再限定居民）。
     * 返回 true 表示本 tick 用于社交（正在接近对方或已开始聊天）。
     */
    public static boolean tryStartTalk(ServerLevel level, Villager villager) {
        if (!canStartConversation(villager)) {
            return false;
        }

        UUID targetUuid = SmartVillagerData.getTalkTargetUuid(villager);
        Villager target = null;

        if (targetUuid != null && level.getEntity(targetUuid) instanceof Villager storedTarget) {
            target = storedTarget;
        }

        if (target == null || !canReceiveConversation(villager, target)) {
            SmartVillagerData.clearTalkTarget(villager);
            target = findConversationTarget(level, villager);

            if (target == null) {
                return false;
            }

            SmartVillagerData.setTalkTarget(villager, target);
        }

        SmartVillagerData.setStatus(villager, "寻找聊天", "心情低落，想去找 " + SmartVillagerData.getCitizenName(target) + " 聊天");
        SmartVillagerData.setTargetPlace(villager, target.blockPosition(), "social_approach");
        villager.getLookControl().setLookAt(target, 30.0F, 30.0F);

        // 走到对方身边（到达判定收紧到 PLACE_CLOSE，约 2 格）。
        // 只要寻路判定“已到达”、或已进入开聊距离，就立即开始聊天，
        // 避免卡在三格外永远聊不起来。
        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_CLOSE,
                SmartVillagerData.SPEED_NORMAL
        );

        if (!arrived && villager.distanceToSqr(target) > SOCIAL_START_DISTANCE_SQR) {
            return true;
        }

        startConversation(level, villager, target, createResidentTopic(villager));
        return true;
    }

    /**
     * 通用交流覆盖状态。
     *
     * 返回 true 表示当前职业状态机应该暂停。
     * hasUrgentWork 为 true 时，会主动结束对话并允许职业状态机继续执行。
     */
    public static boolean tickConversation(ServerLevel level, Villager villager, boolean hasUrgentWork) {
        if (!SmartVillagerData.isTalking(villager)) {
            return false;
        }

        UUID otherUuid = SmartVillagerData.getTalkingWithUuid(villager);

        if (otherUuid == null || !(level.getEntity(otherUuid) instanceof Villager other) || !other.isAlive()) {
            SmartVillagerData.clearTalkBubble(level, villager);
            SmartVillagerData.clearTalking(villager);
            SmartVillagerData.markTalkedNow(villager);
            return false;
        }

        villager.getPersistentData().putString(SmartVillagerData.KEY_STATUS, "正在交流");
        villager.getPersistentData().putString(SmartVillagerData.KEY_TASK, "正在和 " + SmartVillagerData.getCitizenName(other) + " 聊天");
        SmartVillagerData.updateTalkBubble(level, villager);
        SmartVillagerData.updateTalkBubble(level, other);

        if (hasUrgentWork) {
            endConversation(level, villager, "我要先去工作了，再聊。", true);
            return false;
        }

        villager.getNavigation().stop();
        villager.getLookControl().setLookAt(other, 30.0F, 30.0F);

        if (villager.tickCount >= SmartVillagerData.getTalkEndTick(villager)) {
            finishConversation(level, villager, other);
            return false;
        }

        return true;
    }

    /**
     * 兼容旧调用：第二版里只有居民主动发起对话。
     * 工种 idle / wait 状态可以被居民找上门聊天，但不再自己主动找人。
     */
    public static void tryIdleTalk(ServerLevel level, Villager speaker, String topic) {
        // 任何空闲村民都可以顺手找人聊天（受冷却与就近目标限制）。
        // topic 参数保留以兼容旧调用，实际话题由 createResidentTopic 统一生成。
        tryStartTalk(level, speaker);
    }

    private static void startConversation(ServerLevel level, Villager initiator, Villager target, String topic) {
        int endTick = Math.max(initiator.tickCount, target.tickCount) + SOCIAL_DURATION_TICKS;
        String line = SmartVillagerData.getCitizenName(initiator)
                + "找到"
                + SmartVillagerData.getCitizenName(target)
                + "聊天："
                + topic;

        String targetReply = createReplyTopic(target, initiator);

        SmartVillagerData.startTalking(initiator, target, endTick, topic);
        SmartVillagerData.startTalking(target, initiator, endTick, targetReply);
        SmartVillagerData.addMood(initiator, SmartVillagerData.MOOD_TALK_RECOVER);
        SmartVillagerData.addMood(target, SmartVillagerData.MOOD_TALK_RECOVER);
        SmartVillagerData.showTalkBubble(level, initiator, topic);
        SmartVillagerData.showTalkBubble(level, target, targetReply);
        initiator.getPersistentData().putString(SmartVillagerData.KEY_STATUS, "正在交流");
        initiator.getPersistentData().putString(SmartVillagerData.KEY_TASK, "我主动找 " + SmartVillagerData.getCitizenName(target) + " 聊天");
        target.getPersistentData().putString(SmartVillagerData.KEY_STATUS, "正在交流");
        target.getPersistentData().putString(SmartVillagerData.KEY_TASK, SmartVillagerData.getCitizenName(initiator) + " 来找我聊天");
        SmartVillagerData.addDiary(initiator, "我去找" + SmartVillagerData.getCitizenName(target) + "聊天。我说：" + topic + " 对方回应：" + targetReply);
        SmartVillagerData.addDiary(target, SmartVillagerData.getCitizenName(initiator) + "来找我聊天，说：" + topic + " 我回应：" + targetReply);
        addSocialLog(level, SmartVillagerData.getTownCenter(initiator), line);
    }

    private static void finishConversation(ServerLevel level, Villager a, Villager b) {
        if (!SmartVillagerData.isTalking(a) && !SmartVillagerData.isTalking(b)) {
            return;
        }

        String topic = SmartVillagerData.getTalkTopic(a);

        SmartVillagerData.addDiary(a, "我和" + SmartVillagerData.getCitizenName(b) + "聊完了。我们刚才聊到：" + topic);
        SmartVillagerData.addDiary(b, "我和" + SmartVillagerData.getCitizenName(a) + "聊完了。我们刚才聊到：" + topic);
        SmartVillagerData.addRelation(a, b, 1);
        SmartVillagerData.addRelation(b, a, 1);
        SmartVillagerData.markTalkedNow(a);
        SmartVillagerData.markTalkedNow(b);
        SmartVillagerData.clearTalkBubble(level, a);
        SmartVillagerData.clearTalkBubble(level, b);
        SmartVillagerData.clearTalking(a);
        SmartVillagerData.clearTalking(b);
        addSocialLog(level, SmartVillagerData.getTownCenter(a), SmartVillagerData.getCitizenName(a) + "和" + SmartVillagerData.getCitizenName(b) + "结束了一次聊天。");
    }

    public static void endConversation(ServerLevel level, Villager who, String reason, boolean writeDiary) {
        if (!SmartVillagerData.isTalking(who)) {
            return;
        }

        UUID otherUuid = SmartVillagerData.getTalkingWithUuid(who);
        Villager other = null;

        if (otherUuid != null && level.getEntity(otherUuid) instanceof Villager otherVillager) {
            other = otherVillager;
        }

        if (writeDiary) {
            SmartVillagerData.addDiary(who, "我结束了聊天，因为：" + reason);
        }

        SmartVillagerData.markTalkedNow(who);
        SmartVillagerData.clearTalkBubble(level, who);
        SmartVillagerData.clearTalking(who);

        if (other != null) {
            if (writeDiary) {
                SmartVillagerData.addDiary(other, SmartVillagerData.getCitizenName(who) + "结束了聊天，说：" + reason);
            }

            SmartVillagerData.markTalkedNow(other);
            SmartVillagerData.clearTalkBubble(level, other);
            SmartVillagerData.clearTalking(other);
        }

        addSocialLog(level, SmartVillagerData.getTownCenter(who), SmartVillagerData.getCitizenName(who) + "结束聊天：" + reason);
    }

    private static String createReplyTopic(Villager speaker, Villager listener) {
        String task = SmartVillagerData.getTask(speaker);

        if (task != null && !task.isBlank() && !"无任务".equals(task)) {
            return "我刚才在忙：" + task + "。等会儿还要继续看看小镇里的事情。";
        }

        if (SmartVillagerData.isRole(speaker, SmartVillagerData.ROLE_RESIDENT)
                && !SmartVillagerData.hasOwnedHome(speaker)) {
            return "我也还在找自己的房子，希望小镇里能早点有空床位。";
        }

        return "我也在小镇里转了转，想看看大家最近都在忙什么。";
    }

    private static Villager findConversationTarget(ServerLevel level, Villager seeker) {
        AABB box = seeker.getBoundingBox().inflate(SOCIAL_SEARCH_RADIUS, 4.0D, SOCIAL_SEARCH_RADIUS);
        List<Villager> candidates = level.getEntitiesOfClass(
                Villager.class,
                box,
                other -> other != seeker
                        && other.isAlive()
                        && canReceiveConversation(seeker, other)
        );

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator.comparingDouble(seeker::distanceToSqr));
        return candidates.get(0);
    }

    private static boolean canReceiveConversation(Villager seeker, Villager other) {
        if (SmartVillagerData.ROLE_NONE.equals(SmartVillagerData.getRole(other))) {
            return false;
        }

        if (SmartVillagerData.isTalking(other)) {
            return false;
        }

        if (!canStartConversation(other)) {
            return false;
        }

        return townKey(seeker).equals(townKey(other));
    }

    private static boolean canStartConversation(Villager villager) {
        if (SmartVillagerData.isTalking(villager)) {
            return false;
        }

        int lastTalkTick = villager.getPersistentData().getInt(SmartVillagerData.KEY_LAST_TALK_TICK).orElse(-999999);
        return villager.tickCount - lastTalkTick >= SOCIAL_COOLDOWN_TICKS;
    }

    private static String createResidentTopic(Villager resident) {
        if (!SmartVillagerData.hasOwnedHome(resident)) {
            return "我还在找自己的房子，希望小镇里能有一个空床位。";
        }

        if (SmartVillagerData.getHunger(resident) > 50) {
            return "我有点饿了，等会儿可能要去仓库找苹果。";
        }

        return "我今天在小镇里转了转，也想知道大家最近在忙什么。";
    }

    private static boolean isNearExistingHouse(ArrayList<HouseRecord> houses, BlockPos origin) {
        for (HouseRecord house : houses) {
            if (house.center().distSqr(origin) <= 36.0D) {
                return true;
            }
        }

        return false;
    }

    private static BlockPos findNearestDoor(ServerLevel level, BlockPos center, int radius) {
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 6; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    mPos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = level.getBlockState(mPos);

                    if (!(state.getBlock() instanceof DoorBlock)) {
                        continue;
                    }

                    double distance = mPos.distSqr(center);

                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = mPos.immutable();
                    }
                }
            }
        }

        return best;
    }

    private static ArrayList<BlockPos> findBeds(ServerLevel level, BlockPos center, int radius) {
        ArrayList<BlockPos> beds = new ArrayList<>();
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 6; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    mPos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = level.getBlockState(mPos);

                    if (!(state.getBlock() instanceof BedBlock)) {
                        continue;
                    }

                    // 只登记床脚，避免一张床被头部和脚部重复登记成两个床位。
                    if (state.hasProperty(BedBlock.PART)
                            && state.getValue(BedBlock.PART) == BedPart.HEAD) {
                        continue;
                    }

                    beds.add(mPos.immutable());
                }
            }
        }

        beds.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        return beds;
    }

    public static String getHouseInfo(ServerLevel level, BlockPos townCenter) {
        ArrayList<HouseRecord> houses = HOUSES_BY_TOWN.getOrDefault(townKey(level, townCenter), new ArrayList<>());

        if (houses.isEmpty()) {
            return "暂无房屋登记。";
        }

        StringBuilder builder = new StringBuilder();

        for (HouseRecord house : houses) {
            if (builder.length() > 0) {
                builder.append("\n");
            }

            builder.append(house.houseId())
                    .append(" center=").append(posText(house.center()))
                    .append(" door=").append(posText(house.door()))
                    .append(" price=").append(house.price())
                    .append(" beds=").append(house.freeBedCount()).append("/").append(house.bedCount()).append(" 空闲");
        }

        return builder.toString();
    }

    public static String getTradeInfo(ServerLevel level, BlockPos townCenter) {
        ArrayList<String> logs = TRADE_LOGS_BY_TOWN.getOrDefault(townKey(level, townCenter), new ArrayList<>());
        return joinedOrEmpty(logs, "暂无交易记录。");
    }

    public static String getSocialInfo(ServerLevel level, BlockPos townCenter) {
        ArrayList<String> logs = SOCIAL_LOGS_BY_TOWN.getOrDefault(townKey(level, townCenter), new ArrayList<>());
        return joinedOrEmpty(logs, "暂无社交记录。");
    }

    public static void showHouseInfo(ServerLevel level, Player player, BlockPos townCenter) {
        player.sendSystemMessage(Component.literal("§6========== 小镇房屋信息 =========="));
        sendMultiline(player, getHouseInfo(level, townCenter));
        player.sendSystemMessage(Component.literal("§6================================"));
    }

    public static void showTradeInfo(ServerLevel level, Player player, BlockPos townCenter) {
        player.sendSystemMessage(Component.literal("§6========== 小镇交易信息 =========="));
        sendMultiline(player, getTradeInfo(level, townCenter));
        player.sendSystemMessage(Component.literal("§e最近社交："));
        sendMultiline(player, getSocialInfo(level, townCenter));
        player.sendSystemMessage(Component.literal("§6================================"));
    }

    public static void addTradeLog(ServerLevel level, BlockPos townCenter, String line) {
        addLog(TRADE_LOGS_BY_TOWN, townKey(level, townCenter), "T" + level.getGameTime() + " " + line, MAX_TRADE_LOGS);
    }

    public static void addSocialLog(ServerLevel level, BlockPos townCenter, String line) {
        addLog(SOCIAL_LOGS_BY_TOWN, townKey(level, townCenter), "T" + level.getGameTime() + " " + line, MAX_SOCIAL_LOGS);
    }

    private static void addLog(Map<String, ArrayList<String>> map, String key, String line, int maxLines) {
        ArrayList<String> logs = map.computeIfAbsent(key, ignored -> new ArrayList<>());
        logs.add(0, line);

        while (logs.size() > maxLines) {
            logs.remove(logs.size() - 1);
        }
    }

    private static String joinedOrEmpty(ArrayList<String> logs, String emptyText) {
        if (logs.isEmpty()) {
            return emptyText;
        }

        StringBuilder builder = new StringBuilder();
        int shown = 0;

        for (String log : logs) {
            if (builder.length() > 0) {
                builder.append("\n");
            }

            builder.append(stripTick(log));
            shown++;

            if (shown >= 20) {
                break;
            }
        }

        return builder.toString();
    }

    private static String stripTick(String line) {
        if (line == null || !line.startsWith("T")) {
            return line;
        }

        int space = line.indexOf(' ');
        if (space <= 0 || space >= line.length() - 1) {
            return line;
        }

        return line.substring(space + 1);
    }

    private static void sendMultiline(Player player, String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        String[] lines = text.split("\\n");

        for (String line : lines) {
            player.sendSystemMessage(Component.literal("§7- " + line));
        }
    }

    private static String posText(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}