package com.aitown.aitownmod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * 职业行为状态机的公共助手。
 *
 * 所有职业 Handler（矿工、伐木工、牧羊人、农夫、手工业者、建筑师、居民）都共用同一套
 * “状态字符串 + 坐标”持久化样板。过去每个类都各写一份 getState/setState/savePos/readPos，
 * 现统一收敛到这里，避免重复代码，便于以后统一调整存储格式。
 *
 * 约定：
 * - 状态用一个字符串键存储；首次读取（无记录）时回退到各职业自己的默认状态。
 * - 坐标用 x/y/z 三个独立 int 键存储；由于始终成对写入，读取时只需判断 x 键是否存在。
 */
public final class VillagerJobHelper {

    private VillagerJobHelper() {
    }

    /** 读取职业状态；首次调用（无记录）时返回 defaultState。 */
    public static String getState(Villager villager, String stateKey, String defaultState) {
        return villager.getPersistentData().getString(stateKey).orElse(defaultState);
    }

    /** 写入职业状态。 */
    public static void setState(Villager villager, String stateKey, String state) {
        villager.getPersistentData().putString(stateKey, state);
    }

    /** 把一个坐标按 x/y/z 三个键存入村民持久化数据。 */
    public static void savePos(Villager villager, String keyX, String keyY, String keyZ, BlockPos pos) {
        villager.getPersistentData().putInt(keyX, pos.getX());
        villager.getPersistentData().putInt(keyY, pos.getY());
        villager.getPersistentData().putInt(keyZ, pos.getZ());
    }

    /** 读取由 savePos 存入的坐标；坐标缺失时返回 null。 */
    public static BlockPos readPos(Villager villager, String keyX, String keyY, String keyZ) {
        if (!villager.getPersistentData().contains(keyX)) {
            return null;
        }
        return new BlockPos(
                villager.getPersistentData().getInt(keyX).orElse(0),
                villager.getPersistentData().getInt(keyY).orElse(0),
                villager.getPersistentData().getInt(keyZ).orElse(0)
        );
    }

    /** 移除由 savePos 存入的坐标。 */
    public static void clearPos(Villager villager, String keyX, String keyY, String keyZ) {
        villager.getPersistentData().remove(keyX);
        villager.getPersistentData().remove(keyY);
        villager.getPersistentData().remove(keyZ);
    }

    /**
     * 职业 tick 的公共前置守卫。
     *
     * 依次检查：仅服务端、实体是 Villager、角色匹配、运行在 ServerLevel；
     * 通过后补齐身份并抑制原版移动。
     *
     * @return 可继续处理时返回该村民所在的 ServerLevel；应跳过本 tick 时返回 null。
     *           调用方拿到非 null 后，可安全地将 event.getEntity() 强转为 Villager。
     */
    public static ServerLevel beginJobTick(EntityTickEvent.Pre event, String role) {
        if (event.getEntity().level().isClientSide()) {
            return null;
        }
        if (!(event.getEntity() instanceof Villager villager)) {
            return null;
        }
        if (!SmartVillagerData.isRole(villager, role)) {
            return null;
        }
        if (!(villager.level() instanceof ServerLevel level)) {
            return null;
        }
        SmartVillagerData.ensureIdentity(villager);
        SmartVillagerData.suppressVanillaMovement(villager);
        return level;
    }

    /**
     * 累计工作量并在达到阈值时写一条日记，随后计数归零。
     *
     * 所有职业的“工作 N 件后记一笔日记”均走这里，只是 key/阈值/文案不同。
     */
    public static void addDiaryProgress(
            Villager villager,
            String counterKey,
            int amount,
            int threshold,
            String diaryLine
    ) {
        if (amount <= 0) {
            return;
        }
        int value = villager.getPersistentData().getInt(counterKey).orElse(0) + amount;
        if (value >= threshold) {
            SmartVillagerData.addDiary(villager, diaryLine);
            value = 0;
        }
        villager.getPersistentData().putInt(counterKey, value);
    }
}
