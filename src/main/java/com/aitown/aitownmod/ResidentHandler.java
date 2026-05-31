package com.aitown.aitownmod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.npc.villager.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * 居民 / 无业游民行为。
 *
 * 买房、饥饿、社交（心情）等"生活需求"已统一交给 TownSystem.tickLifeNeeds
 * （优先级 HOME > HUNGER > MOOD），所有角色共用同一套逻辑。
 * 这里只保留居民独有的剩余行为：在镇里生活、偶尔回家休息。
 */
@EventBusSubscriber(modid = aitown.MODID)
public class ResidentHandler {
    private static final String KEY_STATE = "ResidentState";
    private static final String STATE_IDLE = "idle";
    private static final String STATE_GO_HOME = "go_home";

    private static final int REST_CHECK_INTERVAL = 200;

    @SubscribeEvent
    public static void onVillagerTick(EntityTickEvent.Pre event) {
        net.minecraft.server.level.ServerLevel level =
                VillagerJobHelper.beginJobTick(event, SmartVillagerData.ROLE_RESIDENT);
        if (level == null) {
            return;
        }
        Villager villager = (Villager) event.getEntity();

        // HOME > HUNGER > MOOD 的生活需求统一在这里处理。
        if (TownSystem.tickLifeNeeds(level, villager)) {
            return;
        }

        if (!SmartVillagerData.shouldThink(villager, 20)) {
            return;
        }

        if (STATE_GO_HOME.equals(getState(villager))) {
            tickGoHome(level, villager);
            return;
        }

        tickIdle(level, villager);
    }

    private static void tickIdle(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        SmartVillagerData.setStatus(villager, "生活待命", "在小镇里生活，偶尔回家休息");

        if (SmartVillagerData.hasOwnedHome(villager)
                && SmartVillagerData.shouldThink(villager, REST_CHECK_INTERVAL)
                && shouldGoHome(villager)) {
            setState(villager, STATE_GO_HOME);
        }
    }

    private static void tickGoHome(
            net.minecraft.server.level.ServerLevel level,
            Villager villager
    ) {
        if (!SmartVillagerData.hasOwnedHome(villager)) {
            setState(villager, STATE_IDLE);
            return;
        }

        BlockPos bed = SmartVillagerData.getOwnedBedPos(villager);
        SmartVillagerData.setStatus(villager, "回家休息", "走向自己的床位 " + SmartVillagerData.getOwnedHomeText(villager));
        SmartVillagerData.setTargetPlace(villager, bed, "resident_go_home");

        boolean arrived = SmartVillagerData.moveToTargetPlace(
                villager,
                SmartVillagerData.PLACE_WORK,
                SmartVillagerData.SPEED_SLOW
        );

        if (arrived) {
            SmartVillagerData.addDiary(villager, "我回到了自己的床位旁边休息。我的住所是 " + SmartVillagerData.getOwnedHomeText(villager));
            setState(villager, STATE_IDLE);
        }
    }

    private static boolean shouldGoHome(Villager villager) {
        // 当前先用轻量规则：偶尔想回家。
        // 后续可以改成夜晚、疲劳、心情、危险等因素共同驱动。
        return villager.getRandom().nextInt(8) == 0;
    }

    // 状态样板统一委托给 VillagerJobHelper。
    private static String getState(Villager villager) {
        return VillagerJobHelper.getState(villager, KEY_STATE, STATE_IDLE);
    }

    private static void setState(Villager villager, String state) {
        VillagerJobHelper.setState(villager, KEY_STATE, state);
    }
}
